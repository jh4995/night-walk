package com.bammasil.poc.log

import com.bammasil.poc.gl.ColorTransformCensus
import com.bammasil.poc.gl.DragoClaheChainStage
import com.bammasil.poc.gl.DragoClaheFusedStage
import com.bammasil.poc.gl.GlCapabilities
import com.bammasil.poc.gl.GlCapabilitiesProbe
import com.bammasil.poc.gl.GpuTimerReport
import com.bammasil.poc.gl.GpuTimerRing
import com.bammasil.poc.detect.DetectContract
import com.bammasil.poc.detect.DetectReport
import com.bammasil.poc.gl.HighlightOverlay
import com.bammasil.poc.gl.LabGlsl
import com.bammasil.poc.gl.RenderArm
import com.bammasil.poc.source.AnalysisConfig
import com.bammasil.poc.source.FrameRequest
import com.bammasil.poc.source.NegotiatedConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 측정 1회의 조건. 키 이름은 `scripts/gen_synthetic_frames.py`(참조 구현)를 그대로 따른다.
 * 하네스는 모르는 키를 버리지 않고 `summary.json`의 `session` 블록에 통째로 보존한다.
 */
class SessionFacts(
    /** **`BuildConfig.BUILD_TYPE`에서 온 값이어야 한다.** 문자열 리터럴을 넣지 않는다. */
    val buildType: String,
    val versionName: String,
    /** `BuildConfig.GIT_COMMIT` — 빌드 시점의 short SHA. 못 읽었으면 `"unknown"`. */
    val gitCommit: String,
    /** `BuildConfig.GIT_DIRTY` — `"true"` / `"false"` / `"unknown"`의 3-상태 문자열. */
    val gitDirty: String,
    val lightingCondition: String,
    /** **측정 시작 시점에 잠근 arm.** 스피너의 현재 값이 아니다. */
    val arm: RenderArm,
    val request: FrameRequest,
    val negotiated: NegotiatedConfig?,
    /**
     * ③ 분석 use case가 **실제로** 물어온 조건. 바인딩하지 않았거나 프레임이 하나도 안
     * 왔으면 null. 🔴 [negotiated]와 **섞지 않는다** — 다른 use case의 값이라 한 칸에 담으면
     * 그 런의 조건이 거짓말을 한다.
     */
    val analysis: AnalysisConfig?,
    val sourceKind: String,
    val framesEmitted: Int,
    val surfaceFramesAvailable: Long,
    val drawsWithoutNewFrame: Int,
    val startedElapsedNs: Long,
    val stoppedElapsedNs: Long,
    val clock: CaptureClockVerdict,
    val glSurfaceWidth: Int,
    val glSurfaceHeight: Int,
    val eglContextClientVersion: Int,
    /** `onSurfaceCreated`에서 실측한 GL 능력. 수집 실패 시 null. */
    val gl: GlCapabilities?,
    /** 3패스가 실제로 쓴 처리 해상도. 협상 전이면 0. */
    val processWidth: Int,
    val processHeight: Int,
    val offscreenStatus: String,
    val offscreenFallbackDraws: Int,
    /** ② 자리 자원의 상태(사람이 읽는 문장). arm에 따라 뜻이 다르다. */
    val stage2Status: String,
    /**
     * ④ 오버레이 자원의 상태(사람이 읽는 문장). 오버레이 arm이 아니면 그 사실이 적혀 있다.
     *
     * 🔴 [stage2Status]와 **따로 낸다.** 한 문장에 섞으면 오버레이 컴파일 실패가 ② 성공 서술
     * 뒤에 묻히고, 그 실패 모드가 실제로 11분 런을 무효로 만든 적이 있다.
     */
    val overlayStatus: String,
    /**
     * 조합 arm의 셰이더에서 **기계가 센** 색공간 변환 호출 지점.
     * `arm id → (패스 이름 → 토큰 → 개수)`이며 체인·융합 둘을 담는다.
     *
     * `session.json`에는 **이 런의 arm 것이 `color_transform_sites`로** 나가고, 나머지
     * 조합 arm 것은 `color_transform_sites_peer`로 나간다 — 한 파일에서 두 arm의 차이를
     * 계산할 수 있어야 `gpu_sum` 차분을 "변환 몇 회를 줄였는가"에 귀속시킬 수 있다.
     */
    val colorTransformSites: Map<String, List<Pair<String, Map<String, Int>>>>,
    /** 이 런의 GPU timer 실적. 계측 arm이 아니면 전부 0이고 `instrumented=false`다. */
    val gpuTimer: GpuTimerReport,
    /**
     * ③ 탐지 세션 준비 결과. **③ arm이 아니면 null**이고 그때는 `detect` 블록을 내지 않는다
     * (`overlay` 블록과 같은 규약 — 다른 arm에 빈 블록을 내면 "잰 적 없는 칸"이 생긴다).
     */
    val detect: DetectReport?,
    /**
     * 프레임 경로에서 ③가 실제로 한 일. **③ arm이 아니면 null**이다.
     * [detect]가 "세션을 어떻게 준비했나"라면 이쪽은 "이 런에서 몇 번 돌았나"다.
     */
    val detectRun: DetectRunFacts?,
)

/**
 * ③ 탐지의 **런 회계**. 🔴 불변식은 하나다:
 *
 * ```
 * analysisFramesReceived == inferencesRun + skippedWhileBusy + errors
 * ```
 *
 * 앱은 이 식을 **맞춰 주지 않는다.** 어긋나면 어긋난 채로 싣고 그 사실을 함께 낸다 —
 * 맞춰 버리면 프레임이 조용히 사라지는 경로를 영영 못 찾는다.
 */
class DetectRunFacts(
    /** 실제로 `detect.csv`에 쓴 행 수. 0이면 파일 자체를 만들지 않았다. */
    val csvRows: Int,
    val analysisFramesReceived: Long,
    val inferencesRun: Long,
    /** 탐지가 바빠 건너뛴 프레임의 **누적**. */
    val skippedWhileBusy: Long,
    val errors: Long,
    val lastError: String?,
    /** A12 (2)의 quiesce가 시간 안에 끝났는가. false면 마지막 행이 찢겼을 수 있다. */
    /**
     * 이 런이 **추론을 돌렸는가.** 분모 arm(`detect_bind_only`)에서는 false이고, 그때는
     * 회계 불변식을 적용하지 않는다(추론 경로 자체가 없으므로 받은 프레임이 어느 칸에도
     * 안 들어가는 것이 정상이다).
     */
    val inferenceEnabled: Boolean,
    val quiesced: Boolean,
    val quiesceTimeoutMs: Long,
    /** 이 런에서 실제로 쓴 letterbox 기하(마지막 프레임). 추론이 없었으면 null. */
    val letterbox: DetectContract.Letterbox?,
    /** 런 디렉토리로 옮긴 ORT 프로파일 JSON 파일 이름들. */
    val profileFiles: List<String>,
)

object SessionWriter {

    /**
     * `drago`의 ② 3단 패스 서술. **조합 arm(`drago_clahe_chain`)의 앞 3패스가 글자 그대로
     * 같은 것**이라 상수로 뽑았다 — 복붙하면 한쪽만 고쳐진다.
     */
    private val DRAGO_STATS_PASSES: List<Triple<String, String, String>> = listOf(
        Triple(
            "stage2_drago_analyze",
            "통계 SSBO (glDispatchCompute)",
            "전역 통계 리덕션 — 로그평균 휘도(Σlog)와 최대 휘도. " +
                "워크그룹 공유메모리 리덕션 + SSBO atomic으로 dispatch 1회",
        ),
        Triple(
            "stage2_drago_build",
            "통계 SSBO (glDispatchCompute, 스레드 1개)",
            "통계 → 톤커브 계수(logAvg / Lmax / biasPow). " +
                "CPU로 읽어오면 GPU 동기화가 걸리므로 GPU에서 계산한다",
        ),
    )

    /**
     * `drago` 3단 전체. **융합 arm은 세 번째(적용)가 없어** [DRAGO_STATS_PASSES]만 쓴다 —
     * 그래서 둘로 쪼개 두었다.
     */
    private val DRAGO_PASSES: List<Triple<String, String, String>> = DRAGO_STATS_PASSES + listOf(
        Triple(
            "stage2_drago_apply",
            "FBO_B (처리 해상도)",
            "Drago 톤맵 적용 + 감마 (uSrcGamma / uOutGamma / uSaturation)",
        ),
    )

    /**
     * 체인 arm의 ② 자리 6패스. **`drago_clahe_chain_bf`가 이 목록에 bf 한 패스를 더해 쓴다** —
     * 복붙하면 한쪽만 고쳐진다(같은 이유로 [DRAGO_STATS_PASSES]를 쪼개 두었다).
     */
    private val CHAIN_STAGE2_PASSES: List<Triple<String, String, String>> =
        DRAGO_PASSES + listOf(
            Triple(
                "stage2_clahe_analyze",
                "히스토그램 SSBO (glDispatchCompute)",
                "타일별 ${LabGlsl.BIN_COUNT}빈 히스토그램(LAB L). " +
                    "입력은 **drago가 적용된 FBO_B**이지 카메라 원본이 아니다. " +
                    "binding=${DragoClaheChainStage.CLAHE_HIST_BINDING} " +
                    "(drago 통계와 겹치지 않게 민 값)",
            ),
            Triple(
                "stage2_clahe_build",
                "LUT SSBO (glDispatchCompute, 타일당 워크그룹 1개)",
                "클립 + 초과분 재분배 + CDF → 타일별 " +
                    "${LabGlsl.BIN_COUNT}엔트리 LUT (uClipLimit). " +
                    "binding=${DragoClaheChainStage.CLAHE_LUT_BINDING}",
            ),
            Triple(
                "stage2_clahe_apply",
                "FBO_A (처리 해상도. 핑퐁으로 되돌아온다)",
                "타일 간 이중선형 보간 + 감마 (uTiles / uClaheGamma). " +
                    "LAB의 L만 바꾸고 a,b는 그대로 둔다",
            ),
        )

    /** 융합 arm의 ② 자리 5패스. `drago_clahe_fused_bf`가 여기에 bf 한 패스를 더해 쓴다. */
    private val FUSED_STAGE2_PASSES: List<Triple<String, String, String>> =
        DRAGO_STATS_PASSES + listOf(
            Triple(
                "stage2_fused_analyze",
                "히스토그램 SSBO (glDispatchCompute)",
                "타일별 ${LabGlsl.BIN_COUNT}빈 히스토그램(LAB L). " +
                    "🔴 **Drago 톤맵을 인라인**해 선형 상태로 L*을 낸다 — " +
                    "입력이 **FBO_A(원본)**이지 톤맵된 중간 이미지가 아니다" +
                    "(중간을 만들지 않으므로 여기서 다시 계산한다). " +
                    "통계 SSBO를 읽으면서 히스토그램을 쓴다. " +
                    "hist binding=${DragoClaheFusedStage.CLAHE_HIST_BINDING}",
            ),
            Triple(
                "stage2_clahe_build",
                "LUT SSBO (glDispatchCompute, 타일당 워크그룹 1개)",
                "클립 + 초과분 재분배 + CDF → 타일별 " +
                    "${LabGlsl.BIN_COUNT}엔트리 LUT (uClipLimit). " +
                    "binding=${DragoClaheFusedStage.CLAHE_LUT_BINDING}",
            ),
            Triple(
                "stage2_fused_apply",
                "FBO_B (처리 해상도)",
                "🔴 **톤맵 + 타일 LUT 이중선형 보간 + 감마를 한 패스에서** " +
                    "한다(uSrcGamma / uSaturation / uTiles / uClaheGamma). " +
                    "프래그먼트 **하나가 SSBO 블록 둘**(DragoStats + " +
                    "ClaheLut)을 읽는다 — 이 arm만 그렇다. " +
                    "uOutGamma는 쓰지 않는다(중간 인코딩이 없다)",
            ),
        )

    /**
     * bf 패스 서술. 타깃이 arm마다 다르므로(체인+bf는 FBO_B, 융합+bf는 FBO_A) 인자로 받는다 —
     * 앞 arm의 마지막 처리 패스가 어느 FBO에 썼는지에 따라 핑퐁 방향이 정해진다.
     */
    private fun bilateralPass(target: String): Triple<String, String, String> = Triple(
        "stage2_bilateral",
        target,
        "bilateral 1패스(프래그먼트 gather). d=${RenderArm.BF_D} → " +
            "radius=${RenderArm.BF_RADIUS}이고 **원형 이웃 ${RenderArm.BF_TAP_COUNT}탭**이다" +
            "(7x7 사각형 49탭이 아니다). 색 거리는 **3채널 L1 합을 제곱**해 " +
            "σc=${RenderArm.BF_SIGMA_COLOR}(0..255 단위)와 비교하고, 공간 가중은 " +
            "σs=${RenderArm.BF_SIGMA_SPACE}(픽셀)이다. " +
            "uniform: ${RenderArm.BF_RADIUS_UNIFORM} / ${RenderArm.BF_SIGMA_COLOR_UNIFORM} / " +
            "${RenderArm.BF_SIGMA_SPACE_UNIFORM} / ${RenderArm.BF_TEXEL_UNIFORM}. " +
            "**sRGB 그대로 필터하고 LabGlsl을 부르지 않는다**(알파는 통과)",
    )

    /**
     * `lib/frame_log.py`의 `SCHEMA_VERSION`. 열이 늘면 양쪽을 함께 올린다.
     *
     * v3에서 D 계열 하위 열(`stage_d_analyze_ms` / `stage_d_build_ms` / `stage_d_apply_ms` /
     * `stage_d_denoise_ms`)이 들어왔고, 이 앱은 그중 앞의 셋을 ② 컴퓨트 arm 세 개
     * (`drago` · `clahe_gamma` · `agcwd`)에서 낸다. `stage_d_denoise_ms`는 **bf arm
     * (`drago_clahe_chain_bf` · `drago_clahe_fused_bf`)이 낸다** — v3에서 자리만 잡아 두었던
     * 열을 그 arm들이 처음 채웠다. `stage_i_ms`는 ④ 오버레이 arm
     * (`highlight_boxes` · `highlight_boxes_stress`)이 낸다.
     *
     * ⚠ **새 arm 4개가 들어와도 열은 늘지 않았으므로 버전은 4 그대로다.** 전부 v3·v4에서 이미
     * 정의된 열이다.
     *
     * v4에서 **서수 2** 열(`stage_d_analyze2_ms` / `stage_d_build2_ms` / `stage_d_apply2_ms`)이
     * 들어왔다. 뜻은 "**그 arm의 두 번째 톤커브 스테이지**의 같은 역할 슬롯"이며 알고리즘
     * 이름이 아니다. 이 앱에서는 조합 arm `drago_clahe_chain`이 낸다 — ② 자리에서 3단을
     * 두 벌 돌기 때문이다. **두 스테이지를 합쳐 한 슬롯에 넣지 않는다**(합치면 유도값이고
     * 어느 스테이지가 비싼지가 사라진다 — `docs/FRAME_LOG_SCHEMA.md` §2).
     *
     * v5에서 **프레임 단일 query 열**(`gpu_frame_ms`) 하나가 들어왔고, 이 앱은
     * `_1q` arm 3개(`blit_2pass_1q` · `drago_clahe_chain_1q` · `drago_clahe_chain_bf_1q`)에서
     * 낸다. 🔴 **이 열은 다른 GPU 열과 물리량이 다르다** — 패스가 아니라 프레임을 재므로
     * `gpu_sum_ms`에도 D 계열에도 들어가지 않고 버짓 칸도 없다
     * ([RenderArm.SINGLE_QUERY_NOT_A_SUM]).
     *
     * ⚠ **이 버전을 4에서 5로 올린 것은 이 앱이 그 열을 실제로 내기 시작했기 때문이다.**
     * 하네스는 이미 v5였고(`docs/FRAME_LOG_SCHEMA.md` §6대로 하네스가 먼저 들어간다),
     * 앱이 4를 선언한 채 v5 열을 실으면 "앱이 하네스보다 뒤처졌다 — `gpu_frame_ms`가 없을 수
     * 있다"는 **거짓 경고**가 그 열이 실제로 있는 로그에 붙는다.
     *
     * v6에서 ③ 탐지가 들어왔다: 별 파일 `detect.csv`(추론 1회당 1행)와 `session.json`의
     * **`detect` 블록**, ③ arm 어휘 5종. 🔴 **`detect` 블록은 하네스가 값을 읽고 판정에
     * 쓰는 유일한 세션 블록이다** — `enabled`가 회수 실패(exit 4)를, `ep.requested` ≠
     * `ep.resolved`가 계획 어긋남을 만든다(`docs/FRAME_LOG_SCHEMA.md` §5).
     *
     * ⚠ **버전은 6 그대로다.** 이 라운드에서 `ImageAnalysis`·전처리·추론·후처리가 붙어
     * `detect.csv`를 **실제로 내기 시작했지만**, 그 열들은 전부 v6에서 이미 정의된 것이고
     * 새 열이 하나도 늘지 않았다(스키마 v6 = "`detect.csv`라는 파일과 그 열들"이다).
     * 앞선 라운드가 v6를 선언한 채 `detect` **블록만** 낸 것도 같은 이유였다 — 그때는
     * `detect.enabled`가 false였고 지금은 추론이 도는 arm에서 true다.
     */
    const val SCHEMA_VERSION = 6

    fun write(file: File, facts: SessionFacts) {
        file.writeText(build(facts).toString(2) + "\n")
    }

