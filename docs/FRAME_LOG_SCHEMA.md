# 프레임 로그 스키마 v3

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

### GPU 패스 시간 (v2 추가, v3에서 D 계열 확장) — **다른 시계에서 온다**

| 열 | 타입 | 의미 | 버짓 칸 | 추가 |
|---|---|---|---|---|
| `stage_b_ms` | float ms | 패스1 OES→오프스크린 720p의 GPU 시간 (색공간 변환/텍스처 업로드) | **B** | v2 |
| `stage_d_ms` | float ms | ② 저조도 개선 패스의 GPU 시간 (감마처럼 **패스가 하나인 arm**) | **D** (D 계열) | v2 |
| `stage_d_analyze_ms` | float ms | ② **통계 산출** 슬롯의 GPU 시간 | **D** (D 계열) | v3 |
| `stage_d_build_ms` | float ms | ② **LUT·계수 생성** 슬롯의 GPU 시간 | **D** (D 계열) | v3 |
| `stage_d_apply_ms` | float ms | ② **적용** 슬롯의 GPU 시간 | **D** (D 계열) | v3 |
| `stage_d_denoise_ms` | float ms | ② **노이즈 억제** 슬롯의 GPU 시간 | **D** (D 계열) | v3 |
| `stage_i_ms` | float ms | ④ 강조 렌더 패스의 GPU 시간 | **I** | v2 |
| `gpu_present_ms` | float ms | 기본 프레임버퍼에 그린 최종 표시 패스의 GPU 시간 | **없음** (§6) | v2 |

#### D 계열(D-family) — D칸을 채우는 열들

**② 저조도를 여러 패스로 쪼개도 채우는 칸은 하나다.** CLAHE는 세 패스(히스토그램 →
클립/CDF → 적용)인데 `GL_TIME_ELAPSED`는 **중첩되지 않으므로 어차피 패스별로 따로 잰다.**
합쳐서 내보내는 것은 정보를 버리는 것이고 위 "유도값은 저장하지 않는다"와도 어긋난다.
그리고 게이트가 "② 단독이 예산 안에 드는가"일 때, **넘었을 때 다음 질문은 "어디가 비싼가"**다
— 히스토그램이 지배하는지 적용 패스가 지배하는지에 따라 경량화 레버가 완전히 달라진다.
같은 런에서 공짜로 얻을 수 있는 정보를 미리 뭉개지 않는다.

- 목록은 `lib/frame_log.py`의 `STAGE_D_FAMILY_COLUMNS`이고 이 표와 **같아야 한다.**
- 그 런의 D는 **`stage_d_total_ms`**(D 계열의 행별 합 → §3)다. 감마만 쓰는 arm은
  `stage_d_ms` 하나, 다패스 arm은 하위 슬롯들 — **arm이 달라도 D칸은 한 키로 읽힌다.**
- 가드·폐기 계수는 다른 GPU 열과 **완전히 동일**하다(하한 `> 0`, 상한 없음 → §4).

##### 하위 열 이름은 알고리즘이 아니라 **슬롯의 역할**이다

| 슬롯 | 담는 것 | arm별 예 |
|---|---|---|
| `stage_d_analyze_ms` | 입력을 **훑어 통계를 만드는** 패스 | CLAHE 히스토그램 / Drago·Reinhard 리덕션 / LIME 조도맵 추정 |
| `stage_d_build_ms` | 그 통계로 **LUT·계수를 만드는** 패스 | CLAHE 클립+CDF / AGCWD 가중 LUT (**없는 arm도 있다**) |
| `stage_d_apply_ms` | 픽셀에 **적용하는** 패스 | LUT 보간+감마 / 톤맵 / 나눗셈 |
| `stage_d_denoise_ms` | **노이즈 억제** 패스 | bilateral (`+bf` arm) |

**왜 알고리즘 이름을 쓰지 않는가.** 처음에는 CLAHE 구성을 그대로 따서
`hist`/`cdf`/`apply`로 지었는데, 그러면 Drago의 **최대휘도 리덕션**이 `stage_d_hist_ms`에
들어가 D칸 분해를 읽는 사람이 **"히스토그램이 비싸다"고 오독한다.** `[D칸]` 라벨에 arm을
묶어 둔 이유(§2 "이 숫자를 읽을 때의 조건" 1항)와 정확히 같은 계열의 문제다.

> **그 arm에서 이 슬롯이 구체적으로 무엇이었는지는 `session.json`의 `render.passes[]`가
> 말한다** — 각 항목의 `gpu_column`이 열 이름을 가리키고, `name`/`shader`가 그 패스의
> 실제 내용이다(앱이 이미 그렇게 쓰고 있다). 열 이름은 **슬롯의 역할**이고 구체적 의미는
> 세션이 선언한다. 하네스는 여기서도 arm을 해석하지 않는다.

