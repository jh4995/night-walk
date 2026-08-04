"""측정 세션을 **안내·검증·기록**한다 (S3-3 9런 같은 다중 런 세션용).

한 세션은 arm·조명·길이가 정해진 런 여러 개다. 지금까지는 그걸 매번 사람이 손으로 맞췄고,
**한 번 틀리면 그 런은 통째로 못 쓴다** — 2026-07-31 시점 기기에 남은 런 21개 중 **8개**가
조명 `unknown`으로 나갔다(비교 대상이 못 된다). 이 스크립트는 손 작업을 줄이는 게 아니라
**틀린 것을 잡는 것**이 목적이다.

    python scripts/run_session.py                      # 기본 계획으로 세션 시작/재개
    python scripts/run_session.py --print_plan          # 계획만 보여주고 끝 (기기 안 건드림)
    python scripts/run_session.py --status              # 진행 상태만
    python scripts/run_session.py --report              # 남은 런 없이 집계·비교만 다시
    python scripts/run_session.py --plan my_plan.json   # 계획을 파일로

## 이 스크립트가 하지 않는 것 — 폰 조작

`adb shell input tap`으로 버튼을 누르는 기능은 **일부러 없다.** 좌표는 기기·해상도·OS
버전에 따라 바뀌고, **틀린 좌표로 엉뚱한 걸 눌러도 스크립트는 성공을 보고한다.**
그건 이 하네스가 계속 닫아 온 실패(조용한 성공)를 새로 여는 것이다.
사람이 앱에서 시작/정지하고, 스크립트는 **계획 제시 → 대기 → 검증**만 한다.

## 판정선을 갖지 않는다

PASS/FAIL은 `analyze_frames.py`(`lib/targets.py`)와 `baseline_diff.py`가 낸다.
여기 있는 임계는 **계획 대조용**이고 판정선이 아니다 — verdict를 만들지 않고,
`lib/targets.py` 밖의 성능 숫자를 쓰지 않는다.

## 중단·재개

진행 상태는 `outputs/measure_session/_sessions/<session_id>.json`에 남는다. 다시 실행하면
**남은 런부터** 이어간다(야간 런은 며칠 뒤일 수 있다). 각 실행은 자기 `init_run()` 스탬프를
갖고, 그 run_ts가 상태 파일의 `invocations[]`에 쌓인다.
"""

from __future__ import annotations

import csv
import json
import logging
import os
import subprocess
import sys
import time
from pathlib import Path

_SCRIPTS_DIR = Path(__file__).resolve().parent
_PROJECT_ROOT = _SCRIPTS_DIR.parent
sys.path.insert(0, str(_PROJECT_ROOT))
if str(_SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS_DIR))

from lib import targets  # noqa: E402
from lib.frame_log import (  # noqa: E402
    GPU_TIME_COLUMNS,
    LIGHTING_CONDITIONS,
    LIGHTING_SYNTHETIC,
    LIGHTING_UNKNOWN,
    RENDER_ARM_PASSTHROUGH,
    RENDER_ARM_SYNTHETIC,
    RENDER_ARMS,
    STAGE_D_TOTAL_COLUMN,
    read_session,
)
from lib.run_utils import (  # noqa: E402
    common_argparser,
    get_git_commit,
    init_run,
    now_run_ts,
    safe_mkdir,
    unique_run_ts,
)
from lib.stats import pct_change  # noqa: E402

# 종료 코드·정렬 규칙·기본 패키지를 **다시 적지 않는다**. pull_frames가 그 규약의 주인이다.
import pull_frames as pull_frames_script  # noqa: E402

LOG = logging.getLogger(__name__)

STAGE = "measure_session"

# 자식 스크립트. **손 adb pull / 손 집계를 하지 않는다** — 회수도 집계도 비교도
# 각자의 init_run 스탬프를 남겨야 하므로 그 스크립트를 그대로 호출한다.
PULL_SCRIPT = "pull_frames.py"
ANALYZE_SCRIPT = "analyze_frames.py"
DIFF_SCRIPT = "baseline_diff.py"
PULL_STAGE = "poc_pull"
ANALYZE_STAGE = "poc_baseline"
DIFF_STAGE = "baseline_diff"

# ── 계획 대조용 임계 (판정선이 아니다) ──────────────────────────────────────
# `analyze_frames.FRAMETIME_PINNED_REL_DIFF`와 같은 부류다. PASS/FAIL도 성능 판정도
# 흔들지 않고, "사람이 계획대로 찍었는가"만 본다.
#
# 10%인 근거: 사람이 앱에서 시작/정지하는 슬랙은 보통 수 초다(3분 런의 10% = 18초).
# 그보다 크게 짧으면 그건 슬랙이 아니라 **다른 길이로 찍은 것**이고, 길이는 분포를 바꾸므로
# 같은 계획 칸에 넣으면 안 된다. 계획보다 **긴** 것은 어긋남으로 보지 않는다(분석 창이
# 넉넉해질 뿐이다).
DURATION_SHORT_TOLERANCE = 0.10

# ── 앱이 신고한 패스스루 폴백 ────────────────────────────────────────────────
# `session.json`의 이 필드는 앱이 **스스로** "설정된 arm을 그리지 못해 패스스루로 대신
# 그렸다"고 센 프레임 수다(`SessionWriter.kt`가 `offscreenFallbackDraws`를 그대로 적는다).
#
# 🔴 **왜 이 필드를 근거로 고르는가.** 2026-08-03 세션 `s4_combo` #5
#    (`drago_clahe_fused`, 11분)에서 적용 프래그먼트가 A34에서 컴파일에 실패했고
#    (`0:109: S0001: Function call discards 'readonly' access qualifier.`), 앱은 죽지 않고
#    **모든 프레임(20032/20032)을 패스스루로 폴백**시켰다. 그 런은 arm·조명·빌드 타입·
#    git_dirty·분석 창(639.5s)을 전부 통과했고 **프레임타임조차 정상으로 보였다**
#    (fps_mean 29.92, meets_fps_target/meets_p95_target 모두 true — 그릴 것이 없으면
#    오히려 빠르다). 즉 기존 계획 대조는 "스피너를 잘못 돌린 실수"는 잡지만 "앱이
#    내부적으로 그 arm을 못 그린 것"은 잡지 못했고, 유일한 신호는 CSV에 GPU 열이 없다는
#    것이어서 사람이 눈으로 봐야 보였다. 11분 런 하나가 무효인 채로 "계획 통과"로 기록됐다.
#
#    대안은 "arm이 패스스루가 아닌데 `stage_d_*` 열이 0개면 어긋남"이었는데, 그건 하네스가
#    **arm→열 매핑을 해석하기 시작하는 것**이다. `lib/frame_log.py`가 명시한 "하네스는
#    arm의 의미를 해석하지 않는다"와 충돌하고, 열 매핑의 주인은 앱의 `RenderArm.gpuColumns`
#    이므로 여기 사본을 두면 arm이 늘어날 때 조용히 어긋난다. 이 필드는 **앱이 스스로
#    신고한 사실**이라 해석이 전혀 필요 없다 — 0이 아니면 그 런은 선언한 arm으로 그려지지
#    않았다.
#
# 이건 **판정선이 아니다.** PASS/FAIL 임계값이 아니라 "그 런이 계획대로 측정됐는가"의
# 무결성 검사이므로 `lib/targets.py`에 값이 생기지 않는다(임계도 없다 — 0이냐 아니냐다).
#
# ⚠ **필드 이름은 여기 한 곳에만 적는다.** 읽는 코드(`fallback_facts`)와 사람에게 보이는
#   메시지가 이름을 각자 갖고 있으면, 앱이 필드를 옮길 때 한쪽만 낡는다 — 값은 못 읽는데
#   메시지는 옛 이름을 자신 있게 가리키는 상태가 가장 나쁘다. 점 표기는 경로에서 파생한다.
FALLBACK_FIELD_PATH = ("render", "processing", "frames_fell_back_to_passthrough")
FALLBACK_FIELD = ".".join(FALLBACK_FIELD_PATH)

# 승격 베이스라인 (docs/baselines/README.md). 런3(passthrough 재현)이 여기에 비교된다.
DEFAULT_PROMOTION_BASELINE = "docs/baselines/20260730_poc_empty_a34.json"

# 비교에 쓰는 열. **`lib/frame_log.py`에서 가져온다** — 여기서 열 목록을 다시 적으면
# 스키마가 늘어날 때 조용히 빠진다.
COMPARE_COLUMNS = tuple(GPU_TIME_COLUMNS) + (STAGE_D_TOTAL_COLUMN, "gpu_sum_ms")
FRAMETIME_KEY = "frametime_primary"
COMPARE_PERCENTILES = ("p50", "p95", "p99")

# 종료 코드. 원인이 다르면 코드도 다르다.
EXIT_OK = 0                 # 계획의 모든 런이 done, 어긋남 없음
EXIT_INCOMPLETE = 1         # 남은 런이 있다 (중단했거나 어긋난 런이 남았다) — 재개 가능
EXIT_PLAN_ERROR = 2         # 계획 파일이 잘못됐다 (어휘 밖 arm/조명, JSON 깨짐 등)
EXIT_DEVICE_PROBLEM = 3     # adb 없음 / 기기 문제 — 검증을 할 수 없다
EXIT_NO_DESTINATION = 5     # --no_outputs (pull_frames와 같은 번호·같은 뜻)
EXIT_SKIPPED = 6            # 남은 런은 없지만 건너뛴 런이 있다 — 완주가 아니다
EXIT_STATE_CONFLICT = 7     # 진행 파일과 계획이 어긋난다 (재개 거부)


# ── 기본 계획 ──────────────────────────────────────────────────────────────
# **순서가 중요하다.** 노이즈 바닥(1·2)을 먼저 확정하지 않으면 arm 차분을 신호로 읽을 수
# 없다 — 직전 검증에서 "안 바꾼 패스가 바꾼 패스만큼 움직이는" 현상이 3쌍에서 복제됐다.
DEFAULT_PLAN = {
    "id": "s3_3",
    "title": "S3-3 실기기 측정 세션 (9런)",
    "build_type": "release",
    "runs": [
        {
            "n": 1, "arm": "drago", "lighting": "indoor_bright", "minutes": 3,
            "purpose": "같은 arm 반복 — 열별 노이즈 바닥 확정 (A)",
            "role": "noise_floor", "noise_pair": 2, "compare_to": 4,
        },
        {
            "n": 2, "arm": "drago", "lighting": "indoor_bright", "minutes": 3,
            "purpose": "같은 arm 반복 — 열별 노이즈 바닥 확정 (B)",
            "role": "noise_floor", "noise_pair": 1, "compare_to": 4,
        },
        {
            "n": 3, "arm": "passthrough", "lighting": "indoor_bright", "minutes": 11,
            "purpose": "승격 베이스라인 재현",
            "role": "promotion_baseline_repro", "diff_baseline": True,
        },
        {
            "n": 4, "arm": "blit_2pass", "lighting": "indoor_bright", "minutes": 3,
            "purpose": "3패스 골격 기준선 (arm 차분의 분모)",
            "role": "arm_reference",
        },
        {
            "n": 5, "arm": "gamma_only", "lighting": "indoor_bright", "minutes": 3,
            "purpose": "② 하한",
            "compare_to": 4,
        },
        {
            "n": 6, "arm": "drago", "lighting": "indoor_bright", "minutes": 11,
            "purpose": "지속·발열",
            "compare_to": 4,
        },
        {
            "n": 7, "arm": "passthrough", "lighting": "outdoor_night_lit", "minutes": 11,
            "purpose": "야간 근거 0건 해소 (가로등 있는 보도)",
            "role": "night_reference",
        },
        {
            "n": 8, "arm": "passthrough", "lighting": "outdoor_night_dark", "minutes": 11,
            "purpose": "야간 근거 0건 해소 (조명 없는 구간 — 실제 사용 조건)",
            "role": "night_reference",
        },
        {
            "n": 9, "arm": "drago", "lighting": "outdoor_night_dark", "minutes": 11,
            "purpose": "야간 ②",
            "compare_to": 8,
        },
    ],
}


# ══ 계획 ═══════════════════════════════════════════════════════════════════
class PlanError(Exception):
    """계획이 잘못됐다. 추측해서 고치지 않고 죽인다 — 계획이 틀리면 세션 전체가 틀린다."""


def load_plan(path: str) -> dict:
    if not path:
        return json.loads(json.dumps(DEFAULT_PLAN))  # 깊은 복사
    p = Path(path)
    if not p.exists():
        raise PlanError(f"계획 파일이 없다: {p}")
    try:
        with p.open("r", encoding="utf-8") as f:
            plan = json.load(f)
    except json.JSONDecodeError as exc:
        raise PlanError(f"계획 파일이 JSON이 아니다: {p} — {exc}") from exc
    if not isinstance(plan, dict):
        raise PlanError(f"계획 파일의 최상위는 객체여야 한다: {p} ({type(plan).__name__})")
    return plan


