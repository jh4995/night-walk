# 프레임 로그 스키마 v1

> **작성:** 팀원2 · **상태:** 확정 (Android 트랙 ↔ 하네스 트랙 내부 규격)
> **소유 주제:** 폰이 뱉고 PC가 읽는 측정 로그의 형식.
> **여기서 다루지 않음:** ms 예산 → `../FRAME_BUDGET.md` / 팀 간 계약 → `../INTERFACES.md`
>
> 실행 가능한 정의는 [`lib/frame_log.py`](../lib/frame_log.py)에 있다. **이 문서와 코드가 어긋나면 코드가 맞다.**

이건 팀 계약(`INTERFACES.md`)이 아니라 **내 두 트랙 사이의 내부 규격**이다.
그래도 문서로 박는 이유: PoC를 짜다 보면 "일단 이렇게 뱉고 나중에 맞추지"가 되고,
그러면 집계 스크립트를 두 번 만들게 된다.

---

## 1. 산출물 2개

폰이 측정 1회당 아래 두 파일을 만든다. PC로는 `adb pull`로 가져온다.

```
frames.csv      프레임당 1행
session.json    이 측정이 어떤 조건이었는지
```

## 2. `frames.csv`

헤더 있는 CSV. **없는 값은 `-1`** — 빈칸이나 `0`이 아니다(`0`은 "0ms 걸렸다"와 구분되지 않는다).

### 필수 열

| 열 | 타입 | 의미 |
|---|---|---|
| `frame_idx` | int | 0부터. **처리된** 프레임만 센다(드롭된 건 세지 않음) |
| `t_recv_ns` | int ns | 분석 콜백에 프레임이 도착한 시각 |

### 선택 열 (있으면 쓰고 없으면 건너뜀)

| 열 | 타입 | 의미 |
|---|---|---|
| `t_capture_ns` | int ns | 카메라 `ImageInfo.timestamp`. **기준 시계 주의 → §4** |
| `t_render_start_ns` | int ns | 렌더 시작 |
| `t_render_end_ns` | int ns | 렌더 제출 완료 |
| `dropped_since_last` | int | 직전 행 이후 백프레셔로 버려진 프레임 수 |

**위 두 표에 없는 열은 집계에 쓰이지 않고, 하네스가 이름을 지목해 경고한다 → §4 "열 단위 방어선".**

### 유도값은 저장하지 않는다

**프레임타임·FPS를 폰이 계산해 넣지 않는다.** 타임스탬프만 넣고 계산은 PC가 한다.
폰이 계산한 값과 PC가 계산한 값이 어긋나면 어느 쪽이 맞는지 판정할 방법이 없다.

## 3. 하네스가 뽑아내는 5가지 (한 숫자로 뭉치지 않는 이유)

| 지표 | 계산 | 채워지는 조건 | 무엇을 말하나 |
|---|---|---|---|
| `recv_interval_ms` | `t_recv_ns` 차분 | 항상 | **카메라 공급 속도** |
| `output_interval_ms` | `t_render_end_ns` 차분 | `t_render_end_ns` 있을 때 | **프레임타임** — 파이프라인이 뱉는 주기 (`FRAME_BUDGET.md` §2) |
| `render_latency_ms` | `t_render_end - t_render_start` | **`t_render_start_ns`가 있을 때만** | 프레임 1장의 **순수 렌더 비용** |
| `recv_to_render_ms` | `t_render_end - t_recv` | `t_render_end_ns` 있을 때 | 도착~렌더 완료 **체류시간** (큐 대기 포함) |
| `capture_to_render_ms` | `t_render_end - t_capture` | §4 조건부 | 취득~표시 |

> ⚠️ **`render_latency_ms`와 `recv_to_render_ms`는 다른 물리량이므로 같은 키에 섞지 않는다.**
> 예전 구현은 `t_render_start_ns`가 없으면 `t_recv_ns`를 폴백 기준으로 써서 `recv_to_render`
> 값을 `render_latency_ms` 키에 넣었다. 소비자는 자기가 받은 숫자가 렌더 비용인지 체류시간인지
> 구분할 수 없었다. 지금은 **키가 다르므로 어느 쪽을 받았는지가 키로 드러난다** —
> `t_render_start_ns`가 없는 로그에서는 `render_latency_ms.count == 0`이다.

