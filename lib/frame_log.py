"""프레임 로그 스키마 — 폰(PoC)이 뱉고 PC(하네스)가 읽는 형식.

규격 전문은 `docs/FRAME_LOG_SCHEMA.md`. 이 모듈은 그 규격의 실행 가능한 정의다.

설계 원칙 3가지:

1. **유도 가능한 값은 저장하지 않는다.** 프레임타임은 타임스탬프 차이로 계산한다.
   폰이 계산한 값과 PC가 계산한 값이 어긋나면 어느 쪽이 맞는지 알 수 없다.
2. **단조 시계 하나로 통일한다.** `t_*_ns`는 전부 같은 시계(`SystemClock.elapsedRealtimeNanos`)여야
   한다. 예외는 카메라가 주는 `t_capture_ns` 하나이며, 그 기준 시계는 기기마다 다르다.
   v2에서 들어온 GPU 패스 시간(`stage_*_ms` / `gpu_present_ms`)은 애초에 **시각이 아니라
   구간 길이**이고 GPU 시계에서 나온다 — `t_*_ns`와 섞지 않는다(GPU_TIME_COLUMNS 주석).
3. **없는 값은 -1.** 빈칸이나 0이 아니라 -1로 명시한다. 0은 "0ms 걸렸다"와 구분되지 않는다.
"""

from __future__ import annotations

import csv
import json
import math
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from lib.stats import percentile

SCHEMA_VERSION = 2

# 폰이 반드시 뱉어야 하는 열
REQUIRED_COLUMNS = ("frame_idx", "t_recv_ns")

# ── GPU 패스 시간 열 (v2 추가) ────────────────────────────────────────────
# 단위는 **ms(float)**이고, 출처는 `GL_EXT_disjoint_timer_query`다.
#
# ⚠ **이 값들은 t_*_ns와 다른 시계에서 온다.** t_*_ns는 CLOCK_BOOTTIME
#   (`SystemClock.elapsedRealtimeNanos`)이고 여기 값들은 GPU 시계다. 두 시계는 서로
#   빼거나 더할 수 없으며, 시계 혼용 교차검사(A/B)의 대상도 **아니다** — 교차검사는
#   "같은 시계라면 반드시 성립해야 하는 관계"를 보는 장치인데 이 열들은 애초에 다른
#   시계라서 위반이 정상이다. 섞으면 범인 열을 엉뚱한 곳에 돌리게 된다.
#
# ⚠ **프레임타임은 이 값들의 미터가 아니다.** 카메라가 30fps로 공급하면 프레임타임은
#   공급 주기에 묶이므로, GPU 비용이 그 아래인 한 패스를 얹어도 프레임타임은 변하지
#   않는다. 프레임타임은 "GPU 비용이 공급 주기를 넘었는가"를 알려주는 **임계 검출기**다.
#   단계 비용 자체는 아래 열로만 말한다.
GPU_TIME_COLUMNS = (
    "stage_b_ms",      # 패스1 OES→오프스크린 720p (버짓 B칸: 색공간 변환/텍스처 업로드)
    "stage_d_ms",      # ② 저조도 개선 패스(들) 합 (버짓 D칸)
    "stage_i_ms",      # ④ 강조 렌더 패스 (버짓 I칸)
    "gpu_present_ms",  # 기본 프레임버퍼에 그린 최종 표시 패스. **버짓 칸이 아니다**
)

# `gpu_sum_ms`(파생 시계열)에 들어가는 열.
# gpu_present_ms를 **포함한다**: 최종 표시 패스도 실제로 GPU를 점유하는 시간이고,
# "이 프레임이 GPU를 몇 ms 잡았나"에서 그것만 빼면 총량이 과소평가된다. 버짓 칸이
# 아니라는 것은 A~J 매핑이 없다는 뜻이지 비용이 아니라는 뜻이 아니다.
# (칸별 비용을 보고 싶으면 stage_* 시계열을 각각 보면 된다.)
GPU_SUM_COLUMNS = GPU_TIME_COLUMNS

# 있으면 쓰고 없으면 건너뛰는 열
OPTIONAL_COLUMNS = (
    "t_capture_ns",       # 카메라 ImageInfo.timestamp — 기준 시계 불명확 (§시계 함정)
    "t_render_start_ns",
    "t_render_end_ns",
    "dropped_since_last",  # 백프레셔로 버려진 프레임 수
) + GPU_TIME_COLUMNS

# 위 두 목록에 없는 열 = 하네스가 읽지 않는 열. 하드 에러로 만들지 않는다(앱이 스키마보다
# 앞서 나갈 수 있다) 대신 **반드시 경고한다.** 행 단위 회계(accounting_ok)에 해당하는
# 방어선이 열 단위에도 있어야 하는 이유:
#   앱이 t_render_end_ns를 t_render_ns로 오타 내면 optional 열이 "그냥 없는 것"으로
#   처리되어 output_interval_ms.count == 0이 되고, 우리는 "출력 타임라인이 없다"고
#   **잘못** 결론 낸다. 오타는 없는 것과 다르다.
KNOWN_COLUMNS = tuple(REQUIRED_COLUMNS) + tuple(OPTIONAL_COLUMNS)

MISSING = -1

# ── 조명 조건 (session.json: lighting_condition) ──────────────────────────
# 야간 앱에서 조명은 취향이 아니라 **공급 fps를 직접 바꾸는 측정 조건**이다. 저조도에서
# 카메라 AE가 노출 시간을 늘리면 t_recv_ns 간격 자체가 벌어지므로, 밝은 방 런과 야간 런을
# 비교하면 코드가 그대로여도 "회귀"로 오판정된다. 그래서 baseline_diff의 CONDITION_KEYS에 든다.
#
# **어휘를 고정하는 이유:** 자유 문자열이면 "밝은방" vs "indoor_bright"로 갈려 모든 비교가
# "조건 다름"이 된다. 이 목록은 `docs/FRAME_LOG_SCHEMA.md` §5와 **같아야 한다.**
# ⚠ 이건 판정선이 아니다 — PASS/FAIL을 흔들지 않는다(lib/targets.py와 섞지 않는다).
LIGHTING_UNKNOWN = "unknown"
LIGHTING_SYNTHETIC = "synthetic"  # 합성 로그 생성기가 박는 값
LIGHTING_CONDITIONS = (
    "indoor_bright",       # 실내 조명 켜짐 — 하네스 배선 점검용. 야간 근거로는 못 쓴다
    "indoor_dim",          # 실내 소등/커튼 — AE가 노출을 늘리기 시작하는 구간
    "outdoor_night_lit",   # 야간 가로등 있는 보도
    "outdoor_night_dark",  # 야간 조명 없는 구간 — 이 앱의 실제 사용 조건
    LIGHTING_SYNTHETIC,    # 합성 로그. 실기기 런과 절대 같은 조건이 아니다
    LIGHTING_UNKNOWN,      # 기록되지 않음. 비교 대상으로 쓸 수 없다
)

