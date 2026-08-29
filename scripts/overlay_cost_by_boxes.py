"""④ 오버레이 비용을 **박스 개수별로** 가른다 (v7 로그 재분석).

    python scripts/overlay_cost_by_boxes.py --frames <런디렉토리|frames.csv> [--frames ...]

**왜 이 스크립트가 있나.** `scripts/analyze_frames.py`가 내는 것은 `stage_i_ms`·`stage_h_ms`·
`overlay_boxes`의 **주변 분포 세 개**이고, `scripts/run_session.py`의 diff 표는 `overlay_boxes`를
일부러 뺀다(개수가 `a_ms`/`delta_ms`로 라벨되는 것을 막기 위해 — 그 결정은 옳다). 그래서
"박스 개수에 따라 ④ 비용이 어떻게 오르는가"에 아직 아무 답이 없었다. 이 스크립트는 그
질문 하나만 본다: **`overlay_boxes` 값을 버킷으로 삼아 같은 프레임의 비용 열 분포를 낸다.**

**v8에서 면적 축이 붙었다.** ④가 박스 **안쪽을 채우기** 시작하면서 I칸의 설명 변수가 개수
하나에서 **개수와 면적 둘**로 늘었다(`overlay_fill_frac`). 🔴 그렇다고 면적으로 버킷을
나누지 않는다 — 개수 버킷은 그대로 두고 **각 버킷 안에서 면적 분포를 함께 낸다.** 2차원
교차 버킷은 실재 런에서 표본이 안 나오고(야간 런 0박스 81.6%, 개수 20버킷 중 8개가 이미
n<30), 면적 구간 경계는 fill 빌드 실측이 0건이라 지금 정하면 데이터가 아니라 발명이다.

🔴 **회귀·외삽·기울기를 내지 않는다.** 개수가 적은 버킷은 `n`이 작아서 백분위가 의미 없고,
   그 사실이 표에 보여야 한다 — 그래서 모든 줄에 `n`을 함께 낸다. "박스 1개당 몇 ms"라는
   숫자를 이 스크립트가 만들면, 그 숫자는 `n=3`짜리 버킷에서 나온 기울기일 수 있다.

🔴 **판정선을 만들지 않는다.** "박스 몇 개까지 허용"이라는 값은 이 저장소에 없다(판정선은
   `lib/targets.py`에만 있다). 이 스크립트는 관측이며 판정이 아니다 — verdict를 내지 않고,
   종료 코드는 "표를 낼 수 있었는가"만 반영한다.

재사용한 것(같은 계산을 두 번 구현하면 두 값이 갈라진다):
  - 백분위·분포        : `lib/stats.py`의 `summarize` (nearest-rank. 세 번째 정의를 만들지 않는다)
  - 깜빡임 전이        : `scripts/analyze_frames.py`의 `overlay_flicker`
                        (끝에 걸린 0 구간은 세지 않는다 등 규약 전부 그쪽이 소유)
  - warmup 절단·행 회계: `lib/frame_log.py`의 `read_frames` (기준은 **첫 행의 t_recv_ns**)
"""

from __future__ import annotations

import csv
import json
import logging
import sys
from pathlib import Path

_SCRIPTS_DIR = Path(__file__).resolve().parent
_PROJECT_ROOT = _SCRIPTS_DIR.parent
sys.path.insert(0, str(_PROJECT_ROOT))
if str(_SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS_DIR))

from lib import targets  # noqa: E402
from lib.frame_log import (  # noqa: E402
    FRAME_COUNT_COLUMNS,
    FRAME_CPU_TIME_COLUMNS,
    FRAME_RATIO_COLUMNS,
    RATIO_SUMMARY_DIGITS,
    GPU_FRAME_COLUMN,
    GPU_TIME_COLUMNS,
    MIN_POSITIVE_MS,
    MISSING,
    FrameLogError,
    read_frames,
    read_session,
)
from lib.run_utils import common_argparser, init_run  # noqa: E402
from lib.stats import summarize  # noqa: E402

# 깜빡임 전이는 **이 문장의 주인이 저쪽**이다. 같은 계산을 여기서 다시 쓰면 두 값이 갈라진다.
from analyze_frames import overlay_flicker  # noqa: E402

LOG = logging.getLogger(__name__)

STAGE = "overlay_cost_by_boxes"
SUMMARY_NAME = "by_boxes.json"

# 버킷의 축이 되는 열. **개수이며 ms가 아니다.**
BUCKET_COLUMN = "overlay_boxes"

# 버킷 안에서 분포를 낼 열. 세 열이 **같은 시계가 아니다** — 그래서 라벨을 아래에서
# 열 소속(frame_log의 상수)으로부터 유도한다. 문자열로 손으로 적으면 열이 옮겨갈 때 낡는다.
COST_COLUMNS = ("stage_i_ms", "stage_h_ms", GPU_FRAME_COLUMN)

# ── 조건 열 (v8) — 버킷 축도 아니고 비용도 아니다 ─────────────────────────
# 🔴 **세 번째 범주가 필요한 이유.** 이 표에는 성질이 다른 열이 세 종류 선다:
#   1) 버킷 축(`BUCKET_COLUMN`) — 줄을 가르는 값. 개수다.
#   2) 비용(`COST_COLUMNS`) — 버킷 안에서 분포를 내는 ms.
#   3) **조건**(여기) — 버킷 안에서 분포를 내지만 **ms가 아니다.** 비용이 아니라
#      *비용의 조건*이다(`overlay_boxes`와 같은 취급이며 버짓 칸이 없다).
# `overlay_fill_frac`을 2)에 넣으면 시계 라벨이 붙고 ms처럼 읽히며, 1)로 삼으면 면적
# 버킷을 만들게 되는데 그 경계는 이 라운드에서 정하지 않는다(NOTES의 no_area_bucket).
#
# 🔴 **면적 버킷을 만들지 않는다.** 개수 버킷은 그대로 두고, 각 버킷 **안에서** 면적
#   분포를 함께 낸다. 2차원 교차 버킷은 실재 런에서 표본이 안 나온다(야간 런은 0박스가
#   81.6%였고 개수 20버킷 중 8개가 이미 n<30이다) — 거기서 면적으로 또 쪼개면 백분위가
#   표본 몇 개짜리가 된다.
CONDITION_COLUMNS = FRAME_RATIO_COLUMNS

# 🔴 비율 열의 반올림 자릿수. **앱이 CSV에 쓰는 자릿수와 같게 둔다**
#   (`docs/FRAME_LOG_SCHEMA.md` ④ 오버레이 절: "앱에 요구하는 자릿수 소수 6자리 — 줄이면
#   작은 박스가 0.000이 되고 하네스는 그 샘플을 면적 0으로 읽는다. 폐기되지 않으므로
#   보이지도 않는다"). 여기서 3자리로 요약하면 그 요구를 **리포트 계층이 되돌린다.**
CONDITION_SUMMARY_DIGITS = RATIO_SUMMARY_DIGITS

