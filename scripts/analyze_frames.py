"""프레임 로그 → 요약 JSON + 판정.

PoC가 뱉은 frames CSV를 읽어 분포를 내고, `FRAME_BUDGET.md`의 판정선과 대조한다.
결과는 `outputs/<stage>/<run_ts>/summary.json`에 git commit·기기 메타와 함께 남는다.

    python scripts/analyze_frames.py --frames <경로.csv> [--session <경로.json>]
"""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lib import device_meta, targets  # noqa: E402
from lib.frame_log import (  # noqa: E402
    ANOMALOUS_SKIP_REASONS,
    DETECT_CADENCE_SERIES,
    DETECT_CUMULATIVE_COLUMNS,
    DETECT_ENABLED_PATH,
    DETECT_MODEL_SHA_PATH,
    DETECT_PADDING_FRACTION_PATH,
    DETECT_PERIOD_N_PATH,
    DETECT_SCORE_COLUMNS,
    DETECT_DISTRIBUTION_COUNT_COLUMNS,
    DETECT_ROW_SKIP_REASON_TEXT,
    DETECT_TIME_COLUMNS,
    DETECT_WALL_SERIES,
    GPU_FRAME_COLUMN,
    GPU_SUM_COLUMNS,
    GPU_TIME_COLUMNS,
    PIPELINE_STAGES,
    ROW_SKIP_REASON_TEXT,
    SCHEMA_VERSION,
    STAGE_D_FAMILY_COLUMNS,
    STAGE_D_TOTAL_COLUMN,
    FrameLogError,
    check_detect_ep,
    check_lighting_condition,
    check_pipeline_stages,
    check_render_arm,
    check_schema_version,
    read_detect,
    read_frames,
    read_session,
    session_field,
)
from lib.run_utils import common_argparser, init_run  # noqa: E402
from lib.stats import summarize  # noqa: E402

LOG = logging.getLogger(__name__)

# ── 진단용 임계 (판정선이 아니다) ────────────────────────────────────────────
# `lib/frame_log.py`의 CLOCK_DWELL_RATIO_LIMIT과 같은 부류다. **판정선(lib/targets.py)과
# 절대 섞지 않는다** — 이 값은 PASS/FAIL도 exit code도 흔들지 않고, 오직 "이 런의
# 프레임타임을 단계 비용의 미터로 읽어도 되는가"를 사람에게 알려주는 데만 쓴다.
#
# 무엇을 판정하나: p50(output_interval_ms)가 p50(recv_interval_ms)와 **사실상 같은가**.
# 같다면 파이프라인은 카메라가 주는 대로 그대로 뱉고 있다는 뜻이고, 그때 프레임타임은
# 단계 비용을 전혀 반영하지 않는다(공급 주기에 묶여 있다).
#
# 5%인 근거: 두 시계열은 같은 시계에서 나오고, 프레임이 하나도 밀리지 않으면 출력 주기는
# 입력 주기와 통계적으로 같아진다(차이는 지터뿐). 반대로 단계 비용이 공급 주기를 넘기
# 시작하면 프레임이 드롭되면서 출력 주기가 공급 주기의 **정수배 쪽으로** 벌어지므로
# 차이가 최소 수십 %가 된다. 5%는 그 사이의 넓은 빈 구간에 있어 어느 쪽으로도 아슬아슬하지
# 않다. (실측 근거: 빈 파이프라인 런 run_ts=20260731_003819에서 두 p50의 차이는 0.75%였다
#  — p50(output)=32.665ms, p50(recv)=32.422ms. 지터만 있는 상태의 실제 폭이 이 정도다.)
FRAMETIME_PINNED_REL_DIFF = 0.05

# 단계 열 → FRAME_BUDGET.md §3의 칸 이름. **배정치가 아니라 칸 이름만** 쓴다
# (단계별 배정치는 폐기됐다 — 단계 비용은 실측으로만 말한다).
#
# ⚠ 이 매핑은 **"이 열이 어느 칸을 채울 열인가"라는 스키마 사실**이지, "이 런의 그 패스가
#   그 단계를 실제로 돌렸다"는 주장이 아니다. 어느 패스에 무엇이 들어갔는지는 그 런의
#   `render_arm`이 정한다 — 예컨대 패스2에 처리를 넣지 않은 arm에서도 `stage_d_ms`는
#   나온다. 그래서 라벨을 떼는 대신 **숫자 옆에 arm을 붙인다**(_print_stages / stages 블록).
#   라벨을 arm에 따라 바꾸는 것은 하네스가 앱의 arm 의미를 해석하기 시작하는 것이고,
#   그 동기화가 어긋나는 날 조용히 틀린 라벨이 나온다. 매핑 자체는 건드리지 않는다.
#
# **D 계열은 전부 D다.** ②를 여러 패스로 쪼개도 채우는 칸은 하나이며, 그 런의 D는
# `stage_d_total_ms`(D 계열의 행별 합)다 — 감마 arm이든 다패스 arm이든 같은 키로 읽힌다.
BUDGET_CELL_OF = {
    "stage_b_ms": "B",
    "stage_d_ms": "D",
    "stage_d_analyze_ms": "D",
    "stage_d_build_ms": "D",
    "stage_d_apply_ms": "D",
    "stage_d_denoise_ms": "D",
    # 서수 2 = 그 arm의 **두 번째 톤커브 스테이지**의 같은 역할 슬롯(v4). 조합 arm이 ②
    # 자리에서 스테이지를 두 번 돌아도 채우는 칸은 여전히 D 하나다.
    "stage_d_analyze2_ms": "D",
    "stage_d_build2_ms": "D",
    "stage_d_apply2_ms": "D",
    STAGE_D_TOTAL_COLUMN: "D",  # 파생(행별 합). 실제 CSV 열이 아니다
    "stage_i_ms": "I",
    "gpu_present_ms": None,  # 최종 표시 패스. 실제 GPU 비용이지만 A~J 어느 칸도 아니다
    # 🔴 프레임 단일 query (v5)는 **칸 없음**이다. 단계 비용이 아니라 프레임 전체 GPU
    #    시간이므로, 칸 라벨을 붙이는 순간 그 숫자가 D칸(또는 아무 칸)에 인용된다.
    #    `gpu_present_ms`와 같은 이유로 명시적으로 None을 적어 둔다 — 키를 빼면 "칸이 없다"와
    #    "매핑을 등록하는 것을 잊었다"가 구분되지 않는다.
    GPU_FRAME_COLUMN: None,
}

# ③ 탐지 열 → 버짓 칸. **위 BUDGET_CELL_OF와 별 dict다** (v6).
#
# 🔴 **섞지 않는다.** 위 dict는 GPU 시계 열(GL_EXT_disjoint_timer_query)의 열↔칸 매핑이고
#   여기 열들은 **CPU 벽시계**(SystemClock.elapsedRealtimeNanos) 구간 길이다. 한 dict에 넣는
#   순간 CPU 시계 열이 GPU 합산 경로(`gpu_sum_columns`·`_stage_series_order`·stages 블록의
#   `budget_cell`)의 라벨 체계 안으로 들어가고, 그러면 두 시계의 숫자가 같은 표에서 더해진다.
#   `lib/frame_log.py`의 v6 자기검사가 상수 수준에서 막는 사고를 소비자 쪽에서도 막는 자리다.
#
# ⚠ 위 dict와 **같은 성질**은 그대로다: 이 매핑은 "이 열이 어느 칸을 채울 열인가"라는 스키마
#   사실이지, 그 런의 탐지가 실제로 그 단계를 돌았다는 주장이 아니다. 숫자를 옮길 때는 arm과
#   EP(`detect.ep.resolved`)를 함께 옮긴다 — `_prof` arm의 시간은 아예 인용하지 않는다.
DETECT_BUDGET_CELL_OF = {
    "stage_e_ms": "E",   # letterbox + RGB + NCHW 텐서화
    "stage_f_ms": "F",   # ORT session.run() 1회
    "stage_g_ms": "G",   # conf 필터 + 좌표 변환 + NMS + letterbox 역변환
    # 🔴 파생 시계열은 **칸 없음**이다. E+F+G의 합이 아니라 두 시각의 차(미계상분을 포함한다)
    #   이므로 칸 라벨을 붙이면 그 숫자가 F칸 같은 자리에 인용된다. `gpu_present_ms`·
    #   `gpu_frame_ms`와 같은 이유로 **키를 빼지 않고 명시적으로 None을 적는다** —
    #   키가 없으면 "칸이 없다"와 "매핑 등록을 잊었다"가 구분되지 않는다.
    DETECT_WALL_SERIES: None,
    # 실행 주기도 칸이 없다. 단계 비용이 아니라 간격이다.
    DETECT_CADENCE_SERIES: None,
}

# 🔴 두 매핑이 **한 열도 공유하지 않는지**를 import 시점에 확인한다. 위 주석이 정한 경계는
#    사람이 지키는 것이라, 다음 사람이 detect 열을 BUDGET_CELL_OF에 넣어도 아무 일도
#    일어나지 않으면 그 경계는 없는 것과 같다. `lib/frame_log.py`의 상수 자기검사와 같은 부류다.
_cell_overlap = sorted(set(DETECT_BUDGET_CELL_OF) & set(BUDGET_CELL_OF))
if _cell_overlap:
    raise RuntimeError(
        f"analyze_frames.py 상수 불일치 — {_cell_overlap}이 GPU 시계 매핑(BUDGET_CELL_OF)과 "
        f"CPU 벽시계 매핑(DETECT_BUDGET_CELL_OF) 양쪽에 있다. 두 매핑은 물리량이 다른 열의 "
        f"칸 라벨이며, 겹치면 CPU 시계 열이 GPU 합산 경로의 라벨 체계에 들어간다"
    )
_detect_cell_missing = [
    c for c in tuple(DETECT_TIME_COLUMNS) + (DETECT_WALL_SERIES, DETECT_CADENCE_SERIES)
    if c not in DETECT_BUDGET_CELL_OF
]
if _detect_cell_missing:
    raise RuntimeError(
        f"analyze_frames.py 상수 불일치 — detect 시계열 {_detect_cell_missing}이 "
        f"DETECT_BUDGET_CELL_OF에 없다. 칸이 없는 열도 **명시적으로 None**을 적어야 "
        f"'칸이 없다'와 '등록을 잊었다'가 구분된다"
    )

