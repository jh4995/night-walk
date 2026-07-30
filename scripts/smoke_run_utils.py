"""run_utils 스모크 테스트 — STARTUP_PLAN.md §7 검증 항목.

init_run()이 run_ts 디렉토리를 만들고 run_meta.json에 git commit/dirty를
기록하는지 확인한다. 측정 하네스가 서 있는지 보는 최소 확인용.

사용법:
    python scripts/smoke_run_utils.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lib.run_utils import common_argparser, init_run  # noqa: E402


def main() -> int:
    args = common_argparser().parse_args()
    paths = init_run(stage="smoke", script_file=__file__, args=args)

    print(f"run_ts:  {paths.run_ts}")
    print(f"out_dir: {paths.out_dir}")

    if not paths.outputs_enabled:
        print("outputs disabled — nothing to verify")
        return 0

    meta_path = paths.out_dir / "run_meta.json"
    if not meta_path.exists():
        print(f"FAIL: {meta_path} not created")
        return 1

    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    print(f"git_commit: {meta.get('git_commit')}")
    print(f"git_dirty:  {meta.get('git_dirty')}")

    if not meta.get("git_commit"):
        print("FAIL: git_commit missing (git repo 없음?)")
        return 1
    if meta.get("git_dirty") is None:
        print("FAIL: git_dirty missing")
        return 1

    print("OK: run_ts dir + run_meta.json with git stamp")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