# ── 파이프라인 단계 어휘 (session.json: pipeline_stages) ────────────────────
# 조명과 **같은 이유로** 어휘를 고정한다. `pipeline_stages`는 baseline_diff의
# CONDITION_KEYS에 들어 있는 비교 조건이라, 같은 구조를 두 이름으로 부르면
# (`blit_2pass` vs `pass1_oes_to_offscreen`) 모든 비교가 "조건 다름"이 된다.
#
# ⚠ **생산자는 앱이다** (`android/.../gl/RenderArm.kt`의 `pipelineStages`). 그러므로 어휘는
#   앱이 쓰는 문자열로 맞춘다 — 하네스가 자기 이름을 따로 쓰면 합성 런과 실측 런이 영원히
#   비교 불가가 된다. 합성 생성기(`gen_synthetic_frames.py`)가 이 목록을 따라간다.
# ⚠ **판정선이 아니다.** 어휘 밖 토큰은 경고만 낸다(미지 열과 같은 취급) — 앱이 새 arm을
#   하네스보다 먼저 낼 수 있고, 그때 집계가 죽으면 그날 측정을 통째로 잃는다.
# 이 목록은 `docs/FRAME_LOG_SCHEMA.md` §5의 표와 **같아야 한다.**
STAGE_BLIT_2PASS = "blit_2pass"        # 3패스 골격(OES→오프스크린→②자리→표시). 앱 생산
STAGE2_GAMMA = "stage2_gamma"          # ② 자리 감마 패스. 앱 생산
STAGE_DETECT = "detect"                # ③ 탐지. **앱 미구현** — 현재는 합성 로그만 낸다
STAGE4_HIGHLIGHT = "stage4_highlight"  # ④ 강조. **앱 미구현** — 현재는 합성 로그만 낸다

PIPELINE_STAGES = (
    STAGE_BLIT_2PASS,
    STAGE2_GAMMA,
    STAGE_DETECT,
    STAGE4_HIGHLIGHT,
)
# 빈 배열 = 처리 없는 arm(passthrough). "단계 없음"은 토큰이 아니라 빈 배열로 적는다.

# ── 폐기 가드 ─────────────────────────────────────────────────────────────
# 하한(0)은 모든 시계열에 적용한다. 0 이하 간격/지연은 물리적으로 불가능하며
# 시계가 역행했다는 뜻이다. **버리되 반드시 센다.**
MIN_POSITIVE_MS = 0.0
#
# 상한은 `capture_to_render_ms`에만 둔다.
#   - t_capture_ns만 기준 시계가 다르다(§시계 함정). 기준이 어긋나면 수천 초가 나오므로
#     상한이 옳다 — 그럴듯한 쓰레기 숫자가 버짓표에 들어가는 것보다 낫다.
#   - 나머지(recv_interval / output_interval / render_latency / recv_to_render)는 전부
#     같은 단조 시계 하나에서 나온다. 여기서 큰 값은 시계 오류가 아니라 **실제로 느린
#     프레임**이다(발열 스로틀링, GC, 백그라운드 전환). p95로 tail을 관리하는 하네스가
#     느린 쪽 샘플을 버리는 것은 존재 이유와 정면으로 어긋나므로 **상한을 두지 않는다.**
#     render_latency_ms도 t_render_end - t_render_start로 같은 시계 안에서 닫히므로
#     같은 논리가 그대로 적용된다(예전 5초 상한은 12초 스톨 프레임을 삼켰다).
#   - GPU 패스 시간(GPU_TIME_COLUMNS)도 **하한 > 0, 상한 없음**으로 같다. 한 패스의
#     시작/끝을 같은 GPU 시계 안에서 닫으므로 큰 값은 시계 오류가 아니라 **진짜 느린
#     프레임**이다. 발열 스로틀링으로 GPU 클럭이 떨어지는 구간이 정확히 우리가 잡아야 할
#     대상이므로 여기에 상한을 두면 잡아야 할 것을 버린다.
SANE_CAPTURE_TO_RENDER_MS = (0.0, 5_000.0)

# 폐기 사유 → 사람이 읽는 문장
DISCARD_REASON_TEXT = {
    "below_min": "0 이하 — 시계 역행 또는 기준 시계 불일치",
    "above_max": f"{SANE_CAPTURE_TO_RENDER_MS[1]:.0f}ms 이상 — 기준 시계 불일치",
}

# GPU 패스 시간 열에서 "0 이하"의 뜻은 시계 역행이 아니다. 사유별 계수는 위 경로를
# 그대로 쓰되(새 폐기 경로를 만들지 않는다), 사람이 읽는 문장만 열 성격에 맞게 바꾼다.
# 엉뚱하게 "시계 역행"이라고 쓰면 폰 쪽이 시계 코드를 뒤지게 된다.
GPU_DISCARD_REASON_TEXT = {
    "below_min": (
        "-1 또는 0 이하 — disjoint로 버려졌거나 query가 해소되지 않았다"
        " (시계 역행이 아니다)"
    ),
}

# ── 행 단위 소실 사유 ─────────────────────────────────────────────────────
# 위 `discarded`는 **값 하나**를 버린 것이고, 여기는 **행 전체**가 시계열에 들어오지 못한
# 것이다. 둘을 같은 통에 넣으면 "몇 행이 사라졌나"를 되물을 수 없으므로 분리한다.
#
# 사유를 3개로 쪼개는 이유: warmup은 매 실측에서 항상 발생하는 **의도된 제외**라서
# 이것까지 data_complete=false로 만들면 플래그가 늘 false가 되어 쓸모없어진다.
# 나머지 둘은 **이상**이며 조용히 넘어가면 안 된다.
ROW_SKIP_REASONS = ("warmup", "before_t0", "unparsable_t_recv")

# data_complete를 흔드는 사유 = warmup을 뺀 나머지
ANOMALOUS_SKIP_REASONS = ("before_t0", "unparsable_t_recv")