# ── 상수 자기검사 ─────────────────────────────────────────────────────────
# 축이 될 열이 카운트 열이 아니거나 비용 열이 어느 분류에도 없으면, 아래 시계 라벨이
# 조용히 "미분류"가 된 채 표가 나간다. 데이터와 무관한 불변식이므로 import 시점에 닫는다.
if BUCKET_COLUMN not in FRAME_COUNT_COLUMNS:
    raise RuntimeError(
        f"{BUCKET_COLUMN}이 FRAME_COUNT_COLUMNS에 없다 — 버킷 축이 카운트 열이 아니다"
    )
_unclassified = [
    c
    for c in COST_COLUMNS
    if c not in GPU_TIME_COLUMNS and c not in FRAME_CPU_TIME_COLUMNS
]
if _unclassified:
    raise RuntimeError(
        f"비용 열이 GPU/CPU 어느 분류에도 없다: {_unclassified} — 시계 라벨을 만들 수 없다"
    )
# 조건 열이 제 범주를 벗어나면 **값이 조용히 망가진다.** 어떤 데이터로도 드러나지 않는
# 부류(카운트 통에 들어가면 소수부가 잘리고, 시간 통에 들어가면 면적 0인 프레임이 전부
# 폐기된다 — 둘 다 폐기 카운트조차 정직하지 않다)라 import 시점에 닫는다.
# 🔴 순서가 있다: **가장 구체적인 진단이 먼저**다. 버킷 축을 조건 열로도 적으면 아래
#   두 검사에도 걸리지만(개수 열이라 비율이 아니고 카운트 통에 있다), 그때 사람이 봐야 할
#   문장은 "범주를 잘못 골랐다"가 아니라 "표가 자기 자신을 조건으로 삼았다"다.
_bucket_as_condition = [c for c in CONDITION_COLUMNS if c == BUCKET_COLUMN]
if _bucket_as_condition:
    raise RuntimeError(
        f"조건 열이 버킷 축과 같다: {_bucket_as_condition} — 버킷을 가른 값을 그 버킷 안에서 "
        "다시 분포로 내면 모든 줄이 상수 하나가 되고, 표가 자기 자신을 조건으로 삼는다"
    )
_not_ratio = [c for c in CONDITION_COLUMNS if c not in FRAME_RATIO_COLUMNS]
if _not_ratio:
    raise RuntimeError(
        f"조건 열이 FRAME_RATIO_COLUMNS에 없다: {_not_ratio} — 비율 열이 아닌 것을 조건 "
        "열로 삼으면 폐기 가드(`>= 0`)와 반올림 자릿수(6자리)가 그 열의 규약과 어긋난다"
    )
_miscategorized = sorted(
    {
        c
        for c in CONDITION_COLUMNS
        if c in FRAME_CPU_TIME_COLUMNS
        or c in GPU_TIME_COLUMNS
        or c in FRAME_COUNT_COLUMNS
        or c in COST_COLUMNS
    }
)
if _miscategorized:
    raise RuntimeError(
        f"조건 열이 시간/카운트/비용 목록에도 있다: {_miscategorized} — "
        "카운트 통이면 `_to_int`가 소수부를 잘라 0.123456이 조용히 0이 되고, 시간 통이면 "
        "`> 0` 가드가 **면적 0인 프레임을 전부 폐기**한다(야간 보행 프레임 대부분이 그렇다). "
        "둘 다 값이 사라진 것이 표에 드러나지 않는다"
    )


def clock_of(column: str) -> str:
    """이 열이 어느 시계인가. **표의 모든 숫자 옆에 붙는다.**

    🔴 `stage_h_ms`(CPU 벽시계)와 `stage_i_ms`·`gpu_frame_ms`(GPU 타이머)를 한 표에 놓기
    때문에 이 라벨이 필수다. 라벨 없이 세 열을 나란히 찍으면 사람이 더한다.

    🔴 **시간 열만 받는다.** 마지막 줄이 `return "GPU 타이머"`라, 이 함수는 CPU 목록에
    없는 **모든** 열을 GPU로 라벨한다 — v8의 비율 열이 이 경로로 새면 표에
    `overlay_fill_frac [GPU 타이머]`가 찍히고, 그건 무차원 비율에 시계를 붙인 것이다.
    시간이 아닌 열은 `unit_of()`로 간다.
    """
    if column in FRAME_CPU_TIME_COLUMNS:
        return "CPU 벽시계"
    if column in GPU_TIME_COLUMNS:
        return "GPU 타이머"
    raise RuntimeError(
        f"clock_of에 시간 열이 아닌 열이 들어왔다: {column!r} — 이 함수는 CPU 목록에 없는 "
        "열을 전부 'GPU 타이머'로 라벨하므로, 비율/카운트 열이 여기로 새면 무차원 값에 "
        "시계가 붙는다. 단위 라벨은 unit_of()를 쓸 것"
    )


# 🔴 비율 열의 단위 라벨. ms가 아니라는 것이 **표 안에서 보여야** 한다 — 이 표는 한 줄씩
#   인용되므로("박스 10개일 때 면적이 얼마"), 단위가 줄에 붙어 있지 않으면 옮겨간 곳에서
#   ms로 읽힌다.
RATIO_UNIT_LABEL = "비율(단위 없음)"


def unit_of(column: str) -> str:
    """이 열의 단위 라벨. 비용 열이면 시계 이름, 조건 열이면 무차원 표시.

    `clock_of`를 그대로 부르지 않는 이유는 그 함수가 이제 시간 열이 아니면 죽기 때문이다
    (일부러 그렇게 했다). 표를 그리는 쪽은 비용·조건을 한 루프로 도는 자리가 있어서,
    분기를 여기 한 곳에만 둔다.
    """
    if column in CONDITION_COLUMNS:
        return RATIO_UNIT_LABEL
    return clock_of(column)


