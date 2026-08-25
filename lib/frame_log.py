"""프레임 로그 스키마 — 폰(PoC)이 뱉고 PC(하네스)가 읽는 형식.

규격 전문은 `docs/FRAME_LOG_SCHEMA.md`. 이 모듈은 그 규격의 실행 가능한 정의다.

파일은 **둘**이다: 매 프레임의 `frames.csv`(read_frames)와, 주기적으로만 도는 ③ 탐지의
`detect.csv`(read_detect, v6). 표본 모집단이 다르므로 파일을 가른다 —
아래 "detect.csv" 절 주석 참고.

설계 원칙 3가지:

1. **유도 가능한 값은 저장하지 않는다.** 프레임타임은 타임스탬프 차이로 계산한다.
   폰이 계산한 값과 PC가 계산한 값이 어긋나면 어느 쪽이 맞는지 알 수 없다.
2. **단조 시계 하나로 통일한다.** `t_*_ns`는 전부 같은 시계(`SystemClock.elapsedRealtimeNanos`)여야
   한다. 예외는 카메라가 주는 `t_capture_ns` 하나이며, 그 기준 시계는 기기마다 다르다.
   v2에서 들어온 GPU 패스 시간(`stage_*_ms` / `gpu_present_ms`)은 애초에 **시각이 아니라
   구간 길이**이고 GPU 시계에서 나온다 — `t_*_ns`와 섞지 않는다(GPU_TIME_COLUMNS 주석).
   v7에서 들어온 `stage_h_ms`도 **시각이 아니라 구간 길이**이지만 시계가 또 다르다 —
   **CPU 벽시계**(GL 스레드)다. GPU 열도 아니고 `t_*_ns`도 아니다
   (FRAME_CPU_TIME_COLUMNS 주석 + 그 아래 상수 자기검사).
3. **없는 값은 -1.** 빈칸이나 0이 아니라 -1로 명시한다. 0은 "0ms 걸렸다"와 구분되지 않는다.
   ⚠ **카운트 열은 예외다** — `overlay_boxes`처럼 개수를 담는 열에서 0은 정상값이고
   (박스가 없는 프레임), -1만이 "기록되지 않았다"다. 폐기 가드가 시간 열과 다르다.
"""

from __future__ import annotations

import csv
import json
import math
from collections import Counter
from dataclasses import dataclass, field
from dataclasses import fields as dataclass_fields
from pathlib import Path
from typing import Optional

from lib.stats import percentile

SCHEMA_VERSION = 7

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
    "stage_b_ms",         # 패스1 OES→오프스크린 720p (버짓 B칸: 색공간 변환/텍스처 업로드)
    # ── D 계열(D-family): D칸을 채우는 열들. 아래 STAGE_D_FAMILY_COLUMNS 참고 ──
    "stage_d_ms",           # ② 저조도 개선 패스 (감마처럼 패스가 하나인 arm)
    "stage_d_analyze_ms",   # ② 입력을 훑어 통계를 만드는 패스 (v3)
    "stage_d_build_ms",     # ② 그 통계로 LUT·계수를 만드는 패스 (v3)
    "stage_d_apply_ms",     # ② 픽셀에 적용하는 패스 (v3)
    "stage_d_denoise_ms",   # ② 노이즈 억제 패스 (v3)
    # 서수 2 = **그 arm의 두 번째 톤커브 스테이지**의 같은 역할 슬롯 (v4). 조합 arm이
    # ② 자리에서 스테이지를 두 번 도는 경우(예: 톤매핑 뒤에 대비 향상)를 담는다.
    # 위 하위 열 바로 뒤에 두어 **읽는 순서가 패스 순서와 같게** 한다.
    "stage_d_analyze2_ms",  # ② 두 번째 스테이지의 통계 산출 패스 (v4)
    "stage_d_build2_ms",    # ② 두 번째 스테이지의 LUT·계수 생성 패스 (v4)
    "stage_d_apply2_ms",    # ② 두 번째 스테이지의 적용 패스 (v4)
    "stage_i_ms",         # ④ 강조 렌더 패스 (버짓 I칸)
    "gpu_present_ms",     # 기본 프레임버퍼에 그린 최종 표시 패스. **버짓 칸이 아니다**
    # ── 프레임 단일 query (v5). **위 열들과 다른 물리량이다** — 아래 GPU_FRAME_COLUMN 참고 ──
    "gpu_frame_ms",
)

# ── 프레임 단일 query (v5) ────────────────────────────────────────────────
# **프레임 하나를 GL_TIME_ELAPSED query 하나로 감싼 값**이다. 위 열들이 "패스 하나"를 재는
# 것과 달리 이 열은 "프레임 전체"를 잰다.
#
# 🔴 **`gpu_sum_ms`에 더하지 않는다**(GPU_SUM_COLUMNS에서 뺀다). 더하면 같은 프레임을 두 번
#   세는 것이다. 🔴 **`stage_d_total_ms`에도 들어가지 않는다** — D 계열이 아니다.
#   🔴 **버짓 칸도 없다**(analyze_frames의 BUDGET_CELL_OF에서 None). 단계 비용이 아니라
#   프레임 전체 GPU 시간이므로 칸 라벨을 붙이면 그 숫자가 D칸에 인용된다.
#
# **한 런에 이 열과 패스별 열이 함께 있을 수 없다.** GL_TIME_ELAPSED는 중첩되지 않으므로
# 같은 프레임에 두 계측을 걸 수 없다 — 프레임 단일 query 런에는 패스별 열이 없고, 반대도
# 같다. 그래도 둘이 함께 온 로그는 **경고한다**(_add_gpu_warnings): 도달 불가한 상태를
# 조용히 통과시키면 둘 중 어느 쪽을 못 믿는지 되물을 근거가 사라진다.
#
# ⚠ **이 값도 여전히 하한이다.** 마지막 전체화면 패스의 타일 해결이 `eglSwapBuffers`에서
#   일어나는데 그 시점은 프레임 단일 query의 **바깥**이다(알려진 이슈 2). 그래서 이 열로
#   재는 것은 "패스별 합의 중복 계상량의 하한"이지 "진짜 GPU 시간"이 아니다.
#
# 폐기 가드는 다른 GPU 열과 **완전히 같다**(하한 `> 0`, 상한 없음).
GPU_FRAME_COLUMN = "gpu_frame_ms"

# ── D 계열 ────────────────────────────────────────────────────────────────
# **D칸을 채우는 열들.** ②를 여러 패스로 쪼개면 GL_TIME_ELAPSED가 중첩되지 않으므로
# **어차피 패스별로 따로 잰다.** 합쳐서 내보내면 정보를 버리는 것이고 §2 "유도값은
# 저장하지 않는다"와도 어긋난다.
#
# ⚠ **arm이 달라도 D칸이 한 지표로 나오게 하는 것**이 이 개념의 목적이다.
#   감마만 쓰는 arm은 `stage_d_ms` 하나, 다패스 arm은 하위 슬롯들 — 양쪽 다
#   `stage_d_total_ms`(아래)가 그 런의 D다. 소비자가 arm별로 어느 열을 더할지
#   판단할 필요가 없어야 한다.
#
# ⚠ **하위 열 이름은 알고리즘이 아니라 '슬롯의 역할'이다.** 처음에는 CLAHE 구성을 그대로
#   따서 hist/cdf/apply로 지었는데, 그러면 Drago의 **최대휘도 리덕션**이 `stage_d_hist_ms`에
#   들어가 D칸 분해를 읽는 사람이 "히스토그램이 비싸다"고 **오독**한다. `[D칸]` 라벨에 arm을
#   묶어 둔 이유와 정확히 같은 계열의 문제라서, 실측이 0건인 지금 arm 중립 이름으로 바꿨다.
#
#     analyze : 입력을 훑어 통계를 만든다 (CLAHE 히스토그램 / Drago·Reinhard 리덕션 /
#               LIME 조도맵 추정)
#     build   : 그 통계로 LUT·계수를 만든다 (CLAHE 클립+CDF / AGCWD 가중 LUT). 없는 arm도 있다
#     apply   : 픽셀에 적용한다 (LUT 보간+감마 / 톤맵 / 나눗셈)
#     denoise : 노이즈 억제 (bilateral 계열, `+bf` arm)
#
#   **이름 끝의 서수(`2`)는 '그 arm의 두 번째 톤커브 스테이지의 같은 역할 슬롯'이다** (v4).
#   조합 arm은 ② 자리에서 스테이지를 두 번 돌기 때문에(예: Drago로 톤을 누른 뒤 CLAHE로
#   국소 대비를 올린다) analyze/build/apply 슬롯이 각각 두 번 필요하다. 서수는 **순서**만
#   말하고 알고리즘을 말하지 않는다 — 어느 스테이지가 무엇이었는지는 위와 똑같이
#   `session.json`의 `render.passes[]`가 선언한다.
#   ⚠ 앱이 두 스테이지를 **합쳐서 한 슬롯에 넣지 않는다.** 합치면 유도값이고(§2), 어느
#     스테이지가 비싼지가 사라져 경량화 레버를 고를 수 없다.
#   ⚠ `stage_d_ms`를 두 번째 스테이지에 재사용하지 않는다 — 그러면 `stage_d_ambiguous`
#     (하위 열과 `stage_d_ms`가 동시에 있는 모호 경로)에 걸려 이중 계상 경고가 붙는다.
#   ⚠ `stage_d_denoise_ms`로 대신하지 않는다 — 그 이름은 bilateral(`+bf`) 전용 역할이라
#     톤커브 스테이지를 담으면 D칸 분해를 읽는 사람이 오독한다(hist/cdf 이름을 버린 이유와
#     같은 부류).
#
#   **그 arm에서 이 슬롯이 구체적으로 무엇이었는지는 앱이 `session.json`의
#   `render.passes[]`(각 항목의 `gpu_column`)에 선언한다.** 열 이름은 역할이고, 의미는
#   세션이 말한다 — 하네스는 여기서도 arm을 해석하지 않는다.
STAGE_D_FAMILY_COLUMNS = (
    "stage_d_ms",
    "stage_d_analyze_ms",
    "stage_d_build_ms",
    "stage_d_apply_ms",
    "stage_d_denoise_ms",
    "stage_d_analyze2_ms",
    "stage_d_build2_ms",
    "stage_d_apply2_ms",
)

# D 계열의 **행별 합** (파생 시계열. CSV 열이 아니다).
# ⚠ `gpu_sum_ms`와 다르다 — `gpu_sum_ms`는 **모든** GPU 열의 합(B + D계열 + I + present)이고
#   이쪽은 **D 계열만**의 합이다. 이름이 비슷해 보이면 둘 중 하나를 D칸에 잘못 옮기게 되므로
#   요약·리포트 양쪽에서 항상 "무엇을 더한 값인지"를 함께 낸다.
# ⚠ **백분위의 합이 아니라 행별 합이다.** p50(hist)+p50(cdf) != p50(hist+cdf).
STAGE_D_TOTAL_COLUMN = "stage_d_total_ms"

# CSV 열이 아니라 하네스가 만드는 파생 시계열. 폐기 사유 문장을 고를 때 GPU 열과 같이 본다.
GPU_DERIVED_SERIES = ("gpu_sum_ms", STAGE_D_TOTAL_COLUMN)

# ══ ④ 오버레이 열 (v7) — frames.csv이지만 **GPU 열이 아니다** ═══════════════
# ③ 탐지 결과를 ④ 오버레이에 이어 붙이는 라운드에서 들어온 열 3개다. 세 열 모두 프레임당
# 1행(frames.csv)이지만 **물리량과 시계가 위 GPU 열들과 다르다.**
#
# 🔴 **`stage_h_ms`는 CPU 벽시계다**(`SystemClock.elapsedRealtimeNanos`, GL 스레드).
#   `detect.csv`의 E·F·G와 **같은 부류**이고 GPU 시계가 아니다 — 그래서
#   `GPU_TIME_COLUMNS`·`GPU_SUM_COLUMNS`·`STAGE_D_FAMILY_COLUMNS` **어디에도 넣지 않는다.**
#   한 번 섞이면 `gpu_sum_ms`에 **CPU 벽시계와 GPU query를 더한 숫자**가 담겨 버짓표로
#   나가는데, 결과만 보면 그럴듯해서 사람 눈으로는 걸러지지 않는다. E·F·G를 별 dict·별
#   registry로 둔 선례(위 detect.csv 절)를 그대로 따르고, 아래 상수 자기검사가 import
#   시점에 막는다.
#   ⚠ **합계 열을 만들지 않는다.** H는 열 하나이며, 이 열을 다른 열과 더하는 파생 시계열은
#     없다(`gpu_sum_ms`도 `stage_d_total_ms`도 아니다).
#   ⚠ **앱에 요구하는 정밀도: ms 소수 3자리 이상.** 평활이 싸면 H가 진짜로 `0.0x ms`인데
#     소수 1자리로 쓰면 `0.0`이 되고, 하한 가드(`> 0`)가 **가장 싼 샘플만 골라 폐기**해
#     분포가 위로 치우친다(E·F·G에 같은 요구가 있는 이유와 글자 그대로 같다).
FRAME_CPU_TIME_COLUMNS = (
    "stage_h_ms",   # H칸: ④ 좌표 평활·hold의 CPU 벽시계 구간 (GL 스레드)
)

# ── 카운트 열 (단위 개, int) ──────────────────────────────────────────────
# 🔴 **폐기 하한이 `>= 0`이다**(시간 열의 `> 0`이 아니다). 0은 정상값이다 — "그 프레임에
#   그린 박스가 없었다". 시간 열의 가드를 복사하면 **박스 0개 프레임이 전부 폐기로 세어져**
#   (a) 분포가 위로 치우치고 (b) 폐기 카운트가 프레임 수만큼 튀어 진짜 결손을 덮는다.
#   `detect.csv`의 `DETECT_COUNT_COLUMNS`와 같은 취급이며 수집도 `_collect_nonneg`로 한다.
#
# 🔴 **이 열이 없으면 I칸·H칸이 "조건 없는 숫자"가 된다.** 오버레이 비용은 박스 개수에 딸린
#   양이므로(`docs/FRAME_LOG_SCHEMA.md` §5의 `overlay.box_count` 규약과 같은 논거)
#   `stage_i_ms`·`stage_h_ms`를 인용할 때 이 분포를 함께 옮긴다.
#   ⚠ `session.json`의 `overlay.box_count`와 **다른 값이다.** 그쪽은 arm이 선언한 조건
#     (정적 더미 박스 개수)이고 이쪽은 **그 프레임에 실제로 그린 개수**다 — ③ 탐지 결과를
#     받기 시작하면 프레임마다 달라진다.
FRAME_COUNT_COLUMNS = (
    "overlay_boxes",   # 그 프레임에 실제로 그린 박스 수. **0은 정상값이다**
)

# ── 오버레이가 사용한 탐지 결과의 **게시 시각** ────────────────────────────
# `t_*_ns` 규약을 그대로 따른다: int ns, `CLOCK_BOOTTIME`
# (`SystemClock.elapsedRealtimeNanos`), 즉 `t_recv_ns`·`t_render_start_ns`와 **같은 시계**다.
#
# 🔴 **신선도(= `t_render_start_ns − t_overlay_source_ns`)를 CSV에 저장하지 않는다.**
#   유도값은 저장하지 않는다는 파일 상단 §1 규약이고, 하네스가 계산한다
#   (아래 OVERLAY_FRESHNESS_SERIES).
# 🔴 **박스가 0개여도 게시 시각을 적는다.** "탐지가 아무것도 못 찾았다"는 것도 게시된 결과다 —
#   그때 -1을 적으면 "결과가 없다"와 "빈 결과가 있다"가 구분되지 않고, 신선도 분포가
#   **박스가 있는 프레임 쪽으로만** 치우친다. -1은 **아직 어떤 결과도 게시되지 않았다**
#   (첫 추론 완료 전)일 때만이다.
FRAME_OVERLAY_SOURCE_COLUMNS = (
    "t_overlay_source_ns",
)

# 위 셋 = v7에서 frames.csv에 늘어난 열 전부. 헤더 탐색·버전 등록·요약 블록이 이 목록을 쓴다.
FRAME_OVERLAY_COLUMNS = (
    FRAME_CPU_TIME_COLUMNS + FRAME_COUNT_COLUMNS + FRAME_OVERLAY_SOURCE_COLUMNS
)

# ── 오버레이 신선도 (파생 시계열. **CSV 열이 아니다**) ─────────────────────
# **무엇인가:** `t_render_start_ns − t_overlay_source_ns`. 그 프레임이 그린 박스가 **얼마나
# 오래된 탐지 결과**인지다. 같은 시계 두 시각의 차이므로 뺄 수 있다.
#
# 🔴 **버짓 칸이 없다.** 단계 비용이 아니라 결과의 나이다. 칸 라벨을 붙이면 그 숫자가
#   H칸이나 I칸에 인용된다(`detect_wall_ms`·`gpu_frame_ms`와 같은 이유로 소비자 쪽 매핑에는
#   **명시적 None**을 적는다 — 키를 빼면 "칸이 없다"와 "등록을 잊었다"가 구분되지 않는다).
# ⚠ **탐지 주기(`detect_cadence_ms`)와 다른 값이다.** 주기는 추론이 얼마나 자주 도는가이고
#   이쪽은 표시 프레임이 그중 어느 것을 쓰고 있었나다 — 모집단도 다르다(추론 vs 프레임).
#   탐지가 3.4Hz인데 표시가 30FPS면 같은 결과가 여러 프레임에 걸쳐 쓰이므로, 이 분포는
#   0부터 주기까지 톱니 모양으로 퍼진다. **두 분포를 더하거나 비교해 빼지 않는다.**
OVERLAY_FRESHNESS_SERIES = "overlay_freshness_ms"

# ── frames.csv 쪽 **파생 시계열 이름 전부** (CSV 열이 아니다) ───────────────
# 🔴 **이 이름들은 CSV 열이 될 수 없다.** 하네스가 계산하는 값이고, 폰이 같은 이름으로 열을
#   내면 폰이 계산한 값과 PC가 계산한 값이 어긋날 때 어느 쪽이 맞는지 알 수 없다
#   (파일 상단 §1 "유도 가능한 값은 저장하지 않는다").
#
# 왜 목록으로 두는가: 앱이 실수로 이 이름을 헤더에 실으면 미지 열 경고가 나가는데, 그 경고의
# 일반 문구는 "의도한 새 열이라면 OPTIONAL_COLUMNS에 등록하라"고 권한다 — **그 권고를 따르면
# 아래 자기검사가 import를 죽인다.** 즉 따라갈 수 없는 조언을 자신 있게 하는 상태가 된다.
# 그래서 이 이름들은 `_add_unknown_column_warnings`가 **다른 문장**으로 다룬다.
FRAME_DERIVED_SERIES = GPU_DERIVED_SERIES + (
    OVERLAY_FRESHNESS_SERIES,
    "capture_to_recv_ms",
    "capture_to_render_ms",
    "recv_interval_ms",
    "output_interval_ms",
    "render_latency_ms",
    "recv_to_render_ms",
    # ⚠ 위 recv_to_render_ms와 **한 글자 차이인데 다른 물리량**이다(render start까지 vs
    #   render end까지). FrameSeries 필드 주석에 셋의 관계가 적혀 있다.
    "recv_to_render_start_ms",
)

# 각 열이 **어느 스키마 버전에서 들어왔는가.** 옛 세션(선언 버전 < 하네스 버전)에 경고를 낼 때
# "그 로그에 없을 수 있는 열"을 정확히 짚기 위해 쓴다 — 버전마다 문장을 손으로 고치면
# v4에서 v2 문구가 그대로 남는다.
COLUMN_ADDED_IN = {
    "stage_b_ms": 2,
    "stage_d_ms": 2,
    "stage_i_ms": 2,
    "gpu_present_ms": 2,
    "stage_d_analyze_ms": 3,
    "stage_d_build_ms": 3,
    "stage_d_apply_ms": 3,
    "stage_d_denoise_ms": 3,
    "stage_d_analyze2_ms": 4,
    "stage_d_build2_ms": 4,
    "stage_d_apply2_ms": 4,
    GPU_FRAME_COLUMN: 5,
    # v7 — ④ 오버레이 열 3개. **GPU 열이 아니지만 frames.csv 열이므로 여기 등록한다**
    # (이 dict의 소비자는 `check_schema_version`이고, 그것이 나열해야 하는 것은 "그 옛 로그에
    #  없을 수 있는 frames.csv 열"이다. GPU 여부는 그 질문과 무관하다 — 빼면 v6 세션에
    #  "늘어난 것: 없음"이라는 거짓 안심을 보낸다).
    "stage_h_ms": 7,
    "overlay_boxes": 7,
    "t_overlay_source_ns": 7,
}

