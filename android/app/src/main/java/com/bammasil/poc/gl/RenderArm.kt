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
    );

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
    }
}