    fun build(facts: SessionFacts): JSONObject {
        val root = JSONObject()
        root.put("schema_version", SCHEMA_VERSION)
        // release가 아니면 그 숫자는 근거로 못 쓴다(baseline_diff가 비교 조건으로 본다).
        // 그 판단을 사람이 하도록, 앱은 실제 빌드 타입을 그대로 싣는다.
        root.put("build_type", facts.buildType)
        root.put("app_version_name", facts.versionName)
        root.put("generated_by", "android/app (com.bammasil.poc)")
        // ⚠ app_version_name은 build.gradle 상수라 커밋이 바뀌어도 안 변한다 → 로그와 APK를
        //   잇는 고리는 아래 build 블록이다. 실제로 문제가 됐다: 승격 베이스라인 2건이
        //   android 코드 커밋보다 앞선 바이너리에서 나왔고 그게 git_dirty의 실체였다.
        root.put(
            "build",
            JSONObject()
                .put("git_commit", facts.gitCommit)
                // 3-상태 **문자열**이다("true"/"false"/"unknown"). git이 없거나 실패하면
                // 빌드를 죽이지 않고 unknown으로 남기므로, boolean으로 만들면 모름을
                // false(=깨끗함)로 거짓 주장하게 된다.
                .put("git_dirty", facts.gitDirty)
                .put(
                    "note",
                    "gradle 구성 시점에 git rev-parse --short HEAD / git status --porcelain을 " +
                        "읽어 BuildConfig에 박은 값이다. 측정 시각이 아니라 **빌드 시각**의 " +
                        "저장소 상태이며, git_dirty=true면 이 APK의 소스는 어느 커밋에도 없다"
                )
        )
        // 어느 arm으로 잰 것인가. 이 키는 baseline_diff의 **비교 조건**이라 arm이 다르면
        // 자동으로 "조건 다름"이 뜬다(그게 원하는 동작이다).
        val stages = JSONArray()
        for (stage in facts.arm.pipelineStages) {
            stages.put(stage)
        }
        root.put("pipeline_stages", stages)
        root.put("render_arm", facts.arm.id)
        root.put("lighting_condition", facts.lightingCondition)
        root.put("capture_clock_base", facts.clock.base)
        root.put("source_kind", facts.sourceKind)

        root.put(
            "camera",
            JSONObject()
                .put("requested_fps", facts.request.fps)
                .put("resolution", "${facts.request.width}x${facts.request.height}")
        )
        // 요청값만 적으면 조건이 거짓말을 한다 → 실제로 받은 값을 따로 싣는다.
        val actual = JSONObject()
        val negotiated = facts.negotiated
        if (negotiated == null) {
            actual.put("resolution", JSONObject.NULL)
            actual.put("frame_rate_range", JSONObject.NULL)
            actual.put(
                "note",
                "카메라가 Surface를 요청하지 않았다 — 실제 조건을 알 수 없다"
            )
        } else {
            actual.put("resolution", "${negotiated.width}x${negotiated.height}")
            actual.put(
                "frame_rate_range",
                negotiated.frameRateRange ?: JSONObject.NULL
            )
            if (negotiated.frameRateRange == null) {
                actual.put(
                    "note",
                    "CameraX가 프레임레이트를 확정해 주지 않았다(FRAME_RATE_RANGE_UNSPECIFIED). " +
                        "요청값을 베껴 넣지 않는다 — 실제 공급 속도는 하네스가 " +
                        "recv_interval_ms에서 낸다"
                )
            }
        }
        root.put("camera_actual", actual)
        // ③ 분석 use case의 조건. 🔴 위 camera_actual과 **따로** 낸다 — 다른 use case라
        //   CameraX가 서로 다른 해상도를 줄 수 있고, 섞으면 E의 전제가 거짓말이 된다.
        //   ③ arm이 아니면 키 자체를 내지 않는다(빈 블록은 "잰 적 없는 칸"처럼 보인다).
        if (facts.arm.isDetectArm) {
            root.put("camera_analysis_actual", buildAnalysisInput(facts))
        }

        root.put("frames_emitted", facts.framesEmitted)
        // ⚠ 0으로 채우면 "드롭 없음"이라는 거짓 주장이 된다. 모르므로 null이다.
        root.put("camera_frames_offered", JSONObject.NULL)
        root.put("frames_dropped", JSONObject.NULL)
        // 🔴 **경로가 둘이므로 문장도 둘이다.** 아래 문장은 **표시 경로에 대해서만** 참이고,
        //   ③ arm에서 함께 도는 탐지 경로에는 ImageProxy가 있어 드롭을 셀 수 있다 —
        //   그 계수는 `detect.run` 블록에 따로 있다(여기 섞으면 어느 경로의 수인지 사라진다).
        root.put(
            "drop_accounting_note",
            "**표시 경로 2-C**에는 ImageProxy가 없어 버려진 프레임 수를 셀 수 없다. " +
                "frames.csv의 dropped_since_last는 전부 -1이며, camera_frames_offered/" +
                "frames_dropped는 0이 아니라 null이다(0은 '드롭 없음'이라는 적극적 주장이다). " +
                "아래 surface_frames_available은 카메라 출력 수가 아니라 우리 SurfaceTexture " +
                "큐에 도착한 수이므로 드롭의 하한 단서일 뿐이다. " +
                "⚠ **③ 탐지 경로는 다르다** — 그쪽은 ImageAnalysis use case라 ImageProxy가 " +
                "있고 건너뛴 프레임을 실제로 센다. 그 계수는 이 키가 아니라 " +
                "**detect.run**(analysis_frames_received / inferences_run / " +
                "skipped_while_busy / errors)에 있으며, 두 경로는 **다른 use case라 프레임 " +
                "수가 서로 같지 않다.** 한쪽 수를 다른 쪽에 옮겨 적지 말 것"
        )
        // 우리가 실제로 관측한 것만 싣는다.
        root.put("surface_frames_available", facts.surfaceFramesAvailable)
        root.put(
            "frames_coalesced_lower_bound",
            maxOf(0L, facts.surfaceFramesAvailable - facts.framesEmitted)
        )
        root.put("draws_without_new_frame", facts.drawsWithoutNewFrame)

        root.put(
            "measurement",
            JSONObject()
                .put("started_elapsed_realtime_ns", facts.startedElapsedNs)
                .put("stopped_elapsed_realtime_ns", facts.stoppedElapsedNs)
                .put("duration_ns", facts.stoppedElapsedNs - facts.startedElapsedNs)
        )

        // 시계 규약. 하네스가 t_capture_ns를 나중에 정규화할 수 있게 오프셋을 남긴다.
        root.put(
            "clock_offset_boottime_minus_monotonic_ns",
            facts.clock.offsetBoottimeMinusMonotonicNs
        )
        root.put("capture_clock_base_reason", facts.clock.reason)
        root.put("log_clock", "SystemClock.elapsedRealtimeNanos() (CLOCK_BOOTTIME)")
        val probes = JSONArray()
        for (sample in facts.clock.samples) {
            probes.put(
                JSONObject()
                    .put("t_capture_ns", sample.tCaptureNs)
                    .put("monotonic_ns", sample.monotonicNs)
                    .put("boottime_ns", sample.boottimeNs)
            )
        }
        root.put("capture_clock_probe", probes)

        // 타임스탬프를 정확히 어디서 찍었는지. 이게 없으면 값의 의미를 나중에 되물을 수 없다.
        root.put(
            "timestamp_sites",
            JSONObject()
                .put(
                    "t_recv_ns",
                    "SurfaceTexture.OnFrameAvailableListener.onFrameAvailable 진입 직후 " +
                        "(전용 신호 스레드). 2-C에는 분석 콜백이 없어 이것이 프레임 도착 신호다"
                )
                .put(
                    "t_capture_ns",
                    "SurfaceTexture.getTimestamp() 원본. 앱이 보정하지 않는다"
                )
                .put(
                    "t_render_start_ns",
                    "GLSurfaceView.Renderer.onDrawFrame 진입 직후, updateTexImage 전 " +
                        "(도착 시각 AtomicLong을 먼저 소비한 뒤 찍는다 — 순서를 뒤집으면 " +
                        "t_recv > t_render_start 가 되어 하네스 교차검사 A가 거짓 위반을 낸다)"
                )
                .put(
                    "t_render_end_ns",
                    "onDrawFrame 반환 직전(드로우콜 제출 완료). swapBuffers는 프레임워크가 " +
                        "onDrawFrame 반환 후에 하므로 우리가 잴 수 없다"
                )
        )
        // ③ 탐지 계측의 타임스탬프 자리. 🔴 **E는 t를 찍는 위치가 정의다** — 어디서
        // 어디까지인지 여기 없으면 그 숫자의 뜻을 나중에 되물을 수 없다.
        if (facts.arm.usesDetectSession) {
            root.put("detect_timestamp_sites", buildDetectTimestampSites())
        }
        root.put(
            "render_latency_meaning",
            "render_latency_ms(t_render_end - t_render_start)는 glDrawArrays가 즉시 반환하므로 " +
                "**CPU 제출 비용이고 GPU 실행 시간이 아니다.** '렌더가 사실상 무료'로 읽으면 " +
                "틀린다 — 실제 GPU 비용은 stage_*_ms / gpu_present_ms 열에 따로 있고 그건 " +
                "**다른 시계(GPU)**다. 반면 output_interval_ms는 swapBuffers가 다음 사이클 " +
                "앞에서 블록하므로 실제 표시 주기(=프레임타임)가 된다"
        )
        root.put("gl", buildGl(facts))
        root.put("gpu_timer", buildGpuTimer(facts))
        root.put("stage2_params", buildStage2Params(facts))
        // ④ 오버레이 arm만 낸다. 다른 arm에 빈 블록을 내면 "잰 적 없는 칸"이 있는 것처럼 보인다.
        if (facts.arm.usesHighlightOverlay) {
            root.put("overlay", buildOverlay(facts))
        }
        // ③ 탐지 arm만 낸다(위와 같은 규약). 🔴 **하네스가 값을 읽고 판정에 쓰는 블록이다.**
        if (facts.arm.isDetectArm) {
            root.put("detect", buildDetect(facts))
        }
        root.put("render", buildRender(facts))
        return root
    }

    /**
     * **앱 컨텍스트에서 실측한** GL 정보. 다른 프로세스의 드라이버 문자열은 근거가 아니다
     * (`GlCapabilitiesProbe` 참고). 판별 불가는 `"unknown"`으로 남는다.
     */
    private fun buildGl(facts: SessionFacts): JSONObject {
        val gl = facts.gl
        val json = JSONObject()
        json.put("egl_context_client_version_requested", facts.eglContextClientVersion)
        if (gl == null) {
            json.put(
                "note",
                "GL 능력 프로브를 수집하지 못했다 — onSurfaceCreated가 돌기 전에 세션이 끝났다"
            )
            return json
        }
        // 원문 그대로. 잘라내거나 정렬하지 않는다.
        json.put("gl_version", gl.version ?: JSONObject.NULL)
        json.put("gl_renderer", gl.renderer ?: JSONObject.NULL)
        json.put("gl_vendor", gl.vendor ?: JSONObject.NULL)
        json.put("gl_shading_language_version", gl.shadingLanguageVersion ?: JSONObject.NULL)
        json.put("gl_extensions", gl.extensions ?: JSONObject.NULL)
        json.put("gl_extension_count", gl.extensionCount)
        // 요청한 버전이 아니라 **실제로 얻은** 컨텍스트 버전.
        json.put("context_version_actual", gl.contextVersion)
        json.put("has_ext_disjoint_timer_query", gl.disjointTimerQuery)
        json.put("supports_compute_shader_es31", gl.computeShaderCapable)
        json.put("has_oes_egl_image_external_essl3", gl.oesEglImageExternalEssl3)
        json.put("probe_note", gl.note)
        return json
    }

    /**
     * ⚠ **`supported`의 의미를 이 앱에서는 "이 런이 GPU 패스 시간 표본을 낼 수 있었는가"로
     * 쓴다.** "확장이 존재하는가"는 `extension_present`로 따로 낸다(S1에서 채택한 정의를
     * 그대로 유지한다).
     *
     * 이유: 하네스의 모순 검사(`gpu_timer_contradicted`)는 "true라고 선언했는데 유효 표본이
     * 0"인 경우를 잡는 장치다. 확장 존재만으로 true를 선언하면 그 경고가 상시 켜져 무뎌진다.
     * 그래서 [GpuTimerReport.supported]는 **0보다 큰 표본을 실제로 남겼는가**로 정의돼 있다
     * (하네스 `_collect`의 하한이 `> 0`이라 0ms 값은 폐기된다 — 선언을 그 기준에 맞춘다).
     */
    private fun buildGpuTimer(facts: SessionFacts): JSONObject {
        val report = facts.gpuTimer
        val json = JSONObject()
        json.put("supported", report.supported)
        json.put("extension", GlCapabilitiesProbe.EXT_DISJOINT_TIMER_QUERY)
        json.put("extension_present", report.extensionPresent)
        json.put("instrumented", report.instrumented)
        // 🔴 **이 런이 어느 계측 방식이었는가**를 명시한다. method 문자열도 링이 실제로 쓴
        //    모드의 것이 나온다(상수를 여기서 고르면 모드와 어긋날 수 있다 — 링이 정한다).
        json.put("instrumentation", report.instrumentation)
        json.put("method", report.method)
        json.put("target_enum", "GL_TIME_ELAPSED_EXT=0x88BF, GL_GPU_DISJOINT_EXT=0x8FBB")
        json.put("ring_depth_frames", GpuTimerRing.RING_DEPTH)
        // arm마다 패스 수가 다르다(3패스 골격 3개 / drago 5개) → 상수가 아니라 실적에서 낸다.
        json.put("queries_per_frame", report.passesPerFrame)
        // ⚠ 위와 **다른 수**다. 단일 query 모드는 렌더 패스 3~9개에 query 1개다 —
        //   패스와 열이 1:1이 아니라는 사실이 이 두 값의 차로 드러난다.
        json.put("render_passes_per_frame", report.renderPassesPerFrame)
        json.put("max_passes_per_frame", GpuTimerRing.MAX_PASS_COUNT)

        // 실제로 CSV에 실은 열. 재지 않은 열은 싣지 않는다(§CSV_HEADER 주석).
        // 이름의 출처는 arm 하나다(RenderArm.gpuColumns) — 여기에 사본을 만들지 않는다.
        val columns = JSONArray()
        if (report.instrumented) {
            for (name in facts.arm.gpuColumns) {
                columns.put(name)
            }
        }
        json.put("columns_emitted", columns)

        json.put("instrumented_frames", report.instrumentedFrames)
        json.put("resolved_frames", report.resolvedFrames)
        json.put("resolved_queries", report.resolvedQueries)
        json.put("positive_samples", report.positiveSamples)
        json.put("zero_result_queries", report.zeroResultQueries)
        json.put("unresolved_queries", report.unresolvedQueries)
        json.put("disjoint_frames", report.disjointFrames)
        json.put("discarded_disjoint_queries", report.discardedDisjointQueries)
        json.put("skipped_ring_full_frames", report.skippedRingFullFrames)
        json.put("malformed_frames", report.malformedFrames)
        // 성공이면 키 자체가 없다. null을 넣으면 "확인은 했다"와 "에러가 없었다"가 섞인다.
        report.beginQueryError?.let { json.put("begin_query_error", it) }
        report.disabledReason?.let { json.put("disabled_reason", it) }

        // 프레임 단일 query arm이면 **읽는 법을 여기서도 낸다.** stage2_params에도 같은
        // 문장이 나가지만, gpu_timer 블록만 보고 숫자를 옮기는 독자가 있다 — 한쪽만 읽어도
        // 오독하지 않아야 한다.
        if (facts.arm.usesSingleFrameQuery) {
            json.put("single_frame_query_what_differs", RenderArm.SINGLE_QUERY_WHAT_DIFFERS)
            json.put("single_frame_query_not_a_sum", RenderArm.SINGLE_QUERY_NOT_A_SUM)
            json.put("single_frame_query_how_to_compare", RenderArm.SINGLE_QUERY_HOW_TO_COMPARE)
            json.put("single_frame_query_lower_bound", RenderArm.SINGLE_QUERY_LOWER_BOUND_NOTE)
            json.put(
                "peer_arm",
                facts.arm.singleFrameQueryPeer?.id ?: JSONObject.NULL
            )
            // 자기검사. null이면 키가 없다 — "확인했다"와 "어긋났다"를 섞지 않는다.
            RenderArm.SINGLE_FRAME_QUERY_COLUMN_MISMATCH?.let {
                json.put("single_frame_query_column_mismatch", it)
            }
            // ⚠ 여기서 attribution_note의 어느 부분이 이 런에 성립하는지를 가른다.
            //    🔴 **문단 순서로 가리키지 않는다**('마지막 문단' 같은 표현은 원문에 문단이
            //    하나 붙는 날 조용히 다른 곳을 가리킨다 — 실제로 그렇게 어긋난 적이 있다).
            //    내용으로 지목한다.
            json.put(
                "attribution_note_scope",
                "🔴 **아래 attribution_note는 패스별 계측(짝 arm)을 서술한 문장이다.** 이 런은 " +
                    "query가 하나뿐이므로 문단 순서가 아니라 **내용으로 골라 읽어야 한다.** " +
                    "▪ 이 런에 그대로 성립하는 것 = **eglSwapBuffers 항목**: 마지막 패스는 기본 " +
                    "프레임버퍼에 그리는데 그 타일 해결이 eglSwapBuffers에서 일어나고 " +
                    "GLSurfaceView는 그것을 onDrawFrame 반환 **후에** 부르므로 **모든 query의 " +
                    "바깥**이다 — 그래서 gpu_frame_ms도 하한이다" +
                    "(single_frame_query_lower_bound). " +
                    "▪ 이 런에 성립하지 **않는** 것 = ① '개별 열의 경계가 ±1패스만큼 흐리다'와 " +
                    "② **패스별 열이 약 한 패스 밀려 있다는 정량 근거 항목**(gpu_present_ms − " +
                    "적용 열이 arm별로 상수라는 그 대목, stage_d_apply_ms를 '적용 패스의 비용'으로 " +
                    "인용하면 틀린다는 결론까지) — **이 arm에는 패스별 열이 아예 없다.** " +
                    "그 항목을 남겨 두는 이유는 짝 arm의 열이 왜 부풀 수 있는지가 거기 적혀 " +
                    "있고, 이 arm의 값은 **그것과 나란히 놓을 때만** 뜻을 갖기 때문이다"
            )
        }

        if (facts.arm.usesComputeStage2) {
            val computePasses = when {
                facts.arm.usesChainedComputeStage2 -> "패스2·3·5·6"
                facts.arm.usesFusedComputeStage2 -> "패스2·3·4·5"
                else -> "패스2·패스3"
            }
            val consumers = when {
                facts.arm.usesChainedComputeStage2 -> "패스3·4·6·7"
                facts.arm.usesFusedComputeStage2 -> "패스3·4·5·6"
                else -> "패스3·패스4"
            }
            json.put(
                "compute_pass_note",
                "이 arm의 ${computePasses}은 glDispatchCompute다. 컴퓨트는 타일러를 거치지 않으므로 " +
                    "아래 attribution_note의 '렌더패스 병합' 갈래가 그대로 적용되지는 않는다. " +
                    "대신 SSBO 배리어(glMemoryBarrier)의 실제 대기가 어느 query에 담기는지는 " +
                    "드라이버가 정한다 — 배리어를 **소비하는 쪽 패스의 맨 앞**에 두었으므로 " +
                    "대기 비용은 소비자($consumers) 쪽으로 청구되도록 의도했다. 그것이 " +
                    "실제로 그렇게 되는지는 **이 기기에서 검증하지 못했다**"
            )
        }
        json.put(
            "attribution_note",
            "query가 패스 시퀀스의 모든 GL **명령**을 빈틈없이 덮는다. 그러나 타일 기반 " +
                "GPU(Mali-G68)에서 '명령을 덮는 것'은 '그 명령이 유발한 GPU 작업을 덮는 것'이 " +
                "아니다 — 어느 작업이 어느 query 구간에 담기는지는 드라이버가 정한다. " +
                "확실한 것: 마지막 패스는 기본 프레임버퍼에 그리는데 그 타일 해결은 " +
                "eglSwapBuffers에서 일어나고 GLSurfaceView는 그것을 onDrawFrame 반환 **후에** " +
                "부른다 — 모든 query의 바깥이다(GLSurfaceView를 쓰는 한 옮길 수 없다). " +
                "그래서 두 갈래이고 어느 쪽이든 '합은 정확하다'가 성립하지 않는다: " +
                "(1) 드라이버가 glEndQuery에서 렌더패스를 쪼갠다 → **계측이 측정 대상 " +
                "워크로드를 바꾸고 있다**, (2) 쪼개지 않는다 → **온스크린 해결 비용이 " +
                "gpu_present_ms 밖으로 떨어져 gpu_sum_ms가 과소가 된다**. " +
                "**우리는 이 기기에서 둘 중 어느 쪽인지 판별하지 못했다.** 따라서 개별 열의 " +
                "경계는 ±1패스만큼 흐리고 **합도 하한으로 읽어야 한다** — B·D 칸을 이 값으로 " +
                "채울 때 이 단서를 함께 옮길 것. " +
                // 아래는 정성적 서술이 아니라 **실측 정량 근거**다. 이 문단이 빠지면
                // stage_d_apply_ms가 '적용 패스 비용'으로 인용되고, 그건 틀린 인용이다.
                "🔴 **정량 근거(독립 검증 실측): gpu_present_ms는 present 패스를 재고 있지 " +
                "않다.** 적용 패스 열과 바로 다음 present 열의 차(gpu_present_ms − 적용 열; " +
                "3패스 arm은 stage_d_ms, 컴퓨트 arm은 stage_d_apply_ms)가 arm별로 " +
                "blit_2pass 0.692 · gamma_only 0.717 · drago 1.430 · agcwd 1.400 · " +
                "clahe_gamma 1.403 (ms)다. 컴퓨트 arm 셋에서 **적용 패스 값이 1.24ms 벌어지는 " +
                "동안 이 차는 ±0.03ms 상수**다 — 적용 패스(FBO_B)의 프래그먼트 실행이 자기 " +
                "query 창이 아니라 **다음 패스(present)의 창에 앉는다**는 뜻이다(타일러가 " +
                "FBO_B 렌더패스를 pass5에서 해소한다). 그러므로 **stage_d_apply_ms를 '적용 " +
                "패스의 비용'으로 인용하면 틀린다** — 개별 D 열은 약 한 패스 밀려 있고, " +
                "**이 런에서 신뢰할 수 있는 것은 합(gpu_sum)뿐이다.** 그 합도 위 이유로 " +
                "여전히 하한이다"
        )
        json.put(
            "instrumentation_overhead_note",
            "계측 on/off A/B는 이 빌드로 할 수 없다 — 패스스루가 아닌 arm은 항상 계측하고 " +
                "패스스루 arm은 절대 계측하지 않으므로, 두 arm의 차이에서 '패스 비용'과 'query 비용'을 " +
                "분리할 수단이 없다(구조적 한계이며 토글을 새로 만들지 않았다). 즉 여기 값에 " +
                "query 자체의 오버헤드가 얼마나 섞였는지는 미측정이다"
        )
        json.put(
            "discard_note",
            "disjoint가 세워진 프레임의 in-flight query는 전량 버렸고, 정지 시점에 안 끝난 " +
                "tail query도 버렸다(각각 discarded_disjoint_queries / unresolved_queries). " +
                "버린 자리는 CSV에 -1로 남는다 — 0으로 채우면 '그 패스가 0ms였다'는 거짓 " +
                "주장이 된다. 하네스는 -1을 below_min 폐기로 세므로 개수가 조용히 사라지지 " +
                "않는다. ⚠ skipped_ring_full_frames의 편향 방향: 링이 차는 것은 회수가 밀렸을 " +
                "때, 즉 **GPU가 느린 구간**이므로 버려지는 쪽이 가장 비싼 프레임이다 — 0이 " +
                "아니면 stage_* 분포의 p95/p99가 낙관 쪽으로 치우친다"
        )
        json.put(
            "disjoint_accounting_note",
            "GL_GPU_DISJOINT_EXT는 '지난 읽기 이후 누적'이고 읽으면 리셋된다. 계측하지 않는 " +
                "기간(프리뷰·arm 전환·앱 시작 직후)에 선 플래그가 이 런의 것으로 계상되지 " +
                "않도록, 런의 **첫 읽기는 기준선으로만 쓰고 세지 않는다**. 런 도중의 읽기는 " +
                "전부 센다 — 그 구간은 in-flight query가 실제로 걸쳐 있는 구간이다"
        )
        json.put(
            "note",
            "패스스루 arm에는 query를 하나도 걸지 않는다 — query 자체가 GPU 동작과 드라이버 " +
                "스케줄링을 바꾸므로, 승격 베이스라인을 재현하는 경로에 넣으면 그 기준이 " +
                "기준이 아니게 된다. stage_i_ms는 ④ 오버레이 arm" +
                "(highlight_boxes / highlight_boxes_stress)이 낸다"
        )
        return json
    }