> ⚠️ **하위 패스가 슬롯보다 많은 arm이 나오면 앱이 임의로 합쳐 한 슬롯에 넣지 말고 올린다.**
> (예: LIME의 다단 조도맵 추정.) 폰이 합치면 그건 **유도값**이고(위 "유도값은 저장하지
> 않는다" 위반), 어느 패스가 비싼지가 사라져 경량화 레버를 고를 수 없게 된다.
> 슬롯을 늘리는 것은 열 추가(§6)이므로 **하네스가 먼저** 들어간다.

> ⚠️ **`stage_d_ms`와 하위 열이 같은 로그에 동시에 있으면 그 로그는 모호하다.**
> `stage_d_ms`가 "② 전체 합"인지 "또 다른 하위 패스"인지 로그만으로는 알 수 없고, 그대로
> 더하면 이중 계상이다. **하네스는 죽지 않는다**(앱이 스키마보다 앞서 나갈 수 있다) —
> 대신 `stages.stage_d_ambiguous=true`로 남기고 경고하며, **택한 해석을 문장에 명시한다.**
>
> **하네스가 택한 해석: "또 다른 하위 패스"** — `stage_d_ms`를 하위 열과 동등하게 취급해
> `stage_d_total_ms`·`gpu_sum_ms`의 행별 합에 그대로 더한다.
>
> **근거는 틀렸을 때의 방향 하나다.** 이 해석이 틀리면 D가 **크게** 나오고(이중 계상),
> 반대 해석이 틀리면 D가 **작게** 나온다(실재하는 패스를 뺀다). 낙관 쪽으로 틀리는 것은
> 이 하네스가 tail 샘플에 상한을 두지 않는 이유(§4)와 같은 부류의 실패다 — 예산 안에
> 든다고 잘못 믿는 쪽이 더 비싸다.
>
> ⚠️ **"스키마가 합계 열을 금지하므로"는 근거가 아니다.** v2 표는 `stage_d_ms`를
> *"② 저조도 개선 패스**(들)**의 GPU 시간 **합**"* 으로 정의했다 — **v2를 지킨 생산자가
> 거기에 합계를 넣는 것은 위반이 아니었다.** v3부터 그 열의 정의를 "패스가 하나인 arm의
> ② 패스"로 좁혔지만, 그것을 과거 로그에 소급 적용해 "정상 생산자라면 그럴 리 없다"고
> 말할 수는 없다. **`stage_d_ms`가 합계일 가능성은 실재한다** — 그래서 이 판단은 확신이
> 아니라 **편향 방향의 선택**이며, 경고 문장도 그렇게 나간다.
> 📌 이 서술 정정은 독립 검증에서 나왔다(2026-07-31). 결론은 그대로다.

- 출처는 `GL_EXT_disjoint_timer_query`다. 측정 기기(Galaxy A34 / Mali-G68, ES 3.2)에
  확장이 있음을 확인했다.
- **없는 값은 다른 열과 같이 `-1`.** disjoint로 버린 프레임, 그 프레임 안에 해소되지 않은
  query도 전부 `-1`로 온다. `0`으로 대체하지 않는다 — `0`은 "그 패스가 0ms였다"는
  **적극적 주장**이라 "재지 못했다"와 구분되지 않는다.
- ⚠️ **이 값들은 `t_*_ns`와 다른 시계다.** `t_*_ns`는 `CLOCK_BOOTTIME`이고 여기는 GPU
  시계다. 두 시계의 값을 더하거나 빼지 않으며, **시계 교차검사 A/B의 대상도 아니다**
  (교차검사는 "같은 시계라면 반드시 성립해야 할 관계"를 보는 장치인데, 다른 시계끼리는
  위반이 정상이라 범인 열을 엉뚱하게 지목하게 된다).
- ⚠️ **프레임타임은 이 값들의 미터가 아니다 → §3의 "임계 검출기" 항.**

#### 이 숫자를 읽을 때의 조건 (S2 실측에서 확인된 것)

**1. arm 없이 인용하지 않는다.** 위 표의 "버짓 칸"은 **이 열이 어느 칸을 채울 열인가**라는
스키마 사실이지, "그 런의 그 패스가 그 단계를 실제로 돌렸다"는 주장이 아니다. 어느 패스에
무엇이 들어갔는지는 그 런의 **`render_arm`**이 정한다(§5) — 예컨대 ② 자리에 처리를 넣지
않은 arm에서도 `stage_d_ms`는 나온다. 그래서 `analyze_frames.py`는 라벨을 arm에 따라 바꾸지
않고(하네스가 앱의 arm 의미를 해석하기 시작하면 어휘가 어긋나는 날 **조용히 틀린 라벨**이
나온다) **숫자 옆에 arm을 붙인다** — 리포트의 각 줄과 `summary.json`의
`stages.render_arm` / `stages.render_arm_known` 양쪽에. **`render_arm_known=false`인 단계
비용은 `FRAME_BUDGET.md`의 칸에 옮길 수 없다.**

**2. 열별 차분(arm A − arm B)은 아직 신뢰할 수 없다.** 실측에서 **바꾸지 않은 패스3이 바꾼
패스2와 거의 같은 폭으로** 움직였다(p50 차, ms. 같은 기기 SM-A346N, 집계 `run_ts` 표기):

| 비교 | 패스1 `stage_b_ms` | 패스2 `stage_d_ms` | 패스3 `gpu_present_ms` |
|---|---|---|---|
| `blit_2pass`(20260731_125522) → `gamma_only`(20260731_121922) | −0.001 | **+0.239** | **+0.231** |
| `blit_2pass`(20260731_125522) → `gamma_only`(20260731_121455) | +0.007 | **+0.211** | **+0.180** |

패스3은 두 arm에서 같은 코드인데도 바꾼 패스와 같은 자릿수로 움직인다(패스1은 노이즈 수준으로
일치). 원인이 **패스 간 귀속 번짐**(`session.gpu_timer.attribution_note`)인지 **런 간
DVFS/발열 차**인지 아직 갈라내지 못했다 → **열별 차분을 근거로 쓰려면 같은 arm 반복 런으로
노이즈 바닥을 먼저 확정해야 한다.** 그 전에는 "②가 +0.2ms"라고 말할 수 없다.
⚠️ 위 표의 `blit_2pass` 런은 `lighting_condition`이 `unknown`이고 `gamma_only` 두 런은
`indoor_bright`다 — 선언된 비교 조건부터 다르다(`baseline_diff`는 이 쌍을 "조건 다름"으로
낸다). 표는 **"차분을 아직 못 쓴다"는 근거**이지 ② 비용의 추정치가 아니다.

**3. `skipped_ring_full_frames`가 0이 아니면 분포를 할인해 읽는다.** timer query 링이 가득
차서 계측을 건너뛴 프레임은 **회수가 밀린 구간 = GPU가 뒤처진 프레임**, 즉 가장 비싼
프레임이다. 그래서 그 값이 0이 아니면 `stage_*_ms`·`gpu_sum_ms`의 **p95/p99가 낙관 쪽으로
치우친다.** 카운터는 `session.gpu_timer.skipped_ring_full_frames`에 있고,
`analyze_frames.py`가 0이 아닐 때만 경고하며 `summary.json`의
`stages.skipped_ring_full_frames`에도 함께 싣는다.

**4. `gpu_sum_ms`는 하한일 수 있다.** 패스3(기본 프레임버퍼)의 **타일 해결이
`eglSwapBuffers`에서 일어나는데 그 시점은 세 query 전부의 바깥**이다(`GLSurfaceView`가
`onDrawFrame` 반환 후에 부른다). 자세한 갈래와 그 한계는 앱이 적는
`session.gpu_timer.attribution_note`에 있다 — **B·D 칸을 이 값으로 채울 때 그 문구를 함께
옮긴다.**

**위 세 표에 없는 열은 집계에 쓰이지 않고, 하네스가 이름을 지목해 경고한다 → §4 "열 단위 방어선".**

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
>
> ⚠️ **단, 그 단서는 `pipeline_stages`가 비었다는 것만으로 붙이지 않는다.** 선언이 비었는데
> **GPU 단계 시계열에 유효 표본이 있으면** 그 문장("여기서부터 ①②③④가 더해진다")은 그 런에서
> **거짓**이다 — 비용이 이미 더해져 있고 이미 측정돼 있다. 그때는 대신
> **`source.pipeline_stages_contradicted = true`** 로 남기고 *"단계 선언과 실측이 어긋난다"* 는
> 사실을 낸다(값이 나온 열은 `source.pipeline_stages_measured_columns`).
> `gpu_timer_contradicted` · `capture_clock_base_contradicted`와 **같은 패턴**이며
> **판정·종료 코드를 바꾸지 않는다.** 이 상황에서는 아래 "프레임타임 묶임" 단서 쪽이 오히려
> 맞는 단서이므로 그것을 낸다(근거는 빈 선언이 아니라 **값이 나온 GPU 열**이라 문장의
> 라벨이 그렇게 나간다).
> 발생 원인은 보통 (a) 그 패스에 대응하는 `pipeline_stages` 토큰이 아직 없는 경우다 —
> ② 하위 패스 열이 지금 그렇다(토큰 생산자는 앱이므로 하네스가 먼저 지어내지 않는다 → §5).
> 📌 이 경로는 독립 검증에서 나왔다(2026-07-31): `--stage_d_analyze_ms` 등만 준 합성 로그가
> `stage_d_total_ms p50=8.998`을 내면서 동시에 "처리 단계가 없는 파이프라인"이라고 단언했다.

### 단계 비용 (v2) — `summary.json`의 `stages` 블록

| 지표 | 계산 | 무엇을 말하나 |
|---|---|---|
| `stage_b_ms` · D 계열 4개 · `stage_i_ms` · `gpu_present_ms` | CSV 열 그대로 | 패스별 GPU 점유 시간 |
| `stage_d_total_ms` | **행별로** 유효한 **D 계열** 열들의 합 | **그 런의 D칸** (v3) |
| `gpu_sum_ms` | **행별로** 유효한 **모든 GPU** 열의 합 | 그 프레임이 GPU를 잡은 총 시간 |

#### `stage_d_total_ms` — D칸을 한 키로 (v3)

**`gpu_sum_ms`와 헷갈리지 않게 읽는다.** 이름이 비슷해 보이지만 더하는 대상이 다르다:

| 파생 시계열 | 더하는 대상 | 쓰는 곳 |
|---|---|---|
| `stage_d_total_ms` | **D 계열만** (`stage_d_ms` + `stage_d_analyze_ms` + `stage_d_build_ms` + `stage_d_apply_ms` + `stage_d_denoise_ms` 중 그 로그에 있는 것) | **D칸** |
| `gpu_sum_ms` | **모든 GPU 열** (B + D 계열 + I + present) | 그 프레임의 GPU 총 점유 |

- **행별 합이지 백분위의 합이 아니다.** `p50(hist) + p50(cdf) != p50(hist+cdf)`.
  행에서 먼저 더한 뒤 분포를 낸다(`gpu_sum_ms`와 같은 규칙). 실측 예: 하위 두 열의 p50이
  각각 1.0인데 `stage_d_total_ms`의 p50이 11.0인 입력을 만들 수 있다(값이 행마다 반대로
  치우친 경우) — 두 값을 더해 D를 말하면 틀린다.
- 그 행에 유효한 D 계열 값이 하나도 없으면 기여하지 않는다. 일부만 유효한 행은 합에 들어가되
  `stages.stage_d_total_partial_rows`로 개수를 노출한다(빠진 패스만큼 D가 작다).
- `stages.stage_d_columns`에는 **그 런에서 실제로 더해진 D 계열 열**만 들어가고, 스키마가
  정의한 전체 목록은 `stages.stage_d_columns_defined`에 따로 있다(`gpu_sum_columns`와 같은 규칙).
- 리포트는 `D칸 구성: <열> + <열> = stage_d_total_ms` 줄로 **무엇을 더했는지**를 먼저 내고,
  하위 패스 줄에는 `·` 들여쓰기를 붙인다 — `stage_d_total_ms`만 보고 D를 인용할 사람과
  내부 분해를 보고 레버를 고를 사람이 둘 다 있기 때문이다.
- **D 계열이 모호한 로그**(§2)에서는 `stages.stage_d_ambiguous=true`이며, 이 값은 D를 두 번
  셌을 수 있다. 경고 문장이 어느 해석을 썼는지 밝힌다.

- **`frametime` 블록과 키를 섞지 않는다.** 간격/체류시간과 단계 비용은 다른 물리량이고
  **다른 시계**에서 온다. `render_latency_ms`와 `recv_to_render_ms`를 같은 키에 넣지 않는
  것과 같은 이유다 — 소비자가 자기가 받은 숫자가 무엇인지 키로 알 수 있어야 한다.
- **`gpu_sum_ms`는 백분위를 더한 값이 아니다.** `p50(B) + p50(D) != p50(B+D)`이므로
  **행에서 먼저 더하고** 그 뒤에 분포를 낸다. 한 행에 유효한 GPU 열이 하나도 없으면
  그 행은 기여하지 않는다. 일부만 유효한 행은 합에 들어가되 `stages.gpu_sum_partial_rows`로
  개수를 노출한다(빠진 패스만큼 합이 작으므로 분포가 아래로 치우친다).
- `stages.gpu_sum_columns`에는 **그 런에서 실제로 더해진 열**만 들어간다(헤더에 있던 것만).
  스키마가 정의한 전체 목록은 `stages.gpu_sum_columns_defined`에 따로 있다 — 한 키만 보고도
  "무엇을 더한 값인지"가 맞아야 하고, 두 키를 대조해야 알 수 있는 상태로 두지 않는다.
- `gpu_present_ms`도 `gpu_sum_ms`에 **포함한다.** 버짓 칸이 없다는 것은 A~J 매핑이 없다는
  뜻이지 GPU를 안 쓴다는 뜻이 아니다. 칸별로 보고 싶으면 개별 시계열을 본다.
- **`render_arm`을 이 블록에도 싣는다**(`stages.render_arm` / `stages.render_arm_known`).
  `session` 블록에도 있지만, **이 블록만 떼어 읽는 경로**가 열려 있으면 그 소비자에게는
  arm이 사라진 채 `stage_d_ms`만 남는다 → §2 "이 숫자를 읽을 때의 조건" 1항.
  분포의 낙관 편향 지표인 `stages.skipped_ring_full_frames`도 같은 이유로 함께 싣는다(3항).
- **판정선이 없다.** `stages`의 어떤 값도 `verdict.meets_*`나 종료 코드를 흔들지 않는다.

> ⚠️ **프레임타임은 단계 비용의 미터가 아니라 임계 검출기다.**
> 카메라가 30fps로 공급하면 프레임타임은 공급 주기(~33ms)에 묶이므로, 단계 비용이 그 아래인
> 한 패스를 얹어도 프레임타임은 **전혀 변하지 않는다.** 즉 "② 셰이더를 얹었는데 회귀가
> 없다"는 관측은 **"D가 0"이 아니라 "D < 공급 주기"**라는 뜻이다.
> `analyze_frames.py`는 `pipeline_stages`가 비어 있지 않은데
> `p50(output_interval_ms) ≈ p50(recv_interval_ms)`이면 이 단서를 자동으로 붙인다
> (진단용 임계 `FRAMETIME_PINNED_REL_DIFF`, 판정선과 무관). tail(p95)까지 묶였는지에 따라
> 문장이 갈린다 — 간헐적으로 무거운 단계는 **중앙값만 묶이고 p95는 이미 벌어진다.**
>
> ⚠️ **`capture_to_render_ms`·`render_latency_ms`에는 GPU 실행 시간이 들어 있지 않다.**
> `t_render_end_ns`는 드로우콜 **제출** 시각이다(`glDrawArrays`는 즉시 반환한다).
> ②를 얹은 뒤 "지연이 그대로다"라고 읽으면 틀린다. 늘어난 GPU 비용은 `stages`에 나타난다.

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
| `stage_b_ms` · `stage_i_ms` · `gpu_present_ms` | `> 0` | **없음** |
| D 계열: `stage_d_ms` · `stage_d_analyze_ms` · `stage_d_build_ms` · `stage_d_apply_ms` · `stage_d_denoise_ms` | `> 0` | **없음** |
| `stage_d_total_ms` (파생) | `> 0` | **없음** |
| `gpu_sum_ms` (파생) | `> 0` | **없음** |

GPU 패스 시간에 상한을 두지 않는 이유도 같다. 한 패스의 시작/끝을 **같은 GPU 시계 안에서**
닫으므로 큰 값은 시계 오류가 아니라 진짜 느린 프레임이다 — 발열로 GPU 클럭이 떨어지는
구간이 정확히 우리가 잡아야 할 대상이므로, 상한을 두면 잡아야 할 것을 버린다.

**`-1`은 하한에 걸려 `below_min`으로 계수된다.** 이 열에서 `below_min`의 뜻은 시계 역행이
아니라 **"disjoint로 버려졌거나 query가 해소되지 않았다"**이며, 폐기 경로는 같아도 사람이
읽는 문장은 그렇게 나간다(엉뚱하게 "시계 역행"이라고 쓰면 폰 쪽이 시계 코드를 뒤진다).
열이 **헤더에 아예 없으면** 폐기로 세지 않는다 — "열이 없다"와 "열은 있는데 `-1`이다"는
다른 사실이고, 후자만 측정 실패다. 유효 표본이 0이면 별도 경고가 붙는다
(`count == 0`을 "그 패스가 0ms였다"로 읽는 것을 막는다).

**상한은 `capture_to_render_ms`에만 있다.** 여기만 기준 시계가 다른 값(`t_capture_ns`)이
섞여서, 기준이 어긋나면 수천 초가 나온다. 나머지는 전부 같은 단조 시계 하나에서 나오므로
큰 값은 시계 오류가 아니라 **실제로 느린 프레임**이다(발열 스로틀링, GC, 백그라운드 전환).
p95로 tail을 관리하는 하네스가 느린 쪽 샘플을 버리면 존재 이유와 정면으로 어긋난다.

**하한은 전부 유지한다.** 0 이하 간격·지연은 물리적으로 불가능하다(시계 역행).

**유한하지 않은 값(`NaN` / `Infinity` / `-Infinity`)은 어떤 시계열에도 들어가지 않는다.**
`float("NaN")`은 예외를 내지 않고 통과하는 데다 **어떤 비교에도 False**를 돌려주므로
`value <= lo` 형태의 가드를 그냥 지나간다. 그러면 (a) 정렬 순서가 깨져 백분위가
무의미해지고 (b) `json.dump`가 표준 JSON이 아닌 `NaN`/`Infinity` 맨 토큰을 뱉어
**파이썬 아닌 소비자가 요약을 못 읽는다.** 그래서 두 겹으로 막는다 —
파싱 경계(`_to_float`)에서 `-1`로 바꾸고, 가드 판정(`_collect`)을 부정형(`not (value > lo)`)으로
써서 값의 출처와 무관하게 걸리게 한다. **걸린 값은 `below_min`으로 계수되고 경고에 드러난다**
(조용히 사라지지 않는다). 📌 이 구멍은 독립 검증에서 나왔다 — v2가 float 열을 들이면서
생겼고, 기존 int 열은 `int(float(x))`가 `ValueError`를 내서 원래 막혀 있었다.

### 시계 혼용 교차검사 — 값으로 거르지 않고 관계로 잡는다

`t_recv_ns`에 `elapsedRealtimeNanos()`(BOOTTIME), `t_render_*_ns`에 `System.nanoTime()`
(MONOTONIC)을 섞어 쓰면 **딥슬립 시간만큼** 어긋난다. 이건 상한으로는 못 잡는다 —
큰 값이 진짜 느린 프레임일 수 있기 때문이다(위 표에서 상한을 없앤 이유와 같다).
대신 **열끼리 반드시 성립해야 하는 물리 관계**를 본다. 걸려도 **값을 버리지 않고 경고만** 낸다.

| 교차검사 | 규칙 | 잡아내는 방향 |
|---|---|---|
| A | `render_latency_ms <= recv_to_render_ms` (= `t_render_start >= t_recv`) | `t_render_*`가 `t_recv`보다 **뒤처진** 경우. 렌더는 수신 후에 시작하므로 위반은 물리적으로 불가능 |
| B | `p50(recv_to_render_ms) <= 20 × p50(<기준 주기>)` | `t_render_*`가 `t_recv`보다 **앞선** 경우. 이때 A는 통과하므로 B가 없으면 1시간짜리 체류시간이 경고 없이 채택된다 |

> **B의 기준 주기는 `output_interval_ms`이고, 그게 비어 있으면 `recv_interval_ms`로 폴백한다.**
> `t_render_end_ns`가 없는 로그에서는 출력 주기 자체가 없는데, 그때 검사를 통째로 건너뛰면
> 시계 혼용이 경고 없이 지나간다. 어느 쪽을 썼는지는 `clock_check.dwell_vs_interval.reference_series`에
> 이름으로 남으므로, 비율만 보고 어느 기준인지 되물을 일이 없다.

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
  "schema_version": 3,
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

### v2에서 늘어난 블록 (전부 선택 — 없어도 집계는 죽지 않는다)

| 키 | 담는 것 |
|---|---|
| `gl` | GL 구현 정보(vendor·renderer·version·확인한 확장 목록). 같은 숫자를 다른 GPU에서 낸 것인지 나중에 되물을 근거 |
| `gpu_timer` | `supported` 등 timer query 선언. **하네스가 유일하게 해석하는 키가 `supported`다** (아래 모순 검사) |
| `stage2_params` | ② 저조도의 파라미터(알고리즘·clip limit·타일·감마 등). 파라미터가 다르면 D 실측끼리 비교가 성립하지 않는다 |
| `render` | 표시 경로·셰이더·패스 수. v1에도 있었고 v2에서 항목이 는다 |

**하네스는 이 블록들을 해석하지 않고 `summary.json`의 `session`에 그대로 싣는다.**
값을 만들어내지 않는다 — 앱이 적은 것이 곧 그 런의 조건 기록이다.
(내용 구조는 앱이 정한다. 하네스가 스키마를 강제하면 앱이 더 적고 싶을 때 막힌다.)

#### `gpu_timer.supported` 모순 검사

`gpu_timer.supported == true`라고 **선언**했는데 단계 시계열의 유효 표본이 0이면
`source.gpu_timer_contradicted = true`가 되고 경고가 붙는다.
`capture_clock_base_contradicted`와 같은 패턴이다 — **선언과 실제가 어긋나면 선언 쪽이
틀렸을 수 있다.** 확장 문자열이 있어도 query가 해소되지 않을 수 있고, CSV에 열을 싣지
못했을 수도 있다. 어느 쪽이든 그 런으로 단계 비용을 말할 수 없다.
선언은 `source.gpu_timer_supported_declared`에 그대로 남는다.
**판정(`meets_*_target`)·종료 코드는 바꾸지 않는다.**

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

### `render_arm` — 단계 비용 숫자의 동반 조건

**허용 어휘는 이 표와 `lib/frame_log.py`의 `RENDER_ARMS` 두 곳이 같아야 한다.**
생산자는 앱이다 — `android/.../gl/RenderArm.kt`의 `id`가 `SessionWriter`를 거쳐 그대로 나간다.

| 값 | 이 arm에서 각 패스가 무엇인가 | `pipeline_stages` |
|---|---|---|
| `passthrough` | 처리 0. OES→화면 1패스. **계측하지 않는다**(GPU 열 자체가 없다) | `[]` |
| `blit_2pass` | 3패스 골격. **② 자리(패스2)는 단순 복사**이며 저조도 개선이 아니다 | `["blit_2pass"]` |
| `gamma_only` | ② 자리에 감마 패스. **② 비용의 하한**이며 알고리즘이 아니다 | `["blit_2pass", "stage2_gamma"]` |
| `synthetic` | **합성 로그**(`gen_synthetic_frames.py`)의 기본값. 어떤 셰이더도 돌지 않았다 | 생성 인자에 따름 |

#### ② arm 예약어 (v3에서 미리 등록) — ⚠️ **계약값이 아니다**

② 알고리즘 arm이 곧 늘어난다. 어휘에 없으면 앱이 붙는 날 매 런 "어휘 밖" 경고가 뜨므로
아래를 미리 등록해 뒀다. **다만 이 이름들은 팀원2(하네스) 쪽 명명이지 팀과 합의한 계약값이
아니다** — `INTERFACES.md`의 미확정 항목과 달리 이건 하네스 내부 어휘일 뿐이고,
**생산자는 앱**이므로 앱이 다른 `id`를 쓰기로 하면 앱 쪽이 정답이며 여기를 고친다.

| 값 | 예정 |
|---|---|
| `clahe_gamma` | CLAHE + 감마 |
| `clahe_gamma_bf` | 위 + 바이래터럴 필터 |
| `agcwd` / `agcwd_bf` | AGCWD (+ 바이래터럴) |
| `drago` · `reinhard` · `lime` | 톤매핑·조도추정 계열 |

**어휘에 있다는 것은 "그 문자열을 안다"는 뜻일 뿐이다.** 하네스는 여전히 arm의 의미를
해석하지 않으며, 어느 arm이 어느 패스에 무엇을 그렸는지는 판단하지 않는다.

- **이 표는 사람이 읽는 것이고, 하네스는 이 의미를 해석하지 않는다.** 하네스가 하는 일은
  "그 문자열이 아는 어휘인가"를 보고 **숫자 옆에 arm을 붙이는 것**까지다. 해석을 시작하면
  앱이 arm을 추가할 때마다 하네스가 따라가야 하고, 동기화가 어긋나는 날 조용히 틀린 라벨이
  나온다 → §2 "이 숫자를 읽을 때의 조건" 1항.
- 키가 없거나 어휘 밖이면 `stages.render_arm_known = false`가 되고, **단계 비용 열이 있는
  런에 한해** 경고가 붙는다(옮길 숫자가 없는 v1·패스스루 런까지 매번 경고하면 곧 아무도 안
  본다 — 그때도 사실은 리포트 줄과 `stages.render_arm`에 그대로 드러난다).
- **판정선이 아니다.** `lighting_condition`·`pipeline_stages`와 같은 취급으로,
  `meets_*_target`·종료 코드를 흔들지 않는다. 어휘 밖 값이 와도 집계는 끝까지 돈다.
- 새 arm을 정식으로 쓰려면 `RENDER_ARMS`와 이 표에 **함께** 등록한다.

### `pipeline_stages` — 어휘를 고정한다 (`lighting_condition`과 같은 방식)

**허용 어휘는 이 표와 `lib/frame_log.py`의 `PIPELINE_STAGES` 두 곳이 같아야 한다.**
`pipeline_stages`는 아래 "비교 조건" 표에 든 `CONDITION_KEYS` 항목이라, 같은 구조를 두 이름으로
부르면(`blit_2pass` vs `pass1_oes_to_offscreen`) **모든 비교가 "조건 다름"이 된다.**
실측이 한 번 쌓이면 그 문자열에 묶이므로 어휘는 실측 전에 고정한다.

**생산자는 앱이다** — 토큰은 `android/.../gl/RenderArm.kt`의 `pipelineStages`가 그대로 나간 것이고,
하네스(`gen_synthetic_frames.py`)가 그 어휘를 따라간다. 반대로 하면 합성 런과 실측 런이 영원히
비교 불가가 된다.

| 토큰 | 의미 | 생산자 |
|---|---|---|
| *(빈 배열)* | 처리 없는 arm(`passthrough`). OES→화면 1패스 | 앱 `RenderArm.PASSTHROUGH` |
| `blit_2pass` | 3패스 골격(OES→오프스크린→②자리→표시). ② 자리는 단순 복사 | 앱 `RenderArm.BLIT_2PASS` / 생성기 `--stage_b_ms` |
| `stage2_gamma` | ② 자리에 감마 패스. **② 비용의 하한**이며 알고리즘이 아니다 | 앱 `RenderArm.GAMMA_ONLY` / 생성기 `--stage_d_ms` |
| `detect` | ③ 탐지. **앱 미구현** — 현재는 합성 로그만 낸다 | 생성기 `--detect_every_n` |
| `stage4_highlight` | ④ 강조. **앱 미구현** — 현재는 합성 로그만 낸다 | 생성기 `--stage_i_ms` |

- 어휘 밖 토큰이 오면 `analyze_frames.py`가 **경고**하고
  `source.pipeline_stages_vocab_ok = false` / `source.pipeline_stages_unknown_tokens`에 남긴다.
- **값이 리스트가 아니어도(int·str·dict) 집계는 끝까지 돈다.** 경고만 붙고 `summary.json`은
  정상 생성된다 — 여기서 죽으면 그날 측정을 통째로 잃고, 정작 그 사실을 적은 요약도 안 남는다.
  단계를 전제하는 두 단서(빈 파이프라인 / 프레임타임 묶임)는 그때 **붙이지 않는다.**
- **하드 에러로 만들지 않는다.** 앱이 새 arm을 하네스보다 먼저 낼 수 있고(미지 열과 같은 상황),
  그때 집계가 죽으면 그날 측정을 통째로 잃는다. **판정(`meets_*_target`)·종료 코드는 바꾸지 않는다.**
- 새 arm을 정식으로 쓰려면 `lib/frame_log.py`의 `PIPELINE_STAGES`와 이 표에 **함께** 등록한다.
  등록 전 런은 과거 런과 "조건 다름"으로 갈린다.

### `schema_version` 대조 — 세션 선언과 하네스 버전을 둘 다 남긴다

`session.json`의 `schema_version`을 아무도 검증하지 않으면, **v1 세션이 v2로 라벨된 요약에
실려 나간다.** `summary.json`의 최상위 `schema_version`은 하네스가 찍은 값이라, 그 요약만
보고는 로그 자신이 뭐라고 선언했는지 되물을 수 없다. 그래서 둘을 이름을 갈라 함께 남긴다.

| `summary.json` 키 | 뜻 |
|---|---|
| `schema_version` (최상위) | **하네스** 버전 (`lib/frame_log.py: SCHEMA_VERSION`) |
| `source.schema_version_declared` | 세션이 선언한 값. 키가 없으면 `null` |
| `source.schema_version_harness` | 위 최상위 값의 사본 — `source` 블록만 보고도 대조가 끝나게 |
| `source.schema_version_matches_harness` | 두 값이 같은가 |

다르면 방향에 따라 문장이 갈린다. **경고만이다 — 판정·종료 코드를 바꾸지 않는다.**
옛 로그는 계속 읽혀야 한다(v1 실측 로그로 확인: `p50` 등 모든 값이 그대로 나온다).

| 상황 | 문장 방향 |
|---|---|
| 선언 < 하네스 | **앱이 뒤처진 로그.** 선언 버전 **이후에 늘어난 열**(경고가 이름으로 나열한다 — `lib/frame_log.py: COLUMN_ADDED_IN`)이 없을 수 있다. `stages`의 count 0은 "0ms"가 아니라 "그 빌드가 재지 않았다". ⚠️ **스키마 확장 시 하네스가 앱보다 먼저 들어가므로**(§6), 앱 라운드가 붙기 전까지 이 경고가 뜨는 것은 **정상이고 의도된 순서**다 |
| 선언 > 하네스 | **앱이 하네스보다 앞서 나갔다.** 앱이 새로 넣은 열은 미지 열로 버려지므로 새 지표가 요약에 없다. 하네스를 먼저 올리고 다시 집계할 것 |
| 키 없음 | 로그가 자기 스키마를 말하지 않는다. `--session`을 생략했거나 스키마 키 이전 빌드 |
| 정수가 아님 (`2.7` · `"two"` · `true`) | 대조 불가. 앱 쪽 `SessionWriter` 확인 |

> ⚠️ **`int()`로 뭉개지 않는다.** `int(2.7) == 2`라서 **불일치가 경고 없이 일치로 정규화**되고,
> `int(True) == 1`이라 타입 오류가 "v1 로그"라는 거짓 사실로 보고된다. 정수로 딱 떨어지는
> 값만 버전으로 받는다(`2.0`은 정수 2와 같은 값이므로 받는다).

`capture_clock_base_contradicted` / `lighting_condition_comparable`과 같은 패턴이다 —
**선언과 실제가 어긋나면 사실을 `source` 블록에 불리언으로 남기고 경고한다.**

### 비교 조건 (`baseline_diff.py`가 다르면 "비교가 아니라 착시"라고 경고)

`CONDITION_KEYS`에 있는 것만이 비교 조건이다.

| 키 | 왜 조건인가 |
|---|---|
| `device.props.model` · `device.props.build_fingerprint` | 다른 기기 숫자를 비교하면 착시다 |
| `session.build_type` | **`release`가 아니면 그 숫자는 근거로 못 쓴다** (debug는 프레임타임이 부풀려짐) |
| `session.pipeline_stages` | 빈 배열 = 빈 파이프라인. 이게 다르면 성능 비교가 성립하지 않는다 |
| `session.render_arm` | **그 런이 실제로 무엇을 그렸는지**를 정한다. `pipeline_stages`가 이것을 담지 못한다는 것이 실증됐다 — ② 하위 패스 열에는 대응 토큰이 없어서 `blit_2pass` 런과 `clahe_gamma` 런이 **둘 다 `["blit_2pass"]`** 였고, 처리량이 완전히 다른데도 무경고로 "회귀 없음"이 나왔다. 조명과 정확히 같은 성격의 조건이다 |
| `session.lighting_condition` | 저조도에서 카메라 AE가 노출을 늘리면 **공급 fps 자체가 떨어진다.** 밝은 방 런과 야간 런을 비교하면 코드가 그대로여도 "회귀"로 오판정된다 |
| `source.warmup_sec` | 워밍업 구간이 다르면 같은 로그도 다른 분포가 된다 |

> ⚠️ **`render_arm`을 조건에 넣은 대가를 알고 받는다.** v1 승격 베이스라인에는 이 키가 없어서
> (`None`) 새 `passthrough` 런과 비교하면 **"조건 다름"으로 뜬다**:
> `session.render_arm: baseline=None vs current='passthrough'`.
> 이건 버그가 아니라 **정직한 표시**다 — 그 옛 로그는 자기가 어떤 arm이었는지 실제로
> 기록하지 않았고, "passthrough 상당"은 우리의 추론이다. `comparable`은 **exit code를
> 바꾸지 않으므로** 비교 자체가 막히지는 않는다(숫자는 그대로 나오고 경고가 붙는다).

### 조건이 아니라 **결과**인 것 (비교하지 않고 기록·보고만 한다)

| 키 | 왜 조건이 아닌가 |
|---|---|
| `frames_dropped` · `camera_frames_offered` · `frames_emitted` | 측정의 입력 조건이 아니라 **산출물**이다. 실행마다 달라지는 게 정상이라 조건으로 넣으면 모든 비교가 "조건 다름"이 된다. 대신 `summary.json`의 `session` 블록과 `source.dropped_total`에 그대로 실려 보고된다 — 드롭을 숨기면 프레임타임이 실제보다 좋아 보이므로 **숨기지 않는 것**이 요구사항이고, 비교 차단은 요구사항이 아니다 |

## 6. 단계가 추가될 때 (①②③④ 붙이기)

**열을 추가한다. 기존 열의 의미를 바꾸지 않는다.**

```
stage_b_ms, stage_c_ms, stage_d_ms, stage_e_ms, ...   ← 버짓 칸이 있는 비용
gpu_present_ms                                        ← 버짓 칸이 없는 비용
```

버짓 칸이 있는 비용은 `FRAME_BUDGET.md` §3의 칸 이름(A~J)을 그대로 쓴다 — 그래야 실측이
어느 칸을 채우는지가 매핑 없이 드러난다.
(칸 **이름**만 쓴다. 칸별 배정치는 `FRAME_BUDGET.md` v0.2에서 폐기됐고 인용하지 않는다.
단계 비용은 실측으로만 말한다.)

### 버짓 칸이 없는 비용도 열로 받는다

**A~J 어느 칸에도 속하지 않지만 실제로 GPU/CPU를 잡아먹는 구간이 있다.**
`gpu_present_ms`(기본 프레임버퍼에 그리는 최종 표시 패스)가 그렇다. 화면에 내보내려면
반드시 도는 패스이고 픽셀 수만큼 비용이 드는데, 버짓표의 어떤 칸도 이걸 가리키지 않는다.

이런 열을 "칸이 없으니 빼자"고 하면 **총 GPU 비용이 조용히 과소평가된다** — 칸의 합이
프레임 비용과 안 맞는데 그 차이가 어디서 왔는지 되물을 수 없게 된다. 그래서:

- 칸 이름이 없는 열은 **`stage_*` 접두사를 쓰지 않는다.** 이름만 보고 "어느 칸이지?"를
  찾게 만들지 않기 위해서다 (`gpu_present_ms`처럼 성격을 이름에 적는다)
- `summary.json`의 `stages.budget_cell`에 열→칸 매핑이 실려 나가고, 칸이 없으면 `null`이다
- 그래도 `gpu_sum_ms`에는 **포함한다.** 총량은 총량이다

열을 추가하면 `SCHEMA_VERSION`을 올리고, `lib/frame_log.py`의 `OPTIONAL_COLUMNS`(GPU 시간이면
`GPU_TIME_COLUMNS`)와 이 문서 §2에 **함께** 등록한다. 등록하지 않으면 하네스는 그 열을
미지 열로 보고 경고만 한 뒤 **집계에서 통째로 버린다** — 10분 측정이 숫자 없이 끝난다.
그래서 **하네스 쪽이 앱보다 먼저 들어간다.**

## 7. 폰 쪽 구현 시 지켜야 할 것

- **`ImageProxy.close()`를 `finally`에서.** 안 닫으면 카메라가 새 프레임을 못 보내고 파이프라인이 멈춘다
- 백프레셔는 **`STRATEGY_KEEP_ONLY_LATEST`**. 큐에 쌓으면 프레임타임이 좋아 보이고 지연만 는다
- 로그는 **메모리에 모았다가 끝날 때 한 번에 쓴다.** 매 프레임 파일 I/O를 하면 측정 대상이 오염된다
- 측정은 **release 빌드, 실기기**로만. 에뮬레이터 프레임은 실기기 숫자가 아니다
- 출력 위치는 **런별 디렉토리**다:
  `getExternalFilesDir(null)/runs/<YYYYMMDD_HHMMSS>/{frames.csv,session.json}`
  = `/sdcard/Android/data/<pkg>/files/runs/<YYYYMMDD_HHMMSS>/`.
  `pull_frames.py`가 이 경로를 기본값으로 본다(§8). 앱 외부 파일 디렉토리가 다르면
  `--remote_dir`로 그 **베이스**를 알려준다(런은 그 아래 `runs/`에서 찾는다).
  ⚠️ **`files/` 바로 아래에 평면으로 쓰지 않는다.** 그 자리는 S1 이전 빌드의 잔해가 남는
  곳이라, 회수 스크립트가 거기서 집으면 낡은 로그가 조용히 최신 측정 행세를 한다
- `session.json`의 **`lighting_condition`은 측정할 때 실제 조건으로 채운다**(§5 어휘).
  비워 두면 그 런은 나중에 아무것과도 정직하게 비교할 수 없다

## 8. 사용법

### 런이 여러 개인 세션은 `run_session.py`로 (아래 ①②를 대신 돌려 준다)

arm·조명·길이가 정해진 런을 여러 개 찍는 날(S3-3 9런 같은)에는 회수·집계·비교를 손으로
맞추지 않는다. **한 번 틀리면 그 런은 통째로 못 쓴다** — 실제로 조명 `unknown`으로 나간
런이 한 세션에 여러 개 생겼다.

```bash
python scripts/run_session.py --print_plan     # 계획만 확인 (기기·진행 파일을 건드리지 않는다)
python scripts/run_session.py                  # 세션 시작 — 남은 런부터 이어간다
python scripts/run_session.py --status         # 어디까지 했나
python scripts/run_session.py --report         # 남은 런을 더 찍지 않고 집계·비교만 다시
python scripts/run_session.py --plan my.json --session_id night_2   # 계획을 파일로
```

- **폰은 사람이 조작한다.** `adb shell input`으로 버튼을 누르는 기능은 일부러 없다 —
  좌표는 기기·해상도·OS에 따라 바뀌고, 틀린 좌표로 엉뚱한 걸 눌러도 스크립트는 성공을
  보고한다. 스크립트는 **계획 제시 → 대기 → 검증**만 한다.
- **검증이 존재 이유다.** 런이 끝나면 회수해서 `session.json`의 `render_arm` ·
  `lighting_condition` · `build_type` · `build.git_dirty` · **분석 창 길이**를 계획과
  대조하고, 어긋나면 크게 알리고 **그 칸을 실패로 남긴다**(다음 실행에서 다시 제시된다).
- **중단·재개된다.** 진행 상태는 `outputs/measure_session/_sessions/<session_id>.json`에
  남고, 각 실행은 자기 `init_run()` 스탬프를 갖는다(그 run_ts가 `invocations[]`에 쌓인다).
- **판정선을 갖지 않는다.** PASS/FAIL은 `analyze_frames`(`lib/targets.py`)와
  `baseline_diff`가 낸다. 이 스크립트는 실행과 대조만 하고, 세션 끝에
  **노이즈 바닥(같은 arm 반복의 열별 차이)** 과 **arm 간 차분**을 나란히 낸다 —
  차분이 바닥보다 작으면 그건 신호가 아니다. `gpu_present_ms`는 arm이 달라도 같은
  코드인데 움직이므로 **arm 비교에 항상 함께** 나온다(귀속 번짐 지표).
- 프롬프트: `Enter`=측정 끝났음 · `s [사유]`=건너뛰기 · `q`=중단(재개 가능) ·
  `u <기기 런 이름>`=이미 찍힌 런을 쓴다(신규 감지를 건너뛰지만 **계획 대조는 그대로 돈다**).

| 종료 코드 | 뜻 |
|---|---|
| 0 | 계획의 모든 런이 통과. 세션 리포트 생성 |
| 1 | 남은 런이 있다(중단했거나 어긋난 칸이 남았다) — 다시 실행하면 이어간다 |
| 2 | 계획이 잘못됐다 (어휘 밖 arm/조명, `unknown` 조명 계획, 깨진 JSON, 없는 파일) |
| 3 | adb 없음 / 기기 문제 — 계획 대조가 불가능하므로 세션을 진행하지 않는다 |
| 5 | `--no_outputs` — 진행 파일을 남길 수 없어 재개가 불가능하다(`--print_plan`만 가능) |
| 6 | 남은 런은 없지만 **건너뛴** 칸이 있다 — 완주가 아니다 |
| 7 | 진행 파일과 계획이 어긋난다(계획 지문·`warmup_sec` 불일치) — 재개 거부 |

> ⚠️ **3분 런은 지속 성능 근거가 아니다.** warmup을 빼면 분석 창이 2.5분이라
> `lib/targets.py`의 `SUSTAINED_SEC`에 한참 못 미친다. 리포트는 그런 런을 `envelope_only`로
> 표시하고 단서를 붙인다 — **봉투 점으로만 인용한다.**
> 같은 이유로 "10분" 런도 warmup 30초를 빼면 570s라 지속 창(600s)에 못 미친다.
> 지속 근거로 쓰려면 **warmup + `SUSTAINED_SEC`**(= 10.5분) 이상 찍는다.

```bash
# ① 폰에서 로그를 가져온다 (outputs/poc_pull/<run_ts>/<기기 런 이름>/ 로 들어간다)
python scripts/pull_frames.py                        # 기기의 **가장 최근 런** 하나
python scripts/pull_frames.py --list                 # 회수하지 않고 런 목록만 (이름·크기·시각)
python scripts/pull_frames.py --run 20260731_032312  # 특정 런
python scripts/pull_frames.py --all                  # runs/ 아래 전부 (야간 여러 런을 한 번에)
python scripts/pull_frames.py --serial <serial>      # 기기 여러 대면 필수
python scripts/pull_frames.py --package <pkg> --remote_dir /sdcard/...  # 베이스 경로가 다르면

# ② 가져온 로그를 집계 (기기 메타는 adb로 자동 수집)
python scripts/analyze_frames.py \
  --frames outputs/poc_pull/<run_ts>/<기기 런 이름>/frames.csv \
  --session outputs/poc_pull/<run_ts>/<기기 런 이름>/session.json

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

# GPU 패스 시간(v2) 합성. 0이면 그 열을 아예 쓰지 않는다(= v1 모양 로그가 나온다)
python scripts/gen_synthetic_frames.py --stage_b_ms 2 --stage_d_ms 5 --gpu_present_ms 1.2
python scripts/gen_synthetic_frames.py --stage_d_ms 5 --gpu_disjoint_frac 0.1   # 10% 행을 -1로
python scripts/gen_synthetic_frames.py --stage_d_ms 5 --gpu_disjoint_frac 1.0   # 전부 -1 (gpu_timer 모순 경로)
python scripts/gen_synthetic_frames.py --stage_d_ms 60                          # GPU가 공급 주기를 넘는 경우

# ② 하위 패스(v3). 셋 다 D 계열이라 stage_d_total_ms(행별 합)로 묶여 나온다
python scripts/gen_synthetic_frames.py --stage_b_ms 2 \
  --stage_d_analyze_ms 1.5 --stage_d_build_ms 0.4 --stage_d_apply_ms 2.2 \n  --stage_d_denoise_ms 1.1 --render_arm clahe_gamma_bf
# stage_d_ms와 하위 열을 **동시에** 주면 D 계열 모호 경로(경고 + 해석 명시)
python scripts/gen_synthetic_frames.py --stage_d_ms 6 --stage_d_analyze_ms 1 --stage_d_build_ms 2

# render_arm / pipeline_stages는 **명시 인자**다 (유추하지 않는다)
python scripts/gen_synthetic_frames.py --stage_d_ms 5 --render_arm gamma_only
python scripts/gen_synthetic_frames.py --stage_d_ms 5 --render_arm ""   # 키 없음 = 'arm 미상' 경고 경로
python scripts/gen_synthetic_frames.py --stage_d_analyze_ms 3 --stage_d_build_ms 1 --stage_d_apply_ms 5 \
  --pipeline_stages "blit_2pass" --render_arm clahe_gamma   # 자기모순 없는 ② 단독 런
python scripts/gen_synthetic_frames.py --pipeline_stages ""             # 명시적 빈 배열
```

> ⚠️ **`--pipeline_stages`를 생략하면 `--stage_*_ms`/`--detect_every_n`에서 유추한다(옛 동작).**
> ② 하위 패스 열에는 대응 토큰이 없으므로, 하위 열만 주면 `pipeline_stages=[]`인
> **자기모순 로그**(선언은 "처리 없음"인데 D 실측이 있다)가 나온다. 생성기가 그 상황을
> 생성 시점에 경고하지만, 모순 경로를 일부러 만드는 게 아니라면 명시할 것.

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
> 기기 메타, 원격 `ls` 결과, **실제로 어느 원격 경로에서 가져왔는지**)이 함께 남는다.
>
> | 종료 코드 | 뜻 |
> |---|---|
> | 0 | 선택된 런의 두 파일을 모두 0바이트 초과로 가져옴 (`--list`는 목록을 낸 뒤 0) |
> | 2 | adb를 찾지 못함 (`--adb`로 경로 지정 가능) |
> | 3 | 기기 문제 — 0대 / 여러 대인데 `--serial` 없음 / 지정 serial이 `device` 상태 아님. **기기를 추측해 고르지 않는다** |
> | 4 | `adb pull` 실패, **0바이트 파일**, **회수할 런이 0개**(`runs/`가 비었거나 런 디렉토리가 하나도 없거나 `--run` 이름이 기기에 없음), **원격 조사 실패**(권한 거부 등), `--run`+`--all` 동시 지정 |
> | 5 | `--no_outputs` — 스탬프 있는 목적지가 없어 아무것도 하지 않았다(`--list` 포함) |

