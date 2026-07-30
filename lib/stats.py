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


def summarize(values: Sequence[float]) -> dict:
    """분포 요약. 값이 없으면 count=0인 dict를 돌려주고 죽지 않는다."""
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
        "min": round(ordered[0], 3),
        "max": round(ordered[-1], 3),
        "mean": round(sum(ordered) / len(ordered), 3),
        "p50": round(percentile(ordered, 0.50), 3),
        "p95": round(percentile(ordered, 0.95), 3),
        "p99": round(percentile(ordered, 0.99), 3),
    }


def pct_change(baseline: Optional[float], current: Optional[float]) -> Optional[float]:
    """baseline 대비 변화율(%). 양수 = 값이 커짐(프레임타임이면 악화)."""
    if baseline is None or current is None:
        return None
    if baseline == 0:
        return None
    return round((current - baseline) / baseline * 100.0, 2)
