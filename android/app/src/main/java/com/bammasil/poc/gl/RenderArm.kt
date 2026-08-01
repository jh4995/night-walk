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
    );

    /**
     * ② 자리가 **컴퓨트 3단**(analyze → build → apply)인 arm인가. 5패스 드로우 경로와
     * `session.json`의 서술 분기가 이 하나로 갈린다 — arm을 나열하는 곳이 늘면 새 arm을
     * 추가할 때 한 군데를 빠뜨린다.
     */
    val usesComputeStage2: Boolean
        get() = this == DRAGO || this == CLAHE_GAMMA || this == AGCWD

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
            "🔴 **열을 명시하지 않은 인용은 정반대 결론을 만든다.** 같은 런에서 순위가 " +
                "실제로 뒤집혔다: stage_d_total_ms로 보면 clahe_gamma 5.398 < drago 5.760이라 " +
                "**clahe가 싸고**, gpu_sum 차분(arm − blit_2pass)으로 보면 clahe +7.22 > " +
                "drago +6.37이라 **clahe가 비싸다.** 둘 다 맞는 숫자이고 재는 대상이 다를 " +
                "뿐이다(전자는 D 열 3개의 합, 후자는 present 번짐까지 포함한 프레임 전체 " +
                "증분). 표·보고서로 옮길 때 **열 이름을 반드시 함께 옮길 것** — 숫자만 옮기면 " +
                "읽는 사람이 반대 결론을 낸다"

        /**
         * 🔴 **인용 함정 2 — `gpu_sum` 차분을 '알고리즘의 비용'으로 읽으면 틀린다.**
         * 독립 검증이 낸 분해다. 이 문장이 빠지면 팀에 "A1이 알고리즘 성질상 가장 비싸다"는
         * **틀린 판정**이 전달된다 — 실제로는 A1의 통계 부분이 셋 중 가장 싸다.
         */
        const val COST_SPLIT_NOTE =
            "🔴 **gpu_sum 차분을 통째로 '그 알고리즘의 비용'으로 읽지 말 것.** 독립 검증이 " +
                "낸 분해(blit_2pass 대비 증분, ms): " +
                "drago = 알고리즘 고유(analyze+build) 3.482(55%) + 적용·present 증분 2.860 ; " +
                "agcwd = 2.797(46%) + 3.284 ; " +
                "clahe_gamma = **1.877(26%)** + **5.315(74%)**. " +
                "즉 **알고리즘 고유 통계 부분은 세 arm 중 clahe가 가장 싸다.** clahe 초과분의 " +
                "74%는 적용 패스이고 그 지배 항은 **A1·A2가 공유하는 LAB 변환(pow 9회)**이다 " +
                "— 눈금으로 gamma_only의 적용 패스(pow 3회)가 +0.239ms다. " +
                "그러므로 'A1이 알고리즘 성질상 가장 비싸다'는 결론은 이 데이터에서 나오지 " +
                "않으며, 경량화 레버도 통계 쪽이 아니라 **LAB 변환 쪽**을 봐야 한다"

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
    }
}