    /** ②의 파라미터. 파라미터가 다르면 D 실측끼리 비교가 성립하지 않는다. */
    private fun buildStage2Params(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        json.put("temporal_state", RenderArm.TEMPORAL_STATE)
        when (facts.arm) {
            RenderArm.PASSTHROUGH -> {
                json.put("algorithm", "none")
                json.put("note", "패스스루 arm에는 ② 자리 자체가 없다(1패스)")
            }
            RenderArm.BLIT_2PASS -> putBlit2Pass(json)
            RenderArm.GAMMA_ONLY -> {
                json.put("algorithm", "gamma_only")
                json.put("gamma", RenderArm.GAMMA_MEASUREMENT_VALUE.toDouble())
                json.put("uniform", RenderArm.GAMMA_UNIFORM)
                // 지어낸 계약값을 조용히 굳히지 않기 위한 문장이다. 지우지 말 것.
                json.put("provenance", RenderArm.GAMMA_PROVENANCE)
                json.put(
                    "note",
                    "② 저조도 개선 알고리즘이 아니다. pow() 한 번짜리 프래그먼트로 " +
                        "**② 비용의 하한**만 본다"
                )
            }
            RenderArm.DRAGO -> {
                json.put("algorithm", "drago_tonemap")
                json.put("upstream_reference", "scripts/lowlight.py D1 (OpenCV TonemapDrago)")
                json.put("gamma", RenderArm.DRAGO_GAMMA.toDouble())
                json.put("saturation", RenderArm.DRAGO_SATURATION.toDouble())
                json.put("bias", RenderArm.DRAGO_BIAS.toDouble())
                json.put("src_gamma", RenderArm.DRAGO_SRC_GAMMA.toDouble())
                json.put("luma_weights", RenderArm.DRAGO_LUMA_WEIGHTS)
                json.put(
                    "uniforms",
                    JSONArray()
                        .put(RenderArm.DRAGO_SRC_GAMMA_UNIFORM)
                        .put(RenderArm.DRAGO_OUT_GAMMA_UNIFORM)
                        .put(RenderArm.DRAGO_SATURATION_UNIFORM)
                        .put(RenderArm.DRAGO_BIAS_UNIFORM)
                )
                // 지어낸 계약값을 조용히 굳히지 않기 위한 문장이다. 지우지 말 것.
                json.put("provenance", RenderArm.DRAGO_PROVENANCE)
                json.put("upstream_deviation", RenderArm.DRAGO_DEVIATION)
                json.put("flicker_note", RenderArm.DRAGO_FLICKER_NOTE)
                json.put("gpu_status", facts.stage2Status)
                json.put(
                    "operates_on",
                    "선형 RGB 3채널 전부 (A1·A2가 LAB의 L만 건드리는 것과 다르다). " +
                        "sRGB를 pow(x, src_gamma)로 선형화한 뒤 오퍼레이터에 넣고 " +
                        "pow(x, 1/gamma)로 되씌운다"
                )
                json.put(
                    "note",
                    "전역 통계가 필요해 ② 자리가 3단(리덕션 → 계수 → 적용)이다. " +
                        "하위 패스를 합치지 않고 D 계열 슬롯 3개에 그대로 낸다 " +
                        "(docs/FRAME_LOG_SCHEMA.md §2)"
                )
                json.put(
                    "how_to_compare",
                    "🔴 **stage_d_total_ms만 인용하지 말 것 — ② 증분을 과소로 낸다.** " +
                        "상류 CPU 실측(720p 82.9ms)과 비교할 숫자는 **gpu_sum_ms의 arm 간 " +
                        "차분**이다(이 arm − blit_2pass). 근거: 독립 검증 실측에서 " +
                        "gpu_present_ms가 **세 arm에서 글자 그대로 같은 코드인데** " +
                        "blit_2pass 1.875 → gamma_only 2.123 → drago 3.597ms로 움직였다. " +
                        "즉 ② 증분의 약 27%가 D 열이 아니라 present에 앉는다 — " +
                        "stage_d_total_ms 5.75ms vs gpu_sum 차분 6.36ms(약 10% 과소). " +
                        "⚠ **그 6.36ms도 하한이다** — 패스3의 타일 해결이 eglSwapBuffers에서 " +
                        "일어나 세 query 전부의 바깥이다(gpu_timer.attribution_note). " +
                        "⚠ 그리고 상류 82.9ms는 PC CPU/NumPy 기준이라 조건이 다르다 — " +
                        "나란히 놓을 때 그 사실을 함께 옮길 것. " +
                        RenderArm.COLUMN_RANK_INVERSION_NOTE
                )
                // A1과 나란히 놓일 숫자라 drago 로그에도 같은 분해를 싣는다 —
                // 한쪽에만 있으면 그 arm의 세션만 본 사람이 반대 결론을 낸다.
                json.put("cost_split_note", RenderArm.COST_SPLIT_NOTE)
                json.put(
                    "cost_breakdown_note",
                    "D 내부 분해를 '리덕션이 60%'로 읽지 말 것. analyze의 비용 중 약 1.13ms는 " +
                        "리덕션이 아니라 **FBO_A를 처음 소비하는 패스의 자리 비용**이다 " +
                        "(blit_2pass의 같은 자리 단순 복사가 1.128ms). 3패스 골격 대비 " +
                        "**증분**으로 보면 analyze+build +3.47(55%) · apply−복사 +1.11(18%) · " +
                        "present 번짐 +1.72(27%)이며 합이 gpu_sum 증분과 맞는다. " +
                        "경량화 레버를 고를 때 '리덕션만 반으로 줄이면 D가 30% 준다'는 읽기는 틀린다"
                )
            }
            RenderArm.CLAHE_GAMMA -> {
                json.put("algorithm", "clahe_gamma")
                json.put("upstream_reference", "scripts/lowlight.py A1 (OpenCV createCLAHE + 감마)")
                json.put("clip_limit", RenderArm.CLAHE_CLIP_LIMIT.toDouble())
                json.put("tile_grid", RenderArm.CLAHE_TILE_GRID)
                json.put("gamma", RenderArm.CLAHE_GAMMA_VALUE.toDouble())
                json.put("histogram_bins", LabGlsl.BIN_COUNT)
                json.put(
                    "uniforms",
                    JSONArray()
                        .put(RenderArm.CLAHE_CLIP_LIMIT_UNIFORM)
                        .put(RenderArm.CLAHE_TILES_UNIFORM)
                        .put(RenderArm.CLAHE_GAMMA_UNIFORM)
                )
                putLabCommon(json, facts)
                json.put(
                    "note",
                    "타일별 히스토그램이 필요해 ② 자리가 3단(히스토그램 → 클립+재분배+CDF → " +
                        "타일 이중선형 보간+감마)이다. 하위 패스를 합치지 않고 D 계열 슬롯 " +
                        "3개에 그대로 낸다 (docs/FRAME_LOG_SCHEMA.md §2). " +
                        "감마는 **타일 보간 뒤**에 씌운다 — LUT마다 미리 구우면 pow가 " +
                        "비선형이라 보간 결과가 달라진다"
                )
            }
            RenderArm.AGCWD -> {
                json.put("algorithm", "agcwd")
                json.put("upstream_reference", "scripts/lowlight.py A2 (AGCWD, Huang 2013)")
                json.put("alpha", RenderArm.AGCWD_ALPHA.toDouble())
                json.put("histogram_bins", LabGlsl.BIN_COUNT)
                json.put("uniforms", JSONArray().put(RenderArm.AGCWD_ALPHA_UNIFORM))
                putLabCommon(json, facts)
                json.put(
                    "formula",
                    "pdf_w(l) = pdf_max * ((pdf(l) - pdf_min) / (pdf_max - pdf_min))^alpha ; " +
                        "cdf_w = 누적(pdf_w) / 합(pdf_w) ; T(l) = 255 * (l/255)^(1 - cdf_w(l))"
                )
                json.put(
                    "note",
                    "전역 히스토그램 하나라 ② 자리가 3단(히스토그램 → 가중 분포 LUT → " +
                        "LUT 적용)이다. 하위 패스를 합치지 않고 D 계열 슬롯 3개에 그대로 낸다 " +
                        "(docs/FRAME_LOG_SCHEMA.md §2). LUT는 최근접 빈으로 읽는다 — " +
                        "상류가 8비트 L에 256엔트리 LUT를 그대로 적용하기 때문이다"
                )
            }
            RenderArm.DRAGO_CLAHE_CHAIN -> putChain(json, facts)
            RenderArm.DRAGO_CLAHE_FUSED -> putFused(json, facts)
            RenderArm.DRAGO_CLAHE_CHAIN_BF -> {
                putChain(json, facts)
                putBilateralOverrides(json, facts, RenderArm.DRAGO_CLAHE_CHAIN_BF)
            }
            RenderArm.DRAGO_CLAHE_FUSED_BF -> {
                putFused(json, facts)
                putBilateralOverrides(json, facts, RenderArm.DRAGO_CLAHE_FUSED_BF)
            }
            // ── 프레임 단일 query arm ─────────────────────────────────────
            // 🔴 **짝 arm의 서술을 그대로 재사용하고 `else` 낙하로 처리하지 않는다.** ② 자리의
            //    알고리즘·파라미터·이탈은 짝과 **글자 그대로 같아야** 두 계측을 비교할 수 있고,
            //    사본을 만들면 한쪽만 고쳐지는 날 두 arm의 서술이 갈라진다. 다른 것은 계측
            //    방식뿐이며 그 사실은 putSingleFrameQueryNotes가 덮어쓴다.
            RenderArm.BLIT_2PASS_1Q -> {
                putBlit2Pass(json)
                putSingleFrameQueryNotes(json, RenderArm.BLIT_2PASS_1Q)
            }
            RenderArm.DRAGO_CLAHE_CHAIN_1Q -> {
                putChain(json, facts)
                putSingleFrameQueryNotes(json, RenderArm.DRAGO_CLAHE_CHAIN_1Q)
            }
            RenderArm.DRAGO_CLAHE_CHAIN_BF_1Q -> {
                putChain(json, facts)
                putBilateralOverrides(json, facts, RenderArm.DRAGO_CLAHE_CHAIN_BF)
                putSingleFrameQueryNotes(json, RenderArm.DRAGO_CLAHE_CHAIN_BF_1Q)
            }
            // ── ③ 탐지 arm ────────────────────────────────────────────────
            // ② 자리는 **단순 복사**이고 렌더가 blit_2pass와 글자 그대로 같다.
            // 🔴 짝의 서술을 그대로 재사용하고 `else` 낙하로 처리하지 않는다 — 사본을 만들면
            //    한쪽만 고쳐지는 날 두 arm의 서술이 갈라진다(`_1q` arm과 같은 이유).
            RenderArm.DETECT_BIND_ONLY,
            RenderArm.DETECT_CPU,
            RenderArm.DETECT_NNAPI,
            RenderArm.DETECT_XNNPACK,
            RenderArm.DETECT_CPU_PROF,
            RenderArm.DETECT_NNAPI_PROF,
            RenderArm.DETECT_XNNPACK_PROF -> {
                putBlit2Pass(json)
                // 🔴 ② 서술만 보고 "탐지가 프레임타임에 안 들어간다"를 유도하지 못하게 한다.
                json.put("detect_round_scope", RenderArm.DETECT_ROUND_SCOPE)
            }
            // ④ arm의 ② 자리는 **단순 복사**다. 오버레이 서술은 root의 overlay 블록에 있다.
            RenderArm.HIGHLIGHT_BOXES, RenderArm.HIGHLIGHT_BOXES_STRESS -> {
                json.put("algorithm", "copy")
                json.put("gpu_status", facts.stage2Status)
                json.put(
                    "note",
                    "④ 오버레이 arm이라 ② 자리는 단순 복사다 — 여기서 나오는 stage_d_ms는 " +
                        "②의 비용이 아니라 **골격 자체(오프스크린 왕복)의 비용**이며 " +
                        "blit_2pass의 같은 자리와 같은 것이다. 이 arm이 재는 것은 " +
                        "**stage_i_ms(④ 오버레이)**이고 그 서술은 session.json의 overlay 블록에 " +
                        "있다"
                )
            }
        }
        return json
    }

    /**
     * 3패스 골격 arm(`blit_2pass`)의 ② 서술. **`blit_2pass_1q`도 이 함수를 부른다** —
     * 두 arm의 렌더가 글자 그대로 같아야 계측 방식의 차이만 남고, 서술에 사본을 만들면
     * 한쪽만 고쳐지는 날 두 arm의 문장이 갈라진다([putChain]/[putFused]와 같은 이유다).
     *
     * [SessionFacts]를 받지 않는다 — 이 arm의 ② 자리에는 런에 따라 달라지는 값이 없다.
     */
    private fun putBlit2Pass(json: JSONObject) {
        json.put("algorithm", "copy")
        json.put(
            "note",
            "3패스 골격은 다 돌지만 ② 자리는 단순 복사다. 여기서 나오는 비용은 " +
                "②의 비용이 아니라 **골격 자체(오프스크린 왕복)의 비용**이다"
        )
    }

    /**
     * 프레임 단일 query arm의 서술. 짝 arm의 ② 서술 **위에 계측 방식의 차이만 덮는다.**
     *
     * 🔴 **② 알고리즘 관련 키는 하나도 건드리지 않는다.** 이 arm은 알고리즘이 아니라 계측
     * 방식이 다르고, 짝과 렌더가 같아야 두 값의 차가 뜻을 갖는다 — 여기서 알고리즘 서술을
     * 고치면 그 전제가 로그 위에서부터 깨진다.
     *
     * 더하는 키: `instrumentation` · `render_arm_peer` · `instrumentation_note` ·
     * `gpu_column_note` · `how_to_compare_instrumentation` · `lower_bound_note`.
     * 🔴 `how_to_compare`는 **덮지 않는다** — 그 키는 ②의 비용을 arm끼리 비교하는 법이고
     * 짝 arm의 문장이 여기서도 그대로 성립한다. 계측 방식의 비교법은 별 키로 낸다(두 문장이
     * 서로 다른 질문에 답하므로 한 키에 뭉치면 하나가 묻힌다).
     */
    private fun putSingleFrameQueryNotes(json: JSONObject, arm: RenderArm) {
        json.put("instrumentation", GpuTimerRing.INSTRUMENTATION_SINGLE_FRAME_QUERY)
        json.put("render_arm_peer", arm.singleFrameQueryPeer?.id ?: JSONObject.NULL)
        json.put("instrumentation_note", RenderArm.SINGLE_QUERY_WHAT_DIFFERS)
        json.put("gpu_column_note", RenderArm.SINGLE_QUERY_NOT_A_SUM)
        json.put("how_to_compare_instrumentation", RenderArm.SINGLE_QUERY_HOW_TO_COMPARE)
        json.put("lower_bound_note", RenderArm.SINGLE_QUERY_LOWER_BOUND_NOTE)
    }