# COLUMN_ADDED_IN이 **덮어야 하는 frames.csv 열 전부.** GPU 열만이 아니다(v7에서 CPU 벽시계
# 열·카운트 열·시각 열이 늘었다) — 아래 자기검사의 기준이며, 목록을 여기서 한 번 파생시켜
# 두면 다음 버전에서 검사 대상을 손으로 늘리는 것을 잊을 수 없다.
# ⚠ v1 열(`t_capture_ns`·`t_render_*_ns`·`dropped_since_last`)은 들어가지 않는다. 그 열들은
#   스키마 첫 버전부터 있었으므로 "선언 버전 이후에 늘어난 열"이 될 수 없다.
VERSIONED_FRAME_COLUMNS = GPU_TIME_COLUMNS + FRAME_OVERLAY_COLUMNS

# ── 상수 자기검사 ─────────────────────────────────────────────────────────
# **등록을 빠뜨리면 조용히 틀린다.** 다음 버전에서 열만 추가하고 COLUMN_ADDED_IN에
# 안 넣으면, "앱이 뒤처졌다" 경고가 그 열을 **말없이 빼먹은 채** 성공을 보고한다
# (그 경고를 보고 "내 로그에 다 있다"고 판단하게 된다). 상수끼리의 불변식이므로 데이터와
# 무관하며, 깨지는 순간은 개발자가 상수를 고친 그 편집 시점이다 — 그래서 import에서 죽인다
# (중복 헤더를 하드 에러로 만든 것과 같은 부류: 틀린 결과가 채택되는 것보다 낫다).
_missing_version = [c for c in VERSIONED_FRAME_COLUMNS if c not in COLUMN_ADDED_IN]
_stray_version = [c for c in COLUMN_ADDED_IN if c not in VERSIONED_FRAME_COLUMNS]
if _missing_version or _stray_version:
    raise RuntimeError(
        "lib/frame_log.py 상수 불일치 — COLUMN_ADDED_IN과 VERSIONED_FRAME_COLUMNS가 "
        f"어긋난다: 버전 미등록 열={_missing_version}, 버전 대상이 아닌 항목={_stray_version}. "
        "열을 추가할 때 두 목록에 **함께** 등록해야 '앱이 뒤처졌다' 경고가 빠진 열을 "
        "정확히 나열한다 (docs/FRAME_LOG_SCHEMA.md §6)"
    )
_missing_family = [c for c in STAGE_D_FAMILY_COLUMNS if c not in GPU_TIME_COLUMNS]
if _missing_family:
    raise RuntimeError(
        f"lib/frame_log.py 상수 불일치 — STAGE_D_FAMILY_COLUMNS가 GPU_TIME_COLUMNS의 "
        f"부분집합이 아니다: {_missing_family}. D 계열은 CSV에서 읽히는 GPU 열이어야 "
        f"{STAGE_D_TOTAL_COLUMN} 합산에 들어간다"
    )

# `gpu_sum_ms`(파생 시계열)에 들어가는 열.
# gpu_present_ms를 **포함한다**: 최종 표시 패스도 실제로 GPU를 점유하는 시간이고,
# "이 프레임이 GPU를 몇 ms 잡았나"에서 그것만 빼면 총량이 과소평가된다. 버짓 칸이
# 아니라는 것은 A~J 매핑이 없다는 뜻이지 비용이 아니라는 뜻이 아니다.
# (칸별 비용을 보고 싶으면 stage_* 시계열을 각각 보면 된다.)
#
# 🔴 **`gpu_frame_ms`만 뺀다** (v5). 그 열은 프레임 하나를 query 하나로 감싼 값이라
#   패스별 합에 더하면 같은 프레임을 두 번 세는 것이다. "GPU 열이면 다 더한다"가 아니라
#   "패스별 열을 더한다"가 이 합의 정의다.
GPU_SUM_COLUMNS = tuple(c for c in GPU_TIME_COLUMNS if c != GPU_FRAME_COLUMN)

# ── 상수 자기검사 (v5) ────────────────────────────────────────────────────
# 위 블록과 같은 부류다 — 상수끼리의 불변식이므로 데이터와 무관하고, 깨지는 순간은 개발자가
# 상수를 고친 그 편집 시점이다. 여기서 죽이지 않으면 `gpu_frame_ms`가 조용히 `gpu_sum_ms`나
# `stage_d_total_ms`에 섞여 **프레임을 두 번 센 숫자**가 버짓표로 나간다.
_frame_col_errors = []
if GPU_FRAME_COLUMN not in GPU_TIME_COLUMNS:
    _frame_col_errors.append(f"{GPU_FRAME_COLUMN}이 GPU_TIME_COLUMNS에 없다(집계되지 않는다)")
if GPU_FRAME_COLUMN in GPU_SUM_COLUMNS:
    _frame_col_errors.append(
        f"{GPU_FRAME_COLUMN}이 GPU_SUM_COLUMNS에 있다 — 프레임 전체 query를 패스별 합에 "
        f"더하면 같은 프레임을 두 번 센다"
    )
if GPU_FRAME_COLUMN in STAGE_D_FAMILY_COLUMNS:
    _frame_col_errors.append(
        f"{GPU_FRAME_COLUMN}이 STAGE_D_FAMILY_COLUMNS에 있다 — D 계열이 아니라 프레임 "
        f"전체 GPU 시간이며 D칸에 들어가면 ② 비용이 부풀려진다"
    )
if _frame_col_errors:
    raise RuntimeError(
        "lib/frame_log.py 상수 불일치 — 프레임 단일 query 열의 성질이 어긋난다: "
        + "; ".join(_frame_col_errors)
        + " (docs/FRAME_LOG_SCHEMA.md §2 '프레임 단일 query')"
    )

# 있으면 쓰고 없으면 건너뛰는 열
OPTIONAL_COLUMNS = (
    "t_capture_ns",       # 카메라 ImageInfo.timestamp — 기준 시계 불명확 (§시계 함정)
    "t_render_start_ns",
    "t_render_end_ns",
    "dropped_since_last",  # 백프레셔로 버려진 프레임 수
) + GPU_TIME_COLUMNS + FRAME_OVERLAY_COLUMNS

# 위 두 목록에 없는 열 = 하네스가 읽지 않는 열. 하드 에러로 만들지 않는다(앱이 스키마보다
# 앞서 나갈 수 있다) 대신 **반드시 경고한다.** 행 단위 회계(accounting_ok)에 해당하는
# 방어선이 열 단위에도 있어야 하는 이유:
#   앱이 t_render_end_ns를 t_render_ns로 오타 내면 optional 열이 "그냥 없는 것"으로
#   처리되어 output_interval_ms.count == 0이 되고, 우리는 "출력 타임라인이 없다"고
#   **잘못** 결론 낸다. 오타는 없는 것과 다르다.
KNOWN_COLUMNS = tuple(REQUIRED_COLUMNS) + tuple(OPTIONAL_COLUMNS)

MISSING = -1

# ── 상수 자기검사 (v7) ────────────────────────────────────────────────────
# 위 v5 블록(`gpu_frame_ms`)과 **같은 부류이며 같은 모양으로 짠다** — 상수끼리의 불변식이라
# 데이터와 무관하고, 깨지는 순간은 개발자가 상수를 고친 그 편집 시점이다. 그래서 import에서
# 죽인다. 여기서 막는 사고는 둘 다 조용하다:
#   1) `stage_h_ms`가 GPU 목록에 섞이면 `gpu_sum_ms`에 **CPU 벽시계 + GPU query**를 더한
#      숫자가 담기고(그리고 D 계열에 섞이면 ④ 비용이 ② 비용으로 계상되고), 결과 숫자만
#      보면 그럴듯해서 사람 눈으로는 걸러지지 않는다.
#   2) `overlay_boxes`가 시간 열 목록에 섞이면 하한 `> 0` 가드가 걸려 **박스 0개 프레임이
#      전부 폐기**되고, 그 편향은 폐기 카운트에만 남아 "왜 오버레이 분포가 이런가"를 되물을
#      때 보이지 않는다.
_overlay_col_errors = []
for _c in FRAME_OVERLAY_COLUMNS:
    if _c in GPU_TIME_COLUMNS:
        _overlay_col_errors.append(
            f"{_c}이 GPU_TIME_COLUMNS에 있다 — v7 오버레이 열은 CPU 벽시계 구간 길이/개수/"
            f"시각이고 GPU 시계가 아니다(물리량이 다르다)"
        )
    if _c in GPU_SUM_COLUMNS:
        _overlay_col_errors.append(
            f"{_c}이 GPU_SUM_COLUMNS에 있다 — CPU 시계(또는 개수)를 GPU 패스별 합에 더한 "
            f"숫자가 gpu_sum_ms로 버짓표에 나간다"
        )
    if _c in STAGE_D_FAMILY_COLUMNS:
        _overlay_col_errors.append(
            f"{_c}이 STAGE_D_FAMILY_COLUMNS에 있다 — ④ 오버레이 비용이 D칸(②)에 계상되어 "
            f"② 비용이 부풀려진다"
        )
    if _c in GPU_DERIVED_SERIES:
        _overlay_col_errors.append(
            f"{_c}이 GPU_DERIVED_SERIES에 있다 — 파생 시계열 이름과 CSV 열 이름이 겹치면 "
            f"폐기 사유 문장이 GPU 쪽으로 뽑혀 폰 쪽이 엉뚱하게 시계 코드를 뒤진다"
        )
    if _c not in OPTIONAL_COLUMNS:
        _overlay_col_errors.append(
            f"{_c}이 OPTIONAL_COLUMNS에 없다 — 미지 열로 처리되어 집계에서 통째로 버려진다"
        )
for _c in FRAME_COUNT_COLUMNS:
    if _c in FRAME_CPU_TIME_COLUMNS:
        _overlay_col_errors.append(
            f"{_c}이 FRAME_CPU_TIME_COLUMNS에 있다 — 카운트 열에 시간 열의 하한(`> 0`)이 "
            f"걸리면 **박스 0개 프레임이 전부 폐기**된다(0은 정상값이다)"
        )
_derived_as_column = [c for c in FRAME_DERIVED_SERIES if c in KNOWN_COLUMNS]
if _derived_as_column:
    _overlay_col_errors.append(
        f"{_derived_as_column}이 CSV 열 목록에 있다 — 이 이름들은 **하네스가 계산하는 파생 "
        f"시계열**이라 저장하지 않는다(파일 상단 §1). 폰이 같은 이름으로 열을 내면 폰이 계산한 "
        f"값과 PC가 계산한 값이 어긋날 때 어느 쪽이 맞는지 알 수 없다"
    )
if _overlay_col_errors:
    raise RuntimeError(
        "lib/frame_log.py 상수 불일치 — v7 오버레이 열의 성질이 어긋난다: "
        + "; ".join(_overlay_col_errors)
        + " (docs/FRAME_LOG_SCHEMA.md §2 '④ 오버레이 열')"
    )

# ══ detect.csv — ③ 탐지 계측 (v6) ═════════════════════════════════════════
# **frames.csv와 별 파일이다.** 탐지는 매 프레임이 아니라 주기적으로만 돈다. E·F·G를
# frames.csv의 열로 넣으면 탐지가 돌지 않은 프레임의 행이 전부 -1로 채워지고, "백분위를
# 낼 때 -1을 걸러낸다"는 책임이 **모든 소비자에게** 퍼진다 — 한 곳만 빠뜨리면 p50이 조용히
# -1로 오염된다. 애초에 표본 모집단이 다르므로(프레임 수 ≫ 추론 수) 파일을 가른다.
#
# 🔴 **E·F·G는 CPU 벽시계(`SystemClock.elapsedRealtimeNanos`) 구간 길이이고 GPU 시계가
#   아니다.** GPU 패스 시간 열들과 **물리량이 다르므로** `gpu_sum_ms`·`stage_d_total_ms`에
#   섞이면 안 된다. 섞이는 순간 CPU 시계와 GPU 시계를 더한 숫자가 버짓표로 나간다 —
#   아래 자기검사가 그것을 import 시점에 막는다.
#
# 🔴 **행의 단위는 '추론 1회'다.** `detect_idx`는 추론 시퀀스 번호이며 **프레임 번호가
#   아니다**(frames.csv의 `frame_idx`와 조인할 수 없다). 두 파일을 잇는 것은 시각
#   (`t_detect_recv_ns` ↔ `t_recv_ns`, 같은 CLOCK_BOOTTIME)이다.
DETECT_REQUIRED_COLUMNS = (
    "detect_idx",        # 추론 시퀀스 번호. **프레임 번호가 아니다**
    "t_detect_recv_ns",  # 탐지 스레드가 프레임을 받은 시각. frames.csv의 t_recv_ns와 같은 시계
)

# ── E·F·G 구간 길이 (단위 ms(float), **CPU 벽시계**) ──────────────────────
# 버짓 E·F·G 칸을 채울 열이다. 값의 뜻은 각 주석 참고.
# ⚠ 여기에 **합계 열을 만들지 않는다.** 총 소요는 `t_detect_end_ns - t_detect_recv_ns`로
#   유도 가능하고, 파일 상단 §2가 "유도 가능한 값은 저장하지 않는다"고 정했다
#   (하네스가 만드는 파생 시계열은 DETECT_WALL_SERIES — CSV 열이 아니고, E+F+G의 합도 아니다).
DETECT_TIME_COLUMNS = (
    "stage_e_ms",   # letterbox + RGB 변환 + NCHW 텐서화 (버짓 E칸)
    "stage_f_ms",   # ORT session.run() 1회 (버짓 F칸)
    "stage_g_ms",   # conf 필터 + cxcywh→xyxy + 클래스별 NMS + letterbox 역변환 (버짓 G칸)
)

# ── 카운트 열 (단위 개, int) ──────────────────────────────────────────────
# ⚠ **시간 열과 폐기 가드가 다르다.** 0은 정상값이다("박스가 없었다") — 시간 열의 하한
#   `> 0`을 그대로 쓰면 박스 0개인 추론이 전부 폐기로 세어져 분포가 위로 치우친다.
#   그래서 카운트는 `>= 0`으로 받는다(_collect_nonneg). 기록되지 않은 값만 -1이다.
DETECT_COUNT_COLUMNS = (
    "boxes_pre_nms",       # conf 임계 통과 후 **NMS 전** 박스 수. G 비용의 설명 변수다
    "boxes_out",           # 최종 박스 수
    "skipped_while_busy",  # 그 시점까지의 **누적**. 탐지 스레드가 바빠 건너뛴 프레임 수
)

# ── 카운트 열 중 **누적값**인 것 ──────────────────────────────────────────
# 단조 증가 수열이라 백분위가 뜻이 없다 — 분포를 내지 않고 마지막 값 하나만 남긴다.
# 그래서 `read_detect`의 수집 경로가 나머지 카운트 열과 **다르다**(DetectSeries에 리스트가
# 아니라 `<열>_total` / `<열>_rows` / `<열>_regressions` 세 필드를 갖는다).
DETECT_CUMULATIVE_COLUMNS = ("skipped_while_busy",)

# 분포를 내는 카운트 열 = 카운트 열에서 누적값을 뺀 것. **여기서 파생시킨다.**
# 🔴 `read_detect` 안에 `("boxes_pre_nms", "boxes_out")` 같은 리터럴을 두지 않는다 —
#   그러면 다음 사람이 DETECT_COUNT_COLUMNS에 열을 추가하고 아래 자기검사를 전부 통과시켜도
#   그 값이 조용히 사라지고, `count == 0`과 "열이 없다"가 구분되지 않는다
#   (KNOWN_COLUMNS 주석이 경계하는 실패와 같은 부류다).
DETECT_DISTRIBUTION_COUNT_COLUMNS = tuple(
    c for c in DETECT_COUNT_COLUMNS if c not in DETECT_CUMULATIVE_COLUMNS
)

# 점수 열. 카운트와 폐기 가드는 같지만(하한 `>= 0`) **개수가 아니라 점수**라 이름을 가른다.
DETECT_SCORE_COLUMNS = ("max_conf",)

DETECT_OPTIONAL_COLUMNS = (
    "t_detect_end_ns",     # 후처리까지 끝난 시각. t_detect_recv_ns와 같은 시계
    # 카메라가 준 ImageProxy.imageInfo.timestamp 원본. **frames.csv의 t_capture_ns와 같은
    # 부류로 기준 시계가 불명확하다**(§시계 함정) — 우리 시계와 빼지 않는다.
    # ⚠ 하네스는 아직 이 열로 **파생 시계열을 만들지 않는다.** 읽어서 아는 열로만 두는 이유는
    #   앱이 이 값을 쓰기 시작해도 미지 열 경고가 뜨지 않게 하려는 것이고, 취득~탐지 지연을
    #   내려면 t_capture_ns가 그랬듯 상한 가드와 폐기 문장이 따로 필요하다(다음 라운드).
    "t_image_capture_ns",
    "max_conf",            # 그 추론의 최대 점수 (float). 카운트가 아니라 점수다
) + DETECT_TIME_COLUMNS + DETECT_COUNT_COLUMNS

# 위 두 목록에 없는 열 = 하네스가 읽지 않는 열. frames.csv 쪽 KNOWN_COLUMNS와 **같은 취급**이다
# — 하드 에러로 만들지 않고(앱이 스키마보다 앞서 나갈 수 있다) 반드시 경고한다. 오타는 "없는
# 것"과 다르다(`stage_f_ms`를 `stage_f`로 오타 내면 F칸이 count=0이 되고, 우리는 "추론 시간을
# 재지 않았다"고 **잘못** 결론 낸다).
DETECT_KNOWN_COLUMNS = tuple(DETECT_REQUIRED_COLUMNS) + tuple(DETECT_OPTIONAL_COLUMNS)

# ── 열 → 수집 경로 분류 (다섯 번째 자기검사의 입력) ────────────────────────
# **`read_detect`가 각 열을 어떻게 다루는지**를 상수로 선언한다. 아래 자기검사가 이 분류와
# `DETECT_KNOWN_COLUMNS`를 대조해, "스키마에 선언했는데 아무 경로도 수집하지 않는 열"을
# import 시점에 죽인다. 선언만 하고 수집을 잊으면 그 열은 **영원히 count=0**이고, 그 상태는
# "앱이 그 열을 안 냈다"와 로그상 구분되지 않는다.
#
# 시계열로 수집되는 열(각각 DetectSeries의 같은 이름 필드에 쌓인다).
DETECT_SERIES_COLUMNS = (
    tuple(DETECT_TIME_COLUMNS)
    + DETECT_DISTRIBUTION_COUNT_COLUMNS
    + DETECT_SCORE_COLUMNS
)
# 시계열이 아니라 **파생 시계열의 재료**로만 읽히는 열 (DETECT_WALL_SERIES = end - recv).
DETECT_WALL_SOURCE_COLUMNS = ("t_detect_end_ns",)
# 🔴 **일부러 수집하지 않는 열.** "아는 열이지만 아직 쓰지 않는다"를 명시적으로 적는 자리다 —
#   빼면 아래 자기검사가 이 열을 "수집을 잊은 열"로 지목해 import가 죽는다. 여기 적는 것은
#   포기가 아니라 **의도의 기록**이고, 쓰기 시작하는 라운드가 이 목록에서 빼면 된다.
#   (t_image_capture_ns는 기준 시계가 불명확해 우리 시계와 뺄 수 없다 — 상한 가드와 폐기
#    문장이 따로 필요하다. DETECT_OPTIONAL_COLUMNS의 그 열 주석 참고.)
DETECT_UNCOLLECTED_COLUMNS = ("t_image_capture_ns",)

# ── 추론 1회의 **벽시계 span** (파생 시계열) ──────────────────────────────
# **무엇인가:** `t_detect_end_ns - t_detect_recv_ns`. 탐지 스레드가 그 프레임을 받은 순간부터
# 후처리를 끝낸 순간까지의 벽시계 길이다. **CSV 열이 아니라 하네스가 만드는 파생 시계열**이다.
#
# 🔴 **E+F+G의 합이 아니다.** 그 셋의 바깥에 있는 비용(프레임 대기 해제, 텐서 복사, 콜백
#   디스패치)이 이 span에 함께 들어가므로 `span - (E+F+G) >= 0`인 **미계상분이 존재할 수
#   있다.** 그 차이를 무엇이라 부르고 어떻게 낼지는 **소비자 라운드(H5)가 정한다** — 여기서
#   이름을 지으면 소비자가 쓰지 않는 이름이 남는다.
# 🔴 **버짓 칸이 없다.** E·F·G 각각에는 칸이 있지만 이 파생값에는 없다. 칸 라벨을 붙이면
#   미계상분까지 포함한 숫자가 F칸 같은 자리에 인용된다.
# 🔴 **`stage_d_total_ms`와 다른 부류다.** 저쪽은 **열들의 행별 합**이고 이쪽은 **두
#   타임스탬프의 차**다. 그래서 이름에 `_total_`을 쓰지 않는다 — `stage_d_total_ms`와
#   `gpu_sum_ms`를 혼동해 D칸에 잘못된 숫자를 옮긴 전례가 있고(STAGE_D_TOTAL_COLUMN 주석),
#   `*_total_ms` 관행을 따르면 이 값이 "E+F+G의 합"으로 읽혀 같은 함정을 새로 파게 된다.
DETECT_WALL_SERIES = "detect_wall_ms"

