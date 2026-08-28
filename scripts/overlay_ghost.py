"""④ 오버레이 **잔상**을 잰다 — 게시(publish)와 화면의 박스 개수 차 (v7 로그 재분석).

    python scripts/overlay_ghost.py --frames <런디렉토리|frames.csv> [--frames ...]

**왜 이 스크립트가 있나.** ④ 오버레이는 hold TTL을 **표시 프레임** 단위로 깎는다
(`session.json`의 `overlay.smoothing.hold_frames`). 사용자는 "박스가 물체보다 늦게 사라진다"고
보고했지만 **그것을 재는 지표가 없었다** — 눈으로 본 것은 계측이 아니고 재현 절차도 없다
(CLAUDE.md 규칙 7). 이 스크립트는 **앱을 한 줄도 고치지 않고** 기존 v7 열만으로 그 지표를
만든다(CSV 열을 늘리지 않았고 `SCHEMA_VERSION`도 그대로다).

**지표의 정의 — 한 줄:**

    excess(frame) = overlay_boxes − published_count(그 프레임의 t_overlay_source_ns가 가리키는 게시)

      excess > 0  →  잔상    (게시에 없는 박스를 그리고 있다)
      excess < 0  →  결손    (게시에 있는 박스를 안 그리고 있다. 아래 분류를 볼 것)
      excess = 0  →  게시와 화면이 일치

**조인:** `t_overlay_source_ns` 값이 바뀔 때마다 새 게시 그룹이고, 그 값 **이하의 최대**
`t_detect_end_ns`를 가진 detect 행에 붙인다. 둘 다 `CLOCK_BOOTTIME`
(`SystemClock.elapsedRealtimeNanos`)이라 뺄 수 있다(`session.json`의 `overlay.publish.clock`).

🔴 **조인이 깨지면 잔상 숫자를 내지 않는다.** `published_count_exact`가 false면 분포를
    통째로 비우고 사유를 남긴다 — 값을 지어내는 것보다 "재지 못했다"가 낫다.
🔴 **판정선을 만들지 않는다.** "잔상 몇 프레임까지 허용"이라는 값은 이 저장소에 없다
    (판정선은 `lib/targets.py`에만 있고 잔상·깜빡임 한계는 팀 합의 전이다). `verdict`는
    null이고 종료 코드는 "표를 낼 수 있었는가"만 반영한다.

재사용한 것(같은 계산을 두 번 구현하면 두 값이 갈라진다):
  - 백분위·분포        : `lib/stats.py`의 `summarize` (nearest-rank. 세 번째 정의를 만들지 않는다)
  - warmup 절단·행 회계: `lib/frame_log.py`의 `read_frames` / `read_detect`
                        (절단 기준은 **frames.csv 첫 행의 t_recv_ns** 하나다)
  - 깜빡임 전이        : `scripts/analyze_frames.py`의 `overlay_flicker` — **재구현하지 않았다**
  - session.json 키    : `lib/frame_log.py`의 `OVERLAY_*_PATH` 상수 (경로 모양의 함정 2개가
                        그 주석에 적혀 있다: `rejected_inverted`의 자리와 `dropped_over_cap`이
                        중첩 객체라는 것)
"""

from __future__ import annotations

import bisect
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
    DETECT_CADENCE_SERIES,
    FRAME_COUNT_COLUMNS,
    FRAME_OVERLAY_SOURCE_COLUMNS,
    MISSING,
    OVERLAY_BOXES_PUBLISHED_PATH,
    OVERLAY_DROPPED_OVER_CAP_PUBLISH_PATH,
    OVERLAY_DROPPED_OVER_CAP_SMOOTHING_PATH,
    OVERLAY_EXCESS_SERIES,
    OVERLAY_HOLD_FRAMES_PATH,
    OVERLAY_MAP_FAILED_FRAMES_PATH,
    OVERLAY_PUBLISH_COUNT_PATH,
    OVERLAY_REJECTED_INVERTED_PATH,
    FrameLogError,
    read_detect,
    read_frames,
    read_session,
    session_field,
)
from lib.run_utils import common_argparser, init_run  # noqa: E402
from lib.stats import summarize  # noqa: E402

# 깜빡임 전이는 **이 문장의 주인이 저쪽**이다. 같은 계산을 여기서 다시 쓰면 두 값이 갈라진다.
from analyze_frames import overlay_flicker  # noqa: E402

LOG = logging.getLogger(__name__)

STAGE = "overlay_ghost"
SUMMARY_NAME = "ghost.json"

# 축이 되는 열. **개수이며 ms가 아니다.**
BOX_COLUMN = "overlay_boxes"
SOURCE_COLUMN = FRAME_OVERLAY_SOURCE_COLUMNS[0]
DETECT_BOX_COLUMN = "boxes_out"
DETECT_END_COLUMN = "t_detect_end_ns"

# ── excess < 0 의 분류 ────────────────────────────────────────────────────
# 🔴 **섞으면 안 되는 두 가지가 여기 있다.** FSM 도입 뒤 `pending`은 곧 "위험물 표시가 얼마나
#   늦었나"이고 그것이 규칙 6의 안전 회귀 숫자가 된다. 정책이 만든 진입 지연과, 게시 안에서
#   TTL이 만료돼 사라진 프레임을 한 통에 담으면 그 숫자를 믿을 수 없다.
CLASS_PENDING = "pending"          # 게시 첫 프레임부터 게시보다 적게 그렸다 (진입 쪽)
CLASS_HOLD_EXPIRY = "hold_expiry"  # 다 그리고 있다가 **같은 게시 안에서** 줄었다 (프레임 단위 TTL)
CLASS_UNEXPLAINED = "unexplained"  # 같은 게시 안에서 줄었는데 그 자리가 hold 선언값이 아니다
NEGATIVE_CLASSES = (CLASS_PENDING, CLASS_HOLD_EXPIRY, CLASS_UNEXPLAINED)

# ── 상수 자기검사 ─────────────────────────────────────────────────────────
# 데이터와 무관한 불변식이므로 import 시점에 닫는다(overlay_cost_by_boxes.py와 같은 부류).
if BOX_COLUMN not in FRAME_COUNT_COLUMNS:
    raise RuntimeError(
        f"{BOX_COLUMN}이 FRAME_COUNT_COLUMNS에 없다 — 카운트 열이 아니면 하한 가드(`>= 0`)의 "
        f"근거가 사라지고 박스 0개 프레임이 조용히 폐기된다"
    )
if OVERLAY_EXCESS_SERIES.endswith("_ms"):
    raise RuntimeError(
        f"{OVERLAY_EXCESS_SERIES}가 ms 이름이다 — 이 값은 **개수**이며 시간이 아니다"
    )