    /**
     * `+bf` arm의 서술. [putChain]/[putFused]가 만든 **base arm 서술 위에 bf의 차이만 덮는다.**
     *
     * 🔴 **덮는 키 목록이 곧 이 함수의 계약이다.** [putChain]/[putFused]에 키를 더할 때는
     * 여기도 함께 봐야 한다 — base 서술 중 bf arm에서 거짓이 되는 문장이 있으면 여기서 덮지
     * 않는 한 그대로 로그로 나간다. 사본을 만들지 않고 덮는 이유는 반대쪽 실패를 피하려는
     * 것이다(90%가 같은 서술을 복붙하면 한쪽만 고쳐진다).
     *
     * 덮는 키: `algorithm` · `upstream_reference` · `composition` · `composition_note` ·
     * `stage_order` · `stages`(bf 스테이지 추가) · `provenance` · `glare_note` ·
     * `flicker_note` · `how_to_compare` · `levers_not_pulled` · `color_transform_*`
     * (arm이 바뀌므로) · `note`. 새로 더하는 키: `upstream_deviation_bf` · `bilateral`.
     * 그대로 두는 키: `upstream_deviation`(+ `_chain`/`_drago`/`_lab`) · `desaturation_note` ·
     * `ssbo_binding_note` · `cost_split_note` · `gpu_status` — bf가 붙어도 그대로 성립한다.
     */
    private fun putBilateralOverrides(
        json: JSONObject,
        facts: SessionFacts,
        arm: RenderArm,
    ) {
        val chained = arm == RenderArm.DRAGO_CLAHE_CHAIN_BF
        json.put("algorithm", arm.id)
        json.put(
            "upstream_reference",
            if (chained) {
                "scripts/lowlight.py의 D1 → A1 → +bf **직렬**(상류 조합 D1A1+bf). " +
                    "🔴 상류 잠정 1위 `D1A1+bf(+ts)`와 **구성이 같아진 첫 arm**이다 — 다만 " +
                    "`ts`는 여전히 없다(INTERFACES.md §B-4가 ☐다)"
            } else {
                "scripts/lowlight.py의 D1 · A1 · +bf 파라미터를 쓰지만 **구성은 상류에 없다** — " +
                    "융합이 상류에 없는 변형이기 때문이다(upstream_deviation 참고). 상류 " +
                    "`D1A1+bf` 옆에 놓을 수 있는 것은 `drago_clahe_chain_bf`다"
            }
        )
        json.put(
            "composition",
            if (chained) {
                "chain + bilateral (중간 표현을 RGBA8 FBO로 materialize한다. bf도 한 장 더 쓴다)"
            } else {
                "fused + bilateral (② 안에는 중간 표현이 없고, bf 결과만 FBO로 떨어진다)"
            }
        )
        json.put(
            "composition_note",
            "② 자리 뒤에 **bilateral 한 패스**가 붙었다. bf는 gather 필터라 통계 패스도 SSBO도 " +
                "없고 프래그먼트 하나다. 🔴 **분리형 2패스 근사를 쓰지 않았다** — bilateral은 " +
                "분리 불가능한 필터라 그것은 근사이고 융합과 같은 부류의 알고리즘 변경이다" +
                "(levers_not_pulled의 (bf-2)). 이번 라운드는 **상류 충실 1패스**다. " +
                "앞부분(체인/융합)은 base arm과 **글자 그대로 같은 GL 호출**이므로 두 arm의 " +
                "gpu_sum 차분이 곧 bf 한 패스의 비용이다"
        )
        json.put(
            "stage_order",
            JSONArray().put("drago_tonemap").put("clahe_gamma").put("bilateral")
        )
        // 앞 두 스테이지 서술은 base가 이미 넣었다. 여기서는 3번째만 이어 붙인다.
        json.optJSONArray("stages")?.put(
            JSONObject()
                .put("index", 3)
                .put("algorithm", "bilateral")
                .put(
                    "upstream_reference",
                    "scripts/lowlight.py +bf (cv2.bilateralFilter, 동작 공간 BGR)"
                )
                .put("d", RenderArm.BF_D)
                .put("radius", RenderArm.BF_RADIUS)
                .put("radius_note", "자유 파라미터가 아니라 d의 유도값이다(radius = d/2, 정수 나눗셈)")
                .put("tap_count", RenderArm.BF_TAP_COUNT)
                .put(
                    "tap_note",
                    "**원형 이웃**이라 ${RenderArm.BF_TAP_COUNT}탭이다(i*i+j*j <= radius*radius). " +
                        "7x7 사각형 49탭이 아니다"
                )
                .put("sigma_color", RenderArm.BF_SIGMA_COLOR.toDouble())
                .put("sigma_color_unit", "0..255 (셰이더가 0..1 L1 합에 255를 곱해 맞춘다)")
                .put("sigma_space", RenderArm.BF_SIGMA_SPACE.toDouble())
                .put("sigma_space_unit", "픽셀")
                .put(
                    "uniforms",
                    JSONArray()
                        .put(RenderArm.BF_RADIUS_UNIFORM)
                        .put(RenderArm.BF_SIGMA_COLOR_UNIFORM)
                        .put(RenderArm.BF_SIGMA_SPACE_UNIFORM)
                        .put(RenderArm.BF_TEXEL_UNIFORM)
                )
                .put(
                    "operates_on",
                    "② 출력 RGBA8을 **sRGB 그대로** 필터한다(선형화하지 않고 LAB도 쓰지 " +
                        "않는다 — 상류가 8비트 BGR 이미지에 걸기 때문이다). 알파는 필터하지 " +
                        "않고 통과시킨다. **LabGlsl을 한 번도 부르지 않는다**"
                )
                .put("ssbo_binding", "없음 (gather 필터라 통계 버퍼가 없다)")
        )
        json.put(
            "provenance",
            json.optString("provenance") + " ── +bf: " + RenderArm.BF_PROVENANCE
        )
        // 🔴 base의 이탈은 그대로 성립하고, bf 고유 이탈이 그 위에 하나 더 붙는다.
        json.put("upstream_deviation_bf", RenderArm.BF_DEVIATION)
        // 🔴 base의 glare_note는 "bf가 없다"고 말한다 — 이 arm에서 그 문장은 거짓이다.
        json.put("glare_note", RenderArm.BF_GLARE_NOTE)
        json.put(
            "flicker_note",
            json.optString("flicker_note") + " ── +bf: " + RenderArm.BF_FLICKER_NOTE
        )
        json.put("how_to_compare", RenderArm.BF_HOW_TO_COMPARE)
        json.put(
            "levers_not_pulled",
            if (chained) RenderArm.CHAIN_BF_LEVERS_NOT_PULLED
            else RenderArm.FUSED_BF_LEVERS_NOT_PULLED
        )
        // arm이 바뀌었으므로 계수·선언값·짝을 이 arm 기준으로 다시 낸다.
        putColorTransform(json, facts, arm)
        json.put("gpu_status", facts.stage2Status)
        json.put(
            "note",
            if (chained) {
                "② 자리가 **7패스**(3단 × 2벌 + bf 1)이고 전체 9패스다. bf는 " +
                    "**stage_d_denoise_ms**를 낸다 — 스키마 v3에서 들어와 있던 그 열을 이 arm이 " +
                    "처음 채운다. 하위 패스를 합치지 않고 D 계열 슬롯 7개에 그대로 낸다" +
                    "(docs/FRAME_LOG_SCHEMA.md §2)"
            } else {
                "② 자리가 **6패스**(통계 2벌 + 적용 1 + bf 1)이고 전체 8패스다. " +
                    "**stage_d_apply2_ms를 쓰지 않고** stage_d_denoise_ms를 쓴다 — 적용이 하나로 " +
                    "접혔고 재지 않은 열은 싣지 않는다. 그래서 열 순서가 " +
                    "…analyze2, build2, apply, denoise, present가 된다(gpuColumns는 **패스 순서 " +
                    "그대로**다). ⚠ 체인+bf와 패스 수가 달라(9 vs 8) 열 단위 대조는 성립하지 " +
                    "않는다"
            }
        )
    }

    /**
     * ④ 오버레이 블록. **명세는 상류 `scripts/emphasize.py`에서 확정된 것**이고
     * (`FRAME_BUDGET.md` §3 주5 · `RESEARCH_20260803_UPSTREAM.md` §5) 값은 전부
     * [RenderArm]의 상수에서 온다 — 여기에 숫자를 다시 적지 않는다.
     *
     * 🔴 `box_count`가 **필수**다. 없으면 `stage_i_ms`가 무슨 조건의 값인지 사라진다.
     */
    private fun buildOverlay(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        json.put("stage", "④ 선택적 강조 (버짓 I칸)")
        json.put("gpu_column", "stage_i_ms")
        json.put("upstream_reference", "scripts/emphasize.py")
        json.put("spec_provenance", RenderArm.HIGHLIGHT_SPEC_PROVENANCE)
        // 🔴 조건. 지우지 말 것.
        json.put("box_count", facts.arm.highlightBoxCount)
        json.put("box_count_provenance", RenderArm.HIGHLIGHT_BOX_PROVENANCE)
        json.put(
            "box_source",
            "🔴 **정적 더미 박스다.** ③ 탐지 결과가 아니다(③ 미구현). 난수를 쓰지 않으므로 " +
                "프레임마다 완전히 같고, 그래서 재현 가능하다"
        )
        json.put("shape", "이중 스트로크 (검정 밑선 + 대비색 본선)")
        json.put(
            "fill",
            "비채움 — 박스 **내부**(스트로크보다 더 안쪽)는 한 픽셀도 건드리지 않는다. " +
                "⚠ 다만 이 문장은 **내부에 대한 것뿐이다**: 스트로크 자체는 경계선 위에 가운데 " +
                "맞춤이라 **경계 밖 " +
                "${(RenderArm.HIGHLIGHT_STROKE_PX_AT_720P + 2f * RenderArm.HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P) / 2f}" +
                "px(720p 기준)을 덮는다** — 스펙 문구('경계선 밖은 일절 안 건드림')와 어긋나는 " +
                "지점이고, 전문은 upstream_deviation에 있다"
        )
        // 🔴 스펙 문구와 기하가 다르다는 사실. 이것이 없으면 픽셀 대조하는 날 막힌다.
        json.put("upstream_deviation", RenderArm.HIGHLIGHT_DEVIATION)
        json.put("stroke_px_at_720p", RenderArm.HIGHLIGHT_STROKE_PX_AT_720P.toDouble())
        json.put(
            "underline_margin_px_at_720p",
            RenderArm.HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P.toDouble()
        )
        json.put("stroke_formula", RenderArm.HIGHLIGHT_STROKE_FORMULA)
        // 실제로 이번 처리 해상도에서 쓴 값. 계산식만 적으면 값이 맞는지 확인할 수 없다.
        if (facts.processWidth > 0 && facts.processHeight > 0) {
            json.put("process_resolution", "${facts.processWidth}x${facts.processHeight}")
            json.put("short_side_px", minOf(facts.processWidth, facts.processHeight))
        } else {
            json.put("process_resolution", JSONObject.NULL)
            json.put(
                "resolution_note",
                "협상된 처리 해상도가 없어 두께를 계산하지 못했다 — 값을 지어내지 않는다"
            )
        }
        json.put(
            "colors",
            JSONObject()
                .put("stairs", RenderArm.HIGHLIGHT_COLOR_STAIRS)
                .put("person", RenderArm.HIGHLIGHT_COLOR_PERSON)
                .put("underline", RenderArm.HIGHLIGHT_COLOR_UNDERLINE)
        )
        json.put("class_note", RenderArm.HIGHLIGHT_CLASS_NOTE)
        json.put("no_red_reason", RenderArm.HIGHLIGHT_NO_RED_REASON)
        json.put("no_blink_reason", RenderArm.HIGHLIGHT_NO_BLINK_REASON)
        // 🔴 이 문장이 빠지면 "깜빡임 없음"이 성능·안전 근거처럼 읽힌다.
        json.put("blink_not_a_perf_claim", RenderArm.HIGHLIGHT_BLINK_NOT_A_PERF_CLAIM)
        json.put(
            "geometry",
            "얇은 사각형 스트로크 quad(${HighlightOverlay.GL_PRIMITIVE_NAME}). 박스당 정점 " +
                "${HighlightOverlay.VERTS_PER_BOX}개(띠 4개 × 삼각형 2개 × 3정점 × 스트로크 2벌)" +
                "이고 드로우콜은 프레임당 **1회**다. " +
                "🔴 전체화면 프래그먼트 SDF로 그리지 않았다 — 그러면 픽셀 셰이더가 화면 전체를 " +
                "돌아 오버레이 비용이 화면 전체 비용으로 부풀고 I칸이 **다른 물리량**이 된다" +
                "(FRAME_BUDGET.md §5가 I칸을 GL 드로우콜 계측으로 못 박았다)"
        )
        json.put(
            "tile_reload_note",
            "⚠ 이 패스는 **glClear를 부르지 않는다** — ② 출력 위에 얹기 때문이다. 그래서 타일 " +
                "GPU가 컬러 어태치먼트를 다시 load하고, **stage_i_ms에는 그 비용이 섞여 있다.** " +
                "오버레이 패스의 실제 비용이며 빼낼 수단이 없다. 게다가 패스2(복사)와 패스3의 " +
                "타깃이 같은 FBO_B라 드라이버가 두 렌더패스를 병합하면 두 열의 경계가 흐려진다 " +
                "— 일반 주의사항은 gpu_timer.attribution_note와 같다"
        )
        json.put("how_to_compare", RenderArm.HIGHLIGHT_HOW_TO_COMPARE)
        json.put("gpu_status", facts.overlayStatus)
        return json
    }

    /**
     * ③ 탐지 블록. 🔴 **하네스가 값을 읽고 판정에 쓰는 유일한 세션 블록이다**
     * (`docs/FRAME_LOG_SCHEMA.md` §5): `enabled`는 반쪽 회수 실패(exit 4)를,
     * `ep.requested` ≠ `ep.resolved`는 계획 어긋남을 만든다. 나머지 키는 해석하지 않고 싣는다.
     *
     * 🔴 **`enabled`의 뜻은 "이 런이 `detect.csv`를 내는가"다.** 추론이 실제로 도는 arm에서만
     * true이고, 분모 arm(`detect_bind_only`)은 추론이 없으므로 false다 — true로 두면
     * `pull_frames.py`가 없는 파일을 요구해 회수가 exit 4로 죽는다.
     */
    private fun buildDetect(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        val report = facts.detect
        val run = facts.detectRun

        // 🔴 하네스의 회수 판정 기준.
        val enabled = facts.arm.usesDetectSession && (run?.csvRows ?: 0) > 0
        json.put("enabled", enabled)
        json.put(
            "enabled_reason",
            if (enabled) {
                "이 런은 detect.csv를 낸다(추론 ${run?.csvRows ?: 0}행). enabled의 뜻은 " +
                    "'③ 탐지를 켠 런인가'이자 pull_frames.py의 반쪽 회수 판정 기준이다 " +
                    "— true인데 파일이 없으면 exit 4다"
            } else if (!facts.arm.usesDetectSession) {
                "🔴 **false인 이유: 이 arm은 추론을 돌리지 않는다**(detect_bind_only는 " +
                    "ImageAnalysis만 붙이는 분모 arm이다). 추론 1회당 1행을 낼 원천이 " +
                    "없으므로 detect.csv도 없다"
            } else {
                "🔴 **false인 이유: 추론이 한 번도 기록되지 않아 detect.csv를 만들지 " +
                    "않았다.** 빈 CSV를 내면 하네스의 read_detect가 '행이 하나도 없다'로 " +
                    "죽고, 그건 사실을 파일 존재로 덮는 것이다. 무슨 일이 있었는지는 " +
                    "아래 run 블록(회계)이 말한다"
            }
        )
        json.put("session_loaded", report?.ok == true)
        json.put("round_scope", RenderArm.DETECT_ROUND_SCOPE)
        json.put("arm", facts.arm.id)
        if (facts.arm.detectProfilingEnabled) {
            json.put("prof_arm_not_quotable", RenderArm.DETECT_PROF_NOT_QUOTABLE)
        }

        // 🔴 탐지 주기 N은 INTERFACES.md에서 아직 ☐다. **null이 정상이고 값을 지어내지 않는다.**
        json.put("period_n", JSONObject.NULL)
        json.put(
            "period_n_reason",
            "탐지 주기 N은 INTERFACES.md에서 아직 ☐ 미정이라 앱이 값을 지어내지 않는다. " +
                "대신 **idle-gated**로 돈다 — 분석 프레임이 올 때 탐지가 유휴이면 즉시 " +
                "추론하고, 바쁘면 skipped_while_busy를 올리고 버린다. 실제 주기는 하네스가 " +
                "detect_cadence_ms 분포로 말한다(선언된 N이 아니라 관측값이다)"
        )
        json.put("trigger", "idle_gated")
        json.put("upper_bound_note", RenderArm.DETECT_UPPER_BOUND)
        if (facts.arm.isDetectArm) {
            json.put("input", buildAnalysisInput(facts))
        }
        if (run != null) {
            json.put("run", buildDetectRun(run))
        }

        if (report == null) {
            json.put(
                "note",
                "③ arm인데 준비 보고가 없다 — 세션을 열지 않는 arm(detect_bind_only)이거나 " +
                    "준비 전에 런이 시작된 것이다. 값을 지어내지 않는다"
            )
            json.put("model", JSONObject().put("sha256", JSONObject.NULL))
            json.put(
                "ep",
                JSONObject()
                    .put("requested", facts.arm.detectEpRequested ?: JSONObject.NULL)
                    // 🔴 모름과 CPU는 다른 사실이다. 요청값을 베끼지 않는다.
                    .put("resolved", DetectContract.EP_UNKNOWN)
                    .put("resolution_method", DetectContract.METHOD_NONE)
            )
            json.put("padding_pixel_fraction", JSONObject.NULL)
            return json
        }

        if (!report.ok) {
            json.put("prepare_failure", report.failure ?: "사유 없음")
        }
        json.put("model", buildDetectModel(report))
        json.put("ep", buildDetectEp(report))
        json.put("graph", buildDetectGraph(report))
        json.put("classes", buildDetectClasses(report))
        json.put("runtime", buildDetectRuntime(report))
        json.put("preprocess_assumptions", buildDetectPreprocess())
        json.put("padding_pixel_fraction", detectPaddingFraction(facts, report))
        json.put("padding_pixel_fraction_note", detectPaddingNote(facts, report))
        json.put("prepare_timing", buildDetectPrepareTiming(report))
        if (report.warnings.isNotEmpty()) {
            val warnings = JSONArray()
            for (w in report.warnings) warnings.put(w)
            json.put("warnings", warnings)
        }
        return json
    }

    /**
     * 🔴 **`sha256`은 앱이 로드한 파일 바이트에서 직접 계산한 값이다.** `metadata.json`의
     * 값을 베껴 넣으면 그건 주장이지 사실이 아니다 — 선언값은 옆 칸에 따로 두고 대조 결과를
     * 함께 낸다. 불일치면 앱이 애초에 런을 시작하지 않으므로 이 파일이 존재한다는 것 자체가
     * `sha256_matches_declared=true`의 방증이지만, **그래도 값을 싣는다**(나중에 되물을 근거).
     */
    private fun buildDetectModel(report: DetectReport): JSONObject = JSONObject()
        .put("sha256", report.sha256Computed ?: JSONObject.NULL)
        .put("sha256_declared", report.sha256Declared)
        .put(
            "sha256_matches_declared",
            report.sha256MatchesDeclared ?: JSONObject.NULL
        )
        .put(
            "sha256_source",
            "앱이 로드한 파일 바이트를 MessageDigest(SHA-256)로 직접 계산했다. " +
                "metadata.json의 값을 베낀 것이 아니다"
        )
        .put("sha256_declared_source", DetectContract.declaredSource)
        .put("file", DetectContract.declaredFileName)
        .put("path", report.modelPath ?: JSONObject.NULL)
        .put("bytes", report.modelBytes)
        .put(
            "delivery",
            "adb push → getExternalFilesDir(null)/${DetectContract.MODELS_SUBDIR}/. " +
                "APK에 동봉하지 않는 이유: .onnx가 gitignore라 동봉하면 **APK 빌드가 추적되지 " +
                "않는 파일에 의존**하게 되고 모델 교체마다 재빌드가 필요해진다. " +
                "🔴 푸시를 잊으면 앱이 그 arm의 런을 **거부한다** — 조용히 탐지 없이 도는 " +
                "경로를 만들지 않는다"
        )