# ── 추론 **실행 주기** (파생 시계열) ──────────────────────────────────────
# **무엇인가:** 인접한 두 추론의 `t_detect_recv_ns` 차. frames.csv의 `recv_interval_ms`와
# 정확히 같은 구조이며(시각의 차분), 같은 시계다.
#
# 🔴 **이것이 탐지 주기 N의 실측 대체물이다.** `INTERFACES.md`의 탐지 주기는 아직 ☐(미정)이고
#   하네스는 미확정 계약값을 지어내지 않는다. 대신 "몇 프레임마다 도는가"를 선언에서 읽지 않고
#   **관측한 간격 분포로** 말한다 — 앱이 주기를 바꾸든 스레드가 밀리든 여기 그대로 드러난다.
# 🔴 **버짓 칸이 없다.** 단계 비용이 아니라 실행 간격이다.
# ⚠ 분모가 프레임이 아니라 추론이므로 `recv_interval_ms`와 **더하거나 비교해 빼지 않는다**
#   (모집단이 다르다). 둘의 비(比)가 대략 "몇 프레임마다 한 번"이지만, 그 나눗셈은 소비자가
#   자기 문맥에서 한다 — 하네스는 두 분포를 각각 낸다.
DETECT_CADENCE_SERIES = "detect_cadence_ms"

# 하네스가 만드는 detect 파생 시계열 전부 (CSV 열이 아니다).
DETECT_DERIVED_SERIES = (DETECT_WALL_SERIES, DETECT_CADENCE_SERIES)

# ── session.json의 detect 블록 경로 ───────────────────────────────────────
# **하네스가 읽는 키를 한 곳에만 적는다.** `run_session.py`의 FALLBACK_FIELD_PATH와 같은
# 취지다 — 읽는 코드와 사람에게 보이는 메시지가 이름을 각자 갖고 있으면, 앱이 필드를 옮길 때
# 한쪽만 낡는다(값은 못 읽는데 메시지는 옛 이름을 자신 있게 가리키는 상태가 가장 나쁘다).
#
# ⚠ **생산자는 앱이다.** 다만 이 키들은 arm 어휘와 반대로 **하네스가 먼저 요구한 것**이고,
#   그 요구는 `docs/FRAME_LOG_SCHEMA.md`에 적혀 앱 담당자가 읽는다. 앱이 다른 이름으로
#   내기로 하면 앱이 정답이며 여기와 문서를 **함께** 고친다.
DETECT_SESSION_BLOCK = "detect"
DETECT_ENABLED_PATH = (DETECT_SESSION_BLOCK, "enabled")
DETECT_MODEL_SHA_PATH = (DETECT_SESSION_BLOCK, "model", "sha256")
DETECT_EP_REQUESTED_PATH = (DETECT_SESSION_BLOCK, "ep", "requested")
DETECT_EP_RESOLVED_PATH = (DETECT_SESSION_BLOCK, "ep", "resolved")
DETECT_PERIOD_N_PATH = (DETECT_SESSION_BLOCK, "period_n")
DETECT_PADDING_FRACTION_PATH = (DETECT_SESSION_BLOCK, "padding_pixel_fraction")

# ── 실행 공급자(EP) 어휘 ──────────────────────────────────────────────────
# LIGHTING_CONDITIONS·PIPELINE_STAGES와 **같은 방식**이다: 어휘를 고정하지 않으면 같은 EP가
# "NNAPI" / "nnapi" / "ort_nnapi"로 갈려 모든 비교가 "조건 다름"이 된다.
#
# 🔴 **하네스는 EP를 해석하지 않는다.** 여기 있는 것은 "그 문자열이 우리가 아는 어휘인가"
#   뿐이고, 어느 EP가 실제로 무엇을 실행했는지는 판단하지 않는다. `requested != resolved`도
#   하네스가 해석해서 내리는 결론이 아니라 **앱의 자진 신고 두 개를 대조**한 것이다.
# ⚠ QNN은 여기에 **없다.** 측정 기기(A34)가 MediaTek이라 이 기기에서 불가능하고, 쓰지도
#   않을 토큰을 미리 등록하면 계획 어휘 검사가 그것을 통과시킨다(어휘의 목적과 반대다).
#   다른 기기가 들어오는 날 앱이 쓴 문자열로 등록한다.
DETECT_EP_CPU = "cpu"
DETECT_EP_NNAPI = "nnapi"
# XNNPACK. **CPU EP를 통째로 대체하지 않는다** — 커널 일부만 가져가므로 node_counts가
# `{CPUExecutionProvider: n, XnnpackExecutionProvider: m}`처럼 섞여 나오는 것이 정상이다.
# 앱은 XNNPACK 노드가 하나라도 있으면 `xnnpack`으로 신고한다(그 규칙은 앱이 소유한다).
DETECT_EP_XNNPACK = "xnnpack"
DETECT_EP_UNKNOWN = "unknown"  # 기록되지 않음. 비교 대상으로 쓸 수 없다(LIGHTING_UNKNOWN과 같다)
DETECT_EPS = (DETECT_EP_CPU, DETECT_EP_NNAPI, DETECT_EP_XNNPACK, DETECT_EP_UNKNOWN)

# 각 detect 열이 **어느 스키마 버전에서 들어왔는가.** COLUMN_ADDED_IN과 같은 용도이며
# 목록을 가른 이유는 대상 파일이 다르기 때문이다(frames.csv / detect.csv).
# ⚠ 값을 컴프리헨션으로 채우지 않는다 — 그러면 아래 자기검사가 항상 참이 되어 검사가 아니다.
DETECT_COLUMN_ADDED_IN = {
    "detect_idx": 6,
    "t_detect_recv_ns": 6,
    "t_detect_end_ns": 6,
    "t_image_capture_ns": 6,
    "max_conf": 6,
    "stage_e_ms": 6,
    "stage_f_ms": 6,
    "stage_g_ms": 6,
    "boxes_pre_nms": 6,
    "boxes_out": 6,
    "skipped_while_busy": 6,
}

# ── 상수 자기검사 (v6) ────────────────────────────────────────────────────
# 위 두 블록과 **같은 부류다** — 상수끼리의 불변식이라 데이터와 무관하고, 깨지는 순간은
# 개발자가 상수를 고친 그 편집 시점이다. 그래서 import에서 죽인다.
# 여기서 막는 사고는 조용하다: detect 열이 GPU 쪽 목록에 한 번 들어가면
#   - GPU_SUM_COLUMNS → **CPU 벽시계와 GPU 시계를 더한 숫자**가 gpu_sum_ms로 버짓표에 나간다
#   - STAGE_D_FAMILY_COLUMNS → ③ 비용이 ② 비용(D칸)으로 계상돼 D가 부풀려진다
# 둘 다 결과 숫자만 보면 그럴듯해서 사람 눈으로는 걸러지지 않는다.
_detect_errors = []
_gpu_overlap = sorted(set(DETECT_TIME_COLUMNS) & set(GPU_TIME_COLUMNS))
if _gpu_overlap:
    _detect_errors.append(
        f"{_gpu_overlap}이 GPU_TIME_COLUMNS에 있다 — E·F·G는 CPU 벽시계 구간 길이이고 "
        f"GPU 시계가 아니다(물리량이 다르다)"
    )
_sum_overlap = sorted(set(DETECT_TIME_COLUMNS) & set(GPU_SUM_COLUMNS))
if _sum_overlap:
    _detect_errors.append(
        f"{_sum_overlap}이 GPU_SUM_COLUMNS에 있다 — CPU 시계와 GPU 시계를 더한 숫자가 "
        f"gpu_sum_ms로 버짓표에 나간다"
    )
_d_overlap = sorted(set(DETECT_TIME_COLUMNS) & set(STAGE_D_FAMILY_COLUMNS))
if _d_overlap:
    _detect_errors.append(
        f"{_d_overlap}이 STAGE_D_FAMILY_COLUMNS에 있다 — ③ 탐지 비용이 D칸(②)에 계상되어 "
        f"② 비용이 부풀려진다"
    )
# ── 다섯 번째 검사: **선언했는데 아무도 수집하지 않는 열** ────────────────
# 위 네 검사는 detect 열이 GPU 쪽에 섞이는 것(물리량 오염)과 버전 미등록을 막는데, 그것을
# 전부 통과하고도 **열이 조용히 사라지는** 경로가 남아 있었다: 새 열을 DETECT_OPTIONAL_COLUMNS
# (또는 DETECT_COUNT_COLUMNS)에 넣고 DETECT_COLUMN_ADDED_IN에도 넣으면 네 검사는 모두 통과하지만,
# `read_detect`가 그 열을 읽지 않으면 값이 어디에도 쌓이지 않는다. 그 상태의 로그는
# **"열이 없는 로그"와 구분되지 않는다** — count=0이 "0이었다"로도 "없었다"로도 읽힌다.
# 그래서 "각 열을 어떻게 다루는가"를 상수로 선언하게 하고(위 분류 블록), 그 분류가
# DETECT_KNOWN_COLUMNS를 **빠짐없이·중복 없이** 덮는지를 여기서 검사한다.
# ⚠ 이 검사는 분류가 실제 코드와 일치하는지까지는 보지 못한다. 그 절반은 DetectSeries 정의
#   바로 뒤의 필드 검사가 닫는다(선언한 열에 담을 자리가 있는가).
_detect_handled = (
    tuple(DETECT_REQUIRED_COLUMNS)      # 행 키로 쓴다 (detect_idx / t_detect_recv_ns)
    + DETECT_SERIES_COLUMNS             # 시계열로 쌓는다
    + tuple(DETECT_CUMULATIVE_COLUMNS)  # 마지막 값만 남긴다
    + DETECT_WALL_SOURCE_COLUMNS        # 파생 시계열의 재료
    + DETECT_UNCOLLECTED_COLUMNS        # 일부러 수집하지 않는다(의도의 기록)
)
_detect_unhandled = [c for c in DETECT_KNOWN_COLUMNS if c not in _detect_handled]
_detect_phantom = [c for c in _detect_handled if c not in DETECT_KNOWN_COLUMNS]
_detect_double = sorted({c for c, n in Counter(_detect_handled).items() if n > 1})
if _detect_unhandled:
    _detect_errors.append(
        f"스키마에 선언된 detect 열 {_detect_unhandled}이 어느 수집 경로에도 분류되지 "
        f"않았다 — read_detect가 읽지 않으므로 그 열의 값은 조용히 사라지고, count=0이 "
        f"'열이 없는 로그'와 구분되지 않는다. DETECT_SERIES_COLUMNS·"
        f"DETECT_CUMULATIVE_COLUMNS·DETECT_WALL_SOURCE_COLUMNS 중 하나에 넣거나, 쓰지 "
        f"않기로 했다면 DETECT_UNCOLLECTED_COLUMNS에 사유와 함께 적을 것"
    )
if _detect_phantom:
    _detect_errors.append(
        f"수집 경로에 분류된 {_detect_phantom}이 detect 열 목록에 없다 — CSV에서 읽히지 "
        f"않는 이름이라 그 경로는 영원히 비어 있다"
    )
if len(DETECT_WALL_SOURCE_COLUMNS) != 1:
    # `DETECT_WALL_SERIES`는 **두 시각의 차 하나**다(end - recv). read_detect가 재료 열을
    # 하나로 전제하고 읽으므로, 목록이 늘면 두 번째부터는 조용히 무시된다.
    _detect_errors.append(
        f"DETECT_WALL_SOURCE_COLUMNS의 원소가 1개가 아니다({list(DETECT_WALL_SOURCE_COLUMNS)}) "
        f"— {DETECT_WALL_SERIES}는 두 시각의 차 하나이며, read_detect는 첫 열만 읽는다"
    )
if _detect_double:
    _detect_errors.append(
        f"{_detect_double}이 수집 경로 둘 이상에 분류됐다 — 한 열을 두 번 세거나 "
        f"두 시계열에 나눠 담게 되고, 어느 쪽이 그 열의 분포인지 되물을 수 없다"
    )
_detect_missing_version = [c for c in DETECT_KNOWN_COLUMNS if c not in DETECT_COLUMN_ADDED_IN]
_detect_stray_version = [c for c in DETECT_COLUMN_ADDED_IN if c not in DETECT_KNOWN_COLUMNS]
if _detect_missing_version or _detect_stray_version:
    _detect_errors.append(
        f"DETECT_COLUMN_ADDED_IN이 detect 열 목록과 어긋난다: "
        f"버전 미등록 열={_detect_missing_version}, detect 열이 아닌 항목="
        f"{_detect_stray_version} — '앱이 뒤처졌다' 경고가 빠진 열을 말없이 건너뛴다"
    )
if _detect_errors:
    raise RuntimeError(
        "lib/frame_log.py 상수 불일치 — detect 열의 성질이 어긋난다: "
        + "; ".join(_detect_errors)
        + " (docs/FRAME_LOG_SCHEMA.md §2-D 'detect.csv')"
    )

# ── detect 행 단위 소실 사유 ──────────────────────────────────────────────
# frames.csv 쪽 ROW_SKIP_REASONS와 같은 구조다. 이름만 열에 맞춘다.
DETECT_ROW_SKIP_REASONS = ("warmup", "before_t0", "unparsable_t_detect_recv")

# data_complete를 흔드는 사유 = warmup을 뺀 나머지
DETECT_ANOMALOUS_SKIP_REASONS = ("before_t0", "unparsable_t_detect_recv")

DETECT_ROW_SKIP_REASON_TEXT = {
    "warmup": "warmup 구간(의도된 제외)",
    # ⚠ frames.csv의 before_t0와 **뜻이 다르다.** 여기 t0는 이 파일의 첫 행이 아니라
    #   frames.csv의 t0이고(read_detect가 인자로 받는다), 탐지는 별 use case라 첫 프레임이
    #   렌더 쪽보다 먼저 도착할 수 있다. 그래서 원인이 둘이다 — 경고 문장이 둘 다 말한다.
    "before_t0": "frames.csv의 t0보다 앞선 t_detect_recv_ns — 시계 역행 또는 use case 시작 순서 차이",
    "unparsable_t_detect_recv": "t_detect_recv_ns 파싱 불가(빈칸/비수치/-1) — 잘린 로그 행",
}

# 폐기 사유 → 사람이 읽는 문장. GPU_DISCARD_REASON_TEXT와 같은 취지 — 사유별 계수는 기존
# 경로를 그대로 쓰고(새 폐기 경로를 만들지 않는다) 문장만 열 성격에 맞게 바꾼다.
# detect 열에서 "0 이하"는 시계 역행이 아니다. 엉뚱하게 그렇게 쓰면 폰 쪽이 시계 코드를 뒤진다.
DETECT_DISCARD_REASON_TEXT = {
    "below_min": (
        "-1 또는 0 이하 — 그 추론에서 기록되지 않았거나 구간이 닫히지 않았다"
        " (시계 역행이 아니다)"
    ),
}

# 카운트·점수 열용(`max_conf` 포함). 0은 정상값이므로 음수만 폐기된다 — 문장도 그렇게 말해야 한다.
DETECT_COUNT_DISCARD_REASON_TEXT = {
    "below_min": (
        "-1 또는 음수 — 기록되지 않았다"
        " (0은 폐기하지 않는다: 박스 0개·점수 0은 정상값이다)"
    ),
}
# ══ detect.csv 절 끝 ══════════════════════════════════════════════════════

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
STAGE2_DRAGO = "stage2_drago"          # ② Drago 톤매핑(리덕션+계수+적용 3패스). 앱 생산
STAGE2_CLAHE = "stage2_clahe"          # ② CLAHE+감마, LAB L (타일 히스토그램+CDF+보간). 앱 생산
STAGE2_AGCWD = "stage2_agcwd"          # ② AGCWD, LAB L (전역 히스토그램+가중 LUT). 앱 생산
# ② 노이즈 억제 스테이지(bilateral 계열, `+bf` arm이 ② 자리에서 한 번 더 도는 패스). 앱 생산.
# STAGE2_DRAGO·STAGE2_CLAHE와 **같은 취급**이다 — 생산자는 앱이고 여기 등록은 "이 문자열을
# 안다"는 뜻일 뿐이다. `+bf` arm은 이 토큰을 기존 토큰 뒤에 **나열**한다
# (예: ["blit_2pass","stage2_drago","stage2_clahe","stage2_bilateral"]) — 조합 arm에 새 토큰을
# 만들지 않는 규칙과 같은 이유로, bf 전용 합성 토큰을 지으면 같은 구조가 두 이름으로 갈린다.
STAGE2_BILATERAL = "stage2_bilateral"  # ② 노이즈 억제(bilateral). 앱 생산
# ③ 탐지 스테이지. **앱이 곧 낸다** — 계측은 스키마 v6에서 별 파일 `detect.csv`로 받는다
# (E·F·G. 위 detect.csv 절 참고). ⚠ 탐지용 arm이 붙어도 **여기에 새 토큰을 만들지 않는다** —
# 아래 주석대로 같은 구조가 두 이름으로 갈리면 모든 비교가 "조건 다름"이 된다.
STAGE_DETECT = "detect"                # ③ 탐지. 앱 생산(v6 detect.csv)
# ④ 강조 오버레이 패스(② 출력 위에 스트로크 박스를 덧그린다) → `stage_i_ms`. **앱 생산**이다 —
# `RenderArm.kt`의 `highlight_boxes`·`highlight_boxes_stress`가 둘 다 이 토큰을 선언하고
# (`["blit_2pass","stage4_highlight"]`) `SessionWriter`가 `overlay.gpu_column="stage_i_ms"`를
# 낸다. 합성 생성기(`--stage_i_ms`)도 같은 토큰을 쓴다 — 생산자가 생성기뿐이던 시기는 끝났다.
STAGE4_HIGHLIGHT = "stage4_highlight"  # ④ 강조. 앱 생산 + 생성기
# ④ 좌표 평활·hold 스테이지 (v7) → `stage_h_ms` (버짓 H칸).
# 🔴 **이것은 렌더 패스가 아니다.** GL 스레드에서 도는 **CPU 구간**이며, 그래서 비용이 GPU
#   query가 아니라 CPU 벽시계 열로 온다(FRAME_CPU_TIME_COLUMNS). 토큰을 두는 이유는 "이 arm이
#   평활을 도는가"가 그 런의 구조적 조건이기 때문이다 — 박스를 그리되 평활하지 않는 arm과
#   평활하는 arm은 같은 `pipeline_stages`로 선언될 수 없다.
# ⚠ **STAGE2_BILATERAL과 같은 취급이다** — 앱이 아직 내지 않는 토큰을 미리 등록하는 것이고
#   (그러지 않으면 앱이 붙는 날 매 런 "어휘 밖" 경고가 뜬다), **팀원2 쪽 명명이지 계약값이
#   아니다.** 앱이 다른 문자열을 쓰기로 하면 앱이 정답이며 여기와 문서를 함께 고친다.
#   이 등록은 "이 문자열을 안다"는 뜻일 뿐이다.
STAGE4_SMOOTHING = "stage4_smoothing"  # ④ 좌표 평활·hold(H칸). 앱 생산 예정

# ⚠ 여기에 **아직 없는 arm의 토큰을 미리 만들지 않는다.** 생산자가 앱이므로, 앱이 그 arm을
#   실제로 내기 전에 하네스가 이름을 지으면 앱이 다른 이름을 쓰는 날 같은 구조가 두 이름으로
#   갈려 모든 비교가 "조건 다름"이 된다. arm이 붙을 때마다 앱이 쓴 문자열로 여기 등록한다.
#   (예외는 위 STAGE2_BILATERAL·STAGE4_SMOOTHING처럼 **열이 먼저 들어가는 라운드**다 —
#    스키마 확장은 하네스가 앱보다 먼저 가므로 그 토큰만 함께 예약한다. §6)
PIPELINE_STAGES = (
    STAGE_BLIT_2PASS,
    STAGE2_GAMMA,
    STAGE2_DRAGO,
    STAGE2_CLAHE,
    STAGE2_AGCWD,
    STAGE2_BILATERAL,
    STAGE_DETECT,
    STAGE4_HIGHLIGHT,
    STAGE4_SMOOTHING,
)
# 빈 배열 = 처리 없는 arm(passthrough). "단계 없음"은 토큰이 아니라 빈 배열로 적는다.

