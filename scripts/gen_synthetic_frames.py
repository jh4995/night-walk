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
    GPU_TIME_COLUMNS,
    LIGHTING_SYNTHETIC,
    MISSING,
    REQUIRED_COLUMNS,
    SCHEMA_VERSION,
    STAGE2_GAMMA,
    STAGE4_HIGHLIGHT,
    STAGE_BLIT_2PASS,
    STAGE_DETECT,
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
    parser.add_argument(
        "--stage_i_ms", type=float, default=0.0,
        help="④ 강조 패스 GPU 시간 (버짓 I칸). 0=열 없음",
    )
    parser.add_argument(
        "--gpu_present_ms", type=float, default=0.0,
        help="최종 표시 패스 GPU 시간 (버짓 칸 아님). 0=열 없음",
    )
    parser.add_argument(
        "--gpu_disjoint_frac", type=float, default=0.0,
        help=(
            "이 비율만큼의 행에서 GPU 열을 전부 -1로 뱉는다 (0~1). 실기기에서 disjoint "
            "구간이거나 query가 그 프레임 안에 해소되지 않으면 실제로 -1이 온다"
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
        "stage_i_ms": args.stage_i_ms,
        "gpu_present_ms": args.gpu_present_ms,
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

    for i in range(n_frames):
        t_recv = t + i * interval_ns + int(rng.uniform(-1.0, 1.0) * args.jitter_ms * MS)

        if prev_end is not None and prev_end > t_recv:
            dropped_accum += 1  # 파이프라인이 아직 바쁘다 → 이 프레임은 버린다
            continue

        cost_ms = args.base_render_ms + abs(rng.gauss(0, args.jitter_ms))
        if args.detect_every_n > 0 and emitted % args.detect_every_n == 0:
            cost_ms += args.detect_ms

        # GPU 패스 시간. **먼저 참값을 만든다** — disjoint는 측정이 실패한 것이지
        # GPU가 일을 안 한 게 아니므로, 아래 파이프라인 비용에는 참값이 들어가야 한다.
        gpu_true = {
            c: gpu_base[c] * (1.0 + rng.uniform(-0.15, 0.15)) for c in gpu_cols
        }
        gpu_total_ms = sum(gpu_true.values())
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

    # 파이프라인 단계 목록 — 어느 arm으로 잰 것인지가 비교 조건이다(baseline_diff).
    # ⚠ 토큰은 **앱 어휘**(lib/frame_log.py의 PIPELINE_STAGES = android RenderArm.pipelineStages)를
    #   그대로 쓴다. 생성기가 자기 이름을 따로 쓰면(예전의 'pass1_oes_to_offscreen' /
    #   'stage2_lowlight') 같은 구조인데도 합성 런과 실측 런이 영원히 "조건 다름"이 된다.
    pipeline_stages: list[str] = []
    if args.stage_b_ms > 0:
        pipeline_stages.append(STAGE_BLIT_2PASS)
    if args.stage_d_ms > 0:
        pipeline_stages.append(STAGE2_GAMMA)
    if args.detect_every_n > 0:
        pipeline_stages.append(STAGE_DETECT)
    if args.stage_i_ms > 0:
        pipeline_stages.append(STAGE4_HIGHLIGHT)

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
        "params": {
            "duration_sec": args.duration_sec,
            "base_render_ms": args.base_render_ms,
            "detect_every_n": args.detect_every_n,
            "detect_ms": args.detect_ms,
            "broken_capture_clock": args.broken_capture_clock,
            "render_clock_skew_sec": args.render_clock_skew_sec,
            "stage_b_ms": args.stage_b_ms,
            "stage_d_ms": args.stage_d_ms,
            "stage_i_ms": args.stage_i_ms,
            "gpu_present_ms": args.gpu_present_ms,
            "gpu_disjoint_frac": args.gpu_disjoint_frac,
        },
        "gpu_columns_written": gpu_cols,
        "gpu_disjoint_rows": gpu_disjoint_rows,
        "camera_frames_offered": n_frames,
        "frames_emitted": len(rows),
        "frames_dropped": n_frames - len(rows),
    }
    session_path = paths.out_dir / "session.json"
    with session_path.open("w", encoding="utf-8") as f:
        json.dump(session, f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")

    LOG.info("합성 프레임 %d개 생성 (합성 데이터 — 측정치가 아님)", len(rows))
    if gpu_cols:
        LOG.info(
            "  GPU 열: %s (disjoint로 -1 처리된 행 %d개)",
            ", ".join(gpu_cols), gpu_disjoint_rows,
        )
    LOG.info("  frames : %s", frames_path)
    LOG.info("  session: %s", session_path)
    LOG.info("다음: python scripts/analyze_frames.py --frames %s --session %s --warmup_sec 0",
             frames_path, session_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