    /**
     * EP 블록. 🔴 **`requested`와 `resolved`를 둘 다 낸다** — 한쪽만 적으면 조용한 폴백이
     * 실패로 잡히지 않는다(하네스는 둘이 다르면 그 런을 계획 어긋남으로 만든다).
     */
    private fun buildDetectEp(report: DetectReport): JSONObject {
        val json = JSONObject()
        json.put("requested", report.epRequested)
        json.put("resolved", report.epResolved)
        json.put("resolution_method", report.resolutionMethod)
        json.put(
            "resolved_meaning",
            "🔴 **판별하지 못했으면 unknown이며 그것은 'CPU였다'와 다른 사실이다.** " +
                "요청값을 이 칸에 베끼지 않는다. 판별 규칙: 프로파일에 NNAPI 노드가 하나라도 " +
                "있으면 nnapi, XNNPACK 노드가 하나라도 있으면 xnnpack, 둘 다 없고 나머지가 " +
                "전부 CPU면 cpu(=통째 폴백), 그 밖은 unknown. " +
                "⚠ **XNNPACK은 CPU EP의 커널을 일부만 대체한다** — 노드가 CPU/XNNPACK로 섞여 " +
                "나오는 것이 정상이고(Conv/Pool 등만 가져간다), '전부 XNNPACK'을 요구하면 " +
                "실제로 돌았는데도 unknown이 된다. 섞인 모양은 아래 node_counts가 말한다"
        )
        val nodes = JSONObject()
        for ((provider, count) in report.providerNodeCounts) nodes.put(provider, count)
        json.put("node_counts", nodes)
        val events = JSONObject()
        for ((provider, count) in report.providerEventCounts) events.put(provider, count)
        json.put("event_counts", events)
        json.put(
            "node_counts_meaning",
            "ORT 프로파일의 `*_kernel_time` 이벤트를 provider 원문 문자열별로 센 것이다" +
                "(event_counts는 provider가 붙은 **모든** 이벤트라 fence까지 포함한다 — " +
                "kernel 집계가 비었을 때만 판정 근거로 쓴다). " +
                "🔴 **노드 수는 작업량 비율이 아니다.** NNAPI가 잡은 서브그래프는 **융합 노드 " +
                "하나**로 나타나므로 'NNAPI 1 : CPU 200'이 그래프의 99%를 NNAPI가 가져간 " +
                "상태일 수도 있다. 이 숫자가 보여 주는 것은 **파티셔닝의 모양**이고, " +
                "전 노드가 CPU면 **통째 폴백 확정**이다"
        )
        json.put("profile_json_probe", report.probeProfilePath ?: JSONObject.NULL)
        json.put("profile_prefix_measured", report.measuredProfilePrefix ?: JSONObject.NULL)
        json.put(
            "profile_prefix_measured_note",
            "⚠ **파일 경로가 아니라 경로 접두사다** — ORT가 타임스탬프를 붙여 파일명을 정한다. " +
                "`_prof` arm이 아니면 null이고, 이번 라운드는 프레임당 추론이 없어 측정 세션 " +
                "프로파일에 담길 것이 없다(세션을 닫을 때 확정 경로를 logcat에 남긴다). " +
                "다음 라운드에는 이 파일이 런 디렉토리로 들어가야 한다"
        )
        // 🔴 **어떤 옵션으로 EP를 붙였는가.** 빈 객체는 "안 잰 칸"이 아니라 "ORT 기본값을
        //    썼다"는 사실이며, 그 구분을 옆의 문장이 말한다.
        val providerOptions = JSONObject()
        for ((key, value) in report.epProviderOptions) providerOptions.put(key, value)
        json.put("provider_options", providerOptions)
        json.put(
            "provider_options_note",
            "EP를 붙일 때 넘긴 provider option 원문이다. **빈 객체 = 아무 옵션도 넘기지 않았다" +
                "(ORT 기본값)**이며 '기록하지 않았다'가 아니다"
        )
        json.put("thread_options", report.threadOptionsNote)
        json.put("nnapi_guard", report.nnapiGuard)
        json.put(
            "nnapi_deprecation_note",
            "NNAPI는 Android 15(API 35)에서 deprecated로 공지됐고 측정 기기는 Android 16이다. " +
                "벤더 드라이버가 살아 있는지 CPU 참조 구현으로 떨어지는지는 **이 기기에서 " +
                "실측해야 안다** — 위 resolved와 node_counts가 그 실측이며, 문헌으로 단정하지 " +
                "않는다"
        )
        val available = JSONArray()
        for (p in report.availableProviders) available.put(p)
        json.put("available_providers", available)
        json.put(
            "available_providers_note",
            "⚠ **`OrtEnvironment.getAvailableProviders()`는 빌드에 컴파일된 EP 목록이고 " +
                "'실제로 쓰였나'에 대해 아무것도 말하지 않는다.** 그래도 싣는 이유는 " +
                "'무엇이 요청 가능했나'의 유일한 근거이기 때문이다. " +
                "⚠ 목록에 QNN이 보여도 이 기기에서는 불가능하다 — A34는 MediaTek이다"
        )
        json.put(
            "no_session_api_note",
            "⚠ **`OrtSession`에는 '이 세션이 어느 EP를 썼는가'를 돌려주는 API가 없다.** " +
                "(1.28.0의 OrtSession 공개 메서드를 확인했다: getInputInfo/getOutputInfo/" +
                "getMetadata/run/endProfiling/getProfilingStartTimeInNs/close뿐이다.) " +
                "그래서 판별을 프로파일러와 로그로 돌아서 한다 — 없는 API를 나중에 다시 찾지 " +
                "말 것"
        )
        val log = JSONArray()
        for (line in report.verboseLogLines) log.put(line)
        json.put("verbose_log_lines", log)
        json.put("verbose_log_note", report.verboseLogNote)
        json.put(
            "verbose_log_meaning",
            "2순위 근거다. ORT 세션 생성 시 노드 배치 요약이 logcat에 찍히며 추론 오버헤드가 " +
                "없다. 🔴 **문자열이라 버전마다 바뀌므로 파싱해서 판정하지 않는다** — " +
                "원문을 그대로 보존해 사람이 위 resolved와 눈으로 대조하게 한다"
        )
        return json
    }

    /**
     * 🔴 **그래프를 읽어 자기를 설정했다는 기록.** 해상도·클래스 수를 코드에 하드코딩하지
     * 않았다는 사실이 이 블록으로 검증 가능해야 한다(`INTERFACES.md` 공통원칙 1).
     */
    private fun buildDetectGraph(report: DetectReport): JSONObject {
        val json = JSONObject()
        val input = JSONObject()
            .put("name", report.inputName ?: JSONObject.NULL)
            .put("shape", JSONArray(report.inputShape))
            .put("dtype", report.inputType ?: JSONObject.NULL)
            .put("declared_name", DetectContract.declaredInputName)
            .put("declared_shape", DetectContract.declaredInputShape)
        val output = JSONObject()
            .put("name", report.outputName ?: JSONObject.NULL)
            .put("shape", JSONArray(report.outputShape))
            .put("dtype", report.outputType ?: JSONObject.NULL)
            .put("declared_name", DetectContract.declaredOutputName)
            .put("declared_shape", DetectContract.declaredOutputShape)
        json.put("input", input)
        json.put("output", output)
        json.put(
            "source",
            "session.inputInfo / outputInfo의 TensorInfo.shape에서 읽었다. " +
                "앱 코드에 640이나 8400 같은 숫자가 없다 — 선언값(declared_*)은 커밋된 " +
                "${DetectContract.declaredSource}에서 빌드 시점에 읽어 BuildConfig에 박은 " +
                "**대조 기준**이다"
        )
        json.put(
            "validation",
            "이름·shape·dtype을 선언과 대조하고, 동적 축(≤0)이 있으면 죽는다" +
                "(INTERFACES.md §A-1: 동적 shape이면 EP가 서브그래프를 못 잡고 통째로 CPU로 " +
                "떨어진다). 출력 채널이 **4 + 클래스 수**인지도 대조한다 — 그건 하드코딩이 " +
                "아니라 두 실측(그래프 shape ↔ 임베드 names)의 정합성 검사다. " +
                "어긋나면 런을 시작하지 않으므로 이 블록이 존재한다는 것은 전부 통과했다는 뜻이다"
        )
        return json
    }

    /**
     * 클래스. 🔴 **1순위 출처는 모델 임베드 메타데이터다**(가중치와 어긋날 수 없다).
     * `metadata.json`의 `classes`는 대조 대상이고, `INTERFACES.md` A-4와의 충돌은 기록만 한다.
     */
    private fun buildDetectClasses(report: DetectReport): JSONObject {
        val json = JSONObject()
        val names = JSONArray()
        for (n in report.classNames) names.put(n)
        json.put("names", names)
        json.put("count", report.classNames.size)
        json.put(
            "source",
            "모델 임베드 메타데이터(session.metadata.customMetadata['names'])가 **1순위 " +
                "출처**다 — 가중치와 함께 export되므로 어긋날 수 없다. " +
                "${DetectContract.declaredSource}의 classes와 대조해 다르면 런을 시작하지 않는다"
        )
        json.put("raw", report.classNamesRaw ?: JSONObject.NULL)
        json.put(
            "raw_note",
            "⚠ 값이 **파이썬 dict 문자열**(작은따옴표)이라 JSON 파서가 못 먹는다. 정규식 " +
                "관용 파서로 읽되 **파싱 실패를 조용히 넘기지 않는다**(실패하면 런을 시작하지 " +
                "않는다). 원문을 그대로 싣는 이유는 파싱 결과만 남기면 나중에 되물을 수 없기 " +
                "때문이다"
        )
        // 🔴 계약 충돌은 **고치지 않고 기록한다.**
        json.put("contract_conflict", report.contractConflict ?: JSONObject.NULL)
        json.put(
            "contract_conflict_policy",
            "충돌이 있어도 **고치지 않는다.** INTERFACES.md는 팀 합의 기록이라 런타임이 임의로 " +
                "못 바꾸고, 모델은 가중치의 사실이라 코드로 뒤집으면 그게 곧 무음 버그다. " +
                "런타임은 위 names(모델에서 읽은 것)를 1순위로 쓰고, 충돌 사실을 여기 기계로 " +
                "남긴다. null이면 충돌이 없다는 뜻이다"
        )
        return json
    }

    /** 🔴 "어느 ORT로 잰 숫자인가"에 답할 유일한 수단. 값의 출처는 build.gradle.kts 한 곳이다. */
    private fun buildDetectRuntime(report: DetectReport): JSONObject = JSONObject()
        .put("package", report.ortPackage)
        .put("version", report.ortVersion)
        .put("abi_filters", "arm64-v8a")
        .put(
            "note",
            "🔴 **full 패키지다(onnxruntime-mobile이 아니다).** mobile 쪽은 .ort 포맷·축소 " +
                "연산자셋이라 이 .onnx(opset 12, FP32)를 못 열 수 있다. 좌표는 " +
                "build.gradle.kts에서 BuildConfig로 박힌 값이며 실제 링크된 의존성과 같은 " +
                "곳에서 나온다(사본이 아니다)"
        )

    /**
     * 🔴 **기계로 읽을 수 없어 가정으로 남은 것.** `RenderArm`의 `*_PROVENANCE`/`*_DEVIATION`
     * 관행 그대로다 — 조용히 굳으면 나중에 박스가 어긋날 때 원인을 못 찾는다.
     */
    private fun buildDetectPreprocess(): JSONObject = JSONObject()
        .put("letterbox_align", DetectContract.LETTERBOX_ALIGN_ASSUMPTION)
        .put("letterbox_align_value", DetectContract.LETTERBOX_ALIGN)
        .put("pad_value", DetectContract.PAD_VALUE_ASSUMPTION)
        .put("pad_value_u8", DetectContract.PAD_VALUE_U8)
        .put("resize_interpolation", DetectContract.RESIZE_INTERPOLATION_ASSUMPTION)
        .put("yuv_to_rgb", DetectContract.YUV_TO_RGB_ASSUMPTION)
        .put("rotation", DetectContract.ROTATION_NOT_APPLIED)
        .put(
            "normalization",
            "0..255 → 0..1 (/255) 하나뿐이다. **평균/표준편차 정규화 없음** — " +
                "metadata.json preprocess.mean_std_normalization = null이고 " +
                "INTERFACES.md §A-2의 mean/std 칸도 '없음(단순 /255)'이다"
        )
        .put("layout", "NCHW float32 [1,C,H,W] — C·H·W는 그래프에서 읽은 값이다")
        .put(
            "used_this_round",
            true
        )
        .put(
            "used_this_round_note",
            "🔴 **이번 라운드는 위 가정 넷을 실제로 썼다**(letterbox 정렬 center · 패딩 114 · " +
                "이중선형 보간 · BT.601 full range YUV→RGB). 넷 다 기계로 확인된 계약값이 " +
                "아니다 — 상류 사이드카가 한국어 산문이라 기계 판독이 안 되고, INTERFACES.md " +
                "§A-2의 해당 칸들도 여전히 ☐다. 확정을 요청해 둔 상태이며, 답이 다르면 " +
                "**E·F 비용은 그대로이고 G의 좌표와 점수만 달라진다**"
        )

    /**
     * letterbox 패딩이 입력 텐서에서 차지하는 픽셀 비율. 🔴 **상수를 복사하지 않고 계산한다.**
     * 협상된 해상도가 없거나 그래프 shape을 못 읽었으면 **값을 지어내지 않고 null**이다.
     */
    private fun detectPaddingFraction(facts: SessionFacts, report: DetectReport): Any {
        // 🔴 **분석 use case의 해상도**로 계산한다. Preview 값을 쓰면 두 use case가 다른
        //    해상도를 받은 런에서 조용히 틀린다 — 실제로 letterbox를 한 것은 분석 프레임이다.
        val analysis = facts.analysis ?: return JSONObject.NULL
        val shape = report.inputShape
        if (shape.size != 4) return JSONObject.NULL
        val fraction = DetectContract.paddingPixelFraction(
            analysis.width,
            analysis.height,
            shape[3].toInt(),
            shape[2].toInt(),
        ) ?: return JSONObject.NULL
        return fraction
    }

    private fun detectPaddingNote(facts: SessionFacts, report: DetectReport): String {
        val analysis = facts.analysis
        val shape = report.inputShape
        if (analysis == null || shape.size != 4) {
            return "분석 프레임 해상도나 입력 shape을 알 수 없어 계산하지 않았다 — " +
                "값을 지어내지 않는다(분석 use case가 붙지 않았거나 프레임이 하나도 오지 않았다)"
        }
        return "1 − (내용 픽셀 수 / 입력 픽셀 수). 소스 " +
            "${analysis.width}x${analysis.height}(**camera_analysis_actual** — Preview가 " +
            "아니라 분석 use case의 값이다) → 입력 ${shape[3]}x${shape[2]}(그래프에서 읽은 " +
            "값)로 **계산한 값이며 상수 복사가 아니다.** 🔴 **F의 일부는 회색 패딩을 미는 " +
            "비용**이라 이 값 없이 다른 입력 크기의 F와 비교하면 안 된다. " +
            "⚠ 패딩을 어느 쪽에 붙이는지는 이 값에 영향이 없다(면적은 같다) — " +
            "그 미확정은 preprocess_assumptions.letterbox_align에 있다"
    }

    /**
     * ③ 분석 use case의 입력 조건. 🔴 **포맷 선택이 E의 뜻을 바꾼다** — 그 문장을 값 옆에
     * 붙여 둔다. `camera_analysis_actual`과 `detect.input` 두 자리에 같은 블록이 나간다
     * (전자는 다른 카메라 조건들과 나란히 읽히고, 후자는 detect 블록만 보는 사람을 위한 것이다).
     */
    private fun buildAnalysisInput(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        val analysis = facts.analysis
        if (analysis == null) {
            json.put("resolution", JSONObject.NULL)
            json.put("input_format", JSONObject.NULL)
            json.put(
                "note",
                "분석 use case가 붙지 않았거나 프레임이 하나도 도착하지 않았다 — " +
                    "값을 지어내지 않는다"
            )
            return json
        }
        json.put("resolution", "${analysis.width}x${analysis.height}")
        json.put("input_format", analysis.imageFormatName)
        json.put("input_format_raw", analysis.imageFormat)
        json.put("input_format_requested", analysis.requestedFormatName)
        json.put("backpressure_strategy", analysis.backpressureStrategy)
        json.put("rotation_degrees", analysis.rotationDegrees)
        json.put(
            "input_format_meaning",
            "🔴 **이 선택이 E의 뜻을 바꾼다.** YUV_420_888을 받아 letterbox·RGB 변환·/255· " +
                "NCHW 배치를 **앱이 직접** 하므로 그 비용이 전부 stage_e_ms 안에 있다. " +
                "RGBA_8888을 요청했다면 CameraX가 색 변환을 대신 해 주고 **그 비용이 E 밖에 " +
                "숨어 E가 과소로 나온다** — 같은 파이프라인의 E라도 두 선택은 비교 대상이 " +
                "아니다. ⚠ 이 값은 요청이 아니라 **실제로 받은 ImageProxy의 포맷**이다"
        )
        json.put(
            "resolution_meaning",
            "⚠ **Preview(camera_actual)와 다른 use case의 값이다.** CameraX가 둘에 서로 " +
                "다른 해상도를 줄 수 있으므로 섞어 읽지 말 것 — padding_pixel_fraction은 " +
                "이 값으로 계산한다"
        )
        return json
    }