# ── 렌더 arm 어휘 (session.json: render_arm) ───────────────────────────────
# **arm은 단계 비용 숫자의 일부다.** `stage_d_ms`가 D칸을 채울 열이라는 것은 스키마 사실
# (analyze_frames의 BUDGET_CELL_OF)이지만, **그 런에서 패스2가 무엇을 그렸는지는 arm이
# 정한다.** 그래서 arm을 떼고 `stage_d_ms` 숫자만 옮기면 재지도 않은 칸을 채우게 된다.
#
# ⚠ **하네스는 arm의 의미를 해석하지 않는다.** 여기 있는 것은 "그 문자열이 우리가 아는
#   어휘인가"뿐이고, 어느 arm이 어느 단계를 실제로 돌렸는지는 판단하지 않는다. 해석을
#   시작하면 앱이 arm을 추가할 때마다 하네스가 따라가야 하고, 그 동기화가 어긋나는 날
#   **조용히 틀린 라벨**이 나온다. 하네스가 하는 일은 숫자 옆에 arm을 붙이는 것까지다.
#
# ⚠ **생산자는 앱이다** (`android/.../gl/RenderArm.kt`의 `id` → SessionWriter의 `render_arm`).
#   PIPELINE_STAGES와 같은 취급 — 어휘 밖 값도 죽이지 않고 경고만 낸다(앱이 새 arm을
#   하네스보다 먼저 낼 수 있다). 이 목록은 `docs/FRAME_LOG_SCHEMA.md` §5와 같아야 한다.
RENDER_ARM_PASSTHROUGH = "passthrough"   # 처리 0. 승격 베이스라인 재현용
RENDER_ARM_BLIT_2PASS = "blit_2pass"     # 3패스 골격
RENDER_ARM_GAMMA_ONLY = "gamma_only"     # ② 자리에 감마 패스
# 합성 로그 생성기가 박는 값. LIGHTING_SYNTHETIC과 같은 취급 — 실기기 arm이 아니라는 사실이
# 값 자체에 드러나야 한다. 앱은 이 값을 절대 쓰지 않는다.
RENDER_ARM_SYNTHETIC = "synthetic"

# ⚠ 아래 ② arm 이름들은 **팀원2(하네스) 쪽 명명이지 팀 계약값이 아니다.** 앱이 붙을 때
#   경고가 뜨지 않도록 미리 등록해 둔 것이고, 앱이 다른 id를 쓰기로 하면 앱 쪽이 정답이며
#   여기를 고친다(생산자는 앱이다). 어휘 등록은 "이 문자열을 안다"는 뜻일 뿐,
#   하네스가 그 arm의 의미를 해석한다는 뜻이 아니다.
RENDER_ARM_CLAHE_GAMMA = "clahe_gamma"
RENDER_ARM_CLAHE_GAMMA_BF = "clahe_gamma_bf"
RENDER_ARM_AGCWD = "agcwd"
RENDER_ARM_AGCWD_BF = "agcwd_bf"
RENDER_ARM_DRAGO = "drago"
RENDER_ARM_REINHARD = "reinhard"
RENDER_ARM_LIME = "lime"
# ② 조합 arm(② 자리에서 톤커브 스테이지를 두 번 돈다) + ④ 강조. **위와 같은 취급이다** —
# 팀원2 쪽 명명이지 계약값이 아니고, 생산자는 앱이므로 앱이 다른 id를 쓰기로 하면 앱이
# 정답이며 여기를 고친다. 등록은 "이 문자열을 안다"는 뜻일 뿐이다.
RENDER_ARM_DRAGO_CLAHE_CHAIN = "drago_clahe_chain"
RENDER_ARM_DRAGO_CLAHE_FUSED = "drago_clahe_fused"
RENDER_ARM_HIGHLIGHT_BOXES = "highlight_boxes"
# ② 조합 arm + bilateral(1패스 joint gather). 이름은 위 `clahe_gamma_bf`·`agcwd_bf`의
# `<arm>_bf` 규약을 그대로 따른다. **위 블록과 같은 취지다** — 팀원2 쪽 명명이지 계약값이
# 아니고, 생산자는 앱이므로 앱이 다른 id를 쓰기로 하면 앱이 정답이며 여기를 고친다.
# 등록은 "이 문자열을 안다"는 뜻일 뿐이고, 하네스는 arm의 의미를 해석하지 않는다.
RENDER_ARM_DRAGO_CLAHE_CHAIN_BF = "drago_clahe_chain_bf"
RENDER_ARM_DRAGO_CLAHE_FUSED_BF = "drago_clahe_fused_bf"
# ④ 강조를 **박스 개수만 다르게** 재는 조건. 같은 arm id로 개수만 바꾸면 안 되는 이유:
# 박스 개수가 다르면 측정 조건이 다른데 `baseline_diff.py`의 CONDITION_KEYS에는 개수를 담을
# 키가 없어서(`pipeline_stages`는 둘 다 ["blit_2pass","stage4_highlight"]다) 조건 차이가
# **무경고로 통과**한다. `blit_2pass`와 `clahe_gamma`가 둘 다 ["blit_2pass"]로 기록되어
# 처리량이 완전히 다른데도 "회귀 없음"이 나온 실패와 **동형**이며, 그때의 처방이 arm을
# 조건 키에 넣는 것이었다 — 그러므로 개수 차이도 arm id로 갈라 둔다.
# ⚠ 개수 자체를 하네스가 해석하지는 않는다(그 값은 앱이 session.json에 적는다).
RENDER_ARM_HIGHLIGHT_BOXES_STRESS = "highlight_boxes_stress"

# ── `_1q` 접미사 = **프레임 단일 query 계측** (v5) ─────────────────────────
# 🔴 **렌더 경로는 접미사 없는 arm과 글자 그대로 같다.** 셰이더도 패스 구성도 그리는 내용도
#   같고, 다른 것은 **GPU 시간을 어떻게 재는가** 하나뿐이다:
#     접미사 없음 : 패스마다 query 하나 → `stage_*_ms` / `gpu_present_ms` (합이 `gpu_sum_ms`)
#     `_1q`       : 프레임 하나를 query 하나로 감싼다 → `gpu_frame_ms`
#   `GL_TIME_ELAPSED`는 중첩되지 않으므로 같은 런에서 둘 다는 불가능하다. 그래서 계측 방식이
#   arm으로 갈리며, 이건 이 저장소의 기존 패턴이다(알려진 이슈 4).
#
# 그러므로 **`pipeline_stages`도 접미사 없는 arm과 같다.** 두 arm을 가르는 것은 `render_arm`
# 하나뿐이고, 그 키가 `baseline_diff.py`의 `CONDITION_KEYS`에 있으므로 조건 차이로 잡힌다 —
# `highlight_boxes` / `highlight_boxes_stress`와 **같은 구조**다(그쪽은 박스 개수가,
# 이쪽은 계측 방식이 `pipeline_stages`에 담기지 않는다).
#
# ⚠ **생산자는 앱이다.** 위 예약어 블록들과 같은 취급 — 팀원2 쪽 명명이지 계약값이 아니고,
#   앱이 다른 id를 쓰기로 하면 앱이 정답이며 여기를 고친다. 등록은 "이 문자열을 안다"는
#   뜻일 뿐이고, 하네스는 arm의 의미를 해석하지 않는다.
RENDER_ARM_BLIT_2PASS_1Q = "blit_2pass_1q"
RENDER_ARM_DRAGO_CLAHE_CHAIN_1Q = "drago_clahe_chain_1q"
RENDER_ARM_DRAGO_CLAHE_CHAIN_BF_1Q = "drago_clahe_chain_bf_1q"
# 🔴 **아래 셋이 생긴 이유는 "하한이 없는 arm 계열이 셋 남아 있다"는 것이다**(STATUS 이슈 22).
#   단일 query 짝을 실제로 잰 것은 `blit_2pass`·`drago_clahe_chain`·`drago_clahe_chain_bf`
#   셋뿐이라, `drago_clahe_fused`·`drago_clahe_fused_bf`·`highlight_boxes` 계열에는 **패스별
#   계측의 상한만 있고 하한이 없다.**
#   ⚠ **부풀림 비율을 다른 arm에서 옮겨 보정할 수 없다** — 중복 계상량은 마지막 전체화면
#     패스의 비용을 따라가므로 패스 구성마다 다르다(④ 오버레이 arm은 +2%, 9패스 arm은 +43%로
#     한 자릿수 배수만큼 벌어진다). 그러므로 하한은 **그 arm에서 직접 재는 수밖에 없고**,
#     그러려면 arm id가 있어야 한다. 그 사실이 이 셋의 존재 이유다.
RENDER_ARM_DRAGO_CLAHE_FUSED_1Q = "drago_clahe_fused_1q"
RENDER_ARM_DRAGO_CLAHE_FUSED_BF_1Q = "drago_clahe_fused_bf_1q"
RENDER_ARM_HIGHLIGHT_BOXES_1Q = "highlight_boxes_1q"

# ── ③ 탐지 arm (v6) ───────────────────────────────────────────────────────
# ⚠ **생산자는 앱이다.** 위 예약어 블록들과 같은 취급 — 팀원2 쪽 명명이지 계약값이 아니고,
#   앱이 다른 id를 쓰기로 하면 앱이 정답이며 여기를 고친다. 등록은 "이 문자열을 안다"는
#   뜻일 뿐이고, 하네스는 arm의 의미를 해석하지 않는다.
#
# `detect_bind_only`는 **분모**다. `ImageAnalysis`를 바인딩만 하고 추론은 돌리지 않으므로,
# 이 arm과 짝 arm의 차이가 "use case를 하나 더 붙인 값"이고 그 위의 차이가 추론 비용이다.
# 둘을 한 arm에서 재면 그 둘이 섞여 어느 쪽이 비싼지 되물을 수 없다.
RENDER_ARM_DETECT_BIND_ONLY = "detect_bind_only"
RENDER_ARM_DETECT_CPU = "detect_cpu"
RENDER_ARM_DETECT_NNAPI = "detect_nnapi"
RENDER_ARM_DETECT_XNNPACK = "detect_xnnpack"

# `_norot` 접미사 = **회전을 의도적으로 적용하지 않는 짝 arm** (v6, 2026-08-07).
# 🔴 `detect_cpu`와 다른 것은 **전처리가 rotationDegrees를 적용하는가** 하나뿐이다
#   (`rotation_site = "none"` — 덤프 포맷 규약 §4-1). `_1q`·`_prof`와 같은 취지로 arm을
#   가른다: 조건이 다르면 같은 코드라도 같은 조건이 아니고, 그 사실을 담을 키가
#   `pipeline_stages`에는 없다.
#
# 왜 필요한가: 회전을 붙이면 E의 **값**이 바뀐다(정의는 안 바뀐다 — E는 여전히
#   `DetectPipeline`이 t를 찍는 위치다). 그 차이를 "E가 회귀했다"로 읽지 않으려면 **같은
#   세션에서 회전 전 기준선**을 함께 재야 한다. 이 arm이 그 분모다.
#   ⚠ 회전 미적용 arm의 박스는 **옆으로 누운 장면**에서 나온 것이라 탐지 품질 근거로
#     쓰지 않는다(알려진 이슈 29와 같은 상태다 — 다만 이쪽은 **의도된** 것이다).
RENDER_ARM_DETECT_CPU_NOROT = "detect_cpu_norot"

# ── `_prof` 접미사 = **ORT 프로파일링을 켠 계측** (v6) ─────────────────────
# 🔴 **렌더·추론 경로는 접미사 없는 짝과 글자 그대로 같다.** 모델도 EP도 전처리도 같고,
#   다른 것은 **ORT 프로파일러가 켜져 있는가** 하나뿐이다. `_1q` 접미사와 **같은 취지**로
#   arm을 가른다 — 계측 방식이 다르면 같은 코드라도 같은 조건이 아니고, 그 사실을 담을 키가
#   `pipeline_stages`에는 없기 때문이다(`render_arm`은 baseline_diff의 CONDITION_KEYS에 있다).
#
# 🔴 **이 arm의 시간은 인용하지 않는다.** 프로파일러는 노드마다 기록을 남기므로 F(그리고
#   그것을 포함하는 모든 값)에 자기 비용을 얹는다. 이 arm은 "어느 노드가 비싼가"를 보는
#   장치이고, E·F·G 숫자와 버짓 칸은 접미사 없는 짝에서만 인용한다.
RENDER_ARM_DETECT_CPU_PROF = "detect_cpu_prof"
RENDER_ARM_DETECT_NNAPI_PROF = "detect_nnapi_prof"
RENDER_ARM_DETECT_XNNPACK_PROF = "detect_xnnpack_prof"

# ── `detect_parity_*` = **이식 정확성 대조 전용 arm** (v6) ─────────────────
# 🔴 **`_prof` arm과 같은 취급이다 — 이 arm의 시간은 인용하지 않는다**
#   (`DETECT_PROF_NOT_QUOTABLE`과 같은 부류). 이 arm은 E·F·G 각 경계에서 텐서를 파일로
#   덤프하는데(샘플당 ~7MB) 그 I/O가 같은 스레드에 있으면 E·F·G가 그만큼 부풀고, 다른
#   스레드로 빼도 SoC 자원 경쟁이 남는다. 그러므로 `detect.csv`가 나오더라도 그 값은
#   버짓 칸으로 옮기지 않고 승격본(`docs/baselines/`)으로도 올리지 않는다.
#   **재는 자리는 이미 있다**(`detect_cpu` 등) — 이 arm은 **값을 대조하는 자리**다.
#
# 무엇을 대조하는가: 폰이 남긴 원본 평면·입력 텐서·출력 텐서·박스를 PC ORT 재구현과
#   3분할(E/F/G)로 비교한다. 포맷 규약은 `docs/plans/20260806_detect_parity_dump_format.md`,
#   소비자는 `scripts/detect_parity.py`다. EP별로 arm을 가르는 이유는 `detect_*`와 같고
#   (EP 차이는 arm id로 가른다), 여기서는 이유가 하나 더 있다 — **NNAPI가 GPU로 내려가는
#   것을 이미 실측했고 GPU 경로는 fp16으로 떨어질 수 있다.** 같은 입력에 답이 달라지는 것은
#   성능 문제가 아니라 안전 문제라, EP끼리도 서로 대조해야 한다.
RENDER_ARM_DETECT_PARITY_CPU = "detect_parity_cpu"
RENDER_ARM_DETECT_PARITY_NNAPI = "detect_parity_nnapi"
RENDER_ARM_DETECT_PARITY_XNNPACK = "detect_parity_xnnpack"

# ── ③→④ 연결 arm (v7) ────────────────────────────────────────────────────
# ⚠ **생산자는 앱이다.** 위 예약어 블록들과 같은 취급 — 팀원2 쪽 명명이지 계약값이 아니고,
#   앱이 다른 id를 쓰기로 하면 앱이 정답이며 여기를 고친다. 등록은 "이 문자열을 안다"는
#   뜻일 뿐이고, 하네스는 arm의 의미를 해석하지 않는다.
#
# 세 arm이 **한 세트**다. 셋을 같은 세션에서 재야 I칸의 상한·하한이 둘 다 나온다:
#
#   detect_cpu_highlight     4패스(오버레이) + 패스별 GPU query + stage_h_ms + overlay_boxes
#                            → I 상한 · H. **본진**이다.
#   detect_cpu_highlight_1q  **위와 글자 그대로 같은 렌더**, 프레임 단일 query → gpu_frame_ms
#                            → I 하한 (`_1q` 접미사 블록의 규약을 그대로 따른다)
#   detect_cpu_1q            3패스(오버레이 없음), 프레임 단일 query → gpu_frame_ms
#                            → 🔴 **하한의 분모**
#
# 🔴 **왜 하한의 분모가 `detect_cpu_1q`인가 (= 왜 이 arm이 새로 필요한가).**
#   하한은 "같은 계측 방식의 두 arm 차"로만 낼 수 있다(`_1q` 블록: 계측 방식이 다른 분모에
#   빼면 그 값이 arm 비용도 중복 계상량도 아니게 된다). 그런데 지금 있는 단일 query 분모는
#   `blit_2pass_1q`뿐이고 **거기에는 탐지 부하가 없다** — ③이 돌면 SoC 전체가 다른 상태이므로
#   그 분모에 빼면 차이에 탐지 비용이 섞인다. 알려진 이슈 36이 정확히 이 부류다:
#   `highlight_boxes_1q`의 `gpu_frame_ms`가 분모와 소수점 셋째 자리까지 같아 I 하한이 0이
#   나왔고, 그 0은 "오버레이가 공짜"가 아니라 **분모가 상한을 통째로 중복 계상했다**는 뜻이었다.
#   ⚠ 그러므로 `detect_cpu_highlight_1q − detect_cpu_1q`만이 하한이다. `blit_2pass_1q`나
#     `detect_cpu`(패스별 계측)를 분모로 쓰지 않는다.
#
# 🔴 **상한의 분모는 기존 `detect_cpu`다** — 같은 세션 안에서 재고, 계측 방식(패스별 query)이
#   같아야 한다. 계측 방식이 다른 짝을 빼면 위와 같은 이유로 값의 뜻이 사라진다.
#
# ⚠ **앱 쪽에 딸린 요구가 있다**(하네스가 강제할 수 없다): `_1q` arm은 앱의 `RenderArm.kt`에서
#   `singleFrameQueryPeer`·`renderPassCount` 대응 항목에 **반드시** 등록돼야 한다. 빠뜨리면
#   `GpuTimerRing`이 프레임 전체가 아니라 **첫 패스만** 감싸고, 그러면 `gpu_frame_ms`가
#   "프레임 하나의 GPU 시간"이 아닌 채로 하한 계산에 들어간다 — 로그만 보면 그럴듯하다.
#   그 등록은 android 트랙의 몫이며 `docs/FRAME_LOG_SCHEMA.md` §5에 요구로 적혀 있다.
RENDER_ARM_DETECT_CPU_HIGHLIGHT = "detect_cpu_highlight"
RENDER_ARM_DETECT_CPU_HIGHLIGHT_1Q = "detect_cpu_highlight_1q"
RENDER_ARM_DETECT_CPU_1Q = "detect_cpu_1q"