> ⚠️ **빈 파이프라인에서 이 다섯은 전부 다른 것을 말한다.** 처리가 없으면
> `output_interval ≈ recv_interval`이고, 이건 **연산 비용이 아니라 카메라가 주는 속도**다.
> "33ms 나왔으니 여유 33ms"는 잘못된 독해다 — 여유의 상한이 아니라 **바닥값**이고
> 여기서부터 ①②③④가 더해진다. 집계 스크립트가 이 단서를 자동으로 붙인다.

## 4. 시계 규약 — 가장 틀리기 쉬운 곳

**`t_recv_ns` · `t_render_*_ns`는 전부 같은 단조 시계여야 한다.**
Android에서는 `SystemClock.elapsedRealtimeNanos()`.

`t_capture_ns`만 예외다. `ImageInfo.timestamp`의 기준 시계가 기기마다
`CLOCK_MONOTONIC`일 수도 `CLOCK_BOOTTIME`일 수도 있어서, 기준이 어긋나면 값이 음수이거나
터무니없이 커진다.

### 가드가 실제로 적용되는 범위

| 시계열 | 하한 | 상한 |
|---|---|---|
| `recv_interval_ms` | `> 0` | **없음** |
| `output_interval_ms` | `> 0` | **없음** |
| `render_latency_ms` | `> 0` | **없음** |
| `recv_to_render_ms` | `> 0` | **없음** |
| `capture_to_render_ms` | `> 0` | `< 5000ms` |

**상한은 `capture_to_render_ms`에만 있다.** 여기만 기준 시계가 다른 값(`t_capture_ns`)이
섞여서, 기준이 어긋나면 수천 초가 나온다. 나머지는 전부 같은 단조 시계 하나에서 나오므로
큰 값은 시계 오류가 아니라 **실제로 느린 프레임**이다(발열 스로틀링, GC, 백그라운드 전환).
p95로 tail을 관리하는 하네스가 느린 쪽 샘플을 버리면 존재 이유와 정면으로 어긋난다.

**하한은 전부 유지한다.** 0 이하 간격·지연은 물리적으로 불가능하다(시계 역행).

### 시계 혼용 교차검사 — 값으로 거르지 않고 관계로 잡는다

`t_recv_ns`에 `elapsedRealtimeNanos()`(BOOTTIME), `t_render_*_ns`에 `System.nanoTime()`
(MONOTONIC)을 섞어 쓰면 **딥슬립 시간만큼** 어긋난다. 이건 상한으로는 못 잡는다 —
큰 값이 진짜 느린 프레임일 수 있기 때문이다(위 표에서 상한을 없앤 이유와 같다).
대신 **열끼리 반드시 성립해야 하는 물리 관계**를 본다. 걸려도 **값을 버리지 않고 경고만** 낸다.

| 교차검사 | 규칙 | 잡아내는 방향 |
|---|---|---|
| A | `render_latency_ms <= recv_to_render_ms` (= `t_render_start >= t_recv`) | `t_render_*`가 `t_recv`보다 **뒤처진** 경우. 렌더는 수신 후에 시작하므로 위반은 물리적으로 불가능 |
| B | `p50(recv_to_render_ms) <= 20 × p50(output_interval_ms)` | `t_render_*`가 `t_recv`보다 **앞선** 경우. 이때 A는 통과하므로 B가 없으면 1시간짜리 체류시간이 경고 없이 채택된다 |

> **B의 20배 근거:** 백프레셔가 `STRATEGY_KEEP_ONLY_LATEST`면 한 번에 한 장만 처리하므로
> 체류시간(recv→render_end)은 출력 주기와 같은 자릿수다. 큐를 두더라도 그 깊이(3~4장)를
> 넘지 않는다. 20배는 그 위로 한참 여유를 둔 값이라 진짜 느린 프레임이나 일시적 큐 적체로는
> 넘지 않고, 시계 오프셋(딥슬립 수십 초~수 시간)은 수천~수십만 배가 나오므로 확실히 걸린다.
> ⚠️ **이 값은 진단용 임계이지 판정선이 아니다.** 판정선(66.7 / 80)은 `lib/targets.py`에만
> 있으며 여기와 섞지 않는다. `lib/frame_log.py: CLOCK_DWELL_RATIO_LIMIT`.

