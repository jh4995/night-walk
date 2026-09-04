# 밤마실 (Bammasil)

> **야맹증 저시력자의 야간 보행을 돕는, 스마트폰 온디바이스 실시간 AI 시각보조 앱**
> 눈부심을 억제하고 어두운 곳을 밝히고 위험물을 찾아 강조하는 네 단계가, 서버 없이 폰 안에서 한 줄로 돈다.

<p>
  <img alt="Android" src="https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <img alt="OpenGL ES" src="https://img.shields.io/badge/OpenGL%20ES-5586A4?style=flat-square&logo=opengl&logoColor=white">
  <img alt="ONNX Runtime" src="https://img.shields.io/badge/ONNX%20Runtime-005CED?style=flat-square&logo=onnx&logoColor=white">
  <img alt="On-Device" src="https://img.shields.io/badge/On--Device-no%20server-111111?style=flat-square">
</p>

### 실기기 야간 구동 — Galaxy A34 · A52S · A37

<p align="center">
  <img src="docs/assets/%EC%B5%9C%EC%A2%85%20A34.gif" width="30%" alt="Galaxy A34 야간 구동">
  <img src="docs/assets/%EC%B5%9C%EC%A2%85%20A52.gif" width="30%" alt="Galaxy A52S 야간 구동">
  <img src="docs/assets/%EC%B5%9C%EC%A2%85%20A37.gif" width="30%" alt="Galaxy A37 야간 구동">
</p>
<p align="center"><sub>Galaxy A34 · Galaxy A52S · Galaxy A37 — 서버 없이 폰 안에서만 돈다</sub></p>

## 🏆 수상

| | |
|---|---|
| **대회** | 2026 제8회 K-디지털 트레이닝(KDT) 해커톤 |
| **부문·수상** | **지정과제 부문 최우수상 (고용노동부 장관상)** |
| **지정과제** | AI 전환 시대, 인간의 역량을 확장하는 디지털 서비스 개발 |
| **주최·주관** | 고용노동부 · 한국기술교육대학교 직업능력심사평가원 |
| **팀** | 밤마실 (4인) |

## 문제

야맹증·저시력 보행자에게 밤길은 두 방향으로 동시에 어렵다.

- **너무 밝다** — 가로등·전조등이 시야를 태워 그 주변이 통째로 보이지 않는다.
- **너무 어둡다** — 조명이 닿지 않는 구간의 계단·볼라드는 형체가 남지 않는다.

밝기를 올리는 것만으로는 첫 번째가 나빠지고, 낮추면 두 번째가 나빠진다.
**밤마실은 한 프레임 안에서 눈부신 영역과 어두운 영역을 서로 다르게 처리하고, 그 위에 위험물을 찾아 강조한다.**

### 왜 온디바이스인가

- **프라이버시** — 영상이 기기 밖으로 나가지 않는다. 처리도 소멸도 폰 안에서 끝난다.
- **지연** — 보행 보조는 눈앞의 장면과 화면이 어긋나면 그 순간 쓸모가 없어진다. 왕복 네트워크를 낄 여유가 없다.
- **접근성** — 별도 장비 없이 **개인 스마트폰만으로** 즉시 쓸 수 있다.

## 주요 기능 — 4단계 파이프라인

| | 단계 | 하는 일 | 구현 |
|---|---|---|---|
| ① | **눈부심 억제** | 빛에 타서 하얗게 뭉개진 부분을 눌러 그 주위를 되살린다 | GPU 프래그먼트 셰이더 |
| ② | **저조도 개선** | 어두운 부분만 골라 밝기 차이를 키운다 (Drago 톤매핑 + CLAHE 융합) | GPU 프래그먼트 셰이더 |
| ③ | **위험물 탐지** | 사람 · 계단 · 볼라드를 찾는다 | YOLO11n 증류 INT8 / ONNX Runtime Mobile |
| ④ | **선택적 강조** | 찾은 것만 화면에 표시한다 | GL 오버레이 + FSM 스무딩 |

**③은 매 프레임 돌지 않는다.** 추론 1회가 프레임 예산의 몇 배이므로 별도 스레드에서 비동기로 돌고,
그 사이 프레임은 최근 결과를 이어받는다. 이 분리가 이 앱이 실시간을 유지하는 핵심 구조다.

추가로 **스마트폰 모드**와 **카드보드(HMD) 모드** 두 가지 표시 경로를 지원하며,
볼륨키로 ②/④를 즉석에서 켜고 끌 수 있다 (시연 중 처리 전/후 비교용).

### ④를 그냥 그리면 못 쓴다 — 표시 안정화

탐지 결과를 매번 그대로 그리면 **깜빡임**(일시적 오탐이 한 프레임 나타났다 사라짐)과
**튐**(같은 물체인데 박스 좌표가 흔들림)이 생긴다. 야간 저조도에서 특히 심하다.
저시력 사용자에게 이건 도움이 아니라 방해다.

