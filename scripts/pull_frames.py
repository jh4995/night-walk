"""폰의 측정 로그(frames.csv / session.json)를 하네스 출력 디렉토리로 가져온다.

손 `adb pull`에는 두 문제가 있었다:
  (a) pull 대상을 `outputs/` 아래로 잡으면 "outputs 산출물을 손으로 만들지 않는다"와 충돌한다
  (b) **원본 로그에 git 스탬프가 안 붙는다** — 어느 커밋의 앱이 뱉은 로그인지 나중에 알 수 없다

그래서 pull도 `init_run()`을 거친다. 출력 위치는 하네스가 정하고(`outputs/poc_pull/<run_ts>/`),
같은 디렉토리에 `run_meta.json`(git commit·dirty)과 `pull_result.json`(adb 원문)이 함께 남는다.

    python scripts/pull_frames.py                      # 기기의 **가장 최근 런** 하나
    python scripts/pull_frames.py --list               # 회수하지 않고 런 목록만
    python scripts/pull_frames.py --run 20260731_032312
    python scripts/pull_frames.py --all                # runs/ 아래 전부

앱은 측정 1회당 `<files>/runs/<YYYYMMDD_HHMMSS>/{frames.csv,session.json}`를 만든다
(android 커밋 0fe533f 이후). 회수 결과도 그 구조를 유지해 **`<out_dir>/<기기 런 이름>/`**
아래로 떨어뜨린다 — 평면으로 떨어뜨리면 어느 기기 런에서 온 숫자인지가 경로에서 사라진다.

⚠ **조용한 오염을 막는 것이 이 스크립트의 주 임무다.** 예전 구현은 `runs/`를 모르고
`<files>/` 바로 아래의 평면 파일을 가져오면서 `ok=True`·exit 0을 냈다. 그 자리에는 S1 이전
빌드가 남긴 옛 로그가 그대로 남아 있어서, "최신 런을 가져왔다"는 보고와 함께 낡은 v1 로그가
베이스라인 비교에 들어갔다. 그래서 평면 폴백은 **`runs/`가 아예 없을 때만** 하고, 할 때는
반드시 크게 경고한다. `runs/`는 있는데 비어 있으면 폴백하지 않고 **실패**한다 —
"런이 없다"와 "낡은 파일이 있다"는 다른 상황이다.

실패는 조용히 넘어가지 않는다. adb 미탐색·기기 0대/여러 대·런 없음·pull 실패·0바이트 파일은
전부 **다른 종료 코드**로 낸다 (0바이트는 "가져왔다"가 아니다).
"""

from __future__ import annotations

import json
import logging
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lib import device_meta  # noqa: E402
from lib.run_utils import common_argparser, init_run  # noqa: E402

LOG = logging.getLogger(__name__)

DEFAULT_PACKAGE = "com.bammasil.poc"
# 앱의 외부 저장소 전용 디렉토리 (Context.getExternalFilesDir(null))
REMOTE_BASE_TEMPLATE = "/sdcard/Android/data/{pkg}/files"
# 앱이 런별 디렉토리를 만드는 위치 (MainActivity: `<files>/runs/<YYYYMMDD_HHMMSS>/`)
RUNS_SUBDIR = "runs"
PULL_FILES = ("frames.csv", "session.json")

# 평면 폴백으로 가져온 것을 담는 로컬 디렉토리 이름. 기기 런 이름(타임스탬프)과 **모양이
# 다르게** 짓는다 — 경로만 봐도 "이건 런 디렉토리에서 온 게 아니다"가 드러나야 한다.
LEGACY_LOCAL_DIRNAME = "_legacy_flat"

_ADB_TIMEOUT = 60  # 로그가 커도 넉넉하게. 멈춘 adb에 영원히 매달리지는 않는다.