#### 원격 레이아웃 선택 규칙 — 조용한 폴백이 오염 경로였다

앱은 `<files>/runs/<YYYYMMDD_HHMMSS>/`에 쓴다(§7). 예전 구현은 `runs/`를 모르고
`<files>/` 바로 아래의 **평면 파일**을 가져오면서 `ok=true`·exit 0을 냈고, 그 자리에는
S1 이전 빌드의 낡은 로그가 남아 있었다. `pipeline_stages`가 빈 배열이라 `baseline_diff`는
새 passthrough 런과 **"비교 가능"으로 판정**해 버린다 — 실패가 아니라 조용한 오염이다.

| 기기 상태 | 동작 |
|---|---|
| `runs/`에 런 있음 | 기본은 **가장 최근 하나**(정렬 규칙은 아래). `--run`으로 지정, `--all`로 전부 |
| `runs/`에 런 있음 **+ 평면 파일도 남아 있음** | `runs/`를 쓰되 **평면 파일이 남아 있다고 경고**한다(측정 전에 지울 대상) |
| `runs/`는 있는데 **비어 있음** | **실패(4).** 폴백하지 않는다 — "런이 없다"와 "낡은 파일이 있다"는 다른 상황이다 |
| `runs/`에 엔트리는 있는데 **런 디렉토리가 하나도 없음**(파일만 있음) | **실패(4). 기본·`--list`·`--all` 세 모드 전부.** 비어 있는 것과 같은 결론이며, 런이 아닌 항목의 이름을 실패 문장에 싣는다 |
| `runs/`는 있는데 **읽을 수 없음**(권한 거부 등) | **실패(4).** "런이 없다"로 뭉개지 않고 **`ls`의 returncode와 stderr 원문으로 권한을 지목**한다 |
| `runs/`가 **아예 없음** + 평면 파일 있음 | 평면 폴백. **크게 경고한다**(S1 이전 로그이며 지금 측정과 비교 금지). 로컬 디렉토리 이름은 `_legacy_flat` — 기기 런 이름과 모양이 다르다 |
| `runs/`가 없는데 `--run`/`--all` 지정 | **실패(4).** 명시적으로 런을 지목했는데 옛 파일을 대신 주지 않는다 |
| `runs/`도 없고 평면 파일도 없음 | **실패(4).** 앱이 한 번도 측정을 끝내지 않았거나 경로가 다르다 |

