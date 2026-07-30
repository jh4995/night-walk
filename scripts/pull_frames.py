"""폰의 측정 로그(frames.csv / session.json)를 하네스 출력 디렉토리로 가져온다.

손 `adb pull`에는 두 문제가 있었다:
  (a) pull 대상을 `outputs/` 아래로 잡으면 "outputs 산출물을 손으로 만들지 않는다"와 충돌한다
  (b) **원본 로그에 git 스탬프가 안 붙는다** — 어느 커밋의 앱이 뱉은 로그인지 나중에 알 수 없다

그래서 pull도 `init_run()`을 거친다. 출력 위치는 하네스가 정하고(`outputs/poc_pull/<run_ts>/`),
같은 디렉토리에 `run_meta.json`(git commit·dirty)과 `pull_result.json`(adb 원문)이 함께 남는다.

    python scripts/pull_frames.py [--package com.bammasil.poc] [--serial <serial>]

실패는 조용히 넘어가지 않는다. adb 미탐색·기기 0대/여러 대·pull 실패·0바이트 파일은
전부 **다른 종료 코드**로 낸다 (0바이트는 "가져왔다"가 아니다).
"""

from __future__ import annotations

import json
import logging
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lib import device_meta  # noqa: E402
from lib.run_utils import common_argparser, init_run  # noqa: E402

LOG = logging.getLogger(__name__)

DEFAULT_PACKAGE = "com.bammasil.poc"
# 앱의 외부 저장소 전용 디렉토리 (Context.getExternalFilesDir(null))
REMOTE_DIR_TEMPLATE = "/sdcard/Android/data/{pkg}/files"
PULL_FILES = ("frames.csv", "session.json")

_ADB_TIMEOUT = 60  # 로그가 커도 넉넉하게. 멈춘 adb에 영원히 매달리지는 않는다.

# 종료 코드. 원인이 다르면 코드도 다르다 — "실패했다"만으로는 무엇을 고쳐야 할지 모른다.
EXIT_OK = 0
EXIT_ADB_NOT_FOUND = 2
EXIT_DEVICE_PROBLEM = 3   # 기기 0대 / 여러 대인데 --serial 없음 / 지정 serial이 device 아님
EXIT_PULL_FAILED = 4      # adb pull 실패 또는 0바이트
EXIT_NO_DESTINATION = 5   # --no_outputs — 가져다 놓을 위치가 없다


def _adb_raw(adb_path: str, args: list[str], serial: str = "") -> dict:
    """adb를 돌리고 **원문을 그대로** 돌려준다.

    `device_meta._adb`는 실패를 None으로 뭉개고 stderr를 버린다(메타 수집에는 그게 맞다).
    여기서는 실패 원문이 곧 진단이므로 returncode/stdout/stderr를 전부 보존한다.
    adb 경로 탐색과 기기 목록은 `lib/device_meta`를 그대로 재사용한다 — 다시 짜지 않는다.
    """
    cmd = [adb_path]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    result = {"cmd": cmd, "returncode": None, "stdout": "", "stderr": "", "error": None}
    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=_ADB_TIMEOUT,
        )
        result["returncode"] = proc.returncode
        result["stdout"] = (proc.stdout or "").strip()
        result["stderr"] = (proc.stderr or "").strip()
    except FileNotFoundError as exc:
        result["error"] = f"adb 실행 실패: {exc}"
    except subprocess.TimeoutExpired:
        result["error"] = f"adb가 {_ADB_TIMEOUT}초 안에 끝나지 않았다"
    return result


def _log_adb_verbatim(prefix: str, res: dict) -> None:
    """adb 출력을 요약하지 않고 원문 그대로 남긴다."""
    LOG.info("%s $ %s", prefix, " ".join(res["cmd"]))
    LOG.info("%s returncode=%s", prefix, res["returncode"])
    for stream in ("stdout", "stderr"):
        text = res.get(stream) or ""
        if text:
            for line in text.splitlines():
                LOG.info("%s %s| %s", prefix, stream, line)
    if res.get("error"):
        LOG.error("%s error: %s", prefix, res["error"])


def resolve_serial(adb_path: str, requested: str) -> tuple[str, list[str], str | None]:
    """(대상 serial, 연결된 serial 목록, 실패 사유). 기기 선택을 추측하지 않는다."""
    serials = device_meta.list_devices(adb_path)
    if not serials:
        return "", serials, (
            "device 상태인 기기가 없다 (미연결 / USB 디버깅 미허용 / unauthorized). "
            "`adb devices`로 확인할 것"
        )
    if requested:
        if requested not in serials:
            return "", serials, (
                f"지정한 serial({requested})이 device 상태가 아니다. 연결된 것: {serials}"
            )
        return requested, serials, None
    if len(serials) > 1:
        # 아무거나 고르면 어느 기기 로그인지 모르는 채로 숫자가 남는다. 고르지 않는다.
        return "", serials, (
            f"기기가 {len(serials)}대 붙어 있다: {serials} — "
            "어느 기기의 로그인지가 숫자의 일부이므로 --serial로 명시할 것"
        )
    return serials[0], serials, None