# ── ②+③+④ 통합 arm (v7) ──────────────────────────────────────────────────
# ⚠ **생산자는 앱이다.** 위 예약어 블록들과 같은 취급 — 팀원2 쪽 명명이 아니라 앱이 확정한
#   id를 뒤이어 등록한 것이고(어휘 등록의 정상 순서다), 등록은 "이 문자열을 안다"는 뜻일
#   뿐이다. 하네스는 여기서도 arm의 의미를 해석하지 않는다.
#
# **네 arm이 한 세트다** (2026-08-25에 셋이 늘었다. 셋을 같은 세션에서 재야 상한과 하한이
# 둘 다 나온다):
#
#   detect_cpu_chain_highlight     9패스(② Drago→CLAHE 체인 + ④ 오버레이) + 패스별 GPU
#                                  query 9열 + stage_h_ms · overlay_boxes ·
#                                  t_overlay_source_ns → **본진**. I 상한 · H
#   detect_cpu_chain_highlight_1q  **위와 글자 그대로 같은 렌더**, 프레임 단일 query →
#                                  gpu_frame_ms 한 열. 오버레이 3열은 **싣는다**
#                                  → I 하한의 **분자**
#   detect_cpu_chain_1q            아래 arm과 **글자 그대로 같은 렌더**(8패스: 체인 7 +
#                                  present, 오버레이 없음), 프레임 단일 query →
#                                  gpu_frame_ms 한 열. 오버레이 열 없음
#                                  → 🔴 **I 하한의 분모**
#   detect_cpu_chain               8패스. 렌더는 drago_clahe_chain과 같고 **탐지가 돈다**.
#                                  오버레이 없음. 패스별 GPU 8열 + detect.csv
#                                  → 패스7↔8 병합 진단(탐지 부하 아래의 ② 체인)이며
#                                  detect_cpu_chain_1q의 패스별 짝이다
#
# **무엇인가:** ② 조합(Drago→CLAHE 체인) 출력 **위에** ③ 탐지 결과를 ④ 오버레이로 덧그리는
#   9패스 arm이 이 계열의 본진이다. 패스 순서는
#     1 OES→FBO_A / 2 drago analyze / 3 drago build / 4 drago apply(A→B) /
#     5 clahe analyze / 6 clahe build / 7 clahe apply(B→**A**) /
#     8 ④ 오버레이 → **A**(clear 없이 덧그림) / 9 present(A→화면)
#   이고, GPU 열도 그 순서로 9개다(stage_b / d_analyze / d_build / d_apply /
#   d_analyze2 / d_build2 / d_apply2 / stage_i / gpu_present) + CSV 오버레이 열 3개
#   (stage_h_ms · overlay_boxes · t_overlay_source_ns).
#   오버레이 없는 짝(detect_cpu_chain)은 패스8이 빠진 8패스이고 GPU 열도 8개다.
#   🔴 **새 토큰도 새 열도 만들지 않는다.** `pipeline_stages`는 기존 토큰을 **나열**하고
#     (9패스 arm은 ["blit_2pass","stage2_drago","stage2_clahe","detect",
#       "stage4_highlight","stage4_smoothing"])
#     열은 위 12개가 이미 v7까지 다 등록돼 있다 — 조합 arm에 합성 토큰을 만들지 않는 규칙과
#     **같은 이유**다(같은 구조가 두 이름으로 갈리면 모든 비교가 "조건 다름"이 된다).
#     그래서 이 라운드도 **CSV 열이 하나도 늘지 않고 `SCHEMA_VERSION`도 7 그대로다** —
#     `_1q` arm은 이미 있는 `gpu_frame_ms` 열을, 오버레이 없는 arm은 이미 있는 열의
#     부분집합을 쓴다.
#
# 🔴 **이 저장소 최초로 `stage2_*`와 `stage4_*`를 동시에 갖는 arm이다**(9패스 짝 둘).
#   전수 확인 결과 기존 arm 중 `stage2_*` 보유 12개 · `stage4_*` 보유 5개인데 **교집합이 0**
#   이었다 (앱 `RenderArm.kt`의 `pipelineStages` 전수). 측정 arm을 "한 번에 하나만 바꾼다"는
#   원칙으로 만든 결과이고 그 격리 덕에 D칸·I칸이 각각 나왔지만, **제품은 저조도 개선된
#   화면 위에 박스가 얹혀야 한다** — 그 구성을 한 번도 재지 않았다는 사실이 이 arm의 이유다.
#
# 🔴 **`_1q` 짝이 생겼다 → 이 계열에서 I칸의 하한이 나온다. 그런데 H칸은 애초에 하한·상한의
#   대상이 아니다 — "I칸·H칸의 하한"은 범주 오류였고 여기서 정정한다.**
#   - I **상한** = `detect_cpu_chain_highlight`의 `stage_i_ms`. 패스별 계측은 마지막
#     전체화면 패스의 비용을 **중복 계상**하므로(알려진 이슈 21) 그 값이 상한이다.
#   - I **하한** = `detect_cpu_chain_highlight_1q` − `detect_cpu_chain_1q`.
#     🔴 **둘 다 프레임 단일 query여야 뜻이 있다.** 계측 방식이 다른 짝을 빼면 그 차가 arm
#     비용도 중복 계상량도 아니게 된다(위 `_1q` 블록).
#   - 🔴 **분모가 `drago_clahe_chain_1q`가 아닌 이유: 거기에는 탐지 부하가 없다**
#     (앱에서 `usesDetectSession=false`이고 `detect.csv`도 나오지 않는다). ③이 돌면 SoC
#     전체가 다른 상태이므로 그 분모에 빼면 차이에 탐지 비용이 섞인다. **알려진 이슈 36이
#     정확히 이 부류다**: `highlight_boxes_1q`의 `gpu_frame_ms`가 분모와 소수점 셋째 자리까지
#     같아 I 하한이 0으로 나왔고, 그 0은 "④가 공짜"가 아니라 **분모가 상한을 통째로 중복
#     계상했다**는 뜻이었다. 분모를 잘못 고르면 하한이 0으로 나오고 그 0은 분모가 상한을
#     중복 계상했다는 뜻이다 — 값이 아니라 분모의 고발이다.
#   - 🔴 **H는 하한·상한의 대상이 아니다.** 앱의 `OVERLAY_STAGE_H_SCOPE`가 `stage_h_ms`
#     구간이 **`gpuTimer.beginFrame`보다 앞에서 닫힌다**고 못 박고 있다 — H는 CPU 벽시계이고
#     모든 GPU query의 **밖**이다. 그러므로 `gpu_frame_ms` 차분에 H는 물리적으로 들어 있지
#     않다. H는 `stage_h_ms` 열로 **직접 측정**되며 차분으로 유도할 대상이 아니다.
#   - 🔴 **상한의 분모는 패스별 계측끼리다**: `detect_cpu_chain_highlight` −
#     `detect_cpu_chain`, 같은 세션 안에서 잰다. 패스별 arm을 `_1q` arm에 빼지 않는다.
#   ⚠ 이 사실은 `session.json`의 **`bounds_note`** 키로 나간다(`stage2_params`·`overlay`
#     두 자리. 앱 상수는 `CHAIN_HIGHLIGHT_BOUNDS_NOTE`). 🔴 **키를 내는 arm은 오버레이가
#     있는 둘뿐이다** — `detect_cpu_chain_highlight` · `detect_cpu_chain_highlight_1q`.
#     오버레이 없는 `detect_cpu_chain` · `detect_cpu_chain_1q`에는 **일부러 없고**(앱이 그
#     분기에서 ④ 관련 키를 넣지 않는다) 그 런에 키가 없는 것은 결함이 아니다.
#     **arm 중립 문장**이라 그 둘 중 어느 쪽이 실어도 참이다(계측 방식을 특정하지 않는다).
#     이전 이름 `no_lower_bound_note`는 폐기됐다 — "하한을 낼 수 없다"는 서술이 더 이상
#     참이 아니고, H를 하한의 대상으로 적은 부분은 범주 오류였다.
#
# ⚠ **패스7과 패스8의 타깃이 같은 FBO_A다.** clahe apply가 A에 쓰고 오버레이가 clear 없이
#   같은 A에 덧그리므로, 드라이버가 두 렌더패스를 병합하면 `stage_d_apply2_ms`와
#   `stage_i_ms`의 **경계가 흐려진다.** 알려진 이슈 3(개별 D 열이 한 패스 밀려 있다)과 같은
#   부류의 귀속 문제이며, 하네스가 갈라낼 수단은 없다 — 두 열을 따로 인용하기 전에 이 사실을
#   함께 옮긴다. 🔴 **`_1q` 짝은 프레임 총량은 갈라 주지만 열 경계는 갈라 주지 못한다** —
#   단일 query는 애초에 열이 하나다. 위 하한은 "④가 프레임 GPU 시간에 더한 양"이고
#   "패스8의 시간"이 아니다.
#
# ⚠ **측정용 추가이며 제품 구성 확정이 아니다.** 팀 결정 4건(② 융합 채택 여부 · bf 여부 ·
#   §B-4 ts · 탐지 주기 N)이 미결이라 이 arm은 상류 잠정 1위와 **같은 구성이 아니다.**
#   이 arm의 숫자를 "제품 성능"으로 인용하지 않는다.
RENDER_ARM_DETECT_CPU_CHAIN = "detect_cpu_chain"
RENDER_ARM_DETECT_CPU_CHAIN_1Q = "detect_cpu_chain_1q"
RENDER_ARM_DETECT_CPU_CHAIN_HIGHLIGHT = "detect_cpu_chain_highlight"
RENDER_ARM_DETECT_CPU_CHAIN_HIGHLIGHT_1Q = "detect_cpu_chain_highlight_1q"

RENDER_ARMS = (
    RENDER_ARM_PASSTHROUGH,
    RENDER_ARM_BLIT_2PASS,
    RENDER_ARM_GAMMA_ONLY,
    RENDER_ARM_CLAHE_GAMMA,
    RENDER_ARM_CLAHE_GAMMA_BF,
    RENDER_ARM_AGCWD,
    RENDER_ARM_AGCWD_BF,
    RENDER_ARM_DRAGO,
    RENDER_ARM_REINHARD,
    RENDER_ARM_LIME,
    RENDER_ARM_DRAGO_CLAHE_CHAIN,
    RENDER_ARM_DRAGO_CLAHE_FUSED,
    RENDER_ARM_HIGHLIGHT_BOXES,
    RENDER_ARM_DRAGO_CLAHE_CHAIN_BF,
    RENDER_ARM_DRAGO_CLAHE_FUSED_BF,
    RENDER_ARM_HIGHLIGHT_BOXES_STRESS,
    # 프레임 단일 query 계측 (v5). 렌더 경로는 접미사 없는 짝과 같다 — 위 블록 참고.
    RENDER_ARM_BLIT_2PASS_1Q,
    RENDER_ARM_DRAGO_CLAHE_CHAIN_1Q,
    RENDER_ARM_DRAGO_CLAHE_CHAIN_BF_1Q,
    # 하한이 없던 세 계열(STATUS 이슈 22)의 단일 query 짝 — 위 블록 참고.
    RENDER_ARM_DRAGO_CLAHE_FUSED_1Q,
    RENDER_ARM_DRAGO_CLAHE_FUSED_BF_1Q,
    RENDER_ARM_HIGHLIGHT_BOXES_1Q,
    # ③ 탐지 (v6). `_prof` 짝은 시간 인용 금지 arm이다 — 위 블록 참고.
    RENDER_ARM_DETECT_BIND_ONLY,
    RENDER_ARM_DETECT_CPU,
    RENDER_ARM_DETECT_NNAPI,
    RENDER_ARM_DETECT_XNNPACK,
    # 회전 미적용 짝 arm(v6, 2026-08-07) — 위 블록 참고. 회전 전 E의 기준선이다.
    RENDER_ARM_DETECT_CPU_NOROT,
    RENDER_ARM_DETECT_CPU_PROF,
    RENDER_ARM_DETECT_NNAPI_PROF,
    RENDER_ARM_DETECT_XNNPACK_PROF,
    # ③ 이식 정확성 대조 전용(v6). `_prof`와 같이 **시간 인용 금지** arm이다 — 위 블록 참고.
    RENDER_ARM_DETECT_PARITY_CPU,
    RENDER_ARM_DETECT_PARITY_NNAPI,
    RENDER_ARM_DETECT_PARITY_XNNPACK,
    # ③→④ 연결(v7). 셋이 한 세트다 — 본진 / I 하한 / **하한의 분모**. 위 블록 참고.
    RENDER_ARM_DETECT_CPU_HIGHLIGHT,
    RENDER_ARM_DETECT_CPU_HIGHLIGHT_1Q,
    RENDER_ARM_DETECT_CPU_1Q,
    # ②+③+④ 통합(v7). 넷이 한 세트다 — 본진(9패스) / 그 `_1q` 짝(I 하한의 분자) /
    # 오버레이 없는 `_1q`(**I 하한의 분모**) / 그 패스별 짝. **stage2_*와 stage4_*를
    # 동시에 갖는 첫 arm**은 9패스 짝 둘이다. H는 하한의 대상이 아니다 — 위 블록 참고.
    RENDER_ARM_DETECT_CPU_CHAIN,
    RENDER_ARM_DETECT_CPU_CHAIN_1Q,
    RENDER_ARM_DETECT_CPU_CHAIN_HIGHLIGHT,
    RENDER_ARM_DETECT_CPU_CHAIN_HIGHLIGHT_1Q,
    RENDER_ARM_SYNTHETIC,
)

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

# ── v7 오버레이 열의 폐기 사유 문장 ───────────────────────────────────────
# **사유별 계수는 기존 경로를 그대로 쓰고**(새 폐기 경로를 만들지 않는다) 문장만 열 성격에
# 맞게 바꾼다. detect 쪽에서 같은 이유로 문장을 가른 선례를 그대로 따른다 — 엉뚱하게
# "시계 역행"이라고 쓰면 폰 쪽이 시계 코드를 뒤진다(H는 GL 스레드의 CPU 구간이다).
FRAME_CPU_DISCARD_REASON_TEXT = {
    "below_min": (
        "-1 또는 0 이하 — 그 프레임에서 기록되지 않았거나 구간이 닫히지 않았다"
        " (시계 역행이 아니다)"
    ),
}

# 카운트 열용. 0은 정상값이므로 음수만 폐기된다 — 문장도 그렇게 말해야 한다.
FRAME_COUNT_DISCARD_REASON_TEXT = {
    "below_min": (
        "-1 또는 음수 — 기록되지 않았다"
        " (0은 폐기하지 않는다: 박스 0개 프레임은 정상값이다)"
    ),
}

