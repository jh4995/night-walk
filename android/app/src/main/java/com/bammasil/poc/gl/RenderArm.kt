package com.bammasil.poc.gl

/**
 * 렌더 경로 arm — **측정 조건을 가르는 축**이다.
 *
 * [pipelineStages]는 `session.json`의 `pipeline_stages`로 그대로 나가고,
 * 그 키는 `scripts/baseline_diff.py`의 **비교 조건**이다. arm이 다르면 하네스가 자동으로
 * "조건 다름"을 낸다 — 그게 원하는 동작이다. 여기 문자열을 바꾸면 과거 런과의 비교가 끊긴다.
 *
 * ⚠ [PASSTHROUGH]는 **승격 베이스라인 재현용**이다. 이 arm은 FBO도 만들지 않고 기존 1패스를
 * 그대로 탄다(`PassthroughRenderer.drawPassthrough`). 여기에 새 GL 호출을 끼워 넣으면
 * `docs/baselines/`의 기존 숫자와 비교할 근거가 사라진다.
 */
enum class RenderArm(
    val id: String,
    val pipelineStages: List<String>,
    /**
     * 이 arm이 CSV에 싣는 GPU 패스 시간 열 — **패스 순서 그대로**다.
     *
     * 이름은 `lib/frame_log.py`의 `GPU_TIME_COLUMNS`와 **글자까지** 같아야 하고, 개수가 곧
     * 이 arm의 계측 패스 수다([GpuTimerRing]의 query 개수·[FrameLogRecorder]의 stride가
     * 여기서 나온다). 재지 않는 열은 넣지 않는다(`FrameLogRecorder.CSV_HEADER` 주석).
     *
     * ⚠ **하위 패스를 임의로 합쳐 한 열에 넣지 않는다**(`docs/FRAME_LOG_SCHEMA.md` §2).
     * 합치면 그건 유도값이고, 어느 패스가 비싼지가 사라진다.
     */
    val gpuColumns: List<String>,
) {

    /** 기존 경로 그대로. OES → 화면 1패스. 처리 0. */
    PASSTHROUGH("passthrough", emptyList(), emptyList()),

    /** 3패스 골격을 다 돌되 ② 자리는 단순 복사. 골격 자체의 비용을 본다. */
    BLIT_2PASS(
        "blit_2pass",
        listOf("blit_2pass"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    /** ② 자리에 감마만. **② 비용의 하한**이며 알고리즘이 아니다. */
    GAMMA_ONLY(
        "gamma_only",
        listOf("blit_2pass", "stage2_gamma"),
        // ⚠ 목록을 상수로 뽑아 공유하지 않는다. enum 상수는 companion보다 먼저 초기화되므로
        //   companion의 val을 인자로 쓰면 초기화 순서 함정에 걸린다.
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    /**
     * ② 자리에 **Drago 톤매핑**(상류 `scripts/lowlight.py`의 `D1`). ② 자리가 셰이더 하나로
     * 끝나지 않는 첫 arm이다 — 전역 통계가 필요해 리덕션 → 계수 → 적용 3단으로 쪼갠다.
     *
     * `stage2_drago` 토큰은 **앱이 먼저 냈고**, 하네스 어휘(`lib/frame_log.py`의
     * `PIPELINE_STAGES`)에 뒤이어 등록됐다 — 생산자가 앱이라 그 순서가 정상이다.
     */
    DRAGO(
        "drago",
        listOf("blit_2pass", "stage2_drago"),
        listOf(
            "stage_b_ms",
            "stage_d_analyze_ms",
            "stage_d_build_ms",
            "stage_d_apply_ms",
            "gpu_present_ms",
        ),
    ),

    /**
     * ② 자리에 **CLAHE + 감마**(상류 `scripts/lowlight.py`의 `A1`). 타일별 히스토그램이
     * 필요해 [DRAGO]와 같은 3단이지만 성격이 다르다 — Drago는 **전역** 통계 하나이고
     * 이쪽은 **타일별** 통계 64벌이다(그래서 화면 효과도 국소 대비다).
     *
     * `stage2_clahe` 토큰은 **앱이 먼저 냈고**, 하네스 어휘(`lib/frame_log.py`의
     * `PIPELINE_STAGES`)에 뒤이어 등록됐다 — 생산자가 앱이라 그 순서가 정상이다
     * (`stage2_drago`·`stage2_agcwd`·`stage2_bilateral`도 같은 순서로 등록됐다).
     * **지금은 등록이 끝나 "어휘 밖" 경고가 뜨지 않는다.**
     */
    CLAHE_GAMMA(
        "clahe_gamma",
        listOf("blit_2pass", "stage2_clahe"),
        listOf(
            "stage_b_ms",
            "stage_d_analyze_ms",
            "stage_d_build_ms",
            "stage_d_apply_ms",
            "gpu_present_ms",
        ),
    ),

    /**
     * ② 자리에 **AGCWD**(상류 `A2`, Huang 2013). 전역 히스토그램 하나 → 1D LUT.
     * `stage2_agcwd` 토큰도 [CLAHE_GAMMA]와 같이 앱이 먼저 낸다.
     */
    AGCWD(
        "agcwd",
        listOf("blit_2pass", "stage2_agcwd"),
        listOf(
            "stage_b_ms",
            "stage_d_analyze_ms",
            "stage_d_build_ms",
            "stage_d_apply_ms",
            "gpu_present_ms",
        ),
    ),

    /**
     * ② 자리에 **Drago → CLAHE 직렬**(상류 조합 `D1A1`). 단품 arm이 아니라 **조합**이고,
     * 이번 라운드는 **체인**이다 — 상류 cv2 파이프라인이 Drago 출력을 8비트 이미지로 내고
     * 그것을 `cvtColor(BGR2LAB)`에 넣는 구조를 그대로 옮긴다(중간 표현이 RGBA8 FBO다).
     * 중간 materialize를 없애는 **융합**은 알고리즘 변경이며, 별 arm([DRAGO_CLAHE_FUSED])으로
     * 이미 있고 실측도 끝났다 — **채택 여부가 팀장 판단 영역**이다.
     *
     * 왜 이 arm을 재는가: 상류는 CPU 720p 101.2ms를 근거로 내부 처리 해상도를 640×360으로
     * 깎는 것을 검토 중인데, 상류 실측상 **글레어 지표가 해상도에 크게 의존한다**
     * (`docs/research/RESEARCH_20260803_UPSTREAM.md` §6). GPU에서 720p 조합이 게이트 안이면
     * 그 레버를 당길 이유가 사라진다.
     *
     * ⚠ **패스 8개다.** [GpuTimerRing.MAX_PASS_COUNT]가 8이던 시절에는 이 arm 하나가 슬롯을
     * 정확히 다 써서 여유가 0이었고, 그래서 `bf`를 얹으면 그 arm의 GPU 계측이 통째로 꺼졌다 —
     * 그 상수를 **12로 올려** [DRAGO_CLAHE_CHAIN_BF](9패스)를 재게 했다. 이 arm 자체의 패스
     * 수와 열 구성은 그대로이므로 과거 승격본과 비교 조건이 같다.
     *
     * 세 토큰(`blit_2pass` · `stage2_drago` · `stage2_clahe`)은 전부 **이미 있는 것**이다 —
     * 조합이라고 새 토큰을 만들지 않는다. 뒤 3개 열(`*2_ms`)은 하네스 스키마 v4에서 들어온
     * "그 arm의 **두 번째** 톤커브 스테이지의 같은 역할 슬롯"이다.
     */
    DRAGO_CLAHE_CHAIN(
        "drago_clahe_chain",
        listOf("blit_2pass", "stage2_drago", "stage2_clahe"),
        listOf(
            "stage_b_ms",
            "stage_d_analyze_ms",
            "stage_d_build_ms",
            "stage_d_apply_ms",
            "stage_d_analyze2_ms",
            "stage_d_build2_ms",
            "stage_d_apply2_ms",
            "gpu_present_ms",
        ),
    ),

    /**
     * ② 자리에 **Drago ⊕ CLAHE 융합**. [DRAGO_CLAHE_CHAIN]에서 중간 RGBA8 materialize를
     * 없애고 Drago 톤맵을 CLAHE의 두 패스에 **인라인**한 것이다 — 7패스다.
     *
     * 🔴 **이식 최적화가 아니라 알고리즘 변경이다.** 중간 8비트 왕복과 `pow` 인코드/디코드
     * 왕복이 사라지므로 **상류와 다른 곡선이 된다** → [FUSED_DEVIATION]. 채택 여부는
     * 팀장/팀 판단이다.
     *
     * ⚠ 열 순서는 **패스 순서 그대로**라 `stage_d_apply_ms`가 `*2_ms` 뒤에 온다 —
     * 적용이 하나로 접혔기 때문이다. `stage_d_apply2_ms`는 **쓰지 않는다**(재지 않은 열을
     * 싣지 않는다는 규약 그대로).
     *
     * `pipelineStages`는 체인과 **같다** — 융합이라고 새 토큰을 만들지 않는다. 무엇이
     * 달라졌는지는 `session.json`의 `render_arm`과 `stage2_params`가 말한다.
     */
    DRAGO_CLAHE_FUSED(
        "drago_clahe_fused",
        listOf("blit_2pass", "stage2_drago", "stage2_clahe"),
        listOf(
            "stage_b_ms",
            "stage_d_analyze_ms",
            "stage_d_build_ms",
            "stage_d_analyze2_ms",
            "stage_d_build2_ms",
            "stage_d_apply_ms",
            "gpu_present_ms",
        ),
    ),

    /**
     * ② 자리에 **Drago → CLAHE → bilateral 직렬**(상류 조합 `D1A1+bf`). [DRAGO_CLAHE_CHAIN]에
     * bf 한 패스를 더한 **9패스**다.
     *
     * 🔴 **이제야 상류 잠정 1위와 구성이 같은 arm이 생겼다** — 다만 `+ts`는 여전히 없다
     * (`INTERFACES.md` §B-4가 ☐다). 무엇을 비교할 수 있고 무엇을 못 하는지는
     * [BF_HOW_TO_COMPARE]에 있다.
     *
     * 앞 7패스는 [DRAGO_CLAHE_CHAIN]과 **글자 그대로 같은 GL 호출**이다(같은 프로그램·같은
     * SSBO를 쓴다) — 그래야 두 arm의 차분이 곧 bf 한 패스의 비용이 된다.
     *
     * `stage2_bilateral` 토큰은 하네스 어휘(`lib/frame_log.py`의 `STAGE2_BILATERAL`)에 이미
     * 있다. `stage_d_denoise_ms` 열도 스키마 v3에서 들어와 있었고 **이 arm이 처음 채운다.**
     */
    DRAGO_CLAHE_CHAIN_BF(
        "drago_clahe_chain_bf",
        listOf("blit_2pass", "stage2_drago", "stage2_clahe", "stage2_bilateral"),
        listOf(
            "stage_b_ms",
            "stage_d_analyze_ms",
            "stage_d_build_ms",
            "stage_d_apply_ms",
            "stage_d_analyze2_ms",
            "stage_d_build2_ms",
            "stage_d_apply2_ms",
            "stage_d_denoise_ms",
            "gpu_present_ms",
        ),
    ),

    /**
     * ② 자리에 **Drago ⊕ CLAHE 융합 → bilateral**. [DRAGO_CLAHE_FUSED]에 bf 한 패스를 더한
     * **8패스**다.
     *
     * 🔴 융합 자체가 **알고리즘 변경**이므로([FUSED_DEVIATION]) 이 arm도 상류 CPU 숫자 옆에
     * 놓을 수 없다. 이 arm의 뜻은 [DRAGO_CLAHE_CHAIN_BF] **대비 절감**이다.
     *
     * `pipelineStages`는 체인+bf와 **같다** — 융합이라고 새 토큰을 만들지 않는다.
     */
    DRAGO_CLAHE_FUSED_BF(
        "drago_clahe_fused_bf",
        listOf("blit_2pass", "stage2_drago", "stage2_clahe", "stage2_bilateral"),
        listOf(
            "stage_b_ms",
            "stage_d_analyze_ms",
            "stage_d_build_ms",
            "stage_d_analyze2_ms",
            "stage_d_build2_ms",
            "stage_d_apply_ms",
            "stage_d_denoise_ms",
            "gpu_present_ms",
        ),
    ),

    /**
     * ④ **선택적 강조 오버레이**. 3패스 골격의 ② 자리는 단순 복사이고, 그 뒤에 오버레이
     * 패스가 하나 붙는 **4패스**다 — 재는 것은 `stage_i_ms`(버짓 I칸) 하나다.
     *
     * ```
     * 패스1  OES   → FBO_A            stage_b_ms
     * 패스2  FBO_A → FBO_B (복사)     stage_d_ms
     * 패스3  FBO_B에 오버레이 덧그림   stage_i_ms
     * 패스4  FBO_B → 화면             gpu_present_ms
     * ```
     * 명세는 상류 `scripts/emphasize.py`에서 확정된 것이다([HighlightOverlay] 참고) —
     * 임의 더미의 비용 봉투가 아니라 **실제 사양**으로 잰다.
     *
     * ⚠ 박스 **개수**는 사양이 아니라 우리가 선언한 측정 조건이다([HIGHLIGHT_BOX_PROVENANCE]).
     * `session.json`의 `overlay.box_count`에 반드시 실린다.
     */
    HIGHLIGHT_BOXES(
        "highlight_boxes",
        listOf("blit_2pass", "stage4_highlight"),
        listOf("stage_b_ms", "stage_d_ms", "stage_i_ms", "gpu_present_ms"),
    ),

    /**
     * [HIGHLIGHT_BOXES]와 **박스 개수만** 다른 arm(개당 비용 기울기용).
     *
     * 왜 개수를 arm id로 갈랐는가: `scripts/baseline_diff.py`의 비교 조건에는 박스 개수를 담을
     * 키가 없어서(`pipeline_stages`가 둘 다 같다) 같은 id로 개수만 바꾸면 **조건 차이가
     * 무경고로 통과한다.** 같은 이유로 하네스도 이 id를 따로 등록해 두었다
     * (`lib/frame_log.py`의 `RENDER_ARM_HIGHLIGHT_BOXES_STRESS`).
     */
    HIGHLIGHT_BOXES_STRESS(
        "highlight_boxes_stress",
        listOf("blit_2pass", "stage4_highlight"),
        listOf("stage_b_ms", "stage_d_ms", "stage_i_ms", "gpu_present_ms"),
    ),

    // ── 프레임 단일 query arm(`*_1q`) ──────────────────────────────────────
    // 🔴 **알고리즘이 아니라 계측 방식이 다른 arm이다**([SINGLE_QUERY_WHAT_DIFFERS]).
    // 렌더 경로는 짝 arm과 **글자 그대로 같다** — `PassthroughRenderer.dispatchDraw`가
    // 짝과 같은 draw 함수로 보내고(uses* 판별식에 이 arm들을 함께 넣었다), 그래야 두 계측의
    // 차분이 곧 패스별 계측의 중복 계상량이 된다. 렌더가 다르면 그 차분은 아무 뜻이 없다.
    //
    // ⚠ 목록에 **뒤에** 붙인다. 스피너는 entries 순서라 중간에 끼우면 기존 arm의 위치가
    //   전부 밀린다(측정자가 손으로 고르는 UI다).
    // ⚠ 열 이름을 companion의 상수로 쓰지 않는다 — enum 상수가 companion보다 먼저
    //   초기화되므로 여기서 companion의 val을 인자로 쓰면 초기화 순서 함정에 걸린다
    //   ([GAMMA_ONLY]의 같은 주석). 값의 대조는 [SINGLE_FRAME_QUERY_COLUMN]이 맡는다.

    /**
     * [BLIT_2PASS]와 **렌더가 같고 계측 방식만 다른** arm. 3패스 골격을 그대로 돌되
     * timer query를 패스마다 걸지 않고 **프레임 전체에 하나만** 건다([GpuTimerRing]의
     * 프레임 단일 query 모드).
     *
     * 열은 `gpu_frame_ms` **1개**이고 렌더 패스는 **3개**다 — 이 arm에서 둘은 1:1이
     * 아니며, 패스 수는 [renderPassCount]가 따로 낸다.
     *
     * 이 arm의 뜻과 읽는 법은 [SINGLE_QUERY_WHAT_DIFFERS] · [SINGLE_QUERY_NOT_A_SUM] ·
     * [SINGLE_QUERY_HOW_TO_COMPARE] · [SINGLE_QUERY_LOWER_BOUND_NOTE]에 있고 같은 문장이
     * `session.json`으로 나간다.
     */
    BLIT_2PASS_1Q(
        "blit_2pass_1q",
        // 짝(blit_2pass)과 **같은 목록**이다. 두 arm을 가르는 것은 render_arm 문자열뿐이다.
        listOf("blit_2pass"),
        listOf("gpu_frame_ms"),
    ),

    /** [DRAGO_CLAHE_CHAIN]의 프레임 단일 query 판. 렌더 8패스, 열 1개. */
    DRAGO_CLAHE_CHAIN_1Q(
        "drago_clahe_chain_1q",
        listOf("blit_2pass", "stage2_drago", "stage2_clahe"),
        listOf("gpu_frame_ms"),
    ),

    /**
     * [DRAGO_CLAHE_CHAIN_BF]의 프레임 단일 query 판. 렌더 9패스, 열 1개.
     *
     * 🔴 **이 arm이 이번 라운드의 본진이다.** 패스별 계측에서 `gpu_sum`이 물리적으로 불가능한
     * 값을 낸 arm이 짝(`drago_clahe_chain_bf`)이다 — [SINGLE_QUERY_WHAT_DIFFERS] 참고.
     */
    DRAGO_CLAHE_CHAIN_BF_1Q(
        "drago_clahe_chain_bf_1q",
        listOf("blit_2pass", "stage2_drago", "stage2_clahe", "stage2_bilateral"),
        listOf("gpu_frame_ms"),
    ),

    // ── ③ 탐지 arm ────────────────────────────────────────────────────────
    // 🔴 **[gpuColumns]는 일곱 arm 모두 짝 arm [BLIT_2PASS]와 글자 그대로 같다** — 탐지는
    // 별 스레드에서 돌고 렌더 경로를 하나도 건드리지 않으므로, 렌더가 같아야 표시 경로
    // 차분이 "탐지를 켜서 생긴 변화"라는 뜻을 갖는다(`_1q` arm과 같은 관행).
    //
    // 🔴 [pipelineStages]는 **다르다.** 프레임 경로에서 실제로 추론이 도는 arm 여섯에만
    // `detect` 토큰을 더했다. [DETECT_BIND_ONLY]는 `ImageAnalysis`만 붙이고 추론이 없으므로
    // `["blit_2pass"]` 그대로다 — 그 토큰의 뜻은 "그 단계가 프레임 경로에서 돌았는가"이고,
    // 분모 arm에서는 돌지 않았다. 이 문자열은 `baseline_diff.py`의 **비교 조건**이라
    // ③ 세션 라운드(커밋 46d1870)의 런과는 여기서 조건이 갈린다 — **그게 맞다.** 그 런들의
    // 프레임타임에는 탐지가 들어 있지 않고 이 런들에는 들어 있다.
    //
    // ⚠ 목록에 **뒤에** 붙인다. 스피너는 entries 순서라 중간에 끼우면 기존 arm의 위치가
    //   전부 밀린다(측정자가 손으로 고르는 UI다).

    /**
     * ③의 **분모**. `ImageAnalysis`를 바인딩만 하고 추론은 돌리지 않는다 — 이 arm과 짝 arm
     * ([BLIT_2PASS])의 차이가 "use case를 하나 더 붙인 값"이고 그 **위의** 차이가 추론 비용이다.
     *
     * 이 arm은 ORT 세션도 열지 않고([usesDetectSession]이 false다) `detect.csv`도 내지 않는다
     * (`detect.enabled=false`). 재는 것은 **분석 use case 하나의 비용**뿐이다.
     */
    DETECT_BIND_ONLY(
        "detect_bind_only",
        listOf("blit_2pass"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    /** ORT **CPU EP**로 세션을 열고 프레임 경로에서 추론을 돌린다. */
    DETECT_CPU(
        "detect_cpu",
        listOf("blit_2pass", "detect"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    /**
     * ORT **NNAPI EP**로 세션을 연다.
     *
     * 🔴 **요청과 실제가 다를 수 있다.** NNAPI를 요청했는데 CPU로 떨어지는 것은 흔하고,
     * 그것을 모르면 "MediaTek에서 NNAPI가 된다"는 틀린 결론이 나온다 — `session.json`의
     * `detect.ep.requested`/`resolved`를 둘 다 내는 이유다.
     */
    DETECT_NNAPI(
        "detect_nnapi",
        listOf("blit_2pass", "detect"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    /** [DETECT_CPU] + **ORT 프로파일러**. 🔴 시간 인용 금지 ([DETECT_PROF_NOT_QUOTABLE]). */
    DETECT_CPU_PROF(
        "detect_cpu_prof",
        listOf("blit_2pass", "detect"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    /** [DETECT_NNAPI] + **ORT 프로파일러**. 🔴 시간 인용 금지 ([DETECT_PROF_NOT_QUOTABLE]). */
    DETECT_NNAPI_PROF(
        "detect_nnapi_prof",
        listOf("blit_2pass", "detect"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    /**
     * ORT **XNNPACK EP**로 세션을 연다.
     *
     * 왜 이 arm이 생겼는가: 교차 배치 5런 실측에서 **NNAPI가 CPU보다 느렸다**(F p50 기준).
     * 남은 후보가 이것이고, `getAvailableProviders()`가 이 AAR에 XNNPACK이 실제로 들어 있음을
     * 이미 보여 줬다.
     *
     * 🔴 **요청과 실제가 다를 수 있다**([DETECT_NNAPI]와 같다). ⚠ 게다가 XNNPACK은 **CPU EP의
     * 커널을 일부만 대체**하므로 노드가 CPU/XNNPACK로 **섞여** 나오는 것이 정상이다 —
     * 그 모양은 `session.json`의 `detect.ep.node_counts`가 그대로 드러낸다.
     */
    DETECT_XNNPACK(
        "detect_xnnpack",
        listOf("blit_2pass", "detect"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    /** [DETECT_XNNPACK] + **ORT 프로파일러**. 🔴 시간 인용 금지 ([DETECT_PROF_NOT_QUOTABLE]). */
    DETECT_XNNPACK_PROF(
        "detect_xnnpack_prof",
        listOf("blit_2pass", "detect"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    // ── 하한이 없던 세 계열의 프레임 단일 query 짝(`*_1q`) ─────────────────
    // 🔴 **왜 이 셋이 더 필요한가:** 이 블록을 쓴 시점에 단일 query 짝을 실제로 잰 것은
    // [BLIT_2PASS] · [DRAGO_CLAHE_CHAIN] · [DRAGO_CLAHE_CHAIN_BF] 셋뿐이라,
    // [DRAGO_CLAHE_FUSED] · [DRAGO_CLAHE_FUSED_BF] · [HIGHLIGHT_BOXES] 계열에는 **패스별
    // 계측의 상한만 있고 하한이 없었다**(`docs/STATUS.md` 알려진 이슈 22).
    //
    // ⚠ **그 뒤로 짝이 더 생겼다** — ③→④ 세트와 ②③④ 통합 세트가 뒤에 붙어 지금은 짝이
    //   열 쌍이다. **지금 짝이 있는 arm의 유일한 출처는 [singleFrameQueryPeer]이고**, 사람이
    //   읽는 전수 목록은 [SINGLE_QUERY_HOW_TO_COMPARE]가 낸다 — 이 머리말에 목록을 두 번째로
    //   적지 않는다(적으면 arm을 더하는 날 한쪽만 고쳐진다).
    //
    // ⚠ **부풀림 비율을 다른 arm에서 옮겨 보정할 수 없다.** 중복 계상량은 마지막 전체화면
    //   패스의 비용을 따라가므로 패스 구성마다 다르다 — 같은 라운드에서 ④ 오버레이 arm은
    //   +2%였고 9패스 arm은 +43%였다([SINGLE_QUERY_WHAT_DIFFERS]). 한 arm의 비율을 다른
    //   arm에 곱하면 그건 측정이 아니라 추정이고, 그 추정이 버짓 칸으로 들어간다.
    //   그러므로 하한은 **그 arm에서 직접 재는 수밖에 없고**, 그러려면 arm id가 있어야 한다.
    //
    // 나머지 규약은 위 `_1q` 블록과 같다 — 렌더 경로는 짝과 글자 그대로 같고(uses* 판별식에
    // 함께 넣었다), 열은 `gpu_frame_ms` 하나이며, 목록 **뒤에** 붙인다(스피너가 entries
    // 순서라 중간에 끼우면 측정자가 고르던 위치가 전부 밀린다).

    /**
     * [DRAGO_CLAHE_FUSED]의 프레임 단일 query 판. 렌더 7패스, 열 1개.
     *
     * 이 짝이 있어야 융합의 `gpu_sum`을 체인의 것과 **같은 자격으로** 비교할 수 있다 —
     * 지금은 체인 쪽만 하한이 있어 두 arm의 차분이 "융합의 절감"인지 "중복 계상량의 차이"인지
     * 가릴 수 없다([FUSED_HOW_TO_COMPARE]가 그 차분을 이 arm의 유일한 뜻으로 못 박았다).
     */
    DRAGO_CLAHE_FUSED_1Q(
        "drago_clahe_fused_1q",
        // 짝(drago_clahe_fused)과 **같은 목록**이다. 두 arm을 가르는 것은 render_arm 문자열뿐이다.
        listOf("blit_2pass", "stage2_drago", "stage2_clahe"),
        listOf("gpu_frame_ms"),
    ),

    /** [DRAGO_CLAHE_FUSED_BF]의 프레임 단일 query 판. 렌더 8패스, 열 1개. */
    DRAGO_CLAHE_FUSED_BF_1Q(
        "drago_clahe_fused_bf_1q",
        listOf("blit_2pass", "stage2_drago", "stage2_clahe", "stage2_bilateral"),
        listOf("gpu_frame_ms"),
    ),

    /**
     * [HIGHLIGHT_BOXES]의 프레임 단일 query 판. 렌더 4패스, 열 1개.
     *
     * 🔴 **박스 개수가 짝과 같아야 한다**([highlightBoxCount]에 이 arm이 들어 있다). 빠뜨리면
     * 박스를 0개 그려 렌더가 짝과 달라지고, 그러면 두 계측의 차분이 아무 뜻이 없어진다 —
     * 이 arm 계열이 재려는 것은 **같은 그림을 두 방식으로 잰 차이**뿐이다.
     */
    HIGHLIGHT_BOXES_1Q(
        "highlight_boxes_1q",
        listOf("blit_2pass", "stage4_highlight"),
        listOf("gpu_frame_ms"),
    ),

    // ── ③ 이식 정확성 대조 arm(`detect_parity_*`) ──────────────────────────
    // 🔴 **재는 arm이 아니라 대조하는 arm이다.** 추론 경로·모델·전처리는 짝
    // ([DETECT_CPU]/[DETECT_NNAPI]/[DETECT_XNNPACK])과 글자 그대로 같고, 다른 것은 **샘플
    // K개의 텐서를 파일로 덤프하는가** 하나뿐이다 — `_prof` 접미사와 같은 취지로 arm을 갈랐다
    // (계측 방식이 다르면 같은 코드라도 같은 조건이 아니고, 그 사실을 담을 키가
    // `pipeline_stages`에는 없다). 🔴 시간 인용 금지 ([DETECT_PARITY_NOT_QUOTABLE]).
    //
    // 무엇에 답하는가: `metadata.json`의 `parity_check`는 **상류가 PC에서 PyTorch 대비** 확인한
    // 것이고, **폰 ORT 출력이 그것과 같은지는 별개 문제다.** 그 질문에 답할 수단이 지금까지
    // 없어 "미검증"이라고 쓰기만 했다 — 이 arm이 그 수단이다. 포맷 규약과 3분할(E/F/G) 대조법은
    // `docs/plans/20260806_detect_parity_dump_format.md`에 있고 소비자는 `scripts/detect_parity.py`다.
    //
    // 🔴 **왜 EP 셋 다인가:** NNAPI가 GPU로 내려가는 것을 이미 실측했다(`gpu_busy` 2.2%→43%).
    // GPU 경로는 **fp16으로 떨어질 수 있고** 그러면 같은 모델·같은 입력인데 **답이 달라진다.**
    // 그건 성능 문제가 아니라 **안전 문제**라 EP별로 봐야 하고, 폰 EP끼리도 서로 대조한다.

    /** [DETECT_CPU]의 덤프 판. 🔴 시간 인용 금지 ([DETECT_PARITY_NOT_QUOTABLE]). */
    DETECT_PARITY_CPU(
        "detect_parity_cpu",
        listOf("blit_2pass", "detect"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    /** [DETECT_NNAPI]의 덤프 판. 🔴 시간 인용 금지 ([DETECT_PARITY_NOT_QUOTABLE]). */
    DETECT_PARITY_NNAPI(
        "detect_parity_nnapi",
        listOf("blit_2pass", "detect"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    /** [DETECT_XNNPACK]의 덤프 판. 🔴 시간 인용 금지 ([DETECT_PARITY_NOT_QUOTABLE]). */
    DETECT_PARITY_XNNPACK(
        "detect_parity_xnnpack",
        listOf("blit_2pass", "detect"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    // ── ③ 회전 대조군(`detect_cpu_norot`) ──────────────────────────────────
    // 🔴 **회전 여부 하나만 다른 짝 arm이다.** 규약 §4-1의 `rotation_site = "none"`이고,
    // §4-2 표의 🟢 행 — `rotation_applied=false`이지만 그 뜻은 **"아직 구현하지 않았다"
    // (알려진 이슈 29)가 아니라 "회전 전 E를 같은 세션에서 재기 위한 의도된 대조군"**이다.
    // 두 뜻을 가르지 않으면 이 arm의 매니페스트가 이슈 29 시절 덤프와 구분되지 않는다.
    //
    // 🔴 **[DETECT_CPU]와 글자 그대로 같아야 한다** — 같은 EP·같은 세션 옵션·같은
    // [pipelineStages]·같은 [gpuColumns]이고, 전처리도 **같은 함수에 회전각 0을 넣어**
    // 태운다(별도 코드 경로를 만들지 않았다. 만들면 차분이 "회전 여부"가 아니라 "루프
    // 모양"까지 섞는다). 갈리는 것은 `RenderArm.appliesDetectRotation` 하나뿐이다.
    //
    // ⚠ 목록 **맨 뒤**에 붙인다. 스피너는 entries 순서라 중간에 끼우면 측정자가 손으로
    //   고르던 기존 arm의 위치가 전부 밀린다(`_1q` 셋을 뒤에 붙인 것과 같은 이유다).

    /**
     * [DETECT_CPU]의 **회전 미적용** 짝. E의 회전 전 기준선을 같은 세션에서 잡는 자리다.
     *
     * 🔴 **이 arm의 boxes_out·max_conf를 탐지 품질로 읽으면 안 된다** — 모델이 옆으로
     * 누운 장면을 본다. 이 arm이 답하는 것은 **"회전이 E에 얼마를 더하는가"** 하나다.
     */
    DETECT_CPU_NOROT(
        "detect_cpu_norot",
        listOf("blit_2pass", "detect"),
        listOf("stage_b_ms", "stage_d_ms", "gpu_present_ms"),
    ),

    // ── ③→④ 연결 arm 3개 (스키마 v7) ─────────────────────────────────────
    // 🔴 **셋이 한 세트다.** 셋을 같은 세션에서 재야 I칸의 상한·하한이 둘 다 나온다
    // (`lib/frame_log.py`의 같은 블록이 이 세트를 이미 등록해 두었다):
    //
    //   detect_cpu_highlight     4패스 오버레이 + 패스별 query + stage_h_ms + overlay_boxes
    //                            → I 상한 · H. **본진**이다. 상한의 분모는 기존 detect_cpu다.
    //   detect_cpu_highlight_1q  **위와 글자 그대로 같은 렌더**, 프레임 단일 query
    //   detect_cpu_1q            3패스(오버레이 없음), 프레임 단일 query
    //                            → 🔴 **하한의 분모**
    //
    // 🔴 **왜 `detect_cpu_1q`가 따로 필요한가:** 하한은 "같은 계측 방식의 두 arm 차"로만 낼 수
    //   있는데 지금 있는 단일 query 분모는 [BLIT_2PASS_1Q]뿐이고 **거기에는 탐지 부하가 없다.**
    //   알려진 이슈 36이 그 부류다 — `highlight_boxes_1q`가 분모와 소수점 셋째 자리까지 같아
    //   I 하한이 0으로 나왔고, 그 0은 "오버레이가 공짜"가 아니라 **분모가 상한을 통째로 중복
    //   계상했다**는 뜻이었다. 그러므로 하한은 `detect_cpu_highlight_1q − detect_cpu_1q`뿐이다.
    //
    // 🔴 [pipelineStages]에 **`stage4_smoothing`이 붙는다** — H칸(좌표 평활·hold)이 실제로
    //   도는 arm이라는 뜻이고, 그래서 오버레이 arm 둘은 `highlight_boxes`와도 조건이 갈린다
    //   (그쪽은 정적 더미 박스에 평활이 없다). `detect_cpu_1q`는 오버레이가 없으므로 그 토큰도
    //   `stage4_highlight`도 없다.
    //
    // ⚠ 목록 **맨 뒤**에 붙인다. 스피너는 entries 순서라 중간에 끼우면 측정자가 손으로 고르던
    //   기존 arm의 위치가 전부 밀린다(`_1q` 셋·회전 대조군을 뒤에 붙인 것과 같은 이유다).

    /**
     * 🔴 **③ 탐지 결과를 ④ 오버레이가 실제로 그리는 첫 arm.** 렌더 4패스이며 [HIGHLIGHT_BOXES]와
     * **같은 골격**이지만 박스가 정적 더미가 아니라 **그 프레임에 게시돼 있는 탐지 결과**다.
     *
     * 재는 것 셋: `stage_i_ms`(I 상한) · `stage_h_ms`(H, **CPU 벽시계**) ·
     * `overlay_boxes`(그 프레임에 실제로 그린 수).
     *
     * 🔴 **박스 개수가 프레임마다 다르다** → [highlightBoxCount]가 [HIGHLIGHT_BOX_COUNT_DYNAMIC]
     * 이고 `session.json`의 `overlay.box_count`는 null이다([OVERLAY_DYNAMIC_BOX_NOTE]).
     * 개수 조건은 `frames.csv`의 `overlay_boxes` 열이 프레임별로 말한다.
     */
    DETECT_CPU_HIGHLIGHT(
        "detect_cpu_highlight",
        listOf("blit_2pass", "detect", "stage4_highlight", "stage4_smoothing"),
        listOf("stage_b_ms", "stage_d_ms", "stage_i_ms", "gpu_present_ms"),
    ),

    /**
     * [DETECT_CPU_HIGHLIGHT]의 프레임 단일 query 판. 렌더 4패스, 열 1개.
     * 🔴 **렌더는 짝과 글자 그대로 같다** — 평활·hold도 같은 자리에서 같은 값으로 돈다.
     */
    DETECT_CPU_HIGHLIGHT_1Q(
        "detect_cpu_highlight_1q",
        listOf("blit_2pass", "detect", "stage4_highlight", "stage4_smoothing"),
        listOf("gpu_frame_ms"),
    ),

    /**
     * 🔴 **I 하한의 분모.** [DETECT_CPU]와 렌더가 글자 그대로 같은 3패스이고(오버레이가 없다)
     * 계측만 프레임 단일 query다. 탐지는 돈다 — 그게 [BLIT_2PASS_1Q]를 분모로 쓸 수 없는
     * 이유다(위 블록 참고).
     */
    DETECT_CPU_1Q(
        "detect_cpu_1q",
        listOf("blit_2pass", "detect"),
        listOf("gpu_frame_ms"),
    ),

    // ── ②③④ 통합 arm (스키마 v7) ─────────────────────────────────────
    // ⚠ 목록 **맨 뒤**에 붙인다. 스피너는 entries 순서라 중간에 끼우면 측정자가 손으로
    //   고르던 기존 arm의 위치가 전부 밀린다(`_1q` 셋·회전 대조군·③→④ 세트를 뒤에 붙인
    //   것과 같은 이유다).

    /**
     * 🔴 **② 체인 + ③ 탐지 + ④ 오버레이가 한 프레임에서 다 도는 첫 arm.** 9패스다:
     * ```
     * 패스1  OES   → FBO_A                     stage_b_ms
     * 패스2  drago analyze (FBO_A)             stage_d_analyze_ms
     * 패스3  drago build                       stage_d_build_ms
     * 패스4  drago apply   FBO_A → FBO_B       stage_d_apply_ms
     * 패스5  clahe analyze (FBO_B)             stage_d_analyze2_ms
     * 패스6  clahe build                       stage_d_build2_ms
     * 패스7  clahe apply   FBO_B → FBO_A       stage_d_apply2_ms
     * 패스8  FBO_A에 ④ 오버레이 덧그림          stage_i_ms
     * 패스9  present       FBO_A → 화면        gpu_present_ms
     * ```
     * 앞 7패스는 [DRAGO_CLAHE_CHAIN]과 **같은 순서·같은 SSBO의 GL 호출**이고 패스8은
     * [DETECT_CPU_HIGHLIGHT]의 오버레이 패스와 같은 프로그램이다 — 그래야 이 arm과 그 둘의
     * 차분이 뜻을 갖는다.
     *
     * 🔴 **다만 패스4·7의 프래그먼트는 그 arm과 같은 프로그램이 아니다**(2026-08-30부터).
     * 시연에서 볼륨키로 ②를 끄기 위해 [DEMO_ENHANCE_UNIFORM]로 섞는 한 줄이 더 있는 **복제
     * 프래그먼트**를 쓴다 — 전문은 [DEMO_APPLY_SHADER_VARIANT]. 산식도 SSBO도 같으므로
     * 차분의 뜻은 유지되지만, "글자 그대로 같은 프로그램"은 이제 참이 아니다.
     *
     * 🔴 **패스8의 타깃이 `fbos[0]`(FBO_A)다.** 체인의 마지막 처리 패스가 FBO_A에 쓰고
     * present가 FBO_A를 읽기 때문이다 — [DETECT_CPU_HIGHLIGHT]의 오버레이는 FBO_B에 그린다
     * (그 arm의 ② 자리가 거기 썼다). 그 함수를 그대로 복사해 오면 **박스가 화면에 안 뜨는데
     * `overlay_boxes`·`stage_i_ms`는 정상값이 나온다** — 이 arm 최대의 무음 실패 지점이고,
     * `PassthroughRenderer.drawChainedHighlight`가 그것을 주석으로 못 박고 있다.
     *
     * 🔴 **이 arm에는 이제 `_1q` 짝이 있다** → [DETECT_CPU_CHAIN_HIGHLIGHT_1Q]를
     * [singleFrameQueryPeer]에 이어 두었다. 그래서 I는 **상한과 하한이 둘 다** 나온다:
     * 상한은 이 arm의 `stage_i_ms`(패스별 계측이라 중복 계상한다 — 알려진 이슈 21),
     * 하한은 `detect_cpu_chain_highlight_1q − detect_cpu_chain_1q`다(둘 다 프레임 단일
     * query여야 뜻이 있고 같은 세션·같은 빌드에서 잰다). 분모가 `drago_clahe_chain_1q`가
     * **아닌** 이유는 거기에 탐지 부하가 없어서다 — 알려진 이슈 36이 그 부류다.
     * 전문은 [CHAIN_HIGHLIGHT_BOUNDS_NOTE].
     *
     * ⚠ **H는 하한·상한의 대상이 아니다.** `stage_h_ms`는 CPU 벽시계 직접 측정이고 모든 GPU
     * query **밖**에서 닫힌다([OVERLAY_STAGE_H_SCOPE]) — `gpu_frame_ms` 차분에 H는 물리적으로
     * 들어 있지 않다. 예전에 이 자리에 적혀 있던 "I칸·H칸의 하한"은 범주 오류였다.
     *
     * 🔴 **제품 구성 확정이 아니다** → [CHAIN_HIGHLIGHT_NOT_A_PRODUCT_DECISION].
     * ⚠ 탐지 입력은 이 arm에서도 **원본 프레임**이다 → [CHAIN_HIGHLIGHT_DETECT_INPUT_NOTE].
     *
     * 토큰 6개·열 9개는 **전부 이미 있는 것**이다 — 통합이라고 새 토큰·새 열을 만들지 않는다.
     */
    DETECT_CPU_CHAIN_HIGHLIGHT(
        "detect_cpu_chain_highlight",
        listOf(
            "blit_2pass",
            "stage2_drago",
            "stage2_clahe",
            "detect",
            "stage4_highlight",
            "stage4_smoothing",
        ),
        listOf(
            "stage_b_ms",
            "stage_d_analyze_ms",
            "stage_d_build_ms",
            "stage_d_apply_ms",
            "stage_d_analyze2_ms",
            "stage_d_build2_ms",
            "stage_d_apply2_ms",
            "stage_i_ms",
            "gpu_present_ms",
        ),
    ),

    // ── ②③④ 통합 arm의 짝 3개 (스키마 v7) ───────────────────────────────
    // 🔴 **셋이 통합 arm([DETECT_CPU_CHAIN_HIGHLIGHT])의 I 상한 옆에 하한을 세운다.** 그 arm은
    // 짝이 없어 지금까지 상한만 냈다. 전문은 [CHAIN_HIGHLIGHT_BOUNDS_NOTE]이고, 세 arm이
    // 각각 맡는 자리는 이렇다:
    //
    //   detect_cpu_chain               8패스(체인 7 + present) + 탐지. **오버레이 없음**.
    //                                  패스별 계측 → 통합 arm과의 차분이 ④ 오버레이다
    //   detect_cpu_chain_1q            위와 렌더가 같고 프레임 단일 query → 🔴 **I 하한의 분모**
    //   detect_cpu_chain_highlight_1q  통합 arm과 렌더가 같고 프레임 단일 query
    //                                  → 🔴 **I 하한의 분자**
    //
    // 🔴 **분모를 `drago_clahe_chain_1q`로 잡으면 안 된다** — 거기엔 탐지 부하가 없다
    //   ([usesDetectSession]이 false이고 `detect.csv`도 없다). 알려진 이슈 36이 그 부류다:
    //   분모를 잘못 고르면 하한이 0으로 나오고 그 0은 '공짜'가 아니라 **분모가 상한을 중복
    //   계상했다**는 뜻이었다.
    //
    // ⚠ **`detect_cpu_chain`을 [usesDynamicHighlightBoxes]에 넣지 않는다** — 넣으면 오버레이가
    //   없는 arm이 `stage_h_ms`·`overlay_boxes` 열을 싣고, 그러면 그 arm은 더 이상 "오버레이만
    //   뺀 분모"가 아니다.
    //
    // ⚠ 목록 **맨 뒤**에 붙인다. 스피너는 entries 순서라 중간에 끼우면 측정자가 손으로 고르던
    //   기존 arm의 위치가 전부 밀린다(`_1q` 셋·회전 대조군·③→④ 세트·통합 arm을 뒤에 붙인 것과
    //   같은 이유다).
    // ⚠ 열 이름을 companion의 상수로 쓰지 않는다 — enum 상수가 companion보다 먼저 초기화되므로
    //   초기화 순서 함정에 걸린다([GAMMA_ONLY]의 같은 주석). 문자열을 직접 적었고, 어긋나면
    //   [SINGLE_FRAME_QUERY_COLUMN_MISMATCH]가 잡는다.

    /**
     * ② 체인 + ③ 탐지이고 **④ 오버레이가 없는** arm. 8패스다 — 렌더는 [DRAGO_CLAHE_CHAIN]과
     * **글자 그대로 같은 GL 호출**이고([usesChainedComputeStage2]에 함께 넣어 같은
     * `PassthroughRenderer.drawChainedComputeStage2`를 탄다) 다른 것은 탐지가 도는가 하나뿐이다.
     *
     * 🔴 **[DETECT_CPU_CHAIN_HIGHLIGHT]에서 ④만 뺀 arm이라는 것이 이 arm의 뜻이다.** 그래서
     * 두 arm의 차분이 ④ 오버레이 + 통합 arm에만 있는 present 번짐이고, [DRAGO_CLAHE_CHAIN]과의
     * 차분이 탐지를 켜서 생긴 변화다.
     *
     * ⚠ 오버레이가 없으므로 [usesDynamicHighlightBoxes]에 **넣지 않았다**(위 블록 머리말).
     *
     * 토큰 4개·열 8개는 **전부 이미 있는 것**이다 — 조합이라고 새 토큰·새 열을 만들지 않는다.
     */
    DETECT_CPU_CHAIN(
        "detect_cpu_chain",
        listOf("blit_2pass", "stage2_drago", "stage2_clahe", "detect"),
        // 짝(drago_clahe_chain)과 **같은 목록·같은 순서**다. 탐지는 별 스레드에서 돌고 렌더
        // 경로를 하나도 건드리지 않으므로 열이 같아야 차분이 뜻을 갖는다.
        listOf(
            "stage_b_ms",
            "stage_d_analyze_ms",
            "stage_d_build_ms",
            "stage_d_apply_ms",
            "stage_d_analyze2_ms",
            "stage_d_build2_ms",
            "stage_d_apply2_ms",
            "gpu_present_ms",
        ),
    ),

    /**
     * [DETECT_CPU_CHAIN]의 프레임 단일 query 판. 렌더 8패스, 열 1개.
     * 🔴 **I 하한의 분모**다 → [CHAIN_HIGHLIGHT_BOUNDS_NOTE].
     */
    DETECT_CPU_CHAIN_1Q(
        "detect_cpu_chain_1q",
        listOf("blit_2pass", "stage2_drago", "stage2_clahe", "detect"),
        listOf("gpu_frame_ms"),
    ),

    /**
     * [DETECT_CPU_CHAIN_HIGHLIGHT]의 프레임 단일 query 판. 렌더 9패스, 열 1개.
     *
     * 🔴 **렌더는 짝과 글자 그대로 같다** — [usesChainedHighlight]에 함께 넣어 같은
     * `PassthroughRenderer.drawChainedHighlight`를 타고, 평활·hold도 같은 자리에서 같은 값으로
     * 돈다. 🔴 그래서 [usesDynamicHighlightBoxes]와 [highlightBoxCount]의 DYNAMIC 분기에도
     * 함께 들어 있어야 한다 — 빠지면 박스를 0개 그려 렌더가 짝과 달라지고 두 계측의 차분이
     * 아무 뜻이 없어진다([HIGHLIGHT_BOXES_1Q]가 같은 경고를 달고 있다).
     *
     * 🔴 **I 하한의 분자**다 → [CHAIN_HIGHLIGHT_BOUNDS_NOTE].
     */
    DETECT_CPU_CHAIN_HIGHLIGHT_1Q(
        "detect_cpu_chain_highlight_1q",
        listOf(
            "blit_2pass",
            "stage2_drago",
            "stage2_clahe",
            "detect",
            "stage4_highlight",
            "stage4_smoothing",
        ),
        listOf("gpu_frame_ms"),
    ),

    // ── ④ fill 대조군(`detect_cpu_chain_highlight_nofill`) ─────────────────
    // 🔴 **fill 기하 하나만 다른 짝 arm이다.** 접미사 규약은 [DETECT_CPU_NOROT]의 선례를
    // 그대로 따른다 — "짝과 글자 그대로 같고 한 요소만 뺀 **의도된 대조군**"이며, 그 뜻이
    // "아직 구현하지 않았다"와 구분돼야 한다.
    //
    // 🔴 **알파 0으로 두는 대조군이 아니다.** 알파 0이면 fill quad가 그대로 래스터라이즈돼
    //   프래그먼트 비용이 똑같이 들고, 그러면 차분이 0에 가깝게 나오면서 "fill이 공짜다"라는
    //   틀린 결론이 나온다. 이 arm은 **fill 기하 자체를 정점 버퍼에 쓰지 않는다**
    //   ([HighlightOverlay.vertsPerBox]가 그 유일한 분기다).
    //
    // 🔴 **[DETECT_CPU_CHAIN_HIGHLIGHT]와 글자 그대로 같아야 한다** — 같은 [pipelineStages]·
    //   같은 [gpuColumns]·같은 draw 함수([usesChainedHighlight])·같은 EP·같은 평활 정책이고,
    //   블렌딩도 켠 채 둔다. 갈리는 것은 [drawsOverlayFill] 하나뿐이다. 전문은
    //   [HIGHLIGHT_NOFILL_CONTROL_NOTE].
    //
    // 🔴 **`_1q` 짝을 만들지 않는다** — [singleFrameQueryPeer]에 넣지 않으므로
    //   [renderPassCount]가 자기 열 수(9)를 그대로 쓴다.
    //
    // ⚠ 목록 **맨 뒤**에 붙인다. 스피너는 entries 순서라 중간에 끼우면 측정자가 손으로 고르던
    //   기존 arm의 위치가 전부 밀린다(`_1q` 셋·회전 대조군·③→④ 세트·통합 세트를 뒤에 붙인
    //   것과 같은 이유다).

    /**
     * [DETECT_CPU_CHAIN_HIGHLIGHT]의 **fill 미적용** 짝. I칸에서 fill 기하가 차지하는 몫을
     * **같은 세션·같은 빌드 안에서** 잡는 자리다.
     *
     * 🔴 **이 arm의 뜻은 짝 arm과의 차분 하나뿐이다** → [HIGHLIGHT_NOFILL_CONTROL_NOTE].
     * 그 문장이 무엇이 분리되고 무엇이 분리되지 않는지를 함께 적는다.
     *
     * ⚠ [pipelineStages]와 [gpuColumns]는 짝과 **글자 그대로 같다.** 토큰의 뜻은 "그 단계가
     * 프레임 경로에서 돌았는가"이고 ④도 평활도 이 arm에서 실제로 돈다 — 스트로크는 그리고
     * fill 기하만 건너뛴다.
     *
     * 🔴 이 arm은 상류 '비채움' 명세에 **부합한다**(짝 arm이 그 명세에서 이탈한 쪽이다) —
     * `overlay.fill_deviation`이 arm별로 그 사실을 말한다.
     */
    DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL(
        "detect_cpu_chain_highlight_nofill",
        // 🔴 짝(detect_cpu_chain_highlight)과 **같은 목록·같은 순서**다.
        listOf(
            "blit_2pass",
            "stage2_drago",
            "stage2_clahe",
            "detect",
            "stage4_highlight",
            "stage4_smoothing",
        ),
        listOf(
            "stage_b_ms",
            "stage_d_analyze_ms",
            "stage_d_build_ms",
            "stage_d_apply_ms",
            "stage_d_analyze2_ms",
            "stage_d_build2_ms",
            "stage_d_apply2_ms",
            "stage_i_ms",
            "gpu_present_ms",
        ),
    );

    /**
     * ② 자리에 **컴퓨트 통계 패스가 있는** arm인가. **`session.json`의 서술 분기 전용**이다
     * (셰이더 방언·draw_call 서술·컴퓨트 배리어 주석).
     *
     * ⚠ 예전에는 이 하나가 **드로우 경로 선택까지** 겸했다. [DRAGO_CLAHE_CHAIN]이 들어오면서
     * 그 겸업이 깨졌다 — 조합은 서술 기준으로는 여기 속하지만 5패스 경로를 타면 안 된다.
     * 그래서 경로 선택은 [usesSingleComputeStage2] / [usesChainedComputeStage2]로 갈랐다.
     */
    val usesComputeStage2: Boolean
        get() = usesSingleComputeStage2 || usesChainedComputeStage2 || usesFusedComputeStage2 ||
            usesChainedBilateral || usesFusedBilateral || usesChainedHighlight

    /**
     * ② 자리가 **컴퓨트 3단 한 벌**(analyze → build → apply)인 arm인가.
     * **`PassthroughRenderer.drawComputeStage2`(5패스) 경로 선택 전용**이다.
     */
    val usesSingleComputeStage2: Boolean
        get() = this == DRAGO || this == CLAHE_GAMMA || this == AGCWD

    /**
     * ② 자리가 **컴퓨트 3단 두 벌**(조합)인 arm인가.
     * **`PassthroughRenderer.drawChainedComputeStage2`(8패스) 경로 선택 전용**이다.
     *
     * 🔴 [DRAGO_CLAHE_CHAIN_1Q]가 **여기 함께 들어 있다.** 그 arm은 계측 방식만 다르고
     * 렌더는 같아야 하므로 짝과 **같은 draw 함수**를 타야 한다 — 여기서 빼면 `dispatchDraw`가
     * 3패스 골격으로 떨어뜨려 실험이 통째로 무의미해진다.
     *
     * 🔴 [DETECT_CPU_CHAIN]·[DETECT_CPU_CHAIN_1Q]도 **여기다.** 탐지는 별 스레드에서 돌고 GL
     * 패스를 하나도 더하지 않으므로 렌더가 체인과 글자 그대로 같다.
     * ⚠ [usesChainedHighlight]와 **교집합이 공집합이어야 한다** — 겹치면 `dispatchDraw`가
     * 8패스 경로로 떨어뜨려 ④가 조용히 사라진다(그 프로퍼티의 같은 경고).
     */
    val usesChainedComputeStage2: Boolean
        get() = this == DRAGO_CLAHE_CHAIN || this == DRAGO_CLAHE_CHAIN_1Q ||
            this == DETECT_CPU_CHAIN || this == DETECT_CPU_CHAIN_1Q

    /**
     * ② 자리가 **체인이고 그 뒤에 ④ 오버레이 패스가 하나 더 붙는** arm인가(9패스).
     * **`PassthroughRenderer.drawChainedHighlight` 경로 선택 전용**이다.
     *
     * 🔴 [usesChainedComputeStage2]와 **겹치지 않는다.** 겹치면 `dispatchDraw`가 8패스 경로로
     * 떨어뜨려 **④가 조용히 사라진다**(그 프로퍼티가 경고한 겸업 함정과 같은 부류이고,
     * [usesChainedBilateral]이 같은 이유로 갈라져 있다).
     *
     * 🔴 fill 대조군([DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL])도 **여기다** — 렌더 경로가 짝과
     * 글자 그대로 같아야 두 arm의 차분이 fill 기하의 비용이 된다. 갈리는 것은
     * [drawsOverlayFill] 하나뿐이다.
     */
    val usesChainedHighlight: Boolean
        get() = this == DETECT_CPU_CHAIN_HIGHLIGHT || this == DETECT_CPU_CHAIN_HIGHLIGHT_1Q ||
            this == DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL

    /**
     * ② 자리가 **융합**(통계 두 벌 + 적용 한 벌)인 arm인가.
     * **`PassthroughRenderer.drawFusedComputeStage2`(7패스) 경로 선택 전용**이다.
     *
     * 🔴 [DRAGO_CLAHE_FUSED_1Q]가 **여기 함께 들어 있다**([usesChainedComputeStage2]와 같은
     * 이유다) — 여기서 빼면 `dispatchDraw`가 3패스 골격으로 떨어뜨려 실험이 무의미해진다.
     */
    val usesFusedComputeStage2: Boolean
        get() = this == DRAGO_CLAHE_FUSED || this == DRAGO_CLAHE_FUSED_1Q

    /**
     * ② 자리가 **체인 + bilateral**인 arm인가.
     * **`PassthroughRenderer.drawChainedBilateral`(9패스) 경로 선택 전용**이다.
     *
     * ⚠ [usesChainedComputeStage2]와 **겹치지 않는다.** 겹치면 이 arm이 8패스 경로를 타고
     * bf 패스가 조용히 사라진다(`usesComputeStage2` 겸업 함정과 같은 부류다).
     */
    val usesChainedBilateral: Boolean
        get() = this == DRAGO_CLAHE_CHAIN_BF || this == DRAGO_CLAHE_CHAIN_BF_1Q

    /**
     * ② 자리가 **융합 + bilateral**인 arm인가.
     * **`PassthroughRenderer.drawFusedBilateral`(8패스) 경로 선택 전용**이다.
     */
    val usesFusedBilateral: Boolean
        get() = this == DRAGO_CLAHE_FUSED_BF || this == DRAGO_CLAHE_FUSED_BF_1Q

    /**
     * ④ 오버레이 arm인가. **`PassthroughRenderer.drawHighlightOverlay`(4패스) 경로 선택
     * 전용**이다 — 개수 차이는 [highlightBoxCount]가 따로 말한다.
     */
    val usesHighlightOverlay: Boolean
        get() = this == HIGHLIGHT_BOXES || this == HIGHLIGHT_BOXES_STRESS ||
            this == HIGHLIGHT_BOXES_1Q || usesDynamicHighlightBoxes

    /**
     * 🔴 **박스 안쪽 fill quad를 실제로 그리는가 — fill 여부의 단일 출처다.**
     *
     * [HighlightOverlay]의 정점 생성과 `session.json`의 `overlay.fill_enabled`가 **둘 다
     * 이 값에서 온다.** 상태 플래그(`var fillEnabled`)를 두지 않고 프레임 경로에 **인자로**
     * 넘기는 이유는 `PassthroughRenderer.setArm`이 `if (arm == next) return`으로 조기
     * 반환하는 경로가 있어서다 — 그 길로 들어가면 초기 arm에 플래그가 안 실리고, 그것은
     * 로그에 아무 흔적을 남기지 않는 무음 실패다.
     *
     * 🔴 **false인 arm은 fill 기하를 정점 버퍼에 아예 쓰지 않는다**(알파 0이 아니다 — 알파 0은
     * 프래그먼트 비용이 같다). 전문은 [HIGHLIGHT_NOFILL_CONTROL_NOTE].
     */
    val drawsOverlayFill: Boolean
        get() = usesHighlightOverlay && this != DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL

    /**
     * 🔴 오버레이 박스가 **③ 탐지 결과**인가(정적 더미가 아닌가). true면 GL 스레드가 매 프레임
     * `DetectOverlayPublisher.latest()`를 읽어 H칸(좌표 평활·hold)을 거친 목록을 그린다.
     *
     * 🔴 **이 값이 `stage_h_ms`·`overlay_boxes` 열의 유일한 판별식이다** — false인 arm은 그
     * 열을 싣지 않는다(정적 더미 arm에 H 열을 -1로 채워 내보내면 하네스가 "쟀는데 못 얻었다"로
     * 읽는데 그건 다른 뜻이다 — `FrameLogRecorder.CSV_HEADER`의 같은 규약).
     *
     * ⚠ 이 값이 true인 arm은 [highlightBoxCount]를 **쓰지 않는다**(개수가 프레임마다 다르다).
     */
    val usesDynamicHighlightBoxes: Boolean
        get() = this == DETECT_CPU_HIGHLIGHT || this == DETECT_CPU_HIGHLIGHT_1Q ||
            this == DETECT_CPU_CHAIN_HIGHLIGHT || this == DETECT_CPU_CHAIN_HIGHLIGHT_1Q ||
            // fill 대조군도 ③ 결과를 그린다 — 스트로크는 그대로 그리고 fill 기하만 건너뛴다.
            // 빠뜨리면 박스를 0개 그려 렌더가 짝과 달라지고 차분이 아무 뜻이 없어진다.
            this == DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL

    /** ② 자리에 bilateral 한 패스가 붙는 arm인가(체인이든 융합이든). */
    val usesBilateral: Boolean
        get() = usesChainedBilateral || usesFusedBilateral

    /**
     * 이 arm이 **프레임 단일 query** arm이면 그 짝(패스별 계측 arm), 아니면 null.
     *
     * 🔴 **짝의 정의는 "렌더 경로가 글자 그대로 같은 arm"이다.** 여기서 이어 둔 덕에
     * [renderPassCount]가 짝의 열 수에서 자동으로 따라오고(손으로 센 숫자가 두 곳에 생기지
     * 않는다), `SessionWriter`가 짝의 서술을 그대로 재사용한다.
     */
    val singleFrameQueryPeer: RenderArm?
        get() = when (this) {
            BLIT_2PASS_1Q -> BLIT_2PASS
            DRAGO_CLAHE_CHAIN_1Q -> DRAGO_CLAHE_CHAIN
            DRAGO_CLAHE_CHAIN_BF_1Q -> DRAGO_CLAHE_CHAIN_BF
            DRAGO_CLAHE_FUSED_1Q -> DRAGO_CLAHE_FUSED
            DRAGO_CLAHE_FUSED_BF_1Q -> DRAGO_CLAHE_FUSED_BF
            HIGHLIGHT_BOXES_1Q -> HIGHLIGHT_BOXES
            // ③→④ 연결 세트(v7). 🔴 이 둘을 빠뜨리면 [renderPassCount]가 열 수(1)를 그대로
            //    쓰고 GpuTimerRing이 **첫 패스만** 감싼다 — 그런데 로그만 보면 그럴듯하다.
            DETECT_CPU_HIGHLIGHT_1Q -> DETECT_CPU_HIGHLIGHT
            DETECT_CPU_1Q -> DETECT_CPU
            // ②③④ 통합 세트. 이 둘의 차가 통합 arm의 **I 하한**이다
            // ([CHAIN_HIGHLIGHT_BOUNDS_NOTE]). 여기 빠뜨리면 [renderPassCount]가 열 수(1)를
            // 그대로 쓰고 GpuTimerRing이 **첫 패스만** 감싼다 — 로그만 보면 그럴듯하다.
            DETECT_CPU_CHAIN_HIGHLIGHT_1Q -> DETECT_CPU_CHAIN_HIGHLIGHT
            DETECT_CPU_CHAIN_1Q -> DETECT_CPU_CHAIN
            else -> null
        }

    /**
     * 프레임 하나를 timer query **하나**로 감싸는 arm인가.
     * **[GpuTimerRing]의 모드 선택 전용**이다(`PassthroughRenderer.setArm`이 넘긴다).
     */
    val usesSingleFrameQuery: Boolean
        get() = singleFrameQueryPeer != null

    /**
     * draw 함수가 프레임당 부르는 `beginPass`/`endPass` 횟수 = **렌더 패스 수**.
     *
     * 🔴 **[gpuColumns]의 개수(= CSV 열 수)와 같지 않을 수 있다.** 패스별 계측 arm에서는
     * 둘이 같지만(패스 하나 = 열 하나), 프레임 단일 query arm은 **열 1개 · 렌더 패스 3~9개**다.
     * 두 수를 한 값으로 쓰면 링이 첫 패스만 감싸고도 그럴듯한 숫자를 낸다 — 이 저장소가 가장
     * 경계하는 실패다. 그래서 [GpuTimerRing.setPassPlan]은 두 수를 **따로** 받아 대조한다.
     *
     * 단일 query arm의 값은 짝의 열 수에서 온다([singleFrameQueryPeer]) — 손으로 센 숫자를
     * 두 번째로 만들지 않기 위해서다. 짝의 패스 수가 바뀌면 이 값도 따라 바뀐다.
     */
    val renderPassCount: Int
        get() = singleFrameQueryPeer?.gpuColumns?.size ?: gpuColumns.size

    /**
     * ③ 탐지 arm인가(분모 [DETECT_BIND_ONLY] 포함). **`session.json`에 `detect` 블록을 낼지의
     * 판별식**이다 — 다른 arm에 빈 블록을 내면 "잰 적 없는 칸"이 있는 것처럼 보인다
     * ([usesHighlightOverlay]가 `overlay` 블록에 대해 하는 일과 같다).
     */
    val isDetectArm: Boolean
        get() = this == DETECT_BIND_ONLY || usesDetectSession

    /**
     * ORT **세션을 여는** arm인가. [DETECT_BIND_ONLY]는 여기 들어가지 않는다 — 그 arm은
     * 추론을 돌리지 않는 분모이므로 세션을 열면 그 자체가 조건 오염이다.
     */
    val usesDetectSession: Boolean
        get() = this == DETECT_CPU || this == DETECT_NNAPI || this == DETECT_XNNPACK ||
            this == DETECT_CPU_PROF || this == DETECT_NNAPI_PROF || this == DETECT_XNNPACK_PROF ||
            this == DETECT_CPU_NOROT || this == DETECT_CPU_1Q ||
            // ②③④ 통합 세트에서 **오버레이가 없는** 두 arm. 나머지 하나
            // ([DETECT_CPU_CHAIN_HIGHLIGHT_1Q])는 usesDynamicHighlightBoxes 경유로 이미
            // 걸리므로 여기 중복 등록하지 않는다.
            this == DETECT_CPU_CHAIN || this == DETECT_CPU_CHAIN_1Q ||
            usesDynamicHighlightBoxes ||
            usesDetectParityDump

    /**
     * 분석 프레임의 `rotationDegrees`를 **전처리에 적용하는가**(규약 §4).
     *
     * 🔴 **`detect_cpu_norot`만 false다** — 그 arm은 회전 전 E를 같은 세션에서 재기 위한
     * **의도된 대조군**이고, 매니페스트에 `rotation_applied=false` + `rotation_site="none"`을
     * 낸다(§4-2 표의 🟢 행). 세션 옵션도 렌더 경로도 짝([DETECT_CPU])과 글자 그대로 같고
     * 갈리는 것은 이 값 하나다.
     *
     * ⚠ **`rotationDegrees == 0`과 다른 사실이다.** 기기가 0°를 주면 회전은 **적용됐는데
     * 항등**인 것이고 그때도 이 값은 true다(§4-2).
     */
    val appliesDetectRotation: Boolean
        get() = usesDetectSession && this != DETECT_CPU_NOROT

    /**
     * ③ **이식 정확성 대조 덤프**를 남기는 arm인가. `DetectParityDumper`의 시작 판별식이고,
     * `session.json`의 `detect.parity` 블록과 인용 금지 문장([DETECT_PARITY_NOT_QUOTABLE])도
     * 이 값으로 갈린다.
     *
     * 🔴 **추론 경로는 짝 arm과 같다** — 다른 것은 샘플 K개를 파일로 쓰는가 하나뿐이다.
     */
    val usesDetectParityDump: Boolean
        get() = this == DETECT_PARITY_CPU || this == DETECT_PARITY_NNAPI ||
            this == DETECT_PARITY_XNNPACK

    /**
     * 이 arm이 **요청하는** 실행 공급자. 어휘는 `lib/frame_log.py`의 `DETECT_EPS`와 같다.
     *
     * 🔴 **요청값이지 결과값이 아니다.** 실제로 무엇이 실행했는지는 앱이 따로 판별해
     * `detect.ep.resolved`로 낸다 — 한쪽만 적으면 조용한 폴백이 실패로 잡히지 않는다.
     */
    val detectEpRequested: String?
        get() = when (this) {
            DETECT_CPU, DETECT_CPU_PROF, DETECT_PARITY_CPU, DETECT_CPU_NOROT,
            DETECT_CPU_HIGHLIGHT, DETECT_CPU_HIGHLIGHT_1Q, DETECT_CPU_1Q,
            DETECT_CPU_CHAIN_HIGHLIGHT, DETECT_CPU_CHAIN_HIGHLIGHT_1Q,
            DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL,
            DETECT_CPU_CHAIN, DETECT_CPU_CHAIN_1Q -> "cpu"
            DETECT_NNAPI, DETECT_NNAPI_PROF, DETECT_PARITY_NNAPI -> "nnapi"
            DETECT_XNNPACK, DETECT_XNNPACK_PROF, DETECT_PARITY_XNNPACK -> "xnnpack"
            else -> null
        }

    /** ORT 프로파일러를 **측정 세션에** 켜는 arm인가. 🔴 [DETECT_PROF_NOT_QUOTABLE]. */
    val detectProfilingEnabled: Boolean
        get() = this == DETECT_CPU_PROF || this == DETECT_NNAPI_PROF ||
            this == DETECT_XNNPACK_PROF

    /**
     * 조합 arm인가(체인이든 융합이든, bf가 붙었든). 둘의 계수를 나란히 낼 때 쓴다.
     * **서술용이며 경로 선택에 쓰지 않는다.**
     */
    val isCompositionArm: Boolean
        get() = usesChainedComputeStage2 || usesFusedComputeStage2 || usesBilateral ||
            usesChainedHighlight

    /**
     * 이 arm이 그리는 ④ 박스 수. 오버레이 arm이 아니면 0이다.
     *
     * ⚠ **사양이 아니라 우리가 선언한 측정 조건**이다([HIGHLIGHT_BOX_PROVENANCE]).
     *
     * 🔴 **정적 더미 arm 전용이다.** ③ 결과를 그리는 arm([usesDynamicHighlightBoxes])에서는
     * 개수가 프레임마다 다르므로 [HIGHLIGHT_BOX_COUNT_DYNAMIC]을 돌려준다 — 여기서 0이나
     * 어떤 고정값을 돌려주면 `session.json`의 `overlay.box_count`가 **거짓 조건**이 된다.
     * 그 arm의 개수는 `frames.csv`의 `overlay_boxes` 열이 프레임별로 말한다.
     */
    val highlightBoxCount: Int
        get() = when (this) {
            // 🔴 `_1q` 짝이 **같은 개수**를 그려야 한다. 여기서 빠지면 0개를 그려 렌더가
            //    짝과 달라지고, 그러면 두 계측의 차분이 아무 뜻이 없어진다.
            HIGHLIGHT_BOXES, HIGHLIGHT_BOXES_1Q -> HIGHLIGHT_BOX_COUNT
            HIGHLIGHT_BOXES_STRESS -> HIGHLIGHT_BOX_COUNT_STRESS
            DETECT_CPU_HIGHLIGHT, DETECT_CPU_HIGHLIGHT_1Q,
            DETECT_CPU_CHAIN_HIGHLIGHT, DETECT_CPU_CHAIN_HIGHLIGHT_1Q,
            DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL ->
                HIGHLIGHT_BOX_COUNT_DYNAMIC
            else -> 0
        }

    companion object {

        /** 기본값은 기존 경로다 — 아무것도 고르지 않은 런이 베이스라인을 재현하도록. */
        val DEFAULT = PASSTHROUGH

        /** 스피너 목록. 첫 항목이 기본값이다. */
        val CHOICES: List<String> = entries.map { it.id }

        fun fromId(id: String?): RenderArm = entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * ② 자리에 넣는 감마 지수. **제안값이 아니다.**
         *
         * `INTERFACES.md` §B-5의 파라미터 표는 아직 전부 `☐`이고, §B-2의 "감마: sRGB 그대로 vs
         * 선형화" 역시 미확정이다. 그러므로 이 숫자는 알고리즘 제안이 아니라 **pow() 한 번의
         * 비용 봉투를 재기 위한 임의값**이다. 0.5(=제곱근)를 쓰는 이유 두 가지:
         *  - 1.0이면 드라이버가 pow를 접어 버려 측정하려던 비용이 사라진다.
         *  - 1/2.2(≈0.4545)는 sRGB 인코딩 감마라서 나중에 "제안값이었다"로 오독될 수 있다.
         * 같은 문장이 `session.json`의 `stage2_params.provenance`에도 나간다.
         */
        const val GAMMA_MEASUREMENT_VALUE = 0.5f

        /** 셰이더에 노출한 uniform 이름. 하드코딩 상수가 아님을 기록으로 남기기 위함. */
        const val GAMMA_UNIFORM = "uGamma"

        const val GAMMA_PROVENANCE =
            "INTERFACES.md §B-5 미확정(☐). 비용 봉투 측정용이며 제안값이 아니다 — " +
                "팀장이 값을 확정하면 그때 교체한다. 값 자체는 pow() 비용에 거의 영향이 없다"

        /**
         * ②는 **stateless**로 만든다. `INTERFACES.md` §B-4(프레임 간 상태)도 `☐`인데,
         * 상태 버퍼를 임의로 도입하면 비용과 리셋 조건이 함께 미정이 된다.
         */
        const val TEMPORAL_STATE = "none (stateless) — INTERFACES.md §B-4 미확정(☐)이라 상태를 두지 않는다"

        // ── 시연용 ② 토글 ────────────────────────────────────────────────
        // 볼륨키로 ②(적응형 조도개선)를 즉시 끄고 켜기 위한 자리다. **체감 비교가 목적이고
        // 성능 기록이 아니다.**

        /** 통합 arm의 패스4·7 복제 프래그먼트가 노출하는 mix 계수 uniform 이름. */
        const val DEMO_ENHANCE_UNIFORM = "uEnhance"

        /** ② ON. `mix(원본, ② 결과, 1)` = ② 결과 그대로. */
        const val DEMO_ENHANCE_ON = 1f

        /** ② OFF. `mix(원본, ② 결과, 0)` = 원본 그대로(패스 수는 그대로 돈다). */
        const val DEMO_ENHANCE_OFF = 0f

        /**
         * 🔴 위 0/1의 출처. [GAMMA_PROVENANCE]와 **같은 형식**이며 `session.json`에 그대로
         * 나간다 — `INTERFACES.md` §B-5의 `☐`를 채운 것으로 오독되지 않게 하기 위해서다.
         */
        const val DEMO_ENHANCE_PROVENANCE =
            "알고리즘 제안값이 아니다 — 시연에서 ②를 켜고 끄는 **스위치**의 두 끝이다. " +
                "0은 원본 그대로(mix 계수 0), 1은 ② 결과 그대로(mix 계수 1)이며 그 사이 값은 " +
                "쓰지 않는다. INTERFACES.md §B-5의 ☐를 채운 값이 아니고, ② 파라미터는 " +
                "stage2_params 쪽 provenance가 따로 말한다"

        /**
         * 🔴 **통합 arm의 패스4·7이 승격 베이스라인과 다른 문자열이라는 자진 신고.**
         * `session.json`의 `demo_toggles.apply_shader_variant`로 나간다.
         *
         * ⚠ 지금은 `baseline_diff.py`의 `CONDITION_KEYS`에 이 키가 없어 **기계가 읽지
         * 않는다.** 앱 쪽 규격을 먼저 확정해 두면 나중에 하네스가 승격할 수 있다.
         */
        const val DEMO_APPLY_SHADER_VARIANT =
            "demo_enhance_mix — 통합 arm(detect_cpu_chain_highlight 계열)의 패스4·7은 " +
                "drago_clahe_chain이 쓰는 공유 프래그먼트가 아니라 " +
                "mix($DEMO_ENHANCE_UNIFORM) 한 줄이 더 붙은 **복제본**이다. " +
                "산식과 색공간 변환 토큰 계수는 같지만 셰이더 문자열은 같지 않다 — " +
                "docs/baselines/의 승격 숫자와 이 arm의 패스4·7 비용을 같은 것으로 보지 말 것"

        // ── Drago 톤매핑 파라미터 ─────────────────────────────────────────
        // 상류(모델링 담당) `scripts/lowlight.py`의 `D1`:
        //   Tonemap Drago(gamma=2.2, saturation=1.0, bias=0.85)
        // ⚠ **팀장이 준 계약 답변이 아니다.** `INTERFACES.md` §B-5는 여전히 전부 `☐`다.
        //   그래서 상수로 박지 않고 전부 uniform으로 노출하고, 실제로 쓴 값은
        //   session.json의 stage2_params에 provenance와 함께 남긴다.

        /** 되씌우는 감마. 셰이더는 `pow(x, 1/uOutGamma)`로 쓴다(OpenCV `Tonemap::gamma`와 같은 뜻). */
        const val DRAGO_GAMMA = 2.2f

        /** `mapLuminance`의 채도 지수. 1.0이면 `out = (c/lum) * newLum`. */
        const val DRAGO_SATURATION = 1.0f

        /**
         * Drago의 bias.
         *
         * ⚠ 상류 레지스트리는 `Tonemap("drago")`처럼 인자 없이 만들어서 **팩토리 기본값
         * 0.85가 적용되지만 `self.params`에는 기록되지 않는다**(상류 문서가 밝힌 알려진 흠,
         * `docs/research/RESEARCH_20260731_UPSTREAM.md` §2-8). 그래서 이 값은 상류의
         * 파라미터 표가 아니라 **구현 기본값을 직접 읽어** 옮긴 것이다.
         */
        const val DRAGO_BIAS = 0.85f

        /**
         * sRGB → 선형 변환 지수. 상류는 톤맵에 넣기 전에 `pow(x, 2.2)`로 선형화한다
         * (CPU 쪽은 이걸 256엔트리 LUT로 접었지만 GPU에서는 `pow`가 하드웨어 명령이라
         * 그 병목 자체가 없다). §B-2 "감마: sRGB 그대로 vs 선형화"도 아직 `☐`다.
         */
        const val DRAGO_SRC_GAMMA = 2.2f

        const val DRAGO_SRC_GAMMA_UNIFORM = "uSrcGamma"
        const val DRAGO_OUT_GAMMA_UNIFORM = "uOutGamma"
        const val DRAGO_SATURATION_UNIFORM = "uSaturation"
        const val DRAGO_BIAS_UNIFORM = "uBias"

        /** 휘도 가중치. OpenCV `COLOR_RGB2GRAY`와 같은 값이라 상류와 채널 정의가 어긋나지 않는다. */
        const val DRAGO_LUMA_WEIGHTS = "0.299 R + 0.587 G + 0.114 B (OpenCV COLOR_RGB2GRAY)"

        const val DRAGO_PROVENANCE =
            "상류(모델링 담당 kty2001/KDT_Hackathon) scripts/lowlight.py의 D1 탐색 구현값이며 " +
                "**계약 확정값이 아니다** — INTERFACES.md §B-5는 여전히 전부 ☐다. bias는 상류 " +
                "레지스트리가 self.params에 기록하지 않아(알려진 흠) 팩토리 기본값을 코드에서 " +
                "직접 읽어 옮겼다. 전부 uniform으로 노출했으므로 팀장이 값을 확정하면 " +
                "셰이더를 고치지 않고 교체한다"

        /**
         * 상류(OpenCV `TonemapDrago`)와 **의도적으로 다른 한 곳.**
         * 보고서에도 같은 문장을 낸다 — 조용히 굳으면 나중에 차이의 출처를 못 찾는다.
         */
        const val DRAGO_DEVIATION =
            "이 이식은 상류 OpenCV TonemapDrago와 **세 곳이 다르다.** 독립 검증이 " +
                "opencv-python 5.0.0으로 4조합을 재구현해 대조한 결과다(front+back 조합만 " +
                "cv2와 max|diff|=0.000000으로 일치). " +
                "(1) **앞 정규화 없음** — OpenCV는 오퍼레이터 앞에 기반 클래스의 min/max 선형 " +
                "정규화를 돌린다. " +
                "(2) **뒤 정규화도 없음** — OpenCV는 오퍼레이터 **뒤에도** 한 번 더 돌린다. " +
                "화면의 다이내믹 레인지를 정하는 쪽이 이 뒤 정규화다(출력 max를 정확히 1.0으로 늘린다). " +
                "(3) **mapLuminance 분모가 다름** — OpenCV는 gray/mean(평균정규화 휘도)으로 " +
                "나누는데 이 이식은 원시 lum으로 나눈다. saturation=1에서 이건 ×mean 상수배이고, " +
                "OpenCV에서는 뒤 정규화가 그걸 지운다. " +
                "🔴 **(2)와 (3)이 서로 거의 상쇄되어 결과가 우연히 그럴듯해 보인다 — 하나만 " +
                "복원하면 화면이 크게 틀어진다.** 셋을 함께 다뤄야 한다. " +
                "실측 이탈 폭: 실기기 실내 프레임 23.5 LSB, 합성 야간+클리핑 광원 55.6 LSB. " +
                "이 이식이 계통적으로 더 밝다 — 골든 이미지 대조(INTERFACES.md §B-6)를 하면 " +
                "여기서 걸린다. " +
                "왜 지금 재현하지 않는가: 뒤 정규화는 매 프레임 전역 오토레벨이라 " +
                "§B-4가 안전 문제로 규정한 프레임 간 출렁임을 하나 더 얹는다. " +
                "⚠ 예전에 여기 함께 적혀 있던 '전체 화면 리덕션 두 번이 GPU 타이머 슬롯을 " +
                "초과한다'는 **이제 근거가 아니다**(GpuTimerRing.MAX_PASS_COUNT를 12로 " +
                "올렸다). 남는 이유는 위 안전 문제 하나다. " +
                "**'상류를 픽셀 단위로 재현할 것인가'는 알고리즘 결정이므로 팀장 판단이다** " +
                "(FRAME_BUDGET.md §5 레버 3). " +
                "덤: 검증 중 상류 쪽 결함이 나왔다 — OpenCV는 앞 정규화가 만든 정확한 0을 " +
                "mapLuminance가 나눠 **실제 8비트 프레임에서 NaN을 낸다**(재현됨). " +
                "이 이식은 max(lum, 1e-4)로 막고 있어 그 입력에서는 상류보다 낫다"

        /**
         * 이 arm의 **관측 대상**이지 결함이 아니다. 고치지 않고 보고한다.
         * `INTERFACES.md` §B-4는 프레임 간 상태와 깜빡임을 안전 문제로 규정하고, 상류 문서도
         * "프레임별 적응 기법은 플리커를 유발한다"고 지적했다. 시간축 평활은 §B-4 답변 이후다.
         */
        const val DRAGO_FLICKER_NOTE =
            "전역 통계(로그평균 휘도·최대 휘도)를 **매 프레임 새로** 구한다(stateless). " +
                "장면이 바뀌면 톤커브가 프레임마다 흔들려 화면이 출렁일 수 있다 — " +
                "INTERFACES.md §B-4(프레임 간 상태)가 ☐라 시간축 평활을 임의로 넣지 않았다. " +
                "이 라운드에서는 **관측 대상**이며, 관측 결과는 보고에 남긴다"

        // ── ② A1 CLAHE + 감마 / A2 AGCWD 파라미터 ─────────────────────────
        // 상류(모델링 담당) `scripts/lowlight.py`:
        //   A1: CLAHE(clip_limit=2.0, tile=8, gamma=0.75)  → LAB의 L
        //   A2: AGCWD(alpha=0.5) (Huang 2013)              → LAB의 L
        // ⚠ **팀장이 준 계약 답변이 아니다.** INTERFACES.md §B-5는 여전히 전부 ☐다.
        //   그래서 상수로 박지 않고 전부 uniform으로 노출하고, 실제로 쓴 값은
        //   session.json의 stage2_params에 provenance와 함께 남긴다.

        /** OpenCV `createCLAHE(clipLimit=...)`. 타일 히스토그램 클립 한도의 배수다. */
        const val CLAHE_CLIP_LIMIT = 2.0f

        /** OpenCV `tileGridSize=(8, 8)`. 한 변의 타일 수이며 정사각 격자다. */
        const val CLAHE_TILE_GRID = 8

        /** CLAHE 결과 `L`에 씌우는 감마. `pow(L/255, gamma)`이므로 1보다 작으면 밝아진다. */
        const val CLAHE_GAMMA_VALUE = 0.75f

        const val CLAHE_CLIP_LIMIT_UNIFORM = "uClipLimit"
        const val CLAHE_TILES_UNIFORM = "uTiles"
        const val CLAHE_GAMMA_UNIFORM = "uClaheGamma"

        /** AGCWD의 가중 지수 α. `pdf_w = pdf_max * ((pdf-pdf_min)/(pdf_max-pdf_min))^α`. */
        const val AGCWD_ALPHA = 0.5f

        const val AGCWD_ALPHA_UNIFORM = "uAlpha"

        const val LAB_PROVENANCE =
            "상류(모델링 담당 kty2001/KDT_Hackathon) scripts/lowlight.py의 A1/A2 탐색 구현값이며 " +
                "**계약 확정값이 아니다** — INTERFACES.md §B-5는 여전히 전부 ☐다. " +
                "출처는 docs/research/RESEARCH_20260731_UPSTREAM.md §2-2의 파라미터 표다. " +
                "전부 uniform으로 노출했으므로 팀장이 값을 확정하면 셰이더를 고치지 않고 교체한다"

        /**
         * 🔴 **동작 채널이 계약서 제안값과 다르다.** 이 문장이 조용히 묻히면 나중에 골든
         * 이미지가 안 맞을 때 원인을 못 찾는다 — `session.json`에 그대로 나간다.
         */
        const val LAB_DEVIATION =
            "🔴 **동작 채널이 INTERFACES.md §B-2 제안값과 다르다.** 계약서 제안값은 " +
                "**YUV의 Y**(그리고 그 칸은 여전히 ☐)인데 이 이식은 상류 구현을 따라 " +
                "**CIE LAB의 L***에서 돈다. **둘은 다른 값이다** — Y는 감마 인코딩된 신호의 " +
                "선형 가중합이고 L*은 선형 휘도의 세제곱근 척도라, 같은 픽셀에서 서로 다른 " +
                "히스토그램과 서로 다른 LUT가 나온다. 상류를 따른 이유는 A1·A2의 CPU 레퍼런스가 " +
                "cv2.cvtColor(BGR2LAB)의 L에서 돌기 때문이며(RESEARCH_20260731_UPSTREAM.md §2-2), " +
                "**계약 확정이 아니라 레퍼런스 일치를 택한 것**이다. §B-2가 Y로 확정되면 " +
                "LabGlsl의 변환 하나만 바꾸면 된다(팀에 올린 항목 U-4). " +
                "그 밖에 상류와 다른 곳: " +
                "(1) **a,b를 8비트로 양자화하지 않는다** — a,b를 그대로 두는 것은 f(X/Xn)-f(Y/Yn)와 " +
                "f(Y/Yn)-f(Z/Zn)를 보존하는 것과 같아 ×500/÷500 왕복을 생략했다(대수적으로 동일, " +
                "양자화가 없어 상류보다 정밀하다). " +
                "(2) **LUT 출력을 uchar로 반올림하지 않는다**(float 그대로 보간한다). " +
                "(3) CLAHE에서 해상도가 타일 격자로 나눠떨어지지 않을 때 **OpenCV의 " +
                "BORDER_REFLECT_101 패딩 대신 실재 픽셀만 균등 분할**한다 — 720p(1280x720)는 " +
                "8로 나눠떨어져 이번 측정 프레임에서는 차이가 없다. " +
                "(4) **CLAHE 타일 보간 좌표의 원점이 반 픽셀 다르다.** 셰이더는 " +
                "p = vTexCoord*uTiles - 0.5 라 **픽셀 중심**((x+0.5)/size) 기준인데 OpenCV " +
                "CLAHE_Interpolation_Body는 x/tileSize - 0.5 라 **픽셀 좌상단** 기준이다. " +
                "독립 검증 실측 이탈: **max 1.86 LSB, 픽셀의 1.03%가 >1 LSB**(원점을 OpenCV와 " +
                "맞추면 max 0.993 LSB로 떨어진다 — 즉 나머지 이탈은 여기서 나온다). " +
                "고치지 않은 이유: 픽셀 중심이 표본 위치의 표준 정의라 **이쪽이 오히려 옳고**, " +
                "이번 라운드에서 검증이 끝난 산식을 건드리지 않는다. §B-6 골든 대조에서 " +
                "1~2 LSB급 차이가 보이면 **먼저 이 항목을 의심할 것**. " +
                "(5) **clipLimit <= 0의 의미가 정반대다.** OpenCV는 그 값을 '클립 없음'으로 " +
                "처리하는데 이 이식은 clip = max(clipI, 1)이라 **빈당 1픽셀 극단 클립**이 " +
                "된다. 지금 값이 2.0이라 이번 측정에서는 걸리지 않지만 uClipLimit은 " +
                "uniform이라 값만 바꿔도 그 경로에 들어간다 — 값을 바꾸는 사람이 알아야 한다. " +
                "⚠ 골든 이미지(§B-6)가 아직 없어 **픽셀 단위 대조는 미수행**이다. 다만 독립 " +
                "검증이 opencv-python 5.0.0 재구현과 대조해 CLAHE 0.993 LSB · AGCWD 1.7e-13 · " +
                "LAB L 1 LSB까지 맞췄다((4)의 원점을 맞춘 조건에서다)"

        /**
         * A1·A2 공통 결함. **관측 대상이지 이번 라운드에서 고칠 것이 아니다** —
         * 고치려면 채도 보정이라는 알고리즘 결정이 필요하고 그건 팀장 판단이다.
         */
        const val LAB_DESATURATION_NOTE =
            "**L만 올리고 a,b를 그대로 두면 상대 채도가 떨어져 화면이 탈색돼 보인다** — " +
                "상류 문서가 A1·A2의 공통 결함으로 지목한 것과 같은 현상이고, 이 이식도 구조가 " +
                "같으므로 그대로 재현된다(a,b는 절대 좌표라 L이 커질수록 채도비 C/L이 준다). " +
                "보정(예: L 증가분만큼 a,b 스케일)은 **상류에 없는 연산**이라 넣지 않았다 — " +
                "넣으면 상류 레퍼런스와 더 멀어지고, 색 왜곡은 INTERFACES.md §B-2가 " +
                "'저시력 사용자에게 위험 신호를 흐릴 수 있다'고 규정한 항목이라 팀장 판단이다"

        /**
         * A1·A2가 눈부심을 **구조적으로 못 누른다**는 상류 실측
         * (`RESEARCH_20260731_UPSTREAM.md` §2-5). 속도 숫자만 보고 arm을 고르면 안 되므로
         * 같은 세션 파일에 함께 싣는다.
         */
        const val LAB_GLARE_NOTE =
            "⚠ **이 arm은 눈부심을 누르지 못한다 — 속도로만 고르면 안 된다.** 상류 실측: " +
                "A1(타일 국소 적응)은 포화 소폭 감소, A2(전역)는 **포화 15배 확대**. " +
                "톤커브가 단조 증가라 어떤 입력값도 낮출 수 없는 **구조적 한계**이며 튜닝으로 " +
                "해결되지 않는다(RESEARCH_20260731_UPSTREAM.md §2-5). 대상 사용자가 광과민 " +
                "야맹증이므로 이 arm의 GPU 비용이 싸게 나오더라도 그 사실이 채택 근거가 되지 " +
                "않는다 — 같은 표에서 글레어를 실제로 누르는 것은 drago(D1) 하나다"

        /**
         * 🔴 **인용 함정 1 — 어느 열인지 밝히지 않으면 정반대 결론이 나온다.**
         * 같은 런에서 실제로 순위가 뒤집힌 것을 그대로 적는다. 세 컴퓨트 arm의
         * `how_to_compare`에 전부 실어 어느 arm의 로그를 먼저 보든 이 문장을 만나게 한다.
         */
        const val COLUMN_RANK_INVERSION_NOTE =
            "🔴 **열을 명시하지 않은 인용은 정반대 결론을 만든다.** 정식 측정(2026-08-03, " +
                "지속 런)에서 순위가 실제로 뒤집혔다: stage_d_total_ms로 보면 " +
                "clahe_gamma 5.369 < drago 5.757이라 **clahe가 싸고**, gpu_sum 차분" +
                "(arm − blit_2pass)으로 보면 clahe +7.176 > drago +6.280이라 " +
                "**clahe가 비싸다.** 둘 다 맞는 숫자이고 재는 대상이 다를 뿐이다" +
                "(전자는 D 열 3개의 합, 후자는 present 번짐까지 포함한 프레임 전체 증분). " +
                "표·보고서로 옮길 때 **열 이름을 반드시 함께 옮길 것** — 숫자만 옮기면 " +
                "읽는 사람이 반대 결론을 낸다. " +
                "⚠️ 여기 적힌 수치는 **이 문자열이 쓰인 시점의 사본**이다. 최신 값의 출처는 " +
                "FRAME_BUDGET.md §3 주3과 docs/baselines/README.md이며, 어긋나면 그쪽이 맞다"

        /**
         * 🔴 **인용 함정 2 — `gpu_sum` 차분을 '알고리즘의 비용'으로 읽으면 틀린다.**
         * 독립 검증이 낸 분해다. 이 문장이 빠지면 팀에 "A1이 알고리즘 성질상 가장 비싸다"는
         * **틀린 판정**이 전달된다 — 실제로는 A1의 통계 부분이 셋 중 가장 싸다.
         */
        const val COST_SPLIT_NOTE =
            "🔴 **gpu_sum 차분을 통째로 '그 알고리즘의 비용'으로 읽지 말 것.** " +
                "정식 측정(2026-08-03, 지속 런)의 분해(blit_2pass 대비 증분, ms): " +
                "drago = 알고리즘 고유(analyze+build) 3.482(55%) + 적용·present 증분 2.798 ; " +
                "agcwd = 2.788(46%) + 3.308 ; " +
                "clahe_gamma = **1.867(26%)** + **5.309(74%)**. " +
                "즉 **알고리즘 고유 통계 부분은 세 arm 중 clahe가 가장 싸다.** clahe 초과분의 " +
                "74%는 적용 패스이고 그 지배 항은 **A1·A2가 공유하는 LAB 변환(pow 9회)**이다. " +
                "그러므로 'A1이 알고리즘 성질상 가장 비싸다'는 결론은 이 데이터에서 나오지 " +
                "않으며, 경량화 레버도 통계 쪽이 아니라 **LAB 변환 쪽**을 봐야 한다. " +
                "⚠️ 고유부는 p50(analyze)+p50(build)라 **백분위의 합**이고 행별 합보다 " +
                "0.04ms쯤 작다 — 대소 비교에만 쓰고 D칸에 더하지 말 것. " +
                "⚠️ drago 행은 08-01 세션(분모도 08-01)이고 나머지 둘은 08-03이다. " +
                "여기 적힌 수치는 **이 문자열이 쓰인 시점의 사본**이며, 최신 값의 출처는 " +
                "FRAME_BUDGET.md §3 주3과 docs/baselines/README.md다"

        /**
         * 독립 검증이 지목했지만 **이번 라운드에서 일부러 당기지 않은** 레버.
         * 지금 최적화하면 이번 라운드 실측을 전부 다시 재야 하고, arm 판정은 속도가 아니라
         * 눈부심으로 갈린다([LAB_GLARE_NOTE]). 예산이 급해질 때 후보가 되도록 기록만 남긴다.
         */
        const val LAB_LEVERS_NOT_PULLED =
            "확인됐지만 **당기지 않은** 레버 3개(독립 검증 지목, 이번 라운드 미적용): " +
                "(1) **sRGB 텍스처 포맷** — FBO를 sRGB로 두면 셰이더의 srgbToLinear/" +
                "linearToSrgb를 샘플러·ROP가 대신한다(위 비용 분해의 지배 항이 그 pow다). " +
                "(2) **타일 LUT를 SSBO 대신 텍스처로** — 적용 패스의 LUT 랜덤 읽기가 텍스처 " +
                "캐시와 이중선형 보간 하드웨어를 탄다. " +
                "(3) **mediump** — LAB 경로가 전부 highp다. " +
                "당기지 않은 이유: 게이트(FRAME_BUDGET.md §1)가 걸리지 않았고, 지금 건드리면 " +
                "이번 라운드의 실측을 **전부 다시 재야 하며**, arm 판정은 속도가 아니라 " +
                "눈부심으로 갈린다(glare_note). 예산이 급해지면 FRAME_BUDGET.md §5 레버 " +
                "목록의 후보가 여기 있다"

        /**
         * ②의 GPU 비용을 arm끼리 비교하는 **유일하게 맞는 방법**. `drago`에서 실측으로
         * 확인한 것을 그대로 적용한다 — 같은 문장이 `stage2_params.how_to_compare`로 나간다.
         */
        const val LAB_HOW_TO_COMPARE =
            "🔴 **stage_d_total_ms만 인용하지 말 것 — ② 증분을 과소로 낸다.** " +
                "상류 CPU 실측(720p A1 5.3ms / A2 6.2ms)과 비교할 숫자는 **gpu_sum_ms의 " +
                "arm 간 차분**이다(이 arm − blit_2pass). 근거: 세 arm에서 **글자 그대로 같은 " +
                "코드인** gpu_present_ms가 blit_2pass 1.881 → drago 3.582ms로 움직였다 — " +
                "② 증분의 약 27%가 D 열이 아니라 present에 앉는다. " +
                "⚠ **그 차분도 하한이다** — 마지막 패스의 타일 해결이 eglSwapBuffers에서 " +
                "일어나 모든 query의 바깥이다(gpu_timer.attribution_note). " +
                "⚠ 그리고 상류 5.3/6.2ms는 PC CPU/NumPy 기준이라 조건이 다르다 — " +
                "나란히 놓을 때 그 사실을 함께 옮길 것. " +
                COLUMN_RANK_INVERSION_NOTE

        /**
         * Drago와 같은 이유의 관측 대상. A1은 타일별, A2는 전역이라 **출렁이는 방식이 다르다** —
         * 그 차이 자체가 이번 라운드의 관측 항목이다.
         */
        const val LAB_FLICKER_NOTE =
            "히스토그램을 **매 프레임 새로** 구한다(stateless). 장면이 바뀌면 LUT가 프레임마다 " +
                "흔들려 화면이 출렁일 수 있고, **A1은 타일 단위로 A2는 화면 전체가** 흔들려 " +
                "성질이 다르다. INTERFACES.md §B-4(프레임 간 상태)가 ☐라 시간축 평활을 임의로 " +
                "넣지 않았다 — 상류 A3가 바로 그 IIR 평활인데 리셋 조건·수렴 시간이 미정의다. " +
                "이 라운드에서는 **관측 대상**이며, 관측 결과는 보고에 남긴다"

        // ── ② 조합 arm(D1 → A1 체인) ──────────────────────────────────────
        // 파라미터는 **위 두 스테이지의 상수를 그대로 재사용한다.** 조합용으로 따로 잡으면
        // 값이 갈라지는 순간 "단품과 같은 설정으로 이었다"는 전제가 조용히 깨진다.

        const val CHAIN_PROVENANCE =
            "상류(모델링 담당 kty2001/KDT_Hackathon) scripts/lowlight.py의 D1·A1 탐색 구현값을 " +
                "**그대로** 직렬로 이은 것이며 **계약 확정값이 아니다** — INTERFACES.md §B-5는 " +
                "여전히 전부 ☐다. 두 스테이지의 파라미터는 단품 arm(drago / clahe_gamma)과 " +
                "같은 상수를 쓰고 전부 uniform으로 노출했으므로, 팀장이 값을 확정하면 " +
                "셰이더를 고치지 않고 교체한다. 조합의 출처는 " +
                "docs/research/RESEARCH_20260803_UPSTREAM.md §6(잠정 1위 D1A1+bf)이다"

        /**
         * 🔴 **조합 고유의 이탈.** 단품 두 arm의 이탈([DRAGO_DEVIATION]·[LAB_DEVIATION])은
         * 그대로 성립하고 **그 위에 하나가 더 붙는다.** 이 문장이 빠지면 나중에 상류와 결과가
         * 다를 때 "두 단품은 맞췄는데 왜 다른가"에서 막힌다.
         */
        const val CHAIN_DEVIATION =
            "🔴 **상류의 조합 합성 방식이 이 저장소에 기록돼 있지 않다.** 기록된 것은 순서" +
                "(Drago → CLAHE → bilateral)뿐이고, **중간 dtype · 클리핑 · 정규화 유무는 " +
                "어디에도 없다**(docs/research/RESEARCH_20260803_UPSTREAM.md §6). " +
                "이 이식이 중간 표현을 **RGBA8 FBO**로 둔 것은 cv2 파이프라인의 통상 동작" +
                "(톤맵 결과를 8비트 이미지로 내고 그것을 cvtColor(BGR2LAB)에 넣는다)에서 온 " +
                "**추론이지 상류 코드 인용이 아니다.** 그러므로 다음 두 가지가 상류와 다를 수 " +
                "있고 우리는 어느 쪽인지 모른다: (1) 중간값의 **8비트 양자화**(상류가 float로 " +
                "이었다면 없다), (2) Drago 출력의 **0..1 클리핑**(이 이식은 clamp한다). " +
                "두 스테이지 각각의 이탈은 upstream_deviation_drago / upstream_deviation_lab에 " +
                "그대로 실었으며 **조합에서도 전부 그대로 성립한다.** " +
                "⚠ 이번 라운드는 **체인(상류 충실)**이다 — 중간 materialize를 없애는 융합은 " +
                "알고리즘 변경이라 팀장 판단 영역이고 여기서 하지 않았다"

        /**
         * 🔴 [LAB_GLARE_NOTE]를 **재사용하면 안 된다.** 그 문장은 "이 arm은 눈부심을 누르지
         * 못한다"인데 조합은 D1을 포함해 글레어를 누른다 — 그대로 두면 **거짓 문장이 로그로
         * 나간다.** 대신 이 arm의 진짜 위험(표시 경로 전용 후보라는 사실)을 담는다.
         */
        const val CHAIN_GLARE_NOTE =
            "⚠ **이 arm(`D1A1`)은 상류 표시 1위 후보의 *부분집합*이다 — 1위는 `D1A1+bf`이고 " +
                "여기에는 `bf`(bilateral)가 없다.** 표시 3축(글레어·대비·노이즈) 1위로 상류가 " +
                "적은 것은 `D1A1+bf`이지 `D1A1`이 아니다(RESEARCH_20260803_UPSTREAM.md §6, §2). " +
                "그러니 이 arm의 결과를 '표시 1위 후보를 쟀다'로 옮기지 말 것. " +
                "그럼에도 **이 arm은 표시(④ 화면) 경로 후보이지 탐지(③) 입력 후보가 아니다.** " +
                "A1·A2 단품과 달리 이 조합은 D1을 포함하므로 글레어를 실제로 누른다 — " +
                "LAB_GLARE_NOTE('눈부심을 누르지 못한다')는 **이 arm에 해당하지 않는다.** " +
                "대신 상류 실측(§2, C7)이 다른 값을 매긴다: **arm이 강할수록 탐지의 `stairs` " +
                "오탐이 오른다**(무처리 0.1% → A1 0.4% → A1+bf 1.1% → D1A1 3.8% → " +
                "D1A1+bf 5.7%). 오인 대상이 횡단보도·차선·차 지붕·포장 텍스처라 저시력 " +
                "보행자가 반드시 지나는 곳이다. 그래서 상류 판정은 **탐지=원본 / " +
                "표시=D1A1+bf(+ts)의 경로 분리**이며(§3, 🗣️ 팀 추인 대기), 이 arm의 GPU " +
                "비용이 싸게 나오더라도 그것이 ③ 입력으로 쓸 근거가 되지는 않는다"

        /**
         * [DRAGO_FLICKER_NOTE]·[LAB_FLICKER_NOTE]와 같은 부류지만 **성질이 다르다** —
         * 통계가 두 벌이고 앞 스테이지의 출렁임이 뒤 스테이지 입력을 흔든다.
         */
        const val CHAIN_FLICKER_NOTE =
            "통계를 **두 벌** 매 프레임 새로 구한다(stateless) — drago의 **전역** 통계와 " +
                "clahe의 **타일별** 히스토그램. 그래서 출렁임의 성질이 단품과 다르다: " +
                "앞 스테이지(D1)의 전역 톤커브가 흔들리면 그 출력이 뒤 스테이지(A1)의 " +
                "**입력 분포**를 통째로 밀어 타일 LUT까지 함께 흔든다(단품에서는 없던 " +
                "연쇄다). INTERFACES.md §B-4(프레임 간 상태)가 ☐라 시간축 평활을 임의로 넣지 " +
                "않았다 — 상류 `+ts`가 바로 그 평활이고 실측은 성공했지만(§7) 리셋 조건· " +
                "수렴 시간이 여전히 미정의다. 이 라운드에서는 **관측 대상**이며, 관측 결과는 " +
                "보고에 남긴다"

        /**
         * [LAB_LEVERS_NOT_PULLED] 3개에 **조합에서만 생기는 3개**를 더한다.
         * 첫째가 이번 측정의 존재 이유라 특히 중요하다 — 당기면 재는 의미가 없어진다.
         */
        const val CHAIN_LEVERS_NOT_PULLED =
            LAB_LEVERS_NOT_PULLED +
                " ── 조합에서 추가로 **당기지 않은** 레버 3개: " +
                "(4) **처리 해상도 640×360 축소** — 상류가 검토 중인 레버지만 " +
                "(RESEARCH_20260803_UPSTREAM.md §6: CPU 720p 101.2ms / 640×360 33.1ms) " +
                "**이번 측정의 목적이 그 레버가 불필요함을 보이는 것**이라 당기면 재는 의미가 " +
                "사라진다. 게다가 상류 실측상 글레어 지표가 해상도에 크게 의존한다(D1 광원 " +
                "코어 원본 10 / 720p 151 / 640 158 / 480 161) — 해상도는 속도만의 문제가 " +
                "아니라 표시 품질 자체를 바꾼다. " +
                "(5) **float/half FBO로 선형 중간값 보존** — 중간 RGBA8 왕복(8비트 양자화 + " +
                "0..1 클리핑)이 없어진다. 당기지 않은 이유는 두 가지다: 이번 라운드가 " +
                "**상류 충실(체인)**이고, float 포맷은 기기 의존성을 늘린다(FBO를 RGBA8로만 " +
                "쓰는 기존 규약). " +
                "(6) **`bf`·`ts` 미포함** — 상류 잠정 1위는 `D1A1+bf(+ts)`이고 이 arm은 " +
                "`D1A1`까지다. `bf`는 그 뒤 **별 arm으로 실제로 쟀다**" +
                "(`drago_clahe_chain_bf` 9패스. GpuTimerRing.MAX_PASS_COUNT를 8→12로 올려 " +
                "슬롯을 만들었으므로 '슬롯을 넘는다'는 더 이상 이유가 아니다). " +
                "**이 arm에는 여전히 bf가 없으므로** 여기 숫자는 `D1A1`의 값으로만 읽어야 " +
                "한다. `ts`는 양쪽 arm 모두 없다 — INTERFACES.md §B-4가 ☐다"

        /**
         * 조합용 비교 지침. 단품과 같은 함정을 그대로 밟으므로 [LAB_HOW_TO_COMPARE]를
         * **재사용**하고 조합에서만 생기는 함정 하나를 앞에 붙인다.
         */
        const val CHAIN_HOW_TO_COMPARE =
            "🔴 **단품 arm의 숫자를 더해 이 arm의 값을 갈음하지 말 것.** 그 합이 맞는지가 " +
                "이 측정의 질문이다 — 두 스테이지가 무엇을 공유하고 무엇을 두 번 하는지는 " +
                "stage2_params.color_transform_sites(자동 계수)와 그 아래 선언 블록에 " +
                "적어 두었으니 **인용은 그 값으로** 할 것. " +
                "상류 CPU 실측과 나란히 놓을 때: 상류 D1A1+bf 720p 101.2ms는 (a) PC CPU/" +
                "NumPy 기준이고 (b) **`bf`를 포함**하는데 이 arm은 `D1A1`까지다 — 두 사실을 " +
                "함께 옮길 것. " + LAB_HOW_TO_COMPARE

        // ── 색공간 변환 횟수 **선언값** ────────────────────────────────────
        // 자동 계수(color_transform_sites)와 **다른 층**이다. 자동 계수는 셰이더 텍스트에서
        // 기계가 센 정적 호출 지점 수이고, 아래는 사람이 픽셀당·프레임당으로 환산한 값이다.
        // 🔴 어긋나면 **자동 계수가 맞다**(아래 provenance에 같은 문장이 나간다).

        /** 이 arm의 총 패스 수 = [gpuColumns]의 개수 = GPU timer 슬롯 수. */
        const val CHAIN_PASSES_TOTAL = 8

        /**
         * 그중 **픽셀마다 도는** 패스 수. 패스3(drago build, 스레드 1개)과 패스6(clahe build,
         * 빈 하나에 스레드 하나)은 픽셀 루프가 아니라 여기서 빠진다.
         */
        const val CHAIN_FULLSCREEN_PASSES = 6

        /** LabGlsl의 **piecewise** `srgbToLinear` 호출 수(패스5 1 + 패스7 1). */
        const val CHAIN_SRGB_TO_LINEAR_PER_PIXEL = 2

        /** `labF` 정방향 평가 수(패스5의 srgbToLabL 1 + 패스7의 srgbToLabF 3). */
        const val CHAIN_LAB_F_FORWARD_PER_PIXEL = 4

        /** `labFInv` 역방향 평가 수(패스7의 labFToSrgb 3). */
        const val CHAIN_LAB_F_INVERSE_PER_PIXEL = 3

        /** LabGlsl의 `linearToSrgb` 호출 수(패스7의 labFToSrgb 1). */
        const val CHAIN_LINEAR_TO_SRGB_PER_PIXEL = 1

        /** Drago 톤커브 평가 수. 패스4의 적용 1회이며 패스2(통계)는 곡선을 평가하지 않는다. */
        const val CHAIN_DRAGO_TONEMAP_EVALS_PER_PIXEL = 1

        /**
         * Drago의 `pow(x, uSrcGamma)` 선형화 수(패스2 1 + 패스4 1).
         * ⚠ 위 [CHAIN_SRGB_TO_LINEAR_PER_PIXEL]과 **다른 것**이다 — 이쪽은 단순 거듭제곱이고
         * 저쪽은 sRGB piecewise 함수다. 한 칸에 합치면 두 스테이지가 같은 변환을 쓴다는
         * 오해가 생긴다(실제로 D1은 LabGlsl을 한 번도 쓰지 않는다).
         */
        const val CHAIN_DRAGO_POW_LINEARIZE_PER_PIXEL = 2

        /**
         * Drago 출력의 되씌우기 `pow(x, 1/uOutGamma)` 수. 체인은 패스4에서 **중간 이미지를
         * 인코드**하느라 1회 쓴다. 융합은 그 중간이 없어 **0**이다 —
         * [FUSED_DEVIATION]의 (b)를 정량으로 만드는 칸이다.
         */
        const val CHAIN_DRAGO_OUT_GAMMA_ENCODE_PER_PIXEL = 1

        /**
         * 중간 RGBA8 이미지로 **떨어뜨리는** 횟수(패스1 FBO_A, 패스4 FBO_B, 패스7 FBO_A).
         * 기본 프레임버퍼로 가는 패스8은 중간이 아니라 세지 않는다. 3패스 골격·단품 컴퓨트
         * arm은 2회이므로 **조합이 1회를 더 쓴다** — 융합 라운드가 지울 대상이 이것이다.
         */
        const val CHAIN_INTERMEDIATE_RGBA8_MATERIALIZATIONS = 3

        const val CHAIN_COLOR_TRANSFORM_DECLARED_PROVENANCE =
            "**셰이더 소스를 읽어 사람이 센 값이며 측정이 아니다.** " +
                "🔴 **두 층을 대조하는 방법:** 자동 계수(color_transform_sites)의 " +
                "**entry_point_tokens**와 어긋나면 **자동 계수가 맞다** — 그쪽은 " +
                "glShaderSource에 넘긴 문자열 자체를 세고 그 토큰들은 main()에서만 불리므로 " +
                "셰이더 텍스트와 어긋날 수 없다. " +
                "⚠ **inner_tokens와는 대조하지 말 것** — 그쪽은 LabGlsl.FUNCTIONS가 통째로 " +
                "삽입되면서 **죽은 본문까지 세므로 상한**이다(예: 융합의 srgbToLinear는 " +
                "자동 계수 2 · 실제 실행 0). 픽셀당 실행 횟수는 **이 블록이 진입점에서 호출 " +
                "그래프를 따라간 값**이며 그 목적에는 이쪽이 맞다. " +
                "단위: per_pixel은 **처리 해상도의 픽셀 하나당 프레임당** 횟수다(마지막 " +
                "present 패스는 surface 해상도라 픽셀 수가 다르지만 색공간 변환이 없어 여기 " +
                "기여가 0이다). 체인과 융합의 같은 칸을 나란히 놓으면 '변환 몇 회를 줄였는가'가 " +
                "바로 읽힌다 — gpu_sum 차분을 그 감소에 귀속시킬 때 쓰는 표다"

        // ── ② 융합 arm(D1 ⊕ A1) ───────────────────────────────────────────
        // 파라미터는 체인과 **같은 상수**를 쓴다. 값이 갈라지면 두 arm의 차이가 "융합했기
        // 때문"인지 "설정이 달라서"인지 구분할 수 없게 된다.

        const val FUSED_PROVENANCE =
            CHAIN_PROVENANCE +
                " ⚠ 다만 **융합은 상류에 없는 구성이다** — 상류가 기록한 것은 순서" +
                "(Drago → CLAHE → bilateral)뿐이고 중간 표현을 어떻게 다뤘는지는 없다. " +
                "그러므로 이 arm은 '상류 구현을 옮긴 것'이 아니라 **우리가 만든 변형**이며, " +
                "파라미터만 상류 값을 그대로 쓴다. 채택 여부는 팀장/팀 판단이다"

        /**
         * 🔴 **융합 고유의 신규 이탈 3건.** 체인의 이탈([CHAIN_DEVIATION])과 단품 두 arm의
         * 이탈은 그 위에 **그대로 성립한다.**
         */
        const val FUSED_DEVIATION =
            "🔴 **이 arm은 이식 최적화가 아니라 알고리즘 변경이다.** 체인 대비 신규 이탈 3건: " +
                "**(a) 중간 uint8/RGBA8 materialize 제거** — Drago 출력을 8비트 이미지로 " +
                "떨어뜨리지 않으므로 **양자화가 사라지고**, 그 write가 하던 **0..1 클리핑도 " +
                "함께 사라진다**(톤맵 결과가 1을 넘으면 체인은 잘랐지만 융합은 그대로 LAB로 " +
                "간다). cv2 파이프라인이 하던 바로 그 왕복이라, 없애면 상류 구조에서 멀어진다. " +
                "**(b) `pow(x, 1/2.2)` 인코드 + sRGB piecewise(지수 2.4) 디코드 왕복 제거** — " +
                "🔴 **두 곡선이 다르므로 상쇄가 아니다.** 체인에서 CLAHE가 실제로 보던 값은 " +
                "`srgbDecode(pow(tone, 1/2.2))`(대략 tone^(2.4/2.2), 게다가 저휘도에서는 " +
                "선형 구간이 섞인다)인데 융합은 `tone` 자체를 본다 — **히스토그램도 LUT도 " +
                "다른 분포에서 나온다.** 그래서 `uOutGamma`(DRAGO_GAMMA)는 이 arm에서 " +
                "**적용되지 않는다**(값은 대조용으로 남겨 둔다). " +
                "**(c) Drago 톤맵을 픽셀당 2회 평가한다** — 융합 analyze와 융합 apply가 각각 " +
                "다시 계산한다(중간 이미지가 없으니 다시 계산하는 수밖에 없다). 즉 융합은 " +
                "'패스 1개 + FBO 왕복 + pow 왕복'을 '톤맵 1회 추가'와 **교환**하는 것이다. " +
                "어느 쪽이 이기는지는 측정 대상이며 여기에 예상치를 적지 않는다. " +
                "🗣️ **채택 여부는 팀장/팀 판단이다** — KICKOFF_ROLES.md가 알고리즘 설계를 " +
                "팀장 영역으로 두고, DRAGO_DEVIATION의 '상류를 픽셀 단위로 재현할 것인가'도 " +
                "같은 이유로 팀장 판단으로 올라가 있다(FRAME_BUDGET.md §5 레버 3)"

        const val FUSED_GLARE_NOTE =
            CHAIN_GLARE_NOTE +
                " ⚠ **게다가 이 arm은 융합이라 상류와 곡선 자체가 다르다**" +
                "(upstream_deviation (a)(b)). 위 상류 3축·오탐 수치는 체인보다도 " +
                "**더 간접적인** 근거다 — 이 arm의 표시 품질·탐지 영향은 우리 출력으로 " +
                "다시 재야 말할 수 있다(scripts/metrics.py의 3축 지표가 그 자다)"

        const val FUSED_FLICKER_NOTE =
            CHAIN_FLICKER_NOTE +
                " ⚠ **융합에서는 그 연쇄가 더 직접적이다** — 중간 이미지가 없어 drago 통계가 " +
                "타일 히스토그램을 **같은 프레임 안에서 바로** 민다(체인은 8비트로 한 번 " +
                "떨어졌다가 다시 읽혔다). 출렁임의 크기가 체인과 다를 수 있고, 그 차이 자체가 " +
                "관측 항목이다"

        const val FUSED_LEVERS_NOT_PULLED =
            CHAIN_LEVERS_NOT_PULLED +
                " ── 융합에서 추가로 **당기지 않은** 레버 2개: " +
                "(7) **적용 결과를 화면에 바로 그리기** — 지금은 FBO_B에 쓰고 present가 " +
                "복사한다. 바로 그리면 패스 하나가 줄지만 gpu_present_ms 열이 사라져 다른 " +
                "arm과 열 구조가 달라지고(비교가 끊긴다) 처리 해상도 ≠ surface 해상도라 " +
                "스케일링이 적용 패스에 섞인다. " +
                "(8) **톤맵 2회 평가를 1회로 줄이기** — 중간 결과를 float FBO에 남기면 되지만 " +
                "그건 **다시 materialize**라 융합의 정의에 어긋난다(그 구성은 별도 arm이지 " +
                "이 arm의 최적화가 아니다)"

        /**
         * 🔴 상류 CPU 숫자 옆에 놓을 수 있는 것은 **체인**이지 융합이 아니다.
         * 융합의 의미는 **체인 대비 절감**이다.
         */
        const val FUSED_HOW_TO_COMPARE =
            "🔴 **상류 CPU 실측(D1A1+bf 720p 101.2ms) 옆에 이 arm의 값을 놓지 말 것.** " +
                "그 자리에 놓을 수 있는 것은 **체인(`drago_clahe_chain`)**이다 — 체인은 상류 " +
                "cv2 구조를 그대로 옮긴 것이고 융합은 **다른 알고리즘**이다" +
                "(upstream_deviation). 융합 값을 상류 옆에 놓으면 서로 다른 두 알고리즘을 " +
                "비교하는 것이 된다. " +
                "🔴 **이 arm의 의미는 하나다: 체인 대비 절감.** 비교할 짝은 같은 조건에서 잰 " +
                "`drago_clahe_chain` 런이고, 비교할 지표는 **gpu_sum_ms의 arm 간 차분**이다" +
                "(stage_d_total_ms만 보면 과소가 된다 — 아래 이유). 절감의 귀속은 두 arm의 " +
                "color_transform_declared / color_transform_sites를 나란히 놓고 '변환 몇 회를 " +
                "줄였는가'로 한다. " +
                "⚠ 두 arm은 **패스 수도 다르다**(체인 8 / 융합 7). 열 이름이 같아도 담기는 " +
                "패스가 다르므로 **열 단위 대조는 성립하지 않는다** — 특히 " +
                "stage_d_apply_ms는 체인에서 drago 적용, 융합에서 융합 적용이다. " +
                LAB_HOW_TO_COMPARE

        // ── 융합의 색공간 변환 **선언값** ──────────────────────────────────
        // 체인의 같은 칸과 나란히 읽으라고 만든 표다. 값의 성격은 체인과 같다
        // (사람이 센 값이며 측정이 아니다 — [CHAIN_COLOR_TRANSFORM_DECLARED_PROVENANCE]).

        const val FUSED_PASSES_TOTAL = 7

        /** 패스3(스레드 1개)과 패스5(빈 하나에 스레드 하나)는 픽셀 루프가 아니라 빠진다. */
        const val FUSED_FULLSCREEN_PASSES = 5

        /**
         * 🔴 **0이다.** 융합은 LabGlsl의 piecewise `srgbToLinear`를 한 번도 부르지 않는다 —
         * 톤맵 결과가 이미 선형이라 [LabGlsl.LINEAR_INPUT_FUNCTIONS]로 바로 들어간다.
         * 체인은 2였다(이탈 (b)의 정량).
         */
        const val FUSED_SRGB_TO_LINEAR_PER_PIXEL = 0

        /** 패스4의 linearToLabL 1 + 패스6의 linearToLabF 3. 체인과 같다. */
        const val FUSED_LAB_F_FORWARD_PER_PIXEL = 4

        /** 패스6의 labFToSrgb 3. 체인과 같다. */
        const val FUSED_LAB_F_INVERSE_PER_PIXEL = 3

        /** 패스6의 labFToSrgb 1. 체인과 같다 — 최종 표시 인코딩은 바꾸지 않았다. */
        const val FUSED_LINEAR_TO_SRGB_PER_PIXEL = 1

        /** 🔴 **2다**(융합 analyze + 융합 apply). 체인은 1이었다 — 이탈 (c)의 정량. */
        const val FUSED_DRAGO_TONEMAP_EVALS_PER_PIXEL = 2

        /** 패스2 1 + 패스4 1 + 패스6 1 = 3. 체인은 2였다. */
        const val FUSED_DRAGO_POW_LINEARIZE_PER_PIXEL = 3

        /** 🔴 **0이다.** 중간 이미지가 없어 인코드할 곳이 없다 — 이탈 (b)의 정량. */
        const val FUSED_DRAGO_OUT_GAMMA_ENCODE_PER_PIXEL = 0

        /** 패스1 FBO_A + 패스6 FBO_B = **2**. 체인은 3이었다 — 이탈 (a)의 정량. */
        const val FUSED_INTERMEDIATE_RGBA8_MATERIALIZATIONS = 2

        // ── `+bf` bilateral 파라미터 ──────────────────────────────────────
        // 상류(모델링 담당) `scripts/lowlight.py`의 `+bf`:
        //   Bilateral(d=7, sigma_color=50, sigma_space=50)   동작 공간 **BGR**
        // 출처는 docs/research/RESEARCH_20260731_UPSTREAM.md §2-2의 파라미터 표(`:96`)다.
        // ⚠ **팀장이 준 계약 답변이 아니다.** INTERFACES.md §B-5는 여전히 전부 ☐다.
        //   그래서 상수로 박지 않고 전부 uniform으로 노출한다.

        /** OpenCV `bilateralFilter(d=...)`. 상류가 *"720p 실시간을 노리면 `d<=7`"* 이라 했다. */
        const val BF_D = 7

        /**
         * 실제 반경. OpenCV `bilateralFilter_8u`는 `d > 0`이면 `radius = d/2`(**정수
         * 나눗셈**)로 잡고 `sigma_space`는 반경 결정에 쓰지 않는다 — 그 규칙을 그대로 옮겼다.
         * 그래서 이 값은 자유 파라미터가 아니라 [BF_D]의 **유도값**이다.
         */
        const val BF_RADIUS = BF_D / 2

        /**
         * 원형 이웃의 탭 수. OpenCV는 `i*i + j*j <= radius*radius`인 탭만 쓴다 —
         * `i,j ∈ [-3,3]`에서 i=0이 7개, i=±1이 5개씩, i=±2가 5개씩, i=±3이 1개씩 = **29**.
         * **7×7 사각형 49탭이 아니다.**
         *
         * ⚠ 서술용 상수다. 셰이더는 조건식으로 걸러 실제 탭 수를 스스로 맞추므로,
         * [BF_RADIUS]가 바뀌면 셰이더는 따라가지만 **이 값은 손으로 다시 세야 한다.**
         */
        const val BF_TAP_COUNT = 29

        /** 색 거리(3채널 L1 합)의 σ. **0..255 단위**다 — 셰이더가 L1 합을 255배해서 비교한다. */
        const val BF_SIGMA_COLOR = 50f

        /** 공간 거리의 σ. **픽셀 단위**다. */
        const val BF_SIGMA_SPACE = 50f

        const val BF_RADIUS_UNIFORM = "uBfRadius"
        const val BF_SIGMA_COLOR_UNIFORM = "uBfSigmaColor"
        const val BF_SIGMA_SPACE_UNIFORM = "uBfSigmaSpace"

        /** 1/처리해상도. 해상도를 셰이더에 하드코딩하지 않기 위한 uniform이다. */
        const val BF_TEXEL_UNIFORM = "uTexel"

        const val BF_PROVENANCE =
            "상류(모델링 담당 kty2001/KDT_Hackathon) scripts/lowlight.py의 `+bf` 탐색 구현값" +
                "(Bilateral d=7, sigma_color=50, sigma_space=50, 동작 공간 BGR)이며 " +
                "**계약 확정값이 아니다** — INTERFACES.md §B-5는 여전히 전부 ☐다. " +
                "출처는 docs/research/RESEARCH_20260731_UPSTREAM.md §2-2의 파라미터 표다. " +
                "radius는 자유 파라미터가 아니라 d의 유도값이다(radius = d/2 = $BF_RADIUS, " +
                "정수 나눗셈 — OpenCV bilateralFilter_8u의 규칙). 전부 uniform으로 " +
                "노출했으므로 팀장이 값을 확정하면 셰이더를 고치지 않고 교체한다"

        /**
         * 🔴 **bf 고유의 이탈.** [CHAIN_DEVIATION]이 중간 dtype에 대해 한 것과 같은 처리다 —
         * **상류 문서에 `cv2.bilateralFilter`의 내부 정의가 없어서** OpenCV 구현을 읽어
         * 옮겼다는 사실을 여기서 밝힌다. 이 문장이 빠지면 나중에 결과가 다를 때
         * "파라미터는 맞췄는데 왜 다른가"에서 막힌다.
         */
        const val BF_DEVIATION =
            "🔴 **상류 문서에 `cv2.bilateralFilter`의 내부 정의가 기록돼 있지 않다.** " +
                "기록된 것은 `Bilateral(d=7, σc=50, σs=50)`과 동작 공간이 BGR이라는 사실뿐이다" +
                "(RESEARCH_20260731_UPSTREAM.md §2-2). 그러므로 다음 넷은 **OpenCV " +
                "구현(bilateralFilter_8u)을 읽어 옮긴 것이지 상류 코드 인용이 아니다**: " +
                "(1) 색 거리를 **3채널 L1 합**으로 보고 그것을 제곱한다" +
                "(w_color = exp(-(|Δr|+|Δg|+|Δb|)² / (2σc²)) — OpenCV가 " +
                "color_weight[|Δb|+|Δg|+|Δr|]를 미리 굽는 방식이며 **채널별 분리가 아니다**), " +
                "(2) **0..255 스케일** — OpenCV는 uchar에서 도므로 셰이더의 0..1 L1 합에 255를 " +
                "곱해 σc=50과 같은 단위로 만든다, " +
                "(3) **원형 이웃 ${BF_TAP_COUNT}탭**(i²+j² ≤ radius². 7×7 사각형 49탭이 아니다), " +
                "(4) **radius = d/2**(정수 나눗셈. σs는 반경을 정하지 않는다). " +
                "그 밖에 상류와 다른 곳: " +
                "**(a) 경계 처리가 다르다** — OpenCV 기본은 BORDER_REFLECT_101인데 이 이식은 " +
                "샘플러의 CLAMP_TO_EDGE다(테두리 radius=$BF_RADIUS 폭에서만 다르다). " +
                "CLAHE의 패딩 이탈(upstream_deviation_lab의 (3))과 **같은 부류**다. " +
                "**(b) 관찰: σs=50이면 공간 가중이 사실상 평탄하다** — 반경 $BF_RADIUS 안에서 " +
                "exp(0)=1 ~ exp(-9/5000)=0.9982라, 이 설정에서 필터를 지배하는 것은 " +
                "**range 가중 하나**다(공간 가중은 거의 상수배로 약분된다). 상류 값이 그러니 " +
                "그대로 썼다 — **레버가 아니라 사실의 기록**이다. " +
                "**(c) 골든 이미지(INTERFACES.md §B-6)가 없어 픽셀 단위 대조는 미수행**이다. " +
                "🔴 그러므로 **이 라운드의 결과는 비용만 말한다** — 화질·글레어·노이즈 지표는 " +
                "이 arm의 출력으로 다시 재야 한다"

        /**
         * bf에서만 생기는 레버 4개. 번호를 `(bf-n)`으로 매긴 이유: 체인의 레버는 (1)~(6),
         * 융합이 (7)(8)까지 쓰므로 숫자를 이어 붙이면 chain_bf에서 7·8이 빈 채로 9부터
         * 시작해 **번호가 거짓말을 한다.**
         *
         * 둘째가 특히 중요하다 — 그것을 당기면 이 라운드가 '상류 충실 이식'이 아니게 된다.
         */
        const val BF_LEVERS_SUFFIX =
            " ── bf에서 추가로 **당기지 않은** 레버 4개: " +
                "(bf-1) **`d`를 7보다 줄이기** — 상류가 말한 것은 `d<=7`을 **유지**하라는 " +
                "것이고 7 미만으로 내리는 것은 값 변경이다(탭 수와 결과가 함께 바뀐다). " +
                "(bf-2) **분리형 2패스 근사** — 🔴 bilateral은 **분리 불가능한 필터**라 2패스는 " +
                "근사이고, 그것은 융합과 **같은 부류의 알고리즘 변경**이다(팀장 판단 영역, " +
                "FRAME_BUDGET.md §5 레버 3). 그래서 당기지 않았다 — " +
                "**이번 라운드는 상류 충실 1패스다.** " +
                "(bf-3) **다운샘플 후 적용** — 상류에 없는 연산이다. " +
                "(bf-4) **원형 이웃을 사각형으로 넓히기** — 탭이 ${BF_TAP_COUNT}→49로 늘고 " +
                "OpenCV와 달라진다(빠르지도 않다)"

        /** 체인+bf의 레버 목록. 체인의 (1)~(6)이 **그대로 성립하고** 그 위에 bf 4개가 붙는다. */
        const val CHAIN_BF_LEVERS_NOT_PULLED = CHAIN_LEVERS_NOT_PULLED + BF_LEVERS_SUFFIX

        /** 융합+bf의 레버 목록. 융합의 (7)(8)까지 성립하므로 그 목록에 붙인다. */
        const val FUSED_BF_LEVERS_NOT_PULLED = FUSED_LEVERS_NOT_PULLED + BF_LEVERS_SUFFIX

        /**
         * 🔴 [CHAIN_GLARE_NOTE]를 **그대로 재사용하면 안 된다.** 그 문장은 "이 arm은 상류
         * 표시 1위 후보의 **부분집합**이고 `bf`가 없다"인데 이제 `bf`가 있다 — 그대로 두면
         * **거짓이 로그로 나간다.** 남는 사실만 담는다.
         */
        const val BF_GLARE_NOTE =
            "⚠ **`bf`가 붙었지만 여전히 상류 표시 1위 후보 전체가 아니다** — 상류 잠정 1위는 " +
                "`D1A1+bf+ts`이고 이 arm에는 `ts`(시간축 평활)가 없다" +
                "(INTERFACES.md §B-4가 ☐라 임의로 넣지 않았다. " +
                "RESEARCH_20260803_UPSTREAM.md §6·§7). " +
                "🔴 그리고 **이 arm은 표시(④ 화면) 경로 후보이지 탐지(③) 입력 후보가 아니다.** " +
                "상류 실측(§2, C7)에서 **arm이 강할수록 탐지의 `stairs` 오탐이 오른다**: " +
                "무처리 0.1% → A1 0.4% → A1+bf 1.1% → D1A1 3.8% → **D1A1+bf 5.7%**. " +
                "즉 `bf`를 더한 이 구성이 그 표에서 **오탐이 가장 높은 조합**이다. 오인 대상이 " +
                "횡단보도·차선·차 지붕·포장 텍스처라 저시력 보행자가 반드시 지나는 곳이다. " +
                "그래서 상류 판정은 **탐지=원본 / 표시=D1A1+bf(+ts)의 경로 분리**이며" +
                "(§3, 🗣️ 팀 추인 대기), 이 arm의 GPU 비용이 싸게 나오더라도 그것이 ③ 입력으로 " +
                "쓸 근거가 되지는 않는다"

        /**
         * [CHAIN_FLICKER_NOTE]·[FUSED_FLICKER_NOTE]와 같은 취지. **bf가 출렁임을 줄이지
         * 않는다**는 것이 요점이다 — 노이즈 억제 필터라서 그럴 것 같지만 아니다.
         */
        const val BF_FLICKER_NOTE =
            "bf 자체는 **stateless**이고 프레임 간 상태를 두지 않는다" +
                "(INTERFACES.md §B-4가 ☐라 임의로 도입하지 않았다). 픽셀마다 **같은 프레임의 " +
                "이웃만** 보므로 앞 스테이지(D1·A1)의 프레임 간 출렁임은 **그대로 통과한다** — " +
                "🔴 **bf가 그것을 줄이지 않는다.** 공간 필터와 시간축 평활은 다른 것이고, " +
                "상류에서 출렁임을 잡는 것은 `+bf`가 아니라 `+ts`다(§7). " +
                "앞 스테이지의 출렁임 서술은 chain/fused의 flicker_note를 함께 볼 것"

        /**
         * bf 조합용 비교 지침. [CHAIN_HOW_TO_COMPARE]를 **재사용**하고, 이번 라운드에서 처음
         * 성립하는 사실(구성이 같은 arm이 생겼다)과 그래도 남는 단서를 앞에 붙인다.
         */
        const val BF_HOW_TO_COMPARE =
            "🔴 **이제야 상류 `D1A1+bf`와 구성이 같은 arm이 생겼다** — 상류 CPU 720p " +
                "101.2ms 옆에 놓을 수 있는 것은 (융합이 아닌) **체인+bf**다. 다만 단서 셋을 " +
                "함께 옮길 것: (a) 상류 101.2ms는 **PC CPU/NumPy** 기준이라 조건이 다르다, " +
                "(b) 상류 잠정 1위는 `D1A1+bf+ts`인데 **`ts`는 여전히 없다**" +
                "(INTERFACES.md §B-4가 ☐다) — 구성이 같아진 것은 `+bf`까지다, " +
                "(c) **'② ≤20ms' 게이트는 기획서가 아니라 상류 `data.md`의 내부 기준**이다" +
                "(RESEARCH_20260803_UPSTREAM.md). 우리 판정선은 FRAME_BUDGET.md §1과 " +
                "lib/targets.py 두 곳에만 있고 그 20ms는 거기 없다 — 상류 기준을 우리 게이트로 " +
                "옮겨 쓰지 말 것. " + CHAIN_HOW_TO_COMPARE

        // ── bf 조합의 색공간 변환 **선언값** ───────────────────────────────
        // 체인·융합의 같은 칸과 나란히 읽으라고 만든 표다. 값의 성격도 같다
        // (사람이 센 값이며 측정이 아니다 — [CHAIN_COLOR_TRANSFORM_DECLARED_PROVENANCE]).
        //
        // 🔴 **bf는 LabGlsl을 한 번도 부르지 않는다** — sRGB 8비트에서 직접 돌기 때문이다.
        //   그래서 색공간 변환 칸은 전부 base arm의 값에 **0을 더한 것**이고, 달라지는 것은
        //   패스 수·전체화면 패스 수·중간 materialize 수뿐이다.

        /** 체인 8패스 + bf 1패스. */
        const val CHAIN_BF_PASSES_TOTAL = CHAIN_PASSES_TOTAL + 1

        /** bf는 픽셀마다 도는 프래그먼트 패스다 → 체인의 6에 1을 더한다. */
        const val CHAIN_BF_FULLSCREEN_PASSES = CHAIN_FULLSCREEN_PASSES + 1

        /** 체인과 같다(bf가 0을 더한다). */
        const val CHAIN_BF_SRGB_TO_LINEAR_PER_PIXEL = CHAIN_SRGB_TO_LINEAR_PER_PIXEL
        const val CHAIN_BF_LAB_F_FORWARD_PER_PIXEL = CHAIN_LAB_F_FORWARD_PER_PIXEL
        const val CHAIN_BF_LAB_F_INVERSE_PER_PIXEL = CHAIN_LAB_F_INVERSE_PER_PIXEL
        const val CHAIN_BF_LINEAR_TO_SRGB_PER_PIXEL = CHAIN_LINEAR_TO_SRGB_PER_PIXEL
        const val CHAIN_BF_DRAGO_TONEMAP_EVALS_PER_PIXEL = CHAIN_DRAGO_TONEMAP_EVALS_PER_PIXEL
        const val CHAIN_BF_DRAGO_POW_LINEARIZE_PER_PIXEL = CHAIN_DRAGO_POW_LINEARIZE_PER_PIXEL
        const val CHAIN_BF_DRAGO_OUT_GAMMA_ENCODE_PER_PIXEL =
            CHAIN_DRAGO_OUT_GAMMA_ENCODE_PER_PIXEL

        /** 패스1 FBO_A + 패스4 FBO_B + 패스7 FBO_A + **패스8 bf FBO_B** = 4. 체인은 3이었다. */
        const val CHAIN_BF_INTERMEDIATE_RGBA8_MATERIALIZATIONS =
            CHAIN_INTERMEDIATE_RGBA8_MATERIALIZATIONS + 1

        /** 융합 7패스 + bf 1패스. */
        const val FUSED_BF_PASSES_TOTAL = FUSED_PASSES_TOTAL + 1

        const val FUSED_BF_FULLSCREEN_PASSES = FUSED_FULLSCREEN_PASSES + 1

        /** 융합과 같다(bf가 0을 더한다). */
        const val FUSED_BF_SRGB_TO_LINEAR_PER_PIXEL = FUSED_SRGB_TO_LINEAR_PER_PIXEL
        const val FUSED_BF_LAB_F_FORWARD_PER_PIXEL = FUSED_LAB_F_FORWARD_PER_PIXEL
        const val FUSED_BF_LAB_F_INVERSE_PER_PIXEL = FUSED_LAB_F_INVERSE_PER_PIXEL
        const val FUSED_BF_LINEAR_TO_SRGB_PER_PIXEL = FUSED_LINEAR_TO_SRGB_PER_PIXEL
        const val FUSED_BF_DRAGO_TONEMAP_EVALS_PER_PIXEL = FUSED_DRAGO_TONEMAP_EVALS_PER_PIXEL
        const val FUSED_BF_DRAGO_POW_LINEARIZE_PER_PIXEL = FUSED_DRAGO_POW_LINEARIZE_PER_PIXEL
        const val FUSED_BF_DRAGO_OUT_GAMMA_ENCODE_PER_PIXEL =
            FUSED_DRAGO_OUT_GAMMA_ENCODE_PER_PIXEL

        /** 패스1 FBO_A + 패스6 FBO_B + **패스7 bf FBO_A** = 3. 융합은 2였다. */
        const val FUSED_BF_INTERMEDIATE_RGBA8_MATERIALIZATIONS =
            FUSED_INTERMEDIATE_RGBA8_MATERIALIZATIONS + 1

        // ── ④ 오버레이 ────────────────────────────────────────────────────
        // 명세는 상류 `scripts/emphasize.py`에서 **확정된 것**이다(FRAME_BUDGET.md §3 주5,
        // docs/research/RESEARCH_20260803_UPSTREAM.md §5). 임의 더미의 봉투가 아니다.
        // ⚠ 단 **박스 개수와 배치는 사양이 아니다** — 아래 provenance를 함께 낸다.

        /** `highlight_boxes` — stairs 1 + person 3. */
        const val HIGHLIGHT_BOX_COUNT = 4

        /** `highlight_boxes_stress` — 개당 비용 기울기용. */
        const val HIGHLIGHT_BOX_COUNT_STRESS = 32

        /**
         * 🔴 [highlightBoxCount]의 **"이 arm은 개수가 고정이 아니다"**. 0을 쓰지 않는다 —
         * 0은 "박스를 하나도 그리지 않는다"는 적극적 주장이고, `overlay_boxes` 열에서 0이
         * 정상값인 것과 같은 이유로 두 사실을 한 값에 담으면 안 된다.
         */
        const val HIGHLIGHT_BOX_COUNT_DYNAMIC = -1

        /** 본선 두께의 기준값(720p에서 px). 상류 확정 사양이다. */
        const val HIGHLIGHT_STROKE_PX_AT_720P = 4f

        /** 720p의 **짧은 변**. 두께를 짧은 변에 비례시키는 기준이라 여기 둔다. */
        const val HIGHLIGHT_SHORT_SIDE_AT_720P = 720f

        /**
         * 검정 밑선이 본선보다 **한쪽으로** 더 나가는 폭(720p에서 px).
         *
         * ⚠ **상류 문서에 이 값이 기록돼 있지 않다** — 확정된 것은 "이중 스트로크(검정 밑선 +
         * 대비색 본선)"라는 구성과 본선 두께 4px까지다. 그래서 이 1px은 **우리가 선언한 측정
         * 조건**이고 사양이 아니다(cv2.rectangle을 t+2와 t로 두 번 그리는 통상 관용에서 왔다).
         * `session.json`에 계산식과 함께 그대로 나간다.
         */
        const val HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P = 1f

        const val HIGHLIGHT_STROKE_FORMULA =
            "scale = min(처리폭, 처리높이) / $HIGHLIGHT_SHORT_SIDE_AT_720P ; " +
                "본선 = $HIGHLIGHT_STROKE_PX_AT_720P * scale ; " +
                "검정 밑선 = ($HIGHLIGHT_STROKE_PX_AT_720P + " +
                "2 * $HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P) * scale. " +
                "두 띠는 경계선 위에 **가운데를 맞춘다**(cv2.rectangle(thickness=t)와 같은 " +
                "배치라 안쪽·바깥쪽으로 t/2씩 채운다). 픽셀 값을 하드코딩하지 않고 **처리 " +
                "해상도에서 계산**하므로 640×360으로 내려도 비례가 유지된다"

        // ── ④ fill (박스 안쪽 채움) ────────────────────────────────────────
        // 🔴 **상류 확정 명세는 '비채움'이다**(FRAME_BUDGET.md §3 주5). 아래 값은 그 명세로부터의
        //    이탈이며 사유는 [HIGHLIGHT_FILL_DEVIATION]에 있다. 🔴 **알파 리터럴은 아래 한 줄이
        //    전부다** — 사본을 만들면 값을 바꾸는 날 session.json이 안 도는 값을 선언한다.
        // 🔴 **fill 대조군([DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL])은 이 값을 쓰지 않는다** —
        //    그 arm에서 `overlay.fill_alpha`는 null이고 사유가 옆 키에 붙는다
        //    ([HIGHLIGHT_NOFILL_CONTROL_NOTE]).

        /**
         * 박스 안쪽 fill의 알파. **제안값이 아니라 임의값이다** — 근거는
         * [highlightFillProvenance].
         *
         * 🔴 **빌드 상수이며 시간·프레임에 따라 변하지 않는다.** 알파 변조는 광과민 사용자
         * 안전 이슈로 규정된 항목이다([OVERLAY_NO_FLICKER_DESIGN] (1)).
         */
        const val OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE = 0.30f

        /**
         * 🔴 위 값의 출처 문장. **같은 문장이 `session.json`으로 나간다**
         * ([OVERLAY_SMOOTHING_PROVENANCE]와 같은 틀이다).
         *
         * 🔴 **상수가 아니라 함수인 이유:** fill 대조군([DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL])도
         * 이 키를 싣는데(키가 사라지면 소비자가 조용히 null을 읽는다) 그 arm은 이 알파를
         * **쓰지 않는다.** 출처·판정 기준·'사본이 없다'는 서술은 두 arm에서 그대로 참이므로
         * 공유하고 **귀속 한 절만** 앞에 붙인다([chainHighlightTileReloadNote]와 같은 틀이다).
         *
         * @param fill 이 arm이 fill quad를 실제로 그리는가([drawsOverlayFill]).
         */
        fun highlightFillProvenance(fill: Boolean): String = (
            if (fill) {
                ""
            } else {
                "🔴 **이 arm은 이 알파를 쓰지 않는다** — fill 기하를 건너뛰는 대조군이라 " +
                    "정점에 이 값이 실리지 않는다(overlay.fill_enabled=false · " +
                    "전문은 overlay.fill). 아래는 **짝 arm이 쓰는 값의 출처**이며, 이 arm의 " +
                    "로그에 남기는 이유는 차분의 상대가 어떤 알파였는지가 사후에 필요하기 " +
                    "때문이다. "
            }
            ) +
            "🔴 **제안값이 아니다 — 임의값이다.** fill_alpha=" +
                "$OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE. " +
                "🔴 **INTERFACES.md에 이 항목 자체가 없다** — 계약은 A(모델)·B(①②)·C(녹화) " +
                "셋뿐이고 ④ 계약도, 채움·알파 항목도 없다. 그러므로 '계약의 ☐'가 아니라 " +
                "**항목 부재**이며, 확정해 줄 칸이 아직 존재하지 않는다. " +
                "OVERLAY_*_MEASUREMENT_VALUE·GAMMA_MEASUREMENT_VALUE와 같은 취급이다: 팀이 " +
                "④ 항목을 만들면 그때 교체한다. " +
                "**판정 기준은 성능이 아니라 형상 파악 가능성이다** — 야간에 bbox 안 물체" +
                "(계단·볼라드·사람)의 **형상이 육안으로 파악되는가**가 이 값을 정하는 유일한 " +
                "기준이다(사용자 결정, 2026-08-28). 알파가 높을수록 박스는 눈에 띄지만 " +
                "**안의 물체는 덜 보인다.** " +
                "🔴 **아직 미측정이다** — 야간 육안 확인(계획 1-6)을 실시하지 않았다. 이 값이 " +
                "그 기준을 만족한다는 근거는 **아직 없다.** " +
                "🔴 **이 숫자의 사본은 코드 어디에도 없다** — 상수 정의 한 줄이 전부이고 " +
                "셰이더·정점·이 문장은 전부 그것을 보간한다"

        /**
         * 🔴 **두 번째 상류 이탈 선언.** [OverlayClassColors.PERSON_COLOR_DEVIATION]이 색에
         * 대해 한 것과 같은 처리다 — 지시였다는 사실과 **대가**를 함께 적는다.
         *
         * ⚠ 그쪽과 결정적으로 다른 점이 하나 있다: **색은 픽셀 비용에 영향이 없었지만
         * 채움은 있다.** 그 문장을 여기 복사해 오면 거짓이 된다.
         */
        const val HIGHLIGHT_FILL_DEVIATION =
            "🔴 **상류 명세 이탈 — 사용자 지시다.** 박스 **안쪽**을 클래스 색으로 알파 " +
                "$OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE 로 채운다. " +
                "상류 확정 명세는 **비채움**이다(FRAME_BUDGET.md §3 주5 · " +
                "docs/research/RESEARCH_20260803_UPSTREAM.md §5 표의 '채움' 행). " +
                "채우기로 한 이유는 야간에 bbox 안 물체의 **형상 파악**을 돕는 것이며 " +
                "판정 기준도 그것이다(fill_alpha_provenance). " +
                "🔴 **이 이탈이 지는 대가 셋을 사실로 적는다.** " +
                "(a) **박스 내부의 대비가 알파만큼 눌린다** — 화면 픽셀이 " +
                "`(1-α)·원본 + α·클래스색`이 되므로 물체와 배경의 대비가 " +
                "$OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE 만큼 깎인다. 즉 '박스가 잘 보인다'와 " +
                "'박스 안이 잘 보인다'를 **정면으로 맞바꾼 것**이고, 어느 쪽으로 기울었는지는 " +
                "야간 육안(계획 1-6)이 판정한다 — **아직 미실시다.** " +
                "(b) **I칸의 설명 변수가 개수에서 면적으로 바뀐다** — 08-26 S3에서 나온 " +
                "'stage_i_ms는 박스 개수와 무관하다'는 결론은 스트로크만 그리던 시절의 " +
                "것이다. 채우는 프래그먼트가 박스 면적에 비례하므로 이제 큰 박스 하나가 작은 " +
                "박스 여럿보다 비쌀 수 있다. 🔴 **그 축은 ③ 결과 arm의 frames.csv에서 " +
                "overlay_fill_frac 열이 낸다** — 정적 더미 arm은 박스의 크기·배치가 고정이라 " +
                "④ 오버레이 열 자체를 싣지 않고, 그 arm의 조건은 box_count와 " +
                "box_count_provenance의 격자 서술이 말한다(overlay_boxes와 같은 게이트다). " +
                "🔴 **(c) 색 이탈과 달리 이 변경은 타이밍 지표를 바꾼다.** " +
                "person_color_deviation의 마지막 문장('색은 픽셀 비용에 영향이 없으므로 이 " +
                "변경은 타이밍 지표를 바꾸지 않는다')이 **여기서는 성립하지 않는다** — 그 " +
                "문장을 이 항목에 옮겨 적지 말 것. 드로우콜은 여전히 프레임당 1회이고 정점은 " +
                "박스당 ${HighlightOverlay.VERTS_PER_FILL}개만 늘지만, **채우는 픽셀 수는 " +
                "테두리 면적에서 박스 면적으로 늘었다.** 🔴 **얼마나 늘었는지는 미측정이다** " +
                "(계획 1-7, I칸 재측정). 이전 stage_i_ms 실측은 **이 빌드의 값이 아니다** — " +
                "정적 더미 arm(highlight_boxes / _stress / _1q)도 함께 채우므로 그 arm들의 " +
                "승격 숫자도 같이 낡았다"

        /**
         * 🔴 **fill 대조군 arm의 뜻 전부.** `session.json`의 **`overlay.fill`**로 나간다
         * (그 키가 arm별로 갈린다 — fill arm에서는 '채운다'는 이탈 서술이 실린다).
         * `overlay.fill_alpha`가 null인 사유(`fill_alpha_null_reason`)도 이 키를 가리킨다.
         *
         * ⚠ [HIGHLIGHT_FILL_DEVIATION]의 사본이 아니다 — 그쪽은 **채운다는 이탈**의 사유이고
         * 이쪽은 **채우지 않는 대조군**이 무엇을 답하고 무엇을 답하지 못하는가다.
         */
        const val HIGHLIGHT_NOFILL_CONTROL_NOTE =
            "🔴 **이 arm은 fill 기하를 건너뛴다 — 알파를 0으로 둔 것이 아니다.** 알파 0이면 " +
                "fill quad가 그대로 래스터라이즈돼 프래그먼트 비용이 **똑같이** 들고, 그러면 " +
                "차분이 0에 가깝게 나오면서 'fill은 공짜다'라는 틀린 결론이 나온다. 이 arm은 " +
                "박스당 정점이 스트로크 몫 ${HighlightOverlay.VERTS_PER_STROKES}개뿐이고" +
                "(짝 arm은 ${HighlightOverlay.VERTS_PER_BOX}개 = 거기에 fill quad " +
                "${HighlightOverlay.VERTS_PER_FILL}개가 더 붙는다) fill 사각형은 정점 버퍼에 " +
                "**쓰이지 않는다.** " +
                "🔴 **이 arm의 유일한 뜻은 짝 arm(detect_cpu_chain_highlight)과의 차분이며 " +
                "그 차분이 fill 기하의 비용이다.** 다른 어떤 질문에도 이 arm으로 답하지 말 것 — " +
                "특히 이 arm의 stage_i_ms를 '④ 오버레이의 비용'으로 인용하면 fill을 뺀 값을 " +
                "제품 구성의 값으로 옮겨 적는 것이 된다(제품은 채운다). " +
                "🔴 **차분이 성립하는 조건 넷**: (1) **같은 세션·같은 빌드**여야 한다 — 다른 " +
                "세션과 빼면 발열·조명·AE 상태의 차이가 fill 비용으로 둔갑한다. " +
                "(2) **overlay_boxes 버킷 안에서** 비교한다(개수를 섞으면 스트로크 몫이 " +
                "차분에 들어온다). (3) 두 런의 **overlay_fill_frac 분포가 겹쳐야 한다** — " +
                "fill 비용은 면적에 비례하므로 면적이 다른 장면끼리의 차분은 fill 비용이 " +
                "아니다. (4) 박스가 0개인 프레임은 두 arm 모두 드로우콜을 내지 않으므로 " +
                "차분에 기여하지 않는다(야간에는 그런 프레임이 다수다 — 버킷 분리가 필수인 " +
                "이유이기도 하다). " +
                "🔴 **이 짝으로 분리되지 않는 것 넷**: (a) **블렌드 상태 변경 비용** — 이 " +
                "arm도 블렌딩을 켠 채 두므로(overlay.fill_blend) glEnable/glBlendFuncSeparate/" +
                "glDisable이 두 arm에서 똑같이 돈다. 상태 변경 자체의 비용은 이 차분에 " +
                "나타나지 않는다. (b) **aAlpha 정점 속성** — 두 arm이 같은 셰이더·같은 " +
                "6-float stride를 쓴다(스트로크도 알파를 나른다). (c) **정점 버퍼 용량** — " +
                "두 arm 모두 최대 개수분을 컨텍스트당 한 번 잡는다(vertexCount만 다르다). " +
                "(d) **패스7↔8의 FBO_A 병합 가능성** — 드라이버가 두 렌더패스를 합칠 수 " +
                "있고(tile_reload_note) 그 사정은 두 arm이 같다. " +
                "🔴 **이 arm은 상류 '비채움' 명세에 부합한다**(FRAME_BUDGET.md §3 주5 · " +
                "docs/research/RESEARCH_20260803_UPSTREAM.md §5 표의 '채움' 행) — 이탈한 쪽은 " +
                "짝 arm이다. 다만 스트로크 기하의 이탈(upstream_deviation)은 이 arm에도 " +
                "그대로 있다. " +
                "🔴 **아직 미측정이다** — 이 arm으로 실기기 런을 뜨지 않았다."

        /**
         * 🔴 fill 대조군의 `overlay.fill_deviation`. **키를 지우지 않는다** — 지우면 소비자가
         * 조용히 null을 읽고 "이탈이 기록되지 않은 빌드"와 구분되지 않는다.
         *
         * ⚠ 이탈 전문은 짝 arm의 같은 키([HIGHLIGHT_FILL_DEVIATION])에 있다. 여기 사본을
         * 두지 않는다.
         */
        const val HIGHLIGHT_NOFILL_DEVIATION_NOTE =
            "🔴 **이 arm은 채우지 않으므로 이 이탈이 적용되지 않는다 — 상류 '비채움' 명세를 " +
                "지킨다.** fill 기하를 건너뛰는 대조군이며(overlay.fill_enabled=false · " +
                "전문은 overlay.fill) 박스 내부는 한 픽셀도 건드리지 않는다. " +
                "⚠ **이탈 전문은 짝 arm(detect_cpu_chain_highlight)의 같은 키에 있다** — " +
                "여기에 사본을 두지 않는다. 그쪽 arm이 상류 명세에서 이탈한 쪽이고 그 이탈의 " +
                "비용이 이 두 arm의 차분이다. " +
                "⚠ 스트로크 기하의 이탈(upstream_deviation)은 **이 arm에도 그대로 있다** — " +
                "fill과 별개의 항목이다"

        const val HIGHLIGHT_SPEC_PROVENANCE =
            "상류(모델링 담당 kty2001/KDT_Hackathon) scripts/emphasize.py로 **확정된 ④ 명세**다 " +
                "— 이중 스트로크(검정 밑선 + 대비색 본선) · 비채움 · stairs=노랑 / person=시안 · " +
                "빨강 금지 · 깜빡임 금지 · 두께는 짧은 변 비례로 720p 기준 " +
                "${HIGHLIGHT_STROKE_PX_AT_720P}px. 출처는 " +
                "docs/research/RESEARCH_20260803_UPSTREAM.md §5이고 FRAME_BUDGET.md §3 주5에 " +
                "같은 내용이 있다. 즉 I칸은 '임의 더미의 비용 봉투'가 아니라 **실제 사양으로 잰 " +
                "값**이다. ⚠ 다만 밑선이 본선보다 얼마나 넓은지는 상류에 기록이 없어 우리가 " +
                "정했다(stroke_formula의 underline_margin_px_at_720p)"

        /**
         * 🔴 **④ 고유의 이탈.** [CHAIN_DEVIATION]이 중간 dtype에 대해 한 것과 같은 처리다 —
         * **스펙 문구와 이 구현의 스트로크 기하가 다르다는 사실**을 숫자로 밝힌다.
         * 이 문장이 빠지면 골든 이미지가 생겨 픽셀 대조를 하는 날 결과가 어긋나는데
         * "사양대로 그렸다"에서 막힌다.
         *
         * ⚠ 고치지 않은 것은 의도다 — 어느 쪽이 상류와 같은지 **우리가 알지 못한다**(아래 (3)).
         * 기하를 건드리면 검증을 다시 받아야 하므로 이번 라운드는 **선언만** 추가했다.
         */
        private const val HIGHLIGHT_DEVIATION_HEAD =
            "🔴 **(1) 스펙 문구와 이 구현의 스트로크 기하가 다르다.** 스펙은 '비채움 — " +
                "경계선 밖은 일절 안 건드림'인데(FRAME_BUDGET.md:258 · " +
                "docs/research/RESEARCH_20260803_UPSTREAM.md:114 §5 표의 '채움' 행) 이 구현은 " +
                "두 띠를 경계선 위에 **가운데 맞춤**해서 경계 **밖으로 나간다.** 720p 기준 실제 " +
                "값(정점의 NDC 반폭 = 두께/처리폭이고 NDC 1 = 처리폭/2 px이므로 **한쪽 = " +
                "두께/2 px**이다): 본선은 밖으로 " +
                "$HIGHLIGHT_STROKE_PX_AT_720P / 2 = ${HIGHLIGHT_STROKE_PX_AT_720P / 2f}px, " +
                "검정 밑선은 ($HIGHLIGHT_STROKE_PX_AT_720P + " +
                "2 * $HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P) / 2 = " +
                "${(HIGHLIGHT_STROKE_PX_AT_720P + 2f * HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P) / 2f}" +
                "px — 즉 **경계 밖 " +
                "${(HIGHLIGHT_STROKE_PX_AT_720P + 2f * HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P) / 2f}" +
                "px을 덮는다**(가장 바깥 " +
                "${HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P}px이 검정, 그 안 " +
                "${HIGHLIGHT_STROKE_PX_AT_720P / 2f}px이 대비색). 안쪽도 대칭으로 같은 폭이다. "

        /**
         * 🔴 (1)번 항목의 **박스 내부** 절 — fill arm 판. 이 arm은 내부를 채우므로 예전 문장
         * ("그보다 더 안쪽은 한 픽셀도 건드리지 않는다")이 **거짓**이다.
         */
        private const val HIGHLIGHT_DEVIATION_INNER_FILLED =
            "⚠ **여기서 '그보다 더 안쪽은 한 픽셀도 건드리지 않는다'는 문장이 예전에 " +
                "있었고 지금은 거짓이다** — 이 빌드는 박스 내부를 알파 " +
                "$OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE 로 채운다(fill_deviation). 이 (1)번 " +
                "항목은 **스트로크의 기하**에 대한 것이고 내부 채움은 별개의 이탈이다. "

        /**
         * 🔴 같은 절의 **fill 대조군** 판 — 그 arm에서는 같은 문장이 **다시 참**이 된다
         * (반대 방향이다). 🔴 스트로크 기하 서술 (2)(3)(4)는 두 arm에서 참이므로 갈리지 않는다.
         */
        private const val HIGHLIGHT_DEVIATION_INNER_UNTOUCHED =
            "🔎 **이 arm에서는 '그보다 더 안쪽은 한 픽셀도 건드리지 않는다'가 다시 참이다** — " +
                "fill 기하를 건너뛰는 대조군이라 박스 내부를 칠하지 않는다" +
                "(overlay.fill_enabled=false · 전문은 overlay.fill). 그 문장이 거짓인 것은 " +
                "**짝 arm**(detect_cpu_chain_highlight) 쪽이며 그쪽 사유는 fill_deviation에 " +
                "있다. 이 (1)번 항목은 **스트로크의 기하**에 대한 것이고 그 이탈은 이 arm에도 " +
                "그대로 있다. "

        /** (2)(3)(4)와 (5)의 앞부분. 🔴 **두 arm에서 글자 그대로 같다.** */
        private const val HIGHLIGHT_DEVIATION_MID =
            "**(2) 왜 고치지 않았는가**: `cv2.rectangle(img, p1, p2, color, thickness=t)`가 " +
                "스트로크를 경계선 **가운데**에 놓고(안쪽 t/2 · 바깥 t/2), 상류 " +
                "scripts/emphasize.py가 쓰는 함수가 바로 그것이다. 그러므로 **이 구현이 오히려 " +
                "상류 동작과 일치할 가능성이 높고**, 스펙 문구가 '비채움'(= 박스 내부를 칠하지 " +
                "않는다)을 느슨하게 표현한 것일 수 있다. 문구에 맞춰 기하를 안쪽 맞춤으로 고치면 " +
                "상류와 어긋날 위험이 오히려 커진다. " +
                "🔴 **(3) 어느 쪽이 맞는지 확인할 수 없다.** 상류 원문에 `thickness` 인자와 좌표 " +
                "규약(경계선을 어느 쪽에 포함하는지)이 기록돼 있지 않고, **INTERFACES.md §B-6 " +
                "골든 이미지가 없어 픽셀 단위 대조가 불가능하다.** 그러므로 (2)는 **추론이지 " +
                "상류 코드 인용이 아니다** — BF_DEVIATION이 cv2.bilateralFilter의 내부 정의에 " +
                "대해 한 것과 같은 처지다. " +
                "**(4) 어디서 걸리는가**: 골든 이미지가 생겨 **픽셀 단위 대조를 하는 날 이 " +
                "항목을 먼저 의심할 것.** 그리고 경계 밖으로 나가므로 **박스가 프레임 " +
                "가장자리에 붙으면 밑선이 잘린다**(뷰포트 밖은 조용히 버려진다). ⚠ 사실 확인: " +
                "**이 arm의 배치는 그 경우를 만들지 않는다** — 셀 격자의 가장자리 여백이 최소 " +
                "CELL_INSET/CELL_COLS = 폭의 1.5%(720p에서 x 19.2px) · " +
                "CELL_INSET/CELL_ROWS = 높이의 3%(y 21.6px)라 " +
                "${(HIGHLIGHT_STROKE_PX_AT_720P + 2f * HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P) / 2f}" +
                "px보다 훨씬 크다. 그러므로 **정적 더미 arm 셋의 stage_i_ms는 잘림의 영향을 " +
                "받지 않는다.** 🔴 **그러나 ③ 결과 arm(detect_cpu_highlight / _1q)에서는 " +
                "그 조건이 실제로 생긴다**(2026-08-07): 탐지 박스는 프레임 경계에 붙을 수 " +
                "있고 경계 밖으로 나갈 수도 있다(후처리가 클램프하지 않는다). 그때 GL 뷰포트가 " +
                "밖을 자르므로 **가장자리에서 검정 밑선이 한쪽만 잘려 보인다** — 이것은 " +
                "이 기하 선택의 알려진 결과이지 결함이 아니며, 클램프로 막지 않는다" +
                "(클램프는 면적 0 박스를 가장자리에 남겨 더 나쁜 쓰레기를 만든다). " +
                "**(5) 비용에 대한 영향**: 드로우콜(프레임당 1회)은 이 선택과 무관하게 같고 "

        /** (5)의 **정점 수** 절 — fill arm 판. */
        private const val HIGHLIGHT_DEVIATION_VERTS_FILLED =
            "정점 수도 스트로크 몫 " +
                "${HighlightOverlay.VERTS_PER_STROKES}개는 그대로다(박스당 총 " +
                "${HighlightOverlay.VERTS_PER_BOX}개인 것은 fill quad " +
                "${HighlightOverlay.VERTS_PER_FILL}개가 더 붙었기 때문이며 그것은 이 항목이 " +
                "아니라 fill_deviation의 몫이다). "

        /** (5)의 **정점 수** 절 — fill 대조군 판. 박스당 정점이 스트로크 몫뿐이다. */
        private const val HIGHLIGHT_DEVIATION_VERTS_NOFILL =
            "정점 수도 스트로크 몫 " +
                "${HighlightOverlay.VERTS_PER_STROKES}개는 그대로다(**이 arm은 박스당 총 " +
                "${HighlightOverlay.VERTS_PER_STROKES}개이고 fill quad가 없다** — 짝 arm은 " +
                "${HighlightOverlay.VERTS_PER_BOX}개다. 전문은 overlay.fill). " +
                "⚠ 아래 4% 비교에 나오는 fill 몫은 **짝 arm의 값**이며 이 arm에는 없다. "

        /** (5)의 나머지. 🔴 **두 arm에서 글자 그대로 같다**(스트로크 기하의 사실이다). */
        private const val HIGHLIGHT_DEVIATION_TAIL =
            "다만 **채우는 프래그먼트 수는 완전히 같지 " +
                "않다** — 두께 t 띠 하나의 면적이 가운데 맞춤이면 " +
                "2t(W+H)이고 안쪽 맞춤이면 2t(W+H) - 4t²이라 **띠마다 4t²만큼 더 채운다**" +
                "(720p에서 본선 t=$HIGHLIGHT_STROKE_PX_AT_720P → 64px, 밑선 t=" +
                "${HIGHLIGHT_STROKE_PX_AT_720P + 2f * HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P}" +
                " → 144px, 합 박스당 208px). 이 격자의 720p 박스가 121.6x136.8px이고 " +
                "스트로크가 박스당 총 5168px을 채우므로 **약 4%**다. 작지만 **0이 아니다** — " +
                "'그린 픽셀 수가 안 바뀐다'로 적지 말 것. " +
                "⚠ **이 4%는 스트로크 안에서의 비율이다** — 같은 박스의 fill은 " +
                "121.6x136.8 = 16635px을 더 채우므로(스트로크의 3.2배) 비교 대상이 아니다. " +
                "🔴 fill을 포함한 실제 I칸 비용은 **미측정이다**"

        /**
         * 🔴 fill arm 판의 전문. **KDoc 참조와 사람이 읽는 앵커가 이 이름이다**
         * ([HighlightOverlay]와 `DetectPostprocessor`의 주석이 이 이름으로 (1)·(4)를 가리킨다).
         *
         * ⚠ **사본이 아니라 위 조각들의 합이다** — 물리 서술을 두 벌로 두지 않는다.
         * `session.json`에 실을 값은 [highlightDeviation]에 arm의 fill 여부를 넣어 얻는다.
         */
        const val HIGHLIGHT_DEVIATION =
            HIGHLIGHT_DEVIATION_HEAD + HIGHLIGHT_DEVIATION_INNER_FILLED +
                HIGHLIGHT_DEVIATION_MID + HIGHLIGHT_DEVIATION_VERTS_FILLED +
                HIGHLIGHT_DEVIATION_TAIL

        /**
         * 🔴 **스트로크 기하 이탈의 arm별 판.** 물리 서술((1)의 기하 수치·(2)(3)(4)·(5)의
         * 프래그먼트 산식)은 **두 arm에서 글자 그대로 같고**, 갈리는 것은 두 절뿐이다:
         * **박스 내부를 건드리는가**와 **박스당 정점 수**. [chainHighlightTileReloadNote]와
         * 같은 틀이다 — 문장을 두 벌로 복사하지 않고 갈리는 절만 매개변수화한다.
         *
         * @param fill 이 arm이 fill quad를 실제로 그리는가([drawsOverlayFill]).
         */
        fun highlightDeviation(fill: Boolean): String =
            HIGHLIGHT_DEVIATION_HEAD +
                (
                    if (fill) HIGHLIGHT_DEVIATION_INNER_FILLED
                    else HIGHLIGHT_DEVIATION_INNER_UNTOUCHED
                    ) +
                HIGHLIGHT_DEVIATION_MID +
                (
                    if (fill) HIGHLIGHT_DEVIATION_VERTS_FILLED
                    else HIGHLIGHT_DEVIATION_VERTS_NOFILL
                    ) +
                HIGHLIGHT_DEVIATION_TAIL

        /**
         * ⚠ **개수와 배치는 계약값이 아니다.** `session.json`에 개수를 싣지 않으면 I칸 숫자가
         * 무슨 조건의 값인지 사라진다 — 그래서 `overlay.box_count`가 필수다.
         */
        const val HIGHLIGHT_BOX_PROVENANCE =
            "🔴 **박스 개수와 배치는 사양이 아니라 우리가 선언한 측정 조건이다.** 실제 개수는 " +
                "장면마다 다르므로(상류도 '몇 개 기준인지 함께 적을 것'이라는 단서를 달았다 — " +
                "FRAME_BUDGET.md §3 주5) 이 값을 사양으로 옮기지 말 것. " +
                "arm 두 개로 갈라 잰다: highlight_boxes = ${HIGHLIGHT_BOX_COUNT}개" +
                "(stairs 1 + person 3) / highlight_boxes_stress = " +
                "${HIGHLIGHT_BOX_COUNT_STRESS}개(개당 비용 기울기용). " +
                "배치는 ${HighlightOverlay.CELL_COLS}x${HighlightOverlay.CELL_ROWS} 셀 격자에서 " +
                "**서로 겹치지 않는 같은 크기**의 박스를 stride ${HighlightOverlay.CELL_STRIDE}로 " +
                "뽑은 것이다(gcd(7,32)=1이라 순열이고, 개수가 적어도 화면 전체에 퍼진다). " +
                "**박스 크기가 두 arm에서 같으므로** 두 값을 나란히 놓아 개당 기울기로 쓸 수 " +
                "있다 — 격자를 개수에 맞춰 바꾸면 박스마다 둘레가 달라져 그 기울기가 깨진다. " +
                "🔴 **이 문단은 정적 더미 arm(highlight_boxes / _stress / _1q) 셋에만 해당한다.** " +
                "③ 결과를 그리는 arm(detect_cpu_highlight / _1q)에서는 개수도 배치도 크기도 " +
                "**장면이 정한다** — 그 arm의 box_count는 null이고 프레임별 개수는 frames.csv의 " +
                "overlay_boxes 열이 말한다(box_count_dynamic 참고). 그러므로 그 arm의 " +
                "stage_i_ms를 위 개당 기울기로 나눠 검산하지 말 것: 박스 크기가 이 격자와 다르다"

        // 🔴 **색 문장의 출처는 [OverlayClassColors] 하나다** — 여기에 사본("노랑 (1, 1, 0)"
        //    같은 문자열)을 두면 색을 고치는 날 session.json의 두 블록(`overlay.colors`와
        //    `overlay.class_color_mapping.table`)이 서로 다른 말을 한다.
        const val HIGHLIGHT_COLOR_STAIRS = OverlayClassColors.STAIRS_COLOR_TEXT
        const val HIGHLIGHT_COLOR_PERSON = OverlayClassColors.PERSON_COLOR_TEXT
        const val HIGHLIGHT_COLOR_BOLLARD = OverlayClassColors.BOLLARD_COLOR_TEXT
        const val HIGHLIGHT_COLOR_UNDERLINE = OverlayClassColors.UNDERLINE_COLOR_TEXT

        /**
         * 🔴 `person`이 **상류 명세를 벗어나 빨강**이라는 사실과 그 위험. 사본을 만들지 않고
         * [OverlayClassColors]의 문장을 그대로 가리킨다(색 문장 규약과 같은 이유).
         */
        const val HIGHLIGHT_PERSON_COLOR_DEVIATION =
            OverlayClassColors.PERSON_COLOR_DEVIATION

        /** `bollard` 색의 출처. 🔴 **이탈이 아니다** — 상류 팔레트에 항목이 없었다. */
        const val HIGHLIGHT_BOLLARD_COLOR_PROVENANCE =
            OverlayClassColors.BOLLARD_COLOR_PROVENANCE

        /**
         * 🔴 **명세 원문(빨강 금지 이유)은 이 키에 그대로 남긴다** — 상류가 무엇을 왜
         * 금지했는지가 사라지면 이탈의 뜻도 사라진다.
         *
         * ⚠ **2026-08-24 갱신** — 예전 마지막 절("그래서 이 오버레이의 색은 노랑·시안·검정
         * 셋뿐이고 빨강 단색 스트로크는 코드에 존재하지 않는다")이 **거짓이 됐다**: `person`이
         * 사용자 지시로 빨강이 됐다. 그 절만 이탈 참조로 교체했고 금지 사유와 출처는 손대지
         * 않았다. 이 문장은 `session.json`에 **두 자리로** 실린다.
         */
        const val HIGHLIGHT_NO_RED_REASON =
            "🔴 **상류 명세: 빨강 금지.** 빨강은 휘도가 낮아 야간 배경에 묻히고 적록색약에서 " +
                "무너진다 (RESEARCH_20260803_UPSTREAM.md §5). " +
                "🔴 **그런데 이 런의 어휘색은 그 금지를 지키지 않는다** — `person`이 사용자 " +
                "지시로 빨강이 됐다(person_color_deviation에 이탈 사유와 위험 두 가지가 있다). " +
                "위 금지는 **중립색(unknown=흰색) 후보에 대해서는 여전히 유효하다**"

        const val HIGHLIGHT_NO_BLINK_REASON =
            "🔴 **깜빡임 금지 — 안전 이슈다.** 대상 사용자가 광과민이므로 상류가 '항상 정적 " +
                "윤곽'으로 못 박았다(RESEARCH_20260803_UPSTREAM.md §5). 그래서 이 오버레이는 " +
                "난수를 쓰지 않고 박스가 프레임마다 **완전히 같다**(재현성도 그 덕에 유지된다)"

        /**
         * 🔴 이 문장이 빠지면 "깜빡임이 없었다"가 **성능 근거처럼** 읽힌다.
         *
         * ⚠ **2026-08-07 갱신** — 예전 문장은 "H칸은 미구현이다"였고 그것이 이번 라운드에
         * 거짓이 됐다. 정적 더미 arm과 ③ 결과 arm에서 이 문장의 뜻이 갈리므로 둘을 갈라 적는다.
         */
        const val HIGHLIGHT_BLINK_NOT_A_PERF_CLAIM =
            "🔴 **깜빡임이 없다는 사실을 성능 근거로 쓰지 말 것 — 그리고 arm마다 뜻이 다르다.** " +
                "(a) 정적 더미 arm(highlight_boxes / _stress / _1q): 박스가 프레임마다 완전히 " +
                "같아 **구조적으로** 깜빡임이 없을 뿐이고 그 런은 깜빡임을 **시험하지 않았다.** " +
                "(b) ③ 결과 arm(detect_cpu_highlight / _1q): 박스가 실제로 바뀌므로 이제 " +
                "**깜빡임이 시험된다** — H칸(좌표 IIR 평활·hold)이 이 라운드에 구현됐고, " +
                "'만들지 않았다'의 유일한 기계 근거는 하네스의 overlay.flicker 블록" +
                "(blank_transitions + zero_frame_fraction + zero_runs_at_edge + " +
                "drew_then_stopped)이다. 🔴 **눈으로 본 것은 계측이 아니다** — 그 블록 없이 " +
                "'깜빡이지 않았다'고 쓰지 말 것. 설계상 무엇을 막았는지는 " +
                "overlay_no_flicker_design에 있다. 상류가 이것을 안전 이슈로 규정했다" +
                "(RESEARCH_20260803_UPSTREAM.md §5)"

        /**
         * 🔴 `INTERFACES.md` §A-4의 클래스 2번은 ☐(미정)다. 3번째 색을 지어내지 않는다.
         *
         * ⚠ **2026-08-07 갱신** — 예전 문장은 "`stairs`(index 0) · `person`(index 1)"이라고
         * **인덱스로** 클래스를 말했다. ③→④가 이어진 지금 그 서술은 거짓이며(모델의 순서는
         * 그 반대다) 색은 [com.bammasil.poc.gl.OverlayClassColors]가 **이름으로** 고른다.
         */
        const val HIGHLIGHT_CLASS_NOTE =
            "🔴 **색은 클래스 이름으로 고른다 — 인덱스로 고르지 않는다**" +
                "(OverlayClassColors). 어휘는 `stairs`·`person`·`bollard` **셋**이고 이름의 " +
                "출처는 **모델 임베드 메타의 names 하나뿐**이다(계약 문서의 순서를 쓰지 " +
                "않는다). 그래서 INTERFACES.md §A-4와 모델의 순서가 반대인 채로도" +
                "(contract_a4_conflict) 이 코드는 옳게 그리고, 팀이 어느 쪽으로 확정해도 " +
                "**바뀌지 않는다** — 그것이 이 설계의 목적이다. " +
                "⚠ **어휘가 둘에서 셋으로 늘었다**: `bollard`는 모델이 새로 가진 클래스이고 " +
                "**상류 팔레트에 항목이 없어** 예전에는 중립색(흰색) fallback이었다 — 색을 " +
                "지어낸 것이 아니라 우리가 선언했다는 사실은 bollard_color_provenance에 있다. " +
                "그리고 `person`의 색은 **상류 명세를 벗어났다**(person_color_deviation). " +
                "🔴 어휘 밖 이름·범위 밖 cls는 여전히 **지우지 않고 중립색(흰색)으로** " +
                "그린다(unknown_policy) — 클래스가 또 늘면 그 자체가 렌더 규약 변경이라" +
                "(§A-4 불변 규칙) 오버레이 어휘를 팀과 함께 갱신해야 한다. " +
                "색은 픽셀 비용에 영향이 없다(어느 색이든 같은 면적을 채운다)"

        // ── ④ H칸: 좌표 평활·hold (스키마 v7) ─────────────────────────────
        // 🔴 **계약에 이 항목 자체가 없다.** `INTERFACES.md`의 계약은 A(모델)·B(①②)·C(녹화)
        //    셋뿐이고 **④ 계약도, hold·평활·트래킹 항목도 없다** — 그러므로 아래 값들은
        //    "계약의 ☐"가 **아니라 계약에 항목이 부재한 것**이다. 두 사실을 섞어 적으면
        //    "팀장이 확정해 주면 된다"로 읽히는데, 그런 칸이 아직 존재하지도 않는다.
        //
        // 🔴 그래서 [GAMMA_MEASUREMENT_VALUE]와 **같은 틀**을 쓴다: 상수 이름에
        //    `_MEASUREMENT_`를 넣고, **제안값이 아니라 비용 봉투를 재기 위한 임의값**이라고
        //    적고, 같은 문장을 `session.json`에도 싣는다([OVERLAY_SMOOTHING_PROVENANCE]).

        // ═══════════════════════════════════════════════════════════════════════
        // 🎛 **④ 오버레이 FSM 튜닝 지점 — 값을 바꾸는 곳은 아래 상수 3개가 전부다.**
        //
        // `HISTORY_MASK`(OverlaySmoother)와 `PENDING` 수명은 진입창에서 **파생되고**,
        // `session.json`의 정책 블록과 근거 문장도 전부 이 상수를 **보간**한다.
        // 🔴 **다른 곳에 값을 적지 않는다** — 적는 순간 둘이 갈리고, `session.json`이
        // 안 도는 정책을 선언하게 된다(옛 `hold_frames`를 지운 이유와 같다).
        //
        // 바꾼 뒤 할 일: release 빌드 → 설치 → 야간 1런 → `overlay_ghost.py`.
        // 🔴 **바꾸면 이전 실측은 이 빌드의 값이 아니다** — baselines의 `entry<W>of<H>`
        // 파일명이 그 경계를 나른다(docs/baselines/README 규약).
        // ═══════════════════════════════════════════════════════════════════════

        /**
         * 진입창 — `PENDING` 트랙의 지지를 세는 **최근 게시 수**. **제안값이 아니라 임의
         * 측정값이다.**
         *
         * 🟢 [com.bammasil.poc.gl.OverlaySmoother]의 히스토리 비트 폭(`HISTORY_MASK`)과
         * `PENDING` 수명이 **이 값에서 파생된다** — 여기만 고치면 둘 다 따라 넓어진다.
         *
         * 🔎 **08-28 밤에 3에서 넓혔다.** 증거 요구([OVERLAY_ENTRY_HITS_REQUIRED_MEASUREMENT_VALUE])는
         * 그대로 두고
         * **인내만 늘린 것**이다. 3일 때는 지지가 게시 0·3에 오면 창이 지나 버려졌다 —
         * 증거가 2회 있는데도 한 번도 안 그려진다. 근거는 `run_ts=20260828_221726`(야간 2런,
         * 진입 3중2): 생성 209·334개 중 승격 **70·88**개뿐이고 **버려진 PENDING이 138·246개**,
         * 표시된 프레임이 **7.8%·14.9%**로 pre(43.2%·44.1%, 단 그중 23.6%p·20.9%p가 잔상
         * 프레임)보다 낮았다.
         *
         * 🔴 **진입만 만진다 — 해제([OVERLAY_HOLD_PUBLISHES_MEASUREMENT_VALUE])는 건드리지
         * 않는다.** 해제를 늦추면 탐지가 끊긴 구간에도 계속 그리게 되는데 그것이 곧 **잔상**
         * 이고, 잔상을 없앤 것이 이 정책의 목적이다. 표시 시간을 되찾는 두 길의 성질이 다르다.
         *
         * 🔴 **지금 값의 효과는 미측정이다.** 위 숫자는 전부 진입창 3에서 잰 것이다.
         * 값을 바꿀 때마다 이 문장이 다시 참이 된다 — 잰 뒤에만 지운다.
         */
        const val OVERLAY_ENTRY_WINDOW_PUBLISHES_MEASUREMENT_VALUE = 4

        /**
         * 진입 조건 — 위 창 안에서 몇 번 지지받아야 `ACTIVE`(그리기 시작)가 되는가.
         * **제안값이 아니라 임의 측정값이다.**
         *
         * ⚠ **이 값이 진입을 늦춘다**: 확인되지 않은 트랙을 그리지 않는 대신 진짜 위험물도
         * 최대 (진입창−1)게시만큼 늦게 뜬다 → [OVERLAY_NO_FLICKER_DESIGN].
         */
        const val OVERLAY_ENTRY_HITS_REQUIRED_MEASUREMENT_VALUE = 2

        /**
         * hold 길이 — 🔴 **표시 프레임이 아니라 게시(publish) 수다.** `ACTIVE` 트랙은 자기를
         * 지지하지 않은 게시가 이만큼 이어지면 그린 목록에서 빠진다(값 1 = **연속 1회 미지지에
         * 즉시 해제**). **제안값이 아니라 임의 측정값이다.**
         *
         * 🟢 게시로 세므로 **표시 FPS에도, 탐지 주기 N에도 딸리지 않는다** — 몇 번의 탐지를
         * 버티는지를 이 상수가 직접 말한다. ⚠ 그러나 **ms로 환산하려면** 그 런의
         * `detect_cadence_ms` 분포가 필요하다 → [OVERLAY_HOLD_CADENCE_NOTE].
         *
         * 🔴 **`ACTIVE`에만 적용한다.** `PENDING`은 진입창이 수명을 정한다.
         */
        const val OVERLAY_HOLD_PUBLISHES_MEASUREMENT_VALUE = 1

        /**
         * 게시 간 박스를 이어 붙이는 IoU 임계 — 그리고 그것이 곧 **지지 판정**이다
         * (이 임계 이상인 측정이 없으면 그 게시는 그 트랙에 대해 "미지지"이며, `ACTIVE`는
         * [OVERLAY_HOLD_PUBLISHES_MEASUREMENT_VALUE]회 연속 미지지에 해제된다).
         * **제안값이 아니라 임의 측정값이다.**
         */
        const val OVERLAY_MATCH_IOU_MEASUREMENT_VALUE = 0.30f

        /**
         * 좌표 IIR 평활 계수. 매 프레임 `s ← s + α(target − s)`이며 **α가 클수록 덜 평활하다.**
         * **제안값이 아니라 임의 측정값이다.**
         */
        const val OVERLAY_IIR_ALPHA_MEASUREMENT_VALUE = 0.35f

        /**
         * 한 게시/한 프레임에 담을 박스 수의 상한. 초과분은 **세고 버린다**
         * (`overlay.dropped_over_cap`) — 조용히 버리지 않는다. **제안값이 아니라 임의 측정값**
         * 이며, [HIGHLIGHT_BOX_COUNT_STRESS]와 같은 값으로 둔 이유는 GL 정점 버퍼의 용량이
         * **한 숫자**로 정해지게 하려는 것이다(정적 arm의 최대와 같다).
         */
        const val OVERLAY_BOX_CAP_MEASUREMENT_VALUE = 32

        /** 🔴 위 여섯 값의 출처 문장. **같은 문장이 `session.json`으로 나간다.** */
        const val OVERLAY_SMOOTHING_PROVENANCE =
            "🔴 **제안값이 아니다 — H칸의 비용 봉투를 재기 위한 임의값이다.** " +
                "entry_window_publishes=" +
                "$OVERLAY_ENTRY_WINDOW_PUBLISHES_MEASUREMENT_VALUE(게시) / " +
                "entry_hits_required=$OVERLAY_ENTRY_HITS_REQUIRED_MEASUREMENT_VALUE / " +
                "hold_publishes=$OVERLAY_HOLD_PUBLISHES_MEASUREMENT_VALUE(게시) / " +
                "match_iou=$OVERLAY_MATCH_IOU_MEASUREMENT_VALUE / " +
                "iir_alpha=$OVERLAY_IIR_ALPHA_MEASUREMENT_VALUE / " +
                "box_cap=$OVERLAY_BOX_CAP_MEASUREMENT_VALUE. " +
                "🟢 **진입·해제를 세는 단위가 게시(publish)다 — 표시 프레임이 아니다.** 그래서 이 " +
                "정책은 **표시 FPS와 탐지 주기 N에 무관하다**: 몇 번의 탐지를 버티고 몇 번을 " +
                "보고 그리기 시작하는지가 상수에 그대로 적혀 있다(옛 hold_frames=18은 둘 다에 " +
                "딸려 있었고, 게시 하나가 18프레임보다 오래 머물면 **아직 그 게시를 쓰는 " +
                "중에도** 박스가 사라졌다 — excess<0 35프레임으로 실측됐다: run_ts=20260828_185222 " +
                "= 08-24 런 20260824_212554 재분석). " +
                "🔴 **그러나 벽시계 잔상 길이는 여전히 cadence에 딸린다** — 게시 수를 ms로 " +
                "환산하려면 그 런의 detect_cadence_ms 분포가 필요하다(hold_cadence_note). " +
                "🔴 **INTERFACES.md에 이 항목 자체가 없다** — 계약은 A(모델)·B(①②)·C(녹화) " +
                "셋뿐이고 ④ 계약도 hold·평활·트래킹 항목도 없다. 그러므로 '계약의 ☐'가 " +
                "아니라 **항목 부재**이며, 확정해 줄 칸이 아직 존재하지 않는다. " +
                "GAMMA_MEASUREMENT_VALUE와 같은 취급이다: 팀이 ④ 항목을 만들면 그때 교체한다. " +
                "⚠ 값 자체가 H의 비용에 미치는 영향은 거의 없다(연결·평활의 일 양은 박스 수가 " +
                "정한다) — 그러나 **화면에 보이는 결과는 크게 달라진다.** 그래서 이 값들은 " +
                "성능 인용이 아니라 **화면 판단**의 조건이며, 값을 옮길 때 이 문장을 함께 옮긴다"

        /** 🔴 hold를 게시로 표현한 것의 함정 — **옛 문장과 방향이 반대다.** `session.json`으로 나간다. */
        const val OVERLAY_HOLD_CADENCE_NOTE =
            "🟢 **hold를 게시 수로 적었으므로 '몇 번의 탐지를 버티는가'는 상수가 직접 " +
                "말한다(hold_publishes=1 = 연속 1회 미지지에 해제).** ⚠ 이 문장은 " +
                "**뜻이 뒤집힌 것이다** — 옛 문장은 hold가 프레임 단위라 몇 탐지를 버티는지 " +
                "모른다고 적었고, 그 서술은 지금 거짓이다. " +
                "🔴 **대신 ms로 환산하려면 detect_cadence_ms 분포가 필요하다.** 탐지 주기 N이 " +
                "미정이라(FRAME_BUDGET.md §7 질문 3) 앱은 idle-gated로 돌고, 실제 주기는 " +
                "하네스가 그 분포로 낸다 — **선언된 N이 아니라 관측값이다.** 그러므로 " +
                "잔상·진입 지연을 **초 단위로** 인용할 때 그 런의 cadence 분포를 함께 옮긴다 " +
                "(run_ts=20260828_185222 = 08-24 런 20260824_212554 재분석, warmup 30s 제외 n=358: " +
                "detect_cadence_ms p50 276.315 / p95 741.653 / max 1263.696ms — 꼬리가 " +
                "길다). " +
                "🔴 **H의 1회 비용을 프레임당으로 환산하지 말 것** — H는 탐지 주기마다가 아니라 " +
                "**표시 프레임마다** 돈다(그래서 stage_h_ms가 프레임당 1행이다). 환산하면 " +
                "탐지 주기에 딸린 값처럼 보이는데 그렇지 않다"

        /**
         * 🔴 **깜빡임을 만들지 않기 위해 설계에서 무엇을 했는가.** 이 문장은 "깜빡이지
         * 않았다"는 **주장이 아니다** — 그 주장의 근거는 하네스의 `overlay.flicker`뿐이다
         * ([HIGHLIGHT_BLINK_NOT_A_PERF_CLAIM]).
         */
        private const val OVERLAY_NO_FLICKER_DESIGN_HEAD =
            "설계에서 막은 것 넷: " +
                "(1) **점멸·펄스·알파 변조가 코드에 없다** — 광과민 사용자에게 안전 이슈로 " +
                "규정된 항목이라(no_blink_reason) 밝기·알파를 **시간에 따라** 바꾸는 경로를 " +
                "두지 않았다. "

        /**
         * 🔴 (1)의 **블렌딩을 켜는 사유** — fill arm 판.
         *
         * ⚠ 갈리는 것은 **사유뿐이다.** 뒤에 오는 "알파 변조가 없다"는 본문은 두 arm에서
         * 그대로 참이므로 [OVERLAY_NO_FLICKER_DESIGN_TAIL]에 공유로 남긴다.
         */
        private const val OVERLAY_NO_FLICKER_BLEND_FILL =
            "⚠ **블렌딩은 켠다** — 박스 안쪽 fill이 반투명이기 때문이다" +
                "(fill_deviation). 예전 문장('블렌딩 자체를 켜지 않는다')은 이 빌드에서 " +
                "**거짓이므로** 갈아 끼웠다. " +
                "🔴 **그러나 알파는 빌드 상수 " +
                "$OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE(fill_alpha)이며 프레임 간에도 " +
                "시간에 따라서도 변하지 않는다** — 변조가 없다는 사실이 이 항목의 " +
                "내용이고 그것은 그대로 참이다. 스트로크의 알파는 1.0 고정이다. "

        /**
         * 🔴 같은 사유의 **fill 대조군** 판. 이 arm은 채우지 않지만 블렌딩을 **켠 채 둔다** —
         * 끄면 차분에 GL 상태 변경 비용이 섞인다([HIGHLIGHT_NOFILL_CONTROL_NOTE] (a)).
         */
        private const val OVERLAY_NO_FLICKER_BLEND_NOFILL =
            "⚠ **이 arm은 채우지 않지만 블렌딩은 켠 채 둔다** — 짝 arm과 GL 상태를 같게 " +
                "두기 위한 것이다(전문은 overlay.fill). 스트로크의 알파가 1.0이라 픽셀은 " +
                "블렌딩 OFF와 **비트 단위로 같다.** " +
                "🔴 **이 arm은 fill 알파를 쓰지 않는다(fill_alpha=null).** 그리는 " +
                "알파는 스트로크의 **1.0 고정**뿐이며 프레임 간에도 시간에 따라서도 " +
                "변하지 않는다 — 변조가 없다는 사실이 이 항목의 내용이고 그것은 " +
                "**두 arm에서 그대로 참이다.** 짝 arm의 빌드 상수 알파는 그쪽 " +
                "fill_alpha와 fill_alpha_provenance가 말한다. "

        private const val OVERLAY_NO_FLICKER_DESIGN_TAIL =
            "**페이드아웃도 없다** — 잔상을 부드럽게 " +
                "지우고 싶어지는 자리이지만 알파 변조라 규약 위반이다. " +
                "(2) **한 게시를 쓰는 동안에는 끊지 않는다** — 갱신이 안 온 표시 프레임에 " +
                "박스를 0으로 떨어뜨리지 않고 마지막 좌표를 계속 그린다. 사라졌다 나타나는 " +
                "구간이 곧 깜빡임이기 때문이다. 🔴 **상태 전이는 게시 단위로만 돈다**(표시 " +
                "프레임 단위가 아니다) — 그래서 게시 하나가 아무리 오래 머물러도 그것을 " +
                "쓰는 중에 박스가 사라지는 일이 **구조적으로 없다.** 옛 프레임 단위 hold에서는 " +
                "있었다(run_ts=20260828_185222: excess<0 35프레임, 결손 시작이 전부 18프레임 자리). " +
                "(3) **확인된 트랙만 그린다(PENDING/ACTIVE)** — 새로 잡힌 박스는 PENDING으로 " +
                "태어나 **그리지 않고**, 최근 " +
                "${OVERLAY_ENTRY_WINDOW_PUBLISHES_MEASUREMENT_VALUE}게시 중 " +
                "${OVERLAY_ENTRY_HITS_REQUIRED_MEASUREMENT_VALUE}회 지지받으면 ACTIVE로 " +
                "승격해 그리기 시작한다. ACTIVE는 연속 " +
                "${OVERLAY_HOLD_PUBLISHES_MEASUREMENT_VALUE}회 미지지에 해제된다. " +
                "🔴 **진입창은 PENDING에만, 해제는 ACTIVE에만 건다** — 해제를 PENDING에도 " +
                "걸면 '창 안에서 ${OVERLAY_ENTRY_HITS_REQUIRED_MEASUREMENT_VALUE}회'가 '연속 " +
                "${OVERLAY_ENTRY_HITS_REQUIRED_MEASUREMENT_VALUE}회'와 같아져 진입 규칙이 사문화된다. " +
                "🔴 **PENDING은 진입을 늦춘다 — 이 설계가 지불하는 값이다.** 확인되지 않은 " +
                "트랙을 그리지 않으므로 1회짜리 오탐이 화면에 번쩍이지 않는 대신, **진짜 " +
                "위험물도 최대 (진입창−1)게시만큼 늦게 뜬다.** ⚠ **해제가 k=1이라 탐지가 " +
                "1게시 끊기면 박스가 사라지고 다시 뜨는 데 2게시가 걸린다** — 옛 " +
                "hold(18프레임)가 그 끊김을 메우고 있었다. 🔴 **맞바꿈이 야간에서 실측됐다 — 단 " +
                "그것은 진입창이 3일 때의 값이고 이 빌드의 값이 아니다.** " +
                "run_ts=20260828_221726(야간 2런, 같은 코스, 진입 3중2): 진입 결손 " +
                "63·104회(22.3·40.3/분) · 미표시 max 1710ms · 표시된 프레임 7.8%·14.9% " +
                "(pre 43.2%·44.1%, 그중 23.6%p·20.9%p는 잔상 프레임) · 생성 209·334개 중 " +
                "승격 70·88개 · 버려진 PENDING 138·246개. 그래서 창을 넓혔다 " +
                "(증거 요구는 그대로, 인내만 늘림). 🔴 **넓힌 뒤의 값은 미측정이다** — " +
                "이 맞바꿈은 하네스의 overlay_ghost.pending과 overlay.flicker가 사후에 판정한다. " +
                "🔴 **상태 기계는 새 게시가 왔을 때만 돈다 — 같은 스냅샷을 다시 보는 " +
                "프레임에서는 아무 전이도 없다.** 게시 슬롯은 새 게시가 올 때까지 직전 " +
                "스냅샷을 보존하므로(정상 동작), 프레임마다 지지로 세면 **탐지 워커가 멈추거나 " +
                "매 프레임 실패해도 낡은 좌표의 박스가 무한히 그려진다.** 야간 보행 보조에서 " +
                "낡은 위험물 위치를 현재인 것처럼 계속 보여 주는 것이 더 위험하다. " +
                "(4) **크기·위치는 IIR로만 움직인다** — 새 좌표로 순간 이동하지 않고 " +
                "iir_alpha로 수렴한다(태어나는 자리만 예외이며, 거기서 0에서 자라게 하면 " +
                "그게 곧 튐이다). IIR은 **매 프레임** 돌아 한 게시를 쓰는 ≈9프레임 동안 계속 " +
                "수렴한다 — 상태 전이와는 별개다(PENDING도 수렴시켜 둔다: 승격되는 순간에 " +
                "이미 목표점에 있어야 튀지 않는다). " +
                "⚠ **박스가 사라지는 동작은 남아 있고 그것이 정상이다.** 두 경우에 일어난다: " +
                "(a) 새 게시가 왔는데 그 박스가 빠졌다(장면에서 실제로 사라졌을 수 있다), " +
                "(b) **게시가 아예 끊겼다**(탐지가 멈췄거나 매 프레임 실패했다 — 그때는 " +
                "새 게시가 없으므로 전이도 없고 박스는 그대로 남는다). 🔴 **둘 다 깜빡임이 " +
                "아니다** — 빠른 on/off가 아니라 탐지가 끊긴 사실의 **정직한 표시**다. " +
                "하네스가 그것을 `drew_then_stopped`로 지목하면 **그 지목이 맞는 것이고**, " +
                "원인이 (a)인지 (b)인지는 그 지표가 가르지 못한다" +
                "(detect.run의 errors·inferences_run과 함께 읽어야 갈린다). " +
                "그 사라짐은 로그에 그대로 남는다(overlay_boxes의 >0→0 전이·뒷자락). " +
                "⚠ 그리고 박스가 크게 튀면 이어지지 않고 **새 박스가 태어난다**(match_iou " +
                "미달) — 그때 이전 ACTIVE 박스는 해제될 때까지 함께 그려지므로 잠깐 둘로 " +
                "보일 수 있다. **그 실패 방향을 일부러 택했다**: 잠깐 하나 더 그리는 것이 " +
                "깜빡이는 것보다 안전하다"

        /**
         * 🔴 fill arm 판의 전문. **KDoc 참조와 사람이 읽는 앵커가 이 이름이다**
         * ([OverlaySmoother]·[HighlightOverlay]의 주석이 이 이름으로 (1)을 가리킨다).
         *
         * ⚠ **사본이 아니라 위 조각들의 합이다.** `session.json`에 실을 값은
         * [overlayNoFlickerDesign]에 arm의 fill 여부를 넣어 얻는다.
         */
        const val OVERLAY_NO_FLICKER_DESIGN =
            OVERLAY_NO_FLICKER_DESIGN_HEAD + OVERLAY_NO_FLICKER_BLEND_FILL +
                OVERLAY_NO_FLICKER_DESIGN_TAIL

        /**
         * 🔴 **깜빡임 방지 설계의 arm별 판.** 갈리는 것은 (1)의 **블렌딩을 켜는 사유** 하나뿐
         * 이고, **알파 변조가 없다는 본문과 (2)(3)(4)는 두 arm에서 그대로 참**이라 공유한다
         * ([chainHighlightTileReloadNote]와 같은 틀이다).
         *
         * @param fill 이 arm이 fill quad를 실제로 그리는가([drawsOverlayFill]).
         */
        fun overlayNoFlickerDesign(fill: Boolean): String =
            OVERLAY_NO_FLICKER_DESIGN_HEAD +
                (
                    if (fill) OVERLAY_NO_FLICKER_BLEND_FILL
                    else OVERLAY_NO_FLICKER_BLEND_NOFILL
                    ) +
                OVERLAY_NO_FLICKER_DESIGN_TAIL

        /** 🔴 `stage_h_ms` 구간이 **정확히 무엇을 감싸는가**. `session.json`으로 나간다. */
        const val OVERLAY_STAGE_H_SCOPE =
            "🔴 **CPU 벽시계**(SystemClock.elapsedRealtimeNanos, GL 스레드)이며 GPU query가 " +
                "아니다 — gpu_sum_ms에도 stage_d_total_ms에도 들어가지 않는다. " +
                "구간은 **GPU 패스를 열기 전에 닫힌다**(gpuTimer.beginFrame보다 앞이다). " +
                "안에 있는 것: (새 게시일 때만) 스냅샷의 박스를 NDC로 매핑 → 살아 있는 " +
                "트랙과 IoU 연결 → 지지 히스토리 갱신 → **상태 전이**(PENDING 승격·폐기 / " +
                "ACTIVE 해제) → 새 트랙 탄생, 그리고 **매 프레임** 좌표 IIR 평활 → " +
                "**ACTIVE만 골라 정점 버퍼에 in-place 재기록**" +
                "까지다. 정점 재기록을 일부러 포함했다 — 그 CPU 비용이 GPU query 안에 있으면 " +
                "(GPU 시간을 재는 query라) **어디에도 계상되지 않는다.** " +
                "밖에 있는 것: `latest()` 참조 읽기 하나(t_render_start_ns를 찍기 **전에** " +
                "읽는다 — 그래야 어떤 프레임도 자기 렌더 시작보다 미래에 게시된 결과를 쓰지 " +
                "않는다)와 오버레이 GPU 패스(그쪽은 stage_i_ms다)"

        /** 🔴 `overlay.box_count`가 null인 이유. `session.json`으로 나간다. */
        const val OVERLAY_DYNAMIC_BOX_NOTE =
            "🔴 **이 arm의 박스 개수는 선언된 조건이 아니라 프레임마다 다른 관측값이다.** " +
                "그래서 box_count가 null이고, 조건은 frames.csv의 **overlay_boxes** 열이 " +
                "프레임별로 말한다(그 열에서 **0은 정상값**이다 — 그 프레임에 그린 박스가 " +
                "없었다는 뜻이고 야간 보행에서는 그런 프레임이 다수다). " +
                "⚠ 그러므로 이 arm의 stage_i_ms·stage_h_ms를 **개수 없이 인용하지 말 것** — " +
                "정적 arm에서 box_count가 필수 조건인 것과 같은 논거이며, 여기서는 그 조건이 " +
                "분포다. 정적 arm의 개당 기울기로 나눠 검산하지도 말 것(박스 크기가 다르다)"

        /** 🔴 게시가 할당을 한다는 사실과 그것이 어디에 있는가. `session.json`으로 나간다. */
        const val OVERLAY_PUBLISH_ALLOCATION_NOTE =
            "🔴 **게시당 객체를 만든다**(스냅샷 1개 + 배열 3개 + 박스 복사 목록). 게시는 실측 " +
                "약 3.4Hz이고 그 자리는 **E·F·G 구간 밖**이다 — DetectPipeline.infer에서 gNs가 " +
                "확정되고 detect.csv 행 기록이 끝난 뒤, parity 덤프와 **같은 자리**다. " +
                "그래서 이 할당이 stage_e/f/g_ms에 섞이지 않고, 승격된 F 실측과의 비교도 " +
                "끊기지 않는다(그 구간의 코드는 바이트 단위로 그대로다). " +
                "🔴 **GL 스레드에는 프레임당 할당이 없다**: latest()는 참조 읽기 하나이고, " +
                "평활 트랙·그릴 목록·정점 버퍼는 전부 상한 크기로 **한 번** 잡아 두고 " +
                "in-place로 재기록한다. GL 스레드에서 GC가 돌면 그것이 곧 프레임타임 꼬리다"

        // ── 통합 arm(`detect_cpu_chain_highlight` 계열) ───────────────────
        // 🔴 이 arm은 **측정용 추가**이고 제품 구성 결정이 아니다. 아래 네 문장이 그 사실과
        //   읽는 법·계측 한계를 담고 `session.json`으로 나간다.
        // 🔴 **네 문장은 arm 중립으로 쓴다** — 통합 arm에는 이제 짝이 셋 있고
        //   ([DETECT_CPU_CHAIN] · [DETECT_CPU_CHAIN_1Q] · [DETECT_CPU_CHAIN_HIGHLIGHT_1Q])
        //   같은 문장이 그 arm들의 `session.json`에도 실린다. 어느 arm이 실어도 참인 문장이어야
        //   한다 — "이 arm은 9패스다" 같은 arm 고유 사실을 여기 넣으면 8패스 arm의 로그에서
        //   거짓이 된다(그 부류의 서술은 `SessionWriter`의 arm별 분기가 맡는다).

        /** 🔴 이 문장이 빠지면 "제품 구성이 정해졌다"로 읽힌다. `session.json`으로 나간다. */
        const val CHAIN_HIGHLIGHT_NOT_A_PRODUCT_DECISION =
            "🔴 **이 arm은 ②③④를 한 프레임에서 돌려 보기 위한 측정용 추가이며 제품 구성 " +
                "확정이 아니다.** 팀 결정 4건이 아직 미결이다: (1) 융합 채택 여부" +
                "(drago_clahe_fused는 알고리즘 변경이라 팀장 판단이다 — fused_deviation), " +
                "(2) bf 포함 여부, (3) INTERFACES.md §B-4의 시간축(ts)이 ☐, " +
                "(4) 탐지 주기 N이 ☐(FRAME_BUDGET.md §7 질문 3). " +
                "그러므로 이 arm은 **상류 잠정 1위(D1A1+bf+ts)와 같은 구성이 아니다** — " +
                "② 자리는 D1A1까지이고 bf도 ts도 없다. 이 런의 숫자를 '제품 구성의 " +
                "프레임타임'으로 옮겨 적지 말 것"

        /**
         * ⚠ 탐지 입력이 ②를 거치지 않는다는 사실. 🔴 **결함이 아니라 상류 요구다.**
         * 모델 패키지가 같은 경고를 `metadata.json`의 `warning`에 싣고 있다.
         */
        const val CHAIN_HIGHLIGHT_DETECT_INPUT_NOTE =
            "🔴 **이 arm에서도 ③ 탐지 입력은 ② 개선을 거치지 않은 원본 프레임이다.** 표시 " +
                "경로(OES → GL 9패스)와 탐지 경로(ImageAnalysis YUV → 전처리 → ORT)가 애초에 " +
                "분리돼 있어 ②가 탐지 입력에 닿지 않는다. 우연이 아니라 상류 요구다: ②를 탐지 " +
                "앞단에 붙이면 stairs 야간 오탐이 **0.1% → 5.7%(57배)**가 된다" +
                "(models/0824/readme_c4e_640.md §6-4 · 같은 경고가 metadata.json의 warning). " +
                "⚠ 그러므로 이 arm의 boxes_out·overlay_boxes는 **② 적용 전 프레임의 탐지 " +
                "결과**이고, ②가 탐지 품질에 준 영향을 이 런으로 말할 수 없다(그 실험이 아니다)"

        /**
         * 🔴 패스7과 패스8의 **타깃이 같은 FBO_A**라 두 열의 경계가 흐려진다.
         * `PassthroughRenderer.drawHighlightOverlay`가 패스2·3에 대해 적은 것과 **같은 자리**다.
         *
         * 🔴 **상수가 아니라 함수인 이유:** 물리 사실(타일 재적재가 일어난다 · 패스7↔8이 같은
         * FBO_A다)은 [usesChainedHighlight]인 **두 arm 모두에서 참**이지만, 그 비용이 **어느
         * 열에 앉는가**는 계측 방식이 정한다. 프레임 단일 query 짝
         * ([DETECT_CPU_CHAIN_HIGHLIGHT_1Q])에는 `stage_i_ms`도 `stage_d_apply2_ms`도 없으므로
         * 열 이름을 박아 두면 **CSV에 없는 열을 사실로 지목**하고, 같은 블록의
         * `overlay.gpu_column_note`("이 arm에는 stage_i_ms가 없다")와 정면으로 모순된다
         * (`SessionWriter.buildOverlay`의 `iCostPhrase`가 같은 지적에서 나왔다 — 그 값을
         * 그대로 받아 두 자리의 표현이 갈라지지 않게 한다).
         *
         * @param iCostPhrase ④ 오버레이 비용이 이 arm에서 앉는 열. **호출부가 하나만 만들어**
         *   이 함수와 비-체인 분기가 함께 쓴다.
         * @param singleFrameQuery 프레임 단일 query arm인가. **열 귀속만** 갈리고 물리 사실은
         *   두 arm이 같다 — 문장을 지우지 않고 지목만 바꾼다.
         */
        fun chainHighlightTileReloadNote(
            iCostPhrase: String,
            singleFrameQuery: Boolean,
        ): String {
            // 🔴 두 갈래가 답하는 것은 **귀속**뿐이다. 앞뒤 문장은 공유하므로 물리 서술이
            //    한쪽만 고쳐지는 일이 생기지 않는다.
            val attribution = if (singleFrameQuery) {
                "🔴 그리고 **패스7(clahe apply)과 패스8의 타깃이 같은 FBO_A**라 드라이버가 두 " +
                    "렌더패스를 병합할 수 있다 — 다만 **이 arm에서는 두 열의 경계 문제가 " +
                    "생기지 않는다.** 귀속을 가를 패스별 열이 애초에 없기 때문이다. 그 대신 " +
                    "어느 패스가 비쌌는지를 이 arm에서 낼 수 없고(gpu_column_note · " +
                    "how_to_compare), 경계가 흐려지는 문제는 짝 arm의 stage_d_apply2_ms ↔ " +
                    "stage_i_ms에서 본다. "
            } else {
                "🔴 게다가 **패스7(clahe apply)과 패스8의 타깃이 같은 FBO_A**라 드라이버가 두 " +
                    "렌더패스를 병합하면 stage_d_apply2_ms와 stage_i_ms의 경계가 흐려진다 — " +
                    "4패스 오버레이 arm에서 패스2·3(둘 다 FBO_B)에 대해 적은 것과 " +
                    "**같은 자리**다. "
            }
            return "⚠ 오버레이 패스(패스8)는 **glClear를 부르지 않는다** — ② 체인의 출력 위에 " +
                "얹기 때문이다(지우면 ② 결과가 사라진다). 그래서 타일 GPU가 컬러 어태치먼트를 " +
                "다시 load하고 **${iCostPhrase}에는 그 비용이 섞여 있다.** 오버레이 패스의 " +
                "실제 비용이며 빼낼 수단이 없다. " +
                attribution +
                "일반 주의사항은 gpu_timer.attribution_note와 같다. 패스 사이에 바인드·뷰포트를 " +
                "다시 명시해 쪼갤 기회를 주는 것까지가 우리가 할 수 있는 일이다"
        }

        /**
         * 🔴 ②③④ 통합 세트에서 **I의 상한·하한이 각각 어느 arm에서 나오는가**. `session.json`
         * 으로 나간다(`stage2_params.bounds_note` · `overlay.bounds_note`).
         *
         * 🔴 **arm 중립 문장이다** — 세트의 네 arm 중 어느 것이 실어도 참이어야 한다. 예전 이름
         * (`CHAIN_HIGHLIGHT_NO_LOWER_BOUND`)과 예전 키(`no_lower_bound_note`)는 통합 arm에
         * 짝이 없던 시절의 것이라 **하한의 분자가 되는 arm 자신이 "하한을 낼 수 없다"를 싣게
         * 된다.** 그래서 이름과 키를 함께 바꿨다.
         *
         * ⚠ 예전 문장의 "I칸·H칸의 하한"은 **범주 오류**였다 — H는 CPU 벽시계이고 모든 GPU
         * query 밖에서 닫힌다([OVERLAY_STAGE_H_SCOPE]). 그러므로 `gpu_frame_ms` 차분에 H는
         * 물리적으로 들어 있지 않고, H는 차분으로 유도할 대상이 아니라 `stage_h_ms` 열로
         * **직접 측정**된다.
         */
        const val CHAIN_HIGHLIGHT_BOUNDS_NOTE =
            "🔴 **②③④ 통합 세트에서 I의 상한과 하한은 서로 다른 arm에서 나온다 — 한 arm의 " +
                "로그만 보고 둘을 다 얻을 수 없다.** " +
                "**I 상한** = detect_cpu_chain_highlight의 stage_i_ms다. 패스별 계측이라 " +
                "중복 계상하므로(알려진 이슈 21) 그 값이 상한이다. " +
                "**I 하한** = detect_cpu_chain_highlight_1q − detect_cpu_chain_1q이며 " +
                "**둘 다 프레임 단일 query일 때만** 뜻이 있다(GL_TIME_ELAPSED가 중첩되지 않아 " +
                "패스별 계측과 섞을 수 없다). 🔴 **같은 세션·같은 빌드에서 잰다** — 다른 세션의 " +
                "값과 빼면 발열·조명·AE 상태의 차이가 중복 계상량으로 둔갑한다. " +
                "🔴 **분모가 drago_clahe_chain_1q가 아닌 이유:** 거기엔 탐지 부하가 없다" +
                "(그 arm은 ORT 세션을 열지 않고 detect.csv도 내지 않는다). 알려진 이슈 36이 " +
                "그 부류다 — 분모를 잘못 고르면 하한이 0으로 나오고 그 0은 '오버레이가 공짜'가 " +
                "아니라 **분모가 상한을 통째로 중복 계상했다**는 뜻이었다. " +
                "🔴 **H는 하한·상한의 대상이 아니다.** stage_h_ms는 CPU 벽시계 직접 측정이고 " +
                "모든 GPU query **밖**에서 닫힌다(overlay.smoothing.scope) — gpu_frame_ms " +
                "차분에 H는 물리적으로 들어 있지 않으므로 'H의 하한'은 범주 오류다. H는 " +
                "오버레이 arm들이 stage_h_ms 열로 **직접** 낸다(개수를 여기 적지 않는다 — " +
                "arm이 늘면 이 문장이 낡는다. 목록의 출처는 usesHighlightOverlay다). " +
                "⚠ 오버레이가 없는 두 arm(detect_cpu_chain / _1q)에는 stage_i_ms도 stage_h_ms도 " +
                "없다 — 그 arm들의 자리는 **분모**이고 그것이 이 세트에서 그 arm의 뜻 전부다"

        // ── 프레임 단일 query arm(`*_1q`) ─────────────────────────────────
        // 🔴 **이 arm들은 알고리즘이 아니라 계측 방식이 다르다.** 아래 네 문장이 그 사실과
        //   읽는 법을 담고, `session.json`의 gpu_timer / stage2_params 양쪽으로 나간다.
        //   한쪽만 읽어도 오독하지 않도록 두 자리에 함께 싣는다.

        /**
         * 이 arm들이 싣는 유일한 GPU 열. 하네스 어휘(`lib/frame_log.py`의 `GPU_FRAME_COLUMN`)와
         * **글자까지** 같아야 한다.
         *
         * ⚠ enum 상수의 생성자 인자로는 쓸 수 없다(초기화 순서). 그쪽에는 같은 문자열을
         * 직접 적었고, 어긋나면 [SINGLE_FRAME_QUERY_COLUMN_MISMATCH]가 잡는다.
         */
        const val SINGLE_FRAME_QUERY_COLUMN = "gpu_frame_ms"

        /**
         * 열 이름 사본이 어긋났는가. null이면 일치한다.
         * **선언과 실제가 조용히 갈라지는 것을 막는 자기검사**이며 `session.json`에 나간다.
         */
        val SINGLE_FRAME_QUERY_COLUMN_MISMATCH: String?
            get() {
                val wrong = entries
                    .filter { it.usesSingleFrameQuery }
                    .filter { it.gpuColumns != listOf(SINGLE_FRAME_QUERY_COLUMN) }
                    .map { it.id }
                return if (wrong.isEmpty()) {
                    null
                } else {
                    "프레임 단일 query arm의 gpuColumns가 [$SINGLE_FRAME_QUERY_COLUMN] 하나가 " +
                        "아니다: $wrong — 열과 query 개수가 어긋난 채로 숫자가 나간다"
                }
            }

        const val SINGLE_QUERY_WHAT_DIFFERS =
            "🔴 **이 arm은 알고리즘이 아니라 계측 방식이 다르다.** 렌더 경로는 짝 arm과 " +
                "**글자 그대로 같다** — 같은 draw 함수를 타고(RenderArm.uses* 판별식에 이 arm을 " +
                "함께 넣었다) 셰이더·패스 수·파라미터가 전부 같다. 다른 것은 GPU timer query를 " +
                "거는 방식 하나뿐이다: 패스마다 하나씩(짝 arm) 대신 **프레임 전체를 query " +
                "하나로** 감싼다(GL_TIME_ELAPSED는 중첩되지 않아 둘을 같이 걸 수 없다 — 그래서 " +
                "arm으로 갈랐다). 그러므로 두 arm의 프레임타임·지연 분포는 **같아야 하고**, " +
                "다르면 그것 자체가 계측 오버헤드의 증거다. " +
                "왜 만들었는가: 직전 라운드의 패스별 계측에서 gpu_sum(패스별 query의 합)이 " +
                "**물리적으로 불가능한 값**을 냈다 — chain_bf가 29.92fps × 43.794ms = 1초에 " +
                "1.31초의 GPU 작업이고, 30fps는 스톨·드롭 없이 유지됐으며 행 단위로 95.8%의 " +
                "행에서 gpu_sum이 그 프레임의 출력 간격을 넘었다. 원인 후보는 " +
                "**gpu_present_ms가 마지막 전체화면 패스의 타일 해결을 흡수해 중복 계상**하는 " +
                "것이다(present는 전 arm에서 같은 셰이더인데 query 값이 1.862 → 15.078로 " +
                "부풀었고, 부풀림이 마지막 전체화면 패스 비용의 73~88%다. 마지막 패스가 얇은 " +
                "quad인 ④ 오버레이 arm은 +0.010(2%)로 대조군이 된다). " +
                "⚠️ 여기 적힌 수치는 **이 문자열이 쓰인 시점의 사본**이며 출처는 그 런의 " +
                "summary.json과 그 라운드 팀 보고서다 — 어긋나면 그쪽이 맞다"

        const val SINGLE_QUERY_NOT_A_SUM =
            "🔴 **gpu_frame_ms는 gpu_sum_ms와 다른 물리량이다.** 프레임 하나를 query 하나로 " +
                "감싼 값이고, 패스별 열의 합이 아니다 — **둘을 더하지 말 것**(더하면 같은 " +
                "프레임을 두 번 세는 것이다). 같은 이유로 D 계열도 아니고" +
                "(stage_d_total_ms에 들어가지 않는다) **버짓 칸도 없다** — 단계 비용이 아니라 " +
                "프레임 전체 GPU 시간이므로 칸 라벨을 붙이면 그 숫자가 D칸으로 인용된다. " +
                "한 런에 이 열과 패스별 열이 **함께 있을 수 없다**(GL_TIME_ELAPSED가 중첩되지 " +
                "않는다) — 그래서 이 arm의 CSV에는 패스별 열이 아예 없다"

        const val SINGLE_QUERY_HOW_TO_COMPARE =
            "🔴 **비교는 같은 세션·같은 빌드의 짝 arm과 나란히 놓고 한다.** " +
                "gpu_frame_ms(이 arm) 대 gpu_sum_ms(짝 arm)의 **차가 곧 패스별 계측의 중복 " +
                "계상량**이다 — 그것이 이 arm의 존재 이유다. " +
                "⚠ 짝은 렌더가 같은 arm 하나뿐이다: blit_2pass_1q↔blit_2pass · " +
                "drago_clahe_chain_1q↔drago_clahe_chain · " +
                "drago_clahe_chain_bf_1q↔drago_clahe_chain_bf · " +
                "drago_clahe_fused_1q↔drago_clahe_fused · " +
                "drago_clahe_fused_bf_1q↔drago_clahe_fused_bf · " +
                "highlight_boxes_1q↔highlight_boxes · " +
                "detect_cpu_highlight_1q↔detect_cpu_highlight · " +
                "detect_cpu_1q↔detect_cpu · " +
                "detect_cpu_chain_1q↔detect_cpu_chain · " +
                "detect_cpu_chain_highlight_1q↔detect_cpu_chain_highlight. 다른 arm과 " +
                "짝지으면 렌더가 달라 그 차분은 아무 뜻이 없다. " +
                "🔴 **I 하한의 분모는 세트마다 다르다 — 하나가 아니다.** " +
                "③→④ 세트(v7)는 `detect_cpu_highlight_1q − detect_cpu_1q`이고, " +
                "②③④ 통합 세트는 `detect_cpu_chain_highlight_1q − detect_cpu_chain_1q`다. " +
                "분모는 **분자에서 ④ 오버레이만 뺀 arm**이어야 하며(② 구성과 탐지가 같아야 " +
                "한다), `blit_2pass_1q`나 `drago_clahe_chain_1q`를 분모로 쓰면 **거기에 탐지 " +
                "부하가 없어서** 차이에 탐지 비용이 섞인다. 알려진 이슈 36이 그 부류다" +
                "(`highlight_boxes_1q`가 분모와 소수점 셋째 자리까지 같아 I 하한이 0으로 " +
                "나왔고, 그 0은 '오버레이가 공짜'가 아니라 분모가 상한을 통째로 중복 계상했다는 " +
                "뜻이었다). " +
                "🔴 **H는 이 차분의 대상이 아니다** — stage_h_ms는 CPU 벽시계이고 모든 GPU " +
                "query 밖에서 닫히므로(overlay.smoothing.scope) gpu_frame_ms 차분에 들어 있지 " +
                "않다. H는 오버레이 arm이 그 열로 직접 낸다. " +
                "🔴 **부풀림 비율을 arm 사이에서 옮기지 말 것** — 중복 계상량은 마지막 " +
                "전체화면 패스의 비용을 따라가므로 패스 구성마다 다르다(같은 라운드에서 " +
                "④ 오버레이 arm +2% / 9패스 arm +43%). 하한이 필요하면 **그 arm의 `_1q` " +
                "짝을 직접 재는 수밖에 없다** — 뒤의 세 짝이 생긴 이유가 그것이다. " +
                "⚠ **다른 세션의 짝과 비교하지 말 것** — 발열·조명·AE 상태가 다르면 그 차이가 " +
                "중복 계상량으로 둔갑한다. " +
                "⚠ 이 arm은 **패스별 분해를 낼 수 없다.** 어느 패스가 비싼지는 짝 arm의 열이 " +
                "말하고, 그 열들이 얼마나 부풀어 있는지는 이 arm이 말한다 — 둘 다 필요하다"

        const val SINGLE_QUERY_LOWER_BOUND_NOTE =
            "🔴 **프레임 단일 query로도 이 값은 여전히 하한이다.** 마지막 패스는 기본 " +
                "프레임버퍼에 그리는데 그 타일 해결은 eglSwapBuffers에서 일어나고 " +
                "GLSurfaceView는 그것을 onDrawFrame 반환 **후에** 부른다 — 프레임 단일 query의 " +
                "**바깥**이다(GLSurfaceView를 쓰는 한 옮길 수 없다. 패스별 계측의 " +
                "attribution_note와 같은 이유다). 그러므로 이 실험이 재는 것은 **'중복 계상량의 " +
                "하한'이지 '진짜 GPU 시간'이 아니다** — gpu_frame_ms를 '이 arm의 실제 프레임 " +
                "GPU 시간'으로 옮겨 적지 말 것"

        const val HIGHLIGHT_HOW_TO_COMPARE =
            "🔴 **이 arm의 stage_i_ms를 '박스 하나의 비용'으로 읽지 말 것** — 개수는 우리가 " +
                "선언한 조건이고(box_count) 그 개수에서의 값이다. 개당 기울기는 " +
                "highlight_boxes(${HIGHLIGHT_BOX_COUNT}개)와 highlight_boxes_stress" +
                "(${HIGHLIGHT_BOX_COUNT_STRESS}개)의 **차분을 개수 차로 나눠** 얻는다(박스 크기가 " +
                "두 arm에서 같아 성립한다). " +
                "⚠ stage_i_ms에는 **컬러 어태치먼트를 다시 load하는 비용이 섞여 있다** — " +
                "오버레이는 ② 출력 위에 얹으므로 glClear를 부를 수 없고, 타일 GPU는 그때 이전 " +
                "내용을 타일로 다시 읽어 온다. 그게 오버레이 패스의 실제 비용이며 빼낼 수단이 " +
                "없다(패스 경계·귀속의 일반 주의사항은 gpu_timer.attribution_note). " +
                "⚠ 이 arm의 ② 자리는 **단순 복사**다 — blit_2pass와 같은 골격이므로 " +
                "gpu_sum 차분의 짝은 blit_2pass다"

        // ── ③ 탐지 arm 상수 ───────────────────────────────────────────────
        // 모델 계약값·전처리 가정은 여기 두지 않는다 — `detect/DetectContract.kt`가 소유한다.
        // 여기 있는 것은 **arm이라는 측정 조건**에 딸린 문장뿐이다.

        /**
         * 🔴 **`_prof` arm의 시간을 인용하지 않는다.**
         *
         * ORT 프로파일러는 노드마다 기록을 남기므로 F(그리고 그것을 포함하는 모든 값)에
         * **자기 비용을 얹는다.** 이 arm은 "어느 노드가 어느 EP에 갔고 무엇이 비싼가"를 보는
         * 장치이고, E·F·G 숫자와 버짓 칸은 **접미사 없는 짝(`detect_cpu`/`detect_nnapi`/
         * `detect_xnnpack`)에서만** 인용한다. `_1q` 접미사와 같은 취지로 arm을 가른 것이다 —
         * 계측 방식이 다르면 같은 코드라도 같은 조건이 아니고, 그 사실을 담을 키가
         * `pipeline_stages`에는 없다.
         */
        const val DETECT_PROF_NOT_QUOTABLE =
            "🔴 **이 arm의 시간을 인용하지 말 것.** ORT 프로파일러가 노드마다 기록을 남겨 " +
                "추론 시간에 자기 비용을 얹는다. 이 arm이 답하는 질문은 '어느 노드가 어느 " +
                "EP에 배치됐는가' 하나이고, E·F·G와 버짓 칸은 접미사 없는 짝 arm" +
                "(detect_cpu / detect_nnapi / detect_xnnpack)에서만 인용한다"

        /**
         * 🔴 **`detect_parity_*` arm의 시간도 인용하지 않는다.**
         *
         * [DETECT_PROF_NOT_QUOTABLE]이 `_prof`에 대해 하는 일과 **같은 자리·같은 취지**다 —
         * 사유만 다르다(프로파일러가 아니라 **덤프 I/O**다). 샘플당 ~7MB를 디스크에 쓰므로
         * 같은 스레드에 있으면 그 프레임의 값이 부풀고, 다른 스레드로 빼도 SoC 경쟁이 남는다.
         * 출처는 `docs/plans/20260806_detect_parity_dump_format.md` §6이다.
         */
        const val DETECT_PARITY_NOT_QUOTABLE =
            "🔴 **이 arm의 시간을 인용하지 말 것.** 이 arm은 샘플 K개의 원본 평면·입력 텐서·" +
                "출력 텐서를 파일로 덤프하는데(샘플당 ~7MB) 그 I/O가 추론과 **같은 스레드**에 " +
                "있어 그 프레임의 E·F·G가 부풀고, 다른 스레드로 빼도 SoC 자원 경쟁은 남는다. " +
                "덤프는 E·F·G를 재는 구간 **밖**(기록이 끝난 뒤)에서 하지만 그것으로 " +
                "**해소되지 않는다** — 같은 런의 다른 프레임과 표시 경로가 그 I/O와 경쟁한다. " +
                "그러므로 detect.csv가 나오더라도 그 값은 **버짓 칸으로 옮기지 않고** " +
                "승격본(docs/baselines/)으로도 올리지 않는다. " +
                "재는 자리는 이미 있다(detect_cpu / detect_nnapi / detect_xnnpack) — " +
                "**이 arm은 값을 대조하는 자리이지 재는 자리가 아니다.** " +
                "🔴 그리고 이 대조가 답하는 것은 '이식이 값을 바꾸지 않았는가'까지이며 " +
                "**'모델이 옳은가'가 아니다**(정답 라벨이 없다). " +
                "출처: docs/plans/20260806_detect_parity_dump_format.md §0·§6"

        /**
         * ③ arm의 프레임타임을 읽는 법. **arm마다 뜻이 다르다.**
         *
         * - [DETECT_BIND_ONLY]: 분석 use case 하나를 더 붙인 값. 추론은 없다.
         * - 나머지 여섯: **탐지를 idle-gated로 최대한 돌린 상태**의 값 → [DETECT_UPPER_BOUND].
         */
        const val DETECT_ROUND_SCOPE =
            "이 arm의 프레임타임을 읽는 법: **detect_bind_only는 분석 use case 하나를 더 붙인 " +
                "값**이고 추론이 없다(pipeline_stages에 detect 토큰이 없는 이유다). " +
                "detect_cpu / detect_nnapi / detect_xnnpack · _prof 셋 · detect_parity_* 셋은 " +
                "프레임 경로에서 **실제로 추론이 돈다** — " +
                "렌더 경로 자체는 blit_2pass와 글자 그대로 같으므로, 그 arm들의 프레임타임 " +
                "차이는 GL 변경이 아니라 **같은 SoC를 탐지 스레드와 나눠 쓴 결과**다. " +
                "🔴 E·F·G의 분포는 프레임타임이 아니라 **detect.csv**에 있다"

        /**
         * 🔴 **idle-gated는 상한이지 배포 구성이 아니다.**
         *
         * 탐지 주기 N이 `INTERFACES.md`에서 `☐`라 앱이 값을 지어내지 않고, 대신 "유휴면 즉시
         * 다음 프레임"으로 돈다. 그건 **탐지를 최대로 돌린 조건**이며 SoC 경쟁·발열이 최악이다.
         */
        const val DETECT_UPPER_BOUND =
            "🔴 **이 런은 탐지 주기의 상한 조건이지 배포 구성이 아니다.** 탐지 주기 N은 " +
                "INTERFACES.md에서 아직 ☐라 앱이 값을 지어내지 않고, 분석 프레임이 올 때 " +
                "**탐지가 유휴이면 즉시** 추론한다(idle-gated). 그래서 이 런의 SoC 경쟁과 " +
                "발열은 최악이고, 그 아래에서 관측된 표시 경로 프레임타임은 **'탐지를 최대로 " +
                "돌렸을 때의 하한'**이다. N을 정해 드물게 돌리면 프레임타임은 이보다 좋아진다. " +
                "실측 실행 주기는 하네스가 detect_cadence_ms 분포로 말한다 — " +
                "선언된 N이 아니라 관측값이다"
    }
}
