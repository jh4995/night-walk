# 문서 지도

**문서마다 소유 주제가 하나씩이다.** 어떤 주제를 찾을 때 갈 곳은 한 군데뿐이다.

> 🧭 **처음 왔거나 이어서 일한다면 [STATUS.md](STATUS.md)부터.**
> 지금 어디까지 왔고 다음이 무엇인지, 무엇에 막혀 있는지가 거기 있다.
> 규칙은 [CLAUDE.md](../CLAUDE.md)가 매 세션 자동으로 싣는다.
>
> 프로젝트 소개·수상·데모는 [README.md](../README.md).

## 규약 (루트) — 팀과의 계약·합의. 임의 수정 금지

| 문서 | 이 주제는 여기서만 본다 | 상태 |
|---|---|---|
| [KICKOFF_ROLES.md](../KICKOFF_ROLES.md) | 역할·오너십 경계, 킥오프 결정 3건 | 동결 (합의 완료) |
| [INTERFACES.md](../INTERFACES.md) | 계약 A/B/C — 모델 아티팩트 · ①② 시그니처 · 녹화 입력 | 살아있음 — 상대방이 `☐`를 채움 |
| [FRAME_BUDGET.md](../FRAME_BUDGET.md) | ms 예산, 측정 용어 정의, 예산 초과 시 레버 | 살아있음 — 실측이 채움 |
| [PIPELINE_STACK.md](../PIPELINE_STACK.md) | 스택 12개 결정의 탈락 후보·상세 근거 | 살아있음 — 확정도가 바뀜 |

## 리서치 (`research/`) — 규약이 아니라 현재 판단의 근거

| 문서 | 담는 것 |
|---|---|
| [RESEARCH_20260823_UPSTREAM.md](research/RESEARCH_20260823_UPSTREAM.md) | **상류 대조 3차 (최신, HEAD `30dbaef`).** 받은 `c4d` 3종 중 **`26n_640`은 상류가 8/23에 기각**했고 **야간 1위 `noneg`는 오지 않았다.** 🔴 **계약값 `conf 0.35`는 argparse 기본값**이며 최신 결정은 0.25 유지 — 0.35는 야간 볼라드를 놓친다. **주간 mAP 순위가 야간에서 뒤집힌다** · 새 충돌 U-10~U-19 |
| [RESEARCH_20260803_UPSTREAM.md](research/RESEARCH_20260803_UPSTREAM.md) | 상류 대조 2차 (HEAD `c77e1d6`). ③ 탐지가 동작하고, **②를 탐지 앞에 두면 안 된다**는 것이 실측으로 나왔고(경로 분리), ④ 명세가 확정됐다. **버짓표 직렬 전제가 흔들린 근거가 여기 있다.** ⚠️ 계약 A 관련 미해소 항목의 **현황은 위 3차 문서 §8**이 갱신한다 |
| [RESEARCH_20260731_UPSTREAM.md](research/RESEARCH_20260731_UPSTREAM.md) | 상류 원문 대조 1차 (HEAD `60bc2a6`). 기획서·모델링 레포를 처음 읽고 계약 문서와 맞춰 본 기록. **② A안이 재설계됐다는 사실이 여기 있다.** ⚠️ 충돌 U-1~U-9의 **해소 현황은 위 2차 문서 §8**이 갱신한다 |
| [RESEARCH_20260728_PIPELINE.md](research/RESEARCH_20260728_PIPELINE.md) | 지연 예산 · ② A/B/C 판정 · 미결질문 5개 (위 4개에 없는 내용). ⚠️ ② 관련 서술은 **위 7/31 문서가 갱신**한다 |
| [SUBMIT_20260728_PIPELINE.md](research/SUBMIT_20260728_PIPELINE.md) | 위 문서의 팀 배포 축약본 — **동결 스냅샷**, 갱신은 RESEARCH 쪽만 |

## 현황·내부 규격 (`docs/`)

