# 밤마실 (Bammasil)

야맹증 저시력자의 야간 보행을 돕는 폰 온디바이스 실시간 AI 시각보조 앱.
2026 KDT 해커톤.

## 문서

**문서마다 소유 주제가 하나씩이다.** 어떤 주제를 찾을 때 갈 곳은 한 군데뿐이다.

> 🧭 **처음 왔거나 이어서 일한다면 [docs/STATUS.md](docs/STATUS.md)부터.**
> 지금 어디까지 왔고 다음이 무엇인지, 무엇에 막혀 있는지가 거기 있다.
> 규칙은 [CLAUDE.md](CLAUDE.md)가 매 세션 자동으로 싣는다.

### 규약 (루트) — 팀과의 계약·합의. 임의 수정 금지

| 문서 | 이 주제는 여기서만 본다 | 상태 |
|---|---|---|
| [KICKOFF_ROLES.md](KICKOFF_ROLES.md) | 역할·오너십 경계, 킥오프 결정 3건 | 동결 (합의 완료) |
| [INTERFACES.md](INTERFACES.md) | 계약 A/B/C — 모델 아티팩트 · ①② 시그니처 · 녹화 입력 | 살아있음 — 상대방이 `☐`를 채움 |
| [FRAME_BUDGET.md](FRAME_BUDGET.md) | ms 예산, 측정 용어 정의, 예산 초과 시 레버 | 살아있음 — 실측이 채움 |
| [PIPELINE_STACK.md](PIPELINE_STACK.md) | 스택 12개 결정의 탈락 후보·상세 근거 | 살아있음 — 확정도가 바뀜 |

### 리서치 (`docs/research/`) — 규약이 아니라 현재 판단의 근거

| 문서 | 담는 것 |
|---|---|
| [RESEARCH_20260728_PIPELINE.md](docs/research/RESEARCH_20260728_PIPELINE.md) | **현행 최신 종합.** 지연 예산 · ② A/B/C 판정 · 미결질문 5개 (위 4개에 없는 내용) |
| [SUBMIT_20260728_PIPELINE.md](docs/research/SUBMIT_20260728_PIPELINE.md) | 위 문서의 팀 배포 축약본 — **동결 스냅샷**, 갱신은 RESEARCH 쪽만 |

### 현황·내부 규격 (`docs/`)

| 문서 | 담는 것 |
|---|---|
| [STATUS.md](docs/STATUS.md) | **현재 상태** — 어디까지 왔나 · 다음 한 수 · 알려진 이슈 · 막혀 있는 것 |
| [FRAME_LOG_SCHEMA.md](docs/FRAME_LOG_SCHEMA.md) | 폰(PoC)이 뱉고 PC(하네스)가 읽는 프레임 로그 형식 — 팀 계약이 아니라 내 두 트랙 사이의 규격 |
| [baselines/](docs/baselines/) | **기준 측정값.** `outputs/`는 git 추적을 안 하므로, 승격하지 않은 숫자는 그 머신에만 남는다 |

### 보관 (`docs/archive/`) — 역할이 끝남

[HANDOFF.md](docs/archive/HANDOFF.md) · [STARTUP_PLAN.md](docs/archive/STARTUP_PLAN.md) —
킥오프 이전 상태라 역할표·스택·미결사항이 현재와 어긋난다. **배경 확인용으로만 읽는다.**

> ✅ **숫자 기준 통일 완료 (2026-07-29):** `docs/research/`의 7/28 리서치를 **현재 결정사항**으로
> 채택했다. 그 결과 `FRAME_BUDGET.md` v0.2에서 **단계별 잠정 배정치를 전부 제거**했고
> (근거는 버짓표 §8), 지연 "130~200ms"도 **미측정**으로 철회했다.
> `INTERFACES.md`·`PIPELINE_STACK.md`의 옛 배정치 인용도 함께 정리했다.
>
> **지금 이 레포에 유효한 ms 수치는 66.7(프레임당 예산)과 80(p95 관리선)뿐이고, 둘 다
> 목표에서 유도된 값이다. 실측은 여전히 0건이다.**

## 개발 체계 (.claude)

기능 개발은 서브에이전트로 진행한다. `/feature-kickoff`로 표준 루틴을 로드한다.

```
planner (읽기전용, 두 트랙 모두 + 접합부)
   ├─ android 트랙  → android-builder  → android-verifier   (android/, Kotlin)
   └─ harness 트랙  → harness-builder  → harness-verifier   (lib/ scripts/, Python)
```

- **공통 규칙**은 `nightwalk-conventions` 스킬 한 곳에 있다 (계약 문서가 source of truth,
  팀 합의 문서 수정 금지, 미확정 계약값 지어내지 않기, 스탬프 없는 숫자 금지).
  요약은 `CLAUDE.md`가 매 세션 자동으로 싣는다.
- **세션을 닫을 때는 `/wrap-session`** — 커밋 / `STATUS.md` 갱신 / 다음 한 수를 남긴다.
  문서가 썩는 건 형식이 나빠서가 아니라 갱신 트리거가 없어서다.
- **훅**: 편집 시 `.py`는 즉시 `py_compile`. `.kt`는 Gradle이 느려 건너뛰고
  android-verifier가 담당한다 (ktlint가 PATH에 있으면 자동으로 켜진다).

## 측정 하네스

모든 성능 측정은 `lib/run_utils.py`의 `init_run()`을 거쳐 실행 시각 + git commit이 함께 기록된다.
스탬프 없는 숫자는 나중에 비교가 불가능하므로 측정 스크립트는 예외 없이 이걸 통해 돈다.

```
python scripts/smoke_run_utils.py        # 하네스 동작 확인
python scripts/gen_synthetic_frames.py   # 합성 프레임 로그 (실기기 없이 경로 시험)
python scripts/analyze_frames.py --frames <csv>   # 집계 + 판정 → summary.json
python scripts/baseline_diff.py --baseline <a> --current <b>   # 회귀 판정
```

프레임 로그 형식은 [docs/FRAME_LOG_SCHEMA.md](docs/FRAME_LOG_SCHEMA.md). 판정선은
[lib/targets.py](lib/targets.py) 한 곳에만 있고 `FRAME_BUDGET.md` §1에서 온다.

산출물은 `outputs/<stage>/<run_ts>/` (git 추적 안 함), 로그는 `outputs/logs/<stage>/`.
`run_meta.json`에 git commit·dirty 여부·플랫폼·argv가 남는다.
비활성화: `--no_outputs` / `--no_cmdlog` 또는 `NW_NO_OUTPUTS=1` / `NW_NO_CMDLOG=1`.