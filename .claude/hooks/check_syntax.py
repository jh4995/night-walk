#!/usr/bin/env python
"""PostToolUse hook: 편집된 파일을 확장자별로 즉시 구문 검사한다.

밤마실은 두 트랙(Python 측정 하네스 / Android Kotlin 런타임)을 함께 쓰는데
툴체인 비용이 완전히 다르다:

  .py  -> py_compile. 수십 ms. 편집마다 돌려도 부담 없다.
  .kt  -> Gradle 컴파일은 10~60초라 편집 훅으로는 쓸 수 없다.
          ktlint가 PATH에 있을 때만 돌리고, 없으면 조용히 건너뛴다.
          (Kotlin 빌드 검증은 android-verifier의 몫)

stdin으로 Claude Code hook 이벤트 JSON을 받는다. 문제가 있으면 exit 2로
stderr를 Claude에게 되돌려주고, 그 외에는 exit 0.
"""

from __future__ import annotations

import json
import os
import py_compile
import shutil
import subprocess
import sys
import tempfile

try:  # Windows 콘솔 코드페이지와 무관하게 한글 피드백을 보존
    sys.stderr.reconfigure(encoding="utf-8")
except (AttributeError, ValueError):
    pass

# 생성물·의존물은 검사 대상이 아니다
SKIP_SEGMENTS = {"build", ".gradle", "__pycache__", ".venv", "node_modules", "outputs"}


def _target_path(event: dict) -> str:
    tool_input = event.get("tool_input") or {}
    return str(tool_input.get("file_path") or tool_input.get("path") or "")


def _skipped(norm: str) -> bool:
    return any(seg in SKIP_SEGMENTS for seg in norm.split("/"))


def _check_python(path: str) -> int:
    # 바이트코드를 레포에 남기지 않으려고 임시 파일로 뺀다 (__pycache__ 오염 방지)
    fd, cfile = tempfile.mkstemp(suffix=".pyc")
    os.close(fd)
    try:
        py_compile.compile(path, cfile=cfile, doraise=True)
    except py_compile.PyCompileError as exc:
        sys.stderr.write(f"py_compile 실패:\n{exc.msg}\n")
        return 2
    except FileNotFoundError:
        # 편집 직후 파일이 옮겨졌을 수 있다. 막지 않는다.
        return 0
    finally:
        try:
            os.unlink(cfile)
        except OSError:
            pass
    return 0


def _check_kotlin(path: str) -> int:
    ktlint = shutil.which("ktlint")
    if ktlint is None:
        # 의도된 no-op: Gradle을 편집마다 부를 수 없으므로 Kotlin 검증은 verifier가 맡는다.
        return 0
    try:
        proc = subprocess.run(
            [ktlint, path],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=60,
        )
    except (OSError, subprocess.SubprocessError):
        return 0
    if proc.returncode != 0:
        sys.stderr.write(f"ktlint 지적:\n{proc.stdout.strip()}\n")
        return 2
    return 0


def main() -> int:
    try:
        event = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        # 이벤트를 못 읽으면 열어둔다 (fail open) — 훅이 작업을 막는 쪽으로 실패하면 안 된다.
        return 0

    raw = _target_path(event)
    if not raw:
        return 0

    norm = raw.replace("\\", "/")
    if _skipped(norm):
        return 0

    if norm.endswith(".py"):
        return _check_python(raw)
    if norm.endswith(".kt") or norm.endswith(".kts"):
        return _check_kotlin(raw)
    return 0


if __name__ == "__main__":
    sys.exit(main())