- 결과는 `series.clock_check` → `summary.json`의 `source.clock_check`에 통째로 남는다
  (검사별 `rule` / `checked` / `violations` / `ratio` / `consistent`)
- 위반이 있으면 `verdict.clock_consistent = false`가 되고, 경고가 **어긋난 열 쌍을 이름으로
  지목**한다 (`suspect_columns`)
- **`t_capture_ns` 경고와 구분한다.** `t_capture_ns` 문제는 `capture_to_render_ms`의 폐기
  카운트가 말하고, 교차검사가 지목하는 범인은 `t_render_*` ↔ `t_recv_ns` 쌍이다.
  범인을 엉뚱한 열에 돌리면 폰 쪽이 잘못된 곳을 고친다

### 행 단위 소실 — 사유별로 세고, 의도된 제외와 이상을 나눈다

값 하나가 가드에 걸리는 것(`discarded`)과 **행 전체가 시계열에 못 들어오는 것**
(`rows_skipped`)은 원인이 다르므로 따로 센다. 소실 사유는 셋뿐이다:

| 사유 | 조건 | 성격 | `verdict.data_complete` |
|---|---|---|---|
| `warmup` | `t0 <= t_recv < cutoff_ns` | **의도된 제외** (AE/AWB 수렴 전) | 영향 없음 |
| `before_t0` | `t_recv < t0` | **이상.** 첫 행보다 앞선 시각 = 시계 역행이 warmup으로 위장된 것 | `false` |
| `unparsable_t_recv` | 파싱 실패·빈칸·`-1` | **이상.** 잘린 로그 행 | `false` |

> **`warmup`을 완전성 판정에서 빼는 이유:** 실기기 측정에서는 기본 30초가 항상 잘려 나간다.
> 이걸로 `data_complete=false`가 되면 플래그가 늘 false여서 아무것도 구분하지 못한다.

- `rows_read == rows_used + sum(rows_skipped.values())` 가 **항상 성립해야 한다.**
  깨지면 `read_frames()`가 `FrameLogError`로 죽는다(행이 세 경로 밖으로 샜다는 뜻).
  `summary.json`의 `source.rows_accounted`에 이 회계 결과가 불리언으로 남는다
- `warmup`을 뺀 소실이 1건이라도 있으면 `series.warnings`에 문장이 들어가고
  `verdict.data_complete=false`, `verdict.rows_skipped_anomalous`에 개수가 붙는다
- 리포트의 "입력 완전성" 줄은 `rows_read → rows_used`와 사유별 개수를 함께 낸다.
  **행이 사라졌는데 "폐기 샘플 없음"이라고 쓰지 않는다** — 그게 조용한 소실보다 나쁘다

### 열 단위 방어선 — 스키마에 없는 열은 경고한다 (죽이지는 않는다)

행 단위 회계(`accounting_ok`)에 해당하는 방어선이 **열 단위에도** 있다.
`REQUIRED_COLUMNS + OPTIONAL_COLUMNS`(= `KNOWN_COLUMNS`)에 없는 헤더가 있으면
`series.warnings`에 문장이 들어가고 `summary.json`의 `source.unknown_columns`에 이름이 남는다.

**왜 필요한가 — 오타는 "그 열이 없는 것"과 다르다.**
앱이 `t_render_end_ns`를 `t_render_ns`로 오타 내면 optional 열이 그냥 없는 것으로 처리되어
`output_interval_ms.count == 0`이 되고, 리포트는 `recv_interval_ms`로 폴백하며
**"출력 타임라인 없음"이라고 잘못 결론 낸다.** 경고가 없으면 이 오진단을 되물을 단서가 없다.