    /**
     * ③ 런 회계. 🔴 **불변식이 여기서 닫혀야 한다.** 앱은 맞춰 주지 않고 어긋난 채로 싣는다 —
     * 맞춰 버리면 프레임이 조용히 사라지는 경로를 영영 못 찾는다.
     */
    private fun buildDetectRun(run: DetectRunFacts): JSONObject {
        val json = JSONObject()
        json.put("detect_csv_rows", run.csvRows)
        json.put("analysis_frames_received", run.analysisFramesReceived)
        json.put("inferences_run", run.inferencesRun)
        json.put("skipped_while_busy", run.skippedWhileBusy)
        json.put("errors", run.errors)
        json.put("last_error", run.lastError ?: JSONObject.NULL)
        json.put("inference_enabled", run.inferenceEnabled)
        if (run.inferenceEnabled) {
            val holds = run.analysisFramesReceived ==
                run.inferencesRun + run.skippedWhileBusy + run.errors
            json.put("accounting_holds", holds)
            json.put(
                "accounting_invariant",
                "analysis_frames_received == inferences_run + skipped_while_busy + errors. " +
                    "🔴 **앱이 맞춰 주지 않는다** — 어긋나면 어긋난 채로 싣는다. false면 " +
                    "분석 프레임 하나가 어느 칸에도 계상되지 않은 것이고, 그건 코드 결함이다"
            )
        } else {
            // 🔴 분모 arm에는 추론 경로가 없다 → 불변식을 적용하지 않는다. null로 두는 이유:
            //    false로 두면 "회계가 깨졌다"로 읽히고 true로 두면 검사하지 않은 것을
            //    통과했다고 말하는 셈이다.
            json.put("accounting_holds", JSONObject.NULL)
            json.put(
                "accounting_invariant",
                "이 arm은 추론을 돌리지 않으므로(detect_bind_only) 불변식을 적용하지 않는다. " +
                    "analysis_frames_received만 뜻이 있다 — **분석 use case가 실제로 프레임을 " +
                    "받고 있었다는 증거**이고, 그래야 이 arm이 분모로서 참이 된다"
            )
        }
        json.put("rows_match_inferences", run.csvRows.toLong() == run.inferencesRun)
        json.put(
            "rows_match_inferences_note",
            "detect.csv의 행 수와 inferences_run이 같아야 한다. 다르면 정지 순서(A12)에서 " +
                "행이 찢겼거나 기록 창 밖의 추론이 섞인 것이다"
        )
        json.put(
            "box_counts_meaning",
            "🔴 **boxes_pre_nms / boxes_out / max_conf를 탐지 정확도로 읽지 말 것.** " +
                "이 라운드가 재는 것은 E·F·G이고, 그 열들의 용도는 (a) G가 실제로 일을 " +
                "했는가와 (b) G 비용의 설명 변수까지다. 정확도로 못 읽는 이유가 둘 있다: " +
                "회전을 적용하지 않아 모델이 옆으로 누운 장면을 본다" +
                "(preprocess_assumptions.rotation), 그리고 전처리 가정 넷이 아직 미확정이다" +
                "(preprocess_assumptions.used_this_round_note). 정확도 판정은 골든 샘플" +
                "(INTERFACES.md §A-6)이 와야 가능하다"
        )
        json.put("quiesced", run.quiesced)
        json.put("quiesce_timeout_ms", run.quiesceTimeoutMs)
        json.put(
            "quiesce_note",
            "A12 정지 순서: (1) 기록 플래그 off → (2) **탐지 스레드 quiesce** → " +
                "(3) frames.csv + detect.csv → (4) session.json. quiesced=false면 " +
                "진행 중이던 추론을 시간 안에 못 기다린 것이고 마지막 행이 없을 수 있다"
        )
        val box = run.letterbox
        if (box == null) {
            json.put("letterbox", JSONObject.NULL)
        } else {
            json.put(
                "letterbox",
                JSONObject()
                    .put("src", "${box.srcW}x${box.srcH}")
                    .put("dst", "${box.dstW}x${box.dstH}")
                    .put("scale", box.scale.toDouble())
                    .put("content", "${box.contentW}x${box.contentH}")
                    .put("pad_left", box.padX)
                    .put("pad_top", box.padY)
                    .put(
                        "note",
                        "이 런에서 **실제로 쓴** 값이다(마지막 프레임 기준. 해상도가 고정이라 " +
                            "전 프레임 같다). 전처리와 후처리가 **같은 객체**를 쓰므로 " +
                            "역변환이 반올림에서 어긋나지 않는다"
                    )
            )
        }
        val profiles = JSONArray()
        for (name in run.profileFiles) profiles.put(name)
        json.put("profile_files_in_run_dir", profiles)
        json.put(
            "profile_files_note",
            "ORT 프로파일 JSON(Chrome trace)을 **런 디렉토리로 옮겼다.** 예전에는 앱 내부 " +
                "캐시(/data/user/0/...)에 떨어져 루트 없이 못 꺼냈다 — `_prof` arm의 유일한 " +
                "산출물인데 회수가 안 되면 그 arm이 아무 답도 못 낸다. " +
                "⚠ measured 프로파일을 확정하면(endProfiling) 그 세션은 다시 프로파일할 수 " +
                "없으므로 앱이 세션을 닫는다 — `_prof` arm으로 연속 측정하려면 arm을 다시 " +
                "고를 것"
        )
        return json
    }

    /**
     * ③ 계측의 타임스탬프 자리. 🔴 **E·F·G는 `t`를 찍는 위치가 정의다.**
     * 이 블록이 없으면 그 숫자가 무엇의 비용인지 나중에 되물을 수 없다.
     */
    private fun buildDetectTimestampSites(): JSONObject = JSONObject()
        .put(
            "t_detect_recv_ns",
            "분석 콜백 진입 후 **idle 게이트를 통과한 직후**(전용 detect-analysis 스레드). " +
                "SystemClock.elapsedRealtimeNanos() — frames.csv의 t_recv_ns와 **같은 시계**다. " +
                "⚠ 건너뛴 프레임에는 이 시각이 없다(행 자체가 없다)"
        )
        .put(
            "t_detect_end_ns",
            "후처리(G)가 끝난 직후, 행을 기록하기 전. t_detect_recv_ns와 같은 시계"
        )
        .put(
            "t_image_capture_ns",
            "ImageProxy.imageInfo.timestamp 원본. 앱이 보정하지 않는다. " +
                "⚠ 기준 시계가 기기마다 다르므로(frames.csv의 t_capture_ns와 같은 부류) " +
                "우리 시계와 빼지 않는다"
        )
        .put(
            "stage_e_ms",
            "🔴 **ImageProxy의 평면 버퍼에서 바이트를 꺼내는 것부터 입력 텐서가 준비될 " +
                "때까지.** 안에 있는 것: Y/U/V 평면 3개 bulk copy, letterbox 리샘플(휘도 " +
                "이중선형·색차 최근접), YUV→RGB, /255, NCHW 배치, 직접 버퍼로의 bulk put, " +
                "OnnxTensor.createTensor. 밖에 있는 것: 프레임 대기 해제, 콜백 디스패치, " +
                "ImageProxy.close(), session.run(). 시계는 System.nanoTime()(구간 길이 전용)"
        )
        .put(
            "stage_f_ms",
            "🔴 **session.run() 호출 하나뿐이다.** 출력 텐서를 읽는 비용은 여기가 아니라 G에 " +
                "있다. 시계는 System.nanoTime()"
        )
        .put(
            "stage_g_ms",
            "🔴 **출력 텐서를 네이티브에서 읽어 오는 복사부터** conf 필터 → cxcywh→xyxy → " +
                "letterbox 역변환 → 클래스별 NMS까지. 시계는 System.nanoTime()"
        )
        .put(
            "thread_split",
            "E는 **detect-analysis 스레드**에서, F·G는 **detect-infer 스레드**에서 잰다. " +
                "🔴 둘을 가른 이유: 하나였다면 CameraX가 콜백 반환까지 다음 프레임을 주지 " +
                "않아 **skipped_while_busy가 영원히 0**이 된다(관측하려던 것이 관측 방법 " +
                "때문에 사라진다). E가 끝나면 ImageProxy를 닫고 분석 스레드가 즉시 다음 " +
                "프레임을 받으므로 건너뛴 수가 실제로 세어진다. " +
                "⚠ 그래서 **E와 F 사이에는 스레드 핸드오프가 하나 있다** — 그 비용은 E에도 " +
                "F에도 없고, 하네스의 detect_wall_ms(= end − recv) 안에서 미계상분으로 " +
                "나타난다"
        )
        .put(
            "clock_note",
            "🔴 **E·F·G는 CPU 벽시계 구간 길이이고 GPU 시계가 아니다.** stage_b_ms·D 계열· " +
                "stage_i_ms·gpu_present_ms와 물리량이 다르므로 gpu_sum_ms에도 " +
                "stage_d_total_ms에도 들어가지 않는다"
        )

    /**
     * 🔴 **인용 금지.** 준비 1회의 벽시계이고 표본이 1개이며, 첫 추론은 지연 초기화를
     * 포함하고 입력도 실제 프레임이 아니다(0으로 채운 더미). E·F·G는 다음 라운드에
     * `detect.csv`로 나온다 — 이 숫자를 F로 옮겨 적지 말 것.
     */
    private fun buildDetectPrepareTiming(report: DetectReport): JSONObject {
        val json = JSONObject()
        json.put("env_init_ms", report.envInitMs)
        json.put("sha256_ms", report.sha256Ms)
        json.put("probe_session_create_ms", report.probeCreateMs)
        json.put("probe_dummy_infer_ms", report.probeInferMs)
        json.put("measured_session_create_ms", report.measuredCreateMs)
        // 🔴 **A8.** 1회차는 그래프 초기화·lazy alloc으로 크게 튄다 → 분포 밖에 따로 낸다.
        json.put("first_inference_ms", report.warmupInferMs)
        val warmup = JSONArray()
        for (ms in report.warmupInferMsAll) warmup.put(ms)
        json.put("warmup_inference_ms", warmup)
        json.put("warmup_inferences", report.warmupInferMsAll.size)
        json.put(
            "warmup_note",
            "🔴 **A8 — 기록 전 warmup.** 측정 세션에서 ${report.warmupInferMsAll.size}회를 " +
                "미리 돌린 뒤에야 detect.csv 기록이 시작된다. 안 하면 1회차의 초기화 비용이 " +
                "F 분포에 들어가 **p99가 통째로 오염된다.** first_inference_ms가 그 1회차이고 " +
                "**분포 밖의 값**이다 — F로 인용하지 말 것. " +
                "⚠ warmup 입력은 실제 프레임이 아니라 **0으로 채운 더미**다(그래프 초기화가 " +
                "목적이므로 내용은 무관하다). warmup_inference_ms 배열을 보면 몇 회차부터 " +
                "평평해지는지 되물을 수 있다"
        )
        json.put(
            "note",
            "🔴 **인용 금지 — F가 아니다.** 준비 1회의 벽시계이고 표본이 1개다. 첫 추론은 " +
                "EP 커널 준비·메모리 아레나 같은 지연 초기화를 포함하고, 입력도 실제 프레임이 " +
                "아니라 **0으로 채운 더미**다. 여기서 확인한 것은 '세션이 실제로 도는가'와 " +
                "'프로파일러가 노드 이벤트를 내는가' 둘뿐이다. E·F·G의 분포는 **detect.csv**에 " +
                "있다"
        )
        return json
    }

    /**
     * ② 조합 arm(`drago_clahe_chain`)의 서술.
     *
     * 🔴 **[putLabCommon]을 부르지 않는다.** 그 함수는 `glare_note`로
     * [RenderArm.LAB_GLARE_NOTE]("이 arm은 눈부심을 누르지 못한다")를 싣는데, 이 조합은 D1을
     * 포함해 글레어를 누르므로 **거짓 문장이 로그로 나간다.** 대신
     * [RenderArm.CHAIN_GLARE_NOTE]가 이 arm의 진짜 위험(표시 경로 전용 후보)을 담는다.
     * 나머지 공통 문장은 같은 상수를 그대로 재사용한다 — 사본을 만들면 갈라진다.
     */
    private fun putChain(json: JSONObject, facts: SessionFacts) {
        json.put("algorithm", "drago_clahe_chain")
        json.put(
            "upstream_reference",
            "scripts/lowlight.py의 D1 → A1 **직렬**(상류 조합 D1A1). 상류 잠정 1위는 " +
                "D1A1+bf(+ts)이고 이 arm은 D1A1까지다 — **이 arm에는 bf가 없다.** bf는 별 " +
                "arm(drago_clahe_chain_bf)으로 따로 재 두었으니 그쪽 값과 나란히 볼 것. " +
                "⚠ `ts`는 **어느 arm에도 없다**(INTERFACES.md §B-4가 ☐라 임의로 넣지 않았다) — " +
                "그러므로 상류 잠정 1위 후보 **전체**는 아직 재지 못했다"
        )
        json.put("composition", "chain (중간 표현을 RGBA8 FBO로 materialize한다)")
        json.put(
            "composition_note",
            "**체인이지 융합이 아니다.** 상류 cv2 파이프라인이 Drago 출력을 8비트 이미지로 " +
                "내고 그것을 cvtColor(BGR2LAB)에 넣는 구조를 그대로 옮겼다. 중간 " +
                "materialize를 없애는 융합(drago_clahe_fused)은 **알고리즘 변경이라 채택 " +
                "여부가 팀장 판단 영역**이며, arm 자체는 이미 있고 실측도 끝났다 — " +
                "그 arm의 값과 이 arm의 값을 나란히 놓는 방법은 how_to_compare에 있다"
        )
        json.put("stage_order", JSONArray().put("drago_tonemap").put("clahe_gamma"))

        // 두 스테이지의 파라미터 전부. **단품 arm과 같은 상수**를 쓴다 — 조합용으로 따로
        // 잡으면 값이 갈라지는 순간 "단품과 같은 설정으로 이었다"는 전제가 조용히 깨진다.
        val stages = JSONArray()
        stages.put(
            JSONObject()
                .put("index", 1)
                .put("algorithm", "drago_tonemap")
                .put("upstream_reference", "scripts/lowlight.py D1 (OpenCV TonemapDrago)")
                .put("gamma", RenderArm.DRAGO_GAMMA.toDouble())
                .put("saturation", RenderArm.DRAGO_SATURATION.toDouble())
                .put("bias", RenderArm.DRAGO_BIAS.toDouble())
                .put("src_gamma", RenderArm.DRAGO_SRC_GAMMA.toDouble())
                .put("luma_weights", RenderArm.DRAGO_LUMA_WEIGHTS)
                .put(
                    "uniforms",
                    JSONArray()
                        .put(RenderArm.DRAGO_SRC_GAMMA_UNIFORM)
                        .put(RenderArm.DRAGO_OUT_GAMMA_UNIFORM)
                        .put(RenderArm.DRAGO_SATURATION_UNIFORM)
                        .put(RenderArm.DRAGO_BIAS_UNIFORM)
                )
                .put(
                    "operates_on",
                    "선형 RGB 3채널 전부. sRGB를 pow(x, src_gamma)로 선형화한 뒤 오퍼레이터에 " +
                        "넣고 pow(x, 1/gamma)로 되씌운다. **LabGlsl을 쓰지 않는다**"
                )
                .put("ssbo_binding", "stats=${DragoClaheChainStage.DRAGO_STATS_BINDING}")
        )
        stages.put(
            JSONObject()
                .put("index", 2)
                .put("algorithm", "clahe_gamma")
                .put(
                    "upstream_reference",
                    "scripts/lowlight.py A1 (OpenCV createCLAHE + 감마)"
                )
                .put("clip_limit", RenderArm.CLAHE_CLIP_LIMIT.toDouble())
                .put("tile_grid", RenderArm.CLAHE_TILE_GRID)
                .put("gamma", RenderArm.CLAHE_GAMMA_VALUE.toDouble())
                .put("histogram_bins", LabGlsl.BIN_COUNT)
                .put(
                    "uniforms",
                    JSONArray()
                        .put(RenderArm.CLAHE_CLIP_LIMIT_UNIFORM)
                        .put(RenderArm.CLAHE_TILES_UNIFORM)
                        .put(RenderArm.CLAHE_GAMMA_UNIFORM)
                )
                .put(
                    "operates_on",
                    "CIE LAB의 L* 채널만 (a,b는 그대로 둔다). 입력은 **drago가 적용된 " +
                        "RGBA8 중간 이미지**이지 카메라 원본이 아니다"
                )
                .put(
                    "ssbo_binding",
                    "hist=${DragoClaheChainStage.CLAHE_HIST_BINDING}, " +
                        "lut=${DragoClaheChainStage.CLAHE_LUT_BINDING}"
                )
        )
        json.put("stages", stages)
        json.put(
            "ssbo_binding_note",
            "단품 arm은 drago stats=0 / clahe hist=0 / clahe lut=1이라 **조합에서는 첫 둘이 " +
                "충돌한다.** 그래서 조합만 clahe를 1·2로 밀었다. 셰이더 텍스트를 복사하지 " +
                "않고 binding을 인자로 받는 소스 함수를 공유하므로 산식은 여전히 한 곳에만 " +
                "있고, 단품 arm의 셰이더 생성 문자열은 바이트 단위로 그대로다"
        )

        // 지어낸 계약값을 조용히 굳히지 않기 위한 문장이다. 지우지 말 것.
        json.put("provenance", RenderArm.CHAIN_PROVENANCE)
        // 조합 고유 이탈. 단품 두 arm의 이탈은 **그대로 성립하므로** 함께 싣는다.
        json.put("upstream_deviation", RenderArm.CHAIN_DEVIATION)
        json.put("upstream_deviation_drago", RenderArm.DRAGO_DEVIATION)
        json.put("upstream_deviation_lab", RenderArm.LAB_DEVIATION)
        // 🔴 LAB_GLARE_NOTE가 아니다. 이 arm에서 그 문장은 거짓이다.
        json.put("glare_note", RenderArm.CHAIN_GLARE_NOTE)
        json.put("desaturation_note", RenderArm.LAB_DESATURATION_NOTE)
        json.put("flicker_note", RenderArm.CHAIN_FLICKER_NOTE)
        json.put("how_to_compare", RenderArm.CHAIN_HOW_TO_COMPARE)
        json.put("cost_split_note", RenderArm.COST_SPLIT_NOTE)
        json.put("levers_not_pulled", RenderArm.CHAIN_LEVERS_NOT_PULLED)
        putColorTransform(json, facts, RenderArm.DRAGO_CLAHE_CHAIN)
        json.put("gpu_status", facts.stage2Status)
        json.put(
            "note",
            "② 자리가 **6패스**(3단 × 2벌)이고 전체 8패스다. GpuTimerRing.MAX_PASS_COUNT가 " +
                "8이던 시절에는 이 arm이 슬롯을 정확히 다 써서 여유가 0이었고 그래서 bf를 " +
                "얹을 수 없었다 — 그 상수를 12로 올렸으므로 지금은 여유가 있다(이 arm의 패스 " +
                "수와 열 구성은 그대로다). 하위 패스를 합치지 않고 D 계열 슬롯 6개에 그대로 " +
                "낸다(docs/FRAME_LOG_SCHEMA.md §2). 서수 2 열은 **두 번째 스테이지의 같은 역할 " +
                "슬롯**이며 알고리즘 이름이 아니다 — 이 arm에서 그것이 무엇이었는지는 " +
                "render.passes[]가 선언한다"
        )
    }