ROW_SKIP_REASON_TEXT = {
    "warmup": "warmup 구간(의도된 제외)",
    "before_t0": "첫 행보다 앞선 t_recv_ns — 시계 역행",
    "unparsable_t_recv": "t_recv_ns 파싱 불가(빈칸/비수치/-1) — 잘린 로그 행",
}

# ── 시계 혼용 교차검사 ────────────────────────────────────────────────────
# 값에 상한을 두어 걸러내지 않는다(F-1의 교훈: 큰 값은 진짜 느린 프레임일 수 있다).
# 대신 **열끼리 물리적으로 반드시 성립해야 하는 관계**를 본다. 위반은 데이터를 버리지
# 않고 경고로만 낸다.
#
# 교차검사 A — 렌더는 수신 후에 시작한다. 같은 시계라면 `t_render_start >= t_recv`가
#   항상 참이며, 이는 `render_latency_ms <= recv_to_render_ms`와 같은 말이다.
#   같은 시계에서 찍은 정수 ns를 비교하므로 허용오차가 필요 없다(0).
#   위반 = t_render_*가 t_recv보다 **뒤처진** 방향의 시계 어긋남.
#
# 교차검사 B — 반대 방향(t_render_*가 t_recv보다 **앞선** 경우)은 A로 잡히지 않는다.
#   recv_to_render가 큰 양수로만 나오기 때문이다. 이때는 비율로 본다.
#   백프레셔가 STRATEGY_KEEP_ONLY_LATEST이면 한 번에 한 장만 처리하므로 체류시간
#   (recv→render_end)은 출력 주기와 같은 자릿수다. 큐를 두더라도 그 깊이(3~4장)를
#   넘지 않는다. 20배는 그 위로 한참 여유를 둔 값이라 진짜 느린 프레임이나 일시적
#   큐 적체로는 넘지 않고, 시계 오프셋(딥슬립 수십 초~수 시간)은 수천~수십만 배가
#   나오므로 확실히 걸린다.
#   ⚠ 이 값은 **진단용 임계**이지 판정선이 아니다. 판정선(66.7 / 80)은 `lib/targets.py`
#     에만 있으며 여기와 섞지 않는다.
CLOCK_DWELL_RATIO_LIMIT = 20.0


@dataclass
class FrameSeries:
    """프레임 로그에서 뽑아낸, 판정에 쓸 수 있는 시계열들."""

    recv_interval_ms: list[float] = field(default_factory=list)
    output_interval_ms: list[float] = field(default_factory=list)
    # t_render_end - t_render_start. 순수 렌더 비용.
    render_latency_ms: list[float] = field(default_factory=list)
    # t_render_end - t_recv. 큐 대기까지 포함. **render_latency_ms와 다른 물리량이므로
    # 같은 키에 섞지 않는다** (예전에는 t_render_start가 없으면 여기 값이 render_latency_ms로
    # 들어가서, 소비자가 어느 쪽을 받았는지 구분할 수 없었다).
    recv_to_render_ms: list[float] = field(default_factory=list)
    capture_to_render_ms: list[float] = field(default_factory=list)
    # ── GPU 패스 시간 (GPU 시계 — 위 시계열들과 **다른 시계**다. 섞지 않는다) ──
    stage_b_ms: list[float] = field(default_factory=list)
    stage_d_ms: list[float] = field(default_factory=list)
    stage_i_ms: list[float] = field(default_factory=list)
    gpu_present_ms: list[float] = field(default_factory=list)
    # **행 단위** 합. p50(B)+p50(D) != p50(B+D)이므로 백분위를 더하지 않고 행에서 먼저
    # 더한 뒤 분포를 낸다. 그 행에 유효한 GPU 열이 하나도 없으면 기여하지 않는다.
    gpu_sum_ms: list[float] = field(default_factory=list)
    # CSV 헤더에 실제로 있던 GPU 열. 헤더에 없는 열은 폐기로 세지 않는다
    # ("열이 아예 없다"와 "열은 있는데 값이 -1이다"는 다른 사실이다).
    gpu_columns_present: list[str] = field(default_factory=list)
    # gpu_sum_ms에 들어갔지만 헤더에 있는 GPU 열을 **전부** 채우지는 못한 행 수.
    # 이 값이 0이 아니면 gpu_sum_ms 분포는 아래쪽으로 치우친다(빠진 패스만큼 작다).
    gpu_sum_partial_rows: int = 0
    dropped_total: int = 0
    rows_read: int = 0
    rows_used: int = 0
    # 시계열 이름 -> {사유: 개수}. **값 하나**를 버린 것. 폐기는 조용히 일어나면 안 된다.
    discarded: dict[str, dict[str, int]] = field(default_factory=dict)
    # 사유 -> 개수. **행 전체**가 시계열에 못 들어온 것. 회계는 아래 accounting_ok로 닫는다.
    rows_skipped: dict[str, int] = field(
        default_factory=lambda: {r: 0 for r in ROW_SKIP_REASONS}
    )
    # 시계 혼용 교차검사 결과 (check_clock_consistency가 채운다)
    clock_check: dict = field(default_factory=dict)
    # CSV 헤더에 있었지만 KNOWN_COLUMNS에 없어 **집계에 쓰이지 않은** 열 이름.
    # 비어 있지 않으면 오타이거나 스키마 확장이 필요한 것이다.
    unknown_columns: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def has_output_timeline(self) -> bool:
        return bool(self.output_interval_ms)

    @property
    def gpu_series(self) -> dict[str, list[float]]:
        """열 이름 -> 시계열. 파생인 gpu_sum_ms는 여기 넣지 않는다(원본 열만)."""
        return {name: getattr(self, name) for name in GPU_TIME_COLUMNS}

    @property
    def has_gpu_timings(self) -> bool:
        return any(self.gpu_series.values())

    @property
    def discarded_total(self) -> int:
        return sum(sum(reasons.values()) for reasons in self.discarded.values())

    @property
    def rows_skipped_total(self) -> int:
        return sum(self.rows_skipped.values())

    @property
    def rows_skipped_anomalous(self) -> int:
        """warmup을 뺀 소실. 1건이라도 있으면 그 로그는 온전하지 않다."""
        return sum(self.rows_skipped.get(r, 0) for r in ANOMALOUS_SKIP_REASONS)

    @property
    def accounting_ok(self) -> bool:
        """rows_read == rows_used + 모든 소실 사유의 합. 깨지면 어딘가 조용히 새고 있다."""
        return self.rows_read == self.rows_used + self.rows_skipped_total

    @property
    def clock_consistent(self) -> bool:
        return bool(self.clock_check.get("consistent", True))

    def note_discard(self, series_name: str, reason: str) -> None:
        reasons = self.discarded.setdefault(series_name, {})
        reasons[reason] = reasons.get(reason, 0) + 1

    def note_row_skip(self, reason: str) -> None:
        self.rows_skipped[reason] = self.rows_skipped.get(reason, 0) + 1