# 처리 단계가 하나도 없는 로그를 해석할 때 반드시 따라붙어야 하는 단서.
# android-runtime 스킬 §5 — 이 숫자가 팀에 가장 오해받기 쉬운 지점이다.
def empty_pipeline_caveat(camera_fps: float | None) -> str:
    msg = (
        "처리 단계가 없는 파이프라인의 프레임 간격은 연산 비용이 아니라 카메라 공급 속도다. "
    )
    # 예시 숫자는 session.json이 실제 카메라 속도를 알려줄 때만 붙인다.
    # 무조건 "30fps면 33ms"라고 쓰면 60fps 로그에서 틀린 예시가 나간다.
    if camera_fps and camera_fps > 0:
        msg += (
            f"이 로그의 카메라는 {camera_fps:g}fps 요청이므로 {1000.0 / camera_fps:.0f}ms 부근이 나오는데, "
            "이는 '여유가 그만큼 있다'는 뜻이 아니라 '카메라가 그 주기로 준다'는 뜻이다. "
        )
    else:
        msg += (
            "session.json에 카메라 속도가 없어 이 값이 어느 공급 속도에서 나온 것인지 "
            "이 로그만으로는 알 수 없다. "
        )
    msg += (
        "이 값은 여유의 상한이 아니라 바닥값이고, 여기서부터 ①②③④ 비용이 더해진다."
    )
    return msg


def empty_pipeline_contradiction_caveat(
    measured_columns: list[str], stages_declared: object
) -> str:
    """`pipeline_stages`가 비었/없는데 **GPU 단계 값이 실제로 있는** 경우.

    이때 `empty_pipeline_caveat`를 내면 "여기서부터 ①②③④ 비용이 더해진다"라고 단언하는데,
    ②는 **이미 더해져 있고 이미 측정돼 있다.** 조건절 없는 거짓 문장이므로 대신 모순을 낸다.

    `gpu_timer_contradicted` / `capture_clock_base_contradicted`와 **같은 패턴**이다 —
    선언과 실제가 어긋나면 사실을 `source`에 불리언으로 남기고 경고한다.
    ⚠ 판정선이 아니다. verdict·exit code를 흔들지 않는다.
    """
    declared = "키 없음" if stages_declared is None else f"{stages_declared!r}"
    return (
        f"단계 선언과 실측이 어긋난다 — session.json의 pipeline_stages는 비어 있는데"
        f"({declared}) GPU 단계 시계열에는 유효 표본이 있다"
        f"(값이 나온 열: {', '.join(measured_columns)}). "
        f"'처리 단계 없음' 단서는 **내지 않았다** — 그 문장은 '여기서부터 ①②③④ 비용이 "
        f"더해진다'라고 단언하는데, 이 런에서는 그 비용이 이미 더해져 있고 이미 측정돼 있다. "
        f"원인은 둘 중 하나다: (a) 앱/생성기가 그 패스에 해당하는 pipeline_stages 토큰을 "
        f"적지 않았다(② 하위 패스 열은 아직 대응 토큰이 없다 — 생산자는 앱이다), "
        f"(b) 선언이 실제 렌더 경로와 다르다. "
        f"⚠ pipeline_stages는 baseline_diff의 비교 조건이므로, 고치지 않으면 이 런이 "
        f"**처리 없는 런과 '같은 조건'으로 비교된다.** 판정·종료 코드는 그대로다"
    )


def frametime_pinned_caveat(
    pinned: dict,
    stages: list,
    camera_fps: float | None = None,
    stages_label: str = "처리 단계",
) -> str:
    """처리 단계가 **붙어 있는데도** 프레임타임이 카메라 공급에 묶여 있을 때의 단서.

    `empty_pipeline_caveat`는 `pipeline_stages`가 비었을 때만 붙는데, 단계를 얹으면
    배열이 안 비므로 꺼진다. 그런데 그 단서의 핵심 주장("이 숫자는 연산 비용이 아니라
    카메라 공급 속도다")은 **여전히 참**이다. 이 함수가 그 자리를 메운다.

    ⚠ p50만 보고 "단계 비용이 공급 주기보다 작다"고 쓰면 **틀린 경우가 있다.** 단계가
    간헐적이면(예: N프레임마다 탐지) 중앙 프레임은 공급에 묶이지만 무거운 프레임은 이미
    주기를 넘는다. 그래서 tail(p95)이 같이 묶였는지에 따라 문장을 다르게 낸다.
    """
    supply = ""
    if camera_fps and camera_fps > 0:
        # 임계가 아니라 참고 수치다. 판정에 쓰지 않는다.
        supply = f" [참고: 카메라 요청 주기 {1000.0 / camera_fps:.1f}ms]"
    head = (
        f"프레임타임이 카메라 공급에 묶여 있다 — "
        f"p50(output_interval_ms)={pinned['out_p50']:g}ms가 "
        f"p50(recv_interval_ms)={pinned['recv_p50']:g}ms와 사실상 같다"
        f"(차이 {pinned['rel_diff_p50'] * 100:.2f}%, "
        f"진단 임계 {FRAMETIME_PINNED_REL_DIFF * 100:g}%){supply}. "
        # 라벨을 인자로 받는다. 단계 선언이 비었는데 실측은 있는 모순 런에서는 선언 리스트
        # (`[]`)를 그대로 찍으면 "처리 단계 []가 붙어 있는데도"라는 거짓 문장이 나간다 —
        # 그 런에서 근거가 되는 것은 선언이 아니라 **값이 나온 GPU 열**이다.
        f"{stages_label} {stages}가 붙어 있는데도 그렇다. "
    )
    if pinned["tail_pinned"]:
        body = (
            f"tail도 마찬가지다(p95: {pinned['out_p95']:g} vs {pinned['recv_p95']:g}ms, "
            f"차이 {pinned['rel_diff_p95'] * 100:.2f}%). 이는 단계 비용의 합이 공급 주기보다 "
            f"**작다**는 뜻일 뿐, 그 비용이 0이라는 뜻이 아니다. "
        )
    else:
        body = (
            f"다만 tail은 이미 벌어졌다(p95: {pinned['out_p95']:g} vs {pinned['recv_p95']:g}ms, "
            f"차이 {pinned['rel_diff_p95'] * 100:.2f}%) — 중앙 프레임만 공급에 묶여 있고 "
            f"무거운 프레임은 주기를 넘고 있다는 뜻이다(간헐 단계에서 나타나는 모양). "
            f"p50만 보고 '비용이 작다'고 읽지 말 것. "
        )
    return head + body + (
        "이 런에서 프레임타임은 단계 비용의 **미터가 아니라 임계 검출기**다 — "
        "'단계를 얹었는데 프레임타임 회귀가 없다'는 관측은 'D=0'이 아니라 "
        "'D < 공급 주기'로만 읽어야 한다. 단계 비용은 stages 블록(GPU timer)으로만 말할 것. "
        "⚠ 이 임계는 진단용이며 판정선(lib/targets.py)이 아니다"
    )


def nonlist_pipeline_stages_caveat(raw) -> str:
    """`pipeline_stages`가 리스트가 아닐 때. **집계를 죽이지 않는다.**

    `docs/FRAME_LOG_SCHEMA.md` §5가 "하드 에러로 만들지 않는다 — 그때 집계가 죽으면 그날
    측정을 통째로 잃는다"라고 약속한 자리이고, `lib/frame_log.py`도 이 검사가 판정선이
    아니라고 못박고 있다. 값이 이상하면 **요약이 나오되 그 사실이 요약에 적혀 있어야** 한다.
    """
    return (
        f"pipeline_stages가 리스트가 아니라 이 런의 단계 구성을 읽지 못했다 "
        f"({type(raw).__name__}: {raw!r}) — 빈 파이프라인 단서도 프레임타임 묶임 단서도 "
        f"붙이지 않았다(둘 다 '단계가 무엇인지'를 전제한다). 이 로그의 프레임타임이 무엇의 "
        f"비용인지는 이 요약만으로 알 수 없고, baseline_diff는 이 값을 그대로 비교하므로 "
        f"같은 조건이어도 '조건 다름'이 된다. 집계와 판정은 그대로 진행했다 — "
        f"어휘·타입 검사는 판정선이 아니다"
    )


def submit_time_caveat() -> str:
    """`capture_to_render_ms`(와 render_latency_ms)가 GPU 실행 시간을 담지 않는다는 단서."""
    return (
        "capture_to_render_ms는 t_render_end_ns 기준인데, 이 시각은 드로우콜 **제출** 시점이지 "
        "GPU가 그 프레임을 다 그린 시점이 아니다(glDrawArrays는 즉시 반환한다). 즉 이 지연에는 "
        "GPU 실행 시간이 **들어 있지 않다** — ② 저조도 패스를 얹은 뒤 '지연이 그대로다'라고 "
        "읽으면 틀린다. 셰이더를 얹어 늘어난 GPU 비용은 여기가 아니라 stages 블록"
        "(GL_EXT_disjoint_timer_query)에 나타난다. render_latency_ms에도 같은 한계가 있다"
    )


def gpu_timer_contradiction_warning(declared: dict, columns_present: list[str]) -> str:
    return (
        f"session.json은 gpu_timer.supported=true라고 선언했지만(선언 내용: {declared}) "
        f"단계 시계열의 유효 표본이 0개다(헤더에 있는 GPU 열: "
        f"{', '.join(columns_present) if columns_present else '없음'}). "
        f"선언 쪽이 틀렸거나(확장 문자열은 있는데 query가 해소되지 않는다), CSV에 열을 싣지 "
        f"못했거나, 런 내내 disjoint였다. 어느 쪽이든 이 런으로 단계 비용을 말할 수 없다"
    )


def ring_full_bias_warning(n: int, gpu_timer: dict) -> str:
    """timer query 링이 가득 차 계측을 건너뛴 프레임이 있을 때.

    **왜 이것만 경고인가:** 문서에만 둘 사실(귀속 번짐·gpu_sum 하한)은 이 빌드에서 항상
    참이라 매 런 뜨면 곧 아무도 안 본다. 반면 이 값은 **0이 아닐 때만** 발생하는 그 런의
    사고이고, 편향의 **방향이 정해져 있다**(느린 프레임이 빠지므로 tail이 낙관). 이미
    같은 성격의 폐기 경고(_add_discard_warnings)가 있는 자리와 같은 부류다.
    ⚠ 판정선이 아니다 — verdict·exit code를 흔들지 않는다.
    """
    depth = gpu_timer.get("ring_depth_frames")
    instrumented = gpu_timer.get("instrumented_frames")
    extra = ""
    if isinstance(instrumented, int) and instrumented > 0:
        extra = f" (계측 프레임 {instrumented}개 중)"
    return (
        f"gpu_timer.skipped_ring_full_frames={n}{extra} — 링"
        + (f"(깊이 {depth})" if depth is not None else "")
        + "이 가득 차서 계측을 건너뛴 프레임이 있다. 링이 차는 것은 query 회수가 밀렸을 때, "
        "즉 **GPU가 뒤처진 구간**이므로 빠진 쪽이 가장 비싼 프레임이다. 그래서 stage_*_ms와 "
        "gpu_sum_ms의 p95·p99는 낙관 쪽으로 치우쳐 있다 — 이 런의 tail을 상한으로 쓰지 말 것. "
        "⚠ 판정선이 아니다(verdict를 흔들지 않는다)"
    )