def validate_plan(plan: dict) -> list[dict]:
    """계획을 검사하고 정규화된 런 목록을 돌려준다.

    **어휘 밖 값은 여기서 죽인다.** `analyze_frames`는 어휘 밖 값을 경고로만 처리하는데
    (앱이 하네스보다 앞서 나갈 수 있으므로 옳다), **계획은 사람이 지금 쓰는 것**이라
    오타를 통과시키면 9런을 다 찍고 나서야 비교 불가를 알게 된다.
    """
    if not isinstance(plan.get("id"), str) or not plan["id"].strip():
        raise PlanError("계획에 문자열 id가 필요하다 (진행 파일 이름이 된다)")
    runs = plan.get("runs")
    if not isinstance(runs, list) or not runs:
        raise PlanError("계획의 runs가 비어 있거나 리스트가 아니다")

    seen: dict[int, dict] = {}
    normalized: list[dict] = []
    for i, raw in enumerate(runs):
        where = f"runs[{i}]"
        if not isinstance(raw, dict):
            raise PlanError(f"{where}가 객체가 아니다: {raw!r}")
        n = raw.get("n", i + 1)
        if not isinstance(n, int) or isinstance(n, bool):
            raise PlanError(f"{where}.n은 정수여야 한다: {n!r}")
        if n in seen:
            raise PlanError(f"{where}.n={n}이 중복이다 — 런 번호는 진행 상태의 키다")
        arm = raw.get("arm")
        if arm not in RENDER_ARMS:
            raise PlanError(
                f"{where}.arm={arm!r}은 어휘 밖이다. 허용: {', '.join(RENDER_ARMS)} "
                "(lib/frame_log.py: RENDER_ARMS)"
            )
        if arm == RENDER_ARM_SYNTHETIC:
            raise PlanError(
                f"{where}.arm={arm!r} — 합성 arm은 실기기 세션의 계획값이 될 수 없다"
            )
        lighting = raw.get("lighting")
        if lighting not in LIGHTING_CONDITIONS:
            raise PlanError(
                f"{where}.lighting={lighting!r}은 어휘 밖이다. "
                f"허용: {', '.join(LIGHTING_CONDITIONS)} (lib/frame_log.py: LIGHTING_CONDITIONS)"
            )
        if lighting in (LIGHTING_UNKNOWN, LIGHTING_SYNTHETIC):
            # 계획값으로 'unknown'을 허용하면 이 스크립트의 존재 이유가 사라진다.
            raise PlanError(
                f"{where}.lighting={lighting!r} — 이 값은 '기록되지 않음/합성'이라는 뜻이라 "
                "계획으로 쓸 수 없다. 실제로 잴 조명을 적을 것"
            )
        minutes = raw.get("minutes")
        if not isinstance(minutes, (int, float)) or isinstance(minutes, bool) or minutes <= 0:
            raise PlanError(f"{where}.minutes는 양수여야 한다: {minutes!r}")
        row = {
            "n": n,
            "arm": arm,
            "lighting": lighting,
            "minutes": float(minutes),
            "purpose": str(raw.get("purpose") or ""),
            "role": raw.get("role"),
            "compare_to": raw.get("compare_to"),
            "noise_pair": raw.get("noise_pair"),
            "diff_baseline": bool(raw.get("diff_baseline")),
            "build_type": str(raw.get("build_type") or plan.get("build_type") or "release"),
        }
        seen[n] = row
        normalized.append(row)

    for row in normalized:
        for key in ("compare_to", "noise_pair"):
            ref = row.get(key)
            if ref is None:
                continue
            if ref not in seen:
                raise PlanError(f"runs[n={row['n']}].{key}={ref!r} — 그런 런 번호가 없다")
            if seen[ref]["lighting"] != row["lighting"]:
                raise PlanError(
                    f"runs[n={row['n']}].{key}={ref} — 조명이 다르다 "
                    f"({row['lighting']} vs {seen[ref]['lighting']}). 조명이 다르면 비교가 "
                    "아니라 착시다 (baseline_diff CONDITION_KEYS와 같은 이유)"
                )
        pair = row.get("noise_pair")
        if pair is not None and seen[pair]["arm"] != row["arm"]:
            raise PlanError(
                f"runs[n={row['n']}].noise_pair={pair} — arm이 다르다. 노이즈 바닥은 "
                "**같은 arm 반복**에서만 나온다"
            )
    return normalized


def plan_fingerprint(rows: list[dict]) -> str:
    """계획이 바뀌었는지 보는 지문. 바뀐 계획에 옛 결과를 얹으면 조용히 어긋난다."""
    import hashlib

    keys = ("n", "arm", "lighting", "minutes", "compare_to", "noise_pair", "diff_baseline")
    payload = json.dumps(
        [{k: r.get(k) for k in keys} for r in rows], sort_keys=True, ensure_ascii=False
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:16]


# ══ 진행 상태 ══════════════════════════════════════════════════════════════
def state_path(out_root: Path, session_id: str) -> Path:
    """진행 파일 위치. **run_ts 디렉토리와 모양이 다르게** `_sessions/` 아래에 둔다 —
    경로만 봐도 "이건 한 번의 실행 산출물이 아니라 세션 상태"임이 드러나야 한다."""
    return out_root / STAGE / "_sessions" / f"{session_id}.json"