| | 방식 | 없애는 것 |
|---|---|---|
| **FSM** | `Detection → PENDING(후보) → 검증 → ACTIVE(표시)` 상태 전이. **최근 4번의 탐지 중 2번 이상 잡힌 것만** 화면에 올리고, **1번이라도 안 잡히면 곧바로 내린다** | 일시적 오탐으로 인한 **깜빡임·잔상** |
| **IIR** | 박스가 튀지 않도록 이전 위치와 새 위치를 섞어 부드럽게 이동시킨다 | 같은 물체의 **BBox 튐** |

**이 설계는 값을 지불한다.** 2번 확인을 기다리는 만큼 **진짜 위험물도 조금 늦게 뜨고**,
1번만 놓쳐도 내리는 만큼 **탐지가 끊기면 박스도 곧바로 사라진다.** 늦게 뜨는 쪽을 택한 이유는,
없는 것이 잠깐 보이는 편보다 있는 것이 잠깐 늦는 편이 야간 보행에서 덜 위험하기 때문이다.

<table>
  <tr>
    <th width="50%">FSM 적용 전</th>
    <th width="50%">FSM 적용 후</th>
  </tr>
  <tr>
    <td><img src="docs/assets/FSM%20%EC%A0%81%EC%9A%A9%20%EC%A0%84.gif" alt="FSM 적용 전"></td>
    <td><img src="docs/assets/FSM%20%EC%A0%81%EC%9A%A9%20%ED%9B%84.gif" alt="FSM 적용 후"></td>
  </tr>
  <tr>
    <th>IIR 적용 전</th>
    <th>IIR 적용 후</th>
  </tr>
  <tr>
    <td><img src="docs/assets/IIR%20%EC%A0%81%EC%9A%A9%20%EC%A0%84.gif" alt="IIR 적용 전"></td>
    <td><img src="docs/assets/IIR%20%EC%A0%81%EC%9A%A9%20%ED%9B%84.gif" alt="IIR 적용 후"></td>
  </tr>
</table>

## 시스템 아키텍처

```
                    ┌─────────────────── 카메라 스레드 ───────────────────┐
  [센서]  →  취득  →  포맷/색공간 (CameraX → OES SurfaceTexture, 제로카피)
                    └──────────────┬───────────────────────────────────┘
                                   │  (프레임 핸들 = GPU 텍스처)
              ┌────────────────────┼─────────────────────────────┐
              ▼                    │                              ▼
  ┌──── 렌더 스레드 (매 프레임) ────┐ │          ┌──── 탐지 스레드 (N프레임마다, 비동기) ────┐
  │ ① 눈부심 억제  (GPU 셰이더)     │ │          │ 전처리 (letterbox · 정규화)              │
  │ ② 저조도 개선  (GPU 셰이더)     │ │          │ ③ YOLO 추론 (ORT Mobile · CPU EP)       │
  │    최근 박스 이어 그리기        │◄┼──게시────│ 후처리 (decode · NMS)                    │
  │ ④ 선택적 강조  (오버레이 렌더)  │ │  (최근    └─────────────────────────────────────────┘
  │    디스플레이 합성             │ │   결과)
  └────────────────────────────────┘ │
              │                       │
              ▼                       │
          [화면] ◄────────────────────┘
```

**폰은 타임스탬프만 남기고, 판정은 PC가 한다.** 프레임 로그를 회수해 Python 하네스가 집계·회귀 판정을 낸다.
선택 근거와 탈락 후보는 [PIPELINE_STACK.md](PIPELINE_STACK.md)에 전부 남아 있다.

## 기술 스택

| 영역 | 사용 |
|---|---|
| 앱 | Kotlin · CameraX · `GLSurfaceView` |
| 영상 경로 | OES `SurfaceTexture` → OpenGL ES (**제로카피**) · GPU timer query로 패스별 계측 |
| 추론 | ONNX Runtime Mobile — **CPU EP** (실측 결과 이 기기에선 NNAPI·XNNPACK보다 빠르고, NNAPI는 표시 경로의 GPU를 빼앗는다) |
| 모델 | YOLO11n 증류 INT8 · `images[1,3,640,640] → output0[1,7,8400]` · 3클래스(person / stairs / bollard) |
| 측정·MLOps | Python 3.13 (stdlib) · adb · 프레임 로그 CSV → 집계 → baseline diff |
| 검증 기기 | Samsung Galaxy A34 (Dimensity 1080 · Mali-G68 MC4 · Android 16) · A52S · A37 |

목표 성능은 **720p 15FPS 이상**. 판정선 ms는 [`lib/targets.py`](lib/targets.py)와
[FRAME_BUDGET.md §1](FRAME_BUDGET.md) 두 곳에만 산다 — 다른 곳에 복사하지 않는다.

## 성능·검증

아래는 **최종 발표 기준 수치**다. 출처를 함께 적는다 — 이 저장소는 스탬프(git commit + 실행 시각 +
기기 메타) 없는 숫자를 성능 근거로 인정하지 않기 때문에, 어느 쪽에서 나온 값인지가 값 자체만큼 중요하다.

### 앱 — 모바일 온디바이스 구동

| 항목 | 값 | 조건 |
|---|---|---|
| 실시간 FPS | **평균 29.78 FPS** | 실야간 · Galaxy A34 |
| 카메라에 들어온 장면이 화면에 뜨기까지 | **평균 95.6 ms** | 〃 |