# ── 표에 반드시 붙는 경고 문장 ────────────────────────────────────────────
# 🔴 문장이 숫자와 **같은 산출물 안에** 있어야 한다. 한 줄만 복사해 옮겨도 조건이 따라가야
#    하고, 이 표는 성질상 한 줄씩 인용된다("박스 10개일 때 I가 얼마").
NOTES = {
    "stage_i_upper_bound": (
        "🔴 `stage_i_ms`(패스별 GPU query)는 **④ 비용의 상한이다.** 패스별 계측은 마지막 "
        "전체화면 패스의 비용을 중복 계상한다(알려진 이슈 21). 그러므로 이 표의 "
        "stage_i_ms 증가분을 '박스를 그리는 데 든 GPU 시간'으로 그대로 인용하지 않는다 — "
        "상한의 증가분이다. 하한은 같은 세션의 `_1q` 짝끼리의 차로만 나오고, 그 계산은 "
        "이 스크립트가 하지 않는다(런을 빼는 계산은 arm·계측 방식이 같아야 뜻이 있다)."
    ),
    "stage_h_cpu_clock": (
        "🔴 `stage_h_ms`는 **CPU 벽시계**(GL 스레드의 좌표 평활·hold 구간)다. "
        "`gpu_frame_ms`·`stage_i_ms`(GPU 타이머)와 **더하지 않는다.** 앱의 H 구간은 "
        "GPU query가 열리기 전에 닫히므로 gpu 값 안에 물리적으로 들어 있지도 않다."
    ),
    "no_slope": (
        "🔴 개수별 **기울기·회귀·외삽을 내지 않는다.** 버킷별 분포만 낸다 — 개수가 큰 "
        "버킷은 `n`이 작아 백분위가 의미 없고, 그 사실을 보이기 위해 모든 줄에 `n`이 있다. "
        "`n`을 떼고 p50만 옮기면 표본 3개짜리 값이 실측처럼 읽힌다."
    ),
    "no_threshold": (
        "⚠ **판정선이 아니다.** '박스 몇 개까지 허용'이라는 값은 이 저장소에 없다"
        "(판정선은 lib/targets.py에만 있다). 이 표는 관측이며 PASS/FAIL을 내지 않는다."
    ),
    "boxes_are_condition": (
        f"⚠ `{BUCKET_COLUMN}`는 **개수이며 ms가 아니다** — 비용이 아니라 **비용의 조건**이다. "
        "버킷 라벨을 시간 단위로 읽지 않는다."
    ),
    "bucket_n_vs_sample_n": (
        "⚠ 버킷의 `n`(프레임 수)과 각 열의 `count`(유효 표본 수)는 다를 수 있다 — 그 열이 "
        "그 프레임에서 기록되지 않았으면(-1) 폐기되기 때문이다. 두 값이 다르면 그 열의 "
        "분포는 그 버킷의 프레임 전부를 대표하지 않는다."
    ),
    "fill_frac_is_area_sum": (
        f"⚠ `{CONDITION_COLUMNS[0]}`는 **Σ(박스 면적) ÷ 화면 면적**이고 **ms가 아니다** — "
        "비용이 아니라 비용의 조건이다(`overlay_boxes`와 같은 취급이며 버짓 칸이 없다). "
        "겹침·클리핑을 보정하지 않으므로 🔴 **1을 넘는 값이 정상이다** — 상한 1을 가정하고 "
        "'화면의 몇 %'로 읽으면 틀린다. `0.0`은 정상값이고(그 프레임에 그린 박스가 없었다) "
        "`-1`만 '기록하지 않았다'다."
    ),
    "fill_frac_not_painted_pixels": (
        f"🔴 `{CONDITION_COLUMNS[0]}`는 **칠해진 픽셀의 비율이 아니다.** 함정이 "
        "**양방향이다**: (1) 겹침은 오버드로라서 **비용에는 정직하다** — 겹친 픽셀은 실제로 "
        "두 번 셰이딩된다. 하지만 **가림 판정에는 틀리다** — 합집합이 아니므로 '화면의 "
        "얼마를 덮었나'로 읽으면 과대다. (2) 클리핑은 반대다 — 화면 밖 프래그먼트는 "
        "래스터화 전에 잘리므로 이 값은 **비용을 과대평가**한다. 🔴 박스 좌표가 로그에 없어 "
        "**어느 쪽도 보정할 수 없다.** 보정하려면 스키마에 좌표가 들어와야 한다."
    ),
    "fill_frac_zero_bucket_is_identity": (
        f"🔴 `{BUCKET_COLUMN}=0` 줄의 `{CONDITION_COLUMNS[0]}` 0.0은 **관측이 아니라 "
        "정의다.** 그릴 박스가 없으면 면적 합은 0일 수밖에 없다. 그 줄을 '면적이 0일 때 "
        "비용이 얼마'라는 면적 근거로 인용하지 않는다 — 그건 개수가 0일 때의 비용이다. "
        "면적의 효과는 **개수가 같은 버킷 안에서** 면적 분포가 넓은 줄로만 말할 수 있다."
    ),
    "no_area_bucket": (
        f"🔴 이 표는 **면적으로 버킷을 나누지 않는다.** 버킷 축은 `{BUCKET_COLUMN}`(개수) "
        f"하나이고, `{CONDITION_COLUMNS[0]}`는 그 버킷 **안의 분포**로만 나온다. 면적 구간 "
        "경계를 지어내지 않기 위해서다 — fill 빌드의 실측이 아직 0건이라 지금 정하는 경계는 "
        "데이터가 아니라 발명이다. 필요해지면 **그 런의 분위수에서** 유도하고 경계값을 표에 "
        "함께 찍는다. 🔴 그 경계를 `lib/targets.py`에 넣지 않는다 — **판정선이 아니다.**"
    ),
    "cross_run_not_pooled": (
        "🔴 런을 **합치지 않는다.** 표의 모든 줄은 한 런에서 나온 것이고, 개요 표에도 "
        "런·커밋 열이 줄마다 붙는다. 서로 다른 빌드의 같은 버킷을 합쳐 하나의 분포로 "
        "만들면 그건 비교가 아니라 착시다(이 저장소가 한 번 정정한 '+13ms' 부류)."
    ),
}


def _to_int(raw) -> int:
    """`lib/frame_log.py`의 `_to_int`와 **같은 규약**(비거나 못 읽으면 MISSING)."""
    if raw is None or str(raw).strip() == "":
        return MISSING
    try:
        return int(float(str(raw).strip()))
    except ValueError:
        return MISSING


def _to_float(raw) -> float:
    """`lib/frame_log.py`의 `_to_float`와 같은 규약. NaN/inf는 MISSING으로 떨군다."""
    if raw is None or str(raw).strip() == "":
        return float(MISSING)
    try:
        val = float(str(raw).strip())
    except ValueError:
        return float(MISSING)
    if val != val or val in (float("inf"), float("-inf")):
        return float(MISSING)
    return val


def resolve_input(raw: str) -> tuple[Path, Path]:
    """`--frames` 하나를 (frames.csv, 런 디렉토리)로 푼다. 디렉토리도 받는다.

    없는 경로는 **죽인다.** 조용히 건너뛰면 오타 하나가 "그 런은 데이터가 없었다"로 읽힌다.
    """
    p = Path(raw)
    if p.is_dir():
        csv_path = p / "frames.csv"
        if not csv_path.exists():
            raise FileNotFoundError(f"런 디렉토리에 frames.csv가 없다: {p}")
        return csv_path, p
    if p.exists():
        return p, p.parent
    raise FileNotFoundError(f"경로가 없다: {p}")


def _warmup_diagnosis(csv_path: Path, warmup_sec: float) -> dict:
    """`read_frames`가 죽었을 때 **왜 표본이 0인지** 말하기 위한 최소 진단.

    🔴 "빈 표를 조용히 내지 않기 위해" 있는 함수다. warmup 컷 규약(첫 행 t_recv_ns 기준)은
    `read_frames`와 같은 것을 쓰되, 여기서는 **세기만** 한다(집계에 쓰는 값은 만들지 않는다).
    """
    out = {"rows_read": 0, "rows_before_cutoff": 0, "span_sec": None}
    try:
        with csv_path.open("r", encoding="utf-8-sig", newline="") as f:
            rows = list(csv.DictReader(f))
    except OSError:
        return out
    out["rows_read"] = len(rows)
    if not rows:
        return out
    t_list = [_to_int(r.get("t_recv_ns")) for r in rows]
    valid = [t for t in t_list if t != MISSING]
    if not valid:
        return out
    t0 = valid[0]
    cutoff = t0 + int(warmup_sec * 1e9)
    out["rows_before_cutoff"] = sum(1 for t in valid if t < cutoff)
    out["span_sec"] = round((max(valid) - t0) / 1e9, 3)
    return out