    /**
     * ② 융합 arm(`drago_clahe_fused`)의 서술.
     *
     * 🔴 [putChain]과 마찬가지로 [putLabCommon]을 부르지 않는다(같은 이유 — `glare_note`가
     * 거짓이 된다). 그리고 **이 arm은 상류에 없는 구성**이므로 `upstream_deviation`에
     * 신규 이탈 3건이 더 붙고, `how_to_compare`가 "상류 옆에 놓지 말라"로 바뀐다.
     */
    private fun putFused(json: JSONObject, facts: SessionFacts) {
        json.put("algorithm", "drago_clahe_fused")
        json.put(
            "upstream_reference",
            "scripts/lowlight.py의 D1 · A1 파라미터를 쓰지만 **구성은 상류에 없다** — " +
                "상류가 기록한 것은 순서(Drago → CLAHE → bilateral)뿐이고 중간 표현을 " +
                "어떻게 다뤘는지는 없다. 상류 구조를 그대로 옮긴 것은 " +
                "`drago_clahe_chain`이고 이 arm은 그 변형이다"
        )
        json.put("composition", "fused (중간 표현 없음 — 톤맵을 CLAHE 두 패스에 인라인)")
        json.put(
            "composition_note",
            "🔴 **이식 최적화가 아니라 알고리즘 변경이다.** 없앤 것: 패스 하나 · FBO 왕복 " +
                "하나 · pow 인코드/디코드 왕복. 대신 치른 것: **Drago 톤맵을 픽셀당 2회 " +
                "평가**(융합 analyze + 융합 apply). 어느 쪽이 이기는지는 **측정 대상**이며 " +
                "여기에 예상치를 적지 않는다"
        )
        json.put("stage_order", JSONArray().put("drago_tonemap").put("clahe_gamma"))

        val stages = JSONArray()
        stages.put(
            JSONObject()
                .put("index", 1)
                .put("algorithm", "drago_tonemap")
                .put("upstream_reference", "scripts/lowlight.py D1 (OpenCV TonemapDrago)")
                // ⚠ 값은 체인·단품과 **같은 상수**다. 갈라지면 두 arm의 차이가 "융합했기
                //   때문"인지 "설정이 달라서"인지 구분할 수 없게 된다.
                .put("saturation", RenderArm.DRAGO_SATURATION.toDouble())
                .put("bias", RenderArm.DRAGO_BIAS.toDouble())
                .put("src_gamma", RenderArm.DRAGO_SRC_GAMMA.toDouble())
                .put("gamma", RenderArm.DRAGO_GAMMA.toDouble())
                // 🔴 지우지 말 것. 값만 보고 "체인과 같은 설정"이라 읽으면 틀린다.
                .put(
                    "gamma_applied",
                    false
                )
                .put(
                    "gamma_note",
                    "🔴 **이 arm에서 gamma(uOutGamma)는 적용되지 않는다.** 체인에서 그 값은 " +
                        "중간 이미지를 pow(x, 1/gamma)로 인코드하는 데만 쓰였고, 융합은 그 " +
                        "중간을 없앴다. 값을 지우지 않고 남기는 이유는 두 arm의 설정을 " +
                        "나중에 대조할 수 있어야 하기 때문이다(upstream_deviation (b))"
                )
                .put("luma_weights", RenderArm.DRAGO_LUMA_WEIGHTS)
                .put(
                    "uniforms",
                    JSONArray()
                        .put(RenderArm.DRAGO_SRC_GAMMA_UNIFORM)
                        .put(RenderArm.DRAGO_SATURATION_UNIFORM)
                        .put(RenderArm.DRAGO_BIAS_UNIFORM)
                )
                .put(
                    "operates_on",
                    "선형 RGB 3채널 전부. **결과를 선형인 채로** CLAHE에 넘긴다 — " +
                        "인코드/디코드 왕복이 없다"
                )
                .put("ssbo_binding", "stats=${DragoClaheFusedStage.DRAGO_STATS_BINDING}")
        )
        stages.put(
            JSONObject()
                .put("index", 2)
                .put("algorithm", "clahe_gamma")
                .put(
                    "upstream_reference",
                    "scripts/lowlight.py A1 (OpenCV createCLAHE + 감마)"
                )
                .put("clip_limit", RenderArm.CLAHE_CLIP_LIMIT.toDouble())
                .put("tile_grid", RenderArm.CLAHE_TILE_GRID)
                .put("gamma", RenderArm.CLAHE_GAMMA_VALUE.toDouble())
                .put("histogram_bins", LabGlsl.BIN_COUNT)
                .put(
                    "uniforms",
                    JSONArray()
                        .put(RenderArm.CLAHE_CLIP_LIMIT_UNIFORM)
                        .put(RenderArm.CLAHE_TILES_UNIFORM)
                        .put(RenderArm.CLAHE_GAMMA_UNIFORM)
                )
                .put(
                    "operates_on",
                    "CIE LAB의 L* 채널만 (a,b는 그대로 둔다). 입력은 **선형 톤맵 결과**라 " +
                        "LabGlsl의 piecewise srgbToLinear를 타지 않고 " +
                        "${LabGlsl.LINEAR_TO_L} / ${LabGlsl.LINEAR_TO_LAB_F}로 바로 들어간다"
                )
                .put(
                    "ssbo_binding",
                    "hist=${DragoClaheFusedStage.CLAHE_HIST_BINDING}, " +
                        "lut=${DragoClaheFusedStage.CLAHE_LUT_BINDING}"
                )
        )
        json.put("stages", stages)
        json.put(
            "ssbo_binding_note",
            "체인과 같은 배치(0/1/2)지만 **버퍼는 따로 소유한다** — 체인 arm의 자원 수명이 " +
                "이 arm의 동작에 따라 바뀌면 체인 실측의 조건이 흔들린다. 🔴 그리고 이 arm의 " +
                "적용 프래그먼트는 **하나가 두 블록**(DragoStats + ClaheLut)을 선언하므로 " +
                "GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS 필요값이 " +
                "${DragoClaheFusedStage.REQUIRED_FRAGMENT_SSBO_BLOCKS}이다(체인은 프래그먼트마다 " +
                "1개씩이라 ${DragoClaheChainStage.REQUIRED_FRAGMENT_SSBO_BLOCKS}였다). " +
                "실측 프로브해서 모자라면 값을 지어내지 않고 arm을 끈다"
        )

        // 지어낸 계약값을 조용히 굳히지 않기 위한 문장이다. 지우지 말 것.
        json.put("provenance", RenderArm.FUSED_PROVENANCE)
        // 🔴 신규 이탈 3건. 체인·단품의 이탈은 그 위에 **그대로 성립하므로** 함께 싣는다.
        json.put("upstream_deviation", RenderArm.FUSED_DEVIATION)
        json.put("upstream_deviation_chain", RenderArm.CHAIN_DEVIATION)
        json.put("upstream_deviation_drago", RenderArm.DRAGO_DEVIATION)
        json.put("upstream_deviation_lab", RenderArm.LAB_DEVIATION)
        json.put("glare_note", RenderArm.FUSED_GLARE_NOTE)
        json.put("desaturation_note", RenderArm.LAB_DESATURATION_NOTE)
        json.put("flicker_note", RenderArm.FUSED_FLICKER_NOTE)
        // 🔴 상류 CPU 숫자 옆에 놓을 것은 체인이지 융합이 아니다.
        json.put("how_to_compare", RenderArm.FUSED_HOW_TO_COMPARE)
        json.put("cost_split_note", RenderArm.COST_SPLIT_NOTE)
        json.put("levers_not_pulled", RenderArm.FUSED_LEVERS_NOT_PULLED)
        putColorTransform(json, facts, RenderArm.DRAGO_CLAHE_FUSED)
        json.put("gpu_status", facts.stage2Status)
        json.put(
            "note",
            "② 자리가 **5패스**(통계 2벌 + 적용 1벌)이고 전체 7패스다. " +
                "**stage_d_apply2_ms를 쓰지 않는다** — 적용이 하나로 접혔고 재지 않은 열은 " +
                "싣지 않는다(docs/FRAME_LOG_SCHEMA.md §2). 그래서 열 순서가 " +
                "…analyze2, build2, apply, present가 된다(gpuColumns는 **패스 순서 그대로**다). " +
                "⚠ 같은 이름의 열이라도 체인과 담기는 패스가 다르다 — 특히 stage_d_apply_ms는 " +
                "체인에서 drago 적용, 융합에서 융합 적용이다"
        )
    }

    /**
     * 색공간 변환 계수 두 층을 함께 싣는다.
     *
     * - `color_transform_sites` — **이 런의 arm**을 기계가 센 값.
     * - `color_transform_sites_peer` — **다른 조합 arm**의 같은 값. 이 런에서 돌지 않았지만,
     *   두 arm의 차이를 한 파일에서 계산할 수 있어야 `gpu_sum` 차분을 "변환 몇 회를
     *   줄였는가"에 귀속시킬 수 있다.
     * - `color_transform_declared` — 사람이 픽셀당·프레임당으로 환산한 선언값.
     */
    private fun putColorTransform(json: JSONObject, facts: SessionFacts, arm: RenderArm) {
        // 🔴 예전에는 이 자리가 **이항 else**였다. 그대로 두면 새 arm이 조용히 체인을 짝으로
        //    집어 "이 arm과 짝이 아닌 계수"가 peer로 나간다 — 명시 분기다.
        //    짝의 정의: **bf 유무를 맞춘 체인 ↔ 융합**이어야 "융합해서 변환 몇 회를 줄였는가"가
        //    성립한다(bf 유무가 다른 짝을 비교하면 두 가지가 동시에 바뀐다).
        val peer = when (arm) {
            RenderArm.DRAGO_CLAHE_CHAIN -> RenderArm.DRAGO_CLAHE_FUSED
            RenderArm.DRAGO_CLAHE_FUSED -> RenderArm.DRAGO_CLAHE_CHAIN
            RenderArm.DRAGO_CLAHE_CHAIN_BF -> RenderArm.DRAGO_CLAHE_FUSED_BF
            RenderArm.DRAGO_CLAHE_FUSED_BF -> RenderArm.DRAGO_CLAHE_CHAIN_BF
            // 프레임 단일 query arm. 여기 오는 것은 **호출부가 짝 arm을 넘기지 않은 경우**뿐이며
            // (buildStage2Params는 짝을 넘긴다) 그때도 계수는 렌더가 같은 짝의 반대쪽 구성이다 —
            // 계측 방식은 셰이더를 바꾸지 않으므로 색공간 변환 계수도 짝과 같다.
            // 🔴 `else -> null`로 흘리지 않는다. 흘리면 조합 arm인데 peer가 사라진다.
            RenderArm.DRAGO_CLAHE_CHAIN_1Q -> RenderArm.DRAGO_CLAHE_FUSED
            RenderArm.DRAGO_CLAHE_CHAIN_BF_1Q -> RenderArm.DRAGO_CLAHE_FUSED_BF
            // 조합 arm이 아니면 짝이 없다(blit_2pass_1q도 여기다). **아무 arm이나 집지 않는다.**
            RenderArm.BLIT_2PASS_1Q -> null
            else -> null
        }
        json.put("color_transform_sites", buildColorTransformSites(facts, arm))
        if (peer != null) {
            json.put(
                "color_transform_sites_peer",
                buildColorTransformSites(facts, peer).put(
                    "peer_note",
                    "**이 런에서 돌지 않은 arm의 계수다.** 두 조합 arm의 변환 횟수 차이를 한 " +
                        "파일에서 계산할 수 있게 함께 싣는다 — 이 런의 값은 위 " +
                        "color_transform_sites다. 짝은 **bf 유무를 맞춘** 반대쪽 구성이다" +
                        "(체인 ↔ 융합)"
                )
            )
        }
        json.put("color_transform_declared", buildColorTransformDeclared(arm))
    }

    /**
     * 색공간 변환 **자동 계수**. 값의 출처는 `glShaderSource`에 넘긴 문자열 자체이므로
     * 셰이더 텍스트와 어긋날 수 없다([ColorTransformCensus]).
     */
    private fun buildColorTransformSites(facts: SessionFacts, arm: RenderArm): JSONObject {
        val json = JSONObject()
        json.put("arm", arm.id)
        json.put("counted_at", "onSurfaceCreated 1회 (hot path 아님)")
        json.put("method", "glShaderSource에 넘긴 String에서 토큰별 정적 호출 지점 수")
        json.put("tokens", JSONArray().apply { ColorTransformCensus.TOKENS.forEach { put(it) } })
        // 🔴 두 부류를 갈라 싣는다. 진입점 계수는 살아 있는 호출 수지만 내부 헬퍼 계수는
        //    죽은 본문까지 포함한 **상한**이다 — 갈라 두지 않으면 정반대로 읽힌다.
        json.put(
            "entry_point_tokens",
            JSONArray().apply { ColorTransformCensus.ENTRY_POINT_TOKENS.forEach { put(it) } }
        )
        json.put(
            "inner_tokens",
            JSONArray().apply { ColorTransformCensus.INNER_TOKENS.forEach { put(it) } }
        )
        json.put("note", ColorTransformCensus.NOTE)
        val sites = facts.colorTransformSites[arm.id].orEmpty()
        val passes = JSONArray()
        for ((pass, counts) in sites) {
            val entry = JSONObject().put("pass", pass)
            for ((token, count) in counts) {
                entry.put(token, count)
            }
            passes.put(entry)
        }
        json.put("by_pass", passes)
        if (sites.isEmpty()) {
            // 값을 지어내지 않는다. 왜 비었는지만 남긴다.
            json.put(
                "empty_reason",
                "onSurfaceCreated가 돌기 전에 세션이 끝나 셰이더 소스를 세지 못했다"
            )
        }
        return json
    }

    /**
     * 색공간 변환 **선언값**. 자동 계수와 **다른 층**이다 — 사람이 셰이더를 읽고 픽셀당·
     * 프레임당으로 환산한 값이며 측정이 아니다. 어긋나면 자동 계수가 맞다.
     *
     * 체인과 융합의 같은 칸을 나란히 놓으면 이탈 (a)(b)(c)가 그대로 숫자로 보인다.
     */
    private fun buildColorTransformDeclared(arm: RenderArm): JSONObject {
        val counts = declaredCounts(arm)
        val json = JSONObject().put("arm", arm.id)
        for ((key, value) in counts) {
            json.put(key, value)
        }
        if (counts.isEmpty()) {
            json.put(
                "empty_reason",
                "이 arm은 조합(② 스테이지 2벌) arm이 아니라 이 표가 성립하지 않는다 — " +
                    "값을 지어내지 않는다"
            )
            return json
        }
        json.put(
            "bilateral_taps_note",
            "bilateral_taps_per_pixel은 색공간 변환이 아니라 **bf의 텍스처 샘플 수**다" +
                "(원형 이웃 ${RenderArm.BF_TAP_COUNT}탭. 같은 표에 둔 이유는 bf 패스의 픽셀당 " +
                "일을 이 표에서 함께 읽게 하려는 것이다). bf는 LabGlsl을 부르지 않으므로 " +
                "위 색공간 변환 칸에는 **0을 더한다** — 그래서 base arm과 값이 같다"
        )
        json.put("provenance", RenderArm.CHAIN_COLOR_TRANSFORM_DECLARED_PROVENANCE)
        return json
    }

    /**
     * [buildColorTransformDeclared]의 선언값 표.
     *
     * 🔴 예전에는 이 자리가 `val fused = arm == DRAGO_CLAHE_FUSED` 하나였고, 그러면 **새 arm에
     * 체인의 선언값이 그대로 나간다**(else 낙하의 함정. `render.passes[]`의
     * `else -> drago 패스 이름`과 같은 부류다). 그래서 arm마다 명시 분기다.
     * 조합 arm이 아니면 이 표 자체가 성립하지 않으므로 **값을 지어내지 않고** 이유만 낸다.
     *
     * 함수로 뽑은 이유: 프레임 단일 query arm이 **짝의 표를 그대로** 써야 하는데(계측 방식은
     * 셰이더를 바꾸지 않는다) 사본을 만들면 한쪽만 고쳐지는 날 두 arm이 갈라진다.
     */
    private fun declaredCounts(arm: RenderArm): Map<String, Int> {
        return when (arm) {
            RenderArm.DRAGO_CLAHE_CHAIN -> mapOf(
                "passes_total" to RenderArm.CHAIN_PASSES_TOTAL,
                "fullscreen_passes" to RenderArm.CHAIN_FULLSCREEN_PASSES,
                "srgb_to_linear_per_pixel" to RenderArm.CHAIN_SRGB_TO_LINEAR_PER_PIXEL,
                "lab_f_forward_per_pixel" to RenderArm.CHAIN_LAB_F_FORWARD_PER_PIXEL,
                "lab_f_inverse_per_pixel" to RenderArm.CHAIN_LAB_F_INVERSE_PER_PIXEL,
                "linear_to_srgb_per_pixel" to RenderArm.CHAIN_LINEAR_TO_SRGB_PER_PIXEL,
                "drago_tonemap_evals_per_pixel" to
                    RenderArm.CHAIN_DRAGO_TONEMAP_EVALS_PER_PIXEL,
                "drago_pow_linearize_per_pixel" to
                    RenderArm.CHAIN_DRAGO_POW_LINEARIZE_PER_PIXEL,
                "drago_out_gamma_encode_per_pixel" to
                    RenderArm.CHAIN_DRAGO_OUT_GAMMA_ENCODE_PER_PIXEL,
                "intermediate_rgba8_materializations" to
                    RenderArm.CHAIN_INTERMEDIATE_RGBA8_MATERIALIZATIONS,
                "bilateral_taps_per_pixel" to 0,
            )
            RenderArm.DRAGO_CLAHE_FUSED -> mapOf(
                "passes_total" to RenderArm.FUSED_PASSES_TOTAL,
                "fullscreen_passes" to RenderArm.FUSED_FULLSCREEN_PASSES,
                "srgb_to_linear_per_pixel" to RenderArm.FUSED_SRGB_TO_LINEAR_PER_PIXEL,
                "lab_f_forward_per_pixel" to RenderArm.FUSED_LAB_F_FORWARD_PER_PIXEL,
                "lab_f_inverse_per_pixel" to RenderArm.FUSED_LAB_F_INVERSE_PER_PIXEL,
                "linear_to_srgb_per_pixel" to RenderArm.FUSED_LINEAR_TO_SRGB_PER_PIXEL,
                "drago_tonemap_evals_per_pixel" to
                    RenderArm.FUSED_DRAGO_TONEMAP_EVALS_PER_PIXEL,
                "drago_pow_linearize_per_pixel" to
                    RenderArm.FUSED_DRAGO_POW_LINEARIZE_PER_PIXEL,
                "drago_out_gamma_encode_per_pixel" to
                    RenderArm.FUSED_DRAGO_OUT_GAMMA_ENCODE_PER_PIXEL,
                "intermediate_rgba8_materializations" to
                    RenderArm.FUSED_INTERMEDIATE_RGBA8_MATERIALIZATIONS,
                "bilateral_taps_per_pixel" to 0,
            )
            // bf는 LabGlsl을 한 번도 부르지 않으므로 색공간 변환 칸은 base arm과 **같다**
            // (0을 더한 것이다). 달라지는 것은 패스 수·전체화면 패스 수·중간 materialize 수다.
            RenderArm.DRAGO_CLAHE_CHAIN_BF -> mapOf(
                "passes_total" to RenderArm.CHAIN_BF_PASSES_TOTAL,
                "fullscreen_passes" to RenderArm.CHAIN_BF_FULLSCREEN_PASSES,
                "srgb_to_linear_per_pixel" to RenderArm.CHAIN_BF_SRGB_TO_LINEAR_PER_PIXEL,
                "lab_f_forward_per_pixel" to RenderArm.CHAIN_BF_LAB_F_FORWARD_PER_PIXEL,
                "lab_f_inverse_per_pixel" to RenderArm.CHAIN_BF_LAB_F_INVERSE_PER_PIXEL,
                "linear_to_srgb_per_pixel" to RenderArm.CHAIN_BF_LINEAR_TO_SRGB_PER_PIXEL,
                "drago_tonemap_evals_per_pixel" to
                    RenderArm.CHAIN_BF_DRAGO_TONEMAP_EVALS_PER_PIXEL,
                "drago_pow_linearize_per_pixel" to
                    RenderArm.CHAIN_BF_DRAGO_POW_LINEARIZE_PER_PIXEL,
                "drago_out_gamma_encode_per_pixel" to
                    RenderArm.CHAIN_BF_DRAGO_OUT_GAMMA_ENCODE_PER_PIXEL,
                "intermediate_rgba8_materializations" to
                    RenderArm.CHAIN_BF_INTERMEDIATE_RGBA8_MATERIALIZATIONS,
                "bilateral_taps_per_pixel" to RenderArm.BF_TAP_COUNT,
            )
            RenderArm.DRAGO_CLAHE_FUSED_BF -> mapOf(
                "passes_total" to RenderArm.FUSED_BF_PASSES_TOTAL,
                "fullscreen_passes" to RenderArm.FUSED_BF_FULLSCREEN_PASSES,
                "srgb_to_linear_per_pixel" to RenderArm.FUSED_BF_SRGB_TO_LINEAR_PER_PIXEL,
                "lab_f_forward_per_pixel" to RenderArm.FUSED_BF_LAB_F_FORWARD_PER_PIXEL,
                "lab_f_inverse_per_pixel" to RenderArm.FUSED_BF_LAB_F_INVERSE_PER_PIXEL,
                "linear_to_srgb_per_pixel" to RenderArm.FUSED_BF_LINEAR_TO_SRGB_PER_PIXEL,
                "drago_tonemap_evals_per_pixel" to
                    RenderArm.FUSED_BF_DRAGO_TONEMAP_EVALS_PER_PIXEL,
                "drago_pow_linearize_per_pixel" to
                    RenderArm.FUSED_BF_DRAGO_POW_LINEARIZE_PER_PIXEL,
                "drago_out_gamma_encode_per_pixel" to
                    RenderArm.FUSED_BF_DRAGO_OUT_GAMMA_ENCODE_PER_PIXEL,
                "intermediate_rgba8_materializations" to
                    RenderArm.FUSED_BF_INTERMEDIATE_RGBA8_MATERIALIZATIONS,
                "bilateral_taps_per_pixel" to RenderArm.BF_TAP_COUNT,
            )
            // 프레임 단일 query arm. **계측 방식은 셰이더를 바꾸지 않으므로 선언값이 짝과
            // 같다** — 그래서 짝의 표를 그대로 재사용한다(사본을 만들면 한쪽만 고쳐진다).
            // 🔴 `else -> emptyMap()`으로 흘리지 않는다. 흘리면 조합 arm인데 "조합 arm이
            //    아니다"라는 **거짓 empty_reason**이 로그로 나간다.
            RenderArm.DRAGO_CLAHE_CHAIN_1Q -> declaredCounts(RenderArm.DRAGO_CLAHE_CHAIN)
            RenderArm.DRAGO_CLAHE_CHAIN_BF_1Q -> declaredCounts(RenderArm.DRAGO_CLAHE_CHAIN_BF)
            // 조합 arm이 아니다 — 짝(blit_2pass)과 같이 이 표가 성립하지 않는다.
            RenderArm.BLIT_2PASS_1Q -> emptyMap()
            else -> emptyMap()
        }
    }

