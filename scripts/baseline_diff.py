"""두 측정을 비교해 회귀를 판정한다.

단일 실행 숫자만으로 "빨라졌다"고 말하지 않는다 (measure-harness 스킬 §4).
비교는 **같은 조건**에서만 의미가 있으므로, 조건이 다르면 비교값보다 경고를 먼저 낸다.

    python scripts/baseline_diff.py --baseline <summary.json> --current <summary.json>
"""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lib.run_utils import common_argparser, init_run  # noqa: E402
from lib.stats import pct_change  # noqa: E402

LOG = logging.getLogger(__name__)

# 이 이하 변화는 실행 간 노이즈로 본다. 넘으면 회귀/개선으로 판정한다.
DEFAULT_NOISE_PCT = 5.0

# 조건이 다르면 비교가 아니라 착시다 (measure-harness 스킬 §4).
CONDITION_KEYS = (
    ("device", "props", "model"),
    ("device", "props", "build_fingerprint"),
    ("session", "build_type"),
    ("session", "pipeline_stages"),
    # 야간 앱에서 조명은 **공급 fps를 직접 바꾸는 조건**이다. 저조도에서 카메라 AE가 노출을
    # 늘리면 프레임 간격 자체가 벌어져, 밝은 방 런과 야간 런을 비교하면 코드가 그대로여도
    # "회귀"로 보인다. 어휘는 lib/frame_log.py의 LIGHTING_CONDITIONS.
    ("session", "lighting_condition"),
    ("source", "warmup_sec"),
)

METRICS = ("p50", "p95", "p99", "mean")

# 종료 코드. "판정 불가"는 "회귀 없음"과 **다른 결론**이므로 다른 코드로 낸다.
EXIT_OK = 0
EXIT_REGRESSION = 1
EXIT_LOAD_ERROR = 2
EXIT_UNDETERMINED = 3


def _dig(d: dict, path: tuple):
    cur = d
    for key in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur


def _load(path: Path) -> dict:
    if not path.exists():
        raise FileNotFoundError(f"summary를 찾을 수 없다: {path}")
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def compare_conditions(base: dict, cur: dict) -> list[str]:
    """비교 가능한 조건인지 확인. 다른 항목을 문장으로 돌려준다."""
    diffs = []
    for path in CONDITION_KEYS:
        b, c = _dig(base, path), _dig(cur, path)
        if b != c:
            diffs.append(f"{'.'.join(path)}: baseline={b!r} vs current={c!r}")
    return diffs