**하드 에러로 만들지 않는 이유:** 앱이 스키마보다 앞서 나갈 수 있다(단계 추가 시 §6처럼
열이 먼저 붙는다). 그때 집계가 죽으면 측정 자체를 못 한다. 경고는 사라지지 않고 남으므로
"모르는 열이 있었다"는 사실은 보존된다.

- 필수 열 누락은 그대로 **`FrameLogError`로 죽는다** (그건 측정이 성립하지 않는 경우다)
- 미지 열은 **경고만.** 판정(`meets_*_target`)·종료 코드에 영향을 주지 않는다
- 새 열을 정식으로 쓰려면 `lib/frame_log.py`의 `OPTIONAL_COLUMNS`와 이 문서 §2에 등록한다

### 중복 열은 죽인다 — 미지 열과 성격이 다르다

**아는 열(`KNOWN_COLUMNS`)이 헤더에 두 번 나오면 `FrameLogError`다.**

`csv.DictReader`는 헤더가 중복되면 **마지막 값만** 남긴다. 그래서
`...,t_render_end_ns,t_render_end_ns`(뒤쪽이 `-1`)이면 성한 값이 `-1`에 덮여
`output_interval_ms.count == 0`이 되고, 리포트는 **"출력 타임라인 없음"이라고 잘못 결론 낸다.**
바로 위 미지 열 방어선이 막으려던 오진단 그 자체인데, 이름이 전부 `KNOWN_COLUMNS` 안에 있어서
**미지 열 검사로는 안 걸린다.**

**왜 여기만 하드 에러인가:** 미지 열은 **덧붙는** 것이라 무해하지만, 중복은 아는 열의 값을
**파괴한다.** 그리고 정상적인 생산자가 헤더를 두 번 쓸 이유가 없다 — 항상 버그다.
행 회계 불변식이 깨질 때 죽는 것과 같은 부류다(값이 세 경로 밖으로 샜다).
경고로 두면 그 로그의 분포가 **틀린 채로 채택**되므로, 10분 측정을 버리는 편이 낫다.

- **미지 열이 중복된 경우**(`bogus,bogus`)는 아는 값을 파괴하지 않으므로 **경고만**이고,
  `unknown_columns`에는 이름이 **1개로** 들어간다
- 이 검사는 `--warmup_sec`·판정선과 무관하다. 헤더만 보고 즉시 죽는다

> 📌 이 규칙은 독립 검증에서 나왔다. 미지 열 경고를 넣은 직후 harness-verifier가
> **중복 열이 경고 없이 통과한다**는 거짓 음성을 찾아냈고(2026-07-30), 그 재현 입력이
> 위 시나리오다. 자기가 만든 것을 자기가 검증하면 이런 경로가 남는다.

### 폐기 처리 — 버리되 반드시 센다

가드에 걸린 값은 **그 행의 그 지표 하나만** 버린다(지표를 통째로 버리지 않는다. 같은 행의
다른 지표와 이후 행은 그대로 쓴다). 대신 **시계열별·사유별로 센다**:

- `series.discarded[<지표>][<"below_min" | "above_max">]` 에 개수가 쌓인다
- 폐기가 1건이라도 있으면 `series.warnings`에 문장이 들어간다
- `summary.json`의 `source.discarded_samples` / `source.discarded_total`에 수치로 남는다
- `verdict.data_complete` 가 `false`가 되고 `verdict.samples_discarded` 에 개수가 붙는다
- 그중 **판정에 직접 쓰인 시계열**(`frametime.primary`)에서 빠진 개수는
  `verdict.primary_samples_discarded` 로 따로 낸다 — `meets_*_target`이 흔들린 정도가 이 값이다

**판정 옆에 폐기 개수가 붙는 이유:** 폐기된 샘플이 그 측정의 최악 프레임일 수 있다.
`rows_used`와 `count`를 사람이 눈으로 대조해야만 알 수 있는 상태는 실패로 본다.
같은 이유로 **행 소실도 사유별로 노출한다**(바로 위 절).