# 신선도(파생)용. 여기서 값이 없는 원인이 **둘**이라 한쪽으로 단정하지 않는다.
OVERLAY_FRESHNESS_DISCARD_REASON_TEXT = {
    "below_min": (
        "t_overlay_source_ns가 -1이거나 t_render_start_ns보다 미래다 — 아직 게시된 탐지 "
        "결과가 없는 프레임(첫 추론 완료 전)이거나 두 시각의 순서가 뒤집혔다"
    ),
    "no_render_start": (
        "t_render_start_ns가 없어 계산할 수 없다 — 신선도의 기준 시각이 렌더 시작이다"
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
    # t_recv - t_capture. **지연의 앞자락**(ISP + 큐)이며 위 capture_to_render_ms의 부분이다
    # (v7). 이 한 칸이 있으면 capture_to_render를 셋으로 가를 수 있다:
    #   capture→recv(ISP/큐) · recv→render_start(디스패치 대기) · render_start→render_end(제출).
    # 🔴 **t_capture_ns가 섞여 있으므로 capture_to_render_ms와 같은 상한 가드를 쓴다** —
    #    그 열만 기준 시계가 의심 대상이고(§시계 함정), 기준이 어긋나면 수천 초가 나온다.
    # ⚠ 세 조각을 더해도 capture_to_render_ms와 정확히 같아지지 않는다(render_start가 없는
    #   프레임이 있고, 폐기가 조각마다 따로 일어난다). **분포끼리 더해 검산하지 않는다.**
    capture_to_recv_ms: list[float] = field(default_factory=list)
    # t_render_start - t_recv. 위 분해의 **가운데 조각**(디스패치 대기 = 수신부터 렌더 시작까지
    # 큐에서 기다린 시간). 같은 시계(CLOCK_BOOTTIME) 두 시각의 차다.
    # ⚠ **`recv_to_render_ms`와 한 글자 차이인데 물리량이 다르다.** 이쪽은 render **start**
    #   까지고, 그쪽은 render **end**까지(체류시간 전체)다. 같은 키에 섞지 않는다
    #   (render_latency_ms / recv_to_render_ms를 가른 것과 같은 급의 위험이다).
    # 세 값의 관계:
    #   - **행 단위로만** `recv_to_render_start_ms + render_latency_ms == recv_to_render_ms`.
    #   - **분포끼리는 성립하지 않는다** — 폐기가 조각마다 따로 일어나므로 표본 집합이 다르다
    #     (p50끼리 더해 검산하지 않는다).
    #   - `recv_to_render_start_ms <= recv_to_render_ms`는 항상 참이며, 이는
    #     `check_clock_consistency` 교차검사 A(t_render_start >= t_recv)와 같은 불변식이다.
    # 🔴 **상한 가드를 두지 않는다**(기본 `(MIN_POSITIVE_MS, None)`). 여기엔 `t_capture_ns`가
    #    섞이지 않으므로 상한의 근거가 없고(폐기 가드 주석), 같은 단조 시계 안에서 큰 값은
    #    시계 오류가 아니라 **실제로 느린 프레임**이다 — 그 느린 프레임이 이 분해의 표적이다.
    # ⚠ 세 조각을 더해도 capture_to_render_ms와 같아지지 않는다(위 주석과 같은 이유).
    recv_to_render_start_ms: list[float] = field(default_factory=list)
    # ── GPU 패스 시간 (GPU 시계 — 위 시계열들과 **다른 시계**다. 섞지 않는다) ──
    stage_b_ms: list[float] = field(default_factory=list)
    stage_d_ms: list[float] = field(default_factory=list)
    stage_d_analyze_ms: list[float] = field(default_factory=list)
    stage_d_build_ms: list[float] = field(default_factory=list)
    stage_d_apply_ms: list[float] = field(default_factory=list)
    stage_d_denoise_ms: list[float] = field(default_factory=list)
    # 서수 2 = 그 arm의 **두 번째 톤커브 스테이지**의 같은 역할 슬롯 (v4)
    stage_d_analyze2_ms: list[float] = field(default_factory=list)
    stage_d_build2_ms: list[float] = field(default_factory=list)
    stage_d_apply2_ms: list[float] = field(default_factory=list)
    stage_i_ms: list[float] = field(default_factory=list)
    gpu_present_ms: list[float] = field(default_factory=list)
    # 프레임 하나를 query 하나로 감싼 값 (v5). **패스별 값이 아니다** —
    # gpu_sum_ms에도 stage_d_total_ms에도 들어가지 않는다 (GPU_FRAME_COLUMN 주석).
    gpu_frame_ms: list[float] = field(default_factory=list)
    # **행 단위** 합. p50(B)+p50(D) != p50(B+D)이므로 백분위를 더하지 않고 행에서 먼저
    # 더한 뒤 분포를 낸다. 그 행에 유효한 GPU 열이 하나도 없으면 기여하지 않는다.
    # ⚠ 더하는 대상은 **패스별 열**(GPU_SUM_COLUMNS)이며 gpu_frame_ms는 빠진다.
    gpu_sum_ms: list[float] = field(default_factory=list)
    # D 계열만의 **행 단위** 합 = 그 런의 D칸. gpu_sum_ms와 더하는 대상이 다르다.
    stage_d_total_ms: list[float] = field(default_factory=list)
    # ── ④ 오버레이 (v7). **위 GPU 열들과 다른 시계·다른 물리량이다** ──────
    # 🔴 stage_h_ms는 **CPU 벽시계**(GL 스레드)다 — gpu_sum_ms에도 stage_d_total_ms에도
    #    들어가지 않는다(FRAME_CPU_TIME_COLUMNS 주석 + 상수 자기검사).
    stage_h_ms: list[float] = field(default_factory=list)
    # 그 프레임에 실제로 그린 박스 수. **0을 폐기하지 않는다**(가드가 `>= 0`이다).
    overlay_boxes: list[int] = field(default_factory=list)
    # t_render_start_ns - t_overlay_source_ns = 그 프레임이 쓴 탐지 결과의 나이 (파생 시계열).
    # 🔴 CSV 열이 아니다 — 유도값은 저장하지 않는다.
    overlay_freshness_ms: list[float] = field(default_factory=list)
    # CSV 헤더에 실제로 있던 v7 오버레이 열. GPU 열과 같은 이유로 따로 둔다
    # ("열이 아예 없다"와 "열은 있는데 값이 -1이다"는 다른 사실이다).
    overlay_columns_present: list[str] = field(default_factory=list)
    # CSV 헤더에 실제로 있던 GPU 열. 헤더에 없는 열은 폐기로 세지 않는다
    # ("열이 아예 없다"와 "열은 있는데 값이 -1이다"는 다른 사실이다).
    gpu_columns_present: list[str] = field(default_factory=list)
    # 그중 D 계열만 (헤더에 있던 것). stage_d_total_ms가 무엇을 더한 값인지가 여기 있다.
    stage_d_columns_present: list[str] = field(default_factory=list)
    # 그중 **gpu_sum_ms에 실제로 더해진 열**(헤더에 있던 것). gpu_columns_present와 다르다 —
    # gpu_frame_ms는 헤더에 있어도 여기 없다(프레임을 두 번 세지 않는다).
    gpu_sum_columns_present: list[str] = field(default_factory=list)
    # gpu_sum_ms에 들어갔지만 헤더에 있는 GPU 열을 **전부** 채우지는 못한 행 수.
    # 이 값이 0이 아니면 gpu_sum_ms 분포는 아래쪽으로 치우친다(빠진 패스만큼 작다).
    gpu_sum_partial_rows: int = 0
    # 같은 이유의 D 계열 판. 하위 패스 하나가 disjoint로 빠지면 그 행의 D는 그만큼 작다.
    stage_d_total_partial_rows: int = 0
    dropped_total: int = 0
    # warmup 컷의 기준 시각 = **첫 행의 t_recv_ns**. 밖으로 내는 이유는 `read_detect`가
    # 같은 t0를 써야 하기 때문이다 — detect.csv가 자기 첫 행을 t0로 잡으면 두 파일의 분석
    # 창이 어긋나고, 그 상태로 "같은 런의 F와 프레임타임"이라고 말하면 거짓이 된다.
    t0_ns: int = MISSING
    # 분석 창의 양 끝 = **실제로 쓰인 행들의 t_recv_ns 최소/최대**(warmup 제외 후).
    # 🔴 창 길이를 `p50(recv_interval_ms) × n`으로 유도하지 않기 위해 둔다. 그 유도는 드롭된
    #   프레임과 폐기된 간격을 빼먹어 창을 짧게 만들고, 그 짧은 창으로 duty cycle 같은
    #   비율을 내면 분모가 작아져 값이 커진다(이 결함이 세 문서에 동시에 재발한 적이 있다).
    t_first_used_ns: int = MISSING
    t_last_used_ns: int = MISSING
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
    def analysis_window_sec(self) -> Optional[float]:
        """분석 창 길이 = `t_recv_ns`의 **실제 span**. 행이 1개뿐이면 0.0이다(None이 아니다).

        🔴 백분위나 표본 수에서 유도하지 않는다 — 이 값이 duty cycle 같은 비율의 분모가 되고,
        분모를 유도로 만들면 드롭·폐기만큼 조용히 짧아진다.
        """
        if self.t_first_used_ns == MISSING or self.t_last_used_ns == MISSING:
            return None
        return (self.t_last_used_ns - self.t_first_used_ns) / 1e9

    @property
    def gpu_series(self) -> dict[str, list[float]]:
        """열 이름 -> 시계열. 파생인 gpu_sum_ms는 여기 넣지 않는다(원본 열만)."""
        return {name: getattr(self, name) for name in GPU_TIME_COLUMNS}

    @property
    def has_gpu_timings(self) -> bool:
        return any(self.gpu_series.values())

    @property
    def overlay_series(self) -> dict[str, list]:
        """v7 오버레이 열 이름 -> 시계열. **파생(신선도)은 넣지 않는다**(원본 열만).

        🔴 `gpu_series`와 **합치지 않는다.** 그 property의 소비자는 GPU 시계 전용 경로
        (`gpu_sum_ms` 합산·stages 블록)라, 여기 값이 그 dict에 들어가면 CPU 벽시계와 개수가
        GPU 라벨 체계 안으로 들어간다.
        """
        return {
            name: getattr(self, name)
            for name in FRAME_CPU_TIME_COLUMNS + FRAME_COUNT_COLUMNS
        }

    @property
    def has_overlay_metrics(self) -> bool:
        return any(self.overlay_series.values()) or bool(self.overlay_freshness_ms)

    @property
    def stage_d_ambiguous(self) -> bool:
        """`stage_d_ms`와 ② 하위 패스 열이 **같은 로그에 동시에** 있는가.

        그렇다면 `stage_d_ms`가 "② 전체 합"인지 "또 다른 하위 패스"인지 이 로그만으로는
        알 수 없다. 하네스가 택한 해석은 `_add_stage_d_warnings`가 문장으로 밝힌다.
        """
        cols = set(self.stage_d_columns_present)
        return "stage_d_ms" in cols and bool(cols - {"stage_d_ms"})

    @property
    def gpu_frame_conflict(self) -> bool:
        """프레임 단일 query 열과 패스별 열이 **같은 로그에 동시에** 있는가 (v5).

        `GL_TIME_ELAPSED`는 중첩되지 않으므로 같은 프레임에 두 계측을 걸 수 없다 — 즉 이
        상태는 도달 불가여야 한다. 그런데도 왔다면 둘 중 하나는 그 프레임의 값이 아니고,
        어느 쪽인지는 로그만으로 알 수 없다. 죽이지 않고(앱이 스키마보다 앞서 나갈 수 있다)
        경고로 낸다 — `stage_d_ambiguous`와 같은 부류다.
        """
        cols = set(self.gpu_columns_present)
        return GPU_FRAME_COLUMN in cols and bool(cols & set(GPU_SUM_COLUMNS))

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


# ── 상수 자기검사 (v7) — **선언한 열에 담을 자리가 실제로 있는가** ─────────
# DetectSeries 쪽 필드 검사(아래)와 같은 부류다. 위 열 성질 검사를 전부 통과하고도 **열이
# 조용히 사라지는** 경로가 남아 있다: 새 열을 FRAME_OVERLAY_COLUMNS·COLUMN_ADDED_IN에 넣고
# OPTIONAL_COLUMNS에도 넣으면 그 검사들은 통과하지만, FrameSeries에 담을 필드가 없으면
# read_frames가 AttributeError로 죽거나(그 런의 집계를 통째로 잃는다) 조용히 건너뛴다.
# count=0은 "0이었다"로도 "없었다"로도 읽히므로 그 상태를 남기지 않는다.
_frame_field_names = {f.name for f in dataclass_fields(FrameSeries)}
_frame_field_errors = [
    f"FrameSeries에 {_c} 필드가 없다 — v7 오버레이 열로 선언됐는데 담을 자리가 없다"
    for _c in FRAME_CPU_TIME_COLUMNS + FRAME_COUNT_COLUMNS
    if _c not in _frame_field_names
]
if OVERLAY_FRESHNESS_SERIES not in _frame_field_names:
    _frame_field_errors.append(
        f"FrameSeries에 {OVERLAY_FRESHNESS_SERIES} 필드가 없다 — 파생 시계열을 담을 자리가 없다"
    )
if "capture_to_recv_ms" not in _frame_field_names:
    _frame_field_errors.append(
        "FrameSeries에 capture_to_recv_ms 필드가 없다 — 파생 시계열을 담을 자리가 없다"
    )
if "recv_to_render_start_ms" not in _frame_field_names:
    _frame_field_errors.append(
        "FrameSeries에 recv_to_render_start_ms 필드가 없다 — 파생 시계열을 담을 자리가 없다"
    )
if _frame_field_errors:
    raise RuntimeError(
        "lib/frame_log.py 상수 불일치 — v7 오버레이 열과 FrameSeries 필드가 어긋난다: "
        + "; ".join(_frame_field_errors)
        + " (docs/FRAME_LOG_SCHEMA.md §2 '④ 오버레이 열')"
    )


@dataclass
class DetectSeries:
    """detect.csv에서 뽑아낸 시계열들. **행 하나 = 추론 1회.**

    🔴 **FrameSeries와 합치지 않는다.** 표본 모집단이 다르다(프레임 수 ≫ 추론 수) —
    같은 객체에 담으면 별 파일로 분리한 이유가 무의미해지고, `rows_used`가 무엇의 개수인지
    되물을 수 없게 된다. 회계·폐기·미지 열 **관행은** FrameSeries와 같은 것을 쓴다.
    """

    # ── E·F·G (CPU 벽시계 구간 길이. GPU 열들과 **다른 물리량**이다) ──
    stage_e_ms: list[float] = field(default_factory=list)
    stage_f_ms: list[float] = field(default_factory=list)
    stage_g_ms: list[float] = field(default_factory=list)
    # 추론 1회의 **벽시계 span** = t_detect_end_ns - t_detect_recv_ns.
    # 🔴 **E+F+G의 합이 아니고**(미계상분을 포함한다) **버짓 칸도 없다**.
    #    `stage_d_total_ms`(열들의 행별 합)와 다른 부류다 — DETECT_WALL_SERIES 주석 참고.
    detect_wall_ms: list[float] = field(default_factory=list)
    # 인접한 두 추론의 t_detect_recv_ns 차 = **실측 실행 주기** (파생 시계열).
    # 🔴 프레임 간격이 아니다 — 모집단이 다르므로 recv_interval_ms와 섞지 않는다.
    detect_cadence_ms: list[float] = field(default_factory=list)
    # ── 카운트·점수 (시간이 아니다. 0을 폐기하지 않는다) ──
    boxes_pre_nms: list[int] = field(default_factory=list)
    boxes_out: list[int] = field(default_factory=list)
    max_conf: list[float] = field(default_factory=list)
    # `skipped_while_busy`는 **누적값**이라 분포를 내지 않는다(단조 증가 수열의 백분위는
    # 뜻이 없다). 마지막으로 관측한 값 하나만 남긴다.
    skipped_while_busy_total: int = 0
    # 그 열이 파싱된 행 수. 0이면 `skipped_while_busy_total = 0`은 "건너뛴 프레임이 없다"가
    # 아니라 **재지 않았다**는 뜻이다 — 둘을 구분하려면 이 값이 필요하다.
    skipped_while_busy_rows: int = 0
    # 누적값이 줄어든 횟수. 누적이면 절대 줄지 않으므로 1건이라도 있으면 그 열을 못 믿는다.
    skipped_while_busy_regressions: int = 0
    # CSV 헤더에 실제로 있던 detect 열(필수 제외). 헤더에 없는 열은 폐기로 세지 않는다
    # ("열이 아예 없다"와 "열은 있는데 값이 -1이다"는 다른 사실이다).
    detect_columns_present: list[str] = field(default_factory=list)
    # frames.csv에서 받아 온 분석 창의 기준 시각. **이 파일의 첫 행이 아니다.**
    t0_ns: int = MISSING
    rows_read: int = 0
    rows_used: int = 0
    # 시계열 이름 -> {사유: 개수}. **값 하나**를 버린 것.
    discarded: dict[str, dict[str, int]] = field(default_factory=dict)
    # 사유 -> 개수. **행 전체**가 시계열에 못 들어온 것. 회계는 accounting_ok로 닫는다.
    rows_skipped: dict[str, int] = field(
        default_factory=lambda: {r: 0 for r in DETECT_ROW_SKIP_REASONS}
    )
    # CSV 헤더에 있었지만 DETECT_KNOWN_COLUMNS에 없어 **집계에 쓰이지 않은** 열 이름.
    unknown_columns: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def time_series(self) -> dict[str, list[float]]:
        """열 이름 -> 시계열. 파생인 detect_wall_ms는 여기 넣지 않는다(원본 열만)."""
        return {name: getattr(self, name) for name in DETECT_TIME_COLUMNS}

    @property
    def has_detect_timings(self) -> bool:
        return any(self.time_series.values())

    @property
    def discarded_total(self) -> int:
        return sum(sum(reasons.values()) for reasons in self.discarded.values())

    @property
    def rows_skipped_total(self) -> int:
        return sum(self.rows_skipped.values())

    @property
    def rows_skipped_anomalous(self) -> int:
        """warmup을 뺀 소실. 1건이라도 있으면 그 로그는 온전하지 않다."""
        return sum(self.rows_skipped.get(r, 0) for r in DETECT_ANOMALOUS_SKIP_REASONS)

    @property
    def accounting_ok(self) -> bool:
        """rows_read == rows_used + 모든 소실 사유의 합. 깨지면 어딘가 조용히 새고 있다."""
        return self.rows_read == self.rows_used + self.rows_skipped_total

    def note_discard(self, series_name: str, reason: str) -> None:
        reasons = self.discarded.setdefault(series_name, {})
        reasons[reason] = reasons.get(reason, 0) + 1

    def note_row_skip(self, reason: str) -> None:
        self.rows_skipped[reason] = self.rows_skipped.get(reason, 0) + 1


# ── 상수 자기검사 (v6, 다섯 번째 검사의 나머지 절반) ──────────────────────
# 위 커버리지 검사는 "모든 열이 어딘가로 분류됐는가"를 보고, 여기서는 **그 분류에 담을 자리가
# 실제로 있는가**를 본다. 둘을 함께 걸어야 "선언은 했는데 수집은 안 되는 열"이 닫힌다 —
# 분류만 있고 필드가 없으면 read_detect가 AttributeError로 죽거나(그 런의 집계를 통째로 잃는다)
# 조용히 건너뛴다. 상수·정의끼리의 불변식이므로 데이터와 무관하고, 깨지는 순간은 개발자가
# 열을 추가한 그 편집 시점이다.
_detect_field_names = {f.name for f in dataclass_fields(DetectSeries)}
_detect_field_errors = []
for _c in DETECT_SERIES_COLUMNS:
    if _c not in _detect_field_names:
        _detect_field_errors.append(
            f"DetectSeries에 {_c} 필드가 없다 — 시계열로 분류됐는데 담을 자리가 없다"
        )
for _c in DETECT_CUMULATIVE_COLUMNS:
    # 누적 열은 리스트가 아니라 세 필드로 받는다: 마지막 값 / 파싱된 행 수 / 감소 횟수.
    # (행 수가 없으면 total=0이 "건너뛴 프레임이 없다"인지 "재지 않았다"인지 구분되지 않는다.)
    for _suffix in ("_total", "_rows", "_regressions"):
        if f"{_c}{_suffix}" not in _detect_field_names:
            _detect_field_errors.append(
                f"DetectSeries에 {_c}{_suffix} 필드가 없다 — 누적 열로 분류됐는데 "
                f"마지막 값·행 수·감소 횟수를 담을 자리가 없다"
            )
for _c in DETECT_DERIVED_SERIES:
    if _c not in _detect_field_names:
        _detect_field_errors.append(
            f"DetectSeries에 {_c} 필드가 없다 — 파생 시계열을 담을 자리가 없다"
        )
if _detect_field_errors:
    raise RuntimeError(
        "lib/frame_log.py 상수 불일치 — detect 열 분류와 DetectSeries 필드가 어긋난다: "
        + "; ".join(_detect_field_errors)
        + " (docs/FRAME_LOG_SCHEMA.md §2-D 'detect.csv')"
    )


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


def session_field(session: dict, path: tuple[str, ...]) -> tuple[object, bool]:
    """(값, 마지막 키가 실제로 있었는가). 경로는 위 DETECT_*_PATH 상수에서만 온다.

    `key_present`를 함께 돌려주는 이유: **명시적 `null`과 "키가 없다"는 다른 사실이다.**
    둘 다 "말할 수 없다"로 판정되지만, 사유가 "필드가 없다"로 뭉개지면 사람이 엉뚱한 곳
    (스키마 버전)을 뒤진다. `run_session.py`의 `_dig`와 같은 규약이다.
    """
    cur: object = session
    for key in path:
        if not isinstance(cur, dict) or key not in cur:
            return None, False
        cur = cur[key]
    return cur, True


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
        # D 계열은 위 목록의 부분집합이다. 따로 뽑아 두는 이유는 stage_d_total_ms가
        # **무엇을 더한 값인지**가 요약에 남아야 하기 때문이다(arm마다 열 구성이 다르다).
        stage_d_columns_present = [
            c for c in STAGE_D_FAMILY_COLUMNS if c in gpu_columns_present
        ]
        # gpu_sum_ms에 **실제로 더해질** 열. gpu_columns_present와 다르다 — 프레임 단일
        # query 열(v5)은 읽고 분포도 내지만 합에는 들어가지 않는다(프레임 이중 계상).
        gpu_sum_columns_present = [
            c for c in GPU_SUM_COLUMNS if c in gpu_columns_present
        ]
        # v7 오버레이 열. **GPU 목록과 별로 뽑는다** — 시계도 물리량도 가드도 다르고,
        # 합산 경로(gpu_sum_columns_present)에 절대 들어가지 않아야 한다.
        overlay_columns_present = [
            c for c in FRAME_OVERLAY_COLUMNS if c in reader.fieldnames
        ]
        rows = list(reader)

    series = FrameSeries()
    series.unknown_columns = unknown_columns
    series.gpu_columns_present = gpu_columns_present
    series.stage_d_columns_present = stage_d_columns_present
    series.gpu_sum_columns_present = gpu_sum_columns_present
    series.overlay_columns_present = overlay_columns_present
    _add_unknown_column_warnings(series)
    series.rows_read = len(rows)
    if not rows:
        raise FrameLogError(f"행이 하나도 없다: {path}")

    # warmup 컷 기준은 첫 행의 t_recv_ns
    t0 = _to_int(rows[0].get("t_recv_ns"))
    if t0 == MISSING:
        raise FrameLogError("첫 행의 t_recv_ns가 비어 있다 — 기준 시각을 잡을 수 없다")
    cutoff_ns = t0 + int(warmup_sec * 1e9)
    # read_detect가 **같은** t0를 쓰도록 밖으로 낸다 (FrameSeries.t0_ns 주석)
    series.t0_ns = t0

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
        # 분석 창의 양 끝. **행을 세는 것이 아니라 시각을 기록한다** (analysis_window_sec 주석).
        if series.t_first_used_ns == MISSING:
            series.t_first_used_ns = t_recv
        series.t_last_used_ns = t_recv
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

        # 디스패치 대기 — 지연 3분해의 **가운데 조각**. `t_render_end`와 무관하므로 위
        # 블록 안이 아니라 `t_rs`만 보고 낸다(렌더가 끝나지 않은 프레임에서도 대기 시간은
        # 이미 확정돼 있다). 상한 없음 — `t_capture_ns`가 섞이지 않는다(필드 주석).
        # 부수 효과로 이 열의 below_min 계수가 아래 교차검사 A의 위반 수와 **거의** 맞물린다
        # — 공짜 검산이다. 정확한 관계는 `below_min >= 교차검사 A 위반`이고 등호는
        # `t_rs == t_recv`인 행이 없을 때만 성립한다: below_min은 `not (v > 0)`이라
        # 동시각(0.0)을 세고, 교차검사 A는 `t_rs < t_recv`라 엄격 부등호로 안 센다.
        # 실기기 로그에서는 ns 해상도라 동시각이 안 나온다(실측 1,482,265행 중 0건).
        if t_rs != MISSING:
            _collect(
                series, "recv_to_render_start_ms", series.recv_to_render_start_ms,
                (t_rs - t_recv) / 1e6,
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

        # 취득~수신 (v7) — 위 지연의 **앞자락**(ISP + 큐). `t_capture_ns`가 섞여 있으므로
        # **같은 상한 가드**를 쓴다(그 열만 기준 시계가 의심 대상이다).
        if t_cap != MISSING:
            _collect(
                series, "capture_to_recv_ms", series.capture_to_recv_ms,
                (t_recv - t_cap) / 1e6, SANE_CAPTURE_TO_RENDER_MS,
            )

        # ── GPU 패스 시간. 위 시계열과 **다른 시계**라 교차검사에 넣지 않는다.
        #    헤더에 있는 열만 본다. 값이 -1이면 _collect의 하한(> 0)에 걸려
        #    below_min으로 세어진다 — 기존 폐기 계수를 그대로 쓴다(새 경로를 만들지 않는다).
        #    그래야 disjoint로 몇 프레임이 빠졌는지가 조용히 사라지지 않는다.
        row_gpu_sum = 0.0
        row_gpu_valid = 0
        row_d_sum = 0.0
        row_d_valid = 0
        for col in gpu_columns_present:
            val = _to_float(row.get(col))
            before = len(getattr(series, col))
            _collect(series, col, getattr(series, col), val)
            if len(getattr(series, col)) > before:
                # 🔴 프레임 단일 query 열(v5)은 **어느 합에도 들어가지 않는다.** 분포는
                #    위 _collect로 이미 냈다 — 여기서 더하면 프레임을 두 번 세게 된다.
                if col not in GPU_SUM_COLUMNS:
                    continue
                row_gpu_sum += val
                row_gpu_valid += 1
                # D 계열은 같은 행 합에 한 번 더 들어간다. **가드·폐기 경로는 위와 동일**이며
                # (채택된 값만 더한다) 여기서 새 판정을 하지 않는다.
                if col in STAGE_D_FAMILY_COLUMNS:
                    row_d_sum += val
                    row_d_valid += 1
        if row_gpu_valid:
            if row_gpu_valid < len(gpu_sum_columns_present):
                # 일부 패스만 해소된 행. 합이 그만큼 작으므로 사실을 세어 둔다.
                series.gpu_sum_partial_rows += 1
            # 백분위를 더하지 않는다: 행에서 먼저 더하고 분포는 그 뒤에 낸다.
            _collect(series, "gpu_sum_ms", series.gpu_sum_ms, row_gpu_sum)
        if row_d_valid:
            if row_d_valid < len(stage_d_columns_present):
                series.stage_d_total_partial_rows += 1
            # D칸도 같은 규칙 — **행별 합**이지 백분위의 합이 아니다.
            _collect(
                series, STAGE_D_TOTAL_COLUMN, series.stage_d_total_ms, row_d_sum
            )

        # ── ④ 오버레이 (v7). **어떤 합에도 더하지 않는다.**
        #    🔴 위 GPU 블록과 코드를 합치지 않는 이유가 여기 있다: 그 블록은 채택된 값을
        #      row_gpu_sum에 더하는데, CPU 벽시계 열이 거기 들어가면 gpu_sum_ms가 두 시계를
        #      더한 숫자가 된다. 상수 자기검사가 상수 수준에서 막는 사고를 코드 수준에서도
        #      막는 자리다(경로 자체가 분리돼 있어야 한다).
        #    🔴 가드가 열 종류마다 다르다 — 시간 열은 `> 0`, 카운트 열은 `>= 0`.
        for col in overlay_columns_present:
            if col in FRAME_CPU_TIME_COLUMNS:
                # 하한 `> 0`, 상한 없음. E·F·G와 같은 논거다(같은 CPU 시계 안에서 구간이
                # 닫히므로 큰 값은 시계 오류가 아니라 **진짜 느린 프레임**이다).
                _collect(series, col, getattr(series, col), _to_float(row.get(col)))
            elif col in FRAME_COUNT_COLUMNS:
                # 🔴 하한 `>= 0`. **박스 0개 프레임은 정상값이다** — 시간 열의 `> 0`을
                #    복사하면 그 프레임들이 전부 폐기로 세어진다.
                _collect_nonneg(series, col, getattr(series, col), _to_int(row.get(col)))
            # t_overlay_source_ns는 시계열이 아니라 **파생의 재료**다 (바로 아래).

        # 오버레이 신선도 = 렌더 시작 − 그 프레임이 쓴 탐지 결과의 게시 시각 (파생 시계열).
        # **CSV 열이 아니다** — 유도값은 저장하지 않는다(파일 상단 §1).
        # 값을 못 만든 경우도 **사유별로 센다** — 조용히 사라지면 count=0이 "신선했다"로도
        # "재지 못했다"로도 읽힌다.
        src_col = FRAME_OVERLAY_SOURCE_COLUMNS[0]
        if src_col in overlay_columns_present:
            t_src = _to_int(row.get(src_col))
            if t_rs == MISSING:
                series.note_discard(OVERLAY_FRESHNESS_SERIES, "no_render_start")
            elif t_src == MISSING:
                series.note_discard(OVERLAY_FRESHNESS_SERIES, "below_min")
            else:
                _collect(
                    series, OVERLAY_FRESHNESS_SERIES, series.overlay_freshness_ms,
                    (t_rs - t_src) / 1e6,
                )

    check_clock_consistency(series, render_start_checked, render_start_violations)
    _add_row_skip_warnings(series)
    _add_discard_warnings(series)
    _add_clock_warnings(series)
    _add_gpu_warnings(series)
    _add_stage_d_warnings(series)
    _add_overlay_warnings(series)

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


def read_detect(
    path: Path,
    t0_ns: int,
    warmup_sec: float = 0.0,
) -> DetectSeries:
    """detect.csv를 읽어 ③ 탐지 시계열을 만든다. **행 하나 = 추론 1회.**

    `t0_ns`: **frames.csv에서 얻은 t0**(`FrameSeries.t0_ns`)를 그대로 넘긴다.
    🔴 자기 첫 행을 t0로 잡지 **않는** 이유: 탐지는 프레임보다 드물게 돌므로 이 파일의 첫
    행은 렌더 쪽 첫 프레임보다 늦다. 그 시각에서 warmup을 다시 세면 두 파일의 분석 창이
    어긋나고, 그 상태로 "같은 런의 F와 프레임타임"이라고 말하면 거짓이 된다. 그래서 t0는
    만들지 않고 **받는다** — 호출자가 두 파일을 같은 창으로 맞출 책임을 갖는다.

    `warmup_sec`: read_frames에 준 것과 **같은 값**을 준다. 다르면 위와 같은 이유로 창이
    어긋난다(하네스는 그것을 검사할 방법이 없다 — 호출자가 지킨다).
    """
    if not path.exists():
        raise FrameLogError(f"탐지 로그가 없다: {path}")
    # t0를 못 받았으면 **죽는다.** 기본값으로 얼버무리면(예: 0이나 첫 행) 창이 조용히
    # 어긋난 채 숫자가 나오고, 그 숫자는 frames.csv 쪽과 같은 런처럼 보인다.
    if not isinstance(t0_ns, int) or isinstance(t0_ns, bool) or t0_ns < 0:
        raise FrameLogError(
            f"t0_ns가 유효하지 않다({t0_ns!r}) — frames.csv에서 얻은 t0"
            "(read_frames가 채우는 FrameSeries.t0_ns)를 넘겨야 두 파일의 분석 창이 같아진다"
        )

    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None:
            raise FrameLogError(f"헤더가 없다: {path}")
        missing = [c for c in DETECT_REQUIRED_COLUMNS if c not in reader.fieldnames]
        if missing:
            raise FrameLogError(
                f"필수 열 누락: {missing} (있는 열: {reader.fieldnames})"
            )
        # 중복 열은 **죽인다.** read_frames와 완전히 같은 이유다(그쪽 주석 참고):
        # csv.DictReader는 중복 헤더에서 마지막 값만 남기므로 성한 값이 조용히 파괴되고,
        # 그 결과 해당 지표가 count=0이 되어 "그 열이 없는 로그"와 구분되지 않는다.
        # 미지 열은 덧붙는 것이라 무해하지만 중복은 **아는 열의 값을 파괴한다.**
        dup_known = sorted(
            {
                c for c, n in Counter(reader.fieldnames).items()
                if n > 1 and c in DETECT_KNOWN_COLUMNS
            }
        )
        if dup_known:
            raise FrameLogError(
                f"헤더에 중복된 열이 있다: {dup_known} (전체 헤더: {reader.fieldnames}) — "
                "csv.DictReader는 중복 헤더에서 마지막 값만 남기므로 앞쪽 값이 조용히 파괴된다. "
                "그 결과 해당 지표가 count=0이 되어 '그 열이 없는 로그'와 구분되지 않는다. "
                "폰 쪽 detect.csv 헤더 생성부를 확인할 것"
            )
        # 미지 열은 죽이지 않는다(앱이 스키마보다 앞서 나갈 수 있다). 대신 이름을 지목해 경고.
        unknown_columns = sorted(
            {c for c in reader.fieldnames if c not in DETECT_KNOWN_COLUMNS}
        )
        # 헤더에 실제로 있는 열만 집계 대상이다. 없는 열까지 읽으면 모든 행이 "값 -1"로 보여
        # 폐기 카운트가 행 수만큼 튄다 — "열이 없다"와 "열은 있는데 -1이다"는 다른 사실이다.
        present = [c for c in DETECT_OPTIONAL_COLUMNS if c in reader.fieldnames]
        rows = list(reader)

    series = DetectSeries()
    series.t0_ns = t0_ns
    series.unknown_columns = unknown_columns
    series.detect_columns_present = present
    _add_detect_unknown_column_warnings(series)
    series.rows_read = len(rows)
    if not rows:
        raise FrameLogError(f"행이 하나도 없다: {path}")

    cutoff_ns = t0_ns + int(warmup_sec * 1e9)
    # ── 수집 대상은 **전부 상수 분류에서 파생시킨다.** 여기에 열 이름 리터럴을 두면
    #    (예전의 `("boxes_pre_nms", "boxes_out")`) 다음 사람이 열을 추가하고 자기검사를
    #    모두 통과시켜도 그 값이 조용히 사라진다. 위 다섯 번째 자기검사가 이 파생과 짝이다.
    time_cols = [c for c in DETECT_TIME_COLUMNS if c in present]
    # 카운트·점수는 폐기 가드가 같다(하한 `>= 0`). 분포를 내는 것만 여기 온다 —
    # 누적 열은 아래에서 따로 다룬다.
    nonneg_cols = [
        c for c in DETECT_DISTRIBUTION_COUNT_COLUMNS + DETECT_SCORE_COLUMNS if c in present
    ]
    # 정수로 읽을 열(카운트)과 실수로 읽을 열(점수)을 가른다. `_to_int`는 "12.7개"를 12로
    # 만들지만 점수에 그걸 쓰면 0.72가 0이 된다.
    int_cols = set(DETECT_DISTRIBUTION_COUNT_COLUMNS)
    cumulative_cols = [c for c in DETECT_CUMULATIVE_COLUMNS if c in present]
    # 벽시계 span은 **두 시각의 차 하나**이므로 재료 열도 하나다(자기검사가 개수를 강제한다).
    wall_end_col = DETECT_WALL_SOURCE_COLUMNS[0]
    has_end = wall_end_col in present
    prev_cumulative: dict[str, int] = {}
    prev_detect_recv = MISSING

    for row in rows:
        t_recv = _to_int(row.get("t_detect_recv_ns"))
        # ── 행을 건너뛰는 세 경로. **전부 사유별로 센다** (read_frames와 같은 규약).
        if t_recv == MISSING:
            series.note_row_skip("unparsable_t_detect_recv")
            continue
        if t_recv < t0_ns:
            # frames.csv의 t0보다 과거. 시계 역행일 수도 있고, 탐지가 별 use case라
            # 첫 프레임이 렌더 쪽보다 먼저 도착한 것일 수도 있다 — 조용히 버리지 않는다.
            series.note_row_skip("before_t0")
            continue
        if t_recv < cutoff_ns:
            series.note_row_skip("warmup")  # 의도된 제외
            continue

        series.rows_used += 1

        # 실행 주기 — 인접한 두 추론의 수신 시각 차. **frames.csv의 recv_interval_ms와 같은
        # 구조이고 같은 시계이지만 모집단이 다르다**(프레임이 아니라 추론). 이 값이 ☐ 미정인
        # 탐지 주기 N을 지어내지 않고 관측으로 말하는 수단이다(DETECT_CADENCE_SERIES 주석).
        if prev_detect_recv != MISSING:
            _collect(
                series, DETECT_CADENCE_SERIES, series.detect_cadence_ms,
                (t_recv - prev_detect_recv) / 1e6,
            )
        prev_detect_recv = t_recv

        # E·F·G — 하한 `> 0`, **상한 없음.** GPU 열과 같은 논거다(SANE_* 주석): 한 구간의
        # 시작/끝을 같은 CPU 시계 안에서 닫으므로 큰 값은 시계 오류가 아니라 **진짜 느린
        # 추론**이고(발열 스로틀링, big 코어 이탈, GC), 그것이 정확히 우리가 잡아야 할 것이다.
        # 값이 -1이면 하한에 걸려 below_min으로 세어진다 — 새 폐기 경로를 만들지 않는다.
        for col in time_cols:
            _collect(series, col, getattr(series, col), _to_float(row.get(col)))

        # 추론 1회의 **벽시계 span** = end - recv. **파생 시계열이지 CSV 열이 아니다**
        # (DETECT_WALL_SERIES 주석: E+F+G의 합이 아니고 버짓 칸도 없다).
        if has_end:
            t_end = _to_int(row.get(wall_end_col))
            if t_end == MISSING:
                series.note_discard(DETECT_WALL_SERIES, "below_min")
            else:
                _collect(
                    series, DETECT_WALL_SERIES, series.detect_wall_ms,
                    (t_end - t_recv) / 1e6,
                )

        # 카운트·점수 — **하한이 `>= 0`이다**(시간 열과 다르다). 박스 0개는 정상값이므로
        # 시간 열의 `> 0`을 쓰면 그 추론들이 통째로 폐기되어 분포가 위로 치우친다.
        for col in nonneg_cols:
            raw = _to_int(row.get(col)) if col in int_cols else _to_float(row.get(col))
            _collect_nonneg(series, col, getattr(series, col), raw)

        # 누적 열(`skipped_while_busy`)은 **분포를 내지 않고 마지막 값만 남긴다** —
        # 단조 증가 수열의 백분위는 뜻이 없다. 누적은 절대 줄지 않으므로 감소는 세어 두고
        # 경고한다(카운터가 리셋됐거나 열이 뒤바뀐 것).
        # ⚠ 열 이름과 필드 이름 규약(`<열>_total`/`_rows`/`_regressions`)은 상수에서 파생한다 —
        #   위 필드 자기검사가 그 자리가 실제로 있는지를 import 시점에 강제한다.
        for col in cumulative_cols:
            val = _to_int(row.get(col))
            if val < 0:
                series.note_discard(col, "below_min")
                continue
            setattr(series, f"{col}_rows", getattr(series, f"{col}_rows") + 1)
            prev = prev_cumulative.get(col)
            if prev is not None and val < prev:
                setattr(
                    series, f"{col}_regressions",
                    getattr(series, f"{col}_regressions") + 1,
                )
            prev_cumulative[col] = val
            setattr(series, f"{col}_total", val)

    _add_detect_row_skip_warnings(series)
    _add_detect_discard_warnings(series)
    _add_detect_warnings(series)

    if series.rows_used == 0:
        raise FrameLogError(
            f"warmup {warmup_sec}s 이후 남은 추론 행이 없다 (rows_read={series.rows_read}, "
            f"소실 내역={series.rows_skipped}) — 측정 시간이 warmup보다 짧거나, "
            f"t_detect_recv_ns가 성한 행이 없거나, t0_ns가 이 로그와 다른 런의 것이다"
        )
    if not series.accounting_ok:  # 방어선. 깨지면 위 세 경로 밖으로 행이 샜다는 뜻이다.
        raise FrameLogError(
            f"행 회계가 맞지 않는다: rows_read={series.rows_read} != "
            f"rows_used={series.rows_used} + 소실 {series.rows_skipped_total} "
            f"({series.rows_skipped})"
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
    # 🔴 **파생 시계열 이름은 다른 문장으로 다룬다.** 일반 문구는 "OPTIONAL_COLUMNS에
    #    등록하라"고 권하는데, 이 이름들은 등록하면 위 v7 자기검사가 **import를 죽인다**
    #    (유도값은 CSV에 두지 않는 것이 규약이다). 따라갈 수 없는 조언을 자신 있게 하는 것은
    #    조용히 틀린 라벨과 같은 부류의 실패다.
    derived = [c for c in series.unknown_columns if c in FRAME_DERIVED_SERIES]
    plain = [c for c in series.unknown_columns if c not in FRAME_DERIVED_SERIES]
    if derived:
        series.warnings.append(
            f"헤더에 **하네스 파생 시계열과 같은 이름의 열**이 있다: "
            f"{', '.join(repr(c) for c in derived)} — 이 값들은 하네스가 타임스탬프에서 "
            f"계산하므로 CSV 열로 두지 않는다(유도값은 저장하지 않는다). 🔴 **OPTIONAL_COLUMNS에 "
            f"등록하지 말 것 — 등록하면 lib/frame_log.py의 자기검사가 import에서 죽인다.** "
            f"앱은 **재료 열만** 내면 된다(예: 신선도는 t_overlay_source_ns만 내고 차는 PC가 "
            f"낸다). 이 열은 집계에 전혀 쓰이지 않았고, 폰이 계산한 값과 PC가 계산한 값이 "
            f"어긋날 때 어느 쪽이 맞는지 알 수 없으므로 폰 쪽 헤더 생성부에서 뺄 것"
        )
    if plain:
        names = ", ".join(repr(c) for c in plain)
        series.warnings.append(
            f"스키마에 없는 열 {len(plain)}개를 발견했다: {names} — "
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


def check_render_arm(session: dict) -> tuple[object, bool, Optional[str]]:
    """session.json의 render_arm을 검사한다. **단계 비용 숫자의 동반자다.**

    반환: (값 원문, 어휘 안의 arm인가, 경고 문장 or None).

    ⚠ **판정선이 아니다** — `check_pipeline_stages`와 같은 취급. PASS/FAIL·exit code를
    흔들지 않는다. 다만 arm을 모르는 채로 `stage_*_ms`를 버짓 칸에 옮기면 재지도 않은
    칸이 채워지므로, 모르면 **모른다는 사실이 드러나야** 한다.

    경고를 **언제 낼지는 호출자가 정한다.** 단계 비용 열이 하나도 없는 로그(v1·패스스루)는
    옮길 숫자 자체가 없어서 위험이 없고, 그런 런까지 매번 경고하면 곧 아무도 안 본다.
    """
    vocab = ", ".join(RENDER_ARMS)
    if "render_arm" not in session:
        return None, False, (
            "session.json에 render_arm이 없다 — 이 런의 단계 비용(stage_*_ms)이 **어느 렌더 "
            "경로에서 나온 것인지 알 수 없다.** 열 이름의 [칸] 라벨은 '이 열이 어느 칸을 채울 "
            "열인가'라는 스키마 사실일 뿐이고, 그 패스가 실제로 무엇을 그렸는지는 arm이 정한다. "
            "arm 미상인 단계 비용은 FRAME_BUDGET.md의 칸에 옮기지 말 것. "
            f"허용 어휘: {vocab} (앱 android/.../gl/RenderArm.kt가 생산한다)"
        )
    raw = session.get("render_arm")
    if not isinstance(raw, str) or not raw.strip():
        return raw, False, (
            f"render_arm이 문자열이 아니거나 비어 있다({type(raw).__name__}: {raw!r}) — "
            "이 런의 단계 비용을 어느 렌더 경로의 것으로 기록할지 알 수 없다. "
            f"앱 쪽 SessionWriter를 확인할 것. 허용 어휘: {vocab}"
        )
    val = raw.strip()
    if val not in RENDER_ARMS:
        return val, False, (
            f"render_arm='{val}'은 하네스가 아는 어휘 밖이다 — 앱이 새 arm을 하네스보다 먼저 "
            "낸 것일 수 있다(집계는 그대로 진행했다). 하네스는 arm의 **의미를 해석하지 않으므로** "
            "이 런의 stage_*_ms가 각각 무엇을 그린 패스인지 확인하기 전에는 버짓 칸에 옮기지 말 것. "
            f"허용 어휘: {vocab} (목록은 lib/frame_log.py의 RENDER_ARMS와 "
            "docs/FRAME_LOG_SCHEMA.md §5)"
        )
    return val, True, None


def check_detect_ep(session: dict) -> dict:
    """session.json의 `detect.ep.requested` / `detect.ep.resolved`를 **대조만** 한다.

    🔴 **하네스가 EP를 해석하지 않는다.** ORT에 물어보지도, 기기 사양에서 유추하지도 않는다 —
    여기 있는 것은 앱이 스스로 신고한 값 두 개와, 그 둘이 같은가뿐이다. 해석을 시작하면
    앱이 EP를 바꿀 때마다 하네스가 따라가야 하고, 그 동기화가 어긋나는 날 **조용히 틀린
    라벨**이 나온다(RENDER_ARMS 주석과 같은 원칙).

    반환 dict:
      requested / resolved  : 원문 (없으면 None)
      requested_present / resolved_present : 키가 있었는가 (명시적 null과 구분)
      matches : True=같다 / False=다르다 / None=한쪽이라도 없어 **말할 수 없다**
      vocab_ok : 두 값 모두 어휘 안인가 (판정선이 아니다 — 경고만)
      warnings : 문장 리스트
    """
    req, req_present = session_field(session, DETECT_EP_REQUESTED_PATH)
    res, res_present = session_field(session, DETECT_EP_RESOLVED_PATH)
    req_field = ".".join(DETECT_EP_REQUESTED_PATH)
    res_field = ".".join(DETECT_EP_RESOLVED_PATH)
    warnings: list[str] = []

    unknown_vocab = [
        (name, val) for name, val in ((req_field, req), (res_field, res))
        if val is not None and val not in DETECT_EPS
    ]
    if unknown_vocab:
        warnings.append(
            "EP 어휘 밖 값: "
            + ", ".join(f"{name}={val!r}" for name, val in unknown_vocab)
            + f" — 앱이 새 EP를 하네스보다 먼저 낸 것일 수 있다(집계는 그대로 진행했다). "
            f"어휘가 갈리면 같은 EP의 런들이 서로 '조건 다름'이 된다. "
            f"허용 어휘: {', '.join(DETECT_EPS)} (lib/frame_log.py: DETECT_EPS)"
        )

    matches: Optional[bool]
    if req is None or res is None:
        matches = None
        missing = []
        if req is None:
            missing.append(f"{req_field}({'명시적 null' if req_present else '키 없음'})")
        if res is None:
            missing.append(f"{res_field}({'명시적 null' if res_present else '키 없음'})")
        warnings.append(
            f"요청 EP와 해소된 EP를 대조할 수 없다: {', '.join(missing)} — 이 런의 F가 어느 "
            f"실행 공급자에서 나온 값인지 **말할 수 없다**(NNAPI를 요청했는데 CPU로 폴백한 "
            f"런이 NNAPI 숫자로 인용되는 경로가 여기다). 앱이 두 값을 모두 적어야 한다"
        )
    else:
        matches = req == res
        if not matches:
            warnings.append(
                f"🔴 EP 어긋남 — {req_field}={req!r}인데 {res_field}={res!r}다. 앱이 요청한 "
                f"실행 공급자로 세션이 열리지 않았다(폴백). **이 런의 F는 {req!r}의 비용이 "
                f"아니라 {res!r}의 비용이다** — arm 이름이 무엇이든 그렇다. 이 값들은 "
                f"하네스가 해석한 것이 아니라 앱의 자진 신고 두 개를 대조한 것이다"
            )
    return {
        "requested": req,
        "resolved": res,
        "requested_present": req_present,
        "resolved_present": res_present,
        "matches": matches,
        "vocab_ok": not unknown_vocab,
        "warnings": warnings,
    }


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
        # 어느 열이 빠져 있을 수 있는지를 **선언 버전 기준으로 계산**한다. 문장에 버전 번호를
        # 손으로 박아 두면 다음 버전에서 옛 문구가 그대로 남는다(v3 하네스가 "v2에서 늘어난"
        # 이라고 말하는 상태).
        missing_cols = [
            c for c, ver in COLUMN_ADDED_IN.items() if ver > declared
        ]
        extra = ""
        # v6에서 늘어난 것은 frames.csv의 열이 아니라 **파일 하나**(detect.csv)다. 그 목록은
        # DETECT_COLUMN_ADDED_IN에 있으므로 위 컴프리헨션에 걸리지 않는다 — 여기서 함께
        # 말하지 않으면 v5 세션에 "늘어난 것: 없음"이라는 **거짓 안심**을 보내게 된다
        # (이 경고의 존재 이유가 "빠진 것을 이름으로 짚는다"인데 그것을 스스로 어기는 꼴이다).
        missing_detect = [
            c for c, ver in DETECT_COLUMN_ADDED_IN.items() if ver > declared
        ]
        if missing_detect:
            extra += f", detect.csv 자체(③ 탐지: {', '.join(missing_detect)})"
        if declared < 2:
            extra += ", session의 gl/gpu_timer 블록"
        return declared, False, (
            f"session.json이 schema_version={declared}이라고 선언했다 — 하네스는 "
            f"v{SCHEMA_VERSION}다. **앱이 하네스보다 뒤처졌다.** v{declared} 이후에 늘어난 것"
            f"(frames.csv 열: {', '.join(missing_cols) if missing_cols else '없음'}{extra})이 "
            f"이 로그에는 없을 수 있다. 읽히기는 하지만 stages 블록의 count가 0인 것은 '그 패스가 "
            f"0ms였다'가 아니라 **그 빌드가 재지 않았다**는 뜻이다. "
            f"⚠ 스키마를 확장할 때는 하네스가 앱보다 **먼저** 들어간다"
            f"(docs/FRAME_LOG_SCHEMA.md §6) — 앱 라운드가 붙기 전까지 이 경고가 뜨는 것은 "
            f"정상이고 의도된 순서다. 다만 그 기간의 로그를 최신 빌드 런과 같은 조건으로 "
            f"취급하지 말 것"
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


def _frame_discard_reason_text(name: str) -> dict:
    """그 시계열의 폐기 사유를 **그 열의 언어로** 말하는 문장 표를 고른다.

    사유 코드(`below_min` 등)는 한 경로에서 나오지만 뜻은 열마다 다르다. 엉뚱한 표를 쓰면
    폰 쪽이 잘못된 곳을 고친다 — GPU 열에 "시계 역행"이라고 쓰면 시계 코드를 뒤지고,
    카운트 열에 "0 이하"라고 쓰면 0이 폐기된다고 오해한다.
    """
    # 파생 시계열(gpu_sum_ms / stage_d_total_ms)도 GPU 쪽 문장을 쓴다 — 원본이 GPU 열이다.
    if name in GPU_TIME_COLUMNS or name in GPU_DERIVED_SERIES:
        return GPU_DISCARD_REASON_TEXT
    if name in FRAME_CPU_TIME_COLUMNS:
        return FRAME_CPU_DISCARD_REASON_TEXT
    if name in FRAME_COUNT_COLUMNS:
        return FRAME_COUNT_DISCARD_REASON_TEXT
    if name == OVERLAY_FRESHNESS_SERIES:
        return OVERLAY_FRESHNESS_DISCARD_REASON_TEXT
    return DISCARD_REASON_TEXT


def _add_discard_warnings(series: FrameSeries) -> None:
    """폐기가 1건이라도 있으면 경고로 남긴다.

    폐기된 샘플은 **그 측정의 최악 프레임일 수 있다.** rows_used와 count를 사람이 눈으로
    대조해야만 알 수 있는 상태여서는 안 된다.
    """
    for name in sorted(series.discarded):
        reasons = series.discarded[name]
        # 사유별 계수는 한 경로에서 나오지만, 열 성격에 따라 그 사유가 뜻하는 바가 다르다.
        text = _frame_discard_reason_text(name)
        detail = ", ".join(
            f"{text.get(reason, DISCARD_REASON_TEXT.get(reason, reason))} {count}개"
            for reason, count in sorted(reasons.items())
        )
        total = sum(reasons.values())
        series.warnings.append(
            f"{name}: 샘플 {total}개를 폐기했다 ({detail}). "
            f"폐기된 샘플이 그 측정의 최악 프레임일 수 있으므로 분포는 낙관적으로 치우친다"
        )

    # t_capture_ns가 섞인 시계열은 **어느 쪽 위반이든** 기준 시계 불일치를 뜻한다.
    # 음수(카메라 epoch이 우리보다 미래) / 수천 초(과거) 둘 다 같은 원인이다.
    # ⚠ v7의 capture_to_recv_ms도 같은 원인을 공유하므로 **한 문장으로 합쳐 낸다** —
    #   같은 사실을 두 문장으로 내면 원인이 둘인 것처럼 읽힌다.
    capture_series = ("capture_to_render_ms", "capture_to_recv_ms")
    per_series = {
        name: sum(series.discarded.get(name, {}).values()) for name in capture_series
    }
    capture_bad = sum(per_series.values())
    if capture_bad:
        detail = ", ".join(f"{name} {n}개" for name, n in per_series.items() if n)
        series.warnings.append(
            f"t_capture_ns 기준 시계가 우리 시계와 다른 것으로 보인다 "
            f"(물리적으로 불가능한 값: {detail}) — "
            f"글래스-투-글래스 지연은 이 로그로 판정할 수 없고, 지연을 "
            f"capture→recv / recv→render로 가르는 분해도 성립하지 않는다"
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
            f"gpu_sum_ms: {series.gpu_sum_partial_rows}개 행이 헤더에 있는 패스별 GPU 열 "
            f"{len(series.gpu_sum_columns_present)}개를 다 채우지 못한 채 합산됐다 "
            f"(있는 열: {', '.join(series.gpu_sum_columns_present)}). "
            f"빠진 패스만큼 합이 작으므로 이 분포는 아래쪽으로 치우친다"
        )
    if series.gpu_frame_conflict:
        pass_cols = [c for c in series.gpu_columns_present if c in GPU_SUM_COLUMNS]
        series.warnings.append(
            f"계측 방식이 섞여 있다 — 이 로그에 프레임 단일 query 열({GPU_FRAME_COLUMN})과 "
            f"패스별 열이 **동시에** 있다(패스별: {', '.join(pass_cols)}). "
            f"GL_TIME_ELAPSED는 중첩되지 않으므로 같은 프레임에 두 계측을 걸 수 없다 — "
            f"둘 중 하나는 그 프레임의 값이 아니며 어느 쪽인지는 이 로그만으로 알 수 없다. "
            f"**하네스는 둘 다 그대로 집계했고 어느 쪽도 버리지 않았다**: {GPU_FRAME_COLUMN}은 "
            f"gpu_sum_ms에도 {STAGE_D_TOTAL_COLUMN}에도 더하지 않았으므로 합이 이중 계상되지는 "
            f"않는다. 그래도 두 계측이 서로를 방해했을 수 있으므로 이 런의 GPU 숫자를 "
            f"버짓 칸이나 중복 계상량 계산에 쓰기 전에 앱 쪽 GpuTimerRing 구성을 확인할 것"
        )


def _add_stage_d_warnings(series: FrameSeries) -> None:
    """D 계열(D칸을 채우는 열들)에 대한 경고.

    두 가지를 말한다:
      1. **모호성** — `stage_d_ms`와 하위 패스 열이 같은 로그에 동시에 있으면 `stage_d_ms`가
         "② 전체 합"인지 "또 다른 하위 패스"인지 알 수 없다. 죽이지 않는다(앱이 스키마보다
         앞서 나갈 수 있다) 대신 **어느 해석을 썼는지 문장에 명시**한다.
      2. **부분 합** — 하위 패스 하나가 disjoint로 빠진 행은 D가 그만큼 작다.
    """
    if series.stage_d_ambiguous:
        subs = [c for c in series.stage_d_columns_present if c != "stage_d_ms"]
        series.warnings.append(
            f"D 계열이 모호하다 — 이 로그에 stage_d_ms와 ② 하위 패스 열이 **동시에** 있다"
            f"(하위: {', '.join(subs)}). stage_d_ms가 '② 전체 합'인지 '또 다른 하위 패스'인지"
            f"는 로그만으로 알 수 없다. "
            f"**하네스는 '또 다른 하위 패스'로 해석했다** — stage_d_ms를 하위 열과 동등하게 "
            f"취급해 {STAGE_D_TOTAL_COLUMN}(과 gpu_sum_ms)의 행별 합에 그대로 더했다. "
            f"근거는 **틀렸을 때의 방향**이다: 이 해석이 틀리면 D가 크게 나오고(이중 계상), "
            f"반대 해석이 틀리면 D가 작게 나온다(실재하는 패스를 뺀다). 예산 안에 든다고 "
            f"잘못 믿는 쪽이 더 비싸므로 낙관 편향을 만들지 않는 쪽을 택한다. "
            f"⚠ '스키마가 합계 열을 금지하므로'가 근거가 **아니다** — v2 스키마는 stage_d_ms를 "
            f"'② 패스(들)의 GPU 시간 합'으로 정의했으므로, v2를 지킨 생산자가 거기에 합계를 "
            f"넣는 것은 위반이 아니었다(v3부터 그 열은 '패스가 하나인 arm의 ② 패스'다). "
            f"즉 이 로그가 v2 시절 규칙으로 쓰였다면 stage_d_ms가 합계일 **가능성은 실재한다.** "
            f"그 경우 {STAGE_D_TOTAL_COLUMN}·gpu_sum_ms는 D를 두 번 센 값이므로 그대로 인용하지 "
            f"말 것 — 앱 쪽 CSV 헤더 생성부에서 어느 쪽인지 확인하고 한쪽 열을 뺀 뒤 다시 잰다"
        )
    if series.stage_d_total_partial_rows:
        series.warnings.append(
            f"{STAGE_D_TOTAL_COLUMN}: {series.stage_d_total_partial_rows}개 행이 헤더에 있는 "
            f"D 계열 열 {len(series.stage_d_columns_present)}개를 다 채우지 못한 채 합산됐다 "
            f"(있는 열: {', '.join(series.stage_d_columns_present)}). "
            f"빠진 패스만큼 D가 작으므로 이 분포는 아래쪽으로 치우친다"
        )


def _add_overlay_warnings(series: FrameSeries) -> None:
    """v7 오버레이 열에 대한 경고. `_add_gpu_warnings`·`_add_detect_warnings`와 같은 취지.

    말하는 것 셋:
      1. **열은 있는데 유효 표본이 0개다** — `count == 0`이 "그 프레임에 박스가 없었다"나
         "평활이 0ms였다"로 읽히는 것을 막는다. 0과 "재지 못했다"는 다른 사실이다.
      2. **박스 개수 없이 H를 잰 로그** — I칸·H칸은 박스 개수의 함수이므로, 개수 열이 없으면
         그 비용은 조건이 없는 숫자다.
      3. **게시 시각 없이 박스를 그린 로그** — 신선도를 낼 수 없으므로 "박스가 몇 프레임
         묶여 있었나"를 되물을 수 없다.
    """
    for col in series.overlay_columns_present:
        if col in FRAME_OVERLAY_SOURCE_COLUMNS:
            continue  # 시계열이 아니다(파생의 재료). 아래에서 신선도로 말한다
        if getattr(series, col):
            continue
        discarded = sum(series.discarded.get(col, {}).values())
        if col in FRAME_COUNT_COLUMNS:
            series.warnings.append(
                f"{col}: 열은 있는데 유효 표본이 0개다(폐기 {discarded}개). 이건 '박스를 "
                f"하나도 그리지 않았다'가 아니라 **개수를 기록하지 못했다**는 뜻이다 "
                f"(0은 폐기하지 않으므로, 0개인 프레임이 많았다면 표본은 0이 아니라 그만큼 "
                f"있어야 한다). 이 열 없이 stage_i_ms·stage_h_ms를 인용하지 말 것"
            )
        else:
            series.warnings.append(
                f"{col}: 열은 있는데 유효 표본이 0개다(폐기 {discarded}개). 이건 '그 구간이 "
                f"0ms였다'가 아니라 **재지 못했다**는 뜻이다 — 앱이 그 구간에서 -1을 쓰고 "
                f"있는지(계측 미구현), 소수 3자리 미만으로 써서 0.0이 됐는지 확인할 것"
            )
    if series.stage_h_ms and not any(
        c in series.overlay_columns_present for c in FRAME_COUNT_COLUMNS
    ):
        series.warnings.append(
            f"stage_h_ms는 있는데 {', '.join(FRAME_COUNT_COLUMNS)} 열이 없다 — H칸(그리고 "
            f"I칸)은 **박스 개수의 함수**이므로 개수 없는 이 값은 조건이 없는 숫자다. "
            f"버짓 칸에 옮길 때 개수를 함께 옮길 수 없으므로 그대로 인용하지 말 것"
        )
    if (
        any(c in series.overlay_columns_present for c in FRAME_COUNT_COLUMNS)
        and not any(
            c in series.overlay_columns_present for c in FRAME_OVERLAY_SOURCE_COLUMNS
        )
    ):
        series.warnings.append(
            f"overlay 박스 개수는 있는데 {FRAME_OVERLAY_SOURCE_COLUMNS[0]}이 없다 — "
            f"{OVERLAY_FRESHNESS_SERIES}(그 프레임이 쓴 탐지 결과의 나이)를 낼 수 없다. "
            f"탐지 갱신 지연을 숫자로 말할 수 없고, 박스가 몇 프레임 동안 같은 값에 묶여 "
            f"있었는지도 이 로그로는 되물을 수 없다"
        )


def _add_detect_unknown_column_warnings(series: DetectSeries) -> None:
    """detect.csv에 스키마 밖 열이 있으면 이름을 지목한다. **죽이지 않고 경고만.**

    `_add_unknown_column_warnings`와 같은 이유다 — 조용히 무시하면 열 이름 오타가
    "그 열이 원래 없었다"와 구분되지 않는다(예: `stage_f_ms`를 `stage_f`로 오타 내면
    F칸이 count=0이 되고 리포트는 "추론 시간을 재지 않았다"고 잘못 말한다).
    """
    if not series.unknown_columns:
        return
    names = ", ".join(repr(c) for c in series.unknown_columns)
    series.warnings.append(
        f"detect.csv에 스키마에 없는 열 {len(series.unknown_columns)}개가 있다: {names} — "
        f"이 열은 집계에 전혀 쓰이지 않았다. 열 이름 오타라면 해당 지표가 count=0이 되어 "
        f"'그 열이 없는 로그'와 구분되지 않으므로 위 이름을 폰 쪽 헤더와 대조할 것. "
        f"의도한 새 열이라면 lib/frame_log.py의 DETECT_OPTIONAL_COLUMNS와 "
        f"docs/FRAME_LOG_SCHEMA.md에 등록해야 집계에 들어온다 "
        f"(하네스가 아는 열: {', '.join(DETECT_KNOWN_COLUMNS)})"
    )


def _add_detect_row_skip_warnings(series: DetectSeries) -> None:
    """추론 행이 사라졌으면 사유별로 말한다. warmup만은 경고가 아니다(의도된 제외)."""
    for reason in DETECT_ANOMALOUS_SKIP_REASONS:
        n = series.rows_skipped.get(reason, 0)
        if not n:
            continue
        extra = ""
        if reason == "before_t0":
            # 원인이 둘이다. 하나로 단정하면 엉뚱한 곳을 뒤진다.
            # ⚠ 이 사유의 **분류 자체가 첫 실측 후 재검토 대상**이다 — (b)가 정상 런마다
            #   일어나면 anomalous가 늘 >= 1이 되어 data_complete 플래그가 쓸모없어진다
            #   (ROW_SKIP_REASONS 주석이 warmup을 이상으로 세지 않는 이유와 같은 논거).
            #   실측 건수를 보고 그대로 둘지·warmup으로 접을지·사유를 쪼갤지 정한다.
            extra = (
                " 원인은 둘 중 하나다: (a) 시계 역행, (b) 탐지가 별 use case라 첫 프레임이 "
                "렌더 쪽 첫 프레임보다 먼저 도착했다. (b)라면 개수가 적고(보통 1~2개) "
                "frames.csv 첫 행 근방의 시각이므로, 그 경우는 분석 창 밖의 앞자락일 뿐이다. "
                "⚠ 이 사유를 이상 소실로 세는 분류는 **첫 실측 후 재검토 대상**이다 — "
                "(b)가 매 런 발생하면 이 플래그가 늘 false가 되어 쓸모없어진다 "
                "(lib/frame_log.py의 _add_detect_row_skip_warnings 주석)."
            )
        series.warnings.append(
            f"추론 행 {n}개가 시계열에 들어가지 못했다 — "
            f"{DETECT_ROW_SKIP_REASON_TEXT[reason]} (rows_read={series.rows_read}, "
            f"rows_used={series.rows_used}, warmup 제외={series.rows_skipped.get('warmup', 0)}, "
            f"t0_ns={series.t0_ns}). 의도된 제외가 아니므로 이 로그의 분포는 온전한 측정이 "
            f"아니다.{extra}"
        )


def _add_detect_discard_warnings(series: DetectSeries) -> None:
    """폐기가 1건이라도 있으면 경고로 남긴다 (`_add_discard_warnings`와 같은 취지).

    폐기된 샘플은 **그 측정의 최악 추론일 수 있다.** 사유 문장은 열 성격에 따라 고른다 —
    detect 열에서 "0 이하"는 시계 역행이 아니므로 GPU/프레임 쪽 문장을 쓰면 폰 쪽이
    엉뚱하게 시계 코드를 뒤진다.
    """
    for name in sorted(series.discarded):
        reasons = series.discarded[name]
        text = (
            DETECT_COUNT_DISCARD_REASON_TEXT
            if name in DETECT_COUNT_COLUMNS or name in DETECT_SCORE_COLUMNS
            else DETECT_DISCARD_REASON_TEXT
        )
        detail = ", ".join(
            f"{text.get(reason, DISCARD_REASON_TEXT.get(reason, reason))} {count}개"
            for reason, count in sorted(reasons.items())
        )
        total = sum(reasons.values())
        series.warnings.append(
            f"{name}: 샘플 {total}개를 폐기했다 ({detail}). "
            f"폐기된 샘플이 그 측정의 최악 추론일 수 있으므로 분포는 낙관적으로 치우친다"
        )


def _add_detect_warnings(series: DetectSeries) -> None:
    """E·F·G 열이 **있는데 유효 표본이 없는** 경우 등을 말한다.

    `_add_gpu_warnings`와 같은 이유다 — 이 경고가 없으면 `stage_f_ms.count == 0`이
    "추론이 공짜였다"로 읽힐 수 있다. 0ms와 "재지 못했다"는 완전히 다른 사실이다.
    """
    if not [c for c in DETECT_TIME_COLUMNS if c in series.detect_columns_present]:
        series.warnings.append(
            f"detect.csv에 E·F·G 열({', '.join(DETECT_TIME_COLUMNS)})이 하나도 없다 — "
            f"추론 행은 {series.rows_used}개 있지만 단계 비용은 이 로그로 말할 수 없다. "
            f"앱이 구간 계측을 켜지 않은 빌드이거나 스키마 v6 이전 로그다"
        )
    for col in series.detect_columns_present:
        if col not in DETECT_TIME_COLUMNS:
            continue
        if getattr(series, col):
            continue
        discarded = sum(series.discarded.get(col, {}).values())
        series.warnings.append(
            f"{col}: 열은 있는데 유효 표본이 0개다(폐기 {discarded}개). "
            f"이건 '그 구간이 0ms였다'가 아니라 **재지 못했다**는 뜻이다 — 앱이 그 구간에서 "
            f"-1을 쓰고 있는지(계측 미구현) 확인할 것. 이 열로 단계 비용을 말하지 말 것"
        )
    # 누적 열의 감소. **열 이름을 리터럴로 쓰지 않는다** — 누적 열이 늘면 그 열의 경고가
    # 조용히 빠진다(수집 경로를 상수에서 파생시키는 것과 같은 이유).
    for col in DETECT_CUMULATIVE_COLUMNS:
        regressions = getattr(series, f"{col}_regressions", 0)
        if not regressions:
            continue
        series.warnings.append(
            f"{col}가 {regressions}번 감소했다 — "
            f"이 열은 **누적값**이라 절대 줄지 않아야 한다. 카운터가 중간에 리셋됐거나 이 열이 "
            f"'그 시점까지의 누적'이 아닌 다른 값을 담고 있다(예: 직전 구간 개수). "
            f"{col}_total={getattr(series, f'{col}_total')}은 마지막으로 관측한 "
            f"값이므로 건너뛴 총량이 아닐 수 있다 — 앱 쪽 카운터를 확인할 것"
        )


def _collect(
    series: FrameSeries | DetectSeries,
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


def _collect_nonneg(
    series: FrameSeries | DetectSeries,
    name: str,
    target: list,
    value: float,
) -> None:
    """카운트·점수용. **0을 받는다** — 시간 열과 가드가 다른 이유가 여기 있다.

    박스 0개(`boxes_out == 0` / v7의 `overlay_boxes == 0`)는 정상값이고 실제로 야간 보행
    대부분의 프레임·추론이 그렇다.
    시간 열의 하한(`> 0`)을 그대로 쓰면 그 추론들이 통째로 폐기되어 (a) 분포가 위로 치우치고
    (b) 폐기 카운트가 행 수만큼 튀어 진짜 결손을 덮는다. 기록되지 않은 값(-1)만 버린다.

    ⚠ `_collect`와 같은 이유로 **부정형**(`not (value >= 0)`)으로 쓴다 — NaN은 어떤 비교에도
    False를 돌려주므로 긍정형으로 쓰면 NaN이 통과해 백분위와 요약 JSON을 망친다.
    (`_to_float`가 이미 막지만, 여기서 한 번 더 닫아야 값의 출처와 무관하게 막힌다.)
    """
    if not (value >= 0):
        series.note_discard(name, "below_min")
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


def write_detect(
    path: Path, rows: list[dict], columns: Optional[list[str]] = None
) -> None:
    """합성 detect.csv 생성용. `write_frames`의 짝이며 **규약이 글자 그대로 같다.**

    - 열 순서는 `DETECT_REQUIRED_COLUMNS` → `DETECT_OPTIONAL_COLUMNS` 순(스키마 순서).
      행 dict의 키 순서를 따르지 않는다 — 그러면 생성 코드에 따라 헤더 순서가 흔들린다.
    - 없는 값은 **-1 센티널**(MISSING). 빈칸이 아니다 (파일 상단 §3).
    - `columns`를 주지 않으면 **행에 실제로 있는 키만** 헤더로 쓴다. 전체 열을 항상 쓰면
      스키마에 열이 늘 때마다 모든 합성 로그가 그 열을 -1로 갖게 되어 **"그 열이 없는
      로그"를 만들 수 없다** — `read_detect`의 하위호환 경로("E·F·G 열이 하나도 없다",
      "열은 있는데 표본 0개")를 생성기로 시험할 수 없게 된다.

    ⚠ **실기기 로그는 앱이 쓴다.** 이 함수를 거치지 않는다 — 여기서 쓴 CSV는 합성이며
      실측이 아니다(`render_arm`도 `RENDER_ARM_SYNTHETIC`로 선언해야 한다).
    ⚠ **중복 헤더를 만들지 않는다.** `columns`에 같은 이름을 두 번 주면 `read_detect`가
      하드 에러로 죽는다(csv.DictReader가 앞쪽 값을 파괴하므로). 그 방어선을 시험하려면
      이 함수를 쓰지 말고 CSV를 직접 써야 한다.
    """
    known = list(DETECT_REQUIRED_COLUMNS) + list(DETECT_OPTIONAL_COLUMNS)
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
