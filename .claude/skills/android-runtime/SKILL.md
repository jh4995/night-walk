---
name: android-runtime
description: 밤마실 Android 온디바이스 런타임(Kotlin/CameraX/ONNX Runtime Mobile) 트랙의 구현·검증 규칙. 프레임 파이프라인 규약, 측정 코드 작성법(p50/p95, ImageProxy 수명, 시계 기준 함정), release 빌드로만 측정, 에뮬레이터 금지, 모델 메타 하드코딩 금지, 빌드 환경 전제와 그 한계를 정의한다.
user-invocable: false
---

# Android 런타임 트랙 규칙

밤마실의 **폰 위에서 도는 부분** 전부: 카메라 → 전처리 → 추론 → 렌더.

> **현재 상태 (2026-07-31):** `android/` 앱이 **존재하고 실기기에서 돈다.**
> CameraX `Preview` → OES `SurfaceTexture` → GL → `GLSurfaceView` (제로카피).
>
> - **arm 스피너**로 렌더 경로를 고른다: `passthrough`(1패스) / `blit_2pass`(3패스 골격) /
>   `gamma_only`(패스2에 감마). **② 셰이더가 꽂힐 자리는 패스2다.**
> - **GPU timer query가 동작한다** — `GL_TIME_ELAPSED_EXT`(0x88BF)를 코어 `glBeginQuery`로.
>   `GLES30`에 `glQueryCounterEXT`가 없어 타임스탬프 방식은 JNI 없이 불가능하고,
>   `GL_TIME_ELAPSED`는 중첩이 안 되므로 **패스별 순차 query**다.
> - `passthrough` arm은 **계측하지 않는다.** 승격 베이스라인 재현 기준이라 query 자체가
>   GPU 동작을 바꾸면 안 된다. 이 arm의 동등성을 깨는 변경은 FAIL이다.
> - 로그는 **런별 디렉토리** `files/runs/<YYYYMMDD_HHMMSS>/`. `BuildConfig`에 빌드 시점
>   git commit·dirty가 박힌다.
>
> **새로 만드는 것이 아니라 있는 것 위에 얹는다.** 스캐폴딩을 다시 만들지 마라.
> 상세는 `docs/STATUS.md`와 `docs/FRAME_LOG_SCHEMA.md`.

---

## 1. 빌드 환경 — 먼저 확인할 것

이 개발 머신의 PATH에는 **JDK·Android SDK·Gradle·adb가 없을 수 있다.** 작업 시작 전에 확인한다.

```
java -version          # 없으면 Android Studio 번들 JDK 경로를 찾아야 한다
echo $ANDROID_HOME     # 비어 있으면 local.properties에 sdk.dir 지정 필요
adb devices            # 실기기 연결 확인
```

**없으면 없다고 보고한다.** 빌드하지 못한 코드를 "빌드된다"고 말하지 않는다.
컴파일 검증이 불가능한 상태라면 그 전제를 명시하고, **API 표면을 넓히는 선택을 피한다**
(deprecated지만 확실히 동작하는 API > 최신이지만 시그니처를 확인 못 한 API).

## 2. 프레임 파이프라인 규약

- **`ImageProxy.close()`를 빠뜨리지 않는다.** 안 닫으면 카메라가 새 프레임을 못 보내고
  파이프라인이 통째로 멈춘다. 항상 `finally`에서 닫는다.
- 백프레셔는 **`STRATEGY_KEEP_ONLY_LATEST`**. 큐에 쌓아 처리하면 프레임타임이 실제보다
  좋아 보이고 지연만 늘어난다 — 실시간 보행 보조에서 의미 있는 건 최신 프레임뿐이다.
- 무거운 처리(③탐지)는 **렌더 경로를 막지 않는다.** 인라인 동기 실행하면 그 프레임만 무거워져
  **p95가 거기 걸린다**(`FRAME_BUDGET.md` §4). 별도 스레드 + 최근 결과 사용이 기본 설계다.
  이 결론은 각 단계에 몇 ms가 배정되든 성립한다.