폰 쪽에서 기준 시계를 알아냈다면 `session.json`의 `capture_clock_base`에 적는다
(`"monotonic"` / `"boottime"` / `"unknown"`). `analyze_frames.py`가 이 선언을
`source.capture_clock_base_declared`에 싣고, `capture_to_render_ms`가 가드에 걸리면
(음수든 수천 초든 원인은 같다 — 기준 시계 불일치)
`source.capture_clock_base_contradicted=true`로 표시한다 — 선언이 `"unknown"`이 아닌데
모순되면 경고를 따로 낸다(선언 쪽이 틀렸을 수 있다).

## 5. `session.json`

숫자를 나중에 비교하려면 **어떤 조건에서 잰 것인지**가 숫자의 일부여야 한다.

```json
{
  "schema_version": 1,
  "build_type": "release",
  "pipeline_stages": [],
  "capture_clock_base": "unknown",
  "lighting_condition": "outdoor_night_dark",
  "camera": { "requested_fps": 30, "resolution": "1280x720" },
  "camera_frames_offered": 1800,
  "frames_emitted": 1800,
  "frames_dropped": 0
}
```

### `lighting_condition` — 어휘를 고정한다

**허용 어휘는 이 표와 `lib/frame_log.py`의 `LIGHTING_CONDITIONS` 두 곳이 같아야 한다.**
자유 문자열을 허용하면 같은 조명이 `"밝은방"`과 `"indoor_bright"`로 갈려 **모든 비교가
"조건 다름"이 된다.** 어휘 밖의 값은 `analyze_frames.py`가 경고한다(판정은 바꾸지 않는다).

| 값 | 의미 |
|---|---|
| `indoor_bright` | 실내 조명 켜짐. 하네스 배선 점검용이며 **야간 성능 근거로는 못 쓴다** |
| `indoor_dim` | 실내 소등/커튼. AE가 노출을 늘리기 시작하는 구간 |
| `outdoor_night_lit` | 야간, 가로등 있는 보도 |
| `outdoor_night_dark` | 야간, 조명 없는 구간 — **이 앱의 실제 사용 조건** |
| `synthetic` | 합성 로그(`gen_synthetic_frames.py`). 실기기 런과 같은 조건이 아니다 |
| `unknown` | 기록되지 않음. 값은 있지만 **비교 대상이 못 된다** — 경고가 붙는다 |

키가 없거나 `unknown`이면 `analyze_frames.py`가 경고하고
`summary.json`의 `source.lighting_condition_comparable = false`로 남긴다.
**판정(exit code·`meets_*_target`)은 바꾸지 않는다** — 조명은 판정선이 아니라 비교 조건이다.

### 비교 조건 (`baseline_diff.py`가 다르면 "비교가 아니라 착시"라고 경고)

`CONDITION_KEYS`에 있는 것만이 비교 조건이다.

| 키 | 왜 조건인가 |
|---|---|
| `device.props.model` · `device.props.build_fingerprint` | 다른 기기 숫자를 비교하면 착시다 |
| `session.build_type` | **`release`가 아니면 그 숫자는 근거로 못 쓴다** (debug는 프레임타임이 부풀려짐) |
| `session.pipeline_stages` | 빈 배열 = 빈 파이프라인. 이게 다르면 성능 비교가 성립하지 않는다 |
| `session.lighting_condition` | 저조도에서 카메라 AE가 노출을 늘리면 **공급 fps 자체가 떨어진다.** 밝은 방 런과 야간 런을 비교하면 코드가 그대로여도 "회귀"로 오판정된다 |
| `source.warmup_sec` | 워밍업 구간이 다르면 같은 로그도 다른 분포가 된다 |

### 조건이 아니라 **결과**인 것 (비교하지 않고 기록·보고만 한다)

| 키 | 왜 조건이 아닌가 |
|---|---|
| `frames_dropped` · `camera_frames_offered` · `frames_emitted` | 측정의 입력 조건이 아니라 **산출물**이다. 실행마다 달라지는 게 정상이라 조건으로 넣으면 모든 비교가 "조건 다름"이 된다. 대신 `summary.json`의 `session` 블록과 `source.dropped_total`에 그대로 실려 보고된다 — 드롭을 숨기면 프레임타임이 실제보다 좋아 보이므로 **숨기지 않는 것**이 요구사항이고, 비교 차단은 요구사항이 아니다 |