# 원격 디렉토리 존재 여부를 **한 번의 shell 호출**로 확정하기 위한 표식.
# returncode만 보면 "디렉토리 없음"과 "권한 거부"를 구분하기 어렵고, adb 버전에 따라
# 원격 종료 코드가 전달되지 않는 경우도 있다. 표식은 어느 쪽이든 명확하다.
_MARK_DIR = "__NW_DIR__"
_MARK_NODIR = "__NW_NODIR__"

# 종료 코드. 원인이 다르면 코드도 다르다 — "실패했다"만으로는 무엇을 고쳐야 할지 모른다.
EXIT_OK = 0
EXIT_ADB_NOT_FOUND = 2
EXIT_DEVICE_PROBLEM = 3   # 기기 0대 / 여러 대인데 --serial 없음 / 지정 serial이 device 아님
EXIT_PULL_FAILED = 4      # adb pull 실패·0바이트, **그리고 회수할 런이 없음**
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


def _parse_ls_long(stdout: str) -> dict[str, dict]:
    """`ls -l` 출력 → {이름: {mode, size_bytes, mtime}}.

    toybox `ls -l`은 `mode links owner group size date time name` 8필드다. 뒤에서부터
    세므로 owner/group 폭이 달라져도 흔들리지 않는다. (이름에 공백이 있으면 깨지지만,
    앱이 쓰는 이름은 `frames.csv`·`session.json`·타임스탬프 디렉토리뿐이다.)
    """
    out: dict[str, dict] = {}
    for line in (stdout or "").splitlines():
        line = line.strip()
        if not line or line.startswith("total "):
            continue
        parts = line.split()
        if len(parts) < 8:
            continue
        name = parts[-1]
        size_raw = parts[-4]
        try:
            size = int(size_raw)
        except ValueError:
            size = None
        out[name] = {
            "mode": parts[0],
            "size_bytes": size,
            "mtime": f"{parts[-3]} {parts[-2]}",
        }
    return out


def probe_remote_dir(adb_path: str, serial: str, remote_dir: str) -> dict:
    """원격 디렉토리 하나를 조사한다. **존재 여부와 내용을 한 번에** 확정한다.

    반환: {"state": "missing"|"empty"|"entries"|"error", "entries": {...},
           "error_reason": str|None, "adb": <원문>}
    `state`를 문자열로 내는 이유: "디렉토리가 없다"와 "있는데 비어 있다"는 이 스크립트에서
    **다른 결론**(폴백 vs 실패)으로 이어지므로 불리언 하나로 뭉치면 안 된다.

    ⚠ 표식은 `ls` **앞에서** echo되므로 표식만 보면 "읽을 수 없는 디렉토리"가 "빈 디렉토리"와
      같아진다. 스코프드 스토리지에서 실제로 밟는 경로이고, 그때 하네스가 "앱이 아직 측정을
      끝내지 않았다"라고 말하면 **범인을 잘못 지목한다**(권한 문제인데 앱을 뒤지게 된다).
      그래서 `ls`의 returncode를 함께 본다 — 0이 아니면 목록을 신뢰하지 않고 error로 올린다.
      시계 교차검사가 t_capture_ns 문제와 t_render_* 문제를 갈라 지목하는 것과 같은 원칙이다.
    """
    script = (
        f"if [ -d '{remote_dir}' ]; then echo {_MARK_DIR}; ls -l '{remote_dir}'; "
        f"else echo {_MARK_NODIR}; fi"
    )
    res = _adb_raw(adb_path, ["shell", script], serial=serial)
    lines = [ln.rstrip("\r") for ln in (res["stdout"] or "").splitlines()]
    if _MARK_NODIR in lines:
        return {"state": "missing", "entries": {}, "error_reason": None, "adb": res}
    if _MARK_DIR in lines:
        body = "\n".join(lines[lines.index(_MARK_DIR) + 1:])
        entries = _parse_ls_long(body)
        if res["returncode"] not in (0, None) or res.get("error"):
            # 디렉토리는 존재하는데 ls가 실패했다. 목록이 비었든 일부만 나왔든 **믿을 수 없다.**
            detail = (res.get("stderr") or "").strip() or res.get("error") or "(stderr 없음)"
            return {
                "state": "error",
                "entries": entries,
                "error_reason": (
                    f"디렉토리는 존재하지만 목록을 읽지 못했다 "
                    f"(ls returncode={res['returncode']}). 원문: {detail}"
                ),
                "adb": res,
            }
        return {
            "state": "entries" if entries else "empty",
            "entries": entries,
            "error_reason": None,
            "adb": res,
        }
    # 표식이 하나도 안 나왔다 = shell 자체가 실패했다(adb 오류, 기기 응답 없음 등).
    detail = (res.get("stderr") or "").strip() or res.get("error") or "(출력 없음)"
    return {
        "state": "error",
        "entries": {},
        "error_reason": f"adb shell이 표식을 내지 못했다. 원문: {detail}",
        "adb": res,
    }