| 문서 | 담는 것 |
|---|---|
| [PROGRESS.md](PROGRESS.md) | **진행 현황판 — 훑어보는 용도.** 6단계 · 🟢지금 가능 / ⏸대기 구분 · 남이 풀어야 열리는 것. ⚠️ **ms 숫자를 담지 않는다**(측정 한 번에 썩는다) — 수치는 아래 두 문서 |
| [STATUS.md](STATUS.md) | **현재 상태 — 읽는 용도.** 어디까지 왔나 · 다음 한 수 · 알려진 이슈 · 막혀 있는 것 · 단서와 한계 |
| [REPORT_20260808_TEAM.md](REPORT_20260808_TEAM.md) | 🟢 **팀장 전달용 (2026-08-08, 최신) — 기획서 v20의 KPI 속도칸 정정 요청.** 값 3건은 유효하고 **라벨 2건이 틀렸다**: `화면 처리 지연 32.7/38.1`은 **지연이 아니라 프레임타임**(실제 지연은 75~97ms로 **예산 초과**) · `5.75(무처리)`는 **무처리가 아니라 ② drago이고 인용 금지 열**이다. 🔴 **최신 빌드 기준이 아니다**(측정 `5bb42d0` vs HEAD) |
| [REPORT_20260806C_TEAM.md](REPORT_20260806C_TEAM.md) | 🟢 **팀 공유용 (2026-08-06) — 기술 현황 회의에 이것만 내면 된다.** 아래 두 보고를 **end-to-end 관점**으로 합치고 비전공자 기준으로 푼 것. **①②③④가 한 줄로 이어졌고, ③→④만 클래스 번호 충돌로 끊겨 있다** |
| [REPORT_20260807_TEAM.md](REPORT_20260807_TEAM.md) | **기술 근거 원본 (2026-08-07).** ② · ④ 비용의 **상한과 하한**이 다 나왔다 — ② 융합 **+9.074~+12.929** · 융합+bf **+21.030~+38.169** · 🔴 **④ 강조는 하한이 +0.000**(지금까지의 0.56ms가 통째로 계측 허수였다) |
| [REPORT_20260806D_TEAM.md](REPORT_20260806D_TEAM.md) | **기술 근거 원본 (2026-08-06).** ③ **폰 이식이 값을 바꾸지 않는다** — E(전처리) 24/24 비트 일치 · F(추론) **conf 임계 판정이 뒤집힌 앵커 0/201,600**. 🔴 **"모델이 정확하다"가 아니다**(정답 라벨 없음) · G(후처리)는 박스 2개짜리 근거다 |
| [REPORT_20260806B_TEAM.md](REPORT_20260806B_TEAM.md) | **기술 근거 원본 (2026-08-06).** ③ **F칸을 쟀다** — 1회 251.5~263.3ms(CPU EP). 🟢 **15FPS는 지켜진다**(비동기 설계가 작동했다) · 문제는 **탐지 신선도**(실측 3.4Hz) · 🔴 **NNAPI는 쓰면 안 된다**(GPU로 내려가 표시 경로와 경쟁). ⚠️ **동결 스냅샷이다** |
| [REPORT_20260806_TEAM.md](REPORT_20260806_TEAM.md) | 팀 보고 (2026-08-06, ③ 착수). 계약 A 충돌 9건 · 팀원1 요청 5건 — **이 둘은 여전히 유효**. ⚠️ **NNAPI 결론은 위 B가 번복했다** |
| [REPORT_20260804B_TEAM.md](REPORT_20260804B_TEAM.md) | 팀 보고 (2026-08-04B). 🔴 우리 GPU 계측이 프레임을 31~43% 중복 계상하고 있었다 · `bf`·④ I칸 실측. ⚠️ **동결 스냅샷이다** |
| [REPORT_20260804_TEAM.md](REPORT_20260804_TEAM.md) | 팀 보고 (2026-08-04). ② **조합**(D1+A1)을 폰에서 쟀다 — 720p 게이트 안 · 융합이 체인보다 싸다 · 🔴 착수 가설의 메커니즘이 틀렸다. ⚠️ **동결 스냅샷이다** |
| [REPORT_20260803_TEAM.md](REPORT_20260803_TEAM.md) | 팀 보고 (2026-08-03, 최초 공유). ② **단품**을 하나씩 쟀다 — 속도로 못 고른다 · 병목은 ②가 아니다. ⚠️ **동결 스냅샷이다** — 숫자가 갱신되면 `FRAME_BUDGET.md`·`baselines/` 쪽이 맞다 |
| [FRAME_LOG_SCHEMA.md](FRAME_LOG_SCHEMA.md) | 폰(PoC)이 뱉고 PC(하네스)가 읽는 프레임 로그 형식 — 팀 계약이 아니라 내 두 트랙 사이의 규격 |
| [baselines/](baselines/) | **기준 측정값.** `outputs/`는 git 추적을 안 하므로, 승격하지 않은 숫자는 그 머신에만 남는다 |
| [plans/](plans/) | **측정 계획.** 무엇을 왜 그 순서로 재는지 · 불변식 · 한계. `baselines/`가 결과라면 이쪽이 입력이다. 계획 지문이 바뀌면 세션 재개가 거부된다 |
| `아이디어기획서_밤마실_20260729.pdf` | **공식 제출본(7/29).** 목표 성능·MVP 범위·KPI·역할의 최종 출처. ⚠️ **git 추적 안 함**(대용량 바이너리) — 원본은 모델링 담당 레포 `docs/`에 있다. **대조 결과는 위 UPSTREAM 문서에 텍스트로 남아 있으므로 PDF 없이도 읽을 수 있다** |