class FrameLogError(Exception):
    """스키마 위반. 조용히 넘어가면 안 되는 것만 여기로 던진다."""


def _to_int(raw: Optional[str]) -> int:
    if raw is None or str(raw).strip() == "":
        return MISSING
    try:
        return int(float(str(raw).strip()))
    except ValueError:
        return MISSING


def _to_float(raw: Optional[str]) -> float:
    """GPU 패스 시간용. 없는 값·파싱 불가는 MISSING(-1)으로 통일한다.

    -1은 실기기에서 실제로 오는 값이다(disjoint 구간이거나 query가 그 프레임 안에
    해소되지 않은 경우). 0.0과 구분해야 하므로 0으로 대체하지 않는다.

    ⚠ **유한하지 않은 값도 MISSING이다.** `float("NaN")`·`float("Infinity")`는
    ValueError를 내지 않고 그대로 통과한다(`_to_int`는 int()가 막아 줘서 이 구멍이 없다).
    NaN이 시계열에 들어가면 (a) 정렬 순서가 깨져 백분위가 무의미해지고,
    (b) json.dump가 표준 JSON이 아닌 `NaN`/`Infinity` 맨 토큰을 뱉어 파이썬 아닌
    소비자가 요약을 못 읽는다. 여기서 -1로 만들면 아래 하한 가드에 걸려 **폐기로 계수**되므로
    조용히 사라지지도 않는다.
    """
    if raw is None or str(raw).strip() == "":
        return float(MISSING)
    try:
        val = float(str(raw).strip())
    except ValueError:
        return float(MISSING)
    if not math.isfinite(val):
        return float(MISSING)
    return val