def bucketize(
    csv_path: Path,
    warmup_sec: float,
    columns_present: list[str],
    condition_present: list[str] | None = None,
) -> dict:
    """행 정렬을 유지한 채 `overlay_boxes` → 비용 열·조건 열 값들을 모은다.

    🔴 **왜 `read_frames`의 시계열을 그대로 쓸 수 없나.** 그쪽은 열마다 독립적으로 폐기하므로
    (`discarded[열]`) 반환된 리스트들이 **행 정렬이 아니다.** 버킷은 "같은 행의 개수와 비용"을
    묶는 일이라 행 단위 접근이 필요하다. 그래서 여기서 CSV를 한 번 더 읽지만, **행 스킵 규약과
    값 가드는 `read_frames`와 글자 그대로 같은 것을 쓰고**, 결과가 어긋나면 호출부가 하드
    에러를 낸다(`_cross_check`) — 두 구현이 갈라지는 것을 사람 눈에 맡기지 않는다.

    🔴 **조건 열의 가드는 비용 열과 다르다.** 비용은 `not (val > MIN_POSITIVE_MS)`이고
    조건은 **`not (val >= 0)`** — `lib/frame_log.py`의 `_collect_nonneg`와 글자 그대로 같은
    부정형이다. 시간 열의 `> 0`을 복사하면 **면적 0인 프레임이 전부 폐기**되는데, 야간 보행
    프레임 대부분이 박스 0개라 그게 곧 표본 대부분이다. 부정형인 이유도 그쪽과 같다 —
    NaN은 어떤 비교에도 False를 돌려주므로 긍정형은 NaN을 통과시킨다.
    """
    condition_present = list(condition_present or [])
    all_value_columns = list(columns_present) + condition_present

    with csv_path.open("r", encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))

    buckets: dict[int, dict[str, list[float]]] = {}
    box_seq: list[int] = []
    rows_used = 0
    rows_skipped = {"unparsable_t_recv": 0, "before_t0": 0, "warmup": 0}
    box_discarded = 0
    cost_discarded = {c: 0 for c in columns_present}
    # 🔴 조건 폐기를 비용 폐기와 **따로** 낸다. 사유 문장이 다르다 — 비용은
    #    "기록되지 않았거나 하한(> 0) 위반"이고, 조건은 "기록되지 않았다(-1)"뿐이다
    #    (0.0은 정상값이라 폐기되지 않는다). 한 통에 합치면 경고 문구가 거짓이 된다.
    condition_discarded = {c: 0 for c in condition_present}
    # 🔴 **불가능한 짝**을 센다. 판정하지 않고 **세기만 한다** — 문장으로 단언하면 모델이
    #    퇴행하거나 arm이 바뀌어도 같은 문장이 그대로 나간다.
    #    같은 루프에서 센다(새 배열도 재순회도 만들지 않는다).
    pair_counts = {
        # 박스는 그렸는데 면적이 정확히 0. 아주 작은 박스가 반올림으로 0이 됐거나(앱이
        # 6자리 미만으로 썼다), 면적 계산이 박스와 다른 좌표를 봤거나.
        "boxes_positive_fill_zero": 0,
        # 🔴 그릴 박스가 없는데 면적이 있다 — **앱 결함 지목**이다. 두 값이 같은 프레임의
        #    같은 draw 결정에서 나와야 하는데 갈라졌다는 뜻이다(누적 리셋 누락 등).
        "boxes_zero_fill_positive": 0,
    }

    t0 = _to_int(rows[0].get("t_recv_ns")) if rows else MISSING
    cutoff = t0 + int(warmup_sec * 1e9) if t0 != MISSING else MISSING

    for row in rows:
        t_recv = _to_int(row.get("t_recv_ns"))
        # ── read_frames와 **같은 순서·같은 사유**의 세 스킵 경로.
        if t_recv == MISSING:
            rows_skipped["unparsable_t_recv"] += 1
            continue
        if t_recv < t0:
            rows_skipped["before_t0"] += 1
            continue
        if t_recv < cutoff:
            rows_skipped["warmup"] += 1
            continue
        rows_used += 1

        # 🔴 카운트 열의 가드는 `>= 0`이다 (`_collect_nonneg`). 시간 열의 `> 0`을 복사하면
        #    **박스 0개 프레임이 전부 폐기된다** — 야간 보행 프레임 대부분이 그렇다.
        box = _to_int(row.get(BUCKET_COLUMN))
        if not (box >= 0):
            box_discarded += 1
            continue
        box_seq.append(box)
        bucket = buckets.setdefault(box, {c: [] for c in all_value_columns})
        for col in columns_present:
            # 🔴 시간 열의 가드는 `> 0`이고 상한이 없다 (`_collect`의 기본 bounds).
            #    부정형으로 쓰는 이유도 그쪽과 같다 — NaN이 통과하면 백분위가 무의미해진다.
            val = _to_float(row.get(col))
            if not (val > MIN_POSITIVE_MS):
                cost_discarded[col] += 1
                continue
            bucket[col].append(val)
        for col in condition_present:
            # 🔴 조건(비율) 열의 가드는 `>= 0`이다 (`_collect_nonneg`). 상한도 없다 —
            #    겹침을 보정하지 않으므로 **1을 넘는 값이 정상**이라, 1을 상한으로 두면
            #    박스가 겹친 프레임이 통째로 폐기된다.
            val = _to_float(row.get(col))
            if not (val >= 0):
                condition_discarded[col] += 1
                continue
            bucket[col].append(val)
            # 불가능한 짝. 조건 열이 유효할 때만 셀 수 있다(폐기된 -1은 면적을 모른다).
            if col == CONDITION_COLUMNS[0]:
                if box > 0 and val == 0.0:
                    pair_counts["boxes_positive_fill_zero"] += 1
                elif box == 0 and val > 0:
                    pair_counts["boxes_zero_fill_positive"] += 1

    return {
        "buckets": buckets,
        "box_seq": box_seq,
        "rows_used": rows_used,
        "rows_skipped": rows_skipped,
        "box_discarded": box_discarded,
        "cost_discarded": cost_discarded,
        "condition_discarded": condition_discarded,
        "pair_counts": pair_counts,
    }


def _cross_check(
    series,
    aligned: dict,
    columns_present: list[str],
    condition_present: list[str] | None = None,
) -> None:
    """행 정렬 재집계가 `read_frames`와 **같은 표본**을 봤는지 확인한다.

    🔴 어긋나면 죽는다. 조용히 다른 표본으로 표를 내면 이 스크립트의 숫자가 다른 스크립트의
    같은 열 숫자와 갈라지고, 그건 이 하네스에서 가장 나쁜 실패다.

    🔴 **조건 열도 여기서 대조한다.** 이것이 `bucketize`의 `>= 0` 가드가 `read_frames`의
    `_collect_nonneg`와 같다는 **유일한 기계적 증거**다 — 실수로 `> 0`(시간 열 가드)을
    복사하면 면적 0인 프레임이 전부 빠지고, 표에는 그냥 "표본이 좀 적네"로 보인다.
    표본 수 대조가 없으면 그 사고가 사람 눈에만 맡겨진다.
    """
    condition_present = list(condition_present or [])
    problems = []
    if aligned["rows_used"] != series.rows_used:
        problems.append(
            f"rows_used {aligned['rows_used']} != read_frames {series.rows_used}"
        )
    if aligned["box_seq"] != [int(b) for b in series.overlay_boxes]:
        problems.append(
            f"{BUCKET_COLUMN} 수열 길이/값 불일치 "
            f"(이쪽 {len(aligned['box_seq'])}개, read_frames {len(series.overlay_boxes)}개)"
        )
    for col in list(columns_present) + condition_present:
        mine = sum(len(b[col]) for b in aligned["buckets"].values())
        theirs = len(getattr(series, col))
        if mine != theirs:
            problems.append(f"{col} 표본 수 {mine} != read_frames {theirs}")
    if problems:
        raise FrameLogError(
            "행 정렬 재집계가 read_frames와 어긋난다 — 가드/스킵 규약이 갈라졌다: "
            + "; ".join(problems)
        )