## 6. 단계가 추가될 때 (①②③④ 붙이기)

**열을 추가한다. 기존 열의 의미를 바꾸지 않는다.**

```
stage_c_ms, stage_d_ms, stage_e_ms, stage_f_ms, stage_g_ms, ...
```

`FRAME_BUDGET.md` §3의 칸 이름(A~J)을 그대로 쓴다 — 그래야 실측이 어느 칸을 채우는지가
매핑 없이 드러난다. 열을 추가하면 `SCHEMA_VERSION`을 올리고 `session.json`에 반영한다.

## 7. 폰 쪽 구현 시 지켜야 할 것

- **`ImageProxy.close()`를 `finally`에서.** 안 닫으면 카메라가 새 프레임을 못 보내고 파이프라인이 멈춘다
- 백프레셔는 **`STRATEGY_KEEP_ONLY_LATEST`**. 큐에 쌓으면 프레임타임이 좋아 보이고 지연만 는다
- 로그는 **메모리에 모았다가 끝날 때 한 번에 쓴다.** 매 프레임 파일 I/O를 하면 측정 대상이 오염된다
- 측정은 **release 빌드, 실기기**로만. 에뮬레이터 프레임은 실기기 숫자가 아니다
- 출력 위치는 `getExternalFilesDir(null)` = `/sdcard/Android/data/<pkg>/files/`.
  `pull_frames.py`가 이 경로를 기본값으로 본다(§8). 다른 곳에 쓰면 `--remote_dir`로 알려줘야 한다
- `session.json`의 **`lighting_condition`은 측정할 때 실제 조건으로 채운다**(§5 어휘).
  비워 두면 그 런은 나중에 아무것과도 정직하게 비교할 수 없다

## 8. 사용법

```bash
# ① 폰에서 로그를 가져온다 (outputs/poc_pull/<run_ts>/ 로 들어간다)
python scripts/pull_frames.py                       # 기본 패키지 com.bammasil.poc
python scripts/pull_frames.py --serial <serial>      # 기기 여러 대면 필수
python scripts/pull_frames.py --package <pkg> --remote_dir /sdcard/...  # 경로가 다르면

# ② 가져온 로그를 집계 (기기 메타는 adb로 자동 수집)
python scripts/analyze_frames.py \
  --frames outputs/poc_pull/<run_ts>/frames.csv \
  --session outputs/poc_pull/<run_ts>/session.json

# 이전 측정과 비교해 회귀 판정 (CI: 회귀=1, 판정 불가=3)
python scripts/baseline_diff.py --baseline <이전>/summary.json --current <이번>/summary.json

# 실기기 없이 하네스만 시험 (합성 데이터 — 측정치가 아님)
python scripts/gen_synthetic_frames.py --duration_sec 60 --detect_every_n 3
python scripts/analyze_frames.py \
  --frames outputs/synthetic/<run_ts>/frames.csv \
  --session outputs/synthetic/<run_ts>/session.json \
  --warmup_sec 0 --no_device

# 가드·교차검사를 일부러 걸리게 하는 생성 옵션
python scripts/gen_synthetic_frames.py --broken_capture_clock          # t_capture_ns만 다른 시계
python scripts/gen_synthetic_frames.py --render_clock_skew_sec 3600    # t_render_*만 다른 시계 (교차검사 B)
python scripts/gen_synthetic_frames.py --render_clock_skew_sec -3600   # 반대 방향 (교차검사 A)
```

> ⚠️ **`--warmup_sec`의 기본값은 `targets.DEFAULT_WARMUP_SEC`(30초)다.** AE/AWB 수렴 전
> 프레임을 버리기 위한 값이라 실기기 측정에는 기본값 그대로 쓰지만, **30초보다 짧은 로그는
> 남는 행이 0이 되어 exit 2로 실패한다.** 60초 합성 로그처럼 짧은 입력을 태울 때는
> `--warmup_sec 0`을 명시한다(그 숫자는 워밍업 구간을 포함하므로 실기기 판정 근거로 쓰지 않는다).
> `--no_device`는 adb 기기가 없을 때 메타 수집을 건너뛴다.

