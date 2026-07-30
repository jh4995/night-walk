from __future__ import annotations

import argparse
import json
import logging
import os
import platform
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Optional, Sequence


_PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT_ROOT = _PROJECT_ROOT / "outputs"
DEFAULT_LOG_ROOT = _PROJECT_ROOT / "outputs" / "logs"


@dataclass
class RunPaths:
    run_ts: str
    stage: str
    script_stem: str
    out_dir: Path
    log_dir: Path
    cmd_path: Optional[Path]
    log_path: Optional[Path]
    outputs_enabled: bool
    cmdlog_enabled: bool


def now_run_ts() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def safe_mkdir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def _run_git_cmd(args: Sequence[str]) -> Optional[str]:
    try:
        proc = subprocess.run(
            ["git", *args],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        return proc.stdout.strip()
    except (FileNotFoundError, subprocess.CalledProcessError):
        return None


def get_git_commit() -> Optional[str]:
    return _run_git_cmd(["rev-parse", "HEAD"])


def get_git_dirty() -> Optional[bool]:
    inside = _run_git_cmd(["rev-parse", "--is-inside-work-tree"])
    if inside != "true":
        return None
    status = _run_git_cmd(["status", "--porcelain"])
    if status is None:
        return None
    return bool(status)


def save_cmd(log_dir: Path, script_stem: str, run_ts: str, argv: Sequence[str]) -> Path:
    safe_mkdir(log_dir)
    cmd_path = log_dir / f"{script_stem}_{run_ts}.cmd.txt"
    with cmd_path.open("w", encoding="utf-8") as f:
        f.write(" ".join(argv) + "\n")
    return cmd_path


def setup_logger(log_dir: Path, script_stem: str, run_ts: str, level: str) -> Path:
    log_path = log_dir / f"{script_stem}_{run_ts}.log"
    logger = logging.getLogger()
    logger.setLevel(getattr(logging, str(level).upper(), logging.INFO))
    formatter = logging.Formatter(
        "%(asctime)s | %(levelname)s | %(name)s | %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    for handler in list(logger.handlers):
        logger.removeHandler(handler)
        handler.close()

    stream_handler = logging.StreamHandler(sys.stdout)
    stream_handler.setFormatter(formatter)
    safe_mkdir(log_dir)
    file_handler = logging.FileHandler(log_path, encoding="utf-8")
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)
    logger.addHandler(stream_handler)
    return log_path


def setup_stream_logger(level: str) -> None:
    logger = logging.getLogger()
    logger.setLevel(getattr(logging, str(level).upper(), logging.INFO))
    formatter = logging.Formatter(
        "%(asctime)s | %(levelname)s | %(name)s | %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    for handler in list(logger.handlers):
        logger.removeHandler(handler)
        handler.close()

    stream_handler = logging.StreamHandler(sys.stdout)
    stream_handler.setFormatter(formatter)
    logger.addHandler(stream_handler)


def ensure_utf8_console() -> None:
    """Windows 기본 콘솔(cp949)에서 한글/기호 출력 시 UnicodeEncodeError로 런이
    죽는 것을 막는다. 파일 핸들러는 이미 utf-8이므로 콘솔만 대상."""
    for stream in (sys.stdout, sys.stderr):
        try:
            if (getattr(stream, "encoding", "") or "").lower().replace("-", "") != "utf8":
                stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError, ValueError):
            pass


def _env_truthy(name: str) -> bool:
    val = os.environ.get(name, "")
    return str(val).strip().lower() in {"1", "true", "yes", "on"}


def _dir_has_content(path: Path) -> bool:
    return path.exists() and any(path.iterdir())


def _resolve_out_dir(out_root: Path, stage: str, run_ts: str, out_base: str) -> Path:
    out_dir = out_root / stage / run_ts
    if out_base:
        out_dir = out_dir / str(out_base)
    return out_dir


def unique_run_ts(out_root: Path, stage: str, run_ts: str, out_base: str = "") -> tuple[str, bool]:
    """run_ts가 이미 쓰인 디렉토리를 가리키면 접미사를 붙여 비켜 간다.

    run_ts는 초 단위라, 같은 초에 두 번 실행하면 앞 측정을 덮어쓴다.
    **측정치가 조용히 사라지는 것이 이 하네스에서 가장 나쁜 실패다** — 덮어쓰기보다
    run_ts를 바꾸는 쪽이 낫다. 두 번째 반환값은 충돌이 있었는지 여부.
    """
    if not _dir_has_content(_resolve_out_dir(out_root, stage, run_ts, out_base)):
        return run_ts, False
    for n in range(2, 1000):
        cand = f"{run_ts}_{n}"
        if not _dir_has_content(_resolve_out_dir(out_root, stage, cand, out_base)):
            return cand, True
    raise RuntimeError(f"run_ts 충돌을 해소하지 못했다: {out_root / stage / run_ts}")


def dump_run_meta(out_dir: Path, meta_dict: dict) -> Path:
    safe_mkdir(out_dir)
    meta_path = out_dir / "run_meta.json"
    with meta_path.open("w", encoding="utf-8") as f:
        json.dump(meta_dict, f, indent=2, sort_keys=True)
        f.write("\n")
    return meta_path


def common_argparser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run_ts", default="", help="Run timestamp; default auto-generated YYYYMMDD_HHMMSS")
    parser.add_argument("--out_root", default=str(DEFAULT_OUT_ROOT))
    parser.add_argument("--log_root", default=str(DEFAULT_LOG_ROOT))
    parser.add_argument("--out_base", default="", help="Optional output tag")
    parser.add_argument("--log_level", default="INFO")
    parser.add_argument("--dump_env", action="store_true", help="Include a full os.environ snapshot in run_meta.json")
    parser.add_argument("--no_cmdlog", action="store_true", default=False, help="Disable cmd/log file saving.")
    parser.add_argument("--no_outputs", action="store_true", default=False, help="Disable all output artifact saving.")
    return parser


def init_run(stage: str, script_file: str, args: argparse.Namespace) -> RunPaths:
    ensure_utf8_console()
    run_ts = args.run_ts.strip() if getattr(args, "run_ts", "") else now_run_ts()
    script_stem = Path(script_file).stem
    out_root = Path(args.out_root)
    log_root = Path(args.log_root)
    no_outputs = bool(getattr(args, "no_outputs", False))
    if not no_outputs:
        no_outputs = _env_truthy("NW_NO_OUTPUTS")

    no_cmdlog = bool(getattr(args, "no_cmdlog", False))
    if not no_cmdlog:
        no_cmdlog = _env_truthy("NW_NO_CMDLOG")
    if no_outputs:
        no_cmdlog = True

    out_base = str(getattr(args, "out_base", "") or "")
    collided = False
    if not no_outputs:
        run_ts, collided = unique_run_ts(out_root, stage, run_ts, out_base)

    out_dir = _resolve_out_dir(out_root, stage, run_ts, out_base)
    log_dir = log_root / stage

    if not no_outputs:
        safe_mkdir(out_dir)
        if collided:
            print(f"run_ts collision avoided; using {run_ts} (previous run preserved)")
    cmd_path = log_dir / f"{script_stem}_{run_ts}.cmd.txt"
    log_path = log_dir / f"{script_stem}_{run_ts}.log"

    if no_outputs:
        setup_stream_logger(args.log_level)
        print("outputs saving disabled (NW_NO_OUTPUTS or --no_outputs)")
        cmd_path = None
        log_path = None
    elif no_cmdlog:
        setup_stream_logger(args.log_level)
        print("cmd/log saving disabled (NW_NO_CMDLOG or --no_cmdlog)")
        cmd_path = None
        log_path = None
    else:
        safe_mkdir(log_dir)
        cmd_path = save_cmd(log_dir, script_stem, run_ts, sys.argv)
        log_path = setup_logger(log_dir, script_stem, run_ts, args.log_level)
        print(f"cmd saved: {cmd_path}")
        print(f"log saved: {log_path}")

    # run_meta.json is written unconditionally: every measurement must carry a
    # git commit + timestamp stamp, otherwise the number is not comparable later.
    if not no_outputs:
        meta = {
            "run_ts": run_ts,
            "stage": stage,
            "script": script_stem,
            "argv": sys.argv,
            "cwd": os.getcwd(),
            "python": sys.version,
            "platform": platform.platform(),
            "git_commit": get_git_commit(),
            "git_dirty": get_git_dirty(),
        }
        if getattr(args, "dump_env", False):
            meta["env"] = dict(os.environ)
        meta_path = dump_run_meta(out_dir, meta)
        print(f"run_meta saved: {meta_path}")

    return RunPaths(
        run_ts=run_ts,
        stage=stage,
        script_stem=script_stem,
        out_dir=out_dir,
        log_dir=log_dir,
        cmd_path=cmd_path,
        log_path=log_path,
        outputs_enabled=(not no_outputs),
        cmdlog_enabled=(not no_cmdlog),
    )