> **0개 회수는 어느 모드에서도 성공이 아니다.** F1이 "낡은 걸 가져오고 성공"이었다면
> "아무것도 안 가져오고 `ok=true`"는 같은 계열의 조용한 성공이다. 선택 단계에서 대상이
> 0개면 pull 루프에 들어가기 전에 실패로 낸다(방어선이 이중으로 있다).
> 실패해도 `pull_result.json`은 남는다 — 그때 기기에 무엇이 있었는지가 기록의 일부다.

> **"가장 최근"은 사전순이 아니다.** 런 이름은 `YYYYMMDD_HHMMSS`이고 같은 초에 두 번
> 시작하면 앱이 `_2`, `_3`, … `_10`으로 접미사를 붙이는데, 사전순으로는 `_10 < _2`라서
> 틀린 런을 "최신"으로 고른다. 접미사를 **숫자로 떼어** 정렬한다
> (`pull_frames.py: run_sort_key`). 규격에서 벗어난 이름은 원본 문자열로 정렬한다.

**출력은 항상 `<out_dir>/<기기 런 이름>/{frames.csv,session.json}`이다.** 평면으로 떨어뜨리지
않는다 — 어느 기기 런에서 온 숫자인지가 경로에 남아야 하고, `--all`이면 런마다 하나씩 생긴다.