## 보관 (`archive/`) — 역할이 끝남

[HANDOFF.md](archive/HANDOFF.md) · [STARTUP_PLAN.md](archive/STARTUP_PLAN.md) —
킥오프 이전 상태라 역할표·스택·미결사항이 현재와 어긋난다. **배경 확인용으로만 읽는다.**

> ✅ **숫자 기준 통일 완료 (2026-07-29):** `research/`의 7/28 리서치를 **현재 결정사항**으로
> 채택했다. 그 결과 `FRAME_BUDGET.md` v0.2에서 **단계별 잠정 배정치를 전부 제거**했고
> (근거는 버짓표 §8), 지연 "130~200ms"도 **미측정**으로 철회했다.
> `INTERFACES.md`·`PIPELINE_STACK.md`의 옛 배정치 인용도 함께 정리했다.
>
> **판정선 ms는 프레임당 예산과 p95 관리선 둘뿐이고, 값은 `FRAME_BUDGET.md` §1과
> `lib/targets.py`에만 산다.**
>
> 🎉 **첫 실측 (2026-07-30):** 빈 파이프라인 PoC로 **A칸**과 **지연 하한**이 채워졌다
> (버짓표 v0.3 §9). 프레임타임 **p50 32.7 / p95 38.1ms**, 취득~제출 지연 **하한 p50 78.8ms**
> — A34 / release / 처리 0 / `indoor_bright` / 연속 10분 × 2회.
> 근거는 [baselines/](baselines/), 한계와 단서는 버짓표 §9에 함께 있다.

---

## 개발 체계 (`.claude`)

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

## 측정 절차 (틀리면 숫자를 못 씀)

1. **커밋된 상태에서 빌드·설치한다.** `BuildConfig`에 git commit과 dirty 여부가 박히며,
   `git_dirty=true`로 찍힌 런은 나중에 어느 코드가 낸 숫자인지 말할 수 없다
2. **스피너 둘을 실제 값으로 고른다 — 조명과 arm.** 둘 다 측정 조건이고, 기본값으로 나간
   런은 비교 대상이 못 된다. arm은 `session.json`의 `pipeline_stages`가 되어
   `baseline_diff`의 비교 조건으로 들어간다