def load_state(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        with path.open("r", encoding="utf-8") as f:
            return json.load(f)
    except json.JSONDecodeError as exc:
        raise PlanError(
            f"진행 파일이 깨졌다: {path} — {exc}. 손으로 고치지 말고 --restart로 "
            "새 세션을 시작할 것(옛 파일은 이름을 바꿔 보존된다)"
        ) from exc


def save_state(path: Path, state: dict) -> None:
    """원자적으로 쓴다. 중간에 죽어서 진행 파일이 반만 남으면 재개가 막힌다.

    ⚠ **여기서 예외가 나면 세션 기록을 통째로 잃는다.** 실제로 그랬다: 파이프로 들어온
    한글 입력이 서로게이트로 디코딩돼(아래 `ensure_utf8_stdin` 참고) `json.dump`가
    UnicodeEncodeError로 죽었고, 그 직전까지의 진행이 저장되지 않았다. 원인은 입력 쪽에서
    고쳤지만, **저장 실패는 재개 보장을 깨므로** 마지막 방어선을 하나 더 둔다 —
    조용히 넘어가지 않고 크게 경고한 뒤 문제 문자만 치환해서라도 기록은 남긴다.
    """
    safe_mkdir(path.parent)
    tmp = path.with_suffix(".json.tmp")
    try:
        with tmp.open("w", encoding="utf-8") as f:
            json.dump(state, f, indent=2, ensure_ascii=False, sort_keys=True)
            f.write("\n")
    except UnicodeEncodeError as exc:
        LOG.error(
            "⚠ 진행 파일에 인코딩할 수 없는 문자가 있다 (%s) — 문제 문자를 치환해 저장한다. "
            "어딘가에서 잘못 디코딩된 텍스트가 들어온 것이니 위 입력을 확인할 것", exc,
        )
        with tmp.open("w", encoding="utf-8", errors="replace") as f:
            json.dump(state, f, indent=2, ensure_ascii=False, sort_keys=True)
            f.write("\n")
    os.replace(tmp, path)


def new_state(plan: dict, rows: list[dict], args) -> dict:
    return {
        "session_id": args.session_id or plan["id"],
        "plan_id": plan["id"],
        "plan_title": plan.get("title") or "",
        "plan_source": args.plan or "builtin",
        "plan_fingerprint": plan_fingerprint(rows),
        "warmup_sec": args.warmup_sec,
        "allow_dirty_build": bool(args.allow_dirty_build),
        "invocations": [],
        "runs": [
            dict(r, status="pending", attempts=[], skip_reason=None) for r in rows
        ],
    }


def pending_rows(state: dict) -> list[dict]:
    """남은 런 = pending + failed. **어긋난 런은 완료가 아니다.**"""
    return [r for r in state["runs"] if r["status"] in ("pending", "failed")]


# ══ 자식 스크립트 호출 ══════════════════════════════════════════════════════
class ChildResult:
    def __init__(self, cmd: list[str], returncode: int, stdout: str, out_dir: Path):
        self.cmd = cmd
        self.returncode = returncode
        self.stdout = stdout
        self.out_dir = out_dir

    def json(self, name: str) -> dict | None:
        p = self.out_dir / name
        if not p.exists():
            return None
        try:
            with p.open("r", encoding="utf-8") as f:
                return json.load(f)
        except json.JSONDecodeError:
            return None


def _child_run_ts(out_root: Path, stage: str) -> str:
    """자식이 쓸 run_ts를 **미리 정한다.** 그래야 산출물 경로를 stdout 파싱 없이 안다.
    충돌 회피는 run_utils의 것을 그대로 쓴다(측정치를 덮어쓰지 않는다)."""
    ts, _ = unique_run_ts(out_root, stage, now_run_ts())
    return ts


def invoke_child(
    script: str, stage: str, extra: list[str], args, echo_level: int = logging.INFO
) -> ChildResult:
    out_root = Path(args.out_root)
    ts = _child_run_ts(out_root, stage)
    cmd = [
        sys.executable,
        str(_SCRIPTS_DIR / script),
        "--run_ts", ts,
        "--out_root", str(out_root),
        "--log_root", str(args.log_root),
        "--log_level", args.log_level,
        *extra,
    ]
    env = dict(os.environ)
    # 자식이 한글을 UTF-8로 뱉게 강제한다. Windows 기본 코드페이지로 파이프에 쓰면
    # 우리 쪽 디코딩에서 깨진다(ensure_utf8_console은 콘솔만 다룬다).
    env["PYTHONIOENCODING"] = "utf-8"
    # 자식이 스탬프 없이 돌지 않도록, 부모가 outputs 비활성이어도 환경변수를 물려주지 않는다.
    env.pop("NW_NO_OUTPUTS", None)
    env.pop("NW_NO_CMDLOG", None)
    LOG.info("$ %s", " ".join(cmd))
    proc = subprocess.run(
        cmd,
        # ⚠ **stdin을 물려주지 않는다.** 자식이 부르는 `adb shell`은 stdin을 읽어 기기로
        #   흘려보내므로, 파이프로 들어온 우리 입력(사람의 응답)을 통째로 삼킨다. 실제로
        #   그렇게 첫 프롬프트가 즉시 EOF가 되어 세션이 시작하자마자 중단됐다.
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
    )
    stdout = proc.stdout or ""
    # **자식 출력을 요약하지 않고 원문 그대로 남긴다** (pull_frames의 adb 원문과 같은 원칙).
    # 런 목록 조회처럼 매 런 두 번 도는 호출만 DEBUG로 낮춘다 — 그 원문은 자식 자신의
    # 로그 파일과 pull_result.json에 그대로 남고(경로를 아래에 찍는다), 여기서까지 21줄씩
    # 흘리면 정작 봐야 할 어긋남 배너가 스크롤 위로 밀린다.
    for line in stdout.splitlines():
        LOG.log(echo_level, "  [%s] %s", Path(script).stem, line)
    LOG.log(echo_level, "  [%s] exit=%s", Path(script).stem, proc.returncode)
    if echo_level < logging.INFO:
        LOG.info(
            "  [%s] exit=%s — 출력 원문은 %s 와 자식 로그에 있다",
            Path(script).stem, proc.returncode, out_root / stage / ts,
        )
    return ChildResult(cmd, proc.returncode, stdout, out_root / stage / ts)


def adb_args(args) -> list[str]:
    """adb 기기 선택 인자. **`--package`는 여기 없다** — `analyze_frames`는 그 인자를
    모르고, 넣으면 집계가 인자 오류로 죽는다(그러면 그 런의 숫자를 통째로 잃는다)."""
    out = []
    if args.serial:
        out += ["--serial", args.serial]
    if args.adb:
        out += ["--adb", args.adb]
    return out


def device_args(args) -> list[str]:
    """pull_frames용 — adb 인자 + 패키지."""
    out = adb_args(args)
    if args.package:
        out += ["--package", args.package]
    return out


def list_device_runs(args) -> tuple[list[str] | None, str | None]:
    """(런 이름 목록, 실패 사유). 기기에 런이 0개인 것은 실패가 아니다."""
    child = invoke_child(
        PULL_SCRIPT, PULL_STAGE, ["--list", *device_args(args)], args,
        echo_level=logging.DEBUG,
    )
    result = child.json("pull_result.json")
    if child.returncode == pull_frames_script.EXIT_ADB_NOT_FOUND:
        return None, (
            "adb를 찾지 못했다 — 기기 런을 확인할 수 없으므로 계획 대조가 불가능하다. "
            "ANDROID_HOME/platform-tools를 확인하거나 --adb로 경로를 줄 것. "
            "(위 pull_frames 출력 원문 참고)"
        )
    if child.returncode == pull_frames_script.EXIT_DEVICE_PROBLEM:
        reason = (result or {}).get("reason") or "(pull_result 없음)"
        return None, f"기기를 정하지 못했다 — {reason}"
    if result is None:
        return None, (
            f"pull_frames --list가 결과 파일을 남기지 않았다 (exit={child.returncode}). "
            "위 출력 원문을 볼 것"
        )
    runs = result.get("runs_available")
    if not isinstance(runs, list):
        runs = []
    if child.returncode == pull_frames_script.EXIT_PULL_FAILED and not runs:
        # 기기에 런이 0개. 세션 **시작 시점에는 정상**이다(아직 안 찍었다).
        # 다만 exit 4에는 "권한 거부라 목록을 못 읽었다"도 섞여 있으므로 **사유를 그대로
        # 띄운다** — 그걸 삼키면 권한 문제가 "아직 안 찍었다"로 둔갑한다.
        LOG.warning(
            "기기에서 회수 가능한 런을 찾지 못했다 (pull_frames exit=4). 사유: %s",
            (result or {}).get("reason") or "(pull_result에 사유 없음)",
        )
        return [], None
    if child.returncode not in (pull_frames_script.EXIT_OK, pull_frames_script.EXIT_PULL_FAILED):
        return None, f"pull_frames --list 실패 (exit={child.returncode})"
    return runs, None


# ══ 회수한 런 검사 ═════════════════════════════════════════════════════════
def recv_span_sec(frames_csv: Path, warmup_sec: float) -> tuple[float | None, float | None]:
    """(전체 span, warmup 이후 span) — 둘 다 `t_recv_ns`의 **실제 span**이다.

    ⚠ `p50 × 표본수`로 유도하지 않는다. 그건 다른 값이고, 실제로 그 유도값이 베이스라인
    표에 지속시간으로 잘못 적힌 적이 있다 (docs/baselines/README.md).
    """
    if not frames_csv.exists():
        return None, None
    values: list[int] = []
    try:
        with frames_csv.open("r", encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            if reader.fieldnames is None or "t_recv_ns" not in reader.fieldnames:
                return None, None
            for row in reader:
                raw = (row.get("t_recv_ns") or "").strip()
                if not raw:
                    continue
                try:
                    values.append(int(float(raw)))
                except ValueError:
                    continue
    except OSError:
        return None, None
    if len(values) < 2:
        return None, None
    t0, tend = values[0], max(values)
    total = (tend - t0) / 1e9
    cutoff = t0 + int(warmup_sec * 1e9)
    after = [v for v in values if v >= cutoff]
    analysis = (max(after) - min(after)) / 1e9 if len(after) >= 2 else 0.0
    return round(total, 1), round(analysis, 1)


def _dirty_flag(session: dict):
    """`build.git_dirty`는 앱이 **문자열 "true"**로 적는다(실측 세션에서 확인).
    불리언으로만 읽으면 문자열 "false"가 참으로 뒤집히거나 "true"가 무시된다."""
    build = session.get("build")
    if not isinstance(build, dict) or "git_dirty" not in build:
        return None
    raw = build.get("git_dirty")
    if isinstance(raw, bool):
        return raw
    return str(raw).strip().lower() in {"true", "1", "yes", "on"}


def _dig(obj, path: tuple[str, ...]) -> tuple[object, bool]:
    """(값, 마지막 키가 실제로 있었는가). 경로는 [FALLBACK_FIELD_PATH] 하나에서 온다.

    `key_present`를 함께 돌려주는 이유: **명시적 `null`과 "키가 없다"는 다른 사실이다.**
    둘 다 "말할 수 없다"로 판정되지만, 사유 문장이 "필드가 없다"로 뭉개지면 사람이
    엉뚱한 곳(스키마 버전)을 뒤진다.
    """
    cur = obj
    for key in path:
        if not isinstance(cur, dict) or key not in cur:
            return None, False
        cur = cur[key]
    return cur, True


def _frame_count(raw) -> int | float | None:
    """프레임 수로 읽는다. **절삭하지 않는다.**

    `int(137.9)`는 사유 문장에 137을 찍어 **로그 원문과 다른 숫자**를 남기고,
    `int(0.4)`는 0이 되어 폴백을 없던 일로 만든다. 정수가 아닌 값이 오면 그대로 들고
    간다 — 그 자체가 이상한 사실이므로 감추지 않는다.
    """
    if raw is None or isinstance(raw, bool):
        # 불리언은 이 필드의 값이 아니다 — 프레임 수를 True/False로 읽지 않는다.
        return None
    if isinstance(raw, int):
        return raw
    if isinstance(raw, float):
        return int(raw) if raw.is_integer() else raw
    text = str(raw).strip()
    try:
        return int(text)
    except ValueError:
        pass
    try:
        num = float(text)
    except ValueError:
        return None  # 숫자로 읽을 수 없으면 판단하지 않는다 (참고 사항으로 나간다)
    return int(num) if num.is_integer() else num


def fallback_facts(session: dict) -> dict:
    """앱이 신고한 패스스루 폴백 사실. **해석하지 않고 그대로 옮긴다** ([FALLBACK_FIELD]).

    `present=False`는 **`value=0`과 다르다.** 스키마가 낮은 옛 로그
    (`docs/baselines/20260730_poc_empty_a34.json`은 `schema_version=1`이고 이 필드가
    아예 없다)는 폴백이 없었다고도 있었다고도 말할 수 없다. 없는 것을 0으로 채우면
    없는 근거를 만드는 것이므로, 그때는 사실만 남기고 판단하지 않는다.
    """
    raw, key_present = _dig(session, FALLBACK_FIELD_PATH)
    emitted = session.get("frames_emitted")
    return {
        "present": raw is not None,
        # 키가 있는데 null인가 (판정은 같고 사유 문장이 다르다)
        "key_present": key_present,
        "raw": raw,
        "value": _frame_count(raw),
        # 폴백이 **전량인지 일부인지**를 사유 문장에서 드러내려면 분모가 필요하다.
        "frames_emitted": (
            emitted if isinstance(emitted, int) and not isinstance(emitted, bool) else None
        ),
    }


def fallback_is_mismatch(arm, value: int | float | None) -> bool:
    """이 폴백을 어긋남으로 볼 것인가. (`value`는 [fallback_facts]의 `value`)

    🔴 **`passthrough` arm에는 걸지 않는다.** 확인한 사실 둘: (1) 렌더러는
    `arm == PASSTHROUGH`면 `dispatchDraw`에서 카운터를 **증가시키기 전에 바로 반환**한다
    (`PassthroughRenderer.kt` 552-555 — 증가 지점 4곳은 모두 그 아래다), (2) 승격본 중
    passthrough 런 3개(`20260731_night_lit_passthrough_a34`,
    `20260731_night_dark_passthrough_a34`, `20260801_indoor_passthrough_rebase_a34`)가
    전부 `0`이다. 그 arm에서는 패스스루로 그리는 것이 정상 동작이므로, 만약 0이 아닌 값이
    나오면 그건 판단할 근거가 없는 새 사실이다 — 어긋남으로 단정하지 않고 참고로 남긴다.
    """
    return value is not None and value != 0 and arm != RENDER_ARM_PASSTHROUGH


def _done_attempt(row: dict) -> dict | None:
    for a in reversed(row.get("attempts") or []):
        if a.get("conforms"):
            return a
    return None


def fallback_for_report(attempt: dict) -> dict:
    """리포트에 실을 폴백 사실. 저장된 attempt에 없으면 **회수해 둔 로그에서 다시 읽는다.**

    이 검사가 생기기 전에 기록된 attempt에는 `observed`에 폴백 필드가 없다. 그 런은
    `conforms=true`로 굳어 있으므로(실제로 `s4_combo` #5가 그렇다) 리포트가 그 사실을
    말해 주지 않으면 무효 런이 계속 유효한 것처럼 인용된다.

    🔴 **`status`/`conforms`를 사후에 고쳐 쓰지 않는다.** 기록을 다시 쓰면 어느 실행이
    무엇을 판정했는지가 사라진다. attempt는 "그 실행이, 그때 있던 검사로 어긋남을 찾지
    못했다"는 **참인 기록**이다. 여기서는 표시를 더할 뿐이고, 재분류는 사람이 다시 찍거나
    세션을 새로 여는 일로 남긴다.
    """
    obs = attempt.get("observed") or {}
    if "no_passthrough_fallback" in obs:
        return {
            "source": "attempt",
            "value": obs.get("frames_fell_back_to_passthrough"),
            "frames_emitted": obs.get("frames_emitted"),
            "ok": obs.get("no_passthrough_fallback"),
            "arm": obs.get("render_arm"),
            # 통과한 attempt에도 이 키를 채운다 — passthrough arm은 0이 아니어도 통과하므로
            # (참고 사항으로만 나간다) `mismatch` 없이 두면 리포트가 그 값을 "말할 수 없다"로
            # 잘못 표시한다.
            "mismatch": fallback_is_mismatch(
                obs.get("render_arm"), obs.get("frames_fell_back_to_passthrough")
            ),
        }
    path = attempt.get("session_json")
    if not path or not Path(path).exists():
        return {
            "source": "unavailable",
            "value": None, "frames_emitted": None, "ok": None,
            "arm": obs.get("render_arm"),
        }
    try:
        session = read_session(Path(path))
    except (OSError, json.JSONDecodeError) as exc:
        # 옛 런의 로그가 깨져 있다고 리포트 전체를 잃지 않는다. 사실만 남긴다.
        return {
            "source": "unreadable",
            "value": None, "frames_emitted": None, "ok": None,
            "arm": obs.get("render_arm"), "reason": str(exc),
        }
    facts = fallback_facts(session)
    return {
        "source": "session_json_recheck",
        "value": facts["value"],
        "frames_emitted": facts["frames_emitted"],
        "ok": None if facts["value"] is None else facts["value"] == 0,
        "arm": session.get("render_arm") or obs.get("render_arm"),
        "mismatch": fallback_is_mismatch(session.get("render_arm"), facts["value"]),
    }


def run_fallback(row: dict) -> dict | None:
    """계획 칸 하나의 폴백 사실 (통과한 attempt가 없으면 None).

    `print_plan`과 `build_report`가 **같은 함수**를 쓴다 — 표시가 화면마다 다르면
    어느 쪽이 맞는지 사람이 알 수 없다.
    """
    a = _done_attempt(row)
    return fallback_for_report(a) if a else None


def fallback_phrase(n: int, fb: dict | None) -> str:
    """`#5(drago_clahe_fused) 폴백 20032/20032` — 블록 안에 넣을 짧은 사실."""
    if not fb:
        return f"#{n} 폴백 기록 없음(통과한 attempt가 없다)"
    arm = fb.get("arm") or "?"
    if fb.get("value") is None:
        return f"#{n}({arm}) 폴백 확인 불가(source={fb.get('source')})"
    return f"#{n}({arm}) 폴백 {fb['value']}/{fb.get('frames_emitted')}"


def fallback_integrity(
    fb_by_run: dict, run_ns: list[int], what: str, consequence: str
) -> dict:
    """이 블록의 숫자를 인용할 수 있는가. **근거는 앱이 신고한 폴백 사실뿐이다.**

    🔴 **숫자가 인용되는 자리에 표시가 있어야 한다.** 런 표와 caveats에만 표시를 두면,
    사람은 arm 차분 블록의 Δ 줄만 복사해 간다 — 이 검사가 막으려던 실패 양식이 정확히
    거기 남는다(`s4_combo` #5가 그랬다). 그래서 블록마다 같은 사실을 다시 싣는다.

    `quotable`: False=어긋남으로 잡힌 폴백 런이 섞였다 / None=폴백 사실을 확인할 수 없다 /
    True=어긋남으로 잡힌 폴백이 없다(`runs_fallback_nonzero_not_flagged`가 비어 있지 않으면
    0이 아닌 값이 있다는 뜻이므로 note를 함께 읽어야 한다).
    (사람이 숫자를 보고 판단하게 두지 않는다 — 불리언으로 명시한다.)
    """
    flagged, unknown, nonzero_not_flagged = [], [], []
    for n in run_ns:
        fb = fb_by_run.get(n)
        if fb and fb.get("mismatch"):
            flagged.append(n)
        elif not fb or fb.get("ok") is None:
            unknown.append(n)
        elif fb.get("value"):
            # 0이 아니지만 어긋남은 아니다 (passthrough arm) — 숫자를 감추지 않는다.
            nonzero_not_flagged.append(n)

    if flagged:
        quotable = False
        note = (
            f"🔴 {what}의 숫자를 인용하지 말 것 — "
            + ", ".join(fallback_phrase(n, fb_by_run.get(n)) for n in flagged)
            + ". 앱이 그 arm을 그리지 못해 패스스루로 대신 그렸다고 스스로 신고했다 — "
            + consequence
        )
    elif unknown:
        quotable = None
        note = (
            f"{what}을 인용할 수 있는지 **말할 수 없다** — "
            + ", ".join(fallback_phrase(n, fb_by_run.get(n)) for n in unknown)
            + ". 그 런이 선언한 arm으로 실제로 그려졌는지 확인할 수 없다"
            "(**값이 없는 것은 0이 아니다**) — 확인하기 전에는 인용하지 말 것"
            f" (폴백된 런이었다면 {consequence})"
        )
    elif nonzero_not_flagged:
        # arm이 passthrough인데 카운터가 0이 아니다 — 그 arm에서 패스스루로 그리는 것은
        # 정상이므로 숫자를 무효로 보지 않지만(`fallback_is_mismatch`), **"모두 0"이라고
        # 말하면 거짓이 된다.** 사실 그대로 쓴다.
        quotable = True
        note = (
            f"{what}: "
            + ", ".join(fallback_phrase(n, fb_by_run.get(n)) for n in nonzero_not_flagged)
            + f" — 0이 아니지만 arm이 {RENDER_ARM_PASSTHROUGH!r}라 어긋남으로 잡지 않았다"
            "(그 arm에서는 패스스루로 그리는 것이 정상 동작이고, 카운터가 증가하는 경로는 "
            "확인하지 못했다 = 판단할 근거가 없는 새 사실). 나머지 런 "
            f"{[n for n in run_ns if n not in nonzero_not_flagged]}은 0이다 — 이 블록을 "
            "인용할 때 이 사실을 함께 적을 것"
        )
    else:
        quotable = True
        note = (
            f"{what}의 런 {list(run_ns)} 모두 {FALLBACK_FIELD}=0 — 폴백 사실만 보면 인용 "
            "가능하다(봉투 점·바닥 폭 같은 다른 단서는 caveats를 볼 것)"
        )
    return {
        "runs": list(run_ns),
        "quotable": quotable,
        "runs_with_fallback": flagged,
        "runs_fallback_unknown": unknown,
        "runs_fallback_nonzero_not_flagged": nonzero_not_flagged,
        "per_run": {str(n): fb_by_run.get(n) for n in run_ns},
        "note": note,
    }


def planned_window_sec(row: dict, warmup_sec: float) -> float:
    return max(0.0, row["minutes"] * 60.0 - warmup_sec)


def check_against_plan(
    row: dict, session: dict, analysis_sec: float | None, warmup_sec: float, allow_dirty: bool
) -> tuple[list[str], list[str], dict]:
    """(어긋남, 참고 사항, 관측값). **어긋남이 하나라도 있으면 그 런은 실패다.**"""
    mismatches: list[str] = []
    notes: list[str] = []

    arm = session.get("render_arm")
    if arm != row["arm"]:
        mismatches.append(
            f"render_arm: 계획={row['arm']!r} vs 실제={arm!r} — "
            "앱의 arm 스피너를 바꾸지 않았을 가능성이 가장 크다"
        )
    lighting = session.get("lighting_condition")
    if lighting != row["lighting"]:
        mismatches.append(
            f"lighting_condition: 계획={row['lighting']!r} vs 실제={lighting!r} — "
            "조명 스피너를 바꾸지 않았을 가능성이 가장 크다"
            + ("(unknown은 '기록되지 않음'이라 비교 대상이 못 된다)"
               if lighting == LIGHTING_UNKNOWN else "")
        )
    build_type = session.get("build_type")
    if build_type != row["build_type"]:
        mismatches.append(
            f"build_type: 계획={row['build_type']!r} vs 실제={build_type!r} — "
            "debug 프레임타임은 실기기 근거로 쓸 수 없다"
        )
    dirty = _dirty_flag(session)
    if dirty is None:
        notes.append(
            "session.json에 build.git_dirty가 없다 — 어느 코드가 낸 숫자인지 "
            "이 로그만으로는 말할 수 없다"
        )
    elif dirty:
        msg = (
            "build.git_dirty=true — 이 APK의 소스는 어느 커밋에도 없다. "
            "커밋한 뒤 다시 빌드·설치해서 찍을 것"
        )
        if allow_dirty:
            notes.append(msg + " (--allow_dirty_build로 통과시켰다)")
        else:
            mismatches.append(msg)

    # ── 앱이 그 arm을 실제로 그렸는가. **스피너가 맞아도 여기서 무효가 될 수 있다.**
    fb = fallback_facts(session)
    if not fb["present"]:
        # 🔴 **명시적 null과 "키가 없다"를 같은 문장으로 말하지 않는다.** 판정은 둘 다
        #    "말할 수 없다"지만, null인데 "필드가 없다"고 하면 사람이 스키마 버전을
        #    뒤지느라 앱이 왜 null을 적었는지를 못 본다.
        why = (
            f"{FALLBACK_FIELD}가 명시적으로 null이다 (키는 있는데 값이 없다)"
            if fb["key_present"]
            else f"session.json에 {FALLBACK_FIELD}가 없다"
        )
        notes.append(
            f"{why} (schema_version={session.get('schema_version')!r}) — 앱이 선언한 arm으로 "
            "실제로 그렸는지 이 로그만으로는 말할 수 없다. **값이 없는 것은 0이 아니다** "
            "(그래서 어긋남으로 잡지 않았다)"
        )
    elif fb["value"] is None:
        notes.append(
            f"{FALLBACK_FIELD}를 숫자로 읽을 수 없다: {fb['raw']!r} — 폴백 여부를 "
            "판단하지 않았다"
        )
    elif fb["value"] != 0:
        emitted = fb["frames_emitted"]
        if emitted is None:
            scope = "frames_emitted를 읽지 못해 전량인지 일부인지 말할 수 없다"
        elif fb["value"] >= emitted:
            scope = "**전량 폴백** — 이 런에 그 arm으로 그린 프레임이 하나도 없다"
        else:
            scope = (
                f"일부 폴백({fb['value']}/{emitted}) — 이 런의 분포에 두 경로가 섞였다"
            )
        head = f"{FALLBACK_FIELD}={fb['value']} (frames_emitted={emitted})"
        tail = (
            f"{scope}. 프레임타임과 fps는 정상으로 보일 수 있다(그릴 것이 없으면 오히려 "
            "빠르다) — session.json의 gpu_status·offscreen_status·stage2_status에 실린 "
            "컴파일러 원문을 볼 것"
        )
        if fallback_is_mismatch(arm, fb["value"]):
            mismatches.append(
                f"{head} — 앱이 arm {arm!r}을 그리지 못해 패스스루로 대신 그렸다고 "
                f"**스스로 신고했다**. {tail}"
            )
        else:
            # 🔴 arm이 이미 passthrough인 런에 "패스스루로 대신 그렸다"고 쓰면 **자기모순**으로
            #    읽힌다(대신 그릴 것이 없다). 여기서 말할 수 있는 사실은 "그 arm에서 증가할
            #    경로를 확인하지 못한 카운터가 0이 아니다"뿐이다 — 그대로만 쓴다.
            notes.append(
                f"{head} — arm이 {arm!r}인데 이 카운터가 0이 아니다. 이 arm에서는 패스스루로 "
                "그리는 것이 정상 동작이고 카운터가 증가하는 경로를 확인하지 못했으므로 "
                "(fallback_is_mismatch 주석) **판단할 근거가 없는 새 사실**이다 — 어긋남으로 "
                f"잡지 않았고, 감추지도 않는다. {tail}"
            )

    planned = planned_window_sec(row, warmup_sec)
    if analysis_sec is None:
        mismatches.append("분석 창 길이를 재지 못했다 (frames.csv의 t_recv_ns를 읽을 수 없다)")
    else:
        floor = planned * (1.0 - DURATION_SHORT_TOLERANCE)
        if analysis_sec < floor:
            mismatches.append(
                f"분석 창이 짧다: 계획 {planned:.0f}s(={row['minutes']:g}분 − warmup "
                f"{warmup_sec:g}s) vs 실제 {analysis_sec:.0f}s "
                f"(허용 하한 {floor:.0f}s, 계획 대조용 여유 "
                f"{DURATION_SHORT_TOLERANCE * 100:g}% — 판정선이 아니다)"
            )
        if analysis_sec < targets.SUSTAINED_SEC:
            notes.append(
                f"분석 창 {analysis_sec:.0f}s < 지속 판정 창 {targets.SUSTAINED_SEC}s "
                "— 이 런은 **봉투 점**이지 지속 성능 근거가 아니다"
            )

    observed = {
        "render_arm": arm,
        "lighting_condition": lighting,
        "build_type": build_type,
        "git_dirty": dirty,
        "git_commit": (session.get("build") or {}).get("git_commit")
        if isinstance(session.get("build"), dict) else None,
        "pipeline_stages": session.get("pipeline_stages"),
        "schema_version": session.get("schema_version"),
        "frames_emitted": fb["frames_emitted"],
        # ⚠ 이 키는 **진행 파일의 스키마**이지 앱의 필드 이름이 아니다 —
        #   [FALLBACK_FIELD_PATH]에서 파생시키지 않는다. 앱이 필드를 옮겨도 이미 디스크에
        #   기록된 attempt는 이 이름으로 남아 있어야 다시 읽힌다(둘은 다른 namespace다).
        "frames_fell_back_to_passthrough": fb["value"],
        # 사람이 숫자를 보고 판단하게 두지 않는다. None = 말할 수 없다(필드 없음/읽을 수 없음).
        "no_passthrough_fallback": None if fb["value"] is None else fb["value"] == 0,
        "analysis_window_sec": analysis_sec,
        "planned_window_sec": round(planned, 1),
        "session_duration_sec": round(
            (session.get("measurement") or {}).get("duration_ns", 0) / 1e9, 1
        ) if isinstance(session.get("measurement"), dict) else None,
        "meets_sustained_window": (
            None if analysis_sec is None else analysis_sec >= targets.SUSTAINED_SEC
        ),
    }
    return mismatches, notes, observed


def matching_plan_rows(state: dict, session: dict) -> list[int]:
    """실제 조건이 **다른 계획 칸**과 맞는가. 맞으면 '순서를 착각한 것'이라고 말해 준다."""
    out = []
    for r in state["runs"]:
        if (
            r["arm"] == session.get("render_arm")
            and r["lighting"] == session.get("lighting_condition")
        ):
            out.append(r["n"])
    return out


# ══ 요약에서 숫자 뽑기 ══════════════════════════════════════════════════════
def summary_metrics(summary: dict) -> dict:
    """비교에 쓸 분포만 뽑는다. p50/p95/p99 + min/max + count를 그대로 옮긴다."""
    out: dict[str, dict] = {}
    ft = ((summary.get("frametime") or {}).get("primary")) or {}
    if ft.get("count"):
        out[FRAMETIME_KEY] = dict(ft)
    stages = summary.get("stages") or {}
    for col in COMPARE_COLUMNS:
        s = stages.get(col) or {}
        if s.get("count"):
            out[col] = dict(s)
    return out


def diff_metrics(a: dict, b: dict) -> dict:
    """b − a. 양수 = b가 느리다."""
    out: dict[str, dict] = {}
    for col in sorted(set(a) & set(b)):
        entry = {}
        for p in COMPARE_PERCENTILES:
            av, bv = a[col].get(p), b[col].get(p)
            if av is None or bv is None:
                continue
            entry[p] = {
                "a_ms": av,
                "b_ms": bv,
                "delta_ms": round(bv - av, 3),
                "abs_delta_ms": round(abs(bv - av), 3),
                "pct": pct_change(av, bv),
            }
        if entry:
            out[col] = entry
    return out


# ══ 사람에게 내는 안내 ═════════════════════════════════════════════════════
def print_plan(state: dict) -> None:
    LOG.info("=" * 66)
    LOG.info(
        "세션 %s — %s (계획 %s / 지문 %s)",
        state["session_id"], state.get("plan_title") or "(제목 없음)",
        state.get("plan_source"), state.get("plan_fingerprint"),
    )
    warmup = state["warmup_sec"]
    fb_flagged: list[int] = []
    fb_unknown: list[int] = []
    for r in state["runs"]:
        window = planned_window_sec(r, warmup)
        mark = {
            "done": "✔", "failed": "✘", "skipped": "→", "pending": " ",
        }.get(r["status"], "?")
        # 🔴 **`[✔]`만 보이면 무효 런이 유효한 것처럼 읽힌다.** 이 목록은 사람이 가장 먼저
        #    보는 화면이라, 폴백 사실이 여기 없으면 리포트 아래쪽 caveats까지 내려가지
        #    않는다(`s4_combo` #5가 그렇게 '통과한 11분 런'으로 보였다). 진행 파일의
        #    status는 그대로 두고 **표시만** 바꾼다.
        fb = run_fallback(r)
        if fb and fb.get("mismatch"):
            mark = "🔴"
            fb_flagged.append(r["n"])
        elif r["status"] == "done" and (not fb or fb.get("ok") is None):
            mark = "⚠"
            fb_unknown.append(r["n"])
        LOG.info(
            "  [%s] #%-2d %-12s %-18s %5.3g분 (분석 창 %.0fs)  %s",
            mark, r["n"], r["arm"], r["lighting"], r["minutes"], window, r["purpose"],
        )
        if fb and fb.get("mismatch"):
            LOG.error(
                "        ↳ 🔴 %s — 앱이 그 arm을 그리지 못해 패스스루로 대신 그렸다고 "
                "신고했다. 통과로 기록됐지만(진행 파일 status=%s) **이 칸의 숫자는 그 arm의 "
                "숫자가 아니다** (다시 찍어야 채워진다)",
                fallback_phrase(r["n"], fb), r["status"],
            )
        elif r["status"] == "done" and (not fb or fb.get("ok") is None):
            LOG.warning(
                "        ↳ ⚠ %s — 선언한 arm으로 실제로 그려졌는지 말할 수 없다 "
                "(**값이 없는 것은 0이 아니다**)",
                fallback_phrase(r["n"], fb),
            )
        elif fb and fb.get("value"):
            # 0이 아니지만 어긋남은 아니다 (passthrough arm). 숫자를 감추지 않는다.
            LOG.warning(
                "        ↳ %s — arm이 %r라 어긋남으로 잡지 않았다(참고)",
                fallback_phrase(r["n"], fb), RENDER_ARM_PASSTHROUGH,
            )
        if window < targets.SUSTAINED_SEC:
            LOG.info(
                "        ↳ 봉투 점 — 분석 창 %.0fs < 지속 판정 창 %ss "
                "(지속 근거로 쓰려면 warmup 포함 %.1f분 이상)",
                window, targets.SUSTAINED_SEC,
                (targets.SUSTAINED_SEC + warmup) / 60.0,
            )
        if r["status"] == "failed":
            last = r["attempts"][-1] if r["attempts"] else {}
            for m in last.get("mismatches", []):
                LOG.warning("        ↳ 어긋남: %s", m)
        if r["status"] == "skipped":
            LOG.warning("        ↳ 건너뜀: %s", r.get("skip_reason"))
    if fb_flagged:
        LOG.error(
            "🔴 앱이 폴백을 신고한 칸: %s — %s가 0이 아니다. 진행 파일의 status는 사후에 "
            "고쳐 쓰지 않으므로(어느 실행이 무엇을 판정했는지를 지우지 않는다) done으로 "
            "남아 있지만, **그 칸은 비어 있는 것과 같다**",
            fb_flagged, FALLBACK_FIELD,
        )
    if fb_unknown:
        LOG.warning(
            "⚠ 폴백 사실을 확인할 수 없는 통과 칸: %s — %s를 읽을 수 없다(옛 스키마이거나 "
            "회수 로그가 없다). 그 칸이 선언한 arm으로 그려졌는지 말할 수 없다",
            fb_unknown, FALLBACK_FIELD,
        )
    LOG.info("=" * 66)


def print_run_instructions(row: dict, state: dict, index: int, total: int) -> None:
    warmup = state["warmup_sec"]
    window = planned_window_sec(row, warmup)
    LOG.info("")
    LOG.info("=" * 66)
    LOG.info(
        "[남은 런 %d/%d]  계획 #%d — arm=%s  조명=%s  길이=%g분",
        index, total, row["n"], row["arm"], row["lighting"], row["minutes"],
    )
    LOG.info("목적: %s", row["purpose"] or "(없음)")
    LOG.info("-" * 66)
    LOG.info("폰에서 지금 할 것:")
    LOG.info("  1. arm 스피너를 '%s'로 바꾼다", row["arm"])
    LOG.info("  2. 조명 스피너를 '%s'로 바꾼다", row["lighting"])
    LOG.info("     (스피너를 안 바꾸고 다음 런을 시작하는 것이 가장 흔한 실수다 — "
             "회수 후 실제 값과 대조한다)")
    LOG.info("  3. 폰을 고정한다 (손으로 들면 AE/모션이 흔들려 분포가 바뀐다)")
    LOG.info(
        "  4. 측정 시작 → %g분 (warmup %g초를 빼면 분석 창 %.0fs)",
        row["minutes"], warmup, window,
    )
    LOG.info("  5. **정지 버튼으로 끝낸다** — 뒤로가기는 로그 flush를 버린다")
    LOG.info("확인:")
    LOG.info(
        "  - 커밋된 상태에서 빌드·설치된 APK인가 (git_dirty=true면 어느 코드가 낸 "
        "숫자인지 말할 수 없다%s)",
        " — 지금은 --allow_dirty_build로 통과시키는 중" if state.get("allow_dirty_build") else "",
    )
    LOG.info("  - release 빌드인가 (계획: %s)", row["build_type"])
    if window < targets.SUSTAINED_SEC:
        LOG.warning(
            "  ⚠ 이 런은 **봉투 점**이다 — 분석 창 %.0fs < 지속 판정 창 %ss. "
            "지속 성능 근거로 인용하지 말 것", window, targets.SUSTAINED_SEC,
        )
    LOG.info("-" * 66)


def ensure_utf8_stdin() -> None:
    """파이프로 들어오는 입력을 UTF-8로 읽는다. `ensure_utf8_console()`의 입력 쪽 짝이다.

    Windows에서 `sys.stdin`이 콘솔이 아니면 로케일 인코딩(cp949)으로 디코딩되어, UTF-8
    한글이 서로게이트가 된 채 통과한다. 그 문자열이 진행 파일에 들어가면 `json.dump`가
    UnicodeEncodeError로 죽어 **그 세션 기록을 통째로 잃는다**(실제로 그렇게 죽었다).
    콘솔(tty)은 건드리지 않는다 — 콘솔 입력은 이미 유니코드로 들어온다.
    """
    stream = sys.stdin
    if stream is None:
        return
    try:
        if stream.isatty():
            return
        if (getattr(stream, "encoding", "") or "").lower().replace("-", "") == "utf8":
            return
        stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError, ValueError):
        pass


def clean_text(text: str) -> str:
    """사람이 준 자유 문자열에서 인코딩 불가 문자를 없앤다(방어선).

    입력 쪽을 고쳤어도, 어떤 경로로든 서로게이트가 들어오면 진행 파일 저장이 깨진다.
    사유 메모 한 줄 때문에 세션 기록을 잃지 않는다.
    """
    return text.encode("utf-8", "replace").decode("utf-8", "replace")


def prompt(text: str) -> str | None:
    """stdin 한 줄. EOF/Ctrl-C면 None (중단으로 처리하고 상태를 저장한다)."""
    try:
        sys.stdout.flush()
        return clean_text(input(text))
    except (EOFError, KeyboardInterrupt):
        print("")
        return None


# ══ 런 하나 처리 ═══════════════════════════════════════════════════════════
def pull_device_run(name: str, args) -> tuple[dict | None, Path | None, ChildResult]:
    child = invoke_child(
        PULL_SCRIPT, PULL_STAGE, ["--run", name, *device_args(args)], args
    )
    result = child.json("pull_result.json")
    if child.returncode != pull_frames_script.EXIT_OK or not result or not result.get("ok"):
        return result, None, child
    local = None
    for r in result.get("runs") or []:
        if r.get("name") == name:
            local = Path(r["local_dir"])
    return result, local, child


def analyze_run(
    local_dir: Path, row: dict, args, session_id: str
) -> tuple[dict | None, ChildResult]:
    label = f"session {session_id} #{row['n']} {row['arm']}/{row['lighting']}"
    extra = [
        "--frames", str(local_dir / "frames.csv"),
        "--session", str(local_dir / "session.json"),
        "--warmup_sec", str(args.warmup_sec),
        "--label", label.strip(),
        *adb_args(args),
    ]
    child = invoke_child(ANALYZE_SCRIPT, ANALYZE_STAGE, extra, args)
    return child.json("summary.json"), child


def execute_run(
    row: dict, state: dict, args, before: list[str], forced_run: str | None = None
) -> dict:
    """회수 → 계획 대조 → 집계. 반환값은 attempt 기록.

    `forced_run`은 사람이 `u <이름>`으로 지목한 기존 기기 런이다. 그때도 **계획 대조는
    그대로 돈다** — 지목은 "어느 로그인지"만 정하고 "계획대로인지"는 검사가 정한다.
    """
    attempt: dict = {
        "invocation_run_ts": state["invocations"][-1]["run_ts"],
        "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "conforms": False,
        "mismatches": [],
        "notes": [],
        "device_run_selection": "forced" if forced_run else "new_since_snapshot",
    }

    # ── 어느 기기 런이 이번 것인가. **새로 생긴 런**으로 찾는다.
    device_run = forced_run
    if device_run is None:
        after, why = list_device_runs(args)
        if after is None:
            attempt["mismatches"].append(f"기기 런 목록을 읽지 못했다: {why}")
            return attempt
        new = [n for n in after if n not in before]
        if not new:
            attempt["mismatches"].append(
                "기기에 새 런이 없다 — 앱이 측정을 끝내지 않았다(로그는 종료 시 한 번에 쓴다). "
                "**정지 버튼**으로 끝냈는지 확인할 것(뒤로가기는 flush를 버린다). "
                "이미 찍힌 런을 쓰려면 프롬프트에서 `u <런 이름>`"
            )
            return attempt
        if len(new) > 1:
            attempt["notes"].append(
                f"새 런이 {len(new)}개다: {new} — 가장 최근 것을 쓴다"
                f"({sorted(new, key=pull_frames_script.run_sort_key)[-1]}). "
                "한 계획 칸에 한 런이어야 하므로 나머지는 이 세션 기록에 들어가지 않는다"
            )
        device_run = sorted(new, key=pull_frames_script.run_sort_key)[-1]
    attempt["device_run"] = device_run
    LOG.info("이번 런의 기기 런 이름: %s", device_run)

    # ── 회수 (손 adb pull 금지 — pull_frames를 거쳐야 스탬프가 붙는다)
    pull_result, local_dir, pull_child = pull_device_run(device_run, args)
    attempt["pull_exit"] = pull_child.returncode
    attempt["pull_dir"] = str(pull_child.out_dir)
    if local_dir is None:
        attempt["mismatches"].append(
            f"회수 실패 (pull_frames exit={pull_child.returncode}) — "
            f"{(pull_result or {}).get('reason') or '위 pull_frames 출력 원문 참고'}"
        )
        return attempt
    attempt["local_dir"] = str(local_dir)
    attempt["frames_csv"] = str(local_dir / "frames.csv")
    attempt["session_json"] = str(local_dir / "session.json")

    # ── 계획 대조. **여기가 이 스크립트의 존재 이유다.**
    session = read_session(local_dir / "session.json")
    _, analysis_sec = recv_span_sec(local_dir / "frames.csv", args.warmup_sec)
    mismatches, notes, observed = check_against_plan(
        row, session, analysis_sec, args.warmup_sec, bool(args.allow_dirty_build)
    )
    attempt["observed"] = observed
    attempt["mismatches"] += mismatches
    attempt["notes"] += notes
    if mismatches:
        others = [n for n in matching_plan_rows(state, session) if n != row["n"]]
        if others:
            attempt["notes"].append(
                f"실제 조건(arm={observed['render_arm']}, "
                f"조명={observed['lighting_condition']})은 계획 #{others} 칸과 맞는다 — "
                "런 순서를 착각했을 수 있다"
            )

    # ── 집계는 어긋난 런에서도 돌린다. 숫자를 버리지 않는다(다만 계획 칸은 못 채운다).
    summary, analyze_child = analyze_run(local_dir, row, args, state["session_id"])
    attempt["analyze_exit"] = analyze_child.returncode
    attempt["analyze_dir"] = str(analyze_child.out_dir)
    if summary is None:
        attempt["mismatches"].append(
            f"집계 실패 (analyze_frames exit={analyze_child.returncode}) — 위 출력 원문 참고"
        )
        return attempt
    attempt["summary_path"] = str(analyze_child.out_dir / "summary.json")
    attempt["summary_run_ts"] = summary.get("run_ts")
    attempt["verdict"] = summary.get("verdict")
    attempt["safety_regression"] = summary.get("safety_regression")
    attempt["metrics"] = summary_metrics(summary)
    attempt["analyze_warnings"] = summary.get("warnings") or []
    attempt["conforms"] = not attempt["mismatches"]
    return attempt


def print_attempt_result(row: dict, attempt: dict) -> None:
    if attempt["conforms"]:
        LOG.info("-" * 66)
        obs = attempt.get("observed") or {}
        # 통과 쪽에도 **근거를 찍는다** — 폴백이 0이었다는 사실이 보이지 않으면 이 검사가
        # 돌았는지 사람이 알 수 없고, "말할 수 없다"(필드 없음)와도 구별되지 않는다.
        fb_text = (
            f"{obs['frames_fell_back_to_passthrough']}/{obs.get('frames_emitted')} 프레임"
            if obs.get("frames_fell_back_to_passthrough") is not None
            # ⚠ "필드가 없다"고 단정하지 않는다 — 명시적 null·숫자 아님도 여기로 온다.
            #    정확한 사유는 바로 아래 참고 사항 줄에 있다.
            else f"{FALLBACK_FIELD} 값을 읽지 못했다 — 말할 수 없다 (사유는 아래 ⚠)"
        )
        LOG.info(
            "✔ 계획 #%d 통과 — arm=%s 조명=%s 분석 창 %.0fs, 패스스루 폴백 %s",
            row["n"], obs.get("render_arm"), obs.get("lighting_condition"),
            obs.get("analysis_window_sec") or 0.0, fb_text,
        )
    else:
        LOG.error("!" * 66)
        LOG.error("!! 계획 #%d 어긋남 — 이 런은 계획 칸을 채우지 못한다", row["n"])
        for m in attempt["mismatches"]:
            LOG.error("!!   - %s", m)
        LOG.error(
            "!! 이 칸은 여전히 **남은 런**이다. 조건을 고쳐 다시 찍을 것 "
            "(이 실행을 끝내도 진행 파일에 남아 다음 실행에서 다시 제시된다)"
        )
        LOG.error("!" * 66)
    for n in attempt.get("notes", []):
        LOG.warning("⚠ %s", n)
    m = attempt.get("metrics") or {}
    arm = (attempt.get("observed") or {}).get("render_arm")
    if m:
        # **arm 없이 단계 비용 숫자를 찍지 않는다** (58a7522와 같은 규칙).
        LOG.info("이 런의 분포 [arm=%s] (판정은 analyze_frames가 낸다):", arm)
        for col in [FRAMETIME_KEY] + [c for c in COMPARE_COLUMNS if c in m]:
            s = m.get(col)
            if not s:
                continue
            LOG.info(
                "  %-24s p50=%-8s p95=%-8s p99=%-8s min=%-8s max=%-8s (n=%s, arm=%s)",
                col, s.get("p50"), s.get("p95"), s.get("p99"), s.get("min"), s.get("max"),
                s.get("count"), arm,
            )
    v = attempt.get("verdict") or {}
    if v:
        LOG.info(
            "  analyze 판정: %s=%s / %s=%s (판정선은 lib/targets.py)",
            targets.fps_target_label(), v.get("meets_fps_target"),
            targets.p95_target_label(), v.get("meets_p95_target"),
        )


# ══ 세션 리포트 ════════════════════════════════════════════════════════════
def build_report(state: dict, args, run_ts: str) -> dict:
    rows = {r["n"]: r for r in state["runs"]}
    done = {n: _done_attempt(r) for n, r in rows.items()}
    done = {n: a for n, a in done.items() if a}

    report: dict = {
        "session_id": state["session_id"],
        "run_ts": run_ts,
        "plan_id": state.get("plan_id"),
        "plan_fingerprint": state.get("plan_fingerprint"),
        "warmup_sec": state["warmup_sec"],
        "generated_from": "scripts/run_session.py --report",
        "runs": [],
        "noise_floor": {"available": False},
        "arm_deltas": [],
        "baseline_diff": None,
        "safety_regression": {
            # 성능만 있는 보고는 불완전한 보고다 (conventions §6). 탐지 미구현이라
            # 미평가이며, **보고에서 빼지 않는다.**
            "evaluated": False,
            "reason": "탐지 단계 미구현 — 위험물 강조 누락률을 아직 잴 수 없다",
            "per_run": {},
        },
        "caveats": [],
    }

    fallback_flagged: list[int] = []   # 앱이 폴백을 신고한 통과 런 (그 숫자는 무효다)
    fallback_unknown: list[int] = []   # 폴백 사실을 확인할 수 없는 통과 런
    # 런별 폴백 사실을 **한 번 모아서** 아래 블록들(노이즈 바닥·arm 차분)이 같이 쓴다.
    # 블록마다 따로 읽으면 같은 세션 안에서 표시가 달라질 수 있다.
    fb_by_run: dict[int, dict | None] = {}
    for n in sorted(rows):
        r = rows[n]
        a = done.get(n)
        obs = (a or {}).get("observed") or {}
        fb = fallback_for_report(a) if a else None
        fb_by_run[n] = fb
        if fb:
            if fb.get("mismatch"):
                fallback_flagged.append(n)
            elif fb["ok"] is None:
                fallback_unknown.append(n)
        report["runs"].append({
            "n": n,
            "arm": r["arm"],
            "lighting": r["lighting"],
            "planned_minutes": r["minutes"],
            "purpose": r["purpose"],
            "status": r["status"],
            "skip_reason": r.get("skip_reason"),
            "device_run": (a or {}).get("device_run"),
            "summary_path": (a or {}).get("summary_path"),
            "analysis_window_sec": obs.get("analysis_window_sec"),
            "meets_sustained_window": obs.get("meets_sustained_window"),
            "envelope_only": (
                None if obs.get("meets_sustained_window") is None
                else not obs["meets_sustained_window"]
            ),
            # 앱이 그 arm을 실제로 그렸는가. **분포보다 먼저 봐야 하는 값이다** —
            # 폴백한 런의 프레임타임은 정상으로 보인다.
            "passthrough_fallback": fb,
            "verdict": (a or {}).get("verdict"),
            "metrics": (a or {}).get("metrics"),
        })
        if a:
            report["safety_regression"]["per_run"][str(n)] = a.get("safety_regression")

    # ── 노이즈 바닥. **이걸 먼저 확정하지 않으면 arm 차분을 신호로 읽을 수 없다.**
    #
    # 🔴 **바닥은 arm마다 따로 귀속시킨다.** 계획은 반복 쌍을 여럿 담을 수 있고
    #    (`s5_bf`는 chain_bf 9패스 / fused_bf 8패스 / highlight_boxes 4패스 세 쌍이다),
    #    패스 수·SSBO 구성이 다르면 실행 간 변동 성질도 다르다. 옛 구현은 `pairs[:1]`로
    #    **첫 쌍만** 쓰고 그 폭을 모든 arm 차분에 붙였다 — 4패스 오버레이 arm에 9패스 arm의
    #    바닥이 **무표시로** 붙었고, 거기서 나온 '바닥 초과 → 신호'는 근거가 없었다.
    #    그래서 (1) 모든 쌍을 쓰고, (2) 차분에는 **그 arm 자신의** 바닥만 붙이고,
    #    (3) 그 arm의 바닥이 없으면 다른 arm의 것으로 갈음하지 않고 판정 불가로 둔다.
    #
    # ⚠ 여기서 하는 대조는 **arm 문자열이 같은가**까지다. "4패스니까 비슷하다" 같은 판단을
    #   넣지 않는다 — 하네스는 arm의 의미를 해석하지 않는다(`lib/frame_log.py`).
    floor_by_arm: dict[str, dict] = {}
    pair_blocks: list[dict] = []
    duplicate_arm_pairs: list[dict] = []
    pairs = [
        (n, rows[n]["noise_pair"]) for n in sorted(rows)
        if rows[n].get("noise_pair") is not None and rows[n]["noise_pair"] > n
    ]
    for n, pair in pairs:
        arm = rows[n]["arm"]
        a, b = done.get(n), done.get(pair)
        if not a or not b:
            pair_blocks.append({
                "available": False,
                "pair": [n, pair],
                "arm": arm,
                "reason": (
                    f"반복 런 #{n}·#{pair} 중 통과한 것이 부족하다 — 이 arm({arm})의 노이즈 "
                    "바닥이 없으면 이 arm의 차분이 신호인지 판단할 수 없다"
                ),
            })
            continue
        d = diff_metrics(a["metrics"], b["metrics"])
        block = {
            "available": True,
            "pair": [n, pair],
            "arm": arm,
            "lighting": rows[n]["lighting"],
            "device_runs": [a["device_run"], b["device_run"]],
            "columns": d,
            # 🔴 **폴백된 런으로 잡은 폭은 바닥이 아니다.** 그 런은 그 arm을 그리지 않았으므로
            #    반복 쌍이 같은 코드 경로를 두 번 잰 것이 아니다 — 이 블록의 숫자와 그것을
            #    쓰는 모든 '바닥 초과' 판단이 근거를 잃는다.
            "passthrough_fallback": fallback_integrity(
                fb_by_run, [n, pair], f"이 노이즈 바닥(arm={arm})",
                "그 런은 그 arm을 그리지 않았으므로 이 폭은 같은 코드 경로를 두 번 잰 것이 "
                "아니고, 이 바닥을 쓰는 '바닥 초과/이내' 판단 전체가 근거를 잃는다",
            ),
            "note": (
                f"같은 arm({arm})·같은 조명({rows[n]['lighting']})을 두 번 찍은 "
                "차이다. **이 폭 안의 (이 arm의) 차분은 신호가 아니다.** 다만 반대는 성립하지 "
                "않는다 — 이 바닥은 **차이를 한 번 관측한 것**이므로 실제 실행 간 변동의 "
                "하한이고(반복을 더 하면 커질 수 있다), 바닥을 넘었다는 것은 신호의 "
                "필요조건이지 충분조건이 아니다. **다른 arm에 물려 쓰지 않는다** "
                "(분산이 arm마다 다르다). 다른 조명에 적용하는 것도 근사이며 야간 런에는 "
                "특히 그렇다"
            ),
        }
        pair_blocks.append(block)
        if arm in floor_by_arm:
            # 같은 arm의 쌍이 둘 이상이다. 첫 쌍을 쓰고 **그 사실을 남긴다** — 조용히
            # 덮어쓰면 어느 쌍이 판정에 쓰였는지 사라진다.
            duplicate_arm_pairs.append({
                "arm": arm, "used_pair": floor_by_arm[arm]["pair"], "ignored_pair": [n, pair],
            })
            continue
        floor_by_arm[arm] = {
            "arm": arm,
            "pair": [n, pair],
            "device_runs": [a["device_run"], b["device_run"]],
            "lighting": rows[n]["lighting"],
            "floors": {
                col: {p: v["abs_delta_ms"] for p, v in cols.items()}
                for col, cols in d.items()
            },
            # 바닥을 잡은 런 자체가 폴백됐으면 그 바닥을 쓰는 모든 '바닥 초과' 판단도
            # 근거가 없다. 차분 블록에 **함께 싣기 위해** 요약을 여기 보관한다.
            "integrity_brief": {
                k: block["passthrough_fallback"][k]
                for k in (
                    "runs", "quotable", "runs_with_fallback",
                    "runs_fallback_unknown", "note",
                )
            },
        }

    measured_pairs = [b for b in pair_blocks if b.get("available")]
    if not pairs:
        report["noise_floor"] = {
            "available": False,
            "pairs": [],
            "arms_measured": [],
            "reason": (
                "계획에 반복 쌍(noise_pair)이 없다 — 같은 arm을 두 번 찍지 않으면 열별 "
                "노이즈 바닥이 없고, arm 차분이 신호인지 판단할 수 없다"
            ),
        }
    else:
        report["noise_floor"] = {
            "available": bool(measured_pairs),
            # arm → 그 arm의 열별 바닥. **판정에 쓰이는 것은 이 표뿐이다.**
            "by_arm": floor_by_arm,
            "arms_measured": sorted(floor_by_arm),
            "pairs": pair_blocks,
            "duplicate_arm_pairs": duplicate_arm_pairs,
            "note": (
                "바닥은 **arm마다 그 arm의 반복 쌍에서** 나온다. 한 arm의 바닥을 다른 arm에 "
                "물려 쓰지 않는다 — 패스 수·SSBO 구성이 다르면 실행 간 변동 성질도 다르다. "
                "바닥이 없는 arm의 차분은 '판정 불가'로 남고, 다른 arm의 바닥으로 갈음하지 "
                "않는다"
            ),
        }
        if not measured_pairs:
            report["noise_floor"]["reason"] = (
                f"반복 쌍 {[b['pair'] for b in pair_blocks]}에서 통과한 쌍이 하나도 없다 — "
                "arm 차분이 신호인지 판단할 수 없다"
            )

    # ── arm 간 차분. 그 arm의 노이즈 바닥과 **나란히** 낸다.
    # 바닥을 잡은 런 자체가 폴백됐으면 그 바닥을 쓰는 '바닥 초과' 판단도 근거가 없다.
    # 그 사실을 각 블록에 **함께 싣는다** — 바닥 블록에만 두면 여기 Δ만 인용된다.
    #
    # 🔴 참고용으로 다른 arm의 바닥 **목록**을 함께 싣지만(사람이 "같은 구조의 다른 arm은
    #    이만큼이었다"를 읽을 수 있게), 그 값은 판정에 들어가지 않는다. 어느 arm이 어느
    #    arm과 "구조가 같은지"는 하네스가 판단하지 않는다.
    other_floors_ref = {
        arm: {"pair": e["pair"], "quotable": e["integrity_brief"]["quotable"]}
        for arm, e in floor_by_arm.items()
    }
    for n in sorted(rows):
        ref_n = rows[n].get("compare_to")
        if ref_n is None:
            continue
        cur, ref = done.get(n), done.get(ref_n)
        if not cur or not ref:
            report["arm_deltas"].append({
                "run": n, "reference_run": ref_n, "available": False,
                "reason": "두 런 중 하나가 아직 계획을 통과하지 못했다",
            })
            continue
        arm = rows[n]["arm"]
        # **그 arm 자신의 바닥만 쓴다.** 없으면 없는 것이다(다른 arm의 것으로 갈음하지 않음).
        floor_entry = floor_by_arm.get(arm)
        floor_source = {
            "available": True,
            "arm": floor_entry["arm"],
            "pair": floor_entry["pair"],
            "device_runs": floor_entry["device_runs"],
            "lighting": floor_entry["lighting"],
            "note": (
                f"이 Δ에 붙은 noise_floor_ms는 **arm={floor_entry['arm']}의 반복 쌍 "
                f"#{floor_entry['pair'][0]}·#{floor_entry['pair'][1]}**에서 나온 값이다"
            ),
        } if floor_entry else {
            "available": False,
            "arm": arm,
            "pair": None,
            "reason": (
                f"**이 arm({arm})의 노이즈 바닥은 이 세션에서 재지 않았다** — 계획에 이 arm의 "
                "반복 쌍(noise_pair)이 없거나 그 쌍이 통과하지 못했다. 다른 arm의 바닥으로 "
                "갈음하지 않으므로 이 Δ는 **신호 여부 판정 불가**다(exceeds_noise_floor=null)"
            ),
            # 판정에 쓰지 않는다. 사람이 읽을 참고 목록일 뿐이다.
            "other_arms_measured_in_session": other_floors_ref,
            "other_arms_note": (
                "이 세션에서 바닥을 잰 arm 목록이다. **판정에 쓰지 않았다** — arm이 다르면 "
                "변동 성질이 같다고 말할 근거가 없고(박스 개수·패스 구성 등 이 하네스가 "
                "해석하지 않는 조건이 다를 수 있다), 하네스는 arm의 의미를 해석하지 않는다"
            ),
        }
        d = diff_metrics(ref["metrics"], cur["metrics"])
        cols: dict[str, dict] = {}
        for col, per_p in d.items():
            cols[col] = {}
            for p, v in per_p.items():
                floor = (
                    (floor_entry["floors"].get(col) or {}).get(p) if floor_entry else None
                )
                cols[col][p] = {
                    "reference_ms": v["a_ms"],
                    "current_ms": v["b_ms"],
                    "delta_ms": v["delta_ms"],
                    "pct": v["pct"],
                    "noise_floor_ms": floor,
                    # 🔴 **바닥의 출처를 숫자와 같은 블록에 둔다.** `noise_floor_ms`만 있으면
                    #    어느 arm의 폭인지 알 수 없고, 그게 이 파일이 폴백 표시에서 이미 한 번
                    #    고친 실패 양식이다(사람은 Δ 줄만 복사해 간다).
                    "noise_floor_arm": floor_entry["arm"] if floor_entry else None,
                    "noise_floor_pair": floor_entry["pair"] if floor_entry else None,
                    # 바닥보다 작은 차분은 **신호가 아니다.** 바닥이 없으면 판단하지 않는다.
                    "exceeds_noise_floor": (
                        None if floor is None else abs(v["delta_ms"]) > floor
                    ),
                }
        report["arm_deltas"].append({
            "run": n,
            "reference_run": ref_n,
            "available": True,
            "arm": arm,
            "reference_arm": rows[ref_n]["arm"],
            "lighting": rows[n]["lighting"],
            "columns": cols,
            # 이 Δ의 '바닥 초과' 판정이 어느 arm의 어느 쌍에서 나온 바닥을 썼는가.
            "noise_floor_source": floor_source,
            # 🔴 **숫자가 인용되는 자리에 표시를 둔다.** 이 두 런 중 하나라도 폴백이 0이
            #    아니면 아래 Δ는 arm 차이가 아니다. 런 표와 caveats에만 표시가 있으면
            #    사람은 이 블록만 복사해 간다(그게 6c2ecab이 남긴 갭이다).
            "passthrough_fallback": fallback_integrity(
                fb_by_run, [n, ref_n], f"arm 차분 #{n} vs #{ref_n}",
                "그 런의 분포는 그 arm의 분포가 아니므로 이 Δ는 arm 차이가 아니다",
            ),
            # 이 Δ에 붙은 바닥을 잡은 쌍의 폴백 사실. **그 arm의 쌍**이며, 바닥이 없으면
            # None이다(없는 바닥의 폴백 사실을 만들어 내지 않는다).
            "noise_floor_fallback": (
                floor_entry["integrity_brief"] if floor_entry else None
            ),
            "gpu_present_note": (
                "gpu_present_ms는 arm이 달라도 **같은 코드**가 도는 최종 표시 패스인데 "
                "실측에서 arm마다 움직인다. 그래서 arm 간 비교에 항상 함께 낸다 — 이 열의 "
                "Δ가 다른 열의 Δ와 같은 자릿수면 **열별 귀속이 번진 것**이고, 그때 "
                "stage_* 열의 Δ를 그 단계의 비용으로 읽으면 안 된다"
            ),
        })

    # ── 승격 베이스라인 대비 diff
    diff_rows = [n for n in sorted(rows) if rows[n].get("diff_baseline")]
    for n in diff_rows:
        a = done.get(n)
        if not a or not a.get("summary_path"):
            report["baseline_diff"] = {
                "run": n, "available": False,
                "reason": "그 런이 아직 계획을 통과하지 못했다",
            }
            continue
        baseline = Path(args.promotion_baseline)
        if not baseline.exists():
            report["baseline_diff"] = {
                "run": n, "available": False,
                "reason": f"승격 베이스라인이 없다: {baseline}",
            }
            continue
        child = invoke_child(
            DIFF_SCRIPT, DIFF_STAGE,
            ["--baseline", str(baseline), "--current", a["summary_path"]],
            args,
        )
        diff = child.json("diff.json")
        report["baseline_diff"] = {
            "run": n,
            "available": diff is not None,
            "baseline": str(baseline),
            "current_summary": a["summary_path"],
            "diff_path": str(child.out_dir / "diff.json"),
            "exit": child.returncode,
            "status": (diff or {}).get("status"),
            "comparable": (diff or {}).get("comparable"),
            "condition_diffs": (diff or {}).get("condition_diffs"),
        }
        break

    # ── 단서. 숫자만 있고 조건이 없는 보고는 인용되면 틀린다.
    envelope = [r["n"] for r in report["runs"] if r.get("envelope_only")]
    if envelope:
        report["caveats"].append(
            f"봉투 점(지속 근거 아님): 런 {envelope} — 분석 창이 지속 판정 창"
            f"({targets.SUSTAINED_SEC}s)보다 짧다. 리포트에 지속 성능으로 옮기지 말 것"
        )
    incomplete = [r["n"] for r in report["runs"] if r["status"] != "done"]
    if incomplete:
        report["caveats"].append(
            f"계획을 통과하지 못한 칸: {incomplete} — 이 세션은 완주가 아니다"
        )
    if fallback_flagged:
        # 진행 파일에는 통과로 남아 있지만 **앱이 그 arm을 못 그렸다고 신고한** 런이다.
        # 이 검사가 생기기 전에 기록됐기 때문에 status는 done이다 — 그 사실을 함께 적는다.
        report["caveats"].append(
            f"🔴 통과로 기록됐지만 {FALLBACK_FIELD}가 0이 아닌 런: {fallback_flagged} — "
            "앱이 그 arm을 그리지 못해 패스스루로 대신 그렸다고 신고했다. **이 런의 숫자는 "
            "그 arm의 숫자가 아니다**(프레임타임은 정상으로 보인다). 이 폴백 검사가 생기기 "
            "전에 기록된 attempt라 status는 done으로 남아 있다 — 리포트가 상태를 사후에 "
            "고쳐 쓰지 않기 때문이다. 다시 찍고 새 세션으로 채울 것"
        )
    if fallback_unknown:
        report["caveats"].append(
            f"{FALLBACK_FIELD}를 확인할 수 없는 통과 런: {fallback_unknown} — 필드가 없는 "
            "옛 스키마이거나 회수 로그가 남아 있지 않다. 그 런이 선언한 arm으로 실제로 "
            "그려졌는지 이 리포트로는 말할 수 없다(**값이 없는 것은 0이 아니다**)"
        )
    if not report["noise_floor"].get("available"):
        report["caveats"].append(
            "노이즈 바닥이 없다 — arm 차분이 실행 간 흔들림보다 큰지 판단할 수 없다"
        )
    else:
        report["caveats"].append(
            f"노이즈 바닥은 **arm마다** 그 arm의 반복 1쌍에서 나왔다 "
            f"(바닥을 잰 arm: {report['noise_floor']['arms_measured']}). 한 쌍에서 나온 값이라 "
            "실행 간 변동의 하한이며 — '바닥 초과'는 신호의 필요조건이지 충분조건이 아니다. "
            "특히 열별 바닥이 0.0x ms로 작게 나오면 그 열은 이 세션에서 사실상 판별력이 없다. "
            "**한 arm의 바닥을 다른 arm에 물려 쓰지 않았다**"
        )
        if report["noise_floor"].get("duplicate_arm_pairs"):
            report["caveats"].append(
                f"같은 arm에 반복 쌍이 둘 이상이다 — 첫 쌍을 판정에 썼고 나머지는 쓰지 "
                f"않았다: {report['noise_floor']['duplicate_arm_pairs']}"
            )
    # 🔴 바닥 없이 판정 불가로 남은 차분을 **caveats에도** 이름으로 적는다. 블록 안 표시만
    #    있으면 통과한 차분 목록만 훑는 사람에게는 보이지 않는다.
    unfloored = [
        (ad["run"], ad["arm"]) for ad in report["arm_deltas"]
        if ad.get("available") and not (ad.get("noise_floor_source") or {}).get("available")
    ]
    if unfloored:
        report["caveats"].append(
            "🔴 그 arm의 노이즈 바닥이 이 세션에 없어 **신호 여부 판정 불가**인 차분: "
            + ", ".join(f"#{n}({arm})" for n, arm in unfloored)
            + " — 다른 arm의 바닥으로 갈음하지 않았다(exceeds_noise_floor=null). 이 Δ를 "
            "'신호'로도 '신호 아님'으로도 쓰지 말 것. 이 arm의 반복 쌍을 찍어야 판정된다"
        )
    if state.get("allow_dirty_build"):
        report["caveats"].append(
            "--allow_dirty_build로 돌렸다 — git_dirty=true인 런이 섞였을 수 있고, "
            "그 숫자는 어느 커밋의 앱이 낸 것인지 말할 수 없다"
        )
    report["caveats"].append(
        "안전 회귀는 evaluated=false다 (탐지 미구현). 성능만 있는 보고는 불완전한 보고다"
    )
    return report


def print_fallback_integrity(integrity: dict | None, indent: str = "  ") -> None:
    """블록 안에 폴백 사실을 찍는다.

    🔴 **숫자보다 먼저 찍는다.** 아래 Δ 줄만 복사해 가는 것을 막는 것이 이 표시의 목적이므로,
    표시가 숫자 뒤에 있으면 없는 것과 비슷하다.

    폴백 0일 때도 한 줄 남긴다 — 근거가 보이지 않으면 "0이었다"와 "이 검사가 아예 돌지
    않았다"를 사람이 구별할 수 없다(통과 배너와 같은 이유).
    """
    if not integrity:
        return
    quotable = integrity.get("quotable")
    if quotable is False:
        LOG.error("%s%s", indent, integrity.get("note"))
    elif quotable is None or integrity.get("runs_fallback_nonzero_not_flagged"):
        # 0이 아닌 값은 어긋남이 아니어도 **경고 수준으로** 낸다 — 설명되지 않은 사실이다.
        LOG.warning("%s⚠ %s", indent, integrity.get("note"))
    else:
        LOG.info("%s%s", indent, integrity.get("note"))


def print_report(report: dict) -> None:
    LOG.info("")
    LOG.info("=" * 66)
    LOG.info("세션 리포트 — %s (run_ts=%s)", report["session_id"], report["run_ts"])
    LOG.info("-" * 66)
    for r in report["runs"]:
        fb = r.get("passthrough_fallback") or {}
        # 폴백 사실을 **런 한 줄에 같이** 찍는다. 이걸 caveats에만 두면 표만 보고 인용된다.
        if fb.get("mismatch"):
            fb_text = f"폴백 {fb.get('value')}/{fb.get('frames_emitted')} ← 무효"
        elif fb.get("value"):
            # 0이 아니지만 어긋남은 아니다 (passthrough arm). 숫자를 감추지 않는다.
            fb_text = f"폴백 {fb['value']} (arm 정상)"
        elif fb.get("ok"):
            fb_text = "폴백 0"
        elif fb:
            fb_text = "폴백 ?"
        else:
            fb_text = ""
        LOG.info(
            "  #%-2d %-12s %-18s %-8s 분석 창 %-7s %-12s %s",
            r["n"], r["arm"], r["lighting"], r["status"],
            f"{r['analysis_window_sec']}s" if r["analysis_window_sec"] else "-",
            fb_text, "봉투 점" if r.get("envelope_only") else "",
        )

    nf = report["noise_floor"]
    LOG.info("-" * 66)
    if nf.get("available"):
        # **쌍마다 따로 찍는다.** 한 덩어리로 찍으면 어느 arm의 폭인지 사라진다.
        LOG.info(
            "노이즈 바닥 — arm마다 따로 잰다. 바닥을 잰 arm: %s", nf["arms_measured"],
        )
        for blk in nf["pairs"]:
            if not blk.get("available"):
                LOG.warning(
                    "  arm=%s 쌍 %s: 바닥 없음 — %s",
                    blk.get("arm"), blk.get("pair"), blk.get("reason"),
                )
                continue
            LOG.info(
                "  [arm=%s] 런 %s 반복 (조명=%s). **이 폭 안은 이 arm의 신호가 아니다**",
                blk["arm"], blk["pair"], blk["lighting"],
            )
            print_fallback_integrity(blk.get("passthrough_fallback"), indent="    ")
            for col, per_p in blk["columns"].items():
                for p in COMPARE_PERCENTILES:
                    v = per_p.get(p)
                    if not v:
                        continue
                    LOG.info(
                        "    %-24s %s: %8s vs %8s ms  |Δ|=%-8s (%+.2f%%) [arm=%s, 쌍=%s]",
                        col, p, v["a_ms"], v["b_ms"], v["abs_delta_ms"],
                        v["pct"] if v["pct"] is not None else 0.0, blk["arm"], blk["pair"],
                    )
        if nf.get("duplicate_arm_pairs"):
            LOG.warning(
                "  ⚠ 같은 arm의 쌍이 둘 이상이다 — 첫 쌍만 판정에 썼다: %s",
                nf["duplicate_arm_pairs"],
            )
    else:
        LOG.warning("노이즈 바닥 없음 — %s", nf.get("reason", "반복 런이 통과하지 않았다"))
        for blk in nf.get("pairs") or []:
            if not blk.get("available"):
                LOG.warning("  arm=%s 쌍 %s: %s", blk.get("arm"), blk.get("pair"),
                            blk.get("reason"))

    for ad in report["arm_deltas"]:
        LOG.info("-" * 66)
        if not ad.get("available"):
            LOG.warning(
                "arm 차분 #%s vs #%s: 낼 수 없다 — %s",
                ad["run"], ad["reference_run"], ad.get("reason"),
            )
            continue
        LOG.info(
            "arm 차분 — #%d(%s) − #%d(%s), 조명=%s. 노이즈 바닥과 나란히 본다",
            ad["run"], ad["arm"], ad["reference_run"], ad["reference_arm"], ad["lighting"],
        )
        # 🔴 이 두 줄이 Δ보다 위에 있어야 한다 — 표·caveats의 표시는 여기까지 따라오지 않는다.
        print_fallback_integrity(ad.get("passthrough_fallback"))
        # 🔴 **바닥의 출처를 Δ보다 위에 찍는다.** 어느 arm의 폭인지 없으면 '바닥 초과'는
        #    근거가 없는 문장이고, 옛 구현이 정확히 그 상태였다(첫 쌍의 바닥이 무표시로 붙었다).
        nfs = ad.get("noise_floor_source") or {}
        if nfs.get("available"):
            LOG.info("  ↳ 바닥 출처: %s", nfs.get("note"))
        else:
            LOG.error("  ↳ %s", nfs.get("reason"))
            if nfs.get("other_arms_measured_in_session"):
                LOG.warning(
                    "  ↳ 참고(판정에 쓰지 않았다): 이 세션에서 바닥을 잰 arm = %s. %s",
                    nfs["other_arms_measured_in_session"], nfs.get("other_arms_note"),
                )
        nf_fb = ad.get("noise_floor_fallback")
        if nf_fb and nf_fb.get("quotable") is not True:
            # 바닥이 무효면 아래 '바닥 초과/이내' 태그도 근거가 없다.
            print_fallback_integrity(nf_fb, indent="  ↳ 바닥: ")
        for col, per_p in ad["columns"].items():
            for p in ("p50", "p95"):
                v = per_p.get(p)
                if not v:
                    continue
                floor = v["noise_floor_ms"]
                src = (
                    f"{v['noise_floor_arm']} #{v['noise_floor_pair'][0]}·"
                    f"#{v['noise_floor_pair'][1]}"
                    if v.get("noise_floor_pair") else "?"
                )
                if v["exceeds_noise_floor"] is None:
                    tag = f"이 arm({ad['arm']})의 바닥 없음 → **판정 불가**"
                elif v["exceeds_noise_floor"]:
                    tag = f"바닥 {floor}ms({src}) 초과 → 신호"
                else:
                    tag = f"바닥 {floor}ms({src}) 이내 → **신호가 아니다**"
                LOG.info(
                    "  %-24s %s: %s(%s) → %s(%s) ms  Δ=%+.3f  %s",
                    col, p, v["reference_ms"], ad["reference_arm"],
                    v["current_ms"], ad["arm"], v["delta_ms"], tag,
                )
        LOG.warning("  ⚠ %s", ad["gpu_present_note"])

    bd = report.get("baseline_diff")
    LOG.info("-" * 66)
    if bd and bd.get("available"):
        LOG.info(
            "승격 베이스라인 대비 (런 #%s): status=%s, 비교 가능=%s → %s",
            bd["run"], bd["status"], bd["comparable"], bd["diff_path"],
        )
        for d in bd.get("condition_diffs") or []:
            LOG.warning("    - 조건 차이: %s", d)
    elif bd:
        LOG.warning("승격 베이스라인 대비: 내지 못했다 — %s", bd.get("reason"))
    else:
        LOG.info("승격 베이스라인 대비: 계획에 diff_baseline 런이 없다")

    LOG.info("-" * 66)
    sr = report["safety_regression"]
    LOG.warning(
        "⚠ 안전 회귀: evaluated=%s (%s) — 성능만 있는 보고는 불완전한 보고다",
        sr["evaluated"], sr["reason"],
    )
    for c in report["caveats"]:
        LOG.warning("⚠ %s", c)
    LOG.info("=" * 66)


# ══ main ═══════════════════════════════════════════════════════════════════
def build_argparser():
    parser = common_argparser()
    parser.add_argument("--plan", default="", help="측정 계획 JSON (기본: 내장 S3-3 계획)")
    parser.add_argument(
        "--session_id", default="",
        help="진행 파일 이름 (기본: 계획 id). 같은 계획을 두 번 돌릴 때 구분",
    )
    parser.add_argument("--print_plan", action="store_true", help="계획만 출력하고 끝")
    parser.add_argument("--status", action="store_true", help="진행 상태만 출력하고 끝")
    parser.add_argument(
        "--report", action="store_true",
        help="런을 더 찍지 않고 지금까지의 결과로 집계·비교만 다시 낸다",
    )
    parser.add_argument(
        "--restart", action="store_true",
        help="진행 파일을 새로 만든다 (옛 파일은 이름을 바꿔 보존한다)",
    )
    parser.add_argument(
        "--allow_dirty_build", action="store_true",
        help="git_dirty=true인 런을 어긋남으로 보지 않는다 (그 사실은 리포트에 남는다)",
    )
    parser.add_argument(
        "--warmup_sec", type=float, default=targets.DEFAULT_WARMUP_SEC,
        help=f"집계에서 제외할 첫 N초 (기본 {targets.DEFAULT_WARMUP_SEC})",
    )
    parser.add_argument(
        "--promotion_baseline", default=DEFAULT_PROMOTION_BASELINE,
        help=f"diff_baseline 런과 비교할 승격 베이스라인 (기본 {DEFAULT_PROMOTION_BASELINE})",
    )
    parser.add_argument("--adb", default="", help="adb 실행 파일 경로")
    parser.add_argument("--serial", default="", help="대상 기기 serial (여러 대면 필수)")
    parser.add_argument(
        "--package", default=pull_frames_script.DEFAULT_PACKAGE,
        help=f"앱 패키지명 (기본 {pull_frames_script.DEFAULT_PACKAGE})",
    )
    return parser


def main() -> int:
    args = build_argparser().parse_args()
    paths = init_run(stage=STAGE, script_file=__file__, args=args)  # 콘솔 출력 UTF-8까지
    ensure_utf8_stdin()  # 입력 쪽은 init_run이 다루지 않는다

    try:
        plan = load_plan(args.plan)
        rows = validate_plan(plan)
    except PlanError as exc:
        LOG.error("계획을 쓸 수 없다: %s", exc)
        return EXIT_PLAN_ERROR

    session_id = args.session_id or plan["id"]

    if not paths.outputs_enabled:
        # 진행 파일을 남길 곳이 없으면 **재개가 불가능**하고, 자식(pull/analyze)의 산출물도
        # 갈 곳이 없다. pull_frames와 같은 판단: 아무것도 하지 않고 정직하게 끝낸다.
        LOG.warning("outputs 비활성 — 계획만 검증했다 (%d런)", len(rows))
        tmp_state = new_state(plan, rows, args)
        print_plan(tmp_state)
        if args.print_plan:
            LOG.info("--print_plan 이므로 여기서 끝낸다 (기기를 건드리지 않았다)")
            return EXIT_OK
        LOG.error(
            "세션을 진행하지 않았다 — --no_outputs에서는 진행 파일을 남길 수 없어 "
            "중단·재개가 불가능하고, 회수·집계 산출물도 스탬프 있는 자리에 놓을 수 없다. "
            "--no_outputs 없이 실행할 것 (계획 확인만 하려면 --print_plan)"
        )
        return EXIT_NO_DESTINATION

    out_root = Path(args.out_root)
    spath = state_path(out_root, session_id)

    try:
        state = load_state(spath)
    except PlanError as exc:
        LOG.error("%s", exc)
        return EXIT_STATE_CONFLICT

    if args.print_plan:
        # **진행 파일을 만들지도 건드리지도 않는다.** 계획을 훑어보는 것만으로 세션이
        # 시작된 것처럼 보이면(그리고 지문이 굳으면) 나중에 --restart를 강요하게 된다.
        print_plan(state or new_state(plan, rows, args))
        LOG.info(
            "--print_plan — 계획만 검증했다. 진행 파일(%s)은 %s",
            spath, "그대로 두었다" if state else "만들지 않았다",
        )
        return EXIT_OK

    if args.status:
        if state is None:
            LOG.warning("아직 시작하지 않은 세션이다 (진행 파일 없음: %s)", spath)
            print_plan(new_state(plan, rows, args))
            return EXIT_INCOMPLETE
        print_plan(state)
        remaining = pending_rows(state)
        LOG.info(
            "남은 런 %d개 / 전체 %d개 (완료 %d, 건너뜀 %d) — 진행 파일 %s",
            len(remaining), len(state["runs"]),
            sum(1 for r in state["runs"] if r["status"] == "done"),
            sum(1 for r in state["runs"] if r["status"] == "skipped"),
            spath,
        )
        return EXIT_OK if not remaining else EXIT_INCOMPLETE

    if state and args.restart:
        backup = spath.with_name(f"{spath.stem}_{paths.run_ts}.json.bak")
        os.replace(spath, backup)
        LOG.warning("--restart — 옛 진행 파일을 보존했다: %s", backup)
        state = None

    fresh = state is None
    if fresh:
        state = new_state(plan, rows, args)
    else:
        if state.get("plan_fingerprint") != plan_fingerprint(rows):
            LOG.error(
                "진행 파일과 계획이 다르다 (지문 %s vs %s) — 바뀐 계획에 옛 결과를 얹으면 "
                "어느 칸이 무엇으로 채워졌는지가 조용히 어긋난다. 계획을 되돌리거나 "
                "--restart(옛 파일은 보존된다) 또는 --session_id로 새 세션을 쓸 것: %s",
                state.get("plan_fingerprint"), plan_fingerprint(rows), spath,
            )
            return EXIT_STATE_CONFLICT
        if state.get("warmup_sec") != args.warmup_sec:
            LOG.error(
                "warmup_sec이 진행 파일(%s)과 다르다(%s) — warmup이 다르면 같은 로그도 다른 "
                "분포가 되고(baseline_diff의 CONDITION_KEYS), 세션 안에서 섞이면 노이즈 "
                "바닥과 arm 차분이 서로 다른 창에서 나온 값이 된다",
                state.get("warmup_sec"), args.warmup_sec,
            )
            return EXIT_STATE_CONFLICT
        # 재개할 때 --allow_dirty_build를 켜면 그 사실을 상태에 남긴다(리포트 단서가 된다).
        state["allow_dirty_build"] = bool(state.get("allow_dirty_build")) or bool(
            args.allow_dirty_build
        )

    state["invocations"].append({
        "run_ts": paths.run_ts,
        "git_commit": get_git_commit(),
        "argv": sys.argv,
        "at": time.strftime("%Y-%m-%d %H:%M:%S"),
    })
    save_state(spath, state)
    LOG.info("진행 파일: %s (%s)", spath, "새 세션" if fresh else "재개")

    print_plan(state)
    remaining = pending_rows(state)
    LOG.info(
        "남은 런 %d개 / 전체 %d개 (완료 %d, 건너뜀 %d)",
        len(remaining), len(state["runs"]),
        sum(1 for r in state["runs"] if r["status"] == "done"),
        sum(1 for r in state["runs"] if r["status"] == "skipped"),
    )

    interrupted = False
    if not args.report:
        interrupted = drive_session(state, spath, args)

    remaining = pending_rows(state)
    if remaining and not args.report:
        LOG.warning("=" * 66)
        LOG.warning(
            "남은 런 %d개 — 세션이 끝나지 않았다. 같은 명령으로 다시 실행하면 "
            "남은 런부터 이어간다: %s",
            len(remaining), spath,
        )
        LOG.warning(
            "집계·비교는 남은 런이 없을 때 낸다 (지금 내면 노이즈 바닥·arm 차분이 "
            "반쪽짜리가 된다). 지금이라도 보려면 --report"
        )
        LOG.warning("=" * 66)
        return EXIT_INCOMPLETE

    # ── 세션 끝. 집계·비교.
    report = build_report(state, args, paths.run_ts)
    print_report(report)
    out_path = paths.out_dir / "session_report.json"
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")
    LOG.info("세션 리포트 저장: %s", out_path)
    progress_copy = paths.out_dir / "progress.json"
    with progress_copy.open("w", encoding="utf-8") as f:
        json.dump(state, f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")
    LOG.info("진행 상태 사본: %s", progress_copy)

    if remaining:
        return EXIT_INCOMPLETE
    if interrupted:
        return EXIT_INCOMPLETE
    if any(r["status"] == "skipped" for r in state["runs"]):
        LOG.warning("건너뛴 런이 있다 — 완주가 아니다")
        return EXIT_SKIPPED
    return EXIT_OK


def drive_session(state: dict, spath: Path, args) -> bool:
    """남은 런을 하나씩 안내·검증한다. 반환값: 사용자가 중단했는가."""
    while True:
        remaining = pending_rows(state)
        if not remaining:
            return False
        row = remaining[0]
        total = len(remaining)
        print_run_instructions(row, state, 1, total)

        before, why = list_device_runs(args)
        if before is None:
            LOG.error("기기를 확인하지 못해 세션을 진행할 수 없다: %s", why)
            LOG.error(
                "기기를 연결하고 다시 실행하면 남은 런부터 이어간다 (진행 파일: %s)", spath
            )
            raise DeviceProblem(why or "기기 문제")
        LOG.info(
            "기기에 이미 있는 런 %d개를 기록했다 — 측정이 끝나면 **새로 생긴 런**을 "
            "이번 것으로 본다", len(before),
        )

        forced_run = None
        waited_start = time.time()
        while True:
            ans = prompt(
                "  [Enter]=측정 끝났음  s[ 사유]=건너뛰기  q=중단(재개 가능)  "
                "u <기기 런 이름>=이미 찍힌 런을 쓴다  > "
            )
            if ans is None:
                LOG.warning("입력이 닫혔다 — 세션을 중단하고 진행 상태를 저장했다")
                save_state(spath, state)
                return True
            ans = ans.strip()
            if ans in ("", "d", "done", "y"):
                break
            if ans in ("q", "quit", "exit"):
                LOG.warning("중단 — 남은 런은 다음 실행에서 이어간다 (%s)", spath)
                save_state(spath, state)
                return True
            if ans in ("s", "skip") or ans.startswith("s "):
                reason = ans[2:].strip() if len(ans) > 2 else "(사유 없음)"
                row["status"] = "skipped"
                row["skip_reason"] = reason
                save_state(spath, state)
                LOG.warning(
                    "계획 #%d 건너뜀: %s — **이 칸은 비어 있다.** 리포트에 그대로 남는다",
                    row["n"], reason,
                )
                forced_run = "__skip__"
                break
            if ans.startswith("u ") or ans.startswith("use "):
                forced_run = ans.split(None, 1)[1].strip()
                if forced_run not in before:
                    LOG.error(
                        "기기에 그런 런이 없다: %r — 있는 런: %s", forced_run, before,
                    )
                    forced_run = None
                    continue
                LOG.warning(
                    "⚠ 신규 런 감지를 건너뛰고 기존 런 %s를 쓴다 — 이 런이 방금 계획대로 "
                    "찍은 것이 맞는지는 **사람이 보증하는 것**이다. 계획 대조는 그대로 돈다",
                    forced_run,
                )
                break
            LOG.info("모르는 입력: %r", ans)

        if forced_run == "__skip__":
            continue

        waited = time.time() - waited_start
        planned_sec = row["minutes"] * 60.0
        if waited < planned_sec * (1.0 - DURATION_SHORT_TOLERANCE) and forced_run is None:
            LOG.warning(
                "⚠ 대기 시간이 %.0fs인데 계획은 %.0fs다 — 측정이 짧았을 수 있다 "
                "(회수 후 실제 분석 창으로 다시 검사한다)", waited, planned_sec,
            )

        attempt = execute_run(row, state, args, before=before, forced_run=forced_run)
        attempt["waited_sec"] = round(waited, 1)
        row.setdefault("attempts", []).append(attempt)
        row["status"] = "done" if attempt["conforms"] else "failed"
        save_state(spath, state)
        print_attempt_result(row, attempt)

        if not attempt["conforms"]:
            ans = prompt(
                "  어긋난 런이다. [Enter]=이 칸을 다시 찍는다  q=중단(재개 가능)  "
                "s[ 사유]=이 칸을 포기 > "
            )
            if ans is None or ans.strip() in ("q", "quit", "exit"):
                LOG.warning("중단 — 어긋난 칸은 다음 실행에서 다시 제시된다 (%s)", spath)
                save_state(spath, state)
                return True
            a = ans.strip()
            if a in ("s", "skip") or a.startswith("s "):
                row["status"] = "skipped"
                row["skip_reason"] = (a[2:].strip() if len(a) > 2 else "(사유 없음)")
                save_state(spath, state)
                LOG.warning("계획 #%d 포기: %s", row["n"], row["skip_reason"])


class DeviceProblem(Exception):
    """기기·adb 문제. 계획 대조를 할 수 없으므로 세션을 진행하지 않는다."""


def _main_wrapper() -> int:
    try:
        return main()
    except DeviceProblem:
        return EXIT_DEVICE_PROBLEM


if __name__ == "__main__":
    raise SystemExit(_main_wrapper())