> ⚠️ **`--remote_dir`에 런 디렉토리를 직접 주지 말 것.** 이 옵션은 앱 외부 파일 디렉토리
> (그 아래에서 `runs/`를 찾는다)를 가리킨다. 런 디렉토리를 직접 주면 그 안에 `runs/`가 없으니
> 평면 폴백이 걸려 **"S1 이전 로그"라는 거짓 경고**와 함께 `_legacy_flat/`으로 떨어진다.
> 여기를 영리하게 만들지 않는 이유: 추론을 넣으면 그 경고가 진짜로 필요한 자리에서 약해진다.
> 특정 런은 `--run`으로 고른다.

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
- **v2 (2026-07-31, 팀원2) — `SCHEMA_VERSION` 1 → 2.** ② 저조도 셰이더의 D칸 실측을 받을
  접합부. **CSV에 열이 4개 늘었으므로** 이번에는 버전을 올린다(앞의 v1.x와 달리 열 자체가
  바뀌었다): `stage_b_ms`(B칸) · `stage_d_ms`(D칸) · `stage_i_ms`(I칸) · `gpu_present_ms`
  (칸 없음). 출처는 `GL_EXT_disjoint_timer_query`이고 **`t_*_ns`와 다른 시계**라 시계
  교차검사에 넣지 않는다(§2·§4). 파생 시계열 `gpu_sum_ms`는 **행별 합**이다
  (`p50(B)+p50(D) != p50(B+D)`). `summary.json`에 `stages` 블록이 생겼고 `frametime`과
  분리했다 — 다른 물리량·다른 시계를 같은 키에 섞지 않는다.
  해석 단서 2종 추가: **① 프레임타임이 카메라 공급에 묶였는지**(단계가 붙어도 프레임타임은
  단계 비용의 미터가 아니라 임계 검출기다 — §3), **② 제출 시각 기반 지연에는 GPU 실행
  시간이 없다**. `gpu_timer.supported` 선언 ↔ 실제 표본 모순 검사도 추가(§5).
  하네스가 앱보다 먼저 들어간 이유는 §6 마지막 문단.
  ⚠️ 앱 쪽 `SessionWriter.kt`의 `SCHEMA_VERSION`과 `FrameLogRecorder.kt`의 헤더는
  **android 트랙에서 따로 맞춘다**(이 커밋에는 없다). 열이 없는 v1 로그는 그대로 읽히고
  `stages`의 count가 0이 될 뿐이다 — 실측 로그 `run_ts=20260731_003819`로 확인했다.
