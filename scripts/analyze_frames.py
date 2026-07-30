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
    ROW_SKIP_REASON_TEXT,
    SCHEMA_VERSION,
    FrameLogError,
    read_frames,
    read_session,
)
from lib.run_utils import common_argparser, init_run  # noqa: E402
from lib.stats import summarize  # noqa: E402

LOG = logging.getLogger(__name__)

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

    summary = {
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

    if not session.get("pipeline_stages"):
        summary["warnings"].append(
            empty_pipeline_caveat(_camera_fps(session))
        )
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


def _camera_fps(session: dict) -> float | None:
    raw = (session.get("camera") or {}).get("requested_fps")
    try:
        return float(raw) if raw is not None else None
    except (TypeError, ValueError):
        return None


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
    for w in summary["warnings"]:
        LOG.warning("⚠ %s", w)
    LOG.info("=" * 62)


if __name__ == "__main__":
    raise SystemExit(main())