def read_session(path: Path) -> dict:
    """session.json — 기기 메타·빌드 타입·파이프라인 구성. 없으면 빈 dict."""
    if not path.exists():
        return {}
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def read_frames(
    path: Path,
    warmup_sec: float = 0.0,
) -> FrameSeries:
    """프레임 CSV를 읽어 시계열을 만든다.

    warmup_sec: 첫 N초를 버린다. AE/AWB 수렴 전 프레임은 튀므로 측정에서 제외한다
    (android-runtime 스킬 §4).
    """
    if not path.exists():
        raise FrameLogError(f"프레임 로그가 없다: {path}")

    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None:
            raise FrameLogError(f"헤더가 없다: {path}")
        missing = [c for c in REQUIRED_COLUMNS if c not in reader.fieldnames]
        if missing:
            raise FrameLogError(
                f"필수 열 누락: {missing} (있는 열: {reader.fieldnames})"
            )
        # ── 중복 열은 **죽인다.** 미지 열과 성격이 다르다.
        #    csv.DictReader는 헤더가 중복되면 **마지막 값만** 남긴다. 그래서 예를 들어
        #    `...,t_render_end_ns,t_render_end_ns`(뒤쪽이 -1)이면 성한 값이 -1에 덮여
        #    output_interval_ms.count == 0이 되고, 리포트는 "출력 타임라인 없음"이라고
        #    **잘못** 결론 낸다 — 아래 미지 열 방어선이 막으려던 오진단 그 자체인데
        #    이름은 전부 KNOWN_COLUMNS 안에 있으므로 미지 열 검사로는 안 걸린다.
        #
        #    왜 경고가 아니라 하드 에러인가: 미지 열은 **덧붙는** 것이라 무해하지만(앱이
        #    스키마보다 앞서 나갈 수 있다), 중복은 아는 열의 값을 **파괴한다.** 그리고
        #    정상적인 생산자가 헤더를 두 번 쓸 이유가 없다 — 항상 버그다. 행 회계
        #    불변식이 깨질 때 FrameLogError로 죽는 것과 같은 부류다(값이 조용히 샜다).
        dup_known = sorted(
            {c for c, n in Counter(reader.fieldnames).items() if n > 1 and c in KNOWN_COLUMNS}
        )
        if dup_known:
            raise FrameLogError(
                f"헤더에 중복된 열이 있다: {dup_known} (전체 헤더: {reader.fieldnames}) — "
                "csv.DictReader는 중복 헤더에서 마지막 값만 남기므로 앞쪽 값이 조용히 파괴된다. "
                "그 결과 해당 지표가 count=0이 되어 '그 열이 없는 로그'와 구분되지 않는다. "
                "폰 쪽 CSV 헤더 생성부를 확인할 것"
            )
        # 미지 열은 죽이지 않는다. 앱이 스키마보다 앞서 나갈 수 있으므로 하드 에러는 과하다.
        # 대신 이름을 지목해 경고한다 (KNOWN_COLUMNS 주석 참고).
        # 중복된 미지 열은 아는 값을 파괴하지 않으므로 여기서 이름 1개로 합쳐 경고만 한다.
        unknown_columns = sorted({c for c in reader.fieldnames if c not in KNOWN_COLUMNS})
        # 헤더에 실제로 있는 GPU 열만 집계 대상이다. 헤더에 없는 열까지 읽으면 v1 로그의
        # 모든 행이 "값 -1"로 보여 폐기 카운트가 행 수만큼 튄다 — "열이 없다"와
        # "열은 있는데 -1이다"는 다른 사실이므로 여기서 갈라 둔다.
        gpu_columns_present = [c for c in GPU_TIME_COLUMNS if c in reader.fieldnames]
        rows = list(reader)

    series = FrameSeries()
    series.unknown_columns = unknown_columns
    series.gpu_columns_present = gpu_columns_present
    _add_unknown_column_warnings(series)
    series.rows_read = len(rows)
    if not rows:
        raise FrameLogError(f"행이 하나도 없다: {path}")

    # warmup 컷 기준은 첫 행의 t_recv_ns
    t0 = _to_int(rows[0].get("t_recv_ns"))
    if t0 == MISSING:
        raise FrameLogError("첫 행의 t_recv_ns가 비어 있다 — 기준 시각을 잡을 수 없다")
    cutoff_ns = t0 + int(warmup_sec * 1e9)

    prev_recv = MISSING
    prev_out = MISSING
    # 교차검사 A용 카운터 (t_render_start >= t_recv 가 항상 참이어야 한다)
    render_start_checked = 0
    render_start_violations = 0

    for row in rows:
        t_recv = _to_int(row.get("t_recv_ns"))
        # ── 행을 건너뛰는 세 경로. **전부 사유별로 센다.**
        #    세지 않으면 rows_read와 rows_used를 사람이 눈으로 대조해야만 소실을 알 수 있고,
        #    그 상태를 docs/FRAME_LOG_SCHEMA.md가 실패로 규정한다.
        if t_recv == MISSING:
            series.note_row_skip("unparsable_t_recv")
            continue
        if t_recv < t0:
            # 첫 행보다 과거 = 시계 역행. warmup 컷으로 위장되어 조용히 사라지던 자리다.
            series.note_row_skip("before_t0")
            continue
        if t_recv < cutoff_ns:
            series.note_row_skip("warmup")  # 의도된 제외
            continue

        t_cap = _to_int(row.get("t_capture_ns"))
        t_rs = _to_int(row.get("t_render_start_ns"))
        t_re = _to_int(row.get("t_render_end_ns"))
        dropped = _to_int(row.get("dropped_since_last"))

        series.rows_used += 1
        if dropped > 0:
            series.dropped_total += dropped

        if prev_recv != MISSING:
            _collect(
                series, "recv_interval_ms", series.recv_interval_ms,
                (t_recv - prev_recv) / 1e6,
            )
        prev_recv = t_recv

        # 출력 주기 — 파이프라인이 프레임을 뱉는 실제 간격 (FRAME_BUDGET.md §2 "프레임타임")
        if t_re != MISSING:
            if prev_out != MISSING:
                _collect(
                    series, "output_interval_ms", series.output_interval_ms,
                    (t_re - prev_out) / 1e6,
                )
            prev_out = t_re

        # 처리 시간 — 두 물리량을 각각 다른 키로 낸다 (같은 키에 섞지 않는다).
        #   render_latency_ms : 순수 렌더 비용        (t_render_start가 있을 때만)
        #   recv_to_render_ms : 큐 대기 포함 체류시간 (t_render_end가 있으면 항상)
        if t_re != MISSING:
            if t_rs != MISSING:
                _collect(
                    series, "render_latency_ms", series.render_latency_ms,
                    (t_re - t_rs) / 1e6,
                )
            _collect(
                series, "recv_to_render_ms", series.recv_to_render_ms,
                (t_re - t_recv) / 1e6,
            )

        # 교차검사 A: 렌더 시작은 수신 이후여야 한다 (= render_latency <= recv_to_render).
        # 값을 버리지 않는다. 세기만 하고 판정은 check_clock_consistency가 한다.
        if t_rs != MISSING:
            render_start_checked += 1
            if t_rs < t_recv:
                render_start_violations += 1

        # 취득~렌더 — 카메라 시계가 우리 시계와 같은 기준일 때만 의미가 있다.
        # 여기만 상한을 둔다 (SANE_CAPTURE_TO_RENDER_MS 주석 참고).
        if t_cap != MISSING and t_re != MISSING:
            _collect(
                series, "capture_to_render_ms", series.capture_to_render_ms,
                (t_re - t_cap) / 1e6, SANE_CAPTURE_TO_RENDER_MS,
            )

        # ── GPU 패스 시간. 위 시계열과 **다른 시계**라 교차검사에 넣지 않는다.
        #    헤더에 있는 열만 본다. 값이 -1이면 _collect의 하한(> 0)에 걸려
        #    below_min으로 세어진다 — 기존 폐기 계수를 그대로 쓴다(새 경로를 만들지 않는다).
        #    그래야 disjoint로 몇 프레임이 빠졌는지가 조용히 사라지지 않는다.
        row_gpu_sum = 0.0
        row_gpu_valid = 0
        for col in gpu_columns_present:
            val = _to_float(row.get(col))
            before = len(getattr(series, col))
            _collect(series, col, getattr(series, col), val)
            if len(getattr(series, col)) > before:
                row_gpu_sum += val
                row_gpu_valid += 1
        if row_gpu_valid:
            if row_gpu_valid < len(gpu_columns_present):
                # 일부 패스만 해소된 행. 합이 그만큼 작으므로 사실을 세어 둔다.
                series.gpu_sum_partial_rows += 1
            # 백분위를 더하지 않는다: 행에서 먼저 더하고 분포는 그 뒤에 낸다.
            _collect(series, "gpu_sum_ms", series.gpu_sum_ms, row_gpu_sum)

    check_clock_consistency(series, render_start_checked, render_start_violations)
    _add_row_skip_warnings(series)
    _add_discard_warnings(series)
    _add_clock_warnings(series)
    _add_gpu_warnings(series)

    if series.rows_used == 0:
        raise FrameLogError(
            f"warmup {warmup_sec}s 이후 남은 행이 없다 (rows_read={series.rows_read}, "
            f"소실 내역={series.rows_skipped}) — 측정 시간이 warmup보다 짧거나 "
            f"t_recv_ns가 성한 행이 없다"
        )
    if not series.accounting_ok:  # 방어선. 깨지면 위 세 경로 밖으로 행이 샜다는 뜻이다.
        raise FrameLogError(
            f"행 회계가 맞지 않는다: rows_read={series.rows_read} != "
            f"rows_used={series.rows_used} + 소실 {series.rows_skipped_total} "
            f"({series.rows_skipped})"
        )
    if not series.recv_interval_ms:
        # 간격이 없는 이유는 두 가지다. 뭉뚱그리면 시계 역행을 "행이 1개뿐"으로 오진단한다.
        discarded = sum(series.discarded.get("recv_interval_ms", {}).values())
        if discarded:
            series.warnings.append(
                f"프레임 간격을 하나도 계산하지 못했다 — {series.rows_used}개 행에서 나온 "
                f"간격 {discarded}개가 전부 폐기됐다(시계 역행). t_recv_ns가 단조 시계인지 확인할 것"
            )
        else:
            series.warnings.append(
                f"프레임 간격을 하나도 계산하지 못했다 — 사용 가능한 행이 "
                f"{series.rows_used}개뿐이라 차분을 만들 수 없다"
            )

    return series


