---
name: measure-harness
description: 밤마실 측정·MLOps 하네스(Python) 트랙의 구현·검증 규칙. run_utils.init_run 사용법과 outputs 디렉토리 규약, 새 측정 스크립트 작성 템플릿, 백분위 계산 규약, baseline diff로 회귀를 판정하는 방법, 검증 시 실제 실행으로 확인하는 원칙을 정의한다.
user-invocable: false
---

# 측정·MLOps 하네스 트랙 규칙

밤마실에서 **숫자를 만들고 보관하고 비교하는 부분** 전부.
팀원2가 팀에서 가져온 오너십(실기기 성능·안전 회귀 검증)의 물리적 구현체다.

---

## 1. run_utils 사용법 (모든 측정 스크립트의 출발점)

```python
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lib.run_utils import common_argparser, init_run

def main() -> int:
    parser = common_argparser()
    parser.add_argument("--my_option", default="")   # 스크립트 고유 인자만 추가
    args = parser.parse_args()

    paths = init_run(stage="<단계이름>", script_file=__file__, args=args)
    # paths.out_dir  -> outputs/<stage>/<run_ts>/   결과는 전부 여기로
    # paths.run_ts   -> 이 실행의 식별자
    # paths.outputs_enabled -> False면 파일을 쓰지 않는다
    ...
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
```

### `init_run()`이 자동으로 하는 것

| 산출물 | 내용 |
|---|---|
| `outputs/<stage>/<run_ts>/run_meta.json` | **git commit·dirty·플랫폼·argv·python 버전** |
| `outputs/logs/<stage>/<script>_<run_ts>.cmd.txt` | 실행한 명령 원문 |
| `outputs/logs/<stage>/<script>_<run_ts>.log` | 로그 |

`run_meta.json`은 **무조건** 기록된다. 스탬프가 옵션이면 붙이는 걸 잊은 측정치가 생기기 때문이다.

### 비활성화 플래그

`--no_outputs` / `--no_cmdlog`, 또는 환경변수 `NW_NO_OUTPUTS=1` / `NW_NO_CMDLOG=1`.
스크립트는 `paths.outputs_enabled`를 확인해 **비활성화 상태에서도 죽지 않아야** 한다.

## 2. 규약

- `outputs/`는 git 추적하지 않는다. **결과 파일을 손으로 만들지 않는다.**
- `stage` 이름은 측정 종류를 나타낸다 (`smoke`, `poc_baseline`, `perf_regression` 등).
  같은 종류의 측정은 같은 stage를 쓴다 — 그래야 나중에 시계열로 비교된다.
- 콘솔에 한글·`①`·`—`를 출력할 때 Windows cp949에서 죽지 않도록,
  `init_run()`이 호출하는 `ensure_utf8_console()`에 의존한다. 직접 `print` 하는 별도 진입점을
  만들 때는 그 함수를 먼저 부른다.

## 3. 백분위 계산 규약

평균만 내는 측정 코드는 목표(`p95 < 80ms`)를 판정할 수 없다. 항상 분포로 낸다.

```python
def percentile(sorted_values: list[float], p: float) -> float:
    if not sorted_values:
        return 0.0
    rank = max(1, min(len(sorted_values), math.ceil(p * len(sorted_values))))
    return sorted_values[rank - 1]
```

- 최소 **p50 / p95 / p99 + min / max**를 낸다.
- 판정선은 `FRAME_BUDGET.md`에서 가져온다: 프레임당 **66.7ms**, p95 **80ms**.
- 판정 결과를 요약 JSON에 **불리언으로 명시**한다 (`meets_fps_target`, `meets_p95_target`).
  사람이 표를 읽고 판단하게 두지 않는다.

## 4. baseline diff — 회귀를 판정하는 방식

**결정적 계약 검증(1회면 충분)과 확률적 성능 측정(N회 분포)은 다른 활동이다.**

| | 계약 검증 | 성능 측정 |
|---|---|---|
| 성격 | 스키마·반환 키·안전규칙 | 프레임타임 분포 |
| 실행 | 1회 PASS/FAIL | N회 → 분포 비교 |
| 판정 | 맞다/틀리다 | **이전 baseline 대비 나아졌나/나빠졌나** |

- 성능 변경을 판정할 때는 **이전 `run_ts`의 요약과 diff**한다. 단일 실행 숫자만으로
  "빨라졌다"고 말하지 않는다.
- 변경 전후 측정은 **같은 조건**에서 한다(같은 기기, 같은 빌드 타입, 같은 지속 시간).
  조건이 다르면 비교가 아니라 착시다.
- 성능이 좋아졌다고 보고할 때는 **안전 회귀 결과를 함께** 낸다
  (`nightwalk-conventions` §6 — 한쪽만 있는 보고는 불완전한 보고다).

## 5. 검증 (harness-verifier용)

- **실제로 실행해서 확인한다.** 코드를 읽고 "맞을 것 같다"로 판정하지 않는다.
- **빈 입력으로 통과한 검사는 검사가 아니다.** 백분위·집계 로직은 비어 있지 않은
  입력으로 시험한다. 실기기가 없으면 **합성 데이터를 만들어** 경로를 끝까지 태운다.
- 오류를 내야 하는 입력(없는 경로, 깨진 CSV)이 조용히 통과하면 FAIL이다.
- 확인 항목:
  1. `python -m py_compile` 또는 실제 실행
  2. `init_run()` 경유 여부 — `outputs/<stage>/<run_ts>/run_meta.json`에 git commit이 남는지
  3. `--no_outputs` 경로에서도 죽지 않는지
  4. 백분위·판정선이 `FRAME_BUDGET.md` 값과 일치하는지
