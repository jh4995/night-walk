"""프레임 로그 스키마 — 폰(PoC)이 뱉고 PC(하네스)가 읽는 형식.

규격 전문은 `docs/FRAME_LOG_SCHEMA.md`. 이 모듈은 그 규격의 실행 가능한 정의다.

설계 원칙 3가지:

1. **유도 가능한 값은 저장하지 않는다.** 프레임타임은 타임스탬프 차이로 계산한다.
   폰이 계산한 값과 PC가 계산한 값이 어긋나면 어느 쪽이 맞는지 알 수 없다.
2. **단조 시계 하나로 통일한다.** `t_*_ns`는 전부 같은 시계(`SystemClock.elapsedRealtimeNanos`)여야
   한다. 예외는 카메라가 주는 `t_capture_ns` 하나이며, 그 기준 시계는 기기마다 다르다.
3. **없는 값은 -1.** 빈칸이나 0이 아니라 -1로 명시한다. 0은 "0ms 걸렸다"와 구분되지 않는다.
"""

from __future__ import annotations

import csv
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from lib.stats import percentile

SCHEMA_VERSION = 1

# 폰이 반드시 뱉어야 하는 열
REQUIRED_COLUMNS = ("frame_idx", "t_recv_ns")

# 있으면 쓰고 없으면 건너뛰는 열
OPTIONAL_COLUMNS = (
    "t_capture_ns",       # 카메라 ImageInfo.timestamp — 기준 시계 불명확 (§시계 함정)
    "t_render_start_ns",
    "t_render_end_ns",
    "dropped_since_last",  # 백프레셔로 버려진 프레임 수
)

MISSING = -1

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
SANE_CAPTURE_TO_RENDER_MS = (0.0, 5_000.0)

# 폐기 사유 → 사람이 읽는 문장
DISCARD_REASON_TEXT = {
    "below_min": "0 이하 — 시계 역행 또는 기준 시계 불일치",
    "above_max": f"{SANE_CAPTURE_TO_RENDER_MS[1]:.0f}ms 이상 — 기준 시계 불일치",
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
    warnings: list[str] = field(default_factory=list)

    @property
    def has_output_timeline(self) -> bool:
        return bool(self.output_interval_ms)

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
        rows = list(reader)

    series = FrameSeries()
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

    check_clock_consistency(series, render_start_checked, render_start_violations)
    _add_row_skip_warnings(series)
    _add_discard_warnings(series)
    _add_clock_warnings(series)

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
        detail = ", ".join(
            f"{DISCARD_REASON_TEXT.get(reason, reason)} {count}개"
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


def _collect(
    series: FrameSeries,
    name: str,
    target: list[float],
    value: float,
    bounds: tuple[float, Optional[float]] = (MIN_POSITIVE_MS, None),
) -> None:
    """범위 안이면 채택, 아니면 **사유별로 세고** 버린다. 조용히 버리지 않는다."""
    lo, hi = bounds
    if value <= lo:
        series.note_discard(name, "below_min")
        return
    if hi is not None and value >= hi:
        series.note_discard(name, "above_max")
        return
    target.append(value)


def write_frames(path: Path, rows: list[dict]) -> None:
    """합성 로그 생성용. 실기기 로그는 폰이 쓰므로 여기를 거치지 않는다."""
    cols = list(REQUIRED_COLUMNS) + list(OPTIONAL_COLUMNS)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=cols)
        writer.writeheader()
        for row in rows:
            writer.writerow({c: row.get(c, MISSING) for c in cols})
