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
    GPU_SUM_COLUMNS,
    GPU_TIME_COLUMNS,
    PIPELINE_STAGES,
    ROW_SKIP_REASON_TEXT,
    SCHEMA_VERSION,
    FrameLogError,
    check_lighting_condition,
    check_pipeline_stages,
    check_schema_version,
    read_frames,
    read_session,
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
BUDGET_CELL_OF = {
    "stage_b_ms": "B",
    "stage_d_ms": "D",
    "stage_i_ms": "I",
    "gpu_present_ms": None,  # 최종 표시 패스. 실제 GPU 비용이지만 A~J 어느 칸도 아니다
}

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


def frametime_pinned_caveat(
    pinned: dict, stages: list, camera_fps: float | None = None
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
        f"처리 단계 {stages}가 붙어 있는데도 그렇다. "
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


def _stages_block(series, stats: dict) -> dict:
    """단계 비용 블록. **frametime과 분리한다.**

    간격/체류시간(frametime)과 단계 비용(stages)은 다른 물리량이고 **다른 시계**에서
    온다. 같은 키에 섞으면 소비자가 자기가 받은 숫자가 어느 시계의 무엇인지 구분할 수
    없다 — render_latency_ms와 recv_to_render_ms를 같은 키에 넣지 않는 것과 같은 이유다.
    """
    block = {
        "clock": (
            "GPU 시계 (GL_EXT_disjoint_timer_query). t_*_ns(CLOCK_BOOTTIME)와 다른 시계이므로 "
            "frametime 값과 더하거나 빼지 않으며, 시계 교차검사(A/B) 대상도 아니다"
        ),
        "columns_present": list(series.gpu_columns_present),
        "budget_cell": dict(BUDGET_CELL_OF),
        # **이 런에서 실제로 합산 대상이 된 열.** 상수 전체 목록을 그대로 실으면
        # 헤더에 2개뿐인 로그에서도 4개가 실려 "4개를 다 더한 값"으로 오독된다.
        # columns_present와 대조해야만 알 수 있는 상태로 두지 않는다.
        "gpu_sum_columns": [c for c in GPU_SUM_COLUMNS if c in series.gpu_columns_present],
        # 스키마가 합산 대상으로 정의한 전체 목록(이 런에 있었는지와 무관). 둘을 나눠 둔다.
        "gpu_sum_columns_defined": list(GPU_SUM_COLUMNS),
        "gpu_sum_note": (
            "gpu_sum_columns는 이 런에서 실제로 더해진 열이다(헤더에 있는 것만). "
            "행별로 유효한 값을 먼저 더한 뒤 백분위를 냈다(p50(B)+p50(D) != p50(B+D)). "
            "유효한 열이 하나도 없는 행은 기여하지 않는다"
        ),
        "gpu_sum_partial_rows": series.gpu_sum_partial_rows,
        "has_gpu_timings": series.has_gpu_timings,
    }
    block.update(stats)
    return block


def main() -> int:
    parser = common_argparser()
    parser.add_argument("--frames", required=True, help="프레임 로그 CSV 경로")
    parser.add_argument("--session", default="", help="session.json 경로 (없으면 생략)")
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

    # ── 단계 비용 (GPU 시계). 판정선이 없으므로 verdict를 흔들지 않는다.
    stage_stats = {name: summarize(getattr(series, name)) for name in GPU_TIME_COLUMNS}
    stage_stats["gpu_sum_ms"] = summarize(series.gpu_sum_ms)
    gpu_valid_total = sum(len(v) for v in series.gpu_series.values())

    # session.json이 선언한 GPU timer 지원 여부. 선언과 실제가 모순되면 선언 쪽이
    # 틀렸을 수 있다 (capture_clock_base_contradicted와 같은 패턴).
    gpu_timer_decl = session.get("gpu_timer") or {}
    if not isinstance(gpu_timer_decl, dict):
        gpu_timer_decl = {}
    gpu_timer_declared = bool(gpu_timer_decl.get("supported"))
    gpu_timer_contradicted = gpu_timer_declared and gpu_valid_total == 0

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
        "stages": _stages_block(series, stage_stats),
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
        # 탐지가 아직 없으므로 미평가임을 명시한다 — 조용히 빠뜨리지 않는다.
        "safety_regression": {
            "evaluated": False,
            "reason": "탐지 단계 미구현 — 위험물 강조 누락률을 아직 잴 수 없다",
        },
    }

    # ⚠ `list(pipeline_stages)`를 그냥 씌우지 않는다. int면 TypeError로 **집계 전체가 죽어
    #   summary.json이 안 남고**(어휘·타입 검사는 판정선이 아니므로 exit code를 흔들면 안 된다),
    #   문자열은 글자로 dict는 키로 쪼개져 "단계" 자리에 엉뚱한 것이 출력된다.
    #   리스트가 아닌 값은 check_pipeline_stages가 이미 경고했다 — 여기서는 죽지 않고,
    #   단계를 전제하는 두 단서(빈 파이프라인 / 묶임)를 **내지 않는 것**으로 처리한다.
    pipeline_stages = stages_declared if isinstance(stages_declared, list) else None
    if stages_declared is not None and pipeline_stages is None:
        pinned = _frametime_pinned(summary["frametime"])
        if pinned is not None:
            # 관측 자체는 단계와 무관한 사실이므로 남긴다. 문장만 내지 않는다.
            summary["frametime"]["pinned_to_camera_supply"] = pinned
        summary["warnings"].append(nonlist_pipeline_stages_caveat(stages_declared))
    elif not pipeline_stages:
        summary["warnings"].append(
            empty_pipeline_caveat(_camera_fps(session))
        )
    else:
        # 단계가 붙었다고 empty_pipeline_caveat만 끄면 안 된다. 프레임타임이 공급에
        # 묶여 있는 한 그 단서의 주장은 그대로 참이므로, 조건을 관측으로 바꿔 다시 낸다.
        pinned = _frametime_pinned(summary["frametime"])
        if pinned is not None:
            # 관측치를 요약에도 남긴다 — 경고 문장만 있으면 나중에 기계가 되물을 수 없다.
            summary["frametime"]["pinned_to_camera_supply"] = pinned
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
    if lighting_warning:
        # 판정은 바꾸지 않는다. 조명은 판정선이 아니라 **비교 조건**이다.
        summary["warnings"].append(lighting_warning)
    if stages_warning:
        # 조명과 같은 취급 — 단계 어휘도 판정선이 아니라 비교 조건이다.
        summary["warnings"].append(stages_warning)
    if schema_warning:
        # 판정·종료 코드는 바꾸지 않는다. 옛 로그는 계속 읽혀야 한다(하위호환).
        summary["warnings"].append(schema_warning)
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


def _print_stages(summary: dict) -> None:
    """단계 비용(GPU 시계) 출력. **판정선이 없으므로 PASS/FAIL을 찍지 않는다.**"""
    st = summary.get("stages") or {}
    cols = st.get("columns_present") or []
    if not cols:
        LOG.info("단계 비용(GPU timer): 열 없음 — 이 로그로는 단계 비용을 말할 수 없다")
        return
    LOG.info("단계 비용 — GPU 시계 (프레임타임과 다른 시계, 판정선 없음)")
    for name in list(GPU_TIME_COLUMNS) + ["gpu_sum_ms"]:
        s = st.get(name) or {}
        if not s:
            continue
        cell = BUDGET_CELL_OF.get(name)
        label = f"{name}[{cell}칸]" if cell else name
        if s.get("count"):
            LOG.info(
                "  %-20s p50=%-8s p95=%-8s p99=%-8s min=%-8s max=%-8s (n=%s)",
                label, s["p50"], s["p95"], s["p99"], s["min"], s["max"], s["count"],
            )
        elif name in cols or (name == "gpu_sum_ms" and cols):
            LOG.warning("  %-20s 유효 표본 0개 — '0ms'가 아니라 재지 못한 것이다", label)


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