def _session_stamp(session: dict) -> dict:
    """표에 실을 조건. **arm·조명·커밋·계측 방식 없이 이 숫자를 인용하지 않는다.**"""
    build = session.get("build") or {}
    gpu_timer = session.get("gpu_timer") or {}
    if not isinstance(build, dict):
        build = {}
    if not isinstance(gpu_timer, dict):
        gpu_timer = {}
    return {
        "render_arm": session.get("render_arm"),
        "lighting_condition": session.get("lighting_condition"),
        "git_commit": build.get("git_commit"),
        # 앱이 문자열 'false'/'true'로 낸다. **불리언으로 바꾸지 않는다** —
        # 원문을 그대로 싣고, 판단은 아래 dirty 불리언에서 따로 낸다.
        "git_dirty": build.get("git_dirty"),
        "git_dirty_is_true": str(build.get("git_dirty")).strip().lower() == "true",
        "build_type": session.get("build_type"),
        "gpu_timer_instrumentation": gpu_timer.get("instrumentation"),
        "schema_version": session.get("schema_version"),
    }


def analyze_run(csv_path: Path, run_dir: Path, warmup_sec: float) -> dict:
    """런 하나 → 결과 블록. 못 낸 런도 **사유를 담은 블록**을 돌려준다(조용히 빠지지 않는다)."""
    run_id = run_dir.name
    block: dict = {
        "run_id": run_id,
        "run_dir": str(run_dir),
        "frames_csv": str(csv_path),
        "usable": False,
    }

    session_path = run_dir / "session.json"
    if not session_path.exists():
        block["skip_reason"] = "session_json_missing"
        block["skip_detail"] = (
            f"session.json이 없다: {session_path} — arm·조명·커밋·계측 방식을 알 수 없으므로 "
            "이 런의 숫자는 조건 없는 숫자다. 건너뛴다"
        )
        return block
    session = read_session(session_path)
    block.update(_session_stamp(session))

    try:
        series = read_frames(csv_path, warmup_sec=warmup_sec)
    except FrameLogError as exc:
        diag = _warmup_diagnosis(csv_path, warmup_sec)
        exhausted = (
            diag["rows_read"] > 0 and diag["rows_before_cutoff"] >= diag["rows_read"]
        )
        block["skip_reason"] = "warmup_no_rows" if exhausted else "frame_log_error"
        block["skip_detail"] = (
            (
                f"warmup {warmup_sec}s 미달로 **표본 0** — 전체 {diag['rows_read']}행이 모두 "
                f"warmup 구간에 있다(노출 span {diag['span_sec']}s). "
                "빈 표가 아니라 '재지 못했다'다"
            )
            if exhausted
            else f"read_frames 실패: {exc}"
        )
        block["diagnosis"] = diag
        block["frame_log_error"] = str(exc)
        return block

    columns_present = [c for c in COST_COLUMNS if c in series.gpu_columns_present
                       or c in series.overlay_columns_present]
    block["cost_columns_present"] = columns_present
    block["cost_columns_defined"] = list(COST_COLUMNS)

    # 조건 열은 **오버레이 열 목록**에만 산다(GPU 목록에 있으면 상수 자기검사가 이미 죽였다).
    condition_present = [c for c in CONDITION_COLUMNS if c in series.overlay_columns_present]
    block["condition_columns_present"] = condition_present
    block["condition_columns_defined"] = list(CONDITION_COLUMNS)
    block["fill_frac_present"] = bool(condition_present)
    if not condition_present:
        # 🔴 **런을 건너뛰지 않는다.** v7 로그에는 이 열이 아예 없고, 그때의 개수 표는
        #    지금까지처럼 그대로 유효하다(그 빌드의 I는 개수·둘레로 설명된다). 면적이
        #    없다는 것은 **표의 한계**이지 그 런의 결함이 아니다.
        #    선언 schema_version을 사유와 함께 실어 두 경우를 가른다:
        #    "v7 로그였다"(정상)와 "v8 빌드인데 면적을 안 실었다"(앱 결함).
        block["fill_frac_absent_reason"] = (
            f"frames.csv에 `{CONDITION_COLUMNS[0]}`(면적) 열이 없다 — 개수 표는 그대로 "
            f"내지만 **면적 축은 이 런에 없다.** 선언 schema_version="
            f"{block.get('schema_version')}이 두 경우를 가른다: <= 7이면 fill 이전 빌드라 "
            f"정상이고(그때의 stage_i_ms는 개수·둘레로 설명된다), 8 이상인데 열이 없으면 "
            f"앱이 면적을 싣지 않은 것이므로 같은 개수의 큰 박스와 작은 박스를 구분할 수 "
            f"없다 — 그 런의 stage_i_ms를 면적 조건 없이 인용하지 말 것"
        )

    if BUCKET_COLUMN not in series.overlay_columns_present:
        block["skip_reason"] = "no_overlay_boxes_column"
        block["skip_detail"] = (
            f"frames.csv에 `{BUCKET_COLUMN}` 열이 없다 — 이 arm은 ④ 오버레이를 그리지 않거나 "
            "v7 이전 로그다. 개수별로 가를 축이 없으므로 건너뛴다(결함이 아니다)"
        )
        return block
    if not columns_present:
        block["skip_reason"] = "no_cost_columns"
        block["skip_detail"] = (
            f"비용 열({', '.join(COST_COLUMNS)})이 하나도 없다 — 가를 값이 없으므로 건너뛴다"
        )
        return block

    aligned = bucketize(csv_path, warmup_sec, columns_present, condition_present)
    # 🔴 교차검사 실패를 **런 하나의 스킵으로 내린다.** 예전에는 여기서 FrameLogError가
    #    그대로 올라가 `analyze_run`을 리스트 컴프리헨션으로 도는 호출자를 통째로 죽였다 —
    #    런 하나의 규약 붕괴가 나머지 전부의 분석을 같이 죽이는 것은 과하다.
    #    🔴 **조용히 넘기는 것이 아니다**: skip_reason으로 남고 ERROR로 찍히며,
    #    그 런은 usable에서 빠지므로 어느 표에도 서지 않는다.
    #    알려진 도달 경로: overlay_boxes=-1인 행의 비용 열이 유효할 때
    #    (bucketize는 박스를 몰라 그 행을 못 담고, read_frames는 열 독립이라 담는다).
    #    실측 v7 런 244개 전수에서 해당 행은 0건이었다 — 잠재 경로다.
    try:
        _cross_check(series, aligned, columns_present, condition_present)
    except FrameLogError as exc:
        LOG.error("[%s] 교차검사 실패 — 이 런을 표에서 뺀다: %s", block["run_id"], exc)
        block["skip_reason"] = "cross_check_mismatch"
        block["skip_detail"] = str(exc)
        return block

    window = series.analysis_window_sec
    # 🔴 판정선이 아니다. `SUSTAINED_SEC`는 "지속 조건"이고, 못 넘는 런은 **봉투 점**이다.
    #    숫자를 여기 적지 않는다 — lib/targets.py에서 가져온다.
    sustained = bool(window is not None and window >= targets.SUSTAINED_SEC)

    bucket_rows = []
    for box in sorted(aligned["buckets"]):
        vals = aligned["buckets"][box]
        row = {
            "boxes": box,
            # n = 그 버킷의 **프레임 수**. 각 열의 count와 다를 수 있다(NOTES 참고).
            "n": sum(1 for b in aligned["box_seq"] if b == box),
        }
        for col in columns_present:
            row[col] = summarize(vals[col])
            row[f"{col}_clock"] = clock_of(col)
            row[f"{col}_unit"] = unit_of(col)
        for col in condition_present:
            # 🔴 자릿수가 다르다. 3자리로 요약하면 작은 박스의 면적이 0.0으로 찍히고,
            #    그 줄을 읽는 사람에게는 "면적이 없었다"가 된다(반올림이지 관측이 아니다).
            row[col] = summarize(vals[col], digits=CONDITION_SUMMARY_DIGITS)
            row[f"{col}_unit"] = unit_of(col)
        bucket_rows.append(row)

    flicker = overlay_flicker(
        series.overlay_boxes,
        window,
        discarded=sum(series.discarded.get(BUCKET_COLUMN, {}).values()),
    )

    block.update(
        {
            "usable": True,
            "rows_read": series.rows_read,
            "rows_used": series.rows_used,
            "rows_skipped": dict(series.rows_skipped),
            "rows_skipped_warmup": series.rows_skipped.get("warmup", 0),
            "rows_skipped_anomalous": series.rows_skipped_anomalous,
            "rows_accounted": series.accounting_ok,
            "analysis_window_sec": (round(window, 3) if window is not None else None),
            "window_meets_sustained": sustained,
            "sustained_sec_target": targets.SUSTAINED_SEC,
            "window_note": (
                "분석 창이 lib/targets.py의 SUSTAINED_SEC를 넘는다"
                if sustained
                else "🔴 분석 창이 lib/targets.py의 SUSTAINED_SEC보다 짧다 — **봉투 점**이다. "
                "지속 성능의 근거로 쓰지 않는다(열·클럭 하강이 아직 오지 않았을 수 있다)"
            ),
            "buckets": bucket_rows,
            "distinct_box_counts": sorted(aligned["buckets"]),
            "boxes_discarded": aligned["box_discarded"],
            "cost_discarded": aligned["cost_discarded"],
            # 🔴 비용 폐기와 **따로** 낸다 — 사유 문장이 다르다(조건 열은 0.0이 정상값이라
            #    폐기되는 것은 -1뿐이다).
            "condition_discarded": aligned["condition_discarded"],
            "condition_summary_digits": CONDITION_SUMMARY_DIGITS,
            # 🔴 **판정하지 않고 센다.** 0이 아니면 그 자체가 조사할 사실이다.
            "boxes_positive_fill_zero": aligned["pair_counts"]["boxes_positive_fill_zero"],
            "boxes_zero_fill_positive": aligned["pair_counts"]["boxes_zero_fill_positive"],
            # 🔴 전이 수는 **런 전체의 순서 성질**이라 버킷 안에 넣을 수 없다.
            #    같은 계산의 주인은 analyze_frames.overlay_flicker다(재구현하지 않았다).
            "flicker": flicker,
            "frame_log_warnings": list(series.warnings),
        }
    )
    return block


