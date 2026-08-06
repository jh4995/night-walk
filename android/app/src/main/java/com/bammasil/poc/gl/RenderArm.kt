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
            usesChainedBilateral || usesFusedBilateral

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
     */
    val usesChainedComputeStage2: Boolean
        get() = this == DRAGO_CLAHE_CHAIN || this == DRAGO_CLAHE_CHAIN_1Q

    /**
     * ② 자리가 **융합**(통계 두 벌 + 적용 한 벌)인 arm인가.
     * **`PassthroughRenderer.drawFusedComputeStage2`(7패스) 경로 선택 전용**이다.
     */
    val usesFusedComputeStage2: Boolean
        get() = this == DRAGO_CLAHE_FUSED

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
        get() = this == DRAGO_CLAHE_FUSED_BF

    /**
     * ④ 오버레이 arm인가. **`PassthroughRenderer.drawHighlightOverlay`(4패스) 경로 선택
     * 전용**이다 — 개수 차이는 [highlightBoxCount]가 따로 말한다.
     */
    val usesHighlightOverlay: Boolean
        get() = this == HIGHLIGHT_BOXES || this == HIGHLIGHT_BOXES_STRESS

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
            this == DETECT_CPU_PROF || this == DETECT_NNAPI_PROF || this == DETECT_XNNPACK_PROF

    /**
     * 이 arm이 **요청하는** 실행 공급자. 어휘는 `lib/frame_log.py`의 `DETECT_EPS`와 같다.
     *
     * 🔴 **요청값이지 결과값이 아니다.** 실제로 무엇이 실행했는지는 앱이 따로 판별해
     * `detect.ep.resolved`로 낸다 — 한쪽만 적으면 조용한 폴백이 실패로 잡히지 않는다.
     */
    val detectEpRequested: String?
        get() = when (this) {
            DETECT_CPU, DETECT_CPU_PROF -> "cpu"
            DETECT_NNAPI, DETECT_NNAPI_PROF -> "nnapi"
            DETECT_XNNPACK, DETECT_XNNPACK_PROF -> "xnnpack"
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
        get() = usesChainedComputeStage2 || usesFusedComputeStage2 || usesBilateral

    /**
     * 이 arm이 그리는 ④ 박스 수. 오버레이 arm이 아니면 0이다.
     *
     * ⚠ **사양이 아니라 우리가 선언한 측정 조건**이다([HIGHLIGHT_BOX_PROVENANCE]).
     */
    val highlightBoxCount: Int
        get() = when (this) {
            HIGHLIGHT_BOXES -> HIGHLIGHT_BOX_COUNT
            HIGHLIGHT_BOXES_STRESS -> HIGHLIGHT_BOX_COUNT_STRESS
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
        const val HIGHLIGHT_DEVIATION =
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
                "${HIGHLIGHT_STROKE_PX_AT_720P / 2f}px이 대비색). 안쪽도 대칭으로 같은 폭이며, " +
                "**그보다 더 안쪽(박스 내부)은 한 픽셀도 건드리지 않는다**(overlay.fill). " +
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
                "px보다 훨씬 크다. 그러므로 **이 라운드의 stage_i_ms는 잘림의 영향을 받지 " +
                "않는다** — 잘림은 ③의 실제 탐지 박스를 그리기 시작하는 날의 문제다. " +
                "**(5) 비용에 대한 영향**: 드로우콜(프레임당 1회)과 정점 수(박스당 " +
                "${HighlightOverlay.VERTS_PER_BOX}개)는 이 선택과 무관하게 같다. 다만 **채우는 " +
                "프래그먼트 수는 완전히 같지 않다** — 두께 t 띠 하나의 면적이 가운데 맞춤이면 " +
                "2t(W+H)이고 안쪽 맞춤이면 2t(W+H) - 4t²이라 **띠마다 4t²만큼 더 채운다**" +
                "(720p에서 본선 t=$HIGHLIGHT_STROKE_PX_AT_720P → 64px, 밑선 t=" +
                "${HIGHLIGHT_STROKE_PX_AT_720P + 2f * HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P}" +
                " → 144px, 합 박스당 208px). 이 격자의 720p 박스가 121.6x136.8px이고 박스당 총 " +
                "5168px을 채우므로 **약 4%**다. 작지만 **0이 아니다** — '그린 픽셀 수가 안 " +
                "바뀐다'로 적지 말 것"

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
                "박스는 **정적 더미**이고 ③ 탐지 결과가 아니다(③ 미구현)"

        const val HIGHLIGHT_COLOR_STAIRS = "노랑 (1, 1, 0)"
        const val HIGHLIGHT_COLOR_PERSON = "시안 (0, 1, 1)"
        const val HIGHLIGHT_COLOR_UNDERLINE = "검정 (0, 0, 0)"

        const val HIGHLIGHT_NO_RED_REASON =
            "🔴 **빨강 금지.** 빨강은 휘도가 낮아 야간 배경에 묻히고 적록색약에서 무너진다 " +
                "(RESEARCH_20260803_UPSTREAM.md §5). 그래서 이 오버레이의 색은 노랑·시안·검정 " +
                "셋뿐이고 빨강 단색 스트로크는 코드에 존재하지 않는다"

        const val HIGHLIGHT_NO_BLINK_REASON =
            "🔴 **깜빡임 금지 — 안전 이슈다.** 대상 사용자가 광과민이므로 상류가 '항상 정적 " +
                "윤곽'으로 못 박았다(RESEARCH_20260803_UPSTREAM.md §5). 그래서 이 오버레이는 " +
                "난수를 쓰지 않고 박스가 프레임마다 **완전히 같다**(재현성도 그 덕에 유지된다)"

        /**
         * 🔴 이 문장이 빠지면 "깜빡임이 없었다"가 **성능 근거처럼** 읽힌다.
         * 실제 깜빡임 위험은 H칸이고 미구현이다.
         */
        const val HIGHLIGHT_BLINK_NOT_A_PERF_CLAIM =
            "🔴 **이 arm에 깜빡임이 없다는 사실을 성능·안전 근거로 쓰지 말 것.** 박스가 정적 " +
                "더미라 구조적으로 없을 뿐이고, 이 런은 깜빡임을 **시험하지 않았다.** 실제 " +
                "깜빡임 위험은 ③을 N프레임마다 돌릴 때 박스가 튀는 것이고(버짓 H칸: 좌표 IIR " +
                "평활·hold) **미구현**이다. 상류가 그것을 안전 이슈로 규정했다" +
                "(RESEARCH_20260803_UPSTREAM.md §5)"

        /**
         * 🔴 `INTERFACES.md` §A-4의 클래스 2번은 ☐(미정)다. 3번째 색을 지어내지 않는다.
         */
        const val HIGHLIGHT_CLASS_NOTE =
            "클래스는 `stairs`(index 0) · `person`(index 1) 두 종만 그린다. " +
                "🔴 INTERFACES.md §A-4의 **클래스 2번은 ☐(미정)**이라 3번째 클래스의 색을 " +
                "지어내지 않았다 — 클래스가 늘면 그 자체가 렌더 규약 변경이고(§A-4 불변 규칙) " +
                "이 오버레이도 함께 고쳐야 한다. 색은 픽셀 비용에 영향이 없다(어느 색이든 같은 " +
                "면적을 채운다)"

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
                "drago_clahe_chain_bf_1q↔drago_clahe_chain_bf. 다른 arm과 짝지으면 렌더가 " +
                "달라 그 차분은 아무 뜻이 없다. " +
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
         * ③ arm의 프레임타임을 읽는 법. **arm마다 뜻이 다르다.**
         *
         * - [DETECT_BIND_ONLY]: 분석 use case 하나를 더 붙인 값. 추론은 없다.
         * - 나머지 여섯: **탐지를 idle-gated로 최대한 돌린 상태**의 값 → [DETECT_UPPER_BOUND].
         */
        const val DETECT_ROUND_SCOPE =
            "이 arm의 프레임타임을 읽는 법: **detect_bind_only는 분석 use case 하나를 더 붙인 " +
                "값**이고 추론이 없다(pipeline_stages에 detect 토큰이 없는 이유다). " +
                "detect_cpu / detect_nnapi / detect_xnnpack / _prof 셋은 프레임 경로에서 " +
                "**실제로 추론이 돈다** — " +
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
