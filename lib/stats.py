"""분포 집계.

평균만 내는 측정은 목표(p95 < 80ms)를 판정할 수 없다. 항상 분포로 낸다.
백분위 수식은 measure-harness 스킬 §3 규약을 그대로 따른다.
"""

from __future__ import annotations

import math
from typing import Optional, Sequence


def percentile(sorted_values: Sequence[float], p: float) -> float:
    """p는 0.0~1.0. 입력은 **정렬돼 있어야 한다** (호출부 책임).

    nearest-rank 방식. 보간하지 않는 이유: 프레임타임은 실제로 관측된 값이라야
    "이 프레임이 이만큼 걸렸다"고 말할 수 있고, 보간값은 존재한 적 없는 프레임이다.
    """
    if not sorted_values:
        return 0.0
    rank = max(1, min(len(sorted_values), math.ceil(p * len(sorted_values))))
    return sorted_values[rank - 1]


#: 요약 통계의 기본 소수 자릿수. ms 열을 위한 값이다.
#: 🔴 비율 열은 이 값으로 요약하지 않는다 — `lib/frame_log.RATIO_SUMMARY_DIGITS`를 쓴다
#: (3자리로 줄이면 작은 면적이 `0.0`이 되어 "면적 0"으로 읽힌다).
DEFAULT_SUMMARY_DIGITS = 3


def summarize(values: Sequence[float], digits: int = DEFAULT_SUMMARY_DIGITS) -> dict:
    """분포 요약. 값이 없으면 count=0인 dict를 돌려주고 죽지 않는다.

    `digits` — 반올림 자릿수. **기본값 3은 ms 열의 규약**이고 바꾸지 않는다
    (프레임타임은 소수 3자리까지가 의미 있는 자리다).

    🔴 **왜 인자가 생겼나.** 이 함수는 ms만 요약하다가 v8부터 **비율 열**
    (`lib/frame_log.py`의 `FRAME_RATIO_COLUMNS` = `overlay_fill_frac`)도 요약한다.
    그 열은 `docs/FRAME_LOG_SCHEMA.md`의 ④ 오버레이 절이 **앱에 소수 6자리를 요구**하며,
    사유를 이렇게 적어 뒀다 — *"줄이면 작은 박스가 `0.000`이 되고, 하네스는 그 샘플을
    '면적 0'으로 읽는다(폐기되지 않으므로 **보이지도 않는다**)."* 그런데 여기서 3자리로
    반올림하면 앱이 6자리로 정직하게 실어 보낸 값을 **리포트 계층이 되돌려** 같은 사고를
    만든다. `0.000001`이 `0.0`으로 찍히면 그 표를 읽는 사람에게는 "면적이 없었다"이고,
    그건 관측이 아니라 반올림이다. 그래서 자릿수를 **호출부가 물리량에 맞게 정한다.**

    🔴 **별도 요약 함수를 만들지 않는 이유**: 이 파일은 백분위·분포의 **단일 정의**다
    (`overlay_cost_by_boxes.py`가 "세 번째 정의를 만들지 않는다"고 적어 둔 그 정의).
    비율용 `summarize_ratio`를 따로 두면 nearest-rank 규약이 두 벌이 되고, 한쪽만 고쳐도
    아무도 알아채지 못한다. 자릿수는 **표시 규약**이지 통계 규약이 아니므로 인자가 맞다.
    """
    vals = [float(v) for v in values]
    if not vals:
        return {
            "count": 0,
            "min": None,
            "max": None,
            "mean": None,
            "p50": None,
            "p95": None,
            "p99": None,
        }
    ordered = sorted(vals)
    return {
        "count": len(ordered),
        "min": round(ordered[0], digits),
        "max": round(ordered[-1], digits),
        "mean": round(sum(ordered) / len(ordered), digits),
        "p50": round(percentile(ordered, 0.50), digits),
        "p95": round(percentile(ordered, 0.95), digits),
        "p99": round(percentile(ordered, 0.99), digits),
    }


def pct_change(baseline: Optional[float], current: Optional[float]) -> Optional[float]:
    """baseline 대비 변화율(%). 양수 = 값이 커짐(프레임타임이면 악화)."""
    if baseline is None or current is None:
        return None
    if baseline == 0:
        return None
    return round((current - baseline) / baseline * 100.0, 2)