# ── 콘솔 표 ───────────────────────────────────────────────────────────────
def _fmt(v) -> str:
    return "-" if v is None else (f"{v:g}" if isinstance(v, (int, float)) else str(v))


def _cell(stat: dict) -> str:
    """`p50/p95 (n)`. 🔴 n을 절대 떼지 않는다 — 표본 3개짜리 백분위를 실측처럼 읽게 된다."""
    if not stat or not stat.get("count"):
        return "표본0"
    return f"{_fmt(stat['p50'])}/{_fmt(stat['p95'])} (n={stat['count']})"


def _print_run(block: dict) -> None:
    if not block.get("usable"):
        LOG.warning(
            "  [건너뜀] %s — %s / %s",
            block["run_id"], block.get("skip_reason"), block.get("skip_detail"),
        )
        return
    LOG.info(
        "  %s | arm=%s | %s | commit=%s dirty=%s | %s | 계측=%s",
        block["run_id"], block.get("render_arm"), block.get("lighting_condition"),
        block.get("git_commit"), block.get("git_dirty"), block.get("build_type"),
        block.get("gpu_timer_instrumentation"),
    )
    LOG.info(
        "    창 %ss (지속조건 %ss 충족=%s) | 행 %s/%s 사용 (warmup 제외 %s행) | 회계=%s",
        block.get("analysis_window_sec"), targets.SUSTAINED_SEC,
        block.get("window_meets_sustained"), block.get("rows_used"),
        block.get("rows_read"), block.get("rows_skipped_warmup"),
        block.get("rows_accounted"),
    )
    if not block.get("window_meets_sustained"):
        LOG.warning("    ⚠ %s", block.get("window_note"))
    # 비용 칸 뒤에 조건 칸을 붙인다. `_cell`을 그대로 쓰므로 `n=` 표기가 조건 칸에도 붙는다 —
    # 면적 표본 수가 비용 표본 수와 다를 수 있고(그 열만 -1인 프레임), 그 사실이 보여야 한다.
    cols = list(block.get("cost_columns_present") or []) + list(
        block.get("condition_columns_present") or []
    )
    header = f"    {'박스':>4} {'n':>6}"
    for c in cols:
        header += f"  {c + ' p50/p95 [' + unit_of(c) + ']':<38}"
    LOG.info(header)
    for row in block.get("buckets") or []:
        line = f"    {row['boxes']:>4} {row['n']:>6}"
        for c in cols:
            line += f"  {_cell(row.get(c)):<38}"
        LOG.info(line)
    if not block.get("fill_frac_present"):
        LOG.warning("    ⚠ %s", block.get("fill_frac_absent_reason"))
    fl = block.get("flicker") or {}
    if fl.get("available"):
        LOG.info(
            "    깜빡임 전이(>0→0→>0) %s회 | 0인 프레임 비율 %s | 끝에 걸린 0 구간 %s개는 "
            "세지 않았다 | 개수 변화 %s회",
            fl.get("blank_transitions"), fl.get("zero_frame_fraction"),
            fl.get("zero_runs_at_edge"), fl.get("box_count_changes"),
        )
        if fl.get("drew_then_stopped"):
            LOG.warning(
                "    ⚠ 그리다가 멈췄다(뒷자락 0 구간 %s프레임) — 전이 수에는 나타나지 않는다",
                fl.get("tail_zero_run_frames"),
            )
    else:
        LOG.warning("    깜빡임 전이: 낼 수 없다 — %s", fl.get("reason"))
    for name, disc in (block.get("cost_discarded") or {}).items():
        if disc:
            LOG.warning(
                "    ⚠ %s 값 %s개가 폐기됐다(기록되지 않음/하한 위반) — 그 프레임들은 이 열의 "
                "분포에 없다. 버킷 n과 count의 차이가 이것이다", name, disc,
            )
    for name, disc in (block.get("condition_discarded") or {}).items():
        if disc:
            LOG.warning(
                "    ⚠ %s 값 %s개가 폐기됐다 — 가드(`>= 0`)를 통과하지 못한 값이다. "
                "🟢 **0.0은 폐기되지 않는다**(그 프레임에 그린 박스가 없었을 뿐이며 정상값이다). "
                "🔴 실기기 로그에서는 이것이 **기록되지 않은 값(-1)**이지만, 이 문장을 "
                "\"-1뿐이다\"로 읽지 말 것 — NaN·음수·파싱 불가도 같은 자리로 떨어지고 "
                "여기서는 서로 구분되지 않는다. 버킷 n과 count의 차이가 이것이다", name, disc,
            )
    if block.get("boxes_discarded"):
        LOG.warning(
            "    ⚠ %s 값 %s개가 폐기됐다 — 그 행은 버킷에도 전이 수열에도 없다",
            BUCKET_COLUMN, block.get("boxes_discarded"),
        )
    # 🔴 **판정하지 않고 센다.** 아래 두 문장은 "무엇을 세었는가"만 말하고 원인을 단정하지
    #    않는다 — 단정하면 모델·arm이 바뀌어도 같은 진단이 그대로 나간다.
    if block.get("boxes_positive_fill_zero"):
        LOG.warning(
            "    ⚠ 박스 > 0 인데 면적 == 0.0 인 프레임 %s개 — 아주 작은 박스가 앱의 "
            "반올림으로 0이 됐거나(자릿수 부족), 면적 계산이 박스와 다른 좌표를 봤을 수 "
            "있다. 세었을 뿐 판정하지 않는다", block.get("boxes_positive_fill_zero"),
        )
    if block.get("boxes_zero_fill_positive"):
        LOG.error(
            "    🔴 박스 == 0 인데 면적 > 0 인 프레임 %s개 — **그릴 박스가 없는데 면적이 "
            "있다.** 두 값이 같은 프레임의 같은 draw 결정에서 나와야 하므로 앱 쪽을 볼 "
            "지점이다. 세었을 뿐 판정하지 않는다", block.get("boxes_zero_fill_positive"),
        )