# 런 이름은 `YYYYMMDD_HHMMSS`이고, 같은 초에 두 번 시작하면 앱이 `_2`, `_3`, … `_10`으로
# 접미사를 붙인다(`MainActivity.kt`). 그래서 **사전순은 시간순이 아니다** — `_10 < _2`다.
_RUN_NAME_RE = re.compile(r"^(\d{8}_\d{6})(?:_(\d+))?$")


def run_sort_key(name: str) -> tuple:
    """런 이름 정렬 키. 충돌 접미사를 **숫자로** 떼어 낸다.

    사전순으로 두면 `20260731_100000_10`이 `..._2`보다 앞서서 "가장 최근"이 틀린 런을 고른다
    — 틀린 런을 성공으로 가져오는 것은 F1과 같은 계열이라 실무 확률이 낮아도 닫는다.
    이름이 규격에서 벗어나면 원본 문자열로 정렬한다(추측하지 않는다).
    """
    m = _RUN_NAME_RE.match(name)
    if not m:
        return (name, 0, name)
    return (m.group(1), int(m.group(2) or 0), name)


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


def legacy_flat_warning(base_dir: str) -> str:
    """평면 폴백을 탈 때 반드시 나가는 문장. **조용한 폴백이 F1 그 자체였다.**"""
    return (
        f"⚠⚠ 평면 레이아웃 폴백 — `{base_dir}/`의 파일을 가져왔다. 여기 있는 것은 "
        f"**런 디렉토리(`{RUNS_SUBDIR}/`)를 쓰기 전, S1 이전 앱이 남긴 로그**다. "
        f"`schema_version`이 없거나 1이고 `render_arm`·`build`·`gl`·`gpu_timer` 블록이 없으며, "
        f"CSV에 GPU 패스 시간 열(stage_*_ms / gpu_present_ms)이 없다. "
        f"**지금 측정과 비교하지 말 것** — pipeline_stages가 빈 배열이라 baseline_diff가 "
        f"새 passthrough 런과 '비교 가능'으로 판정해 버리므로, 이 파일로 승격 베이스라인을 "
        f"재현하면 낡은 숫자가 조용히 기준이 된다. 최신 앱으로 다시 측정하면 "
        f"`{base_dir}/{RUNS_SUBDIR}/<타임스탬프>/`가 생긴다"
    )


def stale_flat_warning(base_dir: str, names: list[str]) -> str:
    """`runs/`를 쓰면서도 평면 파일이 남아 있을 때. 사용자가 지워야 할 대상이다."""
    return (
        f"`{base_dir}/` 바로 아래에 옛 평면 로그가 남아 있다: {', '.join(names)} — "
        f"이번 회수는 `{RUNS_SUBDIR}/` 쪽을 썼으므로 오염되지 않았지만, 이 파일들은 S1 이전 "
        f"빌드의 잔해이고 손 `adb pull`이나 옛 하네스가 집으면 그대로 오염이 된다. "
        f"측정 전에 지울 것 (`adb shell rm {base_dir}/frames.csv {base_dir}/session.json`)"
    )