def main() -> int:
    parser = common_argparser()
    parser.add_argument(
        "--package",
        default=DEFAULT_PACKAGE,
        help=f"앱 패키지명 (기본 {DEFAULT_PACKAGE})",
    )
    parser.add_argument(
        "--remote_dir",
        default="",
        help=f"원격 디렉토리 (기본 {REMOTE_DIR_TEMPLATE.format(pkg='<package>')})",
    )
    parser.add_argument("--adb", default="", help="adb 실행 파일 경로 (자동 탐색 실패 시)")
    parser.add_argument("--serial", default="", help="대상 기기 serial (여러 대면 필수)")
    parser.add_argument("--label", default="", help="이 pull에 붙일 메모")
    args = parser.parse_args()

    paths = init_run(stage="poc_pull", script_file=__file__, args=args)

    if not paths.outputs_enabled:
        # 죽지는 않는다. 다만 가져다 놓을 위치가 없으므로 pull을 하지 않고 정직하게 끝낸다
        # (임의의 경로에 떨어뜨리면 스탬프 없는 로그가 생긴다 — 그게 이 스크립트가 막는 것이다).
        LOG.error(
            "outputs가 비활성이라 pull할 목적지가 없다 — pull을 수행하지 않았다. "
            "--no_outputs 없이 실행할 것 (스탬프 없는 위치에 로그를 떨어뜨리지 않는다)"
        )
        return EXIT_NO_DESTINATION

    adb_path = device_meta.find_adb(args.adb)
    if not adb_path:
        LOG.error(
            "adb를 찾지 못했다 (ANDROID_HOME 미설정 또는 platform-tools 미설치). "
            "--adb 로 경로를 직접 줄 수 있다"
        )
        return EXIT_ADB_NOT_FOUND
    LOG.info("adb: %s", adb_path)

    serial, serials, why = resolve_serial(adb_path, args.serial)
    if why:
        LOG.error("기기를 정하지 못했다: %s", why)
        _write_result(
            paths,
            {
                "ok": False,
                "stage_failed": "device",
                "reason": why,
                "adb_path": adb_path,
                "devices": serials,
                "package": args.package,
            },
        )
        return EXIT_DEVICE_PROBLEM
    LOG.info("대상 기기: %s (연결된 기기 %d대)", serial, len(serials))

    remote_dir = (args.remote_dir or REMOTE_DIR_TEMPLATE.format(pkg=args.package)).rstrip("/")

    # 기기 메타를 같이 남긴다 — 기기가 다르면 숫자를 비교할 수 없으므로 어느 기기에서
    # 나온 로그인지가 로그의 일부다.
    dev = device_meta.collect(adb_path_hint=adb_path, serial=serial)
    LOG.info("기기: %s", device_meta.describe(dev))

    # 원격 디렉토리 목록. pull이 실패했을 때 "앱이 아직 안 돌았다"와 "권한 문제"를
    # 구분하는 유일한 단서다.
    listing = _adb_raw(adb_path, ["shell", "ls", "-l", remote_dir], serial=serial)
    _log_adb_verbatim("[ls]", listing)

    files = []
    failed = []
    for name in PULL_FILES:
        remote = f"{remote_dir}/{name}"
        local = paths.out_dir / name
        res = _adb_raw(adb_path, ["pull", remote, str(local)], serial=serial)
        _log_adb_verbatim(f"[pull {name}]", res)

        exists = local.exists()
        size = local.stat().st_size if exists else 0
        # 0바이트는 "가져왔다"가 아니다. adb가 0을 반환하더라도 빈 파일은 분석 불가다.
        ok = (res["returncode"] == 0) and exists and size > 0
        entry = {
            "name": name,
            "remote_path": remote,
            "local_path": str(local.resolve()) if exists else None,
            "exists": exists,
            "size_bytes": size,
            "ok": ok,
            "adb": res,
        }
        files.append(entry)
        if not ok:
            reason = "adb pull 실패"
            if res["returncode"] == 0 and exists and size == 0:
                reason = "0바이트 파일 — 앱이 로그를 flush하지 않았을 수 있다"
            elif res["returncode"] == 0 and not exists:
                reason = "adb는 성공을 반환했는데 로컬 파일이 없다"
            entry["failure_reason"] = reason
            failed.append(name)
            LOG.error("%s 실패: %s", name, reason)
        else:
            LOG.info("%s 가져옴: %s (%d bytes)", name, local, size)

    result = {
        "ok": not failed,
        "run_ts": paths.run_ts,
        "label": args.label,
        "package": args.package,
        "remote_dir": remote_dir,
        "adb_path": adb_path,
        "serial": serial,
        "devices": serials,
        "device": dev,
        "out_dir": str(paths.out_dir.resolve()),
        "files": files,
        "failed_files": failed,
        # pull이 실패했을 때 원인을 가리는 단서. 원문 그대로 싣는다.
        "remote_listing": listing,
    }
    _write_result(paths, result)

    if failed:
        LOG.error("=" * 62)
        LOG.error("pull 실패: %s", ", ".join(failed))
        LOG.error(
            "확인 순서 — ① 앱이 한 번이라도 측정을 끝냈는가(로그는 종료 시 한 번에 쓴다) "
            "② 위 [ls] 출력에 파일이 보이는가 ③ 경로가 맞는가(--package/--remote_dir) "
            "④ scoped storage 권한(Android 11+에서 /sdcard/Android/data 접근 제한)"
        )
        LOG.error("=" * 62)
        return EXIT_PULL_FAILED

    LOG.info("=" * 62)
    LOG.info("다음: python scripts/analyze_frames.py --frames %s --session %s",
             paths.out_dir / "frames.csv", paths.out_dir / "session.json")
    LOG.info("=" * 62)
    return EXIT_OK


def _write_result(paths, result: dict) -> None:
    if not paths.outputs_enabled:
        LOG.info("outputs 비활성 — pull_result를 파일로 남기지 않았다")
        return
    out_path = paths.out_dir / "pull_result.json"
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")
    LOG.info("pull_result 저장: %s", out_path)


if __name__ == "__main__":
    raise SystemExit(main())