def _print_overview(usable: list[dict], all_columns: list[str]) -> None:
    """버킷 순 개요. **런을 합치지 않는다** — 줄마다 런·커밋이 붙는다."""
    LOG.info("── 개요 (버킷 순. 🔴 런을 합치지 않았다 — 줄마다 런·커밋이 붙는다)")
    header = f"  {'박스':>4} {'n':>6}  {'run':<16} {'commit':<9} {'arm':<32}"
    for c in all_columns:
        header += f"  {c + ' p50/p95':<34}"
    LOG.info(header)
    rows = []
    for block in usable:
        for row in block.get("buckets") or []:
            rows.append((row["boxes"], block, row))
    for boxes, block, row in sorted(rows, key=lambda r: (r[0], r[1]["run_id"])):
        line = (
            f"  {boxes:>4} {row['n']:>6}  {block['run_id']:<16} "
            f"{str(block.get('git_commit')):<9} {str(block.get('render_arm')):<32}"
        )
        present = list(block.get("cost_columns_present") or []) + list(
            block.get("condition_columns_present") or []
        )
        for c in all_columns:
            # 🔴 "그 런에 열이 없다"와 "열은 있는데 유효 표본이 0이다"를 **가른다.**
            #    한 칸에 뭉개면 계측 방식이 다른 arm(패스별 vs 단일 query)이 "재고 실패한
            #    런"으로 읽힌다 — frame_log가 열 존재/값 폐기를 가르는 것과 같은 이유다.
            #    🔴 조건 열이 붙은 뒤로 이 분기가 더 중요해졌다: v7 런과 v8 런이 한 표에
            #    서면 v7 줄의 면적 칸은 **'열없음'**이어야 하고, 그건 "면적이 0이었다"도
            #    "표본 0"도 아니다. 뭉개면 fill 이전 빌드가 "면적을 못 잰 런"으로 읽힌다.
            line += f"  {(_cell(row.get(c)) if c in present else '열없음'):<34}"
        LOG.info(line)