def check_clock_consistency(
    series: FrameSeries,
    render_start_checked: int,
    render_start_violations: int,
) -> None:
    """열 사이의 물리적 관계로 시계 혼용을 잡아낸다 (값 상한 없이).

    `t_capture_ns` 문제와 **구분한다.** 여기서 지목하는 범인은 `t_render_*` ↔ `t_recv_ns`
    쌍이고, `t_capture_ns` 쪽은 `capture_to_render_ms`의 폐기 카운트가 따로 말한다.
    """
    # ── A. render_latency_ms <= recv_to_render_ms (= t_render_start >= t_recv)
    a_consistent = render_start_violations == 0
    check_a = {
        "rule": "render_latency_ms <= recv_to_render_ms (t_render_start_ns >= t_recv_ns)",
        "checked": render_start_checked,
        "violations": render_start_violations,
        "consistent": a_consistent,
    }

    # ── B. 체류시간 p50이 출력 주기 p50의 몇 배인가
    dwell_p50 = _p50(series.recv_to_render_ms)
    if series.output_interval_ms:
        ref_name, ref_p50 = "output_interval_ms", _p50(series.output_interval_ms)
    else:
        ref_name, ref_p50 = "recv_interval_ms", _p50(series.recv_interval_ms)

    ratio = None
    if dwell_p50 is not None and ref_p50:
        ratio = round(dwell_p50 / ref_p50, 3)
    b_consistent = ratio is None or ratio <= CLOCK_DWELL_RATIO_LIMIT
    check_b = {
        "rule": (
            f"p50(recv_to_render_ms) <= {CLOCK_DWELL_RATIO_LIMIT:g}x p50({ref_name}) "
            "— 진단용 임계이며 판정선(lib/targets.py)이 아니다"
        ),
        "reference_series": ref_name,
        "reference_p50_ms": ref_p50,
        "recv_to_render_p50_ms": dwell_p50,
        "ratio": ratio,
        "ratio_limit": CLOCK_DWELL_RATIO_LIMIT,
        "consistent": b_consistent,
    }

    consistent = a_consistent and b_consistent
    series.clock_check = {
        "render_start_after_recv": check_a,
        "dwell_vs_interval": check_b,
        "consistent": consistent,
        # 어긋난 열 쌍을 이름으로 지목한다. t_capture_ns가 아니다.
        "suspect_columns": (
            [] if consistent else ["t_render_start_ns", "t_render_end_ns", "t_recv_ns"]
        ),
    }


def _p50(values: list[float]) -> Optional[float]:
    if not values:
        return None
    return round(percentile(sorted(values), 0.50), 3)


def _add_unknown_column_warnings(series: FrameSeries) -> None:
    """스키마에 없는 열을 이름으로 지목한다. **죽이지 않고 경고만.**

    조용히 무시하면 열 이름 오타가 "그 열이 원래 없었다"와 구분되지 않는다.
    (예: t_render_end_ns → t_render_ns 오타 시 output_interval_ms.count == 0이 되고
     리포트는 "출력 타임라인 없음"이라고 잘못 말한다.)
    """
    if not series.unknown_columns:
        return
    names = ", ".join(repr(c) for c in series.unknown_columns)
    series.warnings.append(
        f"스키마에 없는 열 {len(series.unknown_columns)}개를 발견했다: {names} — "
        f"이 열은 집계에 전혀 쓰이지 않았다. 열 이름 오타라면(예: 't_render_end_ns'를 "
        f"'t_render_ns'로) 해당 지표가 count=0이 되어 '그 열이 없는 로그'와 구분되지 않으므로, "
        f"위 이름을 폰 쪽 헤더와 대조할 것. 의도한 새 열이라면 lib/frame_log.py의 "
        f"OPTIONAL_COLUMNS와 docs/FRAME_LOG_SCHEMA.md에 등록해야 집계에 들어온다 "
        f"(하네스가 아는 열: {', '.join(KNOWN_COLUMNS)})"
    )


def check_lighting_condition(session: dict) -> tuple[Optional[str], bool, Optional[str]]:
    """session.json의 lighting_condition을 검사한다.

    반환: (값, 비교에 쓸 수 있는가, 경고 문장 or None).
    ⚠ **판정선이 아니다.** PASS/FAIL·exit code를 흔들지 않는다. 다만 조용히 넘어가면
    그 런은 나중에 아무것과도 정직하게 비교할 수 없으므로 경고는 반드시 낸다.
    """
    raw = session.get("lighting_condition")
    vocab = ", ".join(LIGHTING_CONDITIONS)
    if raw is None or str(raw).strip() == "":
        return None, False, (
            "session.json에 lighting_condition이 없다 — 야간 앱에서 조명은 공급 fps를 직접 "
            "바꾸는 측정 조건이다(저조도에서 AE가 노출을 늘리면 프레임 간격 자체가 벌어진다). "
            "이 런은 baseline_diff에서 다른 런과 조건이 같은지 확인할 수 없으므로 "
            f"비교 근거로 쓰지 말 것. 허용 어휘: {vocab}"
        )
    val = str(raw).strip()
    if val == LIGHTING_UNKNOWN:
        return val, False, (
            f"lighting_condition='{LIGHTING_UNKNOWN}' — 조명 조건이 기록되지 않았다. "
            "값이 있긴 하지만 이 런은 어느 조명에서 잰 것인지 알 수 없어 비교 대상이 못 된다. "
            f"측정 시 실제 조건을 적을 것. 허용 어휘: {vocab}"
        )
    if val not in LIGHTING_CONDITIONS:
        return val, False, (
            f"lighting_condition='{val}'은 허용 어휘 밖이다 — 자유 문자열을 쓰면 같은 조명이 "
            "서로 다른 이름으로 갈려 모든 비교가 '조건 다름'이 된다. "
            f"허용 어휘: {vocab} (목록은 lib/frame_log.py와 docs/FRAME_LOG_SCHEMA.md §5)"
        )
    return val, True, None


def check_pipeline_stages(session: dict) -> tuple[object, bool, Optional[str]]:
    """session.json의 pipeline_stages 어휘를 검사한다.

    반환: (값 원문, 어휘 안인가, 경고 문장 or None).
    ⚠ **판정선이 아니다.** `check_lighting_condition`과 같은 취급 — PASS/FAIL·exit code를
    흔들지 않는다. 키가 아예 없는 경우는 여기서 말하지 않는다(빈 파이프라인 단서가
    analyze_frames 쪽에서 이미 그 사실을 다룬다).
    """
    raw = session.get("pipeline_stages")
    vocab = ", ".join(PIPELINE_STAGES)
    if raw is None:
        return None, True, None
    if not isinstance(raw, list):
        return raw, False, (
            f"pipeline_stages가 리스트가 아니다({type(raw).__name__}: {raw!r}) — "
            "baseline_diff는 이 값을 그대로 비교하므로 타입이 다르면 같은 조건도 "
            "'조건 다름'이 된다. 앱 쪽 session.json 생성부를 확인할 것"
        )
    unknown = [s for s in raw if s not in PIPELINE_STAGES]
    if unknown:
        names = ", ".join(repr(s) for s in unknown)
        return raw, False, (
            f"pipeline_stages에 어휘 밖 토큰이 있다: {names} — 자유 문자열을 쓰면 같은 "
            "구조가 서로 다른 이름으로 갈려 baseline_diff의 모든 비교가 '조건 다름'이 된다. "
            f"허용 어휘: {vocab} (목록은 lib/frame_log.py의 PIPELINE_STAGES와 "
            "docs/FRAME_LOG_SCHEMA.md §5). 앱이 새 arm을 먼저 낸 것이라면 하네스 쪽 어휘를 "
            "먼저 등록해야 이후 런이 과거 런과 비교된다"
        )
    return raw, True, None


