"""합성 프레임 로그 생성기 (하네스 검증용).

실기기 PoC가 아직 없으므로, 집계·판정·diff 경로를 끝까지 태우려면 입력이 필요하다.
"빈 입력으로 통과한 검사는 검사가 아니다" (measure-harness 스킬 §5).

⚠️ **여기서 나온 숫자는 측정치가 아니다.** session.json에 synthetic=true가 박히고,
분석 결과에도 그대로 실려 나간다. 실기기 숫자와 섞이지 않게 하기 위한 표식이다.

    python scripts/gen_synthetic_frames.py --duration_sec 60 --detect_every_n 3
"""

from __future__ import annotations

import json
import logging
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lib.frame_log import (  # noqa: E402
    DETECT_ENABLED_PATH,
    DETECT_EPS,
    DETECT_EP_CPU,
    DETECT_EP_REQUESTED_PATH,
    DETECT_EP_RESOLVED_PATH,
    DETECT_MODEL_SHA_PATH,
    DETECT_PADDING_FRACTION_PATH,
    DETECT_PERIOD_N_PATH,
    DETECT_REQUIRED_COLUMNS,
    GPU_FRAME_COLUMN,
    GPU_SUM_COLUMNS,
    GPU_TIME_COLUMNS,
    LIGHTING_SYNTHETIC,
    MISSING,
    PIPELINE_STAGES,
    RENDER_ARM_SYNTHETIC,
    RENDER_ARMS,
    REQUIRED_COLUMNS,
    SCHEMA_VERSION,
    STAGE2_GAMMA,
    STAGE4_HIGHLIGHT,
    STAGE_BLIT_2PASS,
    STAGE_DETECT,
    session_field,
    write_detect,
    write_frames,
)
from lib.run_utils import common_argparser, init_run  # noqa: E402

LOG = logging.getLogger(__name__)

MS = 1_000_000  # ns per ms