def _arm_short(stages: dict) -> str:
    """숫자 줄 옆에 붙일 짧은 arm 표기. **줄에서 떼어져도 뜻이 유지돼야 한다.**"""
    arm = stages.get("render_arm")
    if stages.get("render_arm_known"):
        return f"arm={arm}"
    if arm is None:
        return "arm=미상"
    return f"arm={arm!r}(어휘 밖)"


def _arm_long(stages: dict) -> str:
    arm = stages.get("render_arm")
    if stages.get("render_arm_known"):
        return f"render_arm={arm}"
    if arm is None:
        return "render_arm=미상 (session.json에 키 없음)"
    return f"render_arm={arm!r} (하네스 어휘 밖)"


def _stages_block(
    series,
    stats: dict,
    render_arm: object,
    render_arm_known: bool,
    ring_full_frames: object,
) -> dict:
    """단계 비용 블록. **frametime과 분리한다.**

    간격/체류시간(frametime)과 단계 비용(stages)은 다른 물리량이고 **다른 시계**에서
    온다. 같은 키에 섞으면 소비자가 자기가 받은 숫자가 어느 시계의 무엇인지 구분할 수
    없다 — render_latency_ms와 recv_to_render_ms를 같은 키에 넣지 않는 것과 같은 이유다.

    `render_arm`은 session 블록에도 있지만 **여기에 한 번 더 싣는다.** 이 블록만 떼어
    읽는 소비자(사람이든 스크립트든)에게 arm이 사라지면, 그 사람이 보는 stage_*_ms는
    "어느 렌더 경로에서 나왔는지 모르는 숫자"가 되고 그대로 버짓 칸에 전사된다.
    숫자와 그 숫자의 조건은 같은 블록에 있어야 한다.
    """
    block = {
        "clock": (
            "GPU 시계 (GL_EXT_disjoint_timer_query). t_*_ns(CLOCK_BOOTTIME)와 다른 시계이므로 "
            "frametime 값과 더하거나 빼지 않으며, 시계 교차검사(A/B) 대상도 아니다"
        ),
        "columns_present": list(series.gpu_columns_present),
        # **arm 없이 아래 숫자를 인용하지 않는다.** session 블록의 사본이 아니라, 이 블록을
        # 단독으로 읽는 경로를 막기 위한 동반 기록이다.
        "render_arm": render_arm,
        "render_arm_known": render_arm_known,
        "render_arm_note": (
            "budget_cell은 '이 열이 어느 칸을 채울 열인가'라는 스키마 사실이지, 이 arm이 그 "
            "단계를 실제로 돌렸다는 주장이 아니다. 어느 패스에 무엇이 들어갔는지는 render_arm이 "
            "정한다 — 아래 분포를 FRAME_BUDGET.md의 칸에 옮길 때 arm을 함께 옮길 것. "
            "render_arm_known=false면 그 숫자는 인용 대상이 아니다"
        ),
        # 링이 가득 차 계측을 건너뛴 프레임 수(session.gpu_timer). 0이 아니면 **가장 느린
        # 프레임이 빠진 것**이라 아래 p95/p99가 낙관 쪽으로 치우친다. session 블록에만 두면
        # 이 블록만 읽는 소비자에게는 그 편향이 보이지 않는다. 값이 없으면 None.
        "skipped_ring_full_frames": ring_full_frames,
        "budget_cell": dict(BUDGET_CELL_OF),
        # **이 런에서 실제로 합산 대상이 된 열.** 상수 전체 목록을 그대로 실으면
        # 헤더에 2개뿐인 로그에서도 4개가 실려 "4개를 다 더한 값"으로 오독된다.
        # columns_present와 대조해야만 알 수 있는 상태로 두지 않는다.
        # ⚠ columns_present의 부분집합이다 — gpu_frame_ms(v5)는 헤더에 있어도 여기 없다.
        "gpu_sum_columns": list(series.gpu_sum_columns_present),
        # 스키마가 합산 대상으로 정의한 전체 목록(이 런에 있었는지와 무관). 둘을 나눠 둔다.
        "gpu_sum_columns_defined": list(GPU_SUM_COLUMNS),
        "gpu_sum_note": (
            "gpu_sum_columns는 이 런에서 실제로 더해진 열이다(헤더에 있는 것만). "
            "행별로 유효한 값을 먼저 더한 뒤 백분위를 냈다(p50(B)+p50(D) != p50(B+D)). "
            "유효한 열이 하나도 없는 행은 기여하지 않는다. "
            f"⚠ {GPU_FRAME_COLUMN}은 **더하지 않는다** — 프레임 하나를 query 하나로 감싼 값이라 "
            "패스별 합에 더하면 같은 프레임을 두 번 센다"
        ),
        "gpu_sum_partial_rows": series.gpu_sum_partial_rows,
        # ── 프레임 단일 query (v5) ──────────────────────────────────────────
        # **패스별 열과 다른 물리량이다.** 이 블록만 떼어 읽는 소비자에게 그 사실이
        # 안 보이면 gpu_frame_ms가 곧 gpu_sum_ms 자리에, 또는 D칸에 들어간다.
        "gpu_frame_column": GPU_FRAME_COLUMN,
        "gpu_frame_present": GPU_FRAME_COLUMN in series.gpu_columns_present,
        "gpu_frame_note": (
            f"{GPU_FRAME_COLUMN}은 **프레임 하나를 GL_TIME_ELAPSED query 하나로 감싼 값**이다. "
            f"패스별 값이 아니므로 gpu_sum_ms에도 {STAGE_D_TOTAL_COLUMN}에도 들어가지 않고 "
            f"버짓 칸도 없다(budget_cell=null). 같은 런에 패스별 열과 함께 있을 수 없다 — "
            f"GL_TIME_ELAPSED는 중첩되지 않는다. ⚠ 이 값도 **하한**이다: 마지막 전체화면 패스의 "
            f"타일 해결이 eglSwapBuffers에서 일어나 이 query 바깥이다(알려진 이슈 2)"
        ),
        # 도달 불가한 상태(두 계측이 한 런에 동시에). true면 경고가 함께 나간다.
        "gpu_frame_conflict": series.gpu_frame_conflict,
        # ── D 계열 (D칸을 채우는 열들) ──────────────────────────────────────
        # **`stage_d_total_ms`만 보고 D를 인용할 사람**과 **내부 분해를 보고 경량화 레버를
        # 고를 사람**이 둘 다 있다. 그래서 합과 구성을 같은 블록에 함께 낸다.
        "stage_d_columns": list(series.stage_d_columns_present),
        "stage_d_columns_defined": list(STAGE_D_FAMILY_COLUMNS),
        "stage_d_total_partial_rows": series.stage_d_total_partial_rows,
        "stage_d_ambiguous": series.stage_d_ambiguous,
        "stage_d_total_note": (
            f"{STAGE_D_TOTAL_COLUMN} = stage_d_columns의 **행별 합**이며 이 런의 D칸이다"
            "(감마 arm은 stage_d_ms 하나, 다패스 arm은 하위 슬롯들 — 어느 쪽이든 이 키가 D다). "
            "백분위의 합이 아니다: p50(hist)+p50(cdf) != p50(hist+cdf). "
            "⚠ gpu_sum_ms와 다르다 — gpu_sum_ms는 모든 GPU 열(B + D계열 + I + present)의 합이고 "
            "이 값은 D 계열만의 합이다. stage_d_ambiguous=true면 stage_d_ms가 합계일 가능성이 "
            "남아 있어 이 값이 D를 두 번 셌을 수 있다(warnings 참고)"
        ),
        "has_gpu_timings": series.has_gpu_timings,
    }
    block.update(stats)
    return block


# `safety_regression_block`의 `scope`가 가질 수 있는 값. **자유 문자열이 아니다.**
# 🔴 검증하지 않으면 오타(`"sesion"`)가 **조용히 run 분기로 떨어져** 세션 리포트에
#    "이 런에서 재지 않았다"가 실린다 — 이 함수가 고치려던 결함과 정확히 같은 형태다
#    (문장이 그 자리의 사실을 말하지 않는다). 그래서 죽인다: 스코프가 틀린 것은 데이터
#    문제가 아니라 호출자의 버그이고, 조용히 틀린 문장을 내보내는 것보다 죽는 편이 낫다.
SAFETY_SCOPES = ("run", "session")


def _detect_status_clause(detect_block: Optional[dict], scope: str) -> str:
    """안전 회귀 사유의 **탐지 현황 한 문장**을 그 런의 데이터에서 만든다.

    🔴 이 문장을 [safety_regression_block]에 상수로 적으면 낡는다 — 그 함수 독스트링의
    "두 번 났다"가 그 이력이다. 여기서 세는 것은 `detect.csv`의 **유효 표본 수**이지
    "탐지가 구현됐는가"가 아니다. 하네스는 앱의 구현 여부를 알 수 없고 **알 필요도 없다** —
    로그에 F 표본이 있으면 있는 것이고 없으면 없는 것이다.

    `scope="session"`은 **런 하나가 아니라 세션 전체**를 덮는 자리다(`run_session.py`의
    리포트 머리). 거기서 "이 런에서 재지 않았다"고 쓰면 탐지가 돈 세션에서도 그 문장이
    나가므로, 런별 사실은 `per_run`을 가리키게 한다.
    """
    if scope == "session":
        return (
            "③ 탐지 현황은 **런마다 다르다** — 런별 사유는 아래 `per_run`에 있다."
        )
    if detect_block is None:
        return (
            "③ 탐지 계측(E·F·G)은 **이 런에서 재지 않았다**(`--detect`를 주지 않았다) — "
            "'탐지 비용이 0이었다'가 아니라 **집계하지 않았다**는 뜻이다."
        )
    # `summarize()`가 내는 키는 `count`다(lib/stats.py — 값이 없어도 count=0으로 돌아온다).
    n = (detect_block.get("stage_f_ms") or {}).get("count") or 0
    if not n:
        return (
            "③ 탐지 로그를 읽었지만 **F(추론)의 유효 표본이 0개다** — 열이 없거나 값이 "
            "전부 폐기됐다. '0ms였다'가 아니라 **재지 못한 것**이다."
        )
    return (
        f"③ 탐지가 이 런에서 실제로 돌았고 F(추론) 표본 {n}개를 남겼다 — "
        "**그러나 그것은 비용을 쟀다는 뜻이지 안전을 평가했다는 뜻이 아니다.**"
    )