def check_schema_version(session: dict) -> tuple[Optional[int], bool, Optional[str]]:
    """session.json이 선언한 schema_version과 하네스의 SCHEMA_VERSION을 대조한다.

    반환: (선언값, 일치하는가, 경고 문장 or None).
    ⚠ **경고만이다.** 판정(`meets_*_target`)·종료 코드를 바꾸지 않는다 — 옛 로그는 계속
    읽혀야 한다(v1 로그는 GPU 열이 없을 뿐 분포는 그대로 나온다). 다만 조용히 넘어가면
    v1 세션이 v2로 라벨된 요약에 실려 나가고, 나중에 그 요약만 보고는 어느 쪽인지 알 수 없다.
    """
    if "schema_version" not in session:
        return None, False, (
            f"session.json에 schema_version이 없다 — 하네스는 v{SCHEMA_VERSION}로 읽었지만 "
            "이 로그가 어느 스키마로 쓰였는지는 로그 자신이 말하지 않는다. "
            "세션 파일을 주지 않았거나(--session 생략), 스키마 키를 쓰기 전 빌드의 로그다. "
            "summary.json의 schema_version은 **하네스 버전**이지 이 로그의 버전이 아니다"
        )
    raw = session.get("schema_version")
    # ⚠ `int(raw)`로 뭉개지 않는다. `int(2.7) == 2`라서 **불일치가 조용히 일치로 정규화**되고
    #   (경고 0건으로 통과한다), `int(True) == 1`이라 타입 오류가 "v1 로그"라는 거짓 사실로
    #   보고된다. 정수로 딱 떨어지는 값만 버전으로 받고 나머지는 전부 타입 문제로 낸다.
    #   `2.0`은 받는다 — JSON에서 정수 2와 같은 값이지 불일치가 아니다.
    declared: Optional[int] = None
    if isinstance(raw, bool):
        declared = None  # JSON true/false는 버전이 아니다 (파이썬에서 bool은 int의 하위형)
    elif isinstance(raw, int):
        declared = raw
    elif isinstance(raw, float) and raw.is_integer():
        declared = int(raw)
    if declared is None:
        return raw, False, (
            f"session.json의 schema_version이 정수가 아니다({raw!r}, 타입 "
            f"{type(raw).__name__}) — 하네스 버전(v{SCHEMA_VERSION})과 대조할 수 없다. "
            f"소수점이 붙은 값(예: 2.7)을 정수로 깎아 받으면 **불일치가 일치로 둔갑**하므로 "
            f"받지 않는다. 앱 쪽 SessionWriter를 확인할 것"
        )
    if declared == SCHEMA_VERSION:
        return declared, True, None
    if declared < SCHEMA_VERSION:
        return declared, False, (
            f"session.json이 schema_version={declared}이라고 선언했다 — 하네스는 "
            f"v{SCHEMA_VERSION}다. **앱이 하네스보다 뒤처진 옛 로그**이며, v2에서 늘어난 것"
            f"(GPU 패스 시간 열 {', '.join(GPU_TIME_COLUMNS)}, session의 gl/gpu_timer 블록)이 "
            f"없을 수 있다. 읽히기는 하지만 stages 블록의 count가 0인 것은 '그 패스가 "
            f"0ms였다'가 아니라 **그 빌드가 재지 않았다**는 뜻이다. 최신 빌드 런과 같은 "
            f"조건으로 취급하지 말 것"
        )
    return declared, False, (
        f"session.json이 schema_version={declared}이라고 선언했다 — 하네스는 "
        f"v{SCHEMA_VERSION}다. **앱이 하네스보다 앞서 나갔다.** 앱이 새로 넣은 열은 "
        f"KNOWN_COLUMNS에 없어 집계에서 통째로 버려지므로(미지 열 경고를 함께 확인할 것), "
        f"이 런의 새 지표는 요약에 없다. lib/frame_log.py의 OPTIONAL_COLUMNS/"
        f"SCHEMA_VERSION과 docs/FRAME_LOG_SCHEMA.md를 먼저 올린 뒤 다시 집계할 것"
    )


def _add_row_skip_warnings(series: FrameSeries) -> None:
    """행이 사라졌으면 사유별로 말한다. warmup만은 경고가 아니다(의도된 제외)."""
    for reason in ANOMALOUS_SKIP_REASONS:
        n = series.rows_skipped.get(reason, 0)
        if not n:
            continue
        series.warnings.append(
            f"행 {n}개가 시계열에 들어가지 못했다 — {ROW_SKIP_REASON_TEXT[reason]} "
            f"(rows_read={series.rows_read}, rows_used={series.rows_used}, "
            f"warmup 제외={series.rows_skipped.get('warmup', 0)}). "
            f"의도된 제외가 아니므로 이 로그의 분포는 온전한 측정이 아니다"
        )