def main() -> int:
    parser = common_argparser()
    parser.add_argument("--baseline", required=True, help="기준 summary.json")
    parser.add_argument("--current", required=True, help="비교할 summary.json")
    parser.add_argument(
        "--noise_pct",
        type=float,
        default=DEFAULT_NOISE_PCT,
        help=f"이 %% 이하 변화는 노이즈로 간주 (기본 {DEFAULT_NOISE_PCT})",
    )
    parser.add_argument(
        "--fail_on_regression",
        action="store_true",
        help=(
            f"CI용. 회귀={EXIT_REGRESSION}, 판정 불가={EXIT_UNDETERMINED}. "
            "판정 불가를 통과시키면 baseline이 깨져도 초록불이 된다"
        ),
    )
    args = parser.parse_args()

    paths = init_run(stage="baseline_diff", script_file=__file__, args=args)

    try:
        base = _load(Path(args.baseline))
        cur = _load(Path(args.current))
    except (FileNotFoundError, json.JSONDecodeError) as exc:
        LOG.error("summary를 읽지 못했다: %s", exc)
        return EXIT_LOAD_ERROR

    condition_diffs = compare_conditions(base, cur)

    b_ft = base.get("frametime", {}).get("primary", {})
    c_ft = cur.get("frametime", {}).get("primary", {})

    metrics: dict = {}
    regressed, improved, no_data = [], [], []
    for m in METRICS:
        b_val, c_val = b_ft.get(m), c_ft.get(m)
        change = pct_change(b_val, c_val)
        verdict = "no_data"
        if change is not None:
            if change > args.noise_pct:
                verdict = "regressed"      # 프레임 간격이 커짐 = 느려짐
                regressed.append(m)
            elif change < -args.noise_pct:
                verdict = "improved"
                improved.append(m)
            else:
                verdict = "within_noise"
        else:
            no_data.append(m)
        metrics[m] = {
            "baseline_ms": b_val,
            "current_ms": c_val,
            "change_pct": change,
            "verdict": verdict,
        }

    b_v = base.get("verdict", {})
    c_v = cur.get("verdict", {})
    target_flips = {}
    for flag in ("meets_fps_target", "meets_p95_target"):
        target_flips[flag] = {
            "baseline": b_v.get(flag),
            "current": c_v.get(flag),
            "newly_failing": bool(b_v.get(flag)) and not bool(c_v.get(flag)),
        }

    # "판정 불가"는 "회귀 없음"과 **다른 결론**이다. 비교 가능한 지표가 하나도 없으면
    # 회귀가 없다고 단정할 수 없다 — 깨진 baseline이 CI를 초록불로 통과하는 경로가 여기였다.
    undetermined = not [m for m in METRICS if m not in no_data]
    undetermined_reason = None
    if undetermined:
        parts = []
        if not b_ft:
            parts.append("baseline에 frametime.primary가 없다")
        if not c_ft:
            parts.append("current에 frametime.primary가 없다")
        if not parts:
            parts.append("양쪽 frametime.primary에 비교 가능한 값이 없다")
        undetermined_reason = (
            f"{'; '.join(parts)} — 비교 가능한 지표 0/{len(METRICS)}개 "
            f"(no_data: {', '.join(no_data)})"
        )

    has_regression = bool(regressed) or any(
        t["newly_failing"] for t in target_flips.values()
    )
    if has_regression:
        status = "regression"
    elif undetermined:
        status = "undetermined"
    else:
        status = "no_regression"

    result = {
        "baseline_run_ts": base.get("run_ts"),
        "current_run_ts": cur.get("run_ts"),
        "noise_pct": args.noise_pct,
        "comparable": not condition_diffs,
        "condition_diffs": condition_diffs,
        "metrics": metrics,
        "targets": target_flips,
        "regressed_metrics": regressed,
        "improved_metrics": improved,
        "no_data_metrics": no_data,
        "status": status,
        "undetermined": undetermined,
        "undetermined_reason": undetermined_reason,
        "has_regression": has_regression,
        # 성능이 좋아졌다고 보고할 때는 안전 회귀를 함께 낸다 (conventions §6).
        "safety_regression": {
            "baseline": base.get("safety_regression"),
            "current": cur.get("safety_regression"),
        },
    }

    _print_report(result)

    if paths.outputs_enabled:
        out_path = paths.out_dir / "diff.json"
        with out_path.open("w", encoding="utf-8") as f:
            json.dump(result, f, indent=2, ensure_ascii=False, sort_keys=True)
            f.write("\n")
        LOG.info("diff 저장: %s", out_path)
    else:
        LOG.info("outputs 비활성 — diff를 파일로 남기지 않았다")

    if args.fail_on_regression:
        if result["has_regression"]:
            return EXIT_REGRESSION
        if result["undetermined"]:
            # 판정 불가를 통과시키면 baseline이 깨진 것을 CI가 알려주지 못한다.
            return EXIT_UNDETERMINED
    return EXIT_OK


def _print_report(r: dict) -> None:
    LOG.info("=" * 62)
    LOG.info("baseline %s  →  current %s", r["baseline_run_ts"], r["current_run_ts"])
    if not r["comparable"]:
        LOG.warning("⚠ 조건이 다르다 — 아래 숫자는 비교가 아니라 착시일 수 있다:")
        for d in r["condition_diffs"]:
            LOG.warning("    - %s", d)
    LOG.info("-" * 62)
    for name, m in r["metrics"].items():
        LOG.info(
            "  %-5s %8s → %8s ms  (%+.2f%%)  %s",
            name,
            m["baseline_ms"],
            m["current_ms"],
            m["change_pct"] if m["change_pct"] is not None else 0.0,
            m["verdict"],
        )
    for flag, t in r["targets"].items():
        if t["newly_failing"]:
            LOG.warning("  ⚠ %s: PASS → FAIL", flag)
    LOG.info("-" * 62)
    if r["status"] == "regression":
        LOG.info("회귀: 있음")
    elif r["status"] == "undetermined":
        # "없음"이라고 단정하지 않는다. 비교를 못 한 것과 비교해서 괜찮은 것은 다르다.
        LOG.error("회귀: 판정 불가 — %s", r["undetermined_reason"])
    else:
        LOG.info("회귀: 없음")
        if r["no_data_metrics"]:
            LOG.warning(
                "  ⚠ 다만 %s 지표는 비교하지 못했다 (no_data)",
                ", ".join(r["no_data_metrics"]),
            )
    sr = r["safety_regression"].get("current") or {}
    if not sr.get("evaluated", False):
        LOG.warning(
            "⚠ 안전 회귀 미평가 (%s) — 성능만 있는 보고는 불완전한 보고다",
            sr.get("reason"),
        )
    LOG.info("=" * 62)


if __name__ == "__main__":
    raise SystemExit(main())
