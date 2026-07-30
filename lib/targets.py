"""판정선 단일 출처.

이 값들은 `FRAME_BUDGET.md` §1에서 온다. 코드 어디에서도 판정선을 다시 적지 않는다 —
숫자가 두 군데 있으면 문서와 코드가 조용히 어긋난다.

⚠️ 여기 있는 값은 **목표에서 유도된 것**뿐이다. 단계별 배분치(A~J 칸)는
`FRAME_BUDGET.md` v0.2에서 전부 제거됐으므로 여기에도 없다. 단계 비용은 실측으로만 말한다.
"""

from __future__ import annotations

# 신청서 성능 목표
TARGET_FPS = 15.0
TARGET_RESOLUTION = (1280, 720)

# 위 두 줄에서 유도
FRAME_BUDGET_MS = 1000.0 / TARGET_FPS  # 66.666...

# 팀원2 제안 (팀 합의 대기 — FRAME_BUDGET.md §1, RESEARCH §10 질문 3·5)
P95_BUDGET_MS = 80.0
SUSTAINED_SEC = 600  # 연속 10분 후에도 만족

# 측정 조건 (android-runtime 스킬 §4)
DEFAULT_WARMUP_SEC = 30.0  # AE/AWB 수렴 전 프레임은 튄다


def meets_fps_target(mean_frame_ms: float) -> bool:
    """평균 처리율이 목표(15 FPS 이상)를 만족하는가."""
    if mean_frame_ms <= 0:
        return False
    return (1000.0 / mean_frame_ms) >= TARGET_FPS


def meets_p95_target(p95_frame_ms: float) -> bool:
    """tail이 관리선 안에 있는가. 평균만으로는 판정할 수 없는 항목."""
    if p95_frame_ms <= 0:
        return False
    return p95_frame_ms < P95_BUDGET_MS


# ── 로그·리포트용 라벨 ────────────────────────────────────────────────────
# 판정선 숫자를 로그 문자열에 박으면 여기를 바꿔도 로그만 낡는다. 라벨도 여기서 만든다.
def fps_target_label() -> str:
    return f"{TARGET_FPS:g}FPS 목표"


def p95_target_label() -> str:
    return f"p95<{P95_BUDGET_MS:g}ms"


def frame_budget_label() -> str:
    return f"프레임당 {FRAME_BUDGET_MS:.1f}ms"