# ── 산출물에 반드시 붙는 문장 ─────────────────────────────────────────────
# 🔴 문장이 숫자와 **같은 산출물 안에** 있어야 한다. 이 표는 성질상 한 줄씩 인용된다
#   ("잔상이 p50 몇 프레임"). `analyze_frames.overlay_flicker`가 `threshold_note`·
#   `companion_metrics`로 하는 방식을 그대로 따른다.
NOTES = {
    "ms_needs_cadence": (
        "🔴 **`ghost_ms`는 `ghost_publishes × 탐지 주기`다 — 주기 없이 ms를 인용하지 않는다.** "
        "잔상이 몇 ms 남았는지는 탐지가 얼마나 자주 게시하느냐에 비례한다. 같은 정책(hold)이라도 "
        "게시가 두 배 빨라지면 ms는 절반이 된다. 그래서 이 블록의 ms는 항상 같은 런의 "
        f"`{DETECT_CADENCE_SERIES}` 분포와 **함께** 옮긴다(cadence 키가 이 블록 옆에 있다)."
    ),
    "no_ground_truth": (
        "🔴 **정답 라벨이 없다.** `excess > 0`은 '게시에 없는 낡은 박스를 그리고 있다'이지 "
        "'오탐'이 아니다 — 장면에서 그 물체가 **실제로** 사라졌는지 이 지표는 가르지 못한다. "
        "가르려면 정답 라벨이 필요하고 이 저장소에는 없다(`safety_regression`이 "
        "`evaluated:false`인 것과 **같은 사유**다). 그러므로 이 수를 '오탐 n건'으로 옮기지 않는다."
    ),
    "policy_only": (
        "🔴 **정책이 만든 잔상만 잰다.** 탐지가 물체를 놓친 시점부터 그 결과가 화면에 오기까지의 "
        "지연은 여기 들어가지 않는다 — 그건 `overlay_freshness_ms`(게시 결과의 나이)이고 "
        "모집단도 정의도 다르다. 두 값을 더하거나 하나로 '총 지연'을 만들지 않는다."
    ),
    "no_threshold": (
        "⚠ **판정선이 아니다.** '잔상 몇 프레임까지 허용'도 '깜빡임 몇 회까지 허용'도 이 저장소에 "
        "없다(판정선은 lib/targets.py에만 있고 둘 다 팀 합의 전이다). 이 블록은 관측이며 "
        "`verdict`와 종료 코드를 흔들지 않는다."
    ),
    "excess_is_count": (
        f"⚠ `excess`는 **박스 개수**이며 ms가 아니다(파생 시계열 이름 `{OVERLAY_EXCESS_SERIES}`). "
        "`ghost_box_publishes`는 그 개수를 프레임에 걸쳐 더한 값이라 단위가 **박스·프레임**이다 — "
        "게시 수도 박스 수도 아니다."
    ),
    "negative_split": (
        "🔴 **`excess < 0`을 한 통에 담지 않는다.** 게시 첫 프레임부터 모자란 것(pending = 진입 "
        "지연)과, 다 그리다가 **같은 게시 안에서** 줄어든 것(hold_expiry = 프레임 단위 TTL 만료)은 "
        "원인이 다르다. FSM 도입 뒤 pending은 곧 '위험물 표시가 얼마나 늦었나'이고 그것이 규칙 6의 "
        "안전 회귀 숫자가 된다 — 여기 오염이 남으면 그 숫자를 믿을 수 없다."
    ),
    "episode_ms_span": (
        "⚠ 에피소드의 ms는 그 구간 **첫 프레임과 마지막 프레임의 `t_recv_ns` 차**다(창 길이를 "
        "재는 방식과 같다). 1프레임짜리 에피소드는 0.0ms가 되며 '0ms였다'가 아니라 '차분을 만들 "
        "프레임이 하나뿐'이라는 뜻이다 — 그래서 프레임 수 분포를 항상 함께 낸다."
    ),
    "cross_run_not_pooled": (
        "🔴 런을 **합치지 않는다.** 줄마다 런·커밋이 붙는다. 서로 다른 빌드의 잔상 분포를 하나로 "
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


def resolve_input(raw: str) -> tuple[Path, Path, Path]:
    """`--frames` 하나를 (frames.csv, detect.csv, 런 디렉토리)로 푼다. 디렉토리도 받는다.

    없는 경로는 **죽인다.** 조용히 건너뛰면 오타 하나가 "그 런은 데이터가 없었다"로 읽힌다.
    `detect.csv`는 **필수**다 — 그 파일이 없으면 게시의 박스 수를 알 수 없고, 이 스크립트가
    내는 값은 전부 그 수와의 차이다.
    """
    p = Path(raw)
    if p.is_dir():
        csv_path = p / "frames.csv"
        if not csv_path.exists():
            raise FileNotFoundError(f"런 디렉토리에 frames.csv가 없다: {p}")
        run_dir = p
    elif p.exists():
        csv_path, run_dir = p, p.parent
    else:
        raise FileNotFoundError(f"경로가 없다: {p}")
    detect_path = run_dir / "detect.csv"
    if not detect_path.exists():
        raise FileNotFoundError(
            f"detect.csv가 없다: {detect_path} — 게시의 박스 수(boxes_out)를 알 수 없으므로 "
            f"잔상을 정의할 수 없다(이 스크립트의 모든 값이 그 수와의 차다)"
        )
    return csv_path, detect_path, run_dir


def _warmup_diagnosis(csv_path: Path, warmup_sec: float) -> dict:
    """`read_frames`가 죽었을 때 **왜 표본이 0인지** 말하기 위한 최소 진단.

    🔴 "빈 표를 조용히 내지 않기 위해" 있는 함수다(overlay_cost_by_boxes.py와 같은 것).
    warmup 컷 규약(첫 행 t_recv_ns 기준)은 `read_frames`와 같은 것을 쓰되 **세기만** 한다.
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
    valid = [t for t in (_to_int(r.get("t_recv_ns")) for r in rows) if t != MISSING]
    if not valid:
        return out
    t0 = valid[0]
    cutoff = t0 + int(warmup_sec * 1e9)
    out["rows_before_cutoff"] = sum(1 for t in valid if t < cutoff)
    out["span_sec"] = round((max(valid) - t0) / 1e9, 3)
    return out


def _session_stamp(session: dict) -> dict:
    """표에 실을 조건. **arm·조명·커밋 없이 이 숫자를 인용하지 않는다.**"""
    build = session.get("build") or {}
    if not isinstance(build, dict):
        build = {}
    return {
        "render_arm": session.get("render_arm"),
        "lighting_condition": session.get("lighting_condition"),
        "git_commit": build.get("git_commit"),
        # 앱이 문자열 'false'/'true'로 낸다. **불리언으로 바꾸지 않는다** — 원문을 싣고
        # 판단은 아래 불리언에서 따로 낸다.
        "git_dirty": build.get("git_dirty"),
        "git_dirty_is_true": str(build.get("git_dirty")).strip().lower() == "true",
        "build_type": session.get("build_type"),
        "schema_version": session.get("schema_version"),
    }


def _overlay_facts(session: dict) -> dict:
    """`session.json`의 overlay 블록에서 **조인 검산에 쓰는 수**를 뽑는다.

    🔴 키 경로는 전부 `lib/frame_log.py`의 상수에서 온다(모양의 함정 2개는 그 주석에 있다).
    🔴 **명시적 null과 "키가 없다"를 가른다** — 둘 다 "말할 수 없다"지만 사유가 다르고,
      뭉개면 사람이 엉뚱한 곳(스키마 버전)을 뒤진다(`session_field`의 규약).
    """
    out: dict = {}
    for name, path in (
        ("publish_count", OVERLAY_PUBLISH_COUNT_PATH),
        ("boxes_published", OVERLAY_BOXES_PUBLISHED_PATH),
        ("rejected_inverted", OVERLAY_REJECTED_INVERTED_PATH),
        ("dropped_over_cap_publish", OVERLAY_DROPPED_OVER_CAP_PUBLISH_PATH),
        ("dropped_over_cap_smoothing", OVERLAY_DROPPED_OVER_CAP_SMOOTHING_PATH),
        ("map_failed_frames", OVERLAY_MAP_FAILED_FRAMES_PATH),
        ("hold_frames", OVERLAY_HOLD_FRAMES_PATH),
    ):
        value, present = session_field(session, path)
        out[name] = value if isinstance(value, int) and not isinstance(value, bool) else None
        out[f"{name}_key_present"] = present
        out[f"{name}_path"] = ".".join(path)
    # 🔴 **두 dropped_over_cap을 더하지 않는다**(세는 자리가 다르다 — 그 note가 더하지 말라고
    #   적고 있다). 각각 0인지 따로 본다.
    out["dropped_over_cap_note"] = (
        "🔴 `overlay.dropped_over_cap`은 스칼라가 아니라 `{publish, smoothing}` 중첩 객체이며 "
        "**두 수를 더하지 않는다**(게시자가 센 것과 GL 스레드가 hold 중인 트랙까지 합쳐 센 것이라 "
        "같은 박스를 두 번 셀 수 있다). 조인 검산은 **둘 다 0**을 요구한다"
    )
    return out


def read_frame_rows(csv_path: Path, warmup_sec: float) -> dict:
    """행 정렬을 유지한 채 (t_recv, overlay_boxes, t_overlay_source_ns)를 읽는다.

    🔴 **왜 `read_frames`의 시계열을 그대로 쓸 수 없나.** 그쪽은 열마다 독립적으로 폐기하므로
    (`discarded[열]`) 반환된 리스트들이 **행 정렬이 아니다.** 조인은 "같은 행의 개수와 게시
    시각"을 묶는 일이라 행 단위 접근이 필요하다. 그래서 CSV를 한 번 더 읽지만, **행 스킵
    규약과 값 가드는 `read_frames`와 글자 그대로 같은 것을 쓰고**, 결과가 어긋나면 호출부가
    하드 에러를 낸다(`_cross_check`) — 두 구현이 갈라지는 것을 사람 눈에 맡기지 않는다.

    🔴 **warmup 행을 버리지 않고 `in_window=False`로 표시만 한다.** 게시 그룹은 창 경계를
    넘어 이어질 수 있고, hold TTL은 **그 게시를 처음 소비한 프레임부터** 세므로, 창 앞에서
    잘라 버리면 그룹 안 위치(= TTL이 몇 번 깎였나)를 틀리게 센다. 분포는 창 안 프레임만 쓴다.
    """
    with csv_path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
        header = list(reader.fieldnames or [])

    frames: list[dict] = []
    rows_skipped = {"unparsable_t_recv": 0, "before_t0": 0}
    boxes_discarded = 0
    source_unparsable = 0
    rows_in_window = 0

    t0 = _to_int(rows[0].get("t_recv_ns")) if rows else MISSING
    cutoff = t0 + int(warmup_sec * 1e9) if t0 != MISSING else MISSING

    for row in rows:
        t_recv = _to_int(row.get("t_recv_ns"))
        # ── read_frames와 **같은 순서·같은 사유**의 스킵 경로. (warmup은 여기서 버리지
        #    않는다 — 위 docstring 참고. 그 대신 in_window 플래그로 표시한다.)
        if t_recv == MISSING:
            rows_skipped["unparsable_t_recv"] += 1
            continue
        if t_recv < t0:
            rows_skipped["before_t0"] += 1
            continue
        in_window = t_recv >= cutoff
        # 🔴 카운트 열의 가드는 `>= 0`이다(`_collect_nonneg`). 시간 열의 `> 0`을 복사하면
        #    **박스 0개 프레임이 전부 폐기된다** — 야간 보행 프레임 대부분이 그렇다.
        box = _to_int(row.get(BOX_COLUMN))
        if not (box >= 0):
            boxes_discarded += 1
            box = MISSING
        source = _to_int(row.get(SOURCE_COLUMN))
        # 🔴 `-1`은 폐기가 아니다 — **아직 어떤 결과도 게시되지 않았다**는 정상값이다
        #    (FRAME_OVERLAY_SOURCE_COLUMNS 주석). 파싱 자체가 안 된 경우와 가른다.
        if source == MISSING and str(row.get(SOURCE_COLUMN, "")).strip() not in ("", str(MISSING)):
            source_unparsable += 1
        if in_window:
            rows_in_window += 1
        frames.append(
            {
                "t_recv_ns": t_recv,
                "boxes": box,
                "source_ns": source,
                "in_window": in_window,
            }
        )

    return {
        "frames": frames,
        "header": header,
        "rows_read": len(rows),
        "rows_skipped": rows_skipped,
        "rows_in_window": rows_in_window,
        "boxes_discarded": boxes_discarded,
        "source_unparsable": source_unparsable,
        "t0_ns": t0,
        "cutoff_ns": cutoff,
    }


def read_detect_rows(csv_path: Path) -> dict:
    """행 정렬을 유지한 채 (t_detect_end_ns, boxes_out)를 읽는다. **런 전체다**(창 컷 없음).

    🔴 **창으로 자르지 않는 이유:** 창 첫 프레임이 소비 중인 게시는 창보다 **먼저** 끝난
    추론에서 나왔을 수 있다. 그 행을 잘라 내면 그 그룹이 "붙일 곳이 없다"가 되어 조인이
    깨진 것처럼 보인다. 창 컷은 분포에만 적용한다(`read_detect`가 그 몫을 한다).
    """
    with csv_path.open("r", encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))

    ends: list[int] = []
    boxes: list[int] = []
    unusable = {"no_end": 0, "no_boxes": 0}
    for row in rows:
        end = _to_int(row.get(DETECT_END_COLUMN))
        box = _to_int(row.get(DETECT_BOX_COLUMN))
        if end == MISSING:
            unusable["no_end"] += 1
            continue
        if not (box >= 0):  # 카운트 열의 가드는 `>= 0`. **0은 정상값이다**
            unusable["no_boxes"] += 1
            continue
        ends.append(end)
        boxes.append(box)

    monotone = all(ends[i] < ends[i + 1] for i in range(len(ends) - 1))
    return {
        "ends": ends,
        "boxes": boxes,
        "rows_read": len(rows),
        "rows_usable": len(ends),
        "unusable": unusable,
        "end_monotone": monotone,
        "boxes_out_sum": sum(boxes),
    }


def build_groups(frames: list[dict]) -> list[dict]:
    """`t_overlay_source_ns` 값이 바뀔 때마다 새 게시 그룹. **연속된 프레임들의 묶음이다.**

    ⚠ 같은 값이 떨어져서 두 번 나타나면 **다른 그룹**이 된다(게시 시각은 단조라 정상 로그에서는
    일어나지 않는다). 그 경우 단사성 검사가 잡는다 — 두 그룹이 같은 detect 행에 붙기 때문이다.
    """
    groups: list[dict] = []
    prev = None
    for i, fr in enumerate(frames):
        src = fr["source_ns"]
        if not groups or src != prev:
            groups.append({"source_ns": src, "indices": []})
            prev = src
        groups[-1]["indices"].append(i)
    return groups


def join_groups(groups: list[dict], detect: dict) -> dict:
    """각 게시 그룹을 `source_ns` **이하의 최대** `t_detect_end_ns` 행에 붙인다.

    🔴 조인 방어선을 전부 여기서 만든다. 하나라도 깨지면 호출부가 잔상 숫자를 내지 않는다.
    """
    ends = detect["ends"]
    boxes = detect["boxes"]
    joined_by_detect: dict[int, list[int]] = {}
    lags_ms: list[float] = []
    no_source_groups = 0
    unjoinable_groups = 0
    negative_lag_groups = 0

    for gi, g in enumerate(groups):
        src = g["source_ns"]
        g["detect_idx"] = None
        g["published_count"] = None
        g["lag_ms"] = None
        if src == MISSING:
            # 🔴 폐기가 아니다 — **첫 추론이 끝나기 전** 프레임들이다(정상값).
            g["reason"] = "before_first_publish"
            no_source_groups += 1
            continue
        idx = bisect.bisect_right(ends, src) - 1
        if idx < 0:
            # 게시 시각이 **어떤 추론의 종료보다도 이르다.** 조인 전제가 깨진 것이며
            # 값을 지어내지 않는다.
            g["reason"] = "no_detect_at_or_before_source"
            unjoinable_groups += 1
            continue
        lag_ns = src - ends[idx]
        if lag_ns < 0:  # bisect의 정의상 도달 불가. 방어선은 도달 불가여도 남긴다.
            negative_lag_groups += 1
        g["detect_idx"] = idx
        g["published_count"] = boxes[idx]
        g["lag_ms"] = lag_ns / 1e6
        lags_ms.append(lag_ns / 1e6)
        joined_by_detect.setdefault(idx, []).append(gi)

    multi = {idx: gs for idx, gs in joined_by_detect.items() if len(gs) > 1}
    joined_idx = sorted(joined_by_detect)
    return {
        "groups_total": len(groups),
        "groups_joined": len(joined_idx),
        "groups_before_first_publish": no_source_groups,
        "groups_unjoinable": unjoinable_groups,
        "negative_lag_groups": negative_lag_groups,
        "injective": not multi,
        "injectivity_violations": [
            {"detect_idx": idx, "group_indices": gs} for idx, gs in sorted(multi.items())
        ],
        "lag_ms": summarize(lags_ms),
        "joined_detect_indices": joined_idx,
        "boxes_out_sum_joined": sum(boxes[i] for i in joined_idx),
    }


def classify_negative(frames: list[dict], groups: list[dict], hold_frames) -> dict:
    """`excess < 0`인 프레임을 **원인별로 가른다**(NOTES['negative_split']).

    분류의 축은 **결손이 시작된 자리**(deficit onset = 그룹 안에서 excess가 `>= 0`에서
    `< 0`으로 넘어간 인덱스)다. 한 게시 안에서 published_count는 상수이므로 excess가 내려가는
    유일한 경로는 **그린 개수가 줄어든 것**이고, 같은 게시를 다시 보는 프레임에는 새 측정이
    오지 않으므로 개수가 줄 수 있는 경로는 **TTL 만료뿐이다**
    (`OverlaySmoother.update`: `isNewPublish`가 false면 `trackMatched`가 전부 false이고
    그 프레임마다 `trackTtl`이 1씩 깎인다).

      - onset == 0            → `pending` (그 게시의 **첫 프레임부터** 모자랐다 = 진입 쪽)
      - onset == hold_frames  → `hold_expiry` (게시를 소비한 프레임에서 TTL이 선언값으로 차고
                                 그 수만큼 깎여 만료된 자리 — 프레임 단위 TTL의 정의 그대로다)
      - 그 밖                 → `unexplained` (설명된 것과 **섞지 않는다**)

    🔴 **"첫 감소"가 아니라 "결손이 시작된 자리"를 보는 이유:** 트랙의 TTL은 게시 경계와
    무관하게 그 트랙이 마지막으로 매칭된 시점부터 깎인다. 그래서 **직전 게시에서 넘어온 낡은
    트랙**이 그룹 중간(예: 인덱스 3)에서 만료될 수 있고, 그 감소는 excess를 `+1 → 0`으로
    되돌릴 뿐 결손을 만들지 않는다. 첫 감소를 기준으로 삼으면 그런 그룹이 통째로
    `unexplained`가 되어, 설명된 사례를 설명되지 않은 것으로 잘못 보고한다.
    🔴 **그룹 안 위치만으로 가르지 않는 이유:** FSM 도입 뒤(TTL이 게시 단위가 된 뒤)의 PENDING
    프레임이 긴 그룹에서 `hold_expiry`로 잘못 분류된다. 그러면 이 스크립트가 안전 회귀 숫자를
    스스로 오염시킨다.
    """
    n = len(frames)
    excess: list = [None] * n
    klass: list = [None] * n
    group_of: list = [None] * n
    # 결손이 시작된 자리 -> 그 onset을 가진 그룹 수. **분류의 근거를 숫자로 남긴다.**
    onset_histogram: dict[int, int] = {}

    for gi, g in enumerate(groups):
        pc = g["published_count"]
        idxs = g["indices"]
        for i in idxs:
            group_of[i] = gi
        if pc is None:
            continue
        onset = None       # 지금 진행 중인 결손이 시작된 그룹 안 인덱스
        for k, i in enumerate(idxs):
            b = frames[i]["boxes"]
            if b == MISSING:
                continue
            e = b - pc
            excess[i] = e
            if e < 0:
                if onset is None:
                    onset = k
                    onset_histogram[k] = onset_histogram.get(k, 0) + 1
                if onset == 0:
                    klass[i] = CLASS_PENDING
                elif hold_frames is not None and onset == hold_frames:
                    klass[i] = CLASS_HOLD_EXPIRY
                else:
                    klass[i] = CLASS_UNEXPLAINED
            else:
                onset = None

    return {
        "excess": excess,
        "class": klass,
        "group_of": group_of,
        "deficit_onset_histogram": {str(k): v for k, v in sorted(onset_histogram.items())},
        "deficit_onset_indices": sorted(onset_histogram),
    }


def episode_block(
    prefix: str,
    mask: list[bool],
    frames: list[dict],
    excess: list,
    group_of: list,
    window_positions: list[int],
    window_sec,
) -> dict:
    """연속한 프레임 묶음(에피소드)의 분포. ghost/pending/hold_expiry가 **같은 구조**를 쓴다.

    에피소드 = 창 안에서 `mask`가 연속으로 참인 최대 구간. 창은 시간 접미사이므로 창 리스트에서
    이웃한 두 항목은 실제로 이웃한 표시 프레임이다.
    """
    episodes = []
    j = 0
    m = len(window_positions)
    while j < m:
        if not mask[window_positions[j]]:
            j += 1
            continue
        start = j
        while j < m and mask[window_positions[j]]:
            j += 1
        idxs = [window_positions[k] for k in range(start, j)]
        episodes.append(
            {
                "frames": len(idxs),
                # 🔴 창 길이를 재는 방식과 같다 — 첫/마지막 프레임의 t_recv_ns 차.
                "ms": round(
                    (frames[idxs[-1]]["t_recv_ns"] - frames[idxs[0]]["t_recv_ns"]) / 1e6, 3
                ),
                "publishes": len({group_of[i] for i in idxs}),
                "box_frames": sum(abs(excess[i]) for i in idxs if excess[i] is not None),
                "first_frame_index": idxs[0],
            }
        )

    window_min = (window_sec or 0.0) / 60.0
    total_frames = sum(e["frames"] for e in episodes)
    return {
        f"{prefix}_episodes": len(episodes),
        f"{prefix}_episodes_per_min": (
            round(len(episodes) / window_min, 3) if window_min > 0 else None
        ),
        f"{prefix}_frames": summarize([e["frames"] for e in episodes]),
        f"{prefix}_frames_unit": "frames (ms가 아니다)",
        f"{prefix}_ms": summarize([e["ms"] for e in episodes]),
        f"{prefix}_ms_note": NOTES["episode_ms_span"],
        f"{prefix}_publishes": summarize([e["publishes"] for e in episodes]),
        f"{prefix}_publishes_unit": "게시 그룹 수 (그 에피소드가 걸친 게시의 개수)",
        f"{prefix}_box_publishes": sum(e["box_frames"] for e in episodes),
        f"{prefix}_box_publishes_unit": (
            "박스·프레임 (Σ|excess|를 프레임에 걸쳐 더한 값). **게시 수도 박스 수도 아니다**"
        ),
        f"{prefix}_box_publishes_per_episode": summarize([e["box_frames"] for e in episodes]),
        f"{prefix}_total_frames": total_frames,
        f"{prefix}_frame_fraction": (
            round(total_frames / m, 4) if m else None
        ),
        f"{prefix}_window_frames": m,
    }


def detector_stability(boxes_out: list[int], window_sec, discarded: int) -> dict:
    """`detect.csv`만으로 — 탐지 자체가 얼마나 자주 끊기는가. **오버레이와 무관하다.**

    🔴 **이 값이 곧 "해제 k=1이 만들 깜빡임"의 사전 예고다.** 해제 조건을 '연속 1회 미탐지면
    즉시 제거'로 두면, 아래 `isolated_dropouts`(게시 하나짜리 끊김)가 그대로 박스가 사라졌다
    다시 나타나는 구간이 된다 — hold가 지금 그것을 덮고 있을 뿐이다.
    ⚠ 그것이 곧 결함이라는 뜻은 아니다(장면에서 물체가 실제로 사라졌을 수 있다 —
    `NOTES['no_ground_truth']`와 같은 사유).
    """
    n = len(boxes_out)
    common = {
        "samples": n,
        "samples_unit": "publishes (프레임이 아니다 — 행 하나 = 추론 1회)",
        "samples_discarded": discarded,
        "threshold": None,
        "threshold_note": NOTES["no_threshold"],
    }
    if n == 0:
        return {
            **common,
            "available": False,
            "reason": (
                f"{DETECT_BOX_COLUMN} 유효 표본이 0개다 — 열이 없거나 전부 폐기됐다. "
                "**'끊김이 없었다'가 아니라 '보지 못했다'다**"
            ),
        }

    runs: list[dict] = []
    i = 0
    while i < n:
        positive = boxes_out[i] > 0
        start = i
        while i < n and (boxes_out[i] > 0) == positive:
            i += 1
        runs.append(
            {"positive": positive, "length": i - start, "enclosed": start > 0 and i < n}
        )
    pos = [r["length"] for r in runs if r["positive"]]
    zero = [r["length"] for r in runs if not r["positive"]]
    zero_enclosed = [r["length"] for r in runs if not r["positive"] and r["enclosed"]]
    isolated = sum(
        1 for r in runs if not r["positive"] and r["enclosed"] and r["length"] == 1
    )
    window_min = (window_sec or 0.0) / 60.0
    return {
        **common,
        "available": True,
        "positive_run_publishes": summarize(pos),
        "zero_run_publishes": summarize(zero),
        "zero_run_publishes_enclosed": summarize(zero_enclosed),
        "runs_total": len(runs),
        "runs_at_edge": sum(1 for r in runs if not r["enclosed"]),
        "zero_publishes": sum(1 for b in boxes_out if b == 0),
        "zero_publish_fraction": round(sum(1 for b in boxes_out if b == 0) / n, 4),
        # 🔴 머리 숫자. `>0, 0, >0` = 게시 하나짜리 끊김.
        "isolated_dropouts": isolated,
        "isolated_dropouts_per_min": (
            round(isolated / window_min, 3) if window_min > 0 else None
        ),
        "isolated_dropouts_note": (
            "🔴 `>0, 0, >0`인 **게시 하나짜리** 탐지 끊김의 횟수다. 해제 조건을 '연속 1회 "
            "미탐지면 제거'로 두면 이 횟수가 곧 박스가 사라졌다 다시 나타나는 구간이 된다 — "
            "지금은 hold가 덮고 있어 화면에 나타나지 않는다. **판정선이 아니라 사전 예고다.**"
        ),
        "companion_metrics": ["zero_publish_fraction", "runs_at_edge"],
    }


def _cross_check(series, detect_series, aligned: dict, detect_rows: dict) -> None:
    """행 정렬 재집계가 `read_frames`/`read_detect`와 **같은 표본**을 봤는지 확인한다.

    🔴 어긋나면 죽는다(호출부가 그 런만 뺀다). 조용히 다른 표본으로 표를 내면 이 스크립트의
    숫자가 다른 스크립트의 같은 열 숫자와 갈라지고, 그건 이 하네스에서 가장 나쁜 실패다.
    """
    problems = []
    if aligned["rows_read"] != series.rows_read:
        problems.append(f"rows_read {aligned['rows_read']} != read_frames {series.rows_read}")
    if aligned["rows_in_window"] != series.rows_used:
        problems.append(
            f"창 안 프레임 {aligned['rows_in_window']} != read_frames rows_used "
            f"{series.rows_used}"
        )
    mine_boxes = [
        fr["boxes"] for fr in aligned["frames"] if fr["in_window"] and fr["boxes"] != MISSING
    ]
    if mine_boxes != [int(b) for b in series.overlay_boxes]:
        problems.append(
            f"{BOX_COLUMN} 수열 불일치 (이쪽 {len(mine_boxes)}개, read_frames "
            f"{len(series.overlay_boxes)}개)"
        )
    if detect_rows["rows_read"] != detect_series.rows_read:
        problems.append(
            f"detect rows_read {detect_rows['rows_read']} != read_detect "
            f"{detect_series.rows_read}"
        )
    # 🔴 창 안 detect 행의 boxes_out 수열이 read_detect와 같아야 한다. 이쪽은 창을 자르지
    #    않으므로(read_detect_rows 주석) 꼬리 len(series) 개를 비교한다 — 창은 시간 접미사다.
    mine_tail = detect_rows["boxes"][len(detect_rows["boxes"]) - len(detect_series.boxes_out):]
    if len(detect_series.boxes_out) and mine_tail != [int(b) for b in detect_series.boxes_out]:
        problems.append(
            f"{DETECT_BOX_COLUMN} 창 꼬리 수열이 read_detect와 다르다 "
            f"(이쪽 {len(mine_tail)}개, read_detect {len(detect_series.boxes_out)}개)"
        )
    if problems:
        raise FrameLogError(
            "행 정렬 재집계가 read_frames/read_detect와 어긋난다 — 가드/스킵 규약이 갈라졌다: "
            + "; ".join(problems)
        )


def analyze_run(csv_path: Path, detect_path: Path, run_dir: Path, warmup_sec: float) -> dict:
    """런 하나 → 결과 블록. 못 낸 런도 **사유를 담은 블록**을 돌려준다(조용히 빠지지 않는다)."""
    block: dict = {
        "run_id": run_dir.name,
        "run_dir": str(run_dir),
        "frames_csv": str(csv_path),
        "detect_csv": str(detect_path),
        "usable": False,
    }

    session_path = run_dir / "session.json"
    if not session_path.exists():
        block["skip_reason"] = "session_json_missing"
        block["skip_detail"] = (
            f"session.json이 없다: {session_path} — 게시 수·박스 총계·폐기 경로를 대조할 재료가 "
            "없으므로 조인이 맞는지 **검산할 수 없다**. 건너뛴다"
        )
        return block
    session = read_session(session_path)
    block.update(_session_stamp(session))
    facts = _overlay_facts(session)
    block["overlay_facts"] = facts

    try:
        series = read_frames(csv_path, warmup_sec=warmup_sec)
        detect_series = read_detect(detect_path, t0_ns=series.t0_ns, warmup_sec=warmup_sec)
    except FrameLogError as exc:
        diag = _warmup_diagnosis(csv_path, warmup_sec)
        exhausted = diag["rows_read"] > 0 and diag["rows_before_cutoff"] >= diag["rows_read"]
        block["skip_reason"] = "warmup_no_rows" if exhausted else "frame_log_error"
        block["skip_detail"] = (
            (
                f"warmup {warmup_sec}s 미달로 **표본 0** — 전체 {diag['rows_read']}행이 모두 "
                f"warmup 구간에 있다(노출 span {diag['span_sec']}s). "
                "빈 표가 아니라 '재지 못했다'다"
            )
            if exhausted
            else f"로그를 읽지 못했다: {exc}"
        )
        block["diagnosis"] = diag
        block["frame_log_error"] = str(exc)
        return block

    missing_cols = [
        c for c in (BOX_COLUMN, SOURCE_COLUMN) if c not in series.overlay_columns_present
    ]
    if missing_cols:
        block["skip_reason"] = "no_overlay_columns"
        block["skip_detail"] = (
            f"frames.csv에 {missing_cols} 열이 없다 — 이 arm은 ④ 오버레이를 그리지 않거나 "
            "v7 이전 로그다. 잔상을 정의할 축이 없으므로 건너뛴다(결함이 아니다)"
        )
        return block

    aligned = read_frame_rows(csv_path, warmup_sec)
    detect_rows = read_detect_rows(detect_path)
    try:
        _cross_check(series, detect_series, aligned, detect_rows)
    except FrameLogError as exc:
        # 🔴 **조용히 넘기는 것이 아니다**: skip_reason으로 남고 ERROR로 찍히며, 그 런은
        #    어느 표에도 서지 않는다. 런 하나의 규약 붕괴가 나머지 전부를 죽이지는 않는다.
        LOG.error("[%s] 교차검사 실패 — 이 런을 표에서 뺀다: %s", block["run_id"], exc)
        block["skip_reason"] = "cross_check_mismatch"
        block["skip_detail"] = str(exc)
        return block

    frames = aligned["frames"]
    groups = build_groups(frames)
    join = join_groups(groups, detect_rows)

    # ── 조인 방어선 ──────────────────────────────────────────────────────
    publish_count = facts["publish_count"]
    boxes_published = facts["boxes_published"]
    checks = {
        "session_keys_present": all(
            facts[f"{k}_key_present"]
            for k in (
                "publish_count", "boxes_published", "rejected_inverted",
                "dropped_over_cap_publish", "dropped_over_cap_smoothing",
                "map_failed_frames",
            )
        ),
        "detect_end_monotone": detect_rows["end_monotone"],
        "detect_rows_all_usable": detect_rows["rows_usable"] == detect_rows["rows_read"],
        "detect_rows_match_publish_count": (
            publish_count is not None and detect_rows["rows_read"] == publish_count
        ),
        "boxes_out_sum_matches_published": (
            boxes_published is not None and detect_rows["boxes_out_sum"] == boxes_published
        ),
        "groups_le_publish_count": (
            publish_count is not None and join["groups_joined"] <= publish_count
        ),
        "injective": join["injective"],
        "no_negative_lag": join["negative_lag_groups"] == 0,
        "no_unjoinable_group": join["groups_unjoinable"] == 0,
        "rejected_inverted_zero": facts["rejected_inverted"] == 0,
        "dropped_over_cap_publish_zero": facts["dropped_over_cap_publish"] == 0,
        "dropped_over_cap_smoothing_zero": facts["dropped_over_cap_smoothing"] == 0,
        "map_failed_frames_zero": facts["map_failed_frames"] == 0,
        "boxes_column_fully_parsed": aligned["boxes_discarded"] == 0,
        "source_column_fully_parsed": aligned["source_unparsable"] == 0,
        "frame_rows_accounted": series.accounting_ok,
    }
    published_count_exact = all(checks.values())
    failed_checks = [k for k, v in checks.items() if not v]

    cadence = summarize(detect_series.detect_cadence_ms)
    lag_p50 = join["lag_ms"]["p50"]
    cadence_p50 = cadence["p50"]
    join_report = {
        **join,
        "checks": checks,
        "failed_checks": failed_checks,
        "published_count_exact": published_count_exact,
        "detect_rows_read": detect_rows["rows_read"],
        "detect_rows_usable": detect_rows["rows_usable"],
        "detect_rows_unusable": detect_rows["unusable"],
        "boxes_out_sum_all_rows": detect_rows["boxes_out_sum"],
        "publish_count_declared": publish_count,
        "boxes_published_declared": boxes_published,
        "detect_rows_unjoined": detect_rows["rows_usable"] - join["groups_joined"],
        "detect_rows_unjoined_note": (
            "🔴 **0이 아닌 것이 곧 결함은 아니다.** 마지막 추론이 마지막 표시 프레임보다 늦게 "
            "끝나면 그 게시는 어느 프레임도 소비하지 못한 채 런이 끝난다(꼬리에서 정상). 그래서 "
            "`boxes_out_sum_joined`는 `boxes_published`보다 작을 수 있고, **총계 검산은 조인된 "
            "행이 아니라 detect.csv 전체 합으로 한다**(`boxes_out_sum_matches_published`)"
        ),
        "lag_vs_cadence": {
            "lag_p50_ms": lag_p50,
            "cadence_p50_ms": cadence_p50,
            "ratio_p50": (
                round(lag_p50 / cadence_p50, 4)
                if lag_p50 is not None and cadence_p50 else None
            ),
            "note": (
                "🔴 **lag에 임계값을 만들지 않는다.** 게시 시각과 추론 종료 시각의 차가 얼마여야 "
                "정상인지에 대한 합의된 값이 없다. 대신 그 런의 탐지 주기 p50과의 **비**를 낸다 — "
                "비가 1에 가까우면 조인이 한 게시씩 밀렸을 수 있다는 신호이지 판정이 아니다"
            ),
        },
        "boxes_discarded": aligned["boxes_discarded"],
        "source_unparsable": aligned["source_unparsable"],
        "frames_before_first_publish": sum(
            len(g["indices"]) for g in groups if g["source_ns"] == MISSING
        ),
    }

    window = series.analysis_window_sec
    sustained = bool(window is not None and window >= targets.SUSTAINED_SEC)
    hold_frames = facts["hold_frames"]

    negative = classify_negative(frames, groups, hold_frames)
    excess = negative["excess"]
    klass = negative["class"]
    group_of = negative["group_of"]
    window_positions = [i for i, fr in enumerate(frames) if fr["in_window"]]

    counts_by_class = {
        c: sum(1 for i in window_positions if klass[i] == c) for c in NEGATIVE_CLASSES
    }
    ghost_mask = [e is not None and e > 0 for e in excess]
    unexplained_frames = counts_by_class[CLASS_UNEXPLAINED]

    metrics: dict = {
        "cadence_ms": cadence,
        "cadence_source": f"read_detect의 {DETECT_CADENCE_SERIES} (창 안 추론만)",
        "excess_series_name": OVERLAY_EXCESS_SERIES,
        "excess_computed_frames": sum(1 for i in window_positions if excess[i] is not None),
        "excess_undefined_frames": sum(1 for i in window_positions if excess[i] is None),
        "negative_class_counts": counts_by_class,
        "hold_frames_declared": hold_frames,
        # 🔴 분류의 **근거를 숫자로** 남긴다: 결손이 시작된 그룹 안 인덱스의 도수분포.
        #    전부 hold 선언값 하나에 몰려 있으면 "프레임 단위 TTL 만료"라는 설명이 그 자리에서
        #    확인된다 — 문장으로 단언하지 않는다.
        "deficit_onset_histogram": negative["deficit_onset_histogram"],
        "deficit_onset_indices": negative["deficit_onset_indices"],
        "all_deficit_onsets_at_hold_frames": (
            negative["deficit_onset_indices"] == [hold_frames]
            if (negative["deficit_onset_indices"] and hold_frames is not None)
            else None
        ),
    }

    if not published_count_exact:
        reason = (
            "🔴 조인 방어선이 깨졌다 — **잔상 숫자를 내지 않는다.** 실패한 검사: "
            + ", ".join(failed_checks)
            + ". published_count를 신뢰할 수 없으면 excess는 두 오차의 합일 뿐이고, "
            "값을 지어내는 것보다 '재지 못했다'가 낫다"
        )
        for prefix in ("ghost", "pending", "hold_expiry"):
            metrics[prefix] = {"available": False, "reason": reason}
    else:
        metrics["ghost"] = {
            "available": True,
            **episode_block(
                "ghost", ghost_mask, frames, excess, group_of, window_positions, window
            ),
            "definition": (
                f"excess > 0 = 그 프레임이 소비 중인 게시에 **없는** 박스를 그리고 있다 "
                f"({BOX_COLUMN} − published_count)"
            ),
            "threshold": None,
            "threshold_note": NOTES["no_threshold"],
            "companion_notes": [
                NOTES["ms_needs_cadence"], NOTES["no_ground_truth"], NOTES["policy_only"],
            ],
        }
        hold_block = {
            "available": True,
            **episode_block(
                "hold_expiry",
                [k == CLASS_HOLD_EXPIRY for k in klass],
                frames, excess, group_of, window_positions, window,
            ),
            "definition": (
                f"excess < 0 이고 그 결손이 그룹 안 인덱스 {hold_frames}(= 선언된 hold_frames)"
                "에서 시작된 프레임. 같은 게시를 다시 보는 프레임에는 새 측정이 오지 않으므로 "
                "개수가 줄 수 있는 경로는 hold TTL 만료뿐이다 (OverlaySmoother.update: "
                "isNewPublish=false면 trackMatched가 전부 false라 그 프레임마다 trackTtl이 "
                "1씩 깎이고, 게시를 소비한 프레임에서 hold_frames로 찼으므로 정확히 그 인덱스에서 "
                "0이 된다)"
            ),
            "onset_index_expected": hold_frames,
            "threshold": None,
            "threshold_note": NOTES["no_threshold"],
        }
        metrics["hold_expiry"] = hold_block
        if unexplained_frames:
            metrics["pending"] = {
                "available": False,
                "reason": (
                    f"🔴 `excess < 0`인 프레임 중 **{unexplained_frames}개를 설명하지 못했다** — "
                    + (
                        "session.json에 `overlay.smoothing.hold_frames`가 없어 게시 안에서 "
                        "줄어든 결손을 프레임 단위 TTL 만료로 **귀속할 수 없다**"
                        if hold_frames is None
                        else f"같은 게시 안에서 줄었는데 결손이 시작된 자리가 hold 선언값 "
                             f"{hold_frames}이 아니다"
                    )
                    + ". 미규명이 섞인 채로 pending 분포를 내면 그 숫자가 곧 안전 회귀 숫자로 "
                    "인용된다 — 내지 않는다"
                ),
                "unexplained_frames": unexplained_frames,
            }
        else:
            metrics["pending"] = {
                "available": True,
                **episode_block(
                    "pending",
                    [k == CLASS_PENDING for k in klass],
                    frames, excess, group_of, window_positions, window,
                ),
                "definition": (
                    "excess < 0 이면서 **그 게시의 첫 프레임부터** 모자란 프레임 = 진입 쪽 결손. "
                    "FSM 도입 전 빌드에서는 0이어야 한다(PENDING 상태가 없다)"
                ),
                "unexplained_frames": 0,
                "threshold": None,
                "threshold_note": NOTES["no_threshold"],
                "companion_notes": [NOTES["negative_split"], NOTES["no_ground_truth"]],
            }

    flicker = overlay_flicker(
        series.overlay_boxes,
        window,
        discarded=sum(series.discarded.get(BOX_COLUMN, {}).values()),
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
            "detect_rows_used": detect_series.rows_used,
            "detect_rows_accounted": detect_series.accounting_ok,
            "analysis_window_sec": (round(window, 3) if window is not None else None),
            "window_meets_sustained": sustained,
            "sustained_sec_target": targets.SUSTAINED_SEC,
            "window_note": (
                "분석 창이 lib/targets.py의 SUSTAINED_SEC를 넘는다"
                if sustained
                else "🔴 분석 창이 lib/targets.py의 SUSTAINED_SEC보다 짧다 — **봉투 점**이다. "
                "지속 성능의 근거로 쓰지 않는다"
            ),
            "join": join_report,
            "published_count_exact": published_count_exact,
            "metrics": metrics,
            "detector_stability": detector_stability(
                [int(b) for b in detect_series.boxes_out],
                window,
                discarded=sum(detect_series.discarded.get(DETECT_BOX_COLUMN, {}).values()),
            ),
            # 🔴 깜빡임은 **재구현하지 않았다** — analyze_frames.overlay_flicker가 주인이다.
            "flicker": flicker,
            "frame_log_warnings": list(series.warnings),
            "detect_log_warnings": list(detect_series.warnings),
        }
    )
    return block


# ── 콘솔 표 ───────────────────────────────────────────────────────────────
def _fmt(v) -> str:
    return "-" if v is None else (f"{v:g}" if isinstance(v, (int, float)) else str(v))


def _cell(stat: dict) -> str:
    """`p50/p95/max (n)`. 🔴 n을 절대 떼지 않는다."""
    if not stat or not stat.get("count"):
        return "표본0"
    return (
        f"{_fmt(stat['p50'])}/{_fmt(stat['p95'])}/{_fmt(stat['max'])} (n={stat['count']})"
    )


def _print_episode(name: str, prefix: str, blk: dict) -> None:
    if not blk.get("available"):
        LOG.warning("    %s: 낼 수 없다 — %s", name, blk.get("reason"))
        return
    LOG.info(
        "    %s: 에피소드 %s회 (%s회/분) | 프레임 p50/p95/max %s | ms %s | 게시 %s | "
        "Σ|excess| %s 박스·프레임 | 창의 %s",
        name,
        blk.get(f"{prefix}_episodes"),
        blk.get(f"{prefix}_episodes_per_min"),
        _cell(blk.get(f"{prefix}_frames")),
        _cell(blk.get(f"{prefix}_ms")),
        _cell(blk.get(f"{prefix}_publishes")),
        blk.get(f"{prefix}_box_publishes"),
        blk.get(f"{prefix}_frame_fraction"),
    )


def _print_run(block: dict) -> None:
    if not block.get("usable"):
        LOG.warning(
            "  [건너뜀] %s — %s / %s",
            block["run_id"], block.get("skip_reason"), block.get("skip_detail"),
        )
        return
    LOG.info(
        "  %s | arm=%s | %s | commit=%s dirty=%s | %s",
        block["run_id"], block.get("render_arm"), block.get("lighting_condition"),
        block.get("git_commit"), block.get("git_dirty"), block.get("build_type"),
    )
    LOG.info(
        "    창 %ss (지속조건 %ss 충족=%s) | 프레임 %s/%s 사용 | 추론 %s행 | 회계=%s/%s",
        block.get("analysis_window_sec"), targets.SUSTAINED_SEC,
        block.get("window_meets_sustained"), block.get("rows_used"), block.get("rows_read"),
        block.get("detect_rows_used"), block.get("rows_accounted"),
        block.get("detect_rows_accounted"),
    )
    if not block.get("window_meets_sustained"):
        LOG.warning("    ⚠ %s", block.get("window_note"))

    join = block.get("join") or {}
    LOG.info(
        "    조인: 게시그룹 %s (붙음 %s / 게시 전 %s / 못 붙음 %s) | detect행 %s = "
        "publish_count %s | Σboxes_out %s = boxes_published %s | 단사=%s",
        join.get("groups_total"), join.get("groups_joined"),
        join.get("groups_before_first_publish"), join.get("groups_unjoinable"),
        join.get("detect_rows_read"), join.get("publish_count_declared"),
        join.get("boxes_out_sum_all_rows"), join.get("boxes_published_declared"),
        join.get("injective"),
    )
    lag = join.get("lag_ms") or {}
    lvc = join.get("lag_vs_cadence") or {}
    LOG.info(
        "    lag(게시시각 − 추론종료) p50/p95/max %s | 탐지주기 p50 %sms | 비 %s "
        "(🔴 임계값 아님)",
        _cell(lag), lvc.get("cadence_p50_ms"), lvc.get("ratio_p50"),
    )
    if join.get("detect_rows_unjoined"):
        LOG.info(
            "    어느 프레임도 소비하지 못한 게시 %s개 — %s",
            join.get("detect_rows_unjoined"), join.get("detect_rows_unjoined_note"),
        )
    if block.get("published_count_exact"):
        LOG.info("    ✔ published_count_exact=true — 잔상 숫자를 낼 수 있다")
    else:
        LOG.error(
            "    🔴 published_count_exact=false (실패: %s) — **잔상 숫자를 내지 않는다**",
            ", ".join(join.get("failed_checks") or []),
        )

    metrics = block.get("metrics") or {}
    LOG.info(
        "    excess 계산됨 %s프레임 / 미정의 %s프레임 | hold_frames 선언값 %s | "
        "excess<0 분류 %s",
        metrics.get("excess_computed_frames"), metrics.get("excess_undefined_frames"),
        metrics.get("hold_frames_declared"), metrics.get("negative_class_counts"),
    )
    _print_episode("잔상(excess>0)", "ghost", metrics.get("ghost") or {})
    _print_episode("hold 만료(게시 안)", "hold_expiry", metrics.get("hold_expiry") or {})
    _print_episode("진입 결손(pending)", "pending", metrics.get("pending") or {})
    LOG.info(
        "    결손이 시작된 자리(그룹 안 인덱스: 횟수) %s — 전부 hold 선언값(%s)인가=%s",
        metrics.get("deficit_onset_histogram"), metrics.get("hold_frames_declared"),
        metrics.get("all_deficit_onsets_at_hold_frames"),
    )

    st = block.get("detector_stability") or {}
    if st.get("available"):
        LOG.info(
            "    탐지 안정성(detect.csv만): 박스>0 런 %s | 박스=0 런 %s | 0인 게시 비율 %s | "
            "**고립 끊김 %s회** (%s회/분)",
            _cell(st.get("positive_run_publishes")), _cell(st.get("zero_run_publishes")),
            st.get("zero_publish_fraction"), st.get("isolated_dropouts"),
            st.get("isolated_dropouts_per_min"),
        )
        LOG.warning("    %s", st.get("isolated_dropouts_note"))
    else:
        LOG.warning("    탐지 안정성: 낼 수 없다 — %s", st.get("reason"))

    fl = block.get("flicker") or {}
    if fl.get("available"):
        LOG.info(
            "    깜빡임(analyze_frames.overlay_flicker): 전이 %s회 | 0인 프레임 비율 %s | "
            "끝에 걸린 0 구간 %s개",
            fl.get("blank_transitions"), fl.get("zero_frame_fraction"),
            fl.get("zero_runs_at_edge"),
        )
    else:
        LOG.warning("    깜빡임: 낼 수 없다 — %s", fl.get("reason"))


def main() -> int:
    parser = common_argparser()
    parser.add_argument("--label", default="", help="이 측정에 붙일 메모")
    parser.add_argument(
        "--frames",
        action="append",
        default=None,
        help=(
            "런 디렉토리 또는 frames.csv 경로. **반복 가능**하다. 같은 디렉토리의 "
            "detect.csv·session.json을 **반드시** 함께 읽는다(없으면 죽거나 건너뛴다)"
        ),
    )
    parser.add_argument(
        "--warmup_sec",
        type=float,
        default=targets.DEFAULT_WARMUP_SEC,
        help=(
            f"첫 N초 제외 (기본값은 lib/targets.py의 DEFAULT_WARMUP_SEC="
            f"{targets.DEFAULT_WARMUP_SEC}). 절단 기준은 read_frames와 같은 "
            "**frames.csv 첫 행의 t_recv_ns**이며 detect.csv에도 같은 t0를 넘긴다"
        ),
    )
    args = parser.parse_args()
    if not args.frames:
        parser.error("--frames를 최소 1개 줘야 한다")

    paths = init_run(stage=STAGE, script_file=__file__, args=args)

    # 경로 오류는 **먼저 죽는다.** 조용히 건너뛰면 오타가 "데이터가 없었다"로 읽힌다.
    # 🔴 데이터 조건(오버레이 열 없음 등)으로 건너뛰는 것과 **다른 부류**라 종료 코드도 다르다.
    try:
        inputs = [resolve_input(raw) for raw in args.frames]
    except FileNotFoundError as exc:
        LOG.error("입력 경로 오류: %s", exc)
        return 2

    blocks = [
        analyze_run(csv_path, detect_path, run_dir, args.warmup_sec)
        for csv_path, detect_path, run_dir in inputs
    ]
    usable = [b for b in blocks if b.get("usable")]
    skipped = [b for b in blocks if not b.get("usable")]
    exact = [b for b in usable if b.get("published_count_exact")]
    commits = sorted({str(b.get("git_commit")) for b in usable})
    arms = sorted({str(b.get("render_arm")) for b in usable})

    summary = {
        "run_ts": paths.run_ts,
        "stage": STAGE,
        "label": args.label,
        "warmup_sec": args.warmup_sec,
        "warmup_sec_default_source": "lib/targets.py:DEFAULT_WARMUP_SEC",
        "warmup_cut_basis": (
            "frames.csv 첫 행의 t_recv_ns (lib/frame_log.py read_frames와 같은 규약. "
            "detect.csv에도 같은 t0를 넘긴다)"
        ),
        "sustained_sec_target": targets.SUSTAINED_SEC,
        "sustained_sec_source": "lib/targets.py:SUSTAINED_SEC",
        "excess_definition": (
            f"excess(frame) = {BOX_COLUMN} − published_count(그 프레임의 {SOURCE_COLUMN}가 "
            f"가리키는 게시의 {DETECT_BOX_COLUMN})"
        ),
        "join_rule": (
            f"{SOURCE_COLUMN} 값이 바뀔 때마다 새 게시 그룹. 그 값 **이하의 최대** "
            f"{DETECT_END_COLUMN} 행에 붙인다 (둘 다 CLOCK_BOOTTIME)"
        ),
        # 🔴 **앱을 한 줄도 고치지 않았다.** 이 지표는 기존 v7 열 셋(overlay_boxes ·
        #   t_overlay_source_ns · detect.csv의 boxes_out)만으로 만든다.
        "csv_columns_added": [],
        "csv_columns_used": [BOX_COLUMN, SOURCE_COLUMN, DETECT_BOX_COLUMN, DETECT_END_COLUMN],
        "schema_note": (
            "CSV 열을 늘리지 않았고 SCHEMA_VERSION도 그대로다. 새로 등록한 것은 **파생 시계열 "
            f"이름 하나**({OVERLAY_EXCESS_SERIES})뿐이며 그것은 CSV 열이 될 수 없다"
        ),
        "notes": NOTES,
        # ── 불리언 (사람이 표를 읽고 판단하게 두지 않는다) ──
        "any_usable_run": bool(usable),
        "all_runs_join_exact": bool(usable) and len(exact) == len(usable),
        "any_run_join_broken": any(not b.get("published_count_exact") for b in usable),
        "any_run_unexplained_negative": any(
            ((b.get("metrics") or {}).get("negative_class_counts") or {}).get(
                CLASS_UNEXPLAINED, 0
            )
            > 0
            for b in usable
        ),
        "any_run_warmup_no_samples": any(
            b.get("skip_reason") == "warmup_no_rows" for b in skipped
        ),
        "all_runs_meet_sustained_window": bool(usable)
        and all(b.get("window_meets_sustained") for b in usable),
        "any_run_dirty_build": any(b.get("git_dirty_is_true") for b in usable),
        "any_run_accounting_broken": any(not b.get("rows_accounted") for b in usable),
        "mixed_builds": len(commits) > 1,
        "mixed_arms": len(arms) > 1,
        # 🔴 판정선을 만들지 않는다. **키를 빼지 않고 명시적 null로 둔다** — 키가 없으면
        #    "판정선이 없다"와 "쓰는 것을 잊었다"가 구분되지 않는다.
        "threshold": None,
        "verdict": None,
        "verdict_note": NOTES["no_threshold"],
        "cross_build": {
            "commits": commits,
            "render_arms": arms,
            "note": NOTES["cross_run_not_pooled"],
        },
        "counts": {
            "inputs": len(inputs),
            "usable": len(usable),
            "skipped": len(skipped),
            "join_exact": len(exact),
        },
        "runs": blocks,
    }

    LOG.info(
        "④ 오버레이 잔상 — 입력 %s런 (사용 %s / 건너뜀 %s / 조인 정확 %s), warmup %ss",
        len(inputs), len(usable), len(skipped), len(exact), args.warmup_sec,
    )
    LOG.info("  정의: %s", summary["excess_definition"])
    LOG.info("  조인: %s", summary["join_rule"])
    for key in ("ms_needs_cadence", "no_ground_truth", "policy_only", "negative_split",
                "excess_is_count", "episode_ms_span", "cross_run_not_pooled", "no_threshold"):
        LOG.warning("  %s", NOTES[key])
    if summary["mixed_builds"]:
        LOG.error(
            "  🔴 이 표의 런들은 같은 빌드가 아니다(커밋 %s) — 잔상 분포를 빼거나 "
            "'줄었다'로 읽지 않는다", " / ".join(commits),
        )
    LOG.info("── 런별")
    for block in blocks:
        _print_run(block)
    if summary["any_run_warmup_no_samples"]:
        LOG.error(
            "🔴 warmup %ss 미달로 **표본 0**이 된 런 %s개 — 빈 표가 아니라 '재지 못했다'다",
            args.warmup_sec,
            sum(1 for b in skipped if b.get("skip_reason") == "warmup_no_rows"),
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
            "잔상을 낼 수 있는 런이 0개다 — 입력 %s런 전부 건너뛰었다. 위 사유를 볼 것",
            len(inputs),
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