> ⚠️ **손 `adb pull`을 쓰지 않는 이유.** (a) pull 대상을 `outputs/` 아래로 잡으면
> "`outputs/` 산출물을 손으로 만들지 않는다"와 충돌하고, (b) **원본 로그에 git 스탬프가
> 안 붙는다** — 어느 커밋의 앱이 뱉은 로그인지 나중에 알 수 없다.
> `pull_frames.py`는 `init_run(stage="poc_pull")`을 거치므로 같은 디렉토리에
> `run_meta.json`(git commit·dirty·argv)과 `pull_result.json`(adb 명령·returncode·출력 원문,
> 기기 메타, 원격 `ls` 결과)이 함께 남는다.
>
> | 종료 코드 | 뜻 |
> |---|---|
> | 0 | 두 파일 모두 0바이트 초과로 가져옴 |
> | 2 | adb를 찾지 못함 (`--adb`로 경로 지정 가능) |
> | 3 | 기기 문제 — 0대 / 여러 대인데 `--serial` 없음 / 지정 serial이 `device` 상태 아님. **기기를 추측해 고르지 않는다** |
> | 4 | `adb pull` 실패, 또는 **0바이트 파일**(0바이트는 "가져왔다"가 아니다) |
> | 5 | `--no_outputs` — 스탬프 있는 목적지가 없어 pull하지 않았다 |

---

**변경 이력**
- v1 (2026-07-30, 팀원2) — 최초 확정. 합성 데이터로 집계·판정·diff 경로 전체 검증 완료.
- v1.1 (2026-07-30, 팀원2) — 독립 검증 FAIL 2건 반영. 프레임 간격 시계열의 **상한 제거**
  (12초 스톨은 노이즈가 아니라 데이터다), 폐기 샘플 **사유별 카운트 + 판정 노출**,
  `render_latency_ms` 폴백을 `recv_to_render_ms`로 **분리**. §4·§5·§8 문서-코드 불일치 정정.
  스키마 자체(열 이름·타입)는 그대로이므로 `SCHEMA_VERSION`은 1을 유지한다.
- v1.2 (2026-07-30, 팀원2) — 독립 검증 3라운드 반영. **행 단위 소실을 사유별로 계수**
  (`warmup` / `before_t0` / `unparsable_t_recv`)하고 `rows_read = rows_used + 소실` 회계를
  강제. warmup만 의도된 제외로 두어 `data_complete`가 실측에서 무의미해지지 않게 했다.
  **시계 혼용 교차검사 2종**(A: `render_latency <= recv_to_render`, B: 체류시간/출력주기 비율)
  추가 — 값 상한이 아니라 열 관계로 잡고, 범인 열을 `t_capture_ns`와 구분해 지목한다.
  스키마 자체(열 이름·타입)는 그대로이므로 `SCHEMA_VERSION`은 1을 유지한다.
- v1.3 (2026-07-30, 팀원2) — PoC 착수 전 접합부 확정. **① 열 단위 방어선**: `KNOWN_COLUMNS`에
  없는 헤더를 이름으로 지목해 경고(§4). `t_render_end_ns` 오타가 "출력 타임라인 없음"으로
  오진단되던 경로를 막는다. **② `session.json`에 `lighting_condition` 추가**(§5, 어휘 6종 고정)
  하고 `baseline_diff.py`의 `CONDITION_KEYS`에 넣었다 — 저조도에서 AE가 공급 fps를 떨어뜨리므로
  조명은 비교 조건이다. 실측 0건인 시점이라 기존 baseline을 무효화하지 않는다.
  **③ `scripts/pull_frames.py`** 추가 — 손 `adb pull`을 하네스 안으로(§8).
  `SCHEMA_VERSION`은 1을 유지한다: CSV 열 이름·타입은 그대로이고 `session.json`에 키가
  하나 늘었을 뿐이며, 이 키가 없는 로그도 그대로 읽힌다(경고만 붙는다). v1.1·v1.2와 같은 선례다.
