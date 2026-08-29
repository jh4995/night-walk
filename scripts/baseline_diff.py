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
    # **arm은 그 런이 실제로 무엇을 그렸는지를 정하는 측정 조건이다** — 조명과 정확히 같은
    # 성격이며, `pipeline_stages`가 이것을 담지 못한다는 것이 독립 검증에서 실증됐다:
    # `blit_2pass` 런과 `clahe_gamma` 런이 둘 다 pipeline_stages=['blit_2pass']여서
    # 처리량이 완전히 다른데도 **무경고로 "회귀 없음"**이 나왔다.
    # 근본 원인: ② 하위 패스 열(stage_d_hist/cdf/apply_ms)에 대응하는 PIPELINE_STAGES 토큰이
    # 없다. 그 토큰의 생산자는 앱이라 하네스가 먼저 지어낼 수 없으므로(지어내면 앱이 다른
    # 이름을 쓰는 날 모든 비교가 "조건 다름"이 된다), arm 자체를 조건으로 본다.
    # ⚠ 대가를 알고 받는다: v1 승격 베이스라인에는 render_arm이 없어(None) 새 passthrough
    #   런(="passthrough")과 "조건 다름"으로 뜬다. 이건 버그가 아니라 **정직한 표시**다 —
    #   그 옛 로그는 자기가 어떤 arm이었는지 실제로 기록하지 않았고, "passthrough 상당"은
    #   우리의 추론이다. comparable은 exit code를 바꾸지 않는다.
    ("session", "render_arm"),
    # ④ 오버레이의 **fill 알파**. 🔴 이 파일이 :32-38에서 실증한 실패 양식이 지금 fill에서
    # 재현되기 직전이라 넣는다: 박스 안을 채우기 시작하면서 **정적 더미 arm 3개
    # (highlight_boxes / _stress / _1q)의 렌더가 달라졌는데 arm id는 그대로다.** arm이
    # 조건을 담아 주던 자리(render_arm)가 이번에는 담지 못하므로, 그 위 주석의 blit_2pass ↔
    # clahe_gamma 사례처럼 **다른 렌더를 '조건 동일'로 판정한 채** 비교하게 된다.
    # 값의 출처는 앱의 자진 신고(session.json의 overlay.fill_alpha)이고 측정 결과가 아니다 —
    # 아래 detect.ep.resolved를 뺀 기준(선언 vs 결과)에 그대로 부합한다.
    # ⚠ **키 부재의 처리는 이 파일의 기존 관행 그대로다**(새 관행을 만들지 않는다): `_dig`가
    #   None을 돌려주므로 v7 승격 베이스라인(fill 이전 빌드)은 새 v8 런과 "조건 다름"으로 뜬다.
    #   render_arm을 넣을 때 적어 둔 대가와 **글자 그대로 같은 것**이며 버그가 아니라 정직한
    #   표시다 — 그 옛 로그는 자기가 어떤 알파로 그렸는지 실제로 기록하지 않았고, "fill이
    #   없었다"는 우리의 추론이다. comparable은 exit code를 바꾸지 않는다.
    # ⚠ 오버레이를 안 그리는 arm(passthrough 등)은 session.json에 overlay 블록 자체가 없어
    #   양쪽 다 None이 되고, 그때는 조건이 실제로 같으므로 경고가 뜨지 않는다.
    ("session", "overlay", "fill_alpha"),
    # ④ 오버레이의 **fill 켬/끔**. 🔴 위 `fill_alpha`만으로는 부족하다 —
    # fill 대조 arm(`detect_cpu_chain_highlight_nofill`)은 fill 기하를 건너뛰므로 칠한 알파가
    # 없고 `fill_alpha=null`을 낸다(앱이 사유 문장을 옆에 싣는다). 그런데 **이 파일의 `_dig`는
    # 명시적 null과 키 부재를 구분하지 못한다** — 둘 다 `None`을 돌려준다
    # (`lib/frame_log.py`의 `session_field`와 달리 `key_present`를 주지 않는다. 이 파일은
    #  기존 관행을 유지하고 새 관행을 만들지 않는다 → 위 fill_alpha 주석).
    # 그래서 **하중을 받는 키는 이것**이다:
    #   - fill arm(`true`) ↔ nofill arm(`false`)  → `true != false`로 조건 다름.
    #     🔴 `fill_alpha`에만 맡기면 `0.3` vs `None`이라 이 짝도 잡히기는 하지만, 그 판정은
    #     "옛 로그와 비교했다"와 **같은 모양**이라 사유를 읽을 수 없다. 이 키가 있으면
    #     경고 문장이 fill 켬/끔을 **이름으로** 지목한다.
    #   - v7 승격 베이스라인(오버레이 블록에 이 키 자체가 없다 → `None`) ↔ nofill 런(`false`)
    #     → `None != false`로 조건 다름. 🔴 이건 **정직한 판정**이다: 그 옛 로그는 자기가
    #     박스 안을 채웠는지 실제로 기록하지 않았고, "fill이 없었다"는 우리의 추론이다.
    #     (`render_arm`·`fill_alpha`를 넣을 때 받은 대가와 글자 그대로 같은 것이다.)
    # ⚠ `passthrough`처럼 오버레이 블록 자체가 없는 arm은 양쪽 다 `None`이라 경고가 뜨지
    #   않는다 — **기존 성질이며 옳다**(그때는 조건이 실제로 같다).
    # 값의 출처는 앱의 자진 신고(session.json의 overlay.fill_enabled)이고 측정 결과가 아니다.
    ("session", "overlay", "fill_enabled"),
    # 야간 앱에서 조명은 **공급 fps를 직접 바꾸는 조건**이다. 저조도에서 카메라 AE가 노출을
    # 늘리면 프레임 간격 자체가 벌어져, 밝은 방 런과 야간 런을 비교하면 코드가 그대로여도
    # "회귀"로 보인다. 어휘는 lib/frame_log.py의 LIGHTING_CONDITIONS.
    ("session", "lighting_condition"),
    ("source", "warmup_sec"),
    # ── 🔴 **`detect.ep.resolved`를 여기 넣지 않는다 (결정됨, v6)** ──────────
    # CONDITION_KEYS는 **선언된 조건**을 비교하는 장치인데 `ep.resolved`는 **측정된 결과**다.
    # 결과를 조건 키에 넣으면 조용히 폴백한 런이 "조건 다름"이라는 **약한 신호**(comparable
    # =false, exit code는 그대로)로 나오고, 그 사실을 크게 내야 할 `run_session.py`의 EP
    # 어긋남 검사(계획 대조에서 그 런을 **실패**로 만든다)와 역할이 겹친다.
    # **하나의 사실에 장치가 둘이면 약한 쪽이 강한 쪽을 가린다** — "이미 표시가 있다"는
    # 이유로 강한 검사를 안 보게 된다.
    #
    # EP 차이는 **arm id로 가른다**: `detect_cpu` / `detect_nnapi`가 이미 다른 arm이고
    # `render_arm`은 위 조건 키에 있다. `highlight_boxes`/`_stress`(박스 개수), `_1q`
    # (계측 방식)와 **같은 구조**다 — 조건이 다르면 arm을 가른다는 이 저장소의 선례.
    #
    # ⚠ **남는 구멍:** 같은 `detect_nnapi` arm인데 런마다 `ep.resolved`가 갈리는 경우
    #   (한 런은 NNAPI로 열리고 다른 런은 CPU로 폴백)는 arm id로 잡히지 않는다. 그건
    #   run_session의 어긋남 검사가 담당한다(그 런은 계획 칸을 못 채운다). 노이즈 쌍 안에서
    #   실제로 갈리는 것이 관측되면 그때 이 키 추가를 재검토한다 — 관측 전에 넣지 않는다.
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