def safety_regression_block(
    detect_block: Optional[dict] = None, scope: str = "run"
) -> dict:
    """안전 회귀 결과. **이 문장의 사본을 다른 스크립트에 만들지 않는다.**

    `run_session.py`가 이 함수를 import해서 쓴다 — 두 곳에 적으면 한쪽이 낡고, 낡은 쪽이
    "탐지 미구현"처럼 **이미 거짓이 된 문장**을 계속 내보낸다(v6에서 실제로 그렇게 됐다).

    🔴 **`evaluated`는 false 그대로다.** 탐지가 돈다는 것과 안전을 평가했다는 것은 다른
    사실이다. 탐지 계측(E·F·G)이 붙었다고 이 값을 true로 올리면, "탐지가 도니까 안전이
    확인됐다"는 오독을 하네스가 **직접** 하는 것이 된다.

    🔴 **`detect_block`을 받는 이유: 탐지 현황을 정적 문장으로 적으면 반드시 낡는다.**
    이 자리에서 같은 결함이 **두 번** 났다 — 처음엔 "탐지 단계 미구현"이 앱 라운드에서
    거짓이 될 예정이었고, 그 다음엔 "③ 탐지는 돌지만"이 **앱이 미구현인데도** 거짓이었다.
    두 번 다 원인이 같다: 문장이 그 런의 사실을 보지 않았다. 그래서 탐지 현황 한 문장만
    **런 데이터에서 만들고**(`_detect_status_clause`), 나머지(정답 라벨이 없다)는 런과
    무관한 사실이므로 고정 문장으로 둔다. 이 함수에 탐지 현황을 **손으로 적지 마라.**

    `detect_block`이 None이면 `--detect`를 주지 않은 것이다 — "탐지가 0이었다"가 아니라
    **재지 않았다**는 뜻이므로 그렇게 말한다.
    """
    if scope not in SAFETY_SCOPES:
        raise ValueError(
            f"safety_regression_block(scope={scope!r})는 알 수 없는 스코프다 — "
            f"허용: {', '.join(SAFETY_SCOPES)}. 조용히 run으로 떨어뜨리지 않는다: "
            "세션 리포트에 '이 런에서 재지 않았다'가 실리면 그 문장이 그 자리의 사실을 "
            "말하지 않는 것이고, 그게 이 함수가 고치려던 결함 자체다"
        )
    return {
        "evaluated": False,
        "reason": (
            f"{_detect_status_clause(detect_block, scope)} "
            "그것과 별개로 **위험물 강조 누락률은 정답 라벨이 없어 잴 수 "
            "없다.** 재려면 셋이 필요하다: (a) 같은 야간 클립 세트"
            "(INTERFACES.md 계약 C의 녹화 입력), (b) 그 클립의 정답 박스 라벨, "
            "(c) models/golden/의 골든 샘플(계약 §A-6: 야간 720p 입력 + 기대 박스, ±2px 판정) "
            "— **현재 미전달**이다. "
            "⚠ 그리고 stairs 재현율은 야간 거리 장면에서 **한 번도 측정된 적이 없다**"
            "(models/det_c4b_loli0_640/README.md의 held-out 표는 person 클래스 기준이고 "
            "stairs 칸은 오탐률뿐이다). ⚠ 학습 데이터 NightOwls는 **차량 대시캠**이라 "
            "보행 시점과 카메라 높이·모션블러가 다르다 — 폰을 든 보행자 시점의 재현율은 "
            "이 수치로 말할 수 없다. "
            "이 두 사실 때문에 '탐지가 도니까 안전이 확인됐다'로 읽으면 틀린다"
        ),
        # 무엇이 생기면 evaluated=true가 되는가. 사람이 문장에서 추려내게 두지 않는다.
        "blocked_on": [
            "동일 야간 클립 세트 (INTERFACES.md 계약 C)",
            "그 클립의 정답 박스 라벨",
            "models/golden/ 골든 샘플 (계약 §A-6, 현재 미전달)",
        ],
    }


def _detect_block(
    dseries,
    dstats: dict,
    session: dict,
    frames_rows_used: int,
    analysis_window_sec: float | None,
    render_arm: object,
    render_arm_known: bool,
    ep: dict,
) -> dict:
    """③ 탐지 비용 블록 (v6). **stages 블록과 별 블록이다.**

    🔴 **`_stages_block`에 섞지 않는다.** 그 블록의 `clock` 문자열은 "GPU 시계"라고 선언하는데
    E·F·G는 CPU 벽시계다. 한 블록에 담으면 그 선언이 절반에 대해 거짓이 되고, 블록만 떼어
    읽는 소비자는 두 물리량을 같은 표에서 더하게 된다.

    🔴 **아래 값들을 숫자와 **함께** 싣는다.** 없으면 F 숫자를 잘못 읽는다:
      - `n`          : 표본 수 = 추론 횟수. **프레임 수가 아니다**
      - `cadence_ms` : 실측 실행 주기(선언된 N이 아니라 관측값)
      - `duty_cycle` : 탐지가 분석 창의 몇 %를 점유했나
      - `ep`         : 요청/해소된 실행 공급자. F는 EP가 바뀌면 다른 숫자다
    """
    wall_sum_ms = sum(dseries.detect_wall_ms)
    window_ms = (analysis_window_sec or 0.0) * 1000.0
    duty_cycle = (
        round(wall_sum_ms / window_ms, 4)
        if dseries.detect_wall_ms and window_ms > 0 else None
    )
    model_sha, _ = session_field(session, DETECT_MODEL_SHA_PATH)
    period_n, period_present = session_field(session, DETECT_PERIOD_N_PATH)
    padding, _ = session_field(session, DETECT_PADDING_FRACTION_PATH)
    enabled, enabled_present = session_field(session, DETECT_ENABLED_PATH)

    block = {
        # 🔴 이 문장이 이 블록의 계약이다. 떼어 읽는 소비자가 처음 보는 줄이어야 한다.
        "clock": (
            "CPU 벽시계(SystemClock.elapsedRealtimeNanos) 구간 길이. "
            "**GPU 열(stage_b/d/i_ms·gpu_present_ms·gpu_frame_ms)·프레임타임과 더하지 않는다** "
            "— 물리량이 다르다. gpu_sum_ms·stage_d_total_ms에도 들어가지 않는다"
        ),
        "columns_present": list(dseries.detect_columns_present),
        "budget_cell": dict(DETECT_BUDGET_CELL_OF),
        # ── 표본 수. **프레임 수와 다르다는 사실이 드러나야 한다** ──────────
        "n": dseries.rows_used,
        "frames_rows_used": frames_rows_used,
        "n_note": (
            f"n={dseries.rows_used}은 **추론 횟수**이고 프레임 수가 아니다"
            f"(같은 분석 창의 프레임 행은 {frames_rows_used}개). detect.csv의 행 단위는 "
            f"'추론 1회'이며 detect_idx는 frames.csv의 frame_idx와 조인할 수 없다 — "
            f"두 파일을 잇는 것은 시각이다"
        ),
        # ── 실측 실행 주기 ────────────────────────────────────────────────
        "cadence_note": (
            f"{DETECT_CADENCE_SERIES}는 인접한 두 추론의 t_detect_recv_ns 차 = **실측 실행 "
            f"주기**다. INTERFACES.md의 탐지 주기 N은 아직 미정(☐)이라 하네스가 값을 "
            f"지어내지 않고 관측으로 말한다. ⚠ 분모가 프레임이 아니라 추론이므로 "
            f"recv_interval_ms와 더하거나 빼지 않는다(모집단이 다르다)"
        ),
        # ── 분석 창과 점유율 ──────────────────────────────────────────────
        "analysis_window_sec": (
            round(analysis_window_sec, 3) if analysis_window_sec is not None else None
        ),
        "analysis_window_source": (
            "frames.csv의 t_recv_ns **실제 span**(warmup 제외 후 첫 행 ~ 마지막 행). "
            "p50 × n으로 유도하지 않았다 — 그 유도는 드롭·폐기만큼 창을 짧게 만들고, "
            "짧아진 창이 duty_cycle의 분모가 되면 점유율이 실제보다 커진다"
        ),
        "duty_cycle": duty_cycle,
        "duty_cycle_note": (
            f"Σ({DETECT_WALL_SERIES}) / 분석 창 = 탐지가 시간의 몇 %를 점유했나"
            f"(0.25 = 25%). Σ={round(wall_sum_ms, 1)}ms. "
            f"⚠ **프레임타임에서 이만큼을 빼거나 더할 수 없다** — 탐지는 별 스레드에서 돌고, "
            f"점유율이 100%를 넘지 않는다고 해서 렌더가 굶지 않았다는 뜻도 아니다"
            if duty_cycle is not None else
            f"Σ({DETECT_WALL_SERIES})를 낼 수 없어 계산하지 않았다 "
            f"(t_detect_end_ns 열이 없거나 분석 창을 재지 못했다)"
        ),
        "detect_wall_note": (
            f"{DETECT_WALL_SERIES} = t_detect_end_ns − t_detect_recv_ns. **E+F+G의 합이 "
            f"아니다** — 그 셋 바깥의 비용(프레임 대기 해제·텐서 복사·콜백 디스패치)이 함께 "
            f"들어가므로 span − (E+F+G) >= 0인 미계상분이 있다. **버짓 칸도 없다**"
            f"(budget_cell=null)"
        ),
        # ── 누적 카운터. **백분위를 내지 않는다** ─────────────────────────
        "skipped_while_busy_total": dseries.skipped_while_busy_total,
        "skipped_while_busy_rows": dseries.skipped_while_busy_rows,
        "skipped_while_busy_note": (
            "이 값은 **그 시점까지의 누적**이라 마지막으로 관측한 값 하나만 싣는다"
            "(단조 증가 수열의 백분위는 뜻이 없다). rows=0이면 total=0은 '건너뛴 프레임이 "
            "없다'가 아니라 **재지 않았다**는 뜻이다"
        ),
        # ── 그 런의 조건. 숫자와 같은 블록에 있어야 한다 ──────────────────
        "render_arm": render_arm,
        "render_arm_known": render_arm_known,
        "model_sha256": model_sha,
        "ep_requested": ep.get("requested"),
        "ep_resolved": ep.get("resolved"),
        "ep_matches": ep.get("matches"),
        "ep_vocab_ok": ep.get("vocab_ok"),
        "ep_note": (
            "F(추론)는 **EP가 바뀌면 다른 숫자다.** ep_matches=false면 요청한 EP로 세션이 "
            "열리지 않은 것이므로 아래 F를 요청 EP의 비용으로 인용하면 틀린다. "
            "ep_matches=null이면 대조할 수 없었다는 뜻이지 '같다'가 아니다. "
            "⚠ 하네스는 EP를 해석하지 않는다 — 앱의 자진 신고 두 개를 대조했을 뿐이다"
        ),
        "declared_enabled": enabled,
        "declared_enabled_present": enabled_present,
        "period_n": period_n,
        "period_n_note": (
            "앱이 선언한 탐지 주기. **null이 정상이다**(INTERFACES.md에서 아직 ☐ 미정이라 "
            f"앱이 값을 지어내지 않는다) — 실제 주기는 위 {DETECT_CADENCE_SERIES} 분포로 말한다"
            + ("" if period_present else ". 이 로그에는 키 자체가 없다")
        ),
        "padding_pixel_fraction": padding,
        "padding_note": (
            "letterbox 패딩이 입력 텐서에서 차지하는 픽셀 비율. **F의 일부는 회색 패딩을 "
            "미는 비용이다** — 720p를 640 정사각에 넣으면 세로 패딩만큼 연산이 는다"
            "(models/det_c4b_loli0_640/README.md: 고정 shape을 택한 대가). 이 값 없이 "
            "F를 다른 입력 크기의 F와 비교하면 안 된다"
        ),
        # ── 입력 완전성 (frames.csv 쪽 source 블록과 같은 규약) ───────────
        "rows_read": dseries.rows_read,
        "rows_skipped": dseries.rows_skipped,
        "rows_skipped_anomalous": dseries.rows_skipped_anomalous,
        "rows_accounted": dseries.accounting_ok,
        "discarded_samples": dseries.discarded,
        "discarded_total": dseries.discarded_total,
        "unknown_columns": dseries.unknown_columns,
        "has_detect_timings": dseries.has_detect_timings,
        # t0를 frames.csv와 공유했는가 — 두 파일의 분석 창이 같다는 근거.
        "t0_ns": dseries.t0_ns,
        "t0_note": (
            "이 t0는 **frames.csv의 첫 행**에서 왔다(detect.csv 자기 첫 행이 아니다). "
            "warmup도 같은 값을 썼으므로 두 파일의 분석 창은 같다 — 호출자 책임이며 "
            "analyze_frames가 한 스크립트인 이유가 이것이다"
        ),
    }
    block.update(dstats)
    return block