def _add_clock_warnings(series: FrameSeries) -> None:
    """시계 혼용 경고. **범인 열을 이름으로 지목한다.**"""
    cc = series.clock_check
    if not cc:
        return
    a = cc["render_start_after_recv"]
    if not a["consistent"]:
        series.warnings.append(
            f"시계 혼용 의심 — t_render_start_ns가 t_recv_ns보다 앞선 행이 "
            f"{a['violations']}개다({a['checked']}개 중). 렌더는 수신 후에 시작하므로 "
            f"render_latency_ms <= recv_to_render_ms는 항상 참이어야 한다. "
            f"범인은 t_render_* ↔ t_recv_ns 쌍이며 t_capture_ns 문제와 무관하다 "
            f"— 폰 쪽에서 System.nanoTime()(MONOTONIC)과 "
            f"elapsedRealtimeNanos()(BOOTTIME)를 섞어 쓰고 있는지 확인할 것"
        )
    b = cc["dwell_vs_interval"]
    if not b["consistent"]:
        series.warnings.append(
            f"시계 혼용 의심 — recv_to_render_ms p50={b['recv_to_render_p50_ms']}ms가 "
            f"{b['reference_series']} p50={b['reference_p50_ms']}ms의 {b['ratio']:g}배다 "
            f"(진단 임계 {b['ratio_limit']:g}배). 백프레셔가 KEEP_ONLY_LATEST면 체류시간은 "
            f"출력 주기와 같은 자릿수여야 한다. 범인은 t_render_end_ns ↔ t_recv_ns 쌍이며 "
            f"t_capture_ns 문제와 무관하다 — 딥슬립이 있으면 MONOTONIC과 BOOTTIME이 "
            f"딱 이만큼 어긋난다. 이 체류시간을 지연 근거로 쓰지 말 것"
        )


def _add_discard_warnings(series: FrameSeries) -> None:
    """폐기가 1건이라도 있으면 경고로 남긴다.

    폐기된 샘플은 **그 측정의 최악 프레임일 수 있다.** rows_used와 count를 사람이 눈으로
    대조해야만 알 수 있는 상태여서는 안 된다.
    """
    for name in sorted(series.discarded):
        reasons = series.discarded[name]
        # 사유별 계수는 한 경로에서 나오지만, 열 성격에 따라 그 사유가 뜻하는 바가 다르다.
        text = GPU_DISCARD_REASON_TEXT if name in GPU_TIME_COLUMNS else DISCARD_REASON_TEXT
        detail = ", ".join(
            f"{text.get(reason, DISCARD_REASON_TEXT.get(reason, reason))} {count}개"
            for reason, count in sorted(reasons.items())
        )
        total = sum(reasons.values())
        series.warnings.append(
            f"{name}: 샘플 {total}개를 폐기했다 ({detail}). "
            f"폐기된 샘플이 그 측정의 최악 프레임일 수 있으므로 분포는 낙관적으로 치우친다"
        )

    # capture_to_render는 **어느 쪽 위반이든** 기준 시계 불일치를 뜻한다.
    # 음수(카메라 epoch이 우리보다 미래) / 수천 초(과거) 둘 다 같은 원인이다.
    capture_bad = sum(series.discarded.get("capture_to_render_ms", {}).values())
    if capture_bad:
        n = capture_bad
        series.warnings.append(
            f"t_capture_ns 기준 시계가 우리 시계와 다른 것으로 보인다 "
            f"({n}개 행이 물리적으로 불가능한 값) — "
            f"글래스-투-글래스 지연은 이 로그로 판정할 수 없다"
        )


def _add_gpu_warnings(series: FrameSeries) -> None:
    """GPU 패스 시간 열이 **있는데 유효 표본이 없는** 경우를 말한다.

    이 경고가 없으면 `stage_d_ms.count == 0`이 "②가 공짜였다"로 읽힐 수 있다.
    0ms와 "재지 못했다"는 완전히 다른 사실이고, 후자는 측정 실패다.
    """
    for col in series.gpu_columns_present:
        if getattr(series, col):
            continue
        discarded = sum(series.discarded.get(col, {}).values())
        series.warnings.append(
            f"{col}: 열은 있는데 유효 표본이 0개다(폐기 {discarded}개). "
            f"이건 '그 패스가 0ms였다'가 아니라 **재지 못했다**는 뜻이다 — "
            f"GL_EXT_disjoint_timer_query 미지원, disjoint 연속 발생, query 미해소 중 "
            f"하나다. 이 열로 단계 비용을 말하지 말 것"
        )
    if series.gpu_sum_partial_rows:
        series.warnings.append(
            f"gpu_sum_ms: {series.gpu_sum_partial_rows}개 행이 헤더에 있는 GPU 열 "
            f"{len(series.gpu_columns_present)}개를 다 채우지 못한 채 합산됐다 "
            f"(있는 열: {', '.join(series.gpu_columns_present)}). "
            f"빠진 패스만큼 합이 작으므로 이 분포는 아래쪽으로 치우친다"
        )


def _collect(
    series: FrameSeries,
    name: str,
    target: list[float],
    value: float,
    bounds: tuple[float, Optional[float]] = (MIN_POSITIVE_MS, None),
) -> None:
    """범위 안이면 채택, 아니면 **사유별로 세고** 버린다. 조용히 버리지 않는다.

    ⚠ 판정을 `value <= lo`가 아니라 **`not (value > lo)`**로 쓴다. NaN은 어떤 비교에도
    False를 돌려주므로 `value <= lo`는 NaN을 통과시키고, 그러면 정렬·백분위가 무의미해지고
    요약 JSON이 표준이 아니게 된다. 부정형으로 쓰면 "확실히 하한 위"인 값만 채택되므로
    NaN은 하한 위반으로 잡혀 **폐기 계수에 정직하게 드러난다**(사유는 below_min —
    "값이 있었는데 못 썼다"가 보이는 것이 요점이다).
    상한도 같은 이유로 `not (value < hi)`로 쓴다. 파싱 경계(_to_float)에서도 막지만,
    여기서 한 번 더 닫아야 값의 **출처와 무관하게** 이 부류가 통째로 막힌다.
    """
    lo, hi = bounds
    if not (value > lo):
        series.note_discard(name, "below_min")
        return
    if hi is not None and not (value < hi):
        series.note_discard(name, "above_max")
        return
    target.append(value)


def write_frames(
    path: Path, rows: list[dict], columns: Optional[list[str]] = None
) -> None:
    """합성 로그 생성용. 실기기 로그는 폰이 쓰므로 여기를 거치지 않는다.

    `columns`를 주지 않으면 **행에 실제로 있는 키만** 헤더로 쓴다.
    전체 OPTIONAL_COLUMNS를 항상 쓰면, 스키마에 열이 하나 늘 때마다 모든 합성 로그가
    그 열을 -1로 갖게 되어 "그 열이 없는 로그"를 만들 수 없다 — 하위호환 검증 입력을
    생성기로 만들지 못하게 된다.
    """
    known = list(REQUIRED_COLUMNS) + list(OPTIONAL_COLUMNS)
    if columns is not None:
        cols = list(columns)
    elif rows:
        cols = [c for c in known if any(c in r for r in rows)]
    else:
        cols = known
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=cols)
        writer.writeheader()
        for row in rows:
            writer.writerow({c: row.get(c, MISSING) for c in cols})
