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
     * `stage2_clahe` 토큰은 **앱이 먼저 낸다** — 하네스 어휘(`lib/frame_log.py`의
     * `PIPELINE_STAGES`)에 아직 없어 "어휘 밖" 경고가 뜨는 것이 **정상이고 의도된 순서**다
     * (생산자가 앱이다. `stage2_drago`도 같은 순서로 등록됐다).
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
     * 중간 materialize를 없애는 **융합**은 알고리즘 변경이라 이번 범위가 아니다.
     *
     * 왜 이 arm을 재는가: 상류는 CPU 720p 101.2ms를 근거로 내부 처리 해상도를 640×360으로
     * 깎는 것을 검토 중인데, 상류 실측상 **글레어 지표가 해상도에 크게 의존한다**
     * (`docs/research/RESEARCH_20260803_UPSTREAM.md` §6). GPU에서 720p 조합이 게이트 안이면
     * 그 레버를 당길 이유가 사라진다.
     *
     * ⚠ **패스 8개 = [GpuTimerRing.MAX_PASS_COUNT] 정확히다. 여유가 0이다.** 여기에 패스를
     * 하나라도 더하면 `setPassCount`가 거부하고 이 arm의 GPU 계측이 통째로 꺼진다.
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
        get() = usesSingleComputeStage2 || usesChainedComputeStage2 || usesFusedComputeStage2

    /**
     * ② 자리가 **컴퓨트 3단 한 벌**(analyze → build → apply)인 arm인가.
     * **`PassthroughRenderer.drawComputeStage2`(5패스) 경로 선택 전용**이다.
     */
    val usesSingleComputeStage2: Boolean
        get() = this == DRAGO || this == CLAHE_GAMMA || this == AGCWD

    /**
     * ② 자리가 **컴퓨트 3단 두 벌**(조합)인 arm인가.
     * **`PassthroughRenderer.drawChainedComputeStage2`(8패스) 경로 선택 전용**이다.
     */
    val usesChainedComputeStage2: Boolean
        get() = this == DRAGO_CLAHE_CHAIN

    /**
     * ② 자리가 **융합**(통계 두 벌 + 적용 한 벌)인 arm인가.
     * **`PassthroughRenderer.drawFusedComputeStage2`(7패스) 경로 선택 전용**이다.
     */
    val usesFusedComputeStage2: Boolean
        get() = this == DRAGO_CLAHE_FUSED

    /** 조합 arm인가(체인이든 융합이든). 둘의 계수를 나란히 낼 때 쓴다. */
    val isCompositionArm: Boolean
        get() = usesChainedComputeStage2 || usesFusedComputeStage2

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
                "왜 지금 재현하지 않는가: 앞뒤 정규화는 전체 화면 리덕션이 **두 번 더** " +
                "필요해 GPU 타이머 슬롯을 초과하고, 뒤 정규화는 매 프레임 전역 오토레벨이라 " +
                "§B-4가 안전 문제로 규정한 프레임 간 출렁임을 하나 더 얹는다. " +
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
                "`D1A1`까지다. 범위 밖이며, **슬롯도 넘는다**(8패스 = " +
                "GpuTimerRing.MAX_PASS_COUNT 정확히라 여유가 0이다)"

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
    }
}