- 색공간·해상도·정규화 상수를 **코드에 하드코딩하지 않는다.** 모델에 동봉된 메타데이터에서 읽는다
  (`INTERFACES.md` 계약 A). 하드코딩은 모델이 갱신될 때 조용히 어긋난다.

## 3. 측정 코드를 쓸 때

- 항상 **p50/p95/p99를 함께** 낸다. 평균만 내는 측정 코드는 p95 관리선을 판정할 수 없다
  (판정선 값은 `lib/targets.py` — 폰 코드에 숫자를 박지 않는다. 판정은 PC 하네스가 한다).
- 화면 오버레이 숫자는 데모용이고, **인용 가능한 숫자는 파일로 남긴 것**이다.
  CSV/JSON으로 저장해 PC에서 `run_utils` 하네스가 스탬핑할 수 있게 한다.
- 기기·설정 메타(모델명, Android 버전, 실제 해상도, 파이프라인 상태)를 함께 남긴다.
  `run_meta.json`과 같은 역할이다.

### 시계 기준 함정 (반드시 알 것)

`ImageInfo.timestamp`의 기준 시계가 기기마다 `CLOCK_MONOTONIC`(`System.nanoTime`)일 수도
`CLOCK_BOOTTIME`(`SystemClock.elapsedRealtimeNanos`)일 수도 있다.
기준이 어긋나면 값이 음수이거나 터무니없이 커진다.

→ **물리적으로 말이 되는 범위만 채택하고, 아니면 "측정 불가"로 표시한다.**
그럴듯한 쓰레기 숫자가 버짓표에 들어가는 것보다 낫다.

## 4. 측정 조건 (어기면 숫자가 무의미하다)

| 규칙 | 이유 |
|---|---|
| **release 빌드로 측정** | debug는 디버깅 오버헤드로 프레임타임이 부풀려진다 |
| **에뮬레이터 금지** | 가상 카메라 프레임은 실기기 숫자가 아니다 |
| 30초 이상 안정화 후 기록 시작 | AE/AWB 수렴 전 프레임은 튄다 |
| 지속 성능은 10분 연속 | 발열 스로틀링이 반영돼야 데모 근거가 된다 |

## 5. 베이스라인 숫자 읽는 법 (오해 주의)

처리가 없는 파이프라인의 프레임타임은 **연산 비용이 아니라 카메라 공급 속도**다.
30fps 카메라면 33ms가 나오는데, 이건 "우리가 33ms를 쓴다"가 아니라
**"카메라가 33ms마다 프레임을 준다"**는 뜻이다.

→ "33ms 나왔으니 여유 33ms 있다"는 **잘못된 독해**다.
이 값은 여유의 상한이 아니라 바닥값이고, 여기서부터 ①②③④ 비용이 더해진다.
이 숫자를 팀에 공유할 때 가장 오해받기 쉬운 지점이므로, 보고에 항상 이 단서를 붙인다.

## 6. 검증 (android-verifier용)

편집 훅은 `.kt`를 검사하지 않는다(Gradle이 느려서). **Kotlin 검증은 전적으로 verifier 몫이다.**

1. **컴파일** — `gradlew assembleRelease` 또는 `compileReleaseKotlin`. 실행 불가면 미실행으로 기록.
2. **계약 대조** — `INTERFACES.md`의 확정된 항목과 코드가 일치하는지. `☐` 미확정 항목을
   임의값으로 채우지 않았는지 특히 확인.
3. **예산 대조** — 측정 결과가 `FRAME_BUDGET.md`의 어느 칸에 해당하는지 매핑해 보고.
4. **회귀** — 이번 변경 범위 밖이 그대로인지.

빌드도 실기기 측정도 못 했다면 **"정적 검토만 수행"이라고 결론에 명시**한다.