def main() -> int:
    parser = common_argparser()
    parser.add_argument("--duration_sec", type=float, default=60.0)
    parser.add_argument("--camera_fps", type=float, default=30.0, help="카메라 공급 속도")
    parser.add_argument("--base_render_ms", type=float, default=4.0, help="처리 없는 프레임 비용")
    parser.add_argument("--jitter_ms", type=float, default=1.5)
    parser.add_argument(
        "--detect_every_n",
        type=int,
        default=0,
        help="N프레임마다 무거운 탐지 (0=없음). §1-4의 p95 튐을 재현한다",
    )
    parser.add_argument("--detect_ms", type=float, default=45.0, help="탐지 프레임 추가 비용")
    parser.add_argument(
        "--broken_capture_clock",
        action="store_true",
        help="t_capture_ns를 다른 기준 시계로 뱉는다 (시계 함정 가드 시험용)",
    )
    parser.add_argument(
        "--render_clock_skew_sec",
        type=float,
        default=0.0,
        help=(
            "t_render_*_ns만 다른 시계로 뱉는다 (초). 폰이 t_recv에 elapsedRealtimeNanos, "
            "t_render_*에 System.nanoTime을 쓴 상황 재현 — 교차검사 시험용. "
            "양수/음수 두 방향 다 시험할 것"
        ),
    )
    # ── GPU 패스 시간 (스키마 v2). 0(기본)이면 **그 열을 아예 쓰지 않는다** —
    #    "열이 없는 로그"(v1 하위호환 입력)를 생성기로 만들 수 있어야 하기 때문이다.
    parser.add_argument(
        "--stage_b_ms", type=float, default=0.0,
        help="패스1 OES→오프스크린 GPU 시간 (버짓 B칸). 0=열 없음",
    )
    parser.add_argument(
        "--stage_d_ms", type=float, default=0.0,
        help="② 저조도 패스 GPU 시간 (버짓 D칸). 0=열 없음",
    )
    # ② 하위 패스 슬롯 (스키마 v3). GL_TIME_ELAPSED는 중첩되지 않으므로 패스별로 따로 잰다.
    # 넷 다 D 계열이라 stage_d_total_ms(행별 합)에 들어간다.
    # ⚠ 이름은 **알고리즘이 아니라 슬롯의 역할**이다(lib/frame_log.py의 STAGE_D_FAMILY_COLUMNS).
    # ⚠ --stage_d_ms와 **함께** 주면 D 계열이 모호해진다(하네스가 경고하고 해석을 밝힌다).
    #   그 경로를 일부러 태우는 것도 이 조합으로 한다.
    parser.add_argument(
        "--stage_d_analyze_ms", type=float, default=0.0,
        help=(
            "② 통계 산출 패스 GPU 시간 (버짓 D칸). 0=열 없음. "
            "예: CLAHE 히스토그램 / Drago·Reinhard 리덕션 / LIME 조도맵 추정"
        ),
    )
    parser.add_argument(
        "--stage_d_build_ms", type=float, default=0.0,
        help="② LUT·계수 생성 패스 GPU 시간 (버짓 D칸). 0=열 없음. 예: CLAHE 클립+CDF",
    )
    parser.add_argument(
        "--stage_d_apply_ms", type=float, default=0.0,
        help="② 적용 패스 GPU 시간 (버짓 D칸). 0=열 없음. 예: LUT 보간+감마 / 톤맵 / 나눗셈",
    )
    parser.add_argument(
        "--stage_d_denoise_ms", type=float, default=0.0,
        help="② 노이즈 억제 패스 GPU 시간 (버짓 D칸). 0=열 없음. 예: bilateral(+bf arm)",
    )
    # ② 두 번째 톤커브 스테이지의 같은 역할 슬롯 (스키마 v4). 조합 arm은 ② 자리에서
    # 스테이지를 두 번 돌기 때문에 analyze/build/apply가 각각 두 번 필요하다.
    # ⚠ 서수 2는 **순서**만 말하고 알고리즘을 말하지 않는다(lib/frame_log.py 주석 참고).
    parser.add_argument(
        "--stage_d_analyze2_ms", type=float, default=0.0,
        help=(
            "② 두 번째 스테이지의 통계 산출 패스 GPU 시간 (버짓 D칸). 0=열 없음. "
            "예: 조합 arm에서 1차 톤맵 결과를 다시 훑는 히스토그램"
        ),
    )
    parser.add_argument(
        "--stage_d_build2_ms", type=float, default=0.0,
        help="② 두 번째 스테이지의 LUT·계수 생성 패스 GPU 시간 (버짓 D칸). 0=열 없음",
    )
    parser.add_argument(
        "--stage_d_apply2_ms", type=float, default=0.0,
        help="② 두 번째 스테이지의 적용 패스 GPU 시간 (버짓 D칸). 0=열 없음",
    )
    parser.add_argument(
        "--stage_i_ms", type=float, default=0.0,
        help="④ 강조 패스 GPU 시간 (버짓 I칸). 0=열 없음",
    )
    parser.add_argument(
        "--gpu_present_ms", type=float, default=0.0,
        help="최종 표시 패스 GPU 시간 (버짓 칸 아님). 0=열 없음",
    )
    # ── 프레임 단일 query (스키마 v5). **위 열들과 다른 물리량이다.**
    #    실기기에서는 이 열이 있는 런에 패스별 열이 없다(GL_TIME_ELAPSED는 중첩되지 않는다).
    #    생성기는 **그 조합을 막지 않는다** — 하네스의 '계측 방식 혼재' 경고 경로를 태우려면
    #    도달 불가한 입력을 만들 수 있어야 하기 때문이다(어휘 밖 arm을 그대로 쓰는 것과 같은 취지).
    parser.add_argument(
        "--gpu_frame_ms", type=float, default=0.0,
        help=(
            "프레임 하나를 query 하나로 감싼 GPU 시간 (버짓 칸 아님, gpu_sum_ms에 더하지 "
            "않는다). 0=열 없음. 패스별 열과 함께 주면 하네스의 계측 혼재 경고 경로가 된다"
        ),
    )
    parser.add_argument(
        "--gpu_disjoint_frac", type=float, default=0.0,
        help=(
            "이 비율만큼의 행에서 GPU 열을 전부 -1로 뱉는다 (0~1). 실기기에서 disjoint "
            "구간이거나 query가 그 프레임 안에 해소되지 않으면 실제로 -1이 온다"
        ),
    )
    # ── render_arm (session.json). **어휘에서 유추하지 않고 명시 인자로 받는다.**
    #    지금까지 합성 로그에는 이 키가 없어서 GPU 열이 있는 런마다 "arm 미상" 경고가 떴다.
    #    매번 뜨는 경고는 곧 안 보게 되고, 정작 중요한 경고가 그 밑에 묻힌다.
    #
    #    기본값이 'synthetic'인 이유: 무엇을 넣었는지가 로그에 **정직하게** 남아야 한다.
    #    생성기는 어떤 셰이더도 돌리지 않았으므로 gamma_only/blit_2pass라고 적으면 거짓말이고,
    #    passthrough는 "GPU 열 자체가 없다"는 뜻이라 GPU 열을 쓴 런에 붙이면 모순이다.
    #    lighting_condition의 LIGHTING_SYNTHETIC과 같은 취급이다(어휘 안에 있으므로 경고 없음).
    #    빈 문자열을 주면 **키를 아예 쓰지 않는다** — "arm 없는 로그"(경고 경로 시험용)를
    #    생성기로 만들 수 있어야 하기 때문이다.
    parser.add_argument(
        "--render_arm", default=RENDER_ARM_SYNTHETIC,
        help=(
            f"session.json의 render_arm (기본 {RENDER_ARM_SYNTHETIC}). "
            f"''(빈 문자열)이면 키를 쓰지 않는다 = 'arm 미상' 경고 경로. "
            f"어휘: {', '.join(RENDER_ARMS)} (밖의 값도 그대로 쓴다 — 어휘 밖 경고 시험용)"
        ),
    )
    # ── pipeline_stages (session.json). **render_arm과 같은 이유로 명시 인자다.**
    #    유추(어느 --stage_*_ms를 줬는가)만으로 만들면 새 열이 생길 때마다 조용히 어긋난다.
    #    실제로 ② 하위 패스 열(--stage_d_hist/cdf/apply_ms)에는 대응 토큰이 없어서,
    #    "② 단독 비용을 재는 가장 자연스러운 호출"이 pipeline_stages=[] 인 **자기모순 로그**를
    #    낳았다(선언은 '처리 없음'인데 D 실측이 있다).
    #    기본값 None = 지금까지의 유추를 유지(옛 호출의 동작이 바뀌지 않는다).
    #    빈 문자열 = 명시적 빈 배열(빈 파이프라인 런). 콤마로 여러 토큰.
    parser.add_argument(
        "--pipeline_stages", default=None,
        help=(
            "session.json의 pipeline_stages를 콤마로 명시 (예: 'blit_2pass,stage2_gamma'). "
            "생략하면 --stage_*_ms/--detect_every_n에서 유추한다(옛 동작). "
            "''(빈 문자열)이면 빈 배열. "
            f"어휘: {', '.join(PIPELINE_STAGES)} (밖의 값도 그대로 쓴다 — 어휘 경고 시험용)"
        ),
    )
    # ── ③ 탐지 계측 detect.csv (스키마 v6) ────────────────────────────────
    # **언제 파일을 쓰는가:** `--detect_every_n > 0`일 때만. 그 인자는 이미 "N프레임마다
    # 무거운 탐지"를 뜻하므로(위) 탐지가 도는 런의 정의로 그대로 쓴다 — 같은 사실을 두
    # 인자로 나누면 둘이 어긋난 로그(프레임은 무거운데 detect.csv는 없다)가 만들어진다.
    #
    # 각 열은 GPU 열과 **같은 관행**이다: `0 = 그 열을 아예 쓰지 않는다`. 그래야
    # "E·F·G 열이 하나도 없는 detect.csv"(하위호환·경고 경로)를 생성기로 만들 수 있다.
    #
    # ⚠ **여기서 나오는 숫자는 측정치가 아니다.** 값의 크기는 인자가 정하는 것이지
    #   기기에서 잰 것이 아니다 — 이 파일의 목적은 소비자 경로(H5~H7)를 실기기 전에
    #   끝까지 태우는 것뿐이다.
    parser.add_argument(
        "--stage_e_ms", type=float, default=0.0,
        help="③ 전처리(letterbox+RGB+NCHW) CPU 벽시계 (버짓 E칸). 0=열 없음",
    )
    parser.add_argument(
        "--stage_f_ms", type=float, default=0.0,
        help="③ ORT session.run() 1회 CPU 벽시계 (버짓 F칸). 0=열 없음",
    )
    parser.add_argument(
        "--stage_g_ms", type=float, default=0.0,
        help="③ 후처리(conf+좌표변환+NMS+역변환) CPU 벽시계 (버짓 G칸). 0=열 없음",
    )
    parser.add_argument(
        "--detect_wall_extra_ms", type=float, default=2.0,
        help=(
            "E+F+G 바깥의 미계상분(프레임 대기 해제·텐서 복사·콜백 디스패치). "
            "t_detect_end_ns = recv + E+F+G + 이 값. **detect_wall_ms가 E+F+G의 합이 "
            "아니라는 사실을 재현하기 위한 인자다**"
        ),
    )
    parser.add_argument(
        "--no_detect_end", action="store_true",
        help=(
            "t_detect_end_ns 열을 쓰지 않는다 → detect_wall_ms·duty_cycle이 나오지 않는 "
            "경로(하네스가 '낼 수 없다'고 말하는지 시험)"
        ),
    )
    parser.add_argument(
        "--detect_boxes_pre_nms", type=int, default=14,
        help="NMS 전 박스 수의 기준값 (0 이상). G 비용의 설명 변수",
    )
    parser.add_argument(
        "--detect_boxes_out", type=int, default=2, help="최종 박스 수의 기준값 (0 이상)",
    )
    parser.add_argument(
        "--detect_empty_frac", type=float, default=0.25,
        help=(
            "이 비율의 추론에서 박스 0개·max_conf 0을 낸다 (0~1). **카운트 열의 하한 "
            "가드(>=0)를 태우는 입력이다** — 시간 열의 `>0`을 쓰면 이 추론들이 통째로 "
            "폐기되는데, 야간 보행에서는 이쪽이 다수다"
        ),
    )
    parser.add_argument(
        "--detect_max_conf", type=float, default=0.72,
        help="박스가 있는 추론의 최대 점수 기준값. 0=열 없음",
    )
    parser.add_argument(
        "--detect_skipped_total", type=int, default=0,
        help=(
            "skipped_while_busy(**누적값**)의 마지막 값. 행에 걸쳐 단조 증가로 채운다. "
            "0=열은 쓰되 값이 0 (하네스가 '재지 않았다'와 구분하는지 시험)"
        ),
    )
    parser.add_argument(
        "--no_detect_skipped", action="store_true",
        help="skipped_while_busy 열을 아예 쓰지 않는다 (열 없음 경로)",
    )
    parser.add_argument(
        "--detect_ep_requested", default=DETECT_EP_CPU,
        help=(
            f"session.json의 detect.ep.requested (기본 {DETECT_EP_CPU}). "
            f"''이면 키를 쓰지 않는다 = '대조할 수 없다' 경로. "
            f"어휘: {', '.join(DETECT_EPS)} (밖의 값도 그대로 쓴다 — 어휘 경고 시험용)"
        ),
    )
    parser.add_argument(
        "--detect_ep_resolved", default="",
        help=(
            "session.json의 detect.ep.resolved. 생략하면 requested와 **같게** 쓴다. "
            "다르게 주면 EP 어긋남 경로(요청과 다른 EP로 세션이 열린 런)를 만든다"
        ),
    )
    parser.add_argument(
        "--detect_model_sha256", default="",
        help="session.json의 detect.model.sha256. 기본은 null (합성 — 모델을 로드하지 않았다)",
    )
    parser.add_argument(
        "--detect_period_n", type=int, default=MISSING,
        help=(
            "session.json의 detect.period_n. 기본 -1 = **null로 쓴다** — 탐지 주기는 "
            "INTERFACES.md에서 아직 ☐ 미정이라 앱도 값을 지어내지 않는다"
        ),
    )
    parser.add_argument(
        "--detect_padding_fraction", type=float, default=0.4375,
        help=(
            "session.json의 detect.padding_pixel_fraction. 기본 0.4375 = 16:9 프레임을 "
            "640 정사각 letterbox에 넣었을 때의 패딩 픽셀 비율(1 − 360/640)"
        ),
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--label", default="synthetic")
    args = parser.parse_args()

    paths = init_run(stage="synthetic", script_file=__file__, args=args)
    if not paths.outputs_enabled:
        LOG.error("outputs가 비활성이면 생성할 위치가 없다 — --no_outputs 없이 실행할 것")
        return 2

    if not 0.0 <= args.gpu_disjoint_frac <= 1.0:
        LOG.error("--gpu_disjoint_frac는 0~1이어야 한다 (받은 값: %s)", args.gpu_disjoint_frac)
        return 2

    rng = random.Random(args.seed)
    interval_ns = int(1e9 / args.camera_fps)
    n_frames = int(args.duration_sec * args.camera_fps)

    # 열 이름 -> 기준 ms. 0이면 그 열을 쓰지 않는다.
    gpu_base = {
        "stage_b_ms": args.stage_b_ms,
        "stage_d_ms": args.stage_d_ms,
        "stage_d_analyze_ms": args.stage_d_analyze_ms,
        "stage_d_build_ms": args.stage_d_build_ms,
        "stage_d_apply_ms": args.stage_d_apply_ms,
        "stage_d_denoise_ms": args.stage_d_denoise_ms,
        "stage_d_analyze2_ms": args.stage_d_analyze2_ms,
        "stage_d_build2_ms": args.stage_d_build2_ms,
        "stage_d_apply2_ms": args.stage_d_apply2_ms,
        "stage_i_ms": args.stage_i_ms,
        "gpu_present_ms": args.gpu_present_ms,
        GPU_FRAME_COLUMN: args.gpu_frame_ms,
    }
    gpu_cols = [c for c in GPU_TIME_COLUMNS if gpu_base[c] > 0]

    # 임의 기준점 — 단조 시계는 부팅 이후 경과라 0에서 시작하지 않는다
    t = 12_345_678_901_234
    capture_offset = -8 * MS  # 노출~콜백 도착 사이 지연
    # 기준 시계가 어긋난 경우: 카메라가 완전히 다른 epoch을 쓰는 상황
    capture_skew = 9_000_000_000_000 if args.broken_capture_clock else 0
    # t_render_*만 어긋난 시계로 찍는다. 파이프라인 모델(prev_end 백프레셔)은 참시각으로
    # 돌리고, **기록할 때만** 오프셋을 얹는다 — 그래야 "로그만 틀린" 상황이 정확히 재현된다.
    render_skew = int(args.render_clock_skew_sec * 1e9)

    # 파이프라인은 프레임을 **직렬로** 처리한다. 앞 프레임이 끝나기 전에 도착한 프레임은
    # 큐에 쌓이는 게 아니라 버려진다 — CameraX STRATEGY_KEEP_ONLY_LATEST
    # (android-runtime 스킬 §2: 큐에 쌓으면 프레임타임이 실제보다 좋아 보이고 지연만 는다).
    # 이 모델이 없으면 뒤 프레임이 앞 프레임보다 먼저 끝나는 불가능한 로그가 나온다.
    rows = []
    prev_end: int | None = None
    dropped_accum = 0
    emitted = 0
    gpu_disjoint_rows = 0
    # ③ 탐지가 실제로 돈 프레임의 **수신 시각**. detect.csv는 이 목록에서 나온다 —
    # 두 파일이 같은 시계(그리고 같은 t0)를 쓰는 것이 이 생성기의 계약이다.
    detect_recv_times: list[int] = []

    for i in range(n_frames):
        t_recv = t + i * interval_ns + int(rng.uniform(-1.0, 1.0) * args.jitter_ms * MS)

        if prev_end is not None and prev_end > t_recv:
            dropped_accum += 1  # 파이프라인이 아직 바쁘다 → 이 프레임은 버린다
            continue

        cost_ms = args.base_render_ms + abs(rng.gauss(0, args.jitter_ms))
        if args.detect_every_n > 0 and emitted % args.detect_every_n == 0:
            cost_ms += args.detect_ms
            # 탐지 스레드가 이 프레임을 받았다. 아래에서 detect.csv 한 행이 된다.
            detect_recv_times.append(t_recv)

        # GPU 패스 시간. **먼저 참값을 만든다** — disjoint는 측정이 실패한 것이지
        # GPU가 일을 안 한 게 아니므로, 아래 파이프라인 비용에는 참값이 들어가야 한다.
        gpu_true = {
            c: gpu_base[c] * (1.0 + rng.uniform(-0.15, 0.15)) for c in gpu_cols
        }
        # ⚠ **프레임 단일 query 열은 패스별 합에 더하지 않는다** — 같은 프레임을 두 번 세는
        #   것이고, 하네스의 gpu_sum_ms 정의(GPU_SUM_COLUMNS)와도 어긋난다. 프레임 query는
        #   패스들을 **감싼** 값이므로 둘 중 큰 쪽이 그 프레임의 GPU 점유다.
        gpu_total_ms = max(
            sum(v for c, v in gpu_true.items() if c in GPU_SUM_COLUMNS),
            gpu_true.get(GPU_FRAME_COLUMN, 0.0),
        )
        # swapBuffers는 GPU가 끝나기를 기다리므로, GPU 총합이 CPU 제출 비용보다 크면
        # 그쪽이 프레임 비용을 지배한다. 이 모델이 없으면 stage_d를 아무리 키워도
        # 프레임타임이 안 변해서 "카메라 공급에 묶임" 단서만 늘 참이 된다.
        cost_ms = max(cost_ms, gpu_total_ms)

        # 이 프레임의 timer query가 해소되지 않았는가 (실기기의 -1 재현)
        disjoint = args.gpu_disjoint_frac > 0 and rng.random() < args.gpu_disjoint_frac

        t_render_start = t_recv + int(rng.uniform(0.2, 1.0) * MS)
        t_render_end = t_render_start + int(cost_ms * MS)

        row = {
            "frame_idx": emitted,
            "t_recv_ns": t_recv,
            "t_capture_ns": t_recv + capture_offset + capture_skew,
            "t_render_start_ns": t_render_start + render_skew,
            "t_render_end_ns": t_render_end + render_skew,
            "dropped_since_last": dropped_accum,
        }
        for c in gpu_cols:
            row[c] = MISSING if disjoint else round(gpu_true[c], 3)
        if disjoint:
            gpu_disjoint_rows += 1
        rows.append(row)
        prev_end = t_render_end
        dropped_accum = 0
        emitted += 1

    frames_path = paths.out_dir / "frames.csv"
    # 열 목록을 명시한다. GPU 열을 안 쓰면 헤더에 아예 넣지 않는다 —
    # "-1로 가득 찬 열"과 "열이 없다"는 하네스가 구분해야 하는 다른 사실이다.
    columns = [
        *REQUIRED_COLUMNS,
        "t_capture_ns",
        "t_render_start_ns",
        "t_render_end_ns",
        "dropped_since_last",
        *gpu_cols,
    ]
    write_frames(frames_path, rows, columns=columns)

    # ── ③ detect.csv (v6). **행 하나 = 추론 1회이며 프레임 행과 개수가 다르다.**
    #    이 파일을 쓰는 유일한 조건은 `--detect_every_n > 0`이다(위 인자 블록 주석).
    detect_path = paths.out_dir / "detect.csv"
    detect_rows: list[dict] = []
    detect_columns: list[str] = []
    detect_time_base = {
        "stage_e_ms": args.stage_e_ms,
        "stage_f_ms": args.stage_f_ms,
        "stage_g_ms": args.stage_g_ms,
    }
    detect_time_cols = [c for c, v in detect_time_base.items() if v > 0]
    if args.detect_every_n > 0:
        detect_columns = list(DETECT_REQUIRED_COLUMNS)
        if not args.no_detect_end:
            detect_columns.append("t_detect_end_ns")
        if args.detect_max_conf > 0:
            detect_columns.append("max_conf")
        detect_columns += detect_time_cols
        detect_columns += ["boxes_pre_nms", "boxes_out"]
        if not args.no_detect_skipped:
            detect_columns.append("skipped_while_busy")

        n_detect = len(detect_recv_times)
        for idx, t_recv in enumerate(detect_recv_times):
            # 값마다 ±15% 흔든다 — 분포가 한 점이면 백분위 검사가 검사가 아니다.
            times = {
                c: detect_time_base[c] * (1.0 + rng.uniform(-0.15, 0.15))
                for c in detect_time_cols
            }
            empty = rng.random() < args.detect_empty_frac
            if empty:
                # 🔴 **박스 0개는 정상값이다.** 카운트 열의 하한이 `>= 0`이어야 하는 이유가
                #    이 행들이고, 실제 야간 보행에서는 이쪽이 다수다.
                pre_nms, boxes_out, conf = 0, 0, 0.0
            else:
                pre_nms = max(0, int(round(rng.gauss(
                    args.detect_boxes_pre_nms, max(1.0, args.detect_boxes_pre_nms * 0.3)
                ))))
                boxes_out = max(0, min(pre_nms, int(round(rng.gauss(
                    args.detect_boxes_out, max(1.0, args.detect_boxes_out * 0.5)
                )))))
                conf = min(0.999, max(0.0, rng.gauss(args.detect_max_conf, 0.08)))
            row = {
                "detect_idx": idx,   # **프레임 번호가 아니다**
                "t_detect_recv_ns": t_recv,
                "boxes_pre_nms": pre_nms,
                "boxes_out": boxes_out,
            }
            for c, v in times.items():
                # 🔴 소수 3자리로 쓴다. 앱에도 같은 정밀도를 요구한다 — 박스 0개면 G가
                #    진짜로 0.0x ms인데 1자리로 쓰면 0.0이 되고, 하네스의 하한 가드(>0)가
                #    **가장 싼 샘플만 골라 폐기**해 G 분포가 위로 치우친다.
                row[c] = round(v, 3)
            if not args.no_detect_end:
                # span = E+F+G + 미계상분. **E+F+G의 합이 아니다**(그게 요점이다).
                span_ms = sum(times.values()) + args.detect_wall_extra_ms
                row["t_detect_end_ns"] = t_recv + int(span_ms * MS)
            if args.detect_max_conf > 0:
                row["max_conf"] = round(conf, 4)
            if not args.no_detect_skipped:
                # 누적값. 마지막 행이 --detect_skipped_total이 되도록 단조 증가로 채운다.
                row["skipped_while_busy"] = int(
                    round(args.detect_skipped_total * (idx + 1) / max(1, n_detect))
                )
            detect_rows.append(row)
        write_detect(detect_path, detect_rows, columns=detect_columns)

    # 파이프라인 단계 목록 — 어느 arm으로 잰 것인지가 비교 조건이다(baseline_diff).
    # ⚠ 토큰은 **앱 어휘**(lib/frame_log.py의 PIPELINE_STAGES = android RenderArm.pipelineStages)를
    #   그대로 쓴다. 생성기가 자기 이름을 따로 쓰면(예전의 'pass1_oes_to_offscreen' /
    #   'stage2_lowlight') 같은 구조인데도 합성 런과 실측 런이 영원히 "조건 다름"이 된다.
    # ⚠ ② 하위 패스(--stage_d_hist/cdf/apply)에 대응하는 PIPELINE_STAGES 토큰은 **아직 없다.**
    #   그 토큰의 생산자는 앱(RenderArm.pipelineStages)이고, 하네스가 먼저 이름을 지어 두면
    #   앱이 다른 이름을 쓰는 날 같은 구조가 두 이름으로 갈려 모든 비교가 "조건 다름"이 된다.
    #   (열 이름과 render_arm은 미리 등록해야 집계가 되지만, pipeline_stages는 그렇지 않다.)
    pipeline_stages: list[str] = []
    if args.pipeline_stages is not None:
        # 명시 지정. 하네스는 여기서 어휘를 강제하지 않는다(어휘 밖 경고 경로를 만들 수
        # 있어야 한다) — 준 것을 그대로 적는 것이 "정직하게 남는다"의 뜻이다.
        pipeline_stages = [
            s.strip() for s in args.pipeline_stages.split(",") if s.strip()
        ]
    else:
        if args.stage_b_ms > 0:
            pipeline_stages.append(STAGE_BLIT_2PASS)
        if args.stage_d_ms > 0:
            pipeline_stages.append(STAGE2_GAMMA)
        if args.detect_every_n > 0:
            pipeline_stages.append(STAGE_DETECT)
        if args.stage_i_ms > 0:
            pipeline_stages.append(STAGE4_HIGHLIGHT)
        # 유추로는 담기지 않는 열이 있다. 그 상태로 두면 집계가 "단계 선언과 실측이
        # 어긋난다"고 경고하는 자기모순 로그가 나오므로, 생성 시점에 미리 알린다.
        if not pipeline_stages and gpu_cols:
            LOG.warning(
                "pipeline_stages를 유추했더니 빈 배열인데 GPU 단계 열은 있다(%s) — "
                "집계가 '단계 선언과 실측이 어긋난다'고 경고하는 로그가 된다. "
                "그 모순 경로를 일부러 만드는 게 아니라면 --pipeline_stages로 명시할 것 "
                "(② 하위 패스 열에는 아직 대응 토큰이 없다 — 생산자는 앱이다)",
                ", ".join(gpu_cols),
            )

    session = {
        "schema_version": SCHEMA_VERSION,
        "synthetic": True,
        "generated_by": "scripts/gen_synthetic_frames.py",
        "generator_run_ts": paths.run_ts,
        "seed": args.seed,
        "build_type": "synthetic",
        # 조명 조건도 "synthetic"으로 박는다 — 합성 로그가 실기기 런과 조건이 같은 척하면
        # baseline_diff가 조용히 비교 가능하다고 판정한다. 어휘는 lib/frame_log.py.
        "lighting_condition": LIGHTING_SYNTHETIC,
        "label": args.label,
        # 이 로그의 t_capture_ns가 어느 시계 기준인지. 생성기는 알고 있으므로 그대로 적는다
        # (실기기에서는 폰 쪽이 채운다). analyze_frames가 관측과 대조한다.
        "capture_clock_base": "unknown" if args.broken_capture_clock else "monotonic",
        # 빈 파이프라인이면 빈 리스트 — analyze_frames가 이걸 보고 해석 단서를 붙인다
        # (비어 있지 않으면 "프레임타임이 공급에 묶였나"를 관측으로 다시 본다)
        "pipeline_stages": pipeline_stages,
        "camera": {
            "requested_fps": args.camera_fps,
            "resolution": "1280x720",
        },
        # GPU timer 선언. 실기기에서는 앱이 확장 존재 여부를 적는다. 여기서는 GPU 열을
        # 하나라도 쓰면 supported=true로 박는다 — 그래야 "선언은 true인데 표본 0"
        # (전부 disjoint) 모순 경로를 실기기 없이 태울 수 있다.
        "gpu_timer": {
            "supported": bool(gpu_cols),
            "extension": "GL_EXT_disjoint_timer_query" if gpu_cols else None,
            "synthetic": True,
        },
        # ③ 탐지 블록. **키 이름의 주인은 lib/frame_log.py의 DETECT_*_PATH 상수다** —
        # 여기 문자열이 그것과 어긋나면 소비자(analyze_frames/pull_frames/run_session)가
        # 못 읽는다. 아래 조립 뒤에 상수와 대조해 어긋나면 죽인다.
        "detect": {
            # 🔴 이 값이 pull_frames의 반쪽 회수 판정 기준이다(true인데 detect.csv가 없으면 실패).
            "enabled": bool(detect_rows),
            "synthetic": True,
            "model": {
                # 합성이므로 기본은 null — 모델을 로드한 적이 없다. 가짜 해시를 박으면
                # "어느 모델의 F인가"라는 질문에 거짓으로 답하는 것이 된다.
                "sha256": args.detect_model_sha256 or None,
            },
            "ep": {
                "requested": args.detect_ep_requested or None,
                "resolved": (
                    (args.detect_ep_resolved or args.detect_ep_requested) or None
                ),
            },
            # 앱도 null로 낸다 — 탐지 주기는 INTERFACES.md에서 아직 ☐ 미정이다.
            "period_n": None if args.detect_period_n < 0 else args.detect_period_n,
            "padding_pixel_fraction": args.detect_padding_fraction,
            "detections_written": len(detect_rows),
            "detect_columns_written": detect_columns,
        },
        "params": {
            "render_arm_arg": args.render_arm,
            "pipeline_stages_arg": args.pipeline_stages,
            "duration_sec": args.duration_sec,
            "base_render_ms": args.base_render_ms,
            "detect_every_n": args.detect_every_n,
            "detect_ms": args.detect_ms,
            "broken_capture_clock": args.broken_capture_clock,
            "render_clock_skew_sec": args.render_clock_skew_sec,
            "stage_b_ms": args.stage_b_ms,
            "stage_d_ms": args.stage_d_ms,
            "stage_d_analyze_ms": args.stage_d_analyze_ms,
            "stage_d_build_ms": args.stage_d_build_ms,
            "stage_d_apply_ms": args.stage_d_apply_ms,
            "stage_d_denoise_ms": args.stage_d_denoise_ms,
            "stage_d_analyze2_ms": args.stage_d_analyze2_ms,
            "stage_d_build2_ms": args.stage_d_build2_ms,
            "stage_d_apply2_ms": args.stage_d_apply2_ms,
            "stage_i_ms": args.stage_i_ms,
            "gpu_present_ms": args.gpu_present_ms,
            "gpu_frame_ms": args.gpu_frame_ms,
            "gpu_disjoint_frac": args.gpu_disjoint_frac,
            "stage_e_ms": args.stage_e_ms,
            "stage_f_ms": args.stage_f_ms,
            "stage_g_ms": args.stage_g_ms,
            "detect_wall_extra_ms": args.detect_wall_extra_ms,
            "no_detect_end": args.no_detect_end,
            "detect_boxes_pre_nms": args.detect_boxes_pre_nms,
            "detect_boxes_out": args.detect_boxes_out,
            "detect_empty_frac": args.detect_empty_frac,
            "detect_max_conf": args.detect_max_conf,
            "detect_skipped_total": args.detect_skipped_total,
            "no_detect_skipped": args.no_detect_skipped,
            "detect_ep_requested": args.detect_ep_requested,
            "detect_ep_resolved": args.detect_ep_resolved,
        },
        "gpu_columns_written": gpu_cols,
        "gpu_disjoint_rows": gpu_disjoint_rows,
        "camera_frames_offered": n_frames,
        "frames_emitted": len(rows),
        "frames_dropped": n_frames - len(rows),
    }
    # render_arm은 **값을 준 경우에만** 싣는다. 빈 문자열은 "키가 없는 로그"를 만들려는
    # 명시적 선택이고(하네스의 'arm 미상' 경고 경로 시험용), 여기서 기본값을 몰래 채우면
    # 그 입력을 생성기로 만들 수 없게 된다 — GPU 열 0=열 없음 규칙과 같은 이유다.
    if args.render_arm != "":
        session["render_arm"] = args.render_arm

    # 🔴 **생성한 detect 블록을 소비자가 실제로 읽을 수 있는가**를 여기서 확인한다.
    #    키 이름을 손으로 적었으므로(위 dict 리터럴) 상수와 어긋날 수 있고, 어긋나면
    #    "합성 로그로 소비자 경로를 태웠다"는 검증 자체가 거짓이 된다 — 소비자는 아무것도
    #    못 읽은 채 조용히 null을 보고, 우리는 통과했다고 착각한다.
    if detect_rows:
        for path_const in (
            DETECT_ENABLED_PATH, DETECT_MODEL_SHA_PATH, DETECT_EP_REQUESTED_PATH,
            DETECT_EP_RESOLVED_PATH, DETECT_PERIOD_N_PATH, DETECT_PADDING_FRACTION_PATH,
        ):
            _, present = session_field(session, path_const)
            if not present:
                LOG.error(
                    "생성한 session.json에 %s 키가 없다 — lib/frame_log.py의 경로 상수와 "
                    "어긋났다. 이 로그로는 소비자 경로를 태울 수 없다",
                    ".".join(path_const),
                )
                return 2

    session_path = paths.out_dir / "session.json"
    with session_path.open("w", encoding="utf-8") as f:
        json.dump(session, f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")

    LOG.info("합성 프레임 %d개 생성 (합성 데이터 — 측정치가 아님)", len(rows))
    LOG.info(
        "  render_arm: %s",
        session.get("render_arm", "(키 없음 — 하네스가 'arm 미상'으로 경고한다)"),
    )
    if gpu_cols:
        LOG.info(
            "  GPU 열: %s (disjoint로 -1 처리된 행 %d개)",
            ", ".join(gpu_cols), gpu_disjoint_rows,
        )
    LOG.info("  frames : %s", frames_path)
    LOG.info("  session: %s", session_path)
    detect_arg = ""
    if detect_rows:
        detect_arg = f" --detect {detect_path}"
        LOG.info(
            "  detect : %s — 추론 %d행 (**프레임 %d행과 다른 표본이다**), 열: %s",
            detect_path, len(detect_rows), len(rows), ", ".join(detect_columns),
        )
        LOG.warning(
            "  ⚠ 이 detect.csv의 E·F·G는 **인자로 만든 값이지 측정치가 아니다.** "
            "여기서 나온 F를 버짓 F칸에 옮기지 말 것 — 이 파일의 목적은 소비자 경로"
            "(analyze_frames --detect / pull_frames / run_session)를 실기기 전에 태우는 "
            "것뿐이다. render_arm=%s·lighting_condition=%s·build_type=synthetic 세 가지가 "
            "그 사실을 로그에 박아 둔다",
            session.get("render_arm", "(키 없음)"), LIGHTING_SYNTHETIC,
        )
    elif args.detect_every_n > 0:
        LOG.warning(
            "  --detect_every_n=%s인데 탐지 행이 0개다 — 프레임이 하나도 방출되지 않았다",
            args.detect_every_n,
        )
    LOG.info(
        "다음: python scripts/analyze_frames.py --frames %s --session %s%s --warmup_sec 0",
        frames_path, session_path, detect_arg,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
