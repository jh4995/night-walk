package com.bammasil.poc.detect

import com.bammasil.poc.gl.RenderArm

/**
 * ③ 탐지 세션을 **한 번 준비한 결과**. `session.json`의 `detect` 블록이 전부 여기서 나온다.
 *
 * 🔴 **판정하지 않는다.** 필드는 전부 "앱이 관측한 것"이고, 관측하지 못한 것은 `null`이나
 * [DetectContract.EP_UNKNOWN]으로 남는다 — 요청값을 결과 칸에 베껴 넣는 자리가 없다.
 * 그래야 하네스가 `ep.requested != ep.resolved`로 조용한 폴백을 잡을 수 있다.
 */
class DetectReport(
    /** 이 보고를 만든 arm. 런 시작 시점의 arm과 다르면 그 보고는 이 런의 것이 아니다. */
    val arm: RenderArm,

    // ── 결과 ──────────────────────────────────────────────────────────────
    /** 세션을 열고 더미 추론까지 성공했는가. false면 **런을 시작하지 않는다.** */
    val ok: Boolean,
    /** 실패 사유(사람이 읽는 문장). 성공이면 null. */
    val failure: String?,
    /** 실패까지 가지는 않았지만 남겨야 하는 관측(예: verbose 로그 회수 실패). */
    val warnings: List<String>,

    // ── 모델 파일 ─────────────────────────────────────────────────────────
    val modelPath: String?,
    val modelBytes: Long,
    /** 🔴 **앱이 로드한 파일 바이트에서 직접 계산한 값.** metadata.json에서 베낀 값이 아니다. */
    val sha256Computed: String?,
    val sha256Declared: String,
    /** 선언값이 없으면(빌드 시점에 metadata.json을 못 읽음) null. **null은 "일치"가 아니다.** */
    val sha256MatchesDeclared: Boolean?,
    val sha256Ms: Long,

    // ── 그래프(앱이 TensorInfo에서 읽은 것) ───────────────────────────────
    val inputName: String?,
    val inputShape: List<Long>,
    val inputType: String?,
    val outputName: String?,
    val outputShape: List<Long>,
    val outputType: String?,
    /** 그래프 검증에서 어긋난 항목 전부. 비어 있지 않으면 [ok]가 false다. */
    val graphMismatches: List<String>,

    // ── 클래스 ────────────────────────────────────────────────────────────
    /** 모델 임베드 메타데이터 `names`에서 읽은 인덱스 순 이름. **1순위 출처다.** */
    val classNames: List<String>,
    /** `names`의 원문(파이썬 dict 문자열). 파싱 결과만 남기면 나중에 되물을 수 없다. */
    val classNamesRaw: String?,
    /** `INTERFACES.md` A-4와 어긋나면 그 사실. 어긋나지 않으면 null. */
    val contractConflict: String?,

    // ── EP ────────────────────────────────────────────────────────────────
    val epRequested: String,
    /** 🔴 **판별하지 못했으면 [DetectContract.EP_UNKNOWN]이다.** 요청값을 베끼지 않는다. */
    val epResolved: String,
    val resolutionMethod: String,
    /** 프로파일의 `*_kernel_time` 이벤트를 provider 원문 문자열별로 센 것. */
    val providerNodeCounts: Map<String, Int>,
    /** 위가 0일 때 쓴 보조 집계 — provider가 붙은 **모든** 프로파일 이벤트. */
    val providerEventCounts: Map<String, Int>,
    /**
     * 프로브 세션의 프로파일 JSON **파일 경로**(Chrome trace). `endProfiling()`이 돌려준
     * 확정 경로이며, 원문을 나중에 볼 수 있게 남긴다.
     */
    val probeProfilePath: String?,
    /**
     * 측정 세션 프로파일의 **경로 접두사**(파일 경로가 아니다 — ORT가 타임스탬프를 붙인다).
     * `_prof` arm이 아니면 null.
     *
     * ⚠ 이번 라운드는 측정 세션에서 `endProfiling()`을 부르지 않는다 — 프레임당 추론이 없어
     * 담길 것이 없기 때문이다. 세션을 닫을 때 확정 경로를 logcat에 남긴다.
     */
    val measuredProfilePrefix: String?,
    /** ⚠ **"빌드에 컴파일된 EP 목록"일 뿐 무엇이 쓰였는지는 말하지 않는다.** */
    val availableProviders: List<String>,
    /** ORT verbose 로그 원문(logcat에서 회수). **파싱해서 판정하지 않는다.** */
    val verboseLogLines: List<String>,
    val verboseLogNote: String,
    /** NNAPI 가드 결과(런타임 API 레벨). 요청이 cpu면 "해당 없음"이 들어온다. */
    val nnapiGuard: String,
    /**
     * EP에 실제로 넘긴 provider option. 🔴 **빈 맵은 "안 잰 칸"이 아니라 "ORT 기본값을 썼다"는
     * 사실**이며, 그 구분을 [threadOptionsNote]가 문장으로 말한다.
     */
    val epProviderOptions: Map<String, String>,
    /** 스레드 관련 옵션을 어떻게 두었는가(사람이 읽는 문장). 값을 지어내지 않은 이유가 여기 있다. */
    val threadOptionsNote: String,

    // ── 시간(스모크 확인용. 🔴 인용 금지 — 준비 1회이고 분포가 아니다) ────
    val envInitMs: Long,
    val probeCreateMs: Long,
    val probeInferMs: Long,
    val measuredCreateMs: Long,
    /**
     * **A8 warmup의 1회차**(= `first_inference_ms`). 🔴 **분포 밖에 따로 낸다** —
     * 그래프 초기화·lazy alloc이 여기 다 들어 있어 섞으면 p99가 초기화 비용에 오염된다.
     */
    val warmupInferMs: Long,
    /** warmup 회차별 ms 전부. "몇 회부터 평평해지는가"를 로그로 되물을 수 있게 남긴다. */
    val warmupInferMsAll: List<Long>,

    // ── 런타임 좌표 ───────────────────────────────────────────────────────
    val ortPackage: String,
    val ortVersion: String,
) {

    /** 화면·logcat 한 줄 요약. **인용 근거가 아니다**(인용은 session.json으로만). */
    fun oneLine(): String = if (!ok) {
        "③ 준비 실패: $failure"
    } else {
        "③ ${arm.id} | ep req=$epRequested → resolved=$epResolved | " +
            "노드 ${providerNodeCounts.entries.joinToString(",") { "${it.key}=${it.value}" }} | " +
            "sha256 일치=$sha256MatchesDeclared"
    }
}