A52S · A37에서도 같은 APK가 동작하는 것을 확인했다(위 GIF). **수치는 A34 기준이다.**

목표였던 **720p 15FPS**는 여유 있게 넘겼다. ③ 추론 1회가 프레임 예산의 몇 배인데도 표시 경로가
30FPS를 유지하는 건 **탐지를 별도 스레드로 분리한 구조** 덕이다.

⚠️ **지연은 프레임당 예산을 넘는다.** 95.6ms는 "체감상 즉각적"인 구간이지만
[FRAME_BUDGET.md §1](FRAME_BUDGET.md)의 프레임당 예산보다 크다 — **통과가 아니라 알려진 초과**이고,
줄일 레버는 같은 문서에 정리돼 있다.

### 모델 — 실야간 탐지 성능

| mAP50 | Recall | F1-Score |
|---|---|---|
| **0.571** | **0.550** | **0.599** |

3클래스(보행자 · 계단 · 볼라드) 실야간 데이터 기준.
**앱에 실제로 실린 최종 모델(증류 + INT8 양자화)로 잰 값이다** — 양자화 전 원본 모델의 수치가 아니다.
모델 학습·평가는 [@kty2001](https://github.com/kty2001) 담당이며 원본은
[kty2001/KDT_Hackathon](https://github.com/kty2001/KDT_Hackathon)에 있다.

### 이 수치를 어떻게 냈나

이 저장소는 성능을 눈으로 판단하지 않는다.

- 폰은 **타임스탬프만** 남기고, 판정은 PC가 한다 (`analyze_frames.py`)
- 모든 런에 **git commit · dirty 여부 · 기기 메타 · arm · 조명 조건**이 함께 박힌다 (`init_run()`)
- **성능이 나빠졌는지는 예전 측정치와의 자동 비교로만** 판정한다 (`baseline_diff`). 모델을 바꾸면
  모델 파일의 지문(`sha256`)이 비교 조건에 들어가므로, 조건이 다른 두 측정이 "같은 조건"으로 오인되지 않는다
- **에뮬레이터 프레임은 숫자로 치지 않는다.** release 빌드 · 실기기만

⚠️ **속도를 올린 대가로 위험물을 놓치게 되진 않았는지는 아직 숫자로 재지 않았다.**
위 FPS 옆에 있어야 할 값이고, 없으면 성능 보고는 절반짜리다. 표시 안정화 전후의 차이는
위 "표시 안정화" GIF로 보이지만, 그건 눈으로 본 것이지 잰 것이 아니다.

중간 실측·한계·알려진 이슈는 [docs/STATUS.md](docs/STATUS.md) ·
[FRAME_BUDGET.md](FRAME_BUDGET.md) · [docs/baselines/](docs/baselines/)에 있다.

## 빌드 및 실행

```bash
cd android
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

측정용으로 돌릴 때는 **반드시 release 빌드·실기기**로 한다. 에뮬레이터 프레임은 실기기 숫자가 아니다.
측정 절차와 하네스 사용법은 [docs/DOCS_MAP.md](docs/DOCS_MAP.md)에 있다.

## 저장소 구조

```
android/        Kotlin 앱 — CameraX · GL 렌더 경로 · 추론 · 오버레이
  app/src/main/java/com/bammasil/poc/
    gl/         ①② 셰이더 스테이지 · ④ 오버레이 · GPU 계측 · 카드보드 기하
    detect/     ③ 전처리 · 추론 · 후처리 · 폰↔PC parity 덤프
    log/        프레임 로그 기록
lib/            측정 하네스 (run_utils · targets)
scripts/        측정 세션 진행 · 로그 회수 · 집계 · 회귀 판정 · parity 대조
models/         ONNX 아티팩트 (git 추적 안 함)
docs/           문서 — 진입점은 docs/DOCS_MAP.md
```

## 팀

| 역할 | GitHub |
|---|---|
| 팀장 (기획) | [@grkygrt1476](https://github.com/grkygrt1476) |
| 모델 (탐지 모델 설계·학습·경량화) | [@kty2001](https://github.com/kty2001) |
| 앱 (모바일 실시간 런타임·통합·검증) | [@jh4995](https://github.com/jh4995) |
| SW/HW 통합 및 실증 | [@kijac](https://github.com/kijac) |

> **이 저장소는 앱(모바일 런타임) 담당의 저장소다.** 카메라~추론~렌더 실시간 런타임,
> ①②의 모바일 GPU 실현, 4단계 인터페이스 정의, 프레임 버짓·발열, 실기기 성능·안전 회귀 검증을 담는다.
> 역할 경계의 전문은 [KICKOFF_ROLES.md](KICKOFF_ROLES.md).

### 관련 저장소

- **모델 (③ 탐지)** — [kty2001/KDT_Hackathon](https://github.com/kty2001/KDT_Hackathon)

---

📚 **개발 문서 전체 지도는 [docs/DOCS_MAP.md](docs/DOCS_MAP.md).**
현재 진행 상황은 [docs/STATUS.md](docs/STATUS.md).