def _print_listing(runs: list[dict]) -> None:
    """--list 출력. 어느 런을 뽑을지 사람이 고를 수 있어야 한다."""
    LOG.info("=" * 62)
    LOG.info("기기의 런 %d개:", len(runs))
    for r in runs:
        LOG.info("  %s  (%s)", r["name"], r["dir_mtime"] or "mtime 불명")
        for name in PULL_FILES:
            f = (r["files_on_device"] or {}).get(name)
            if f:
                LOG.info("      %-14s %10s bytes  %s", name, f["size_bytes"], f["mtime"])
            else:
                LOG.warning("      %-14s 없음 — 이 런은 회수해도 반쪽이다", name)
        extra = sorted(set(r["files_on_device"] or {}) - set(PULL_FILES))
        if extra:
            LOG.info("      (그 밖의 파일: %s)", ", ".join(extra))
    LOG.info("=" * 62)


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
        help=(
            f"앱 외부 파일 디렉토리 (기본 {REMOTE_BASE_TEMPLATE.format(pkg='<package>')}). "
            f"런은 이 아래 {RUNS_SUBDIR}/ 에서 찾는다"
        ),
    )
    parser.add_argument("--adb", default="", help="adb 실행 파일 경로 (자동 탐색 실패 시)")
    parser.add_argument("--serial", default="", help="대상 기기 serial (여러 대면 필수)")
    parser.add_argument("--label", default="", help="이 pull에 붙일 메모")
    parser.add_argument(
        "--run",
        default="",
        help="가져올 기기 런 이름 (기본: 사전순 마지막 = 가장 최근 런 하나)",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help=f"{RUNS_SUBDIR}/ 아래 런을 전부 가져온다 (야간에 여러 런 찍고 와서 한 번에 회수)",
    )
    parser.add_argument(
        "--list",
        action="store_true",
        dest="list_runs",
        help="회수하지 않고 기기의 런 목록만 보여준다 (이름·파일 크기·수정 시각)",
    )
    args = parser.parse_args()

    paths = init_run(stage="poc_pull", script_file=__file__, args=args)

    if args.run and args.all:
        LOG.error("--run 과 --all 은 같이 쓸 수 없다 — 하나만 지정할 것")
        return EXIT_PULL_FAILED

    if not paths.outputs_enabled:
        # 죽지는 않는다. 다만 가져다 놓을 위치가 없으므로 pull을 하지 않고 정직하게 끝낸다
        # (임의의 경로에 떨어뜨리면 스탬프 없는 로그가 생긴다 — 그게 이 스크립트가 막는 것이다).
        # --list도 같이 막는다: 결과를 pull_result.json에 남기지 못하면 "그때 기기에 무엇이
        # 있었나"가 사라지고, 종료 코드 5의 뜻("--no_outputs")도 모드마다 갈리게 된다.
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

    base_dir = (args.remote_dir or REMOTE_BASE_TEMPLATE.format(pkg=args.package)).rstrip("/")
    if "'" in base_dir:
        LOG.error("--remote_dir에 작은따옴표를 쓸 수 없다: %s", base_dir)
        return EXIT_PULL_FAILED
    runs_dir = f"{base_dir}/{RUNS_SUBDIR}"

    # 기기 메타를 같이 남긴다 — 기기가 다르면 숫자를 비교할 수 없으므로 어느 기기에서
    # 나온 로그인지가 로그의 일부다.
    dev = device_meta.collect(adb_path_hint=adb_path, serial=serial)
    LOG.info("기기: %s", device_meta.describe(dev))

    # ── 원격 레이아웃 조사. pull이 실패했을 때 "앱이 아직 안 돌았다"와 "권한 문제"를
    #    구분하는 유일한 단서이기도 하다.
    base_probe = probe_remote_dir(adb_path, serial, base_dir)
    _log_adb_verbatim("[ls base]", base_probe["adb"])
    runs_probe = probe_remote_dir(adb_path, serial, runs_dir)
    _log_adb_verbatim("[ls runs]", runs_probe["adb"])

    flat_names = [n for n in PULL_FILES if n in base_probe["entries"]]
    # 런은 **디렉토리**다. 접미사를 숫자로 떼어 정렬해야 `_10`이 `_2` 뒤에 온다(run_sort_key).
    run_names = sorted(
        (n for n, meta in runs_probe["entries"].items() if meta["mode"].startswith("d")),
        key=run_sort_key,
    )
    # 런이 아닌 항목. 이게 있는데 run_names가 비면 "빈 디렉토리"와 다른 상황이므로 갈라 말한다.
    non_run_entries = sorted(
        n for n, meta in runs_probe["entries"].items() if not meta["mode"].startswith("d")
    )

    warnings: list[str] = []
    result: dict = {
        "run_ts": paths.run_ts,
        "label": args.label,
        "package": args.package,
        # **실제로 어느 원격 경로를 봤는지.** 회수 결과만 보고 되물을 일이 없어야 한다.
        "remote_base_dir": base_dir,
        "remote_runs_dir": runs_dir,
        "remote_runs_state": runs_probe["state"],
        "runs_available": run_names,
        "legacy_flat_files_present": flat_names,
        "adb_path": adb_path,
        "serial": serial,
        "devices": serials,
        "device": dev,
        "out_dir": str(paths.out_dir.resolve()),
        # 원격 `ls` 원문. 위 요약이 틀렸을 때 되물을 근거다.
        "remote_listing_base": base_probe["adb"],
        "remote_listing_runs": runs_probe["adb"],
    }

    # ── 어느 레이아웃에서 무엇을 가져올지 결정한다. 여기가 F1의 핵심이다.
    layout = "runs"
    selection_mode = "latest"
    selected: list[dict] = []   # [{"name":..., "remote_dir":...}]

    if runs_probe["state"] == "error":
        # 범인을 지목한다. "앱이 아직 측정을 안 끝냈다"로 뭉개면 폰 쪽 코드를 뒤지게 된다.
        reason = (
            f"{runs_dir} 를 조사하지 못했다 — {runs_probe.get('error_reason')} "
            "위 [ls runs] 원문을 확인할 것. 권한 거부(Android 11+ scoped storage: "
            "/sdcard/Android/data 접근 제한)이거나 adb 상태 문제이며, **런이 없다는 뜻이 "
            "아니다.** 낡은 평면 파일로 폴백하지 않는다"
        )
        return _fail(paths, result, reason, warnings)

    # ── "회수할 런이 0개"는 **어느 모드에서도 성공이 아니다.**
    #    state=="empty"(디렉토리가 텅 빔)와 state=="entries"인데 디렉토리가 하나도 없는 경우
    #    (파일만 있음)를 **같은 가드**로 닫는다. 예전에는 후자에서 기본/--list가
    #    run_names[-1]로 IndexError(문서에 없는 exit 1, pull_result 미생성)를 냈고,
    #    --all은 빈 루프를 돌고 `ok=true, runs=[]`로 **아무것도 안 가져오고 성공**을 냈다.
    #    조용한 성공을 없애려는 라운드에서 조용한 성공을 새로 만들지 않는다.
    if runs_probe["state"] in ("empty", "entries") and not run_names:
        reason = (
            f"{runs_dir} 아래에 회수할 런이 하나도 없다 — 앱이 아직 측정을 끝내지 않았다"
            "(로그는 측정 종료 시 한 번에 쓴다). "
            + (
                f"런 디렉토리가 아닌 항목만 있다: {', '.join(non_run_entries)} — 런은 "
                "`<YYYYMMDD_HHMMSS>/` 디렉토리다. "
                if non_run_entries else ""
            )
            + (
                f"`{base_dir}/` 아래 평면 파일({', '.join(flat_names)})이 보이지만 이건 "
                "S1 이전 빌드의 낡은 로그라 **일부러 가져오지 않았다.** "
                if flat_names else ""
            )
            + "앱에서 측정을 한 번 끝낸 뒤 다시 실행할 것"
        )
        return _fail(paths, result, reason, warnings)

    if runs_probe["state"] == "missing":
        if args.run or args.all:
            # 명시적으로 런을 지목했는데 런 디렉토리 자체가 없다 → 조용히 옛 파일을 주지 않는다.
            reason = (
                f"{runs_dir} 가 없다 — 이 앱 빌드는 런별 디렉토리를 쓰지 않는다(S1 이전). "
                "--run/--all 은 런 디렉토리가 있어야 쓸 수 있다. 옵션 없이 실행하면 "
                "평면 레이아웃 폴백을 경고와 함께 태운다"
            )
            return _fail(paths, result, reason, warnings)
        if not flat_names:
            reason = (
                f"{runs_dir} 도 없고 `{base_dir}/` 에 {', '.join(PULL_FILES)} 도 없다 — "
                "앱이 한 번도 측정을 끝내지 않았거나 경로가 다르다"
                "(--package / --remote_dir 확인). 위 [ls base] 원문을 볼 것"
            )
            return _fail(paths, result, reason, warnings)
        layout = "legacy_flat"
        if args.list_runs:
            # 회수하지 않으므로 "가져왔다"는 문장을 내지 않는다. --list 전용 안내가 아래에 있다.
            selection_mode = "list"
        else:
            selection_mode = "legacy_flat"
            selected = [{"name": LEGACY_LOCAL_DIRNAME, "remote_dir": base_dir}]
            warnings.append(legacy_flat_warning(base_dir))
    else:
        # runs/ 에 런이 있다. 평면 파일이 남아 있어도 **runs/ 를 쓴다.**
        if flat_names:
            warnings.append(stale_flat_warning(base_dir, flat_names))
        if args.list_runs:
            # 회수하지 않는다 → "가장 최근"을 고를 필요도, 찍을 이유도 없다.
            selection_mode = "list"
        elif args.all:
            selection_mode = "all"
            selected = [{"name": n, "remote_dir": f"{runs_dir}/{n}"} for n in run_names]
        elif args.run:
            selection_mode = "explicit"
            if args.run not in run_names:
                reason = (
                    f"--run {args.run!r} 인 런이 기기에 없다. 있는 런: {run_names} "
                    "(--list 로 확인할 것). 이름을 추측해 다른 런을 가져오지 않는다"
                )
                return _fail(paths, result, reason, warnings)
            selected = [{"name": args.run, "remote_dir": f"{runs_dir}/{args.run}"}]
        else:
            # run_sort_key로 정렬한 마지막 = 가장 최근. **사전순이 아니다** — 충돌 접미사가
            # 두 자리로 가면(`_10`) 사전순은 `_2`를 뒤에 놓아 틀린 런을 고른다.
            latest = run_names[-1]
            selected = [{"name": latest, "remote_dir": f"{runs_dir}/{latest}"}]
            LOG.info("가장 최근 런: %s (기기에 %d개)", latest, len(run_names))

    result["layout"] = layout
    result["selection_mode"] = selection_mode
    result["runs_selected"] = [s["name"] for s in selected]

    for w in warnings:
        LOG.warning("%s", w)

    # ── --list: 회수하지 않고 목록만. 스탬프는 남긴다(그때 기기에 무엇이 있었는지도 기록이다).
    if args.list_runs:
        listing = []
        for name in run_names:
            rdir = f"{runs_dir}/{name}"
            probe = probe_remote_dir(adb_path, serial, rdir)
            listing.append({
                "name": name,
                "remote_dir": rdir,
                "dir_mtime": (runs_probe["entries"].get(name) or {}).get("mtime"),
                "files_on_device": probe["entries"],
                "adb": probe["adb"],
            })
        result.update({
            "ok": True,
            "mode": "list",
            "pulled": False,
            "runs_listed": listing,
            "warnings": warnings,
        })
        _write_result(paths, result)
        if layout == "legacy_flat":
            LOG.warning(
                "기기에 런 디렉토리가 없다 — 회수 가능한 것은 `%s/`의 평면 파일뿐이다 "
                "(옵션 없이 실행하면 경고와 함께 그것을 가져온다)", base_dir,
            )
        else:
            _print_listing(listing)
        return EXIT_OK

    # ── 방어선. 위 선택 로직 중 어느 경로가 새로 생기더라도 **0개 회수 = 성공**은 없다.
    #    (`--all`이 빈 리스트를 그대로 루프에 넣어 `ok=true, runs=[]`를 내던 자리다.)
    if not selected:
        reason = (
            f"회수 대상이 0개다 (레이아웃={layout}, 선택 모드={selection_mode}, "
            f"기기의 런={run_names}) — 아무것도 가져오지 않았으므로 성공이 아니다. "
            "선택 로직이 대상을 못 고른 것이므로 위 [ls runs] 원문과 함께 보고할 것"
        )
        return _fail(paths, result, reason, warnings)

    # ── 실제 회수. 런마다 자기 디렉토리로 떨어뜨린다.
    runs_out: list[dict] = []
    failed_all: list[str] = []
    for sel in selected:
        local_dir = paths.out_dir / sel["name"]
        local_dir.mkdir(parents=True, exist_ok=True)
        files = []
        failed = []
        for name in PULL_FILES:
            remote = f"{sel['remote_dir']}/{name}"
            local = local_dir / name
            res = _adb_raw(adb_path, ["pull", remote, str(local)], serial=serial)
            _log_adb_verbatim(f"[pull {sel['name']}/{name}]", res)

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
                failed_all.append(f"{sel['name']}/{name}")
                LOG.error("%s/%s 실패: %s", sel["name"], name, reason)
            else:
                LOG.info("%s/%s 가져옴: %s (%d bytes)", sel["name"], name, local, size)

        runs_out.append({
            "name": sel["name"],
            "remote_dir": sel["remote_dir"],
            "local_dir": str(local_dir.resolve()),
            "files": files,
            "failed_files": failed,
            "ok": not failed,
        })

    result.update({
        "ok": not failed_all,
        "mode": "pull",
        "pulled": True,
        "runs": runs_out,
        "failed_files": failed_all,
        "warnings": warnings,
    })
    _write_result(paths, result)

    if failed_all:
        LOG.error("=" * 62)
        LOG.error("pull 실패: %s", ", ".join(failed_all))
        LOG.error(
            "확인 순서 — ① 앱이 그 런에서 측정을 끝냈는가(로그는 종료 시 한 번에 쓴다) "
            "② 위 [ls runs] 출력에 파일이 보이는가 ③ 경로가 맞는가(--package/--remote_dir) "
            "④ scoped storage 권한(Android 11+에서 /sdcard/Android/data 접근 제한)"
        )
        LOG.error("=" * 62)
        return EXIT_PULL_FAILED

    LOG.info("=" * 62)
    LOG.info("회수한 런 %d개 (레이아웃: %s)", len(runs_out), layout)
    for r in runs_out:
        LOG.info("다음: python scripts/analyze_frames.py --frames %s --session %s",
                 Path(r["local_dir"]) / "frames.csv", Path(r["local_dir"]) / "session.json")
    if layout == "legacy_flat":
        # 마지막에 한 번 더. 이 경고는 스크롤 위쪽에서 묻히면 안 된다.
        LOG.warning("%s", legacy_flat_warning(base_dir))
    LOG.info("=" * 62)
    return EXIT_OK


def _fail(paths, result: dict, reason: str, warnings: list[str]) -> int:
    """회수할 것이 없거나 원격 조사에 실패한 경우. **폴백하지 않고 실패로 낸다.**"""
    LOG.error("=" * 62)
    LOG.error("회수하지 못했다: %s", reason)
    LOG.error("=" * 62)
    result.update({
        "ok": False,
        "stage_failed": "select_run",
        "reason": reason,
        "pulled": False,
        "warnings": warnings,
    })
    _write_result(paths, result)
    return EXIT_PULL_FAILED


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
