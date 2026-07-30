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
enum class RenderArm(val id: String, val pipelineStages: List<String>) {

    /** 기존 경로 그대로. OES → 화면 1패스. 처리 0. */
    PASSTHROUGH("passthrough", emptyList()),

    /** 3패스 골격을 다 돌되 ② 자리는 단순 복사. 골격 자체의 비용을 본다. */
    BLIT_2PASS("blit_2pass", listOf("blit_2pass")),

    /** ② 자리에 감마만. **② 비용의 하한**이며 알고리즘이 아니다. */
    GAMMA_ONLY("gamma_only", listOf("blit_2pass", "stage2_gamma"));

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
    }
}