- **v2.1 (2026-07-31, 팀원2) — 오염 경로 3건.** 스키마 자체(열 이름·타입)는 그대로이므로
  `SCHEMA_VERSION`은 2를 유지한다.
  **① `pull_frames.py`가 `runs/`를 본다**(§7·§8). 앱이 런별 디렉토리로 옮긴 뒤에도 회수
  스크립트가 `files/` 평면 경로를 보면서 **낡은 로그를 가져오고 성공을 보고**하던 것을 막는다.
  기본=최근 런 하나 / `--run` / `--all` / `--list`, 출력은 `<out_dir>/<기기 런 이름>/`,
  평면 폴백은 `runs/`가 없을 때만 + 큰 경고, `runs/`가 비었으면 실패.
  **② `schema_version` 대조**(§5). 세션 선언과 하네스 버전을 둘 다 `summary.json`에 남기고
  다르면 방향별로 경고한다 — 판정·종료 코드는 그대로이며 v1 로그는 계속 읽힌다.
  **③ `pipeline_stages` 어휘 고정**(§5). 생성기가 쓰던 `pass1_oes_to_offscreen`/`stage2_lowlight`를
  **앱 어휘**(`blit_2pass`/`stage2_gamma`)로 통일했다. 어휘 밖 토큰은 경고(조명과 같은 취급).
  실기기 확인: android 커밋 `0fe533f`의 런 3개를 회수해 집계했고(`schema_version=2` 선언 일치,
  `pipeline_stages=["blit_2pass"]`가 어휘 안), v1 실측 로그의 분포는 그대로다
  (`run_ts=20260730_235122`의 `p50=32.665` 재현 — 승격 베이스라인이 무효화되지 않았다).
  📌 **독립 검증 6건 반영**(harness-verifier, 2026-07-31): ① `runs/`에 런 디렉토리가 하나도
  없을 때 `--all`이 **0개 회수하고 `ok=true`**를 내던 것과 기본/`--list`가 `IndexError`(문서에
  없는 exit 1, `pull_result.json` 미생성)로 죽던 것을 가드 하나로 닫았다. ② 권한 거부를
  "앱이 아직 측정을 안 끝냈다"로 오진단하던 것을 `ls` returncode/stderr로 갈라 지목한다.
  ③ `pipeline_stages`가 스칼라면 `list()`가 `TypeError`를 내 집계가 죽던 것 → 경고만.
  ④ `_10 < _2` 사전순 때문에 "가장 최근"이 틀리던 것 → 접미사를 숫자로 떼어 정렬.
  ⑤ `schema_version: 2.7`이 `int()`에 깎여 조용히 "일치"가 되던 것 → 타입 오류로 경고.