3. 폰을 고정하고 측정 시작 → **30초 warmup + 목표 시간** (지속 판정은 연속 10분)
4. **반드시 "측정 정지" 버튼으로 끝낸다.** 뒤로가기로 끝내면 로그 flush가 버려질 수 있다
5. **폰을 연결한 상태로** `pull_frames.py` → `analyze_frames.py`
   (기기 메타가 분석 시점에 수집되고 `device.props.model`이 비교 조건이다)

> 로그는 런마다 `files/runs/<YYYYMMDD_HHMMSS>/`에 따로 쌓인다 —
> **PC 없이 연속으로 여러 런을 찍고 나중에 한 번에 회수할 수 있다**(`--all`).

> 🧭 **런이 여러 개인 세션은 `python scripts/run_session.py`가 안내·검증한다.**
> 위 2·4·5를 사람이 매번 맞추다 보면 틀리고, 틀린 런은 통째로 못 쓴다. 스크립트는
> **폰을 조작하지 않고**(좌표 탭 금지 — 틀린 좌표로 눌러도 성공을 보고한다) 계획을 제시하고
> 기다렸다가, 회수한 `session.json`이 계획한 arm·조명·길이와 맞는지 대조해
> **어긋나면 그 칸을 실패로 남긴다.** 중단·재개되고, 끝나면 노이즈 바닥·arm 차분·`baseline_diff`까지 낸다.
> 자세한 것은 [FRAME_LOG_SCHEMA.md §8](FRAME_LOG_SCHEMA.md#8-사용법).

⚠️ 측정은 **release 빌드 · 실기기**로만. 에뮬레이터 프레임은 실기기 숫자가 아니다.

## 측정 하네스

모든 성능 측정은 `lib/run_utils.py`의 `init_run()`을 거쳐 실행 시각 + git commit이 함께 기록된다.
스탬프 없는 숫자는 나중에 비교가 불가능하므로 측정 스크립트는 예외 없이 이걸 통해 돈다.

```
python scripts/run_session.py            # 다중 런 측정 세션 안내·검증·집계 (중단/재개)
python scripts/run_session.py --print_plan   # 계획만 확인 (기기를 건드리지 않는다)
python scripts/smoke_run_utils.py        # 하네스 동작 확인
python scripts/gen_synthetic_frames.py   # 합성 프레임 로그 (실기기 없이 경로 시험)
python scripts/pull_frames.py            # 폰에서 로그 회수 (기본: 가장 최근 런 1개)
python scripts/pull_frames.py --list     # 기기에 어떤 런이 있는지만 본다
python scripts/pull_frames.py --all      # 쌓아 둔 런을 한 번에
python scripts/analyze_frames.py --frames <csv>   # 집계 + 판정 → summary.json
python scripts/baseline_diff.py --baseline <a> --current <b>   # 회귀 판정
python scripts/detect_parity.py --dump <run>/parity   # ③ 이식 정확성 대조 (폰 ↔ PC ORT)
```

> ⚠️ **`detect_parity.py`만 stdlib 밖을 쓴다** (`onnxruntime`·`numpy`). 지연 import라 나머지
> 스크립트는 그대로 무의존이고, 없으면 설치 안내와 함께 깔끔히 죽는다:
> `python -m pip install onnxruntime numpy`.
> 무엇을 대조하고 **무엇은 대조하지 못하는지**는
> [plans/20260806_detect_parity_dump_format.md](plans/20260806_detect_parity_dump_format.md) §0.

프레임 로그 형식은 [FRAME_LOG_SCHEMA.md](FRAME_LOG_SCHEMA.md). 판정선은
[lib/targets.py](../lib/targets.py) 한 곳에만 있고 `FRAME_BUDGET.md` §1에서 온다.

산출물은 `outputs/<stage>/<run_ts>/` (git 추적 안 함), 로그는 `outputs/logs/<stage>/`.
`run_meta.json`에 git commit·dirty 여부·플랫폼·argv가 남는다.
비활성화: `--no_outputs` / `--no_cmdlog` 또는 `NW_NO_OUTPUTS=1` / `NW_NO_CMDLOG=1`.
