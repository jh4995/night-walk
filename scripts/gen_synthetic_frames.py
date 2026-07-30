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

from lib.frame_log import SCHEMA_VERSION, write_frames  # noqa: E402
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
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--label", default="synthetic")
    args = parser.parse_args()

    paths = init_run(stage="synthetic", script_file=__file__, args=args)
    if not paths.outputs_enabled:
        LOG.error("outputs가 비활성이면 생성할 위치가 없다 — --no_outputs 없이 실행할 것")
        return 2

    rng = random.Random(args.seed)
    interval_ns = int(1e9 / args.camera_fps)
    n_frames = int(args.duration_sec * args.camera_fps)

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

    for i in range(n_frames):
        t_recv = t + i * interval_ns + int(rng.uniform(-1.0, 1.0) * args.jitter_ms * MS)

        if prev_end is not None and prev_end > t_recv:
            dropped_accum += 1  # 파이프라인이 아직 바쁘다 → 이 프레임은 버린다
            continue

        cost_ms = args.base_render_ms + abs(rng.gauss(0, args.jitter_ms))
        if args.detect_every_n > 0 and emitted % args.detect_every_n == 0:
            cost_ms += args.detect_ms

        t_render_start = t_recv + int(rng.uniform(0.2, 1.0) * MS)
        t_render_end = t_render_start + int(cost_ms * MS)

        rows.append(
            {
                "frame_idx": emitted,
                "t_recv_ns": t_recv,
                "t_capture_ns": t_recv + capture_offset + capture_skew,
                "t_render_start_ns": t_render_start + render_skew,
                "t_render_end_ns": t_render_end + render_skew,
                "dropped_since_last": dropped_accum,
            }
        )
        prev_end = t_render_end
        dropped_accum = 0
        emitted += 1

    frames_path = paths.out_dir / "frames.csv"
    write_frames(frames_path, rows)

    session = {
        "schema_version": SCHEMA_VERSION,
        "synthetic": True,
        "generated_by": "scripts/gen_synthetic_frames.py",
        "generator_run_ts": paths.run_ts,
        "seed": args.seed,
        "build_type": "synthetic",
        "label": args.label,
        # 이 로그의 t_capture_ns가 어느 시계 기준인지. 생성기는 알고 있으므로 그대로 적는다
        # (실기기에서는 폰 쪽이 채운다). analyze_frames가 관측과 대조한다.
        "capture_clock_base": "unknown" if args.broken_capture_clock else "monotonic",
        # 빈 파이프라인이면 빈 리스트 — analyze_frames가 이걸 보고 해석 단서를 붙인다
        "pipeline_stages": (["detect"] if args.detect_every_n > 0 else []),
        "camera": {
            "requested_fps": args.camera_fps,
            "resolution": "1280x720",
        },
        "params": {
            "duration_sec": args.duration_sec,
            "base_render_ms": args.base_render_ms,
            "detect_every_n": args.detect_every_n,
            "detect_ms": args.detect_ms,
            "broken_capture_clock": args.broken_capture_clock,
            "render_clock_skew_sec": args.render_clock_skew_sec,
        },
        "camera_frames_offered": n_frames,
        "frames_emitted": len(rows),
        "frames_dropped": n_frames - len(rows),
    }
    session_path = paths.out_dir / "session.json"
    with session_path.open("w", encoding="utf-8") as f:
        json.dump(session, f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")

    LOG.info("합성 프레임 %d개 생성 (합성 데이터 — 측정치가 아님)", len(rows))
    LOG.info("  frames : %s", frames_path)
    LOG.info("  session: %s", session_path)
    LOG.info("다음: python scripts/analyze_frames.py --frames %s --session %s --warmup_sec 0",
             frames_path, session_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