- **v2.2 (2026-07-31, 팀원2) — 단계 비용 숫자가 arm 없이 돌아다니는 경로를 막는다.**
  스키마 자체(CSV 열 이름·타입)는 그대로라 `SCHEMA_VERSION`은 2를 유지한다.
  발단: `blit_2pass` arm의 `stage_d_ms`는 ② 저조도가 아니라 **단순 복사 패스**인데, 리포트
  한 줄(`stage_d_ms[D칸] p50=1.177 …`)만 보고 D칸에 전사하면 **재지도 않은 칸이 채워진다**
  (독립 검증 지적). **`budget_cell` 매핑은 그대로 둔다** — 틀린 것은 라벨이 아니라 arm 없이
  숫자를 읽는 것이고, 하네스가 arm 의미를 해석하기 시작하면 어휘가 어긋나는 날 조용히 틀린
  라벨이 나온다. 대신 **arm을 숫자 옆에 붙였다**: 리포트의 단계 비용 절 제목과 **각 줄**,
  그리고 `summary.json`의 `stages.render_arm` / `stages.render_arm_known`(§3).
  `render_arm` 어휘를 `lib/frame_log.py`의 `RENDER_ARMS`와 §5 표에 고정했고, 키가 없거나
  어휘 밖이면 **단계 비용 열이 있는 런에 한해** 경고한다(판정·종료 코드는 그대로).
  `skipped_ring_full_frames`가 0이 아니면 경고하고 `stages`에도 싣는다 — 링이 차서 버려지는
  것은 가장 느린 프레임이라 p95/p99가 낙관 쪽으로 치우친다.
  §2에 **"이 숫자를 읽을 때의 조건" 4항**을 박았다(arm 동반 / 열별 차분 미신뢰 — 바꾸지 않은
  패스3이 바꾼 패스2와 같은 폭으로 움직였다 / 링 편향 / `gpu_sum_ms` 하한).
  실기기 확인: `blit_2pass`(집계 `run_ts=20260731_125522`) · `passthrough`(`20260731_125538`) ·
  `render_arm` 없는 v1 로그(`20260731_125545`)에서 각각 arm이 드러나고 죽지 않는다.
  하위호환: v1 로그의 `frametime` 블록이 승격 베이스라인 `20260730_poc_empty_a34.json`과
  **비트 단위로 같다**(`p50=32.703`, `p95=38.071`).
