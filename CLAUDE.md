# 밤마실 (Bammasil)

야맹증 저시력자의 야간 보행을 돕는 **폰 온디바이스 실시간 AI 시각보조 앱**. 2026 KDT 해커톤, 4인.
4단계: ①눈부심 억제 → ②저조도 개선 → ③위험 탐지(YOLO) → ④선택적 강조. 목표 **720p 15FPS+**.

**이 저장소의 주인 = 팀원2 (모바일 런타임·통합·검증 오너).** 모델 학습·①② 알고리즘 설계는 내 일이 아니다.

## 시작 전에

- **문서 지도는 [README.md](README.md).** 규약 4개는 루트, 리서치는 `docs/research/`.
  `docs/archive/`는 킥오프 이전 상태라 **요구사항 출처가 아니다.**
- **상류 원문 2종이 계약 문서보다 최신일 수 있다** — 공식 제출본 기획서(7/29)와
  모델링 담당 레포(`kty2001/KDT_Hackathon`). 둘 다 **이 저장소가 git으로 갖고 있지 않다.**
  대조 결과·충돌 목록·인용 근거는
  [`docs/research/RESEARCH_20260731_UPSTREAM.md`](docs/research/RESEARCH_20260731_UPSTREAM.md)에
  텍스트로 남아 있다. **② A안은 CLAHE+감마 하나가 아니다** — 거기부터 읽는다.
- **규칙 전문: `.claude/skills/nightwalk-conventions/SKILL.md`** — 이 파일은 메인 세션에
  자동 로드되지 않는다. 작업 전에 읽는다.
- **현재 상태: [docs/STATUS.md](docs/STATUS.md)**

## 절대 규칙 (전문은 위 스킬)

1. **팀 합의 문서는 수정 금지** — `INTERFACES.md` · `FRAME_BUDGET.md` · `KICKOFF_ROLES.md` ·
   `PIPELINE_STACK.md`. 고칠 이유를 찾으면 **고치지 말고 보고**한다.
   예외: 사용자가 그 문서를 고치라고 명시 지시한 경우.
2. **`INTERFACES.md`의 `☐` 항목 값을 임의로 정해서 구현하지 않는다.** 그럴듯한 기본값을 넣으면
   나중에 실제 값과 어긋난 채로 코드가 굳는다. 확실하지 않으면 질문으로 반환한다.
3. **모든 측정은 `lib/run_utils.py`의 `init_run()`을 거친다.** 스탬프(git commit + run_ts) 없는
   숫자는 나중에 비교가 불가능하다. `outputs/` 아래 파일을 손으로 만들지 않는다.
4. **판정선 값은 `FRAME_BUDGET.md` §1과 `lib/targets.py` 두 곳에만 있다.** 다른 문서·코드에
   숫자를 복사하지 말고 거기서 가져온다 (p95 관리선은 아직 팀 합의 전이라 바뀔 수 있다).
   **단계별 배정치(A~J 칸)는 폐기됐다** — 어디선가 보더라도 인용하지 않는다.
   단계 비용은 실측으로만 말한다.
5. **성능 보고에는 안전 회귀를 함께 낸다.** 속도를 올렸다면 위험물 강조를 놓치지 않았음을
   같이 보인다. 한쪽만 있는 보고는 불완전한 보고다.
6. **측정하지 못했으면 "미측정"이라고 쓴다.** 추정치를 실측처럼 제시하지 않는다.
   합성 데이터로 통과한 것은 **로직 검증이지 실측이 아니다.**

## 트랙과 작업 방식

| 트랙 | 범위 | builder | verifier |
|---|---|---|---|
| android | `android/` — Kotlin/CameraX/추론/렌더 | `android-builder` | `android-verifier` |
| harness | `lib/`, `scripts/` — Python 측정·MLOps | `harness-builder` | `harness-verifier` |

기능 착수는 **`/feature-kickoff`** (사용자가 직접 호출). planner → builder → verifier를
사용자 승인 게이트로 진행한다. **자기가 만든 것을 자기가 검증하면 놓치는 것이 있다** —
실제로 그렇게 해서 tail 샘플이 조용히 사라지는 결함을 놓친 적이 있다.

## 환경

- Python 3.13. **`uv`를 쓰지 않는다** — `python` 직접 호출.
- Android SDK/JDK/adb 설치됨. `JAVA_HOME`은 Android Studio 번들 JBR.
- 측정 기기: **삼성 Galaxy A34 (SM-A346N)** — MediaTek Dimensity 1080, Mali-G68 MC4,
  Android 16(API 36), arm64-v8a. **MediaTek이라 QNN EP는 이 기기에서 불가.**
- `.py` 편집 시 훅이 즉시 `py_compile`. `.kt`는 훅이 안 잡는다(verifier 몫).