def main() -> int:
    parser = common_argparser()
    # 🔴 `--label`은 common_argparser에 **없다**(확인함). analyze_frames.py가 자기 인자로
    #    더하는 것과 같은 방식으로 여기서 더한다 — 문구도 그쪽과 같은 뜻으로 맞춘다.
    parser.add_argument("--label", default="", help="이 측정에 붙일 메모")
    parser.add_argument(
        "--frames",
        action="append",
        default=None,
        help=(
            "런 디렉토리 또는 frames.csv 경로. **반복 가능**하다. 같은 디렉토리의 "
            "session.json을 반드시 함께 읽는다(없으면 그 런을 건너뛴다)"
        ),
    )
    parser.add_argument(
        "--warmup_sec",
        type=float,
        default=targets.DEFAULT_WARMUP_SEC,
        help=(
            f"첫 N초 제외 (기본값은 lib/targets.py의 DEFAULT_WARMUP_SEC="
            f"{targets.DEFAULT_WARMUP_SEC} — AE/AWB 수렴 전 프레임은 튄다). "
            "절단 기준은 read_frames와 같은 **첫 행의 t_recv_ns**다"
        ),
    )
    args = parser.parse_args()
    if not args.frames:
        parser.error("--frames를 최소 1개 줘야 한다")

    paths = init_run(stage=STAGE, script_file=__file__, args=args)

    # 경로 오류는 **먼저 죽는다.** 조용히 건너뛰면 오타가 "데이터가 없었다"로 읽힌다.
    # 🔴 데이터 조건(오버레이 열 없음 등)으로 건너뛰는 것과 **다른 부류**라 종료 코드도 다르다:
    #    이건 호출자의 오류이므로 표를 내지 않고 2로 끝낸다.
    try:
        inputs = [resolve_input(raw) for raw in args.frames]
    except FileNotFoundError as exc:
        LOG.error("입력 경로 오류: %s", exc)
        return 2

    blocks = [analyze_run(csv_path, run_dir, args.warmup_sec) for csv_path, run_dir in inputs]
    usable = [b for b in blocks if b.get("usable")]
    skipped = [b for b in blocks if not b.get("usable")]

    cost_columns_used = [
        c for c in COST_COLUMNS if any(c in (b.get("cost_columns_present") or []) for b in usable)
    ]
    condition_columns_used = [
        c
        for c in CONDITION_COLUMNS
        if any(c in (b.get("condition_columns_present") or []) for b in usable)
    ]
    # 개요 표의 열 = 비용 + 조건. 어느 런에도 없는 열은 서지 않는다(빈 칸만 늘어난다).
    all_columns = cost_columns_used + condition_columns_used
    commits = sorted({str(b.get("git_commit")) for b in usable})
    instrumentations = sorted({str(b.get("gpu_timer_instrumentation")) for b in usable})
    arms = sorted({str(b.get("render_arm")) for b in usable})
    mixed_builds = len(commits) > 1
    cross_build_note = (
        "🔴 **이 표의 런들은 같은 빌드가 아니다.** 커밋 "
        + " / ".join(commits)
        + " 이 한 표에 서 있다. 서로 다른 커밋의 값을 빼거나 '늘었다/줄었다'로 읽지 "
        "않는다 — 교차 빌드 비교는 이 저장소가 한 번 정정한 결함이다(STATUS의 '+13ms 정정'). "
        "개수 범위를 넓히려면 여러 런이 필요했고 그 런들이 다른 빌드에서 나왔다는 것이 "
        "이 표의 한계다"
        if mixed_builds
        else (
            f"이 표의 런은 모두 같은 커밋({commits[0]})에서 나왔다"
            if commits
            else "🔴 쓸 수 있는 런이 0개다 — 커밋을 말할 표 자체가 없다"
        )
    )

    summary = {
        "run_ts": paths.run_ts,
        "stage": STAGE,
        "label": args.label,
        "warmup_sec": args.warmup_sec,
        "warmup_sec_default_source": "lib/targets.py:DEFAULT_WARMUP_SEC",
        "warmup_cut_basis": "첫 행의 t_recv_ns (lib/frame_log.py read_frames와 같은 규약)",
        "sustained_sec_target": targets.SUSTAINED_SEC,
        "sustained_sec_source": "lib/targets.py:SUSTAINED_SEC",
        "bucket_column": BUCKET_COLUMN,
        "cost_columns_defined": list(COST_COLUMNS),
        "cost_columns_used": cost_columns_used,
        "condition_columns_defined": list(CONDITION_COLUMNS),
        "condition_columns_used": condition_columns_used,
        "clock_of_column": {c: clock_of(c) for c in COST_COLUMNS},
        # 🔴 비용 열과 조건 열이 **한 표에 선다.** 단위 라벨이 요약에도 있어야 한 줄만
        #    옮겨간 곳에서 ms인지 무차원인지 되물을 수 있다.
        "unit_of_column": {
            c: unit_of(c) for c in list(COST_COLUMNS) + list(CONDITION_COLUMNS)
        },
        "condition_summary_digits": CONDITION_SUMMARY_DIGITS,
        "condition_summary_digits_source": (
            "docs/FRAME_LOG_SCHEMA.md ④ 오버레이 절이 앱에 요구하는 자릿수(소수 6자리)와 "
            "같게 둔다 — 3자리로 요약하면 작은 박스의 면적이 0.0으로 찍혀 리포트 계층이 "
            "그 요구를 되돌린다"
        ),
        "notes": NOTES,
        # ── 불리언 (사람이 표를 읽고 판단하게 두지 않는다) ──
        "any_usable_run": bool(usable),
        "mixed_builds": mixed_builds,
        "mixed_instrumentation": len(instrumentations) > 1,
        "mixed_arms": len(arms) > 1,
        "any_run_warmup_no_samples": any(
            b.get("skip_reason") == "warmup_no_rows" for b in skipped
        ),
        "all_runs_meet_sustained_window": bool(usable) and all(
            b.get("window_meets_sustained") for b in usable
        ),
        "any_run_dirty_build": any(b.get("git_dirty_is_true") for b in usable),
        "any_run_accounting_broken": any(not b.get("rows_accounted") for b in usable),
        # 🔴 면적 열이 없는 런이 표에 섞였는가. 이 표의 개수 칸은 그래도 유효하지만,
        #    그 런의 stage_i_ms를 **면적 조건 없이** 인용하면 안 된다.
        "any_run_missing_fill_frac": any(not b.get("fill_frac_present") for b in usable),
        # 🔴 세었을 뿐 판정이 아니다. True면 조사할 사실이 있다는 뜻이고, 어느 런인지는
        #    runs[]의 같은 이름 카운터에 있다.
        "any_run_boxes_zero_fill_positive": any(
            b.get("boxes_zero_fill_positive") for b in usable
        ),
        "any_run_boxes_positive_fill_zero": any(
            b.get("boxes_positive_fill_zero") for b in usable
        ),
        "verdict": None,
        "verdict_note": NOTES["no_threshold"],
        "cross_build": {
            "commits": commits,
            "instrumentations": instrumentations,
            "render_arms": arms,
            "note": cross_build_note,
        },
        "counts": {
            "inputs": len(inputs),
            "usable": len(usable),
            "skipped": len(skipped),
            "warmup_no_samples": sum(
                1 for b in skipped if b.get("skip_reason") == "warmup_no_rows"
            ),
        },
        "runs": blocks,
    }

    # ── 콘솔 표 ──
    LOG.info(
        "④ 오버레이 비용 × 박스 개수 — 입력 %s런 (사용 %s / 건너뜀 %s), warmup %ss",
        len(inputs), len(usable), len(skipped), args.warmup_sec,
    )
    for key in ("boxes_are_condition", "fill_frac_is_area_sum",
                "fill_frac_not_painted_pixels", "fill_frac_zero_bucket_is_identity",
                "no_area_bucket", "stage_i_upper_bound", "stage_h_cpu_clock",
                "no_slope", "bucket_n_vs_sample_n", "cross_run_not_pooled", "no_threshold"):
        LOG.warning("  %s", NOTES[key])
    if mixed_builds:
        LOG.error("  %s", cross_build_note)
    else:
        LOG.info("  %s", cross_build_note)
    if summary["mixed_instrumentation"]:
        LOG.warning(
            "  ⚠ 계측 방식이 섞여 있다(%s) — `stage_i_ms`(패스별)와 `gpu_frame_ms`(프레임 "
            "단일 query)는 **다른 계측**이며 서로 빼지 않는다", ", ".join(instrumentations),
        )
    LOG.info("── 런별")
    for block in blocks:
        _print_run(block)
    if usable:
        _print_overview(usable, all_columns)
    if summary["any_run_warmup_no_samples"]:
        LOG.error(
            "🔴 warmup %ss 미달로 **표본 0**이 된 런 %s개: %s — 빈 표가 아니라 '재지 못했다'다",
            args.warmup_sec, summary["counts"]["warmup_no_samples"],
            ", ".join(
                b["run_id"] for b in skipped if b.get("skip_reason") == "warmup_no_rows"
            ),
        )

    if paths.outputs_enabled:
        out_path = paths.out_dir / SUMMARY_NAME
        with out_path.open("w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2, sort_keys=True)
            f.write("\n")
        LOG.info("요약 저장: %s", out_path)
    else:
        LOG.info("outputs 비활성 — %s를 쓰지 않았다 (표는 위 콘솔에 있다)", SUMMARY_NAME)

    if not usable:
        LOG.error(
            "표를 낼 수 있는 런이 0개다 — 입력 %s런 전부 건너뛰었다. 위 사유를 볼 것",
            len(inputs),
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