- **v3 (2026-07-31, 팀원2) — `SCHEMA_VERSION` 2 → 3. ② 저조도의 하위 패스별 GPU 시간.**
  S3(② 셰이더 포팅)의 1단계이며 **android보다 먼저** 들어간다 — 앱이 새 열을 먼저 내면
  `KNOWN_COLUMNS`에 없다며 경고만 하고 집계에서 버려져 그날 측정이 숫자 없이 끝난다(§6).
  **CSV에 열이 4개 늘었으므로** 버전을 올린다: `stage_d_analyze_ms` · `stage_d_build_ms` ·
  `stage_d_apply_ms` · `stage_d_denoise_ms`(전부 D칸). 근거: CLAHE는 세 패스이고 `GL_TIME_ELAPSED`는 중첩되지
  않아 **어차피 패스별로 따로 잰다** — 합쳐 내보내면 정보를 버리는 것이고 §2의 "유도값은
  저장하지 않는다"와도 어긋난다. 그리고 "② 단독이 예산 안에 드는가"를 넘겼을 때 다음 질문은
  **"어디가 비싼가"**이고, 그 답에 따라 경량화 레버가 달라진다(결정권은 팀장).
  **D 계열(D-family) 개념 도입**(§2·§3): 파생 시계열 `stage_d_total_ms` = 그 행에 존재하는
  D 계열 열들의 **행별 합**이며, 감마 arm(`stage_d_ms` 하나)이든 다패스 arm(하위 슬롯들)이든
  **D칸이 한 키로 나온다.** `gpu_sum_ms`(모든 GPU 열의 합)와 더하는 대상이 다르므로 표로
  갈라 적었다. `stage_d_ms`와 하위 열이 **동시에** 있는 로그는 모호하므로(이중 계상 위험)
  죽이지 않고 경고하며 **택한 해석("또 다른 하위 패스")과 그 근거를 문장에 명시**한다.
  가드·폐기 계수는 기존 GPU 열과 동일(하한 `> 0`, 상한 없음, 같은 `_collect` 경로).
  판정(`meets_*_target`)·종료 코드는 그대로다 — 단계 비용에는 판정선이 없다.
  **`render_arm` 어휘 확장**: ② arm 7종(`clahe_gamma` 등)을 미리 등록했다(⚠️ 팀원2 명명이지
  계약값이 아니다 — 생산자는 앱이다) + 합성 로그용 `synthetic`. 생성기에 `--render_arm`을
  **명시 인자**로 넣어(기본 `synthetic`) 합성 런마다 뜨던 "arm 미상" 경고를 없앴다 —
  매번 뜨는 경고는 곧 안 보게 되고 정작 중요한 경고가 그 밑에 묻힌다.
  옛 세션 경고 문장은 버전 번호를 손으로 박는 대신 `COLUMN_ADDED_IN`으로 **빠진 열을
  계산해** 낸다(v3 하네스가 "v2에서 늘어난"이라고 말하는 상태를 막는다).
  ⚠️ **앱은 아직 v2다.** android 라운드가 붙기 전까지 실기기 로그에 "앱이 하네스보다
  뒤처졌다" 경고가 뜨는 것은 **정상이고 의도된 순서**이며, 경고 문장이 그 사실을 말한다.
  검증(합성 + 실기기 로그 재집계): 하위 3열 로그에서 `stage_d_total_ms`가 나오고,
  **행별 합을 손 검산**했다(하위 두 열 p50이 각각 1.0인데 합의 p50은 11.0 — 백분위 합 2.0과
  다르다). `stage_d_ms` 단일 열 로그에서 `stage_d_total_ms`는 그 열과 전 통계값이 같다
  (실기기 `gamma_only` 런 재집계: 둘 다 `p50=1.419` / `p95=2.1`). 하위호환은 **하드 요구**라
  v1 로그(`outputs/poc_pull/20260730_224955`)와 v2 로그를 다시 태웠고, v1의 `frametime`
  블록이 승격 베이스라인 2종과 **비트 단위로 같다**(`p50=32.703` / `p95=38.071`,
  repeat는 `p50=32.665` / `p95=38.249`).
  📌 **독립 검증 FAIL 4건 반영**(harness-verifier, 2026-07-31). 넷 다 "숫자는 옳은데 그 숫자에
  붙어 나가는 **문장·비교 판정**이 틀린" 경로였다:
  **① `baseline_diff`의 `CONDITION_KEYS`에 `session.render_arm` 추가.** arm 대리 지표가
  `pipeline_stages` 하나였는데 ② 하위 패스 열에는 토큰이 없어서, `blit_2pass` 런과
  `clahe_gamma` 런이 둘 다 `["blit_2pass"]`로 선언되어 **무경고로 "회귀 없음"**이 나왔다.
  (가드 **강화**이므로 `CONDITION_KEYS` 동결의 예외다. 대가는 위 §5 인용 참고 —
  v1 승격본은 `render_arm=None`이라 `passthrough` 런과 "조건 다름"으로 뜬다. 정직한 표시다.)
  **② `pipeline_stages`가 비었는데 GPU 단계 값이 있으면 모순으로 처리**(§3). 예전에는
  "여기서부터 ①②③④가 더해진다"고 **단언**했는데 ②는 이미 더해져 있고 이미 측정돼 있었다.
  `source.pipeline_stages_contradicted` / `_measured_columns` 추가. 같은 상황에서 꺼져 있던
  "프레임타임 묶임" 단서를 되살렸고(라벨을 "측정된 GPU 단계 열"로 바꿔 낸다),
  **`frametime.pinned_to_camera_supply` 관측치는 이제 분기와 무관하게 항상 기록**한다 —
  문장을 내지 않는 분기에서 관측치 키까지 빠져 기계 소비자가 되물을 근거가 없었다.
  생성기에도 **`--pipeline_stages` 명시 인자**를 넣었다(`--render_arm`과 같은 이유).
  **③ 모호성 근거 서술 정정**(§2). 결론은 그대로고 근거만 정확해졌다 — v2 표가
  `stage_d_ms`를 "패스(들)의 합"으로 정의했으므로 "스키마가 합계 열을 금지한다"는 소급 적용이다.
  **④ 방어 2건**: `_stage_series_order()`가 D 계열 부재 시 `ValueError`로 집계를 죽이던 것,
  `COLUMN_ADDED_IN` ↔ `GPU_TIME_COLUMNS` 자기검사 부재(등록을 빠뜨리면 "뒤처진 앱" 경고가
  그 열을 조용히 빼먹는다) → 상수 불일치는 import에서 죽는다.
  회귀 재확인: v1 로그 2건의 `frametime` 시계열이 승격본과 **여전히 비트 단위로 같다**
  (새 키 `pinned_to_camera_supply`가 **추가**됐을 뿐 기존 값·`verdict`·폐기 0·행 회계 불변).
  📌 **커밋 직전 하위 열 이름 교체 (같은 v3 안에서).** `stage_d_hist_ms`/`stage_d_cdf_ms`는
  **CLAHE 전용 이름**이었다 — Drago(S3-2 1순위)의 **최대휘도 리덕션**이 `stage_d_hist_ms`에
  들어가면 D칸 분해를 읽는 사람이 "히스토그램이 비싸다"고 **오독**하고, LIME의 조도맵 추정은
  아예 담을 이름이 없으며 bilateral(`+bf`)은 슬롯 자체가 없었다. **arm 중립 슬롯 이름 4개**로
  바꿨다: `stage_d_analyze_ms`(통계 산출) · `stage_d_build_ms`(LUT·계수 생성) ·
  `stage_d_apply_ms`(적용, 이름 유지) · **`stage_d_denoise_ms`(노이즈 억제, 신규)**.
  arm별 구체적 의미는 **`render.passes[].gpu_column`이 선언**하고(앱이 이미 그렇게 쓴다),
  슬롯보다 패스가 많으면 **앱이 합치지 말고 올린다**(합치면 유도값이다) → §2.
  **`SCHEMA_VERSION`은 3을 유지한다** — v3 열을 쓴 로그가 실측에 **하나도 없어서**(생산자인
  앱은 아직 v2이고, 그 열이 있는 로그는 이 라운드에서 만든 합성뿐이다) 깨질 하위호환이
  존재하지 않는다. 버전은 "옛 로그를 어떻게 읽을지"를 가르는 장치이므로, 읽을 옛 로그가
  없는 이름 변경은 버전을 올릴 호환성 사건이 아니다. 옛 이름이 든 CSV를 태우면 **미지 열
  경고가 그 이름을 지목**한다(실행 확인). 이름을 바꿀 수 있는 가장 싼 순간이 지금이었다.