def main() -> int:
    parser = common_argparser()
    parser.add_argument("--frames", required=True, help="프레임 로그 CSV 경로")
    parser.add_argument("--session", default="", help="session.json 경로 (없으면 생략)")
    parser.add_argument(
        "--detect",
        default="",
        help=(
            "detect.csv 경로 (③ 탐지 계측. 없으면 생략). **frames.csv와 같은 스크립트에서 "
            "읽는 이유는 t0 공유다** — read_frames가 낸 t0를 그대로 넘겨야 두 파일의 분석 "
            "창이 같아진다. 별 스크립트로 쪼개면 그 연결이 끊어진다"
        ),
    )
    parser.add_argument(
        "--warmup_sec",
        type=float,
        default=targets.DEFAULT_WARMUP_SEC,
        help=f"첫 N초 제외 (기본 {targets.DEFAULT_WARMUP_SEC}s — AE/AWB 수렴 전 프레임은 튄다)",
    )
    parser.add_argument("--adb", default="", help="adb 실행 파일 경로 (자동 탐색 실패 시)")
    parser.add_argument("--serial", default="", help="대상 기기 serial")
    parser.add_argument(
        "--no_device",
        action="store_true",
        help="adb 기기 메타 수집을 건너뛴다",
    )
    parser.add_argument("--label", default="", help="이 측정에 붙일 메모 (예: 'poc 빈 파이프라인')")
    args = parser.parse_args()

    paths = init_run(stage="poc_baseline", script_file=__file__, args=args)

    frames_path = Path(args.frames)
    try:
        series = read_frames(frames_path, warmup_sec=args.warmup_sec)
    except FrameLogError as exc:
        LOG.error("프레임 로그를 읽지 못했다: %s", exc)
        return 2

    session = read_session(Path(args.session)) if args.session else {}

    # ── ③ 탐지 (v6). **t0와 warmup을 frames.csv 쪽과 같은 값으로 넘긴다.**
    #    🔴 `--detect`를 줬는데 읽지 못하면 **죽는다.** 사람이 명시적으로 지목한 파일을
    #       조용히 건너뛰면 "탐지를 쟀는데 탐지 블록이 없는 요약"이 나오고, 그 요약은
    #       "탐지를 안 잰 런"과 구분되지 않는다(이 하네스가 계속 닫아 온 실패 양식).
    dseries = None
    if args.detect:
        try:
            dseries = read_detect(
                Path(args.detect), t0_ns=series.t0_ns, warmup_sec=args.warmup_sec
            )
        except FrameLogError as exc:
            LOG.error("탐지 로그를 읽지 못했다: %s", exc)
            return 2

    dev = {"available": False, "reason": "--no_device 로 건너뜀"}
    if not args.no_device:
        dev = device_meta.collect(adb_path_hint=args.adb, serial=args.serial)
    LOG.info("기기: %s", device_meta.describe(dev))

    # 파이프라인이 실제로 프레임을 뱉는 간격이 "프레임타임"(FRAME_BUDGET.md §2).
    # 출력 타임라인이 없으면(빈 PoC 초기형) 수신 간격으로 대신하고 그 사실을 명시한다.
    if series.has_output_timeline:
        primary = series.output_interval_ms
        primary_name = "output_interval_ms"
        primary_source = "output_interval_ms (t_render_end_ns 간격)"
    else:
        primary = series.recv_interval_ms
        primary_name = "recv_interval_ms"
        primary_source = "recv_interval_ms (t_recv_ns 간격) — 출력 타임라인 없음"

    primary_stats = summarize(primary)
    mean_ms = primary_stats["mean"]
    p95_ms = primary_stats["p95"]

    # 폐기된 샘플은 그 측정의 최악 프레임일 수 있다 → 판정 옆에 수치로 붙여 낸다.
    discarded_total = series.discarded_total
    # 판정은 primary 시계열에서만 나온다. 거기서 빠진 샘플은 PASS/FAIL을 직접 흔든다.
    primary_discarded = sum(series.discarded.get(primary_name, {}).values())
    # 행 단위 소실. warmup은 의도된 제외라 완전성 판정에서 뺀다 — 그걸로 false가 되면
    # 매 실측에서 항상 false가 되어 플래그가 무의미해진다.
    anomalous_skips = series.rows_skipped_anomalous
    # session.json이 선언한 카메라 기준 시계. 관측과 어긋나면 선언 쪽을 의심해야 한다.
    declared_clock_base = session.get("capture_clock_base", "unknown")
    # capture_to_render는 어느 쪽 가드에 걸리든 원인이 기준 시계 불일치다.
    capture_clock_mismatch = bool(
        sum(series.discarded.get("capture_to_render_ms", {}).values())
    )
    # 조명 조건. 판정(exit code)은 흔들지 않지만, 없거나 unknown이면 그 런은 나중에
    # 아무것과도 정직하게 비교할 수 없으므로 경고로 낸다.
    lighting, lighting_comparable, lighting_warning = check_lighting_condition(session)
    # 단계 어휘. 조명과 같은 취급 — 비교 조건이지 판정선이 아니다.
    stages_declared, stages_vocab_ok, stages_warning = check_pipeline_stages(session)
    # 세션이 선언한 스키마 버전 ↔ 하네스 버전. 다르면 경고만 낸다(옛 로그는 계속 읽힌다).
    schema_declared, schema_matches, schema_warning = check_schema_version(session)
    # 렌더 arm. **단계 비용 숫자의 동반 조건**이라 stages 블록에 함께 싣는다.
    # 경고는 단계 비용 열이 실제로 있을 때만 낸다(아래) — 옮길 숫자가 없는 런까지 매번
    # 경고하면 정작 위험한 런에서 아무도 안 본다.
    render_arm, render_arm_known, render_arm_warning = check_render_arm(session)

    # ── 단계 비용 (GPU 시계). 판정선이 없으므로 verdict를 흔들지 않는다.
    stage_stats = {name: summarize(getattr(series, name)) for name in GPU_TIME_COLUMNS}
    stage_stats["gpu_sum_ms"] = summarize(series.gpu_sum_ms)
    # D 계열의 행별 합 = 그 런의 D칸. arm에 따라 열 구성이 달라도 이 키 하나로 읽힌다.
    stage_stats[STAGE_D_TOTAL_COLUMN] = summarize(series.stage_d_total_ms)
    gpu_valid_total = sum(len(v) for v in series.gpu_series.values())
    # **값이 실제로 나온** 단계 열. 헤더에 있는 것(columns_present)과 다르다 — 전부 -1이면
    # 여기는 비고, 그때는 "단계를 쟀다"고 말할 수 없다.
    measured_stage_columns = [c for c, v in series.gpu_series.items() if v]
    # 단계 선언(빈 배열/키 없음) ↔ 실측 단계 값의 모순. 리스트가 아닌 경우는 여기서 다루지
    # 않는다(그건 nonlist_pipeline_stages_caveat가 이미 "읽지 못했다"고 말한다).
    pipeline_stages_contradicted = bool(
        isinstance(stages_declared, (list, type(None)))
        and not stages_declared
        and series.has_gpu_timings
    )

    # session.json이 선언한 GPU timer 지원 여부. 선언과 실제가 모순되면 선언 쪽이
    # 틀렸을 수 있다 (capture_clock_base_contradicted와 같은 패턴).
    gpu_timer_decl = session.get("gpu_timer") or {}
    if not isinstance(gpu_timer_decl, dict):
        gpu_timer_decl = {}
    gpu_timer_declared = bool(gpu_timer_decl.get("supported"))
    gpu_timer_contradicted = gpu_timer_declared and gpu_valid_total == 0

    # 링이 가득 차 계측을 건너뛴 프레임 수. bool은 int의 하위형이라 먼저 배제한다
    # (true가 1로 계상되면 "1프레임 빠졌다"는 거짓 사실이 된다).
    ring_full_raw = gpu_timer_decl.get("skipped_ring_full_frames")
    ring_full = (
        ring_full_raw
        if isinstance(ring_full_raw, int) and not isinstance(ring_full_raw, bool)
        else None
    )

    # ── ③ 탐지 비용 (CPU 벽시계). 판정선이 없으므로 verdict를 흔들지 않는다.
    #    stages와 **다른 블록**이다 — 시계가 다르다(_detect_block 주석).
    detect_block = None
    detect_ep = check_detect_ep(session)
    if dseries is not None:
        detect_stats = {
            name: summarize(getattr(dseries, name))
            for name in tuple(DETECT_TIME_COLUMNS)
            + tuple(DETECT_DISTRIBUTION_COUNT_COLUMNS)
            + tuple(DETECT_SCORE_COLUMNS)
        }
        detect_stats[DETECT_WALL_SERIES] = summarize(dseries.detect_wall_ms)
        detect_stats[DETECT_CADENCE_SERIES] = summarize(dseries.detect_cadence_ms)
        detect_block = _detect_block(
            dseries,
            detect_stats,
            session,
            frames_rows_used=series.rows_used,
            analysis_window_sec=series.analysis_window_sec,
            render_arm=render_arm,
            render_arm_known=render_arm_known,
            ep=detect_ep,
        )

    summary = {
        # ⚠ 이건 **하네스** 버전이다. 이 로그가 어느 스키마로 쓰였는지는
        #   source.schema_version_declared 쪽이며, 둘은 다를 수 있다.
        "schema_version": SCHEMA_VERSION,
        "run_ts": paths.run_ts,
        "label": args.label,
        "source": {
            "frames_csv": str(frames_path.resolve()),
            "session_json": str(Path(args.session).resolve()) if args.session else None,
            "warmup_sec": args.warmup_sec,
            "rows_read": series.rows_read,
            "rows_used": series.rows_used,
            "dropped_total": series.dropped_total,
            # 행 단위 소실. rows_read == rows_used + rows_skipped_total 이 성립해야 한다
            # (read_frames가 깨지면 FrameLogError로 죽는다). warmup만 의도된 제외다.
            "rows_skipped": series.rows_skipped,
            "rows_skipped_total": series.rows_skipped_total,
            "rows_skipped_anomalous": anomalous_skips,
            "rows_accounted": series.accounting_ok,
            # 가드에 걸려 분포에서 빠진 샘플. rows_used와 count를 사람이 대조하지 않아도
            # 알 수 있도록 시계열·사유별로 남긴다.
            "discarded_samples": series.discarded,
            "discarded_total": discarded_total,
            "capture_clock_base_declared": declared_clock_base,
            "capture_clock_base_contradicted": capture_clock_mismatch,
            # GPU timer 선언 ↔ 실제 표본. 선언만 믿고 "단계 비용을 쟀다"고 하면 안 된다.
            "gpu_timer_supported_declared": gpu_timer_declared,
            "gpu_timer_contradicted": gpu_timer_contradicted,
            # 조명 조건. baseline_diff는 session 블록 쪽(session.lighting_condition)을 보고
            # 비교 가능성을 판정한다. 여기 둘은 사람이 읽는 사본이 아니라 **검사 결과**다.
            "lighting_condition": lighting,
            "lighting_condition_comparable": lighting_comparable,
            # 단계 어휘 검사. 어휘 밖 토큰은 비교를 끊으므로 사실을 남긴다(판정은 그대로).
            "pipeline_stages_vocab_ok": stages_vocab_ok,
            # 선언은 "처리 없음"인데 GPU 단계 값이 실제로 있는가.
            # gpu_timer_contradicted / capture_clock_base_contradicted와 같은 패턴이다.
            # ⚠ 판정선이 아니다 — verdict·exit code를 바꾸지 않는다.
            "pipeline_stages_contradicted": pipeline_stages_contradicted,
            "pipeline_stages_measured_columns": measured_stage_columns,
            "pipeline_stages_unknown_tokens": (
                [] if stages_vocab_ok or not isinstance(stages_declared, list)
                else [s for s in stages_declared if s not in PIPELINE_STAGES]
            ),
            # 세션이 **선언한** 스키마 버전과 하네스 버전을 둘 다 남긴다. 하네스 값만
            # 찍으면 v1 세션이 v2로 라벨된 요약에 실려 나가고 되물을 근거가 사라진다.
            "schema_version_declared": schema_declared,
            "schema_version_harness": SCHEMA_VERSION,
            "schema_version_matches_harness": schema_matches,
            # CSV 헤더에 있었지만 집계에 쓰이지 않은 열. 비어 있지 않으면 오타를 의심한다.
            "unknown_columns": series.unknown_columns,
            # 열 사이 물리 관계로 본 시계 혼용 교차검사 (t_capture_ns 문제와 별개)
            "clock_check": series.clock_check,
        },
        "device": dev,
        "session": session,
        "frametime": {
            "primary_source": primary_source,
            "primary": primary_stats,
            "recv_interval_ms": summarize(series.recv_interval_ms),
            "output_interval_ms": summarize(series.output_interval_ms),
            "render_latency_ms": summarize(series.render_latency_ms),
            "recv_to_render_ms": summarize(series.recv_to_render_ms),
            "capture_to_render_ms": summarize(series.capture_to_render_ms),
        },
        # 단계 비용은 frametime과 **다른 물리량이자 다른 시계**라 블록을 나눈다.
        # 판정선이 없다 — verdict.meets_*는 여기 값을 보지 않는다.
        "stages": _stages_block(
            series, stage_stats, render_arm, render_arm_known, ring_full
        ),
        # ③ 탐지. **stages와 다른 시계다**(CPU 벽시계) — 키를 나눈 이유가 그것이다.
        # --detect를 주지 않았으면 null. "탐지가 0이었다"가 아니라 **재지 않았다**는 뜻이다.
        "detect": detect_block,
        "targets": {
            "target_fps": targets.TARGET_FPS,
            "frame_budget_ms": round(targets.FRAME_BUDGET_MS, 1),
            "p95_budget_ms": targets.P95_BUDGET_MS,
        },
        "verdict": {
            "fps_mean": round(1000.0 / mean_ms, 2) if mean_ms else None,
            "meets_fps_target": targets.meets_fps_target(mean_ms) if mean_ms else False,
            "meets_p95_target": targets.meets_p95_target(p95_ms) if p95_ms else False,
            # 판정이 어떤 입력 위에서 나온 것인지. 폐기가 있으면 PASS도 낙관 편향이다.
            "samples_discarded": discarded_total,
            # 판정에 직접 쓰인 시계열에서 빠진 개수 — 위 두 불리언의 신뢰도가 여기 달렸다.
            "primary_samples_discarded": primary_discarded,
            # 행 통째로 사라진 개수(warmup 제외). 값 폐기와 원인이 달라 따로 낸다.
            "rows_skipped_anomalous": anomalous_skips,
            "data_complete": discarded_total == 0 and anomalous_skips == 0,
            # 시계 혼용은 "값이 빠진 것"이 아니라 "값의 의미가 틀린 것"이라 별도 불리언이다.
            "clock_consistent": series.clock_consistent,
        },
        "warnings": list(series.warnings),
        # 성능만 보고하는 것은 불완전한 보고다 (nightwalk-conventions §6).
        "safety_regression": safety_regression_block(detect_block),
    }

    # ⚠ `list(pipeline_stages)`를 그냥 씌우지 않는다. int면 TypeError로 **집계 전체가 죽어
    #   summary.json이 안 남고**(어휘·타입 검사는 판정선이 아니므로 exit code를 흔들면 안 된다),
    #   문자열은 글자로 dict는 키로 쪼개져 "단계" 자리에 엉뚱한 것이 출력된다.
    #   리스트가 아닌 값은 check_pipeline_stages가 이미 경고했다 — 여기서는 죽지 않고,
    #   단계를 전제하는 두 단서(빈 파이프라인 / 묶임)를 **내지 않는 것**으로 처리한다.
    pipeline_stages = stages_declared if isinstance(stages_declared, list) else None
    # ⚠ **관측은 단계 선언과 무관한 사실이므로 항상 기록한다.** 예전에는 문장을 내지 않는
    #   분기에서 관측치 키까지 함께 빠져서, 기계 소비자가 "이 런의 프레임타임이 공급에
    #   묶여 있었나"를 나중에 되물을 근거가 아예 없었다. 문장을 낼지 말지는 분기가 정하되,
    #   관측치는 여기서 한 번 남긴다.
    pinned = _frametime_pinned(summary["frametime"])
    if pinned is not None:
        summary["frametime"]["pinned_to_camera_supply"] = pinned
    if stages_declared is not None and pipeline_stages is None:
        summary["warnings"].append(nonlist_pipeline_stages_caveat(stages_declared))
    elif not pipeline_stages:
        if pipeline_stages_contradicted:
            # 선언은 "처리 없음"인데 GPU 단계 값이 실제로 있다. "여기서부터 ①②③④ 비용이
            # 더해진다"는 이 런에서 거짓이므로 그 단서 대신 모순을 낸다.
            summary["warnings"].append(
                empty_pipeline_contradiction_caveat(
                    measured_stage_columns, stages_declared
                )
            )
            # 묶임 단서는 이 상황에서 **오히려 맞는 단서**다(단계가 실제로 붙어 있는데
            # 프레임타임이 안 변한 경우). 선언이 비었다는 이유로 끄지 않는다. 다만 근거는
            # 선언 리스트가 아니라 값이 나온 열이므로 라벨을 바꿔 낸다.
            if pinned is not None:
                summary["warnings"].append(
                    frametime_pinned_caveat(
                        pinned,
                        measured_stage_columns,
                        _camera_fps(session),
                        stages_label="측정된 GPU 단계 열",
                    )
                )
        else:
            summary["warnings"].append(
                empty_pipeline_caveat(_camera_fps(session))
            )
    else:
        # 단계가 붙었다고 empty_pipeline_caveat만 끄면 안 된다. 프레임타임이 공급에
        # 묶여 있는 한 그 단서의 주장은 그대로 참이므로, 조건을 관측으로 바꿔 다시 낸다.
        if pinned is not None:
            summary["warnings"].append(
                frametime_pinned_caveat(
                    pinned, list(pipeline_stages), _camera_fps(session)
                )
            )
    # 제출 시각 기반 지연은 GPU 실행 시간을 담지 않는다. 값이 있을 때만 말한다.
    if summary["frametime"]["capture_to_render_ms"]["count"]:
        summary["warnings"].append(submit_time_caveat())
    if gpu_timer_contradicted:
        summary["warnings"].append(
            gpu_timer_contradiction_warning(gpu_timer_decl, series.gpu_columns_present)
        )
    # arm 미상 경고는 **옮길 숫자가 있을 때만.** GPU 열이 하나도 없는 로그(v1·패스스루)는
    # 버짓 칸에 전사될 단계 비용 자체가 없으므로 경고 대상이 아니다 — 그런 런에도 매번
    # 뜨면 곧 아무도 안 보게 되고, 정작 위험한 런에서 묻힌다. 그 경우에도 사실 자체는
    # stages.render_arm(=null)과 리포트 줄에 그대로 드러난다.
    if render_arm_warning and series.gpu_columns_present:
        summary["warnings"].append(render_arm_warning)
    if ring_full:
        summary["warnings"].append(ring_full_bias_warning(ring_full, gpu_timer_decl))
    if lighting_warning:
        # 판정은 바꾸지 않는다. 조명은 판정선이 아니라 **비교 조건**이다.
        summary["warnings"].append(lighting_warning)
    if stages_warning:
        # 조명과 같은 취급 — 단계 어휘도 판정선이 아니라 비교 조건이다.
        summary["warnings"].append(stages_warning)
    if schema_warning:
        # 판정·종료 코드는 바꾸지 않는다. 옛 로그는 계속 읽혀야 한다(하위호환).
        summary["warnings"].append(schema_warning)
    # ③ 탐지 경고. read_detect가 만든 것 + EP 대조.
    # ⚠ EP 경고는 **detect.csv를 읽은 런에서만** 낸다. 탐지가 없는 런(승격본 45건 포함)에서
    #   "EP를 대조할 수 없다"가 매번 뜨면 그 경고는 곧 아무도 안 보고, 정작 탐지 런에서
    #   묻힌다. 어긋남을 크게 내는 것은 run_session의 계획 대조가 담당한다.
    if dseries is not None:
        summary["warnings"].extend(dseries.warnings)
        summary["warnings"].extend(detect_ep["warnings"])
    if capture_clock_mismatch and declared_clock_base not in ("", "unknown", None):
        summary["warnings"].append(
            f"session.json은 capture_clock_base='{declared_clock_base}'라고 선언했지만 "
            f"t_capture_ns가 우리 시계와 맞지 않는다 — 선언 쪽이 틀렸을 수 있다"
        )

    _print_report(summary)

    if paths.outputs_enabled:
        out_path = paths.out_dir / "summary.json"
        with out_path.open("w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2, ensure_ascii=False, sort_keys=True)
            f.write("\n")
        LOG.info("summary 저장: %s", out_path)
    else:
        LOG.info("outputs 비활성 — summary를 파일로 남기지 않았다")

    return 0


def _frametime_pinned(ft: dict) -> dict | None:
    """출력 주기 p50이 수신 주기 p50과 사실상 같으면 그 관측치를 돌려준다.

    같다 = 파이프라인이 카메라가 주는 대로 뱉고 있다 = 프레임타임이 단계 비용을 반영하지
    않는다. 두 시계열 중 하나라도 표본이 없으면 판단하지 않는다(None).

    p95도 같은 임계로 함께 본다. p50만 묶이고 p95가 벌어지는 모양(간헐적 무거운 단계)을
    "전부 묶였다"로 뭉뚱그리면 단서 자체가 거짓이 된다.
    """
    out = ft.get("output_interval_ms") or {}
    recv = ft.get("recv_interval_ms") or {}
    out_p50, recv_p50 = out.get("p50"), recv.get("p50")
    if not out_p50 or not recv_p50:
        return None
    rel_diff = abs(out_p50 - recv_p50) / recv_p50
    if rel_diff > FRAMETIME_PINNED_REL_DIFF:
        return None
    out_p95, recv_p95 = out.get("p95"), recv.get("p95")
    rel_diff_p95 = (
        abs(out_p95 - recv_p95) / recv_p95 if out_p95 and recv_p95 else None
    )
    return {
        "out_p50": out_p50,
        "recv_p50": recv_p50,
        "rel_diff_p50": rel_diff,
        "out_p95": out_p95,
        "recv_p95": recv_p95,
        "rel_diff_p95": rel_diff_p95,
        "tail_pinned": (
            rel_diff_p95 is not None and rel_diff_p95 <= FRAMETIME_PINNED_REL_DIFF
        ),
    }


def _camera_fps(session: dict) -> float | None:
    raw = (session.get("camera") or {}).get("requested_fps")
    try:
        return float(raw) if raw is not None else None
    except (TypeError, ValueError):
        return None


def _stage_series_order() -> list[str]:
    """리포트에 찍을 순서. **파생 시계열을 자기 구성 요소 바로 뒤에 둔다.**

    `stage_d_total_ms`를 맨 끝(gpu_sum_ms 옆)에 두면 "D 계열의 합"이 "전체 GPU 합"과
    나란히 서서 둘을 혼동하기 쉽다. 하위 패스 줄 바로 아래에 두면 무엇을 더한 값인지가
    화면 배치로 드러난다.
    """
    order = list(GPU_TIME_COLUMNS)
    # D 계열이 GPU_TIME_COLUMNS에서 전부 빠지면 max()가 빈 시퀀스 ValueError를 내고
    # **집계 전체가 죽는다.** 오늘은 불가능하지만(둘 다 상수), 리포트 배치 문제로 그날
    # 측정을 통째로 잃을 이유가 없다 — 없으면 맨 끝에 붙인다.
    d_positions = [i for i, n in enumerate(order) if n in STAGE_D_FAMILY_COLUMNS]
    order.insert(d_positions[-1] + 1 if d_positions else len(order), STAGE_D_TOTAL_COLUMN)
    order.append("gpu_sum_ms")
    return order


def _print_stages(summary: dict) -> None:
    """단계 비용(GPU 시계) 출력. **판정선이 없으므로 PASS/FAIL을 찍지 않는다.**"""
    st = summary.get("stages") or {}
    cols = st.get("columns_present") or []
    d_cols = st.get("stage_d_columns") or []
    # gpu_sum_ms가 **있을 수 있는 런인가.** cols와 다르다: 프레임 단일 query 런(v5)은
    # GPU 열이 있어도 패스별 열이 하나도 없으므로 gpu_sum_ms가 애초에 나올 수 없다.
    # cols로 판단하면 그런 런마다 "gpu_sum_ms 유효 표본 0개 — 재지 못한 것이다"라는
    # **거짓 경고**가 뜬다(재지 못한 게 아니라 그 계측을 하지 않은 것이다).
    sum_cols = st.get("gpu_sum_columns") or []
    arm_long, arm_short = _arm_long(st), _arm_short(st)
    if not cols:
        LOG.info(
            "단계 비용(GPU timer): 열 없음 — 이 로그로는 단계 비용을 말할 수 없다 [%s]",
            arm_long,
        )
        return
    # arm을 절 제목과 각 줄에 **둘 다** 낸다. 제목만 붙이면 한 줄만 복사해 옮기는 순간
    # 조건이 떨어져 나가고, 그 줄이 곧 버짓표의 한 칸이 된다.
    LOG.info("단계 비용 — GPU 시계 [%s] (프레임타임과 다른 시계, 판정선 없음)", arm_long)
    if st.get("render_arm_known"):
        LOG.info(
            "  ⚠ [칸] 라벨은 '이 열이 어느 칸을 채울 열인가'라는 스키마 사실이다 — "
            "이 arm이 그 단계를 실제로 돌렸다는 뜻이 아니다. 숫자를 arm과 떼어 옮기지 말 것"
        )
    else:
        LOG.warning(
            "  ⚠ %s — 아래 숫자가 어느 렌더 경로의 것인지 알 수 없다. "
            "[칸] 라벨은 열↔칸 스키마 매핑일 뿐이므로 이 값을 버짓 칸에 옮기지 말 것",
            arm_long,
        )
    # D 계열이 **어떻게 구성됐는지**를 숫자 앞에 먼저 낸다. stage_d_total_ms만 보고 D를
    # 인용할 사람과 내부 분해를 보고 레버를 고를 사람이 둘 다 있으므로, 어느 열들을 더해
    # D가 나왔는지가 리포트에서 사라지면 안 된다.
    if d_cols:
        LOG.info(
            "  D칸 구성: %s = %s (행별 합 — 백분위의 합이 아니다)",
            " + ".join(d_cols), STAGE_D_TOTAL_COLUMN,
        )
        if st.get("stage_d_ambiguous"):
            LOG.warning(
                "  ⚠ D 계열 모호 — stage_d_ms가 '합계'인지 '또 하나의 하위 패스'인지 "
                "이 로그로는 알 수 없다. 하네스는 **하위 패스로 해석**해 그대로 더했다 "
                "(아래 경고 참고)"
            )
    # 프레임 단일 query(v5)가 있는 런에서는 그 열이 무엇인지를 숫자 **앞에** 낸다.
    # 줄만 보면 gpu_sum_ms와 같은 자리의 값처럼 읽히는데, 물리량도 계측 방식도 다르다.
    if st.get("gpu_frame_present"):
        LOG.info(
            "  %s: 프레임 하나를 query 하나로 감싼 값 — gpu_sum_ms(패스별 합)에 더하지 "
            "않았고 %s에도 넣지 않았으며 버짓 칸도 없다. 마지막 타일 해결이 "
            "eglSwapBuffers에서 일어나 이 값도 하한이다",
            GPU_FRAME_COLUMN, STAGE_D_TOTAL_COLUMN,
        )
        if st.get("gpu_frame_conflict"):
            LOG.warning(
                "  ⚠ 계측 방식 혼재 — %s와 패스별 열이 한 런에 동시에 있다. "
                "GL_TIME_ELAPSED는 중첩되지 않으므로 둘 중 하나는 못 믿는다 (아래 경고 참고)",
                GPU_FRAME_COLUMN,
            )
    for name in _stage_series_order():
        s = st.get(name) or {}
        if not s:
            continue
        cell = BUDGET_CELL_OF.get(name)
        label = f"{name}[{cell}칸]" if cell else name
        # D칸의 대표값과 그 구성 요소를 눈으로 갈라 놓는다. 들여쓰기가 없으면 하위 패스
        # 한 줄만 복사해 D칸에 옮기는 일이 생긴다(전체가 아니라 일부인데).
        if name in STAGE_D_FAMILY_COLUMNS and len(d_cols) > 1:
            label = "· " + label
        if s.get("count"):
            LOG.info(
                "  %-26s p50=%-8s p95=%-8s p99=%-8s min=%-8s max=%-8s (n=%s, %s)",
                label, s["p50"], s["p95"], s["p99"], s["min"], s["max"],
                s["count"], arm_short,
            )
        elif (
            name in cols
            or (name == "gpu_sum_ms" and sum_cols)
            or (name == STAGE_D_TOTAL_COLUMN and d_cols)
        ):
            LOG.warning(
                "  %-26s 유효 표본 0개 — '0ms'가 아니라 재지 못한 것이다 (%s)",
                label, arm_short,
            )


def _detect_series_order() -> list[str]:
    """리포트에 찍을 순서. 파생 시계열을 재료 뒤에, 조건 열(카운트·점수)을 맨 뒤에 둔다."""
    return (
        list(DETECT_TIME_COLUMNS)
        + [DETECT_WALL_SERIES, DETECT_CADENCE_SERIES]
        + list(DETECT_DISTRIBUTION_COUNT_COLUMNS)
        + list(DETECT_SCORE_COLUMNS)
    )


def _print_detect(summary: dict) -> None:
    """③ 탐지 비용 출력. **판정선이 없으므로 PASS/FAIL을 찍지 않는다.**

    🔴 단계 비용(GPU) 표와 **줄을 섞지 않는다.** 절 제목에 시계를 적고, 숫자 줄마다 arm과
    EP를 붙인다 — 한 줄만 복사해 옮겨도 그 숫자가 어느 시계·어느 EP의 것인지 남아야 한다.
    """
    dt = summary.get("detect")
    if not dt:
        return
    arm = dt.get("render_arm")
    arm_short = f"arm={arm}" if dt.get("render_arm_known") else f"arm={arm!r}(어휘 밖/미상)"
    ep_short = f"EP={dt.get('ep_resolved')}"
    LOG.info(
        "③ 탐지 비용 — CPU 벽시계 [arm=%s, 요청 EP=%s → 해소 EP=%s] "
        "(GPU 열·프레임타임과 다른 시계, 판정선 없음)",
        arm, dt.get("ep_requested"), dt.get("ep_resolved"),
    )
    if dt.get("ep_matches") is False:
        LOG.error("  🔴 EP 어긋남 — 아래 F는 요청한 EP의 비용이 아니다 (아래 경고 참고)")
    elif dt.get("ep_matches") is None:
        LOG.warning(
            "  ⚠ EP를 대조할 수 없다 — 아래 F가 어느 실행 공급자의 값인지 말할 수 없다"
        )
    # 🔴 표본 수와 프레임 수를 **숫자 앞에** 낸다. 둘이 같다고 착각하면 duty cycle도
    #    cadence도 전부 잘못 읽힌다.
    LOG.info(
        "  표본 n=%s (추론 횟수 — 같은 창의 프레임 행은 %s개). 분석 창 %s초 "
        "(frames.csv t_recv_ns의 실제 span)",
        dt.get("n"), dt.get("frames_rows_used"), dt.get("analysis_window_sec"),
    )
    duty = dt.get("duty_cycle")
    if duty is not None:
        LOG.info(
            "  duty_cycle=%.1f%% — 탐지가 분석 창의 이만큼을 점유했다 (Σ%s / 창). "
            "프레임타임에서 빼거나 더할 수 없다",
            duty * 100.0, DETECT_WALL_SERIES,
        )
    else:
        LOG.warning("  duty_cycle: 낼 수 없다 — %s", dt.get("duty_cycle_note"))
    for name in _detect_series_order():
        s = dt.get(name) or {}
        if not s:
            continue
        cell = DETECT_BUDGET_CELL_OF.get(name)
        label = f"{name}[{cell}칸]" if cell else name
        if s.get("count"):
            LOG.info(
                "  %-26s p50=%-8s p95=%-8s p99=%-8s min=%-8s max=%-8s (n=%s, %s, %s)",
                label, s["p50"], s["p95"], s["p99"], s["min"], s["max"],
                s["count"], arm_short, ep_short,
            )
        elif name in (dt.get("columns_present") or []):
            LOG.warning(
                "  %-26s 유효 표본 0개 — '0ms'가 아니라 재지 못한 것이다 (%s)",
                label, arm_short,
            )
    # 누적 카운터는 분포가 아니므로 줄을 따로 낸다(백분위 표에 섞으면 그 표가 거짓이 된다).
    for col in DETECT_CUMULATIVE_COLUMNS:
        rows = dt.get(f"{col}_rows")
        if rows:
            LOG.info(
                "  %-26s %s (누적값의 마지막 관측치 — 백분위를 내지 않는다, 관측 %s행)",
                col, dt.get(f"{col}_total"), rows,
            )
        elif col in (dt.get("columns_present") or []):
            LOG.warning(
                "  %-26s 열은 있는데 파싱된 행이 0개다 — 0은 '건너뛴 프레임 없음'이 아니라 "
                "**재지 않았다**는 뜻이다", col,
            )
    # 입력 완전성 — frames.csv 쪽과 같은 규약. 이상 소실은 조용히 넘어가지 않는다.
    if dt.get("rows_skipped_anomalous"):
        LOG.warning(
            "  ⚠ 추론 행 이상 소실 %s개 (rows_read=%s → n=%s): %s",
            dt["rows_skipped_anomalous"], dt.get("rows_read"), dt.get("n"),
            {k: v for k, v in (dt.get("rows_skipped") or {}).items()
             if v and k in DETECT_ROW_SKIP_REASON_TEXT},
        )


def _print_report(summary: dict) -> None:
    ft = summary["frametime"]
    v = summary["verdict"]
    src = summary["source"]
    LOG.info("=" * 62)
    LOG.info("프레임 간격 기준: %s", ft["primary_source"])
    p = ft["primary"]
    LOG.info(
        "  n=%s  p50=%s  p95=%s  p99=%s  min=%s  max=%s (ms)",
        p["count"], p["p50"], p["p95"], p["p99"], p["min"], p["max"],
    )
    for name in (
        "recv_interval_ms",
        "output_interval_ms",
        "render_latency_ms",
        "recv_to_render_ms",
        "capture_to_render_ms",
    ):
        s = ft[name]
        if s["count"]:
            LOG.info("  %-22s p50=%-8s p95=%-8s (n=%s)", name, s["p50"], s["p95"], s["count"])
    _print_stages(summary)
    _print_detect(summary)
    LOG.info("-" * 62)
    LOG.info(
        "평균 %.2f FPS | %s: %s | %s: %s",
        v["fps_mean"] if v["fps_mean"] else 0.0,
        targets.fps_target_label(),
        "PASS" if v["meets_fps_target"] else "FAIL",
        targets.p95_target_label(),
        "PASS" if v["meets_p95_target"] else "FAIL",
    )
    # 판정 옆에 반드시 붙는다. 폐기된 샘플이 최악 프레임이면 위 PASS는 낙관 편향이다.
    skipped = src["rows_skipped"]
    warmup_n = skipped.get("warmup", 0)
    if v["data_complete"]:
        LOG.info(
            "입력 완전성: 소실 없음 (rows_read=%s = rows_used=%s + warmup 제외 %s, 폐기 샘플 0)",
            src["rows_read"], src["rows_used"], warmup_n,
        )
    else:
        LOG.warning(
            "⚠ 입력 불완전 (rows_read=%s → rows_used=%s): 이상 소실 행 %s개, 폐기 샘플 %s개 "
            "— 위 판정은 남은 것에 대한 것이다",
            src["rows_read"], src["rows_used"],
            v["rows_skipped_anomalous"], v["samples_discarded"],
        )
        for reason in ANOMALOUS_SKIP_REASONS:
            n = skipped.get(reason, 0)
            if n:
                LOG.warning(
                    "    - 행 소실 %s: %s개 (%s)", reason, n, ROW_SKIP_REASON_TEXT[reason]
                )
        if warmup_n:
            LOG.warning("    - (참고) warmup 제외 %s행 — 의도된 제외라 완전성 판정에 넣지 않는다", warmup_n)
        for name, reasons in sorted(src["discarded_samples"].items()):
            LOG.warning("    - 값 폐기 %s: %s", name, reasons)
        if v["primary_samples_discarded"]:
            LOG.warning(
                "    ↳ 그중 %s개는 판정에 쓰인 시계열(%s)에서 빠졌다 — PASS/FAIL이 직접 흔들린다",
                v["primary_samples_discarded"], ft["primary_source"],
            )
    # 시계 혼용은 값이 빠진 게 아니라 값의 의미가 틀린 것이라 줄을 따로 낸다.
    cc = src.get("clock_check") or {}
    if v.get("clock_consistent", True):
        LOG.info(
            "시계 일관성: 이상 없음 (t_render_* ↔ t_recv_ns 교차검사 %s행)",
            (cc.get("render_start_after_recv") or {}).get("checked", 0),
        )
    else:
        LOG.warning(
            "⚠ 시계 일관성 위반 — 어긋난 열: %s",
            ", ".join(cc.get("suspect_columns") or []),
        )
    # 스키마 버전이 맞으면 한 줄로 확인해 준다(어긋난 경우는 아래 경고 루프가 말한다).
    if src.get("schema_version_matches_harness"):
        LOG.info(
            "스키마 버전: 세션 선언 v%s = 하네스 v%s",
            src.get("schema_version_declared"), src.get("schema_version_harness"),
        )
    # 조명 조건이 성하면 한 줄로 확인해 준다(어긋난 경우는 아래 경고 루프가 말한다).
    if src.get("lighting_condition_comparable"):
        LOG.info(
            "조명 조건: %s — baseline_diff에서 같은 조명끼리만 비교된다",
            src.get("lighting_condition"),
        )
    for w in summary["warnings"]:
        LOG.warning("⚠ %s", w)
    LOG.info("=" * 62)


if __name__ == "__main__":
    raise SystemExit(main())