    /**
     * `clahe_gamma`·`agcwd` 공통 서술. **동작 채널이 계약서 제안값과 다르다는 사실**이
     * 두 arm 모두에서 빠지지 않게 한 군데로 모았다 — 한쪽만 적으면 나중에 그 arm의 로그만
     * 보고 "차이가 없었다"고 잘못 결론 낸다.
     */
    private fun putLabCommon(json: JSONObject, facts: SessionFacts) {
        json.put(
            "operates_on",
            "CIE LAB의 L* 채널만 (a,b는 그대로 둔다). sRGB → 선형 → D65 XYZ → L*a*b* " +
                "(OpenCV COLOR_BGR2Lab과 같은 계수)"
        )
        // 지어낸 계약값을 조용히 굳히지 않기 위한 문장이다. 지우지 말 것.
        json.put("provenance", RenderArm.LAB_PROVENANCE)
        json.put("upstream_deviation", RenderArm.LAB_DEVIATION)
        json.put("desaturation_note", RenderArm.LAB_DESATURATION_NOTE)
        json.put("glare_note", RenderArm.LAB_GLARE_NOTE)
        json.put("flicker_note", RenderArm.LAB_FLICKER_NOTE)
        json.put("how_to_compare", RenderArm.LAB_HOW_TO_COMPARE)
        // 🔴 이 두 문장이 빠지면 팀이 "A1이 알고리즘 성질상 가장 비싸다"는 **반대 결론**을
        //    낸다(실제로는 A1의 통계 부분이 셋 중 가장 싸다). 지우지 말 것.
        json.put("cost_split_note", RenderArm.COST_SPLIT_NOTE)
        json.put("levers_not_pulled", RenderArm.LAB_LEVERS_NOT_PULLED)
        json.put("gpu_status", facts.stage2Status)
    }

    private fun buildRender(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        json.put("display_path", "2-C: CameraX Preview -> 우리 SurfaceTexture(OES) -> GL")
        json.put("arm", facts.arm.id)
        json.put("gl_surface_size", "${facts.glSurfaceWidth}x${facts.glSurfaceHeight}")
        json.put("egl_context_client_version", facts.eglContextClientVersion)
        json.put("render_mode", "RENDERMODE_WHEN_DIRTY (onFrameAvailable에서 requestRender)")
        json.put(
            "draw_call",
            when {
                // ⚠ bf arm을 **먼저** 본다. usesComputeStage2에는 bf arm도 들어 있으므로
                //   순서가 뒤집히면 "통계 패스 2개"라는 틀린 서술이 나간다.
                facts.arm.usesChainedBilateral ->
                    "그리기 패스는 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4), " +
                        "통계 패스 4개(3단 × 2벌)는 glDispatchCompute. bf는 그리기 패스다" +
                        "(gather 필터라 통계가 없다)"
                facts.arm.usesFusedBilateral ->
                    "그리기 패스는 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4), " +
                        "통계 패스 4개는 glDispatchCompute (적용은 1패스로 접혔다). " +
                        "bf는 그리기 패스다"
                facts.arm.usesChainedComputeStage2 ->
                    "그리기 패스는 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4), " +
                        "통계 패스 4개(3단 × 2벌)는 glDispatchCompute"
                facts.arm.usesFusedComputeStage2 ->
                    "그리기 패스는 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4), " +
                        "통계 패스 4개는 glDispatchCompute (적용은 1패스로 접혔다)"
                facts.arm.usesComputeStage2 ->
                    "그리기 패스는 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4), " +
                        "통계 패스 2개는 glDispatchCompute"
                facts.arm.usesHighlightOverlay ->
                    "패스1·2·4는 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4), " +
                        "패스3(④ 오버레이)은 glDrawArrays(" +
                        "${HighlightOverlay.GL_PRIMITIVE_NAME}, 0, " +
                        "${facts.arm.highlightBoxCount * HighlightOverlay.VERTS_PER_BOX}) " +
                        "**1회**다 — 박스 " +
                        "${facts.arm.highlightBoxCount}개의 스트로크 quad를 한 버퍼에 담는다"
                else -> "패스당 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)"
            }
        )
        json.put(
            "shader_language",
            when {
                facts.arm.usesChainedBilateral ->
                    "GLSL ES 1.00 (패스1·패스9) + GLSL ES 3.10 (패스2·3·5·6 컴퓨트, " +
                        "패스4·7 적용, 패스8 bf). 적용 패스가 SSBO를 읽어야 해서 310으로 " +
                        "올렸고, bf는 SSBO를 읽지 않지만 반경 루프 상한이 uniform이라 " +
                        "ES 1.00의 제한된 for 형식에 맞지 않고 정점 셰이더를 다른 적용 패스와 " +
                        "**같은 문자열로** 재사용하려면 같은 버전이어야 한다"
                facts.arm.usesFusedBilateral ->
                    "GLSL ES 1.00 (패스1·패스8) + GLSL ES 3.10 (패스2·3·4·5 컴퓨트, " +
                        "패스6 적용, 패스7 bf). 적용 패스가 SSBO를 **둘** 읽어야 해서 310으로 " +
                        "올렸고, bf가 310인 이유는 체인+bf와 같다"
                facts.arm.usesChainedComputeStage2 ->
                    "GLSL ES 1.00 (패스1·패스8) + GLSL ES 3.10 (패스2·3·5·6 컴퓨트, " +
                        "패스4·7 적용). 적용 패스가 SSBO를 읽어야 해서 310으로 올렸고, ESSL은 " +
                        "한 프로그램 안에서 버전을 섞지 못하므로 그 패스의 정점 셰이더도 310이다"
                facts.arm.usesFusedComputeStage2 ->
                    "GLSL ES 1.00 (패스1·패스7) + GLSL ES 3.10 (패스2·3·4·5 컴퓨트, " +
                        "패스6 적용). 적용 패스가 SSBO를 **둘** 읽어야 해서 310으로 올렸고, " +
                        "ESSL은 한 프로그램 안에서 버전을 섞지 못하므로 그 패스의 정점 " +
                        "셰이더도 310이다"
                facts.arm.usesComputeStage2 ->
                    "GLSL ES 1.00 (패스1·패스5) + GLSL ES 3.10 (패스2·3 컴퓨트, 패스4 적용). " +
                        "적용 패스가 SSBO를 읽어야 해서 310으로 올렸고, ESSL은 한 프로그램 " +
                        "안에서 버전을 섞지 못하므로 그 패스의 정점 셰이더도 310이다"
                else -> "GLSL ES 1.00 (전 패스 공통)"
            }
        )

        // 처리 해상도. **값을 지어내지 않는다** — 협상 전이면 그 사실을 그대로 적는다.
        val process = JSONObject()
        if (facts.processWidth <= 0 || facts.processHeight <= 0) {
            process.put("resolution", JSONObject.NULL)
            process.put(
                "note",
                "카메라와 해상도를 협상하지 못했다 — 오프스크린 크기를 지어내지 않았다"
            )
        } else {
            process.put("resolution", "${facts.processWidth}x${facts.processHeight}")
            process.put(
                "source",
                "SurfaceRequest.resolution (= camera_actual.resolution). 하드코딩이 아니다"
            )
        }
        process.put("offscreen_status", facts.offscreenStatus)
        process.put("stage2_status", facts.stage2Status)
        // 0이 아니면 선언한 pipeline_stages와 실제로 탄 경로가 어긋난 것이다.
        process.put("frames_fell_back_to_passthrough", facts.offscreenFallbackDraws)
        json.put("processing", process)

        // ⚠ S1의 `future_gpu_column`을 `gpu_column`으로 이름만 바꿨다. 3패스 arm의 매핑은 S1이
        //   선언한 것 그대로이고(패스1→stage_b_ms, 패스2→stage_d_ms, 패스3→gpu_present_ms),
        //   `drago`에서만 패스2 자리가 3단으로 벌어진다. 이 키는 하네스의 비교 조건이 아니다.
        //   **열 이름의 출처는 RenderArm.gpuColumns 하나**이므로 여기서도 그 목록을 순서대로
        //   꺼내 쓴다 — 손으로 다시 적으면 어긋나는 날 조용히 틀린 라벨이 나간다.
        val passes = JSONArray()
        val instrumented = facts.gpuTimer.instrumented
        val columns = facts.arm.gpuColumns
        if (facts.arm == RenderArm.PASSTHROUGH) {
            passes.put(
                JSONObject()
                    .put("index", 1)
                    .put("name", "oes_to_screen")
                    .put("target", "default framebuffer")
                    .put("shader", "OES 패스스루 (처리 없음)")
                    .put("gpu_column", "gpu_present_ms")
                    // 베이스라인 재현 경로라 계측하지 않는다 → CSV에 그 열이 없다.
                    .put("instrumented", false)
            )
        } else {
            val names: List<Triple<String, String, String>> =
                if (facts.arm.usesHighlightOverlay) {
                    // ④ arm. ② 자리는 단순 복사이고 그 뒤에 오버레이 패스가 하나 붙는다.
                    // 🔴 아래 else(3패스 골격)로 떨어뜨리면 **패스 하나가 서술에서 사라진다.**
                    listOf(
                        Triple(
                            "oes_to_fbo_a", "FBO_A (처리 해상도)", "OES 패스스루 + uTexMatrix"
                        ),
                        Triple(
                            "stage2_slot_copy",
                            "FBO_B (처리 해상도)",
                            "② 자리이며 **단순 복사**다(이 arm이 재는 것은 ④다)",
                        ),
                        Triple(
                            "stage4_highlight",
                            "FBO_B (처리 해상도. **clear하지 않고 덧그린다**)",
                            "④ 이중 스트로크 박스 ${facts.arm.highlightBoxCount}개" +
                                "(검정 밑선 + 대비색 본선, 비채움). " +
                                "얇은 사각형 스트로크 quad를 " +
                                "${HighlightOverlay.GL_PRIMITIVE_NAME} **드로우콜 1회**로 " +
                                "그린다. 두께는 처리 해상도의 짧은 변에서 계산한다" +
                                "(720p 기준 ${RenderArm.HIGHLIGHT_STROKE_PX_AT_720P}px). " +
                                "박스는 정적 더미이고 ③ 결과가 아니다 — 자세한 것은 " +
                                "session.json의 overlay 블록",
                        ),
                        Triple(
                            "present", "default framebuffer (surface 크기)", "단순 복사"
                        ),
                    )
                } else if (facts.arm.usesComputeStage2) {
                    val middle = when (facts.arm) {
                        RenderArm.CLAHE_GAMMA -> listOf(
                            Triple(
                                "stage2_clahe_analyze",
                                "히스토그램 SSBO (glDispatchCompute)",
                                "타일별 ${LabGlsl.BIN_COUNT}빈 히스토그램(LAB L). 워크그룹 " +
                                    "하나가 타일 하나를 맡아 공유메모리 atomic만 쓴다 — " +
                                    "타일이 서로 독립이라 전역 atomic이 없다",
                            ),
                            Triple(
                                "stage2_clahe_build",
                                "LUT SSBO (glDispatchCompute, 타일당 워크그룹 1개)",
                                "클립 + 초과분 재분배 + CDF → 타일별 " +
                                    "${LabGlsl.BIN_COUNT}엔트리 LUT (uClipLimit). " +
                                    "CPU로 읽어오면 GPU 동기화가 걸리므로 GPU에서 계산한다",
                            ),
                            Triple(
                                "stage2_clahe_apply",
                                "FBO_B (처리 해상도)",
                                "타일 간 이중선형 보간 + 감마 (uTiles / uClaheGamma). " +
                                    "LAB의 L만 바꾸고 a,b는 그대로 둔다",
                            ),
                        )
                        RenderArm.AGCWD -> listOf(
                            Triple(
                                "stage2_agcwd_analyze",
                                "히스토그램 SSBO (glDispatchCompute)",
                                "전역 ${LabGlsl.BIN_COUNT}빈 히스토그램(LAB L). 워크그룹 " +
                                    "공유메모리 atomic + SSBO atomic으로 dispatch 1회",
                            ),
                            Triple(
                                "stage2_agcwd_build",
                                "LUT SSBO (glDispatchCompute, 워크그룹 1개)",
                                "가중 분포(pdf_w) → 누적 → " +
                                    "${LabGlsl.BIN_COUNT}엔트리 1D LUT (uAlpha). " +
                                    "CPU로 읽어오면 GPU 동기화가 걸리므로 GPU에서 계산한다",
                            ),
                            Triple(
                                "stage2_agcwd_apply",
                                "FBO_B (처리 해상도)",
                                "1D LUT 적용(최근접 빈). LAB의 L만 바꾸고 a,b는 그대로 둔다",
                            ),
                        )
                        // 🔴 예전에는 이 자리가 `else -> drago 패스 이름`이었다. 그대로 두면
                        //    새 arm이 **drago 패스 서술을 달고** session.json에 나간다 —
                        //    조합 arm이 들어오면서 실제로 그렇게 될 뻔했다. 명시 분기다.
                        RenderArm.DRAGO -> DRAGO_PASSES
                        RenderArm.DRAGO_CLAHE_CHAIN -> CHAIN_STAGE2_PASSES
                        RenderArm.DRAGO_CLAHE_FUSED -> FUSED_STAGE2_PASSES
                        // bf arm은 base 목록 **뒤에** bf 패스 하나를 더한다. 목록을 복사하지
                        // 않으므로 base 서술이 바뀌면 bf arm도 함께 따라간다.
                        RenderArm.DRAGO_CLAHE_CHAIN_BF ->
                            CHAIN_STAGE2_PASSES + bilateralPass("FBO_B (처리 해상도. 핑퐁)")
                        RenderArm.DRAGO_CLAHE_FUSED_BF ->
                            FUSED_STAGE2_PASSES + bilateralPass("FBO_A (처리 해상도. 핑퐁)")
                        // 프레임 단일 query arm은 짝과 **같은 draw 함수**를 타므로 패스 목록도
                        // 짝의 것 그대로다. 🔴 else 낙하로 처리하지 않는다 — 흘리면 8·9패스
                        // arm의 서술에서 패스가 통째로 사라진다(위 else -> drago 함정과 같은 부류).
                        RenderArm.DRAGO_CLAHE_CHAIN_1Q -> CHAIN_STAGE2_PASSES
                        RenderArm.DRAGO_CLAHE_CHAIN_BF_1Q ->
                            CHAIN_STAGE2_PASSES + bilateralPass("FBO_B (처리 해상도. 핑퐁)")
                        else -> emptyList()
                    }
                    listOf(
                        Triple(
                            "oes_to_fbo_a", "FBO_A (처리 해상도)", "OES 패스스루 + uTexMatrix"
                        ),
                    ) + middle + listOf(
                        Triple(
                            "present", "default framebuffer (surface 크기)", "단순 복사"
                        ),
                    )
                } else {
                    listOf(
                        Triple(
                            "oes_to_fbo_a", "FBO_A (처리 해상도)", "OES 패스스루 + uTexMatrix"
                        ),
                        Triple(
                            "stage2_slot",
                            "FBO_B (처리 해상도)",
                            if (facts.arm == RenderArm.GAMMA_ONLY) "감마 (uGamma)" else "단순 복사",
                        ),
                        Triple(
                            "present", "default framebuffer (surface 크기)", "단순 복사"
                        ),
                    )
                }
            // 🔴 **패스와 열이 1:1인 것은 패스별 계측 arm뿐이다.** 프레임 단일 query arm은
            //    렌더 패스가 3~9개인데 열은 gpu_frame_ms 하나이고, 그 하나가 **패스 하나가
            //    아니라 프레임 전체**를 잰다. 그래서 그 arm에서는 어느 패스에도 열을 매달지
            //    않고(gpu_column = null) covered_by로 "이 패스는 프레임 query 안에 들어 있다"만
            //    말한다 — i번째 열을 집으면 '패스1 = gpu_frame_ms'라는 거짓 매핑이 나간다.
            val singleFrameQuery = facts.arm.usesSingleFrameQuery
            for (i in names.indices) {
                val (name, target, shader) = names[i]
                passes.put(
                    JSONObject()
                        .put("index", i + 1)
                        .put("name", name)
                        .put("target", target)
                        .put("shader", shader)
                        .put(
                            "gpu_column",
                            when {
                                singleFrameQuery -> JSONObject.NULL
                                i < columns.size -> columns[i]
                                else -> JSONObject.NULL
                            }
                        )
                        .apply {
                            if (singleFrameQuery) {
                                put("covered_by", RenderArm.SINGLE_FRAME_QUERY_COLUMN)
                            }
                        }
                        .put("instrumented", instrumented)
                )
            }
        }
        json.put("passes", passes)
        if (facts.arm.usesSingleFrameQuery) {
            json.put(
                "pass_column_mapping_note",
                "🔴 **이 arm에서 패스와 GPU 열은 1:1이 아니다.** 렌더 패스 " +
                    "${facts.arm.renderPassCount}개 전부를 query **하나**로 감쌌고 그 값이 " +
                    "${RenderArm.SINGLE_FRAME_QUERY_COLUMN} 한 열로 나간다 — 그래서 위 " +
                    "passes[]의 gpu_column은 전부 null이고 covered_by가 대신 그 열을 가리킨다. " +
                    "어느 패스가 비싼지는 이 런에서 나오지 않는다(짝 arm " +
                    "${facts.arm.singleFrameQueryPeer?.id}의 열이 그 질문에 답한다). " +
                    RenderArm.SINGLE_QUERY_NOT_A_SUM
            )
        }
        json.put(
            "pass_boundary_note",
            "패스마다 glBindFramebuffer + glClear를 명시한다. 타일 기반 GPU(Mali)에서 " +
                "드라이버가 렌더패스를 병합하면 timer query 귀속이 흐려진다 " +
                "(gpu_timer.attribution_note 참고)"
        )
        json.put(
            "note",
            "표시 방향·화면비 보정을 하지 않는다. 센서 방향 그대로 GL 서피스 전체에 " +
                "늘려 그리므로 세로 화면에서 프리뷰가 누워 보인다 — 픽셀 비용에는 " +
                "영향이 없고 이번 측정 범위 밖이다"
        )
        return json
    }
}
