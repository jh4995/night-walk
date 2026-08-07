package com.bammasil.poc.log

import com.bammasil.poc.gl.ColorTransformCensus
import com.bammasil.poc.gl.DragoClaheChainStage
import com.bammasil.poc.gl.DragoClaheFusedStage
import com.bammasil.poc.gl.GlCapabilities
import com.bammasil.poc.gl.GlCapabilitiesProbe
import com.bammasil.poc.gl.GpuTimerReport
import com.bammasil.poc.gl.GpuTimerRing
import com.bammasil.poc.detect.DetectContract
import com.bammasil.poc.detect.DetectGeometryCheck
import com.bammasil.poc.detect.DetectOverlayPublishFacts
import com.bammasil.poc.detect.DetectParityDumper
import com.bammasil.poc.detect.DetectParityResult
import com.bammasil.poc.detect.DetectPostprocessor
import com.bammasil.poc.detect.DetectReport
import com.bammasil.poc.detect.DetectRotationFacts
import com.bammasil.poc.gl.HighlightOverlay
import com.bammasil.poc.gl.LabGlsl
import com.bammasil.poc.gl.OverlayClassColors
import com.bammasil.poc.gl.OverlayCoordMap
import com.bammasil.poc.gl.OverlaySmootherFacts
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
    /**
     * ③ 이식 정확성 대조 덤프의 사실. **덤프 arm(`detect_parity_*`)이 아니면 null**이고
     * 그때는 `detect.parity` 블록 자체가 나가지 않는다(다른 블록들과 같은 규약).
     */
    val detectParity: DetectParityResult?,
    /**
     * ③ 회전의 사실(규약 §4). **③ arm이 아니면 null**이다.
     * 🔴 `parity.json`의 `source` 블록과 **같은 객체**에서 나온다 — 두 곳이 각자 계산하면
     * 갈리는 날이 오고, 그때 어느 쪽이 맞는지 알 수 없다.
     */
    val detectRotation: DetectRotationFacts?,
    /**
     * ③ 기하 왕복 자체검사의 결과. 세션을 여는 arm이 아니면 null이다.
     * 🔴 **실패하면 앱이 런을 시작하지 않는다** — 그래서 이 블록이 있는 런은 통과한 런이다.
     * 그래도 관측값(`max|d|`)을 싣는다. 통과/실패보다 관측값이 먼저다(규약 §7).
     */
    val detectGeometry: DetectGeometryCheck.Result?,
    /**
     * ④ 게시(③→④)의 런 사실. **③ 결과를 그리는 arm이 아니면 null**이고 그때는 `overlay`
     * 블록에 게시 키가 나가지 않는다(다른 블록들과 같은 규약).
     * 🔴 **A12 (2) quiesce 뒤에 뜬 스냅샷이어야 한다** — 그 앞이면 마지막 게시가 빠진다.
     */
    val overlayPublish: DetectOverlayPublishFacts?,
    /** ④ H칸(좌표 평활·hold)의 런 사실. 위와 같은 규약으로 null일 수 있다. */
    val overlaySmoothing: OverlaySmootherFacts?,
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
    /**
     * 🔴 **역전 박스**(`x2<x1` 또는 `y2<y1`)의 런 전체 총계. **거른 개수가 아니라 센
     * 개수다**(규약 §5-3) — 거르면 면적 0인 박스가 화면 가장자리에 남아 ④가 얇은 선을 그린다.
     */
    val invertedBoxes: Long,
    /**
     * 그중 처음 몇 개의 **실제 좌표**. 🔴 **개수만으로는 못 고친다** — 알려진 이슈 34가
     * 잡힌 것은 `x1=0.0, x2=-148.75`라는 구체 좌표 덕이었다.
     */
    val invertedSamples: List<DetectPostprocessor.InvertedBox>,
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
     *
     * v7에서 ③→④가 이어졌다: `frames.csv`에 ④ 오버레이 열 **3개**가 늘었다
     * (`stage_h_ms` / `overlay_boxes` / `t_overlay_source_ns` —
     * `FrameLogRecorder.OVERLAY_HEADER`). 🔴 **GPU 열이 아니다** — `stage_h_ms`는 CPU 벽시계라
     * `gpu_sum_ms`·`stage_d_total_ms`에 들어가지 않고, `overlay_boxes`는 시간이 아니라 개수라
     * 폐기 하한이 `>= 0`이다(**0은 정상값이다**).
     *
     * 🔴 **신선도(`t_render_start_ns − t_overlay_source_ns`) 열을 내지 않는다** — 유도값이라
     * PC가 계산한다(앱이 같은 이름 열을 내면 미지 열 경고가 난다).
     *
     * ⚠ **6에서 7로 올린 것은 이 앱이 그 열을 실제로 내기 시작했기 때문이다.** 하네스는 이미
     * v7이었고(`docs/FRAME_LOG_SCHEMA.md` §6대로 하네스가 먼저 들어간다), 앱이 6을 선언한 채
     * v7 열을 실으면 "앱이 하네스보다 뒤처졌다"는 **거짓 경고**가 그 열이 실제로 있는 로그에
     * 붙는다(v4→v5 때와 같은 논거다).
     */
    const val SCHEMA_VERSION = 7

    /**
     * ④ 좌표 매핑에서 **분석 치수와 처리 치수의 종횡비가 같다고 볼 허용치**.
     *
     * ⚠ **우리가 선언한 검사 조건이지 계약값이 아니다**(`DetectGeometryCheck.TOLERANCE_PX`와
     * 같은 부류다). 근거: 1280×720(1.7778)과 640×360(1.7778)은 정확히 같고, 실제로 어긋나는
     * 조합(16:9 vs 4:3 = 1.7778 vs 1.3333)은 0.44 떨어져 있어 자릿수가 한참 멀다.
     */
    const val ASPECT_TOLERANCE = 1e-3

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
                        "t_recv > t_render_start 가 되어 하네스 교차검사 A가 거짓 위반을 낸다). " +
                        "⚠ **v7부터 이 스탬프 앞에 한 줄이 더 있다**: ④ 게시 스냅샷 읽기다" +
                        "(t_render_start_ns보다 **먼저** 읽어야 어떤 프레임도 자기 렌더 " +
                        "시작보다 미래에 게시된 결과를 쓰지 않는다 — overlay.publish.clock). " +
                        "🔴 그 줄은 **arm과 무관하게 실행되지만**(오버레이 arm이 아니면 조건이 " +
                        "false여서 참조 읽기조차 하지 않는다) **할당·GL 호출·syscall이 없고 " +
                        "계측 창 밖이다** — 즉 passthrough arm의 승격 베이스라인 동등성은 " +
                        "유지된다(그 arm은 query를 하나도 걸지 않으므로 GPU 동작이 " +
                        "그대로이고, 이 줄이 t_render_start_ns 앞에 있어 render_latency_ms에도 " +
                        "들어가지 않는다)"
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
                "(highlight_boxes / highlight_boxes_stress / detect_cpu_highlight)이 낸다. " +
                "🔴 stage_h_ms는 **GPU 열이 아니다** — CPU 벽시계(GL 스레드)라 이 블록의 " +
                "어느 칸에도 들어가지 않는다(overlay.smoothing 참고)"
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
            RenderArm.DRAGO_CLAHE_FUSED_1Q -> {
                putFused(json, facts)
                putSingleFrameQueryNotes(json, RenderArm.DRAGO_CLAHE_FUSED_1Q)
            }
            RenderArm.DRAGO_CLAHE_FUSED_BF_1Q -> {
                putFused(json, facts)
                putBilateralOverrides(json, facts, RenderArm.DRAGO_CLAHE_FUSED_BF)
                putSingleFrameQueryNotes(json, RenderArm.DRAGO_CLAHE_FUSED_BF_1Q)
            }
            RenderArm.HIGHLIGHT_BOXES_1Q -> {
                putHighlightCopy(json, facts)
                putSingleFrameQueryNotes(json, RenderArm.HIGHLIGHT_BOXES_1Q)
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
            RenderArm.DETECT_XNNPACK_PROF,
            RenderArm.DETECT_PARITY_CPU,
            RenderArm.DETECT_PARITY_NNAPI,
            RenderArm.DETECT_PARITY_XNNPACK,
            // 🔴 회전 대조군도 ② 자리는 짝(detect_cpu)과 **글자 그대로 같다.**
            RenderArm.DETECT_CPU_NOROT -> {
                putBlit2Pass(json)
                // 🔴 ② 서술만 보고 "탐지가 프레임타임에 안 들어간다"를 유도하지 못하게 한다.
                json.put("detect_round_scope", RenderArm.DETECT_ROUND_SCOPE)
            }
            // ④ arm의 ② 자리는 **단순 복사**다. 오버레이 서술은 root의 overlay 블록에 있다.
            RenderArm.HIGHLIGHT_BOXES, RenderArm.HIGHLIGHT_BOXES_STRESS ->
                putHighlightCopy(json, facts)
            // ── ③→④ 연결 세트(v7) ────────────────────────────────────────
            // ② 자리는 위 두 부류를 **합친 것**이다: 오버레이 arm이므로 단순 복사이고
            // (putHighlightCopy) 동시에 탐지가 도는 arm이다(detect_round_scope).
            // 🔴 짝의 서술을 그대로 재사용하고 else 낙하로 처리하지 않는다.
            RenderArm.DETECT_CPU_HIGHLIGHT -> {
                putHighlightCopy(json, facts)
                json.put("detect_round_scope", RenderArm.DETECT_ROUND_SCOPE)
            }
            RenderArm.DETECT_CPU_HIGHLIGHT_1Q -> {
                putHighlightCopy(json, facts)
                json.put("detect_round_scope", RenderArm.DETECT_ROUND_SCOPE)
                putSingleFrameQueryNotes(json, RenderArm.DETECT_CPU_HIGHLIGHT_1Q)
            }
            // 오버레이가 없는 3패스 골격 + 탐지. 짝은 detect_cpu다.
            RenderArm.DETECT_CPU_1Q -> {
                putBlit2Pass(json)
                json.put("detect_round_scope", RenderArm.DETECT_ROUND_SCOPE)
                putSingleFrameQueryNotes(json, RenderArm.DETECT_CPU_1Q)
            }
        }
        return json
    }

    /**
     * ④ 오버레이 arm의 ② 서술. **`highlight_boxes_1q`도 이 함수를 부른다** —
     * 두 arm의 렌더가 글자 그대로 같아야 계측 방식의 차이만 남고, 서술에 사본을 만들면
     * 한쪽만 고쳐지는 날 두 arm의 문장이 갈라진다([putBlit2Pass]와 같은 이유다).
     */
    private fun putHighlightCopy(json: JSONObject, facts: SessionFacts) {
        json.put("algorithm", "copy")
        json.put("gpu_status", facts.stage2Status)
        // 🔴 열 이름을 이 arm이 **실제로 싣는 열**로 말한다. 프레임 단일 query arm에는
        //    stage_d_ms도 stage_i_ms도 없으므로 그 이름을 그대로 쓰면 거짓 서술이 나간다.
        val columnPart = if (facts.arm.usesSingleFrameQuery) {
            "⚠ 이 arm은 **패스별 열을 싣지 않는다**(gpu_frame_ms 하나뿐이다) — ② 자리와 " +
                "④ 오버레이의 패스별 비용은 짝 arm " +
                "${facts.arm.singleFrameQueryPeer?.id}의 stage_d_ms / stage_i_ms가 말한다"
        } else {
            "여기서 나오는 stage_d_ms는 ②의 비용이 아니라 **골격 자체(오프스크린 왕복)의 " +
                "비용**이며 blit_2pass의 같은 자리와 같은 것이다. 이 arm이 재는 것은 " +
                "**stage_i_ms(④ 오버레이)**다"
        }
        json.put(
            "note",
            "④ 오버레이 arm이라 ② 자리는 단순 복사다 — $columnPart. " +
                "④의 서술은 session.json의 overlay 블록에 있다"
        )
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
     *
     * 🔴 **열 이름을 무조건 `stage_i_ms`라고 적지 않는다.** `highlight_boxes_1q`는 이 블록을
     * 함께 내지만(둘 다 [RenderArm.usesHighlightOverlay]다) `gpu_frame_ms` 하나만 싣는다 —
     * 거기에 `stage_i_ms`를 사실 키로 적으면 **CSV에 없는 열을 있다고 말하는 것**이고,
     * `session.json`은 나중에 숫자의 출처를 되묻는 유일한 기록이라 그게 곧 가짜 대응이 된다.
     * [putHighlightCopy]가 `stage2_params` 쪽에서 이미 같은 분기를 하고 있었는데 **이 블록만
     * 빠져 있었다**(독립 검증 지적). 두 곳의 표현이 갈라지지 않도록 아래 [iCostPhrase] 하나를
     * 만들어 두 문장이 함께 쓴다.
     */
    private fun buildOverlay(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        val singleQuery = facts.arm.usesSingleFrameQuery
        val peerId = facts.arm.singleFrameQueryPeer?.id
        // ④ 오버레이 비용이 **이 arm에서 어느 열에 들어 있는가**. 아래 두 자리가 같은 것을 쓴다.
        val iCostPhrase = if (singleQuery) {
            "gpu_frame_ms(프레임 전체를 감싼 query 하나뿐이라 오버레이 패스만 떼어낼 수 없다)"
        } else {
            "stage_i_ms"
        }
        json.put("stage", "④ 선택적 강조 (버짓 I칸)")
        json.put(
            "gpu_column",
            if (singleQuery) RenderArm.SINGLE_FRAME_QUERY_COLUMN else "stage_i_ms"
        )
        if (singleQuery) {
            json.put(
                "gpu_column_note",
                "🔴 **이 arm에는 stage_i_ms가 없다.** 계측 방식만 다른 arm이라(렌더는 짝과 " +
                    "글자 그대로 같다) 열이 gpu_frame_ms 하나뿐이고, 그 값은 **프레임 전체의 " +
                    "GPU 시간**이지 I칸이 아니다 — 버짓 I칸으로 옮겨 적지 말 것. ④ 오버레이 " +
                    "패스만의 비용은 짝 arm ${peerId}의 stage_i_ms가 말한다. 이 블록의 나머지 " +
                    "키(박스 개수·기하·색·스펙 이탈)는 **렌더가 같으므로 그대로 성립한다**"
            )
        }
        json.put("upstream_reference", "scripts/emphasize.py")
        json.put("spec_provenance", RenderArm.HIGHLIGHT_SPEC_PROVENANCE)
        // 🔴 조건. 지우지 말 것.
        val dynamicBoxes = facts.arm.usesDynamicHighlightBoxes
        if (dynamicBoxes) {
            // 🔴 **개수를 지어내지 않는다.** 이 arm의 개수는 선언된 조건이 아니라 프레임마다
            //    다른 관측값이고, 고정값을 적으면 그것이 곧 거짓 조건이 된다.
            json.put("box_count", JSONObject.NULL)
            json.put("box_count_dynamic", true)
            json.put("box_count_column", "overlay_boxes")
            json.put("box_count_note", RenderArm.OVERLAY_DYNAMIC_BOX_NOTE)
        } else {
            json.put("box_count", facts.arm.highlightBoxCount)
            json.put("box_count_dynamic", false)
        }
        json.put("box_count_provenance", RenderArm.HIGHLIGHT_BOX_PROVENANCE)
        json.put(
            "box_source",
            if (dynamicBoxes) {
                "🔴 **③ 탐지 결과다.** DetectOverlayPublisher가 게시한 스냅샷을 GL 스레드가 " +
                    "H칸(좌표 평활·hold)에 태워 그린다 — 정적 더미가 아니므로 **프레임마다 " +
                    "다르고 재현 가능하지 않다.** 같은 장면을 다시 찍어도 같은 박스가 나오지 " +
                    "않는다. 좌표 공간의 사슬은 coordinate_map에, 평활 정책은 smoothing에 있다"
            } else {
                "🔴 **정적 더미 박스다.** ③ 탐지 결과가 아니다. 난수를 쓰지 않으므로 " +
                    "프레임마다 완전히 같고, 그래서 재현 가능하다. " +
                    "⚠ ③ 결과를 그리는 arm은 detect_cpu_highlight / _1q이며 이 arm이 아니다"
            }
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
                "GPU가 컬러 어태치먼트를 다시 load하고, **${iCostPhrase}에는 그 비용이 섞여 " +
                "있다.** " +
                "오버레이 패스의 실제 비용이며 빼낼 수단이 없다. 게다가 패스2(복사)와 패스3의 " +
                "타깃이 같은 FBO_B라 드라이버가 두 렌더패스를 병합하면 두 열의 경계가 흐려진다 " +
                "— 일반 주의사항은 gpu_timer.attribution_note와 같다"
        )
        // 🔴 짝 arm의 문장을 그대로 쓰면 **없는 열로 개당 기울기를 구하라**고 말하게 된다.
        //    게다가 이 arm에는 stress 짝(`highlight_boxes_stress_1q`)이 아예 없어서 그 방법
        //    자체가 성립하지 않는다 — 없는 절차를 안내하지 않는다.
        json.put(
            "how_to_compare",
            if (singleQuery) {
                "🔴 **이 arm으로 ④의 개당 비용을 구할 수 없다.** 열이 gpu_frame_ms 하나뿐이라 " +
                    "오버레이 패스가 분리되지 않고, 개수만 다른 짝(highlight_boxes_stress)의 " +
                    "**단일 query 판이 없어** 차분을 낼 상대도 없다. 개당 기울기는 짝 arm " +
                    "${peerId}와 highlight_boxes_stress의 stage_i_ms 차분으로 구한다. " +
                    "이 arm이 답하는 질문은 **하나뿐**이다: 짝 arm의 gpu_sum이 프레임 전체 GPU " +
                    "시간을 얼마나 부풀려 세는가(gpu_frame_ms와의 차). 그 비교법은 " +
                    "**stage2_params.how_to_compare_instrumentation**에 있다"
            } else {
                RenderArm.HIGHLIGHT_HOW_TO_COMPARE
            }
        )
        json.put("gpu_status", facts.overlayStatus)
        // ── ③→④ 연결 arm에만 있는 것 (스키마 v7) ──────────────────────────
        if (dynamicBoxes) {
            json.put("class_color_mapping", buildOverlayClassColorMapping(facts))
            json.put("coordinate_map", buildOverlayCoordinateMap(facts))
            json.put("smoothing", buildOverlaySmoothing(facts))
            json.put("publish", buildOverlayPublish(facts))
            json.put("csv_columns", buildOverlayCsvColumns())
            // 🔴 **버린 것의 개수는 이 블록의 최상위에 둔다.** 조용히 버리지 않는다는 규약의
            //    실체이고, 하위 블록에 묻으면 읽는 사람이 찾지 못한다.
            putOverlayDiscards(json, facts)
        }
        return json
    }

    /**
     * 🔴 **세고 버린 것들.** 두 값 모두 "거른 개수"이며 **그린 개수가 아니다.**
     * `overlay.rejected_inverted`는 후처리가 센 `detect.run.inverted_boxes`와 **교차 대조한다** —
     * 두 수가 다르면 어느 단계가 역전을 만들었는지가 갈린다.
     */
    private fun putOverlayDiscards(json: JSONObject, facts: SessionFacts) {
        val p = facts.overlayPublish
        val s = facts.overlaySmoothing
        if (p == null) {
            json.put("rejected_inverted", JSONObject.NULL)
            json.put("dropped_over_cap", JSONObject.NULL)
            json.put(
                "discards_note",
                "게시 사실을 잡지 못했다 — 값을 지어내지 않는다"
            )
            return
        }
        json.put("rejected_inverted", p.rejectedInverted)
        json.put(
            "rejected_inverted_note",
            "🔴 **역전 박스(x2<x1 / y2<y1)를 세고 그리지 않은 수다**(규약 §5-3). 후처리는 " +
                "거르지 않고 세기만 하므로 그 박스가 게시자까지 온다 — 그리면 면적이 0이거나 " +
                "음수인 사각형이 화면 가장자리에 얇은 선으로 남고 그건 사용자에게 보이는 " +
                "쓰레기다. 🔴 **detect.run.inverted_boxes와 교차 대조할 것** — 그 값은 후처리가 " +
                "센 런 총계이고 이 값은 게시자가 거른 런 총계다. 두 수가 다르면 어느 단계가 " +
                "역전을 만들었는지가 갈린다(게시자는 게시된 추론만 보고, 후처리는 모든 추론을 " +
                "본다 — 게시자가 꺼져 있던 구간이 없으면 두 수는 같아야 한다). " +
                "⚠ 클램프로 막지 않는다(클램프가 바로 그 면적 0 박스를 만들었다)"
        )
        // 🔴 상한 초과는 **두 자리에서** 생긴다. 합치지 않고 갈라 싣는다 — 어느 쪽이 넘쳤는지가
        //    사라지면 상한을 올려야 할 곳을 못 찾는다.
        json.put(
            "dropped_over_cap",
            JSONObject()
                .put("publish", p.droppedOverCap)
                .put("smoothing", s?.droppedOverCap ?: JSONObject.NULL)
                .put(
                    "note",
                    "상한(smoothing.box_cap)을 넘어 **세고 버린** 박스 수다 — 조용히 버리지 " +
                        "않는다. `publish`는 탐지 워커가 게시할 때, `smoothing`은 GL 스레드가 " +
                        "hold 중인 트랙까지 합쳐 셀 때 넘친 수다(후자는 **프레임마다** 세므로 " +
                        "같은 초과가 여러 번 계상될 수 있다 — 두 수를 더하지 말 것). " +
                        "🔴 0이 아니면 그 런의 화면에 실제로 있던 위험물 일부가 그려지지 " +
                        "않았다는 뜻이고, 그때는 상한을 올려 다시 재야 한다(임의 측정값이다)"
                )
        )
    }

    /**
     * 🔴 **색을 무엇으로 골랐는지의 전기록.** 이 블록이 없으면 "사람에게 계단 색을 칠했다"를
     * 나중에 되짚을 수 없다 — 계약 A-4와 모델의 순서가 **반대**이므로 그 위험이 실재한다.
     *
     * `contract_a4_conflict`는 [DetectContract.contractConflictText]가 만든 문장을 **재사용**
     * 한다(사본을 만들면 한쪽만 고쳐진다). 값은 [DetectReport]가 이미 들고 있다.
     */
    private fun buildOverlayClassColorMapping(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        val publish = facts.overlayPublish
        json.put(
            "source",
            "🔴 **모델 임베드 메타의 names 하나뿐이다**(DetectRuntime.classNames) — " +
                "INTERFACES.md 계약 A-4의 순서를 쓰지 않는다. 색은 **이름**으로 고르므로" +
                "(gl/OverlayClassColors) 팀이 A-4를 어느 쪽으로 확정해도 코드가 바뀌지 않는다"
        )
        json.put("normalization", OverlayClassColors.NORMALIZATION)
        json.put(
            "table",
            JSONObject()
                .put(OverlayClassColors.CLASS_STAIRS, OverlayClassColors.STAIRS_COLOR_TEXT)
                .put(OverlayClassColors.CLASS_PERSON, OverlayClassColors.PERSON_COLOR_TEXT)
                .put("underline", OverlayClassColors.UNDERLINE_COLOR_TEXT)
                .put("<unknown>", OverlayClassColors.UNKNOWN_NAME_COLOR_TEXT)
        )
        json.put("unknown_policy", OverlayClassColors.UNKNOWN_POLICY)
        json.put("no_red_reason", RenderArm.HIGHLIGHT_NO_RED_REASON)
        // 🔴 이 런에서 **실제로 색의 출처가 된** 이름 목록. 선언이 아니라 관측이다.
        json.put(
            "class_names_used",
            JSONArray().apply { publish?.classNamesUsed?.forEach { put(it) } }
        )
        json.put(
            "unknown_names_seen",
            JSONArray().apply { publish?.unknownNamesSeen?.forEach { put(it) } }
        )
        val counts = JSONObject()
        publish?.countsByClass?.forEach { (name, n) -> counts.put(name, n) }
        json.put("counts_by_class", counts)
        json.put(
            "counts_by_class_note",
            "🔴 **그린 박스를 정규화된 이름으로 센 값이다**(게시 시점 계수 = 그린 시점과 다를 " +
                "수 있다: hold 때문에 한 게시가 여러 프레임에 걸쳐 그려지고, 이 표는 " +
                "**게시당 한 번** 센다). 프레임당 개수는 frames.csv의 overlay_boxes다. " +
                "키가 `<cls N: ...>` 꼴이면 cls가 **모델 이름 목록의 범위 밖**이었다는 뜻이고 " +
                "그때 이름을 지어내지 않았다"
        )
        // 🔴 기존 생성기를 재사용한다 — 충돌 문장의 사본을 만들지 않는다.
        json.put("contract_a4_conflict", facts.detect?.contractConflict ?: JSONObject.NULL)
        json.put(
            "contract_a4_conflict_note",
            if (facts.detect?.contractConflict == null) {
                "이 런에서는 모델의 클래스 순서가 계약 A-4와 **같았다**(또는 보고를 " +
                    "잡지 못했다) — 그래도 색은 이름으로 고른다"
            } else {
                "🔴 위 문장은 detect.classes.contract_conflict와 **같은 문장이다**" +
                    "(DetectContract.contractConflictText 하나가 만든다). 여기 함께 싣는 " +
                    "이유는 오버레이 색을 되짚는 사람이 detect 블록까지 안 열어도 이 충돌을 " +
                    "만나게 하려는 것이다"
            }
        )
        return json
    }

    /**
     * ① 센서 → ④ NDC 매핑의 사실. 🔴 **분석 치수와 처리 치수를 나란히 싣는다** — 종횡비가
     * 어긋나면 두 use case의 시야가 다르다는 뜻이고, 그 crop을 알려 주는 런타임 값이 없어
     * 매핑이 그만큼 틀린다([OverlayCoordMap.ASSUMPTIONS] (4)).
     */
    private fun buildOverlayCoordinateMap(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        json.put("formula", OverlayCoordMap.FORMULA)
        json.put("assumptions", OverlayCoordMap.ASSUMPTIONS)
        json.put("flip_y", OverlayCoordMap.FLIP_Y)
        // 🔴 분석 치수(센서 공간) — 값을 지어내지 않는다. 분석 프레임이 없었으면 null이다.
        val analysis = facts.analysis
        if (analysis == null) {
            json.put("analysis_resolution", JSONObject.NULL)
            json.put(
                "analysis_resolution_note",
                "분석 use case가 실제로 준 치수를 못 잡았다(프레임이 하나도 안 왔다) — " +
                    "값을 지어내지 않는다. Preview 해상도로 대체하지 **않는다**"
            )
        } else {
            json.put("analysis_resolution", "${analysis.width}x${analysis.height}")
        }
        if (facts.processWidth > 0 && facts.processHeight > 0) {
            json.put("process_resolution", "${facts.processWidth}x${facts.processHeight}")
        } else {
            json.put("process_resolution", JSONObject.NULL)
        }
        // 🔴 두 치수의 종횡비를 **기계로** 대조한다. 문장으로만 두면 어긋난 날 아무 일도 없다.
        if (analysis != null && facts.processWidth > 0 && facts.processHeight > 0 &&
            analysis.width > 0 && analysis.height > 0
        ) {
            val ar = analysis.width.toDouble() / analysis.height.toDouble()
            val pr = facts.processWidth.toDouble() / facts.processHeight.toDouble()
            json.put("analysis_aspect", ar)
            json.put("process_aspect", pr)
            json.put("aspect_matches", kotlin.math.abs(ar - pr) <= ASPECT_TOLERANCE)
            json.put("aspect_tolerance", ASPECT_TOLERANCE)
            json.put(
                "aspect_note",
                "⚠ aspect_matches가 false면 두 use case의 **시야(crop)가 다르다**는 뜻이고 " +
                    "이 매핑은 그만큼 틀린다 — 그 crop을 알려 주는 런타임 값이 없어 앱이 " +
                    "보정할 수 없다. 그 런의 박스 위치를 화면 기준으로 인용하지 말 것. " +
                    "⚠ aspect_tolerance는 **우리가 선언한 검사 조건이지 계약값이 아니다**"
            )
        }
        // 🔴 번호는 DetectGeometryCheck의 KDoc 목록·인라인 주석과 **같아야 한다**(세 자리).
        json.put(
            "selfcheck",
            "detect.geometry(DetectGeometryCheck)의 **검사 5**가 이 매핑을 태운다 — " +
                "센서 프레임 전체 (0,0,srcW,srcH)가 NDC 전체 (-1,-1,1,1)로 가는지. " +
                "🔴 그 검사는 **자기 일관성까지**이고 flip_y의 참·거짓도, 호출부가 잘못된 " +
                "처리 해상도를 넘기는 실수도 잡지 못한다(process 치수가 대수적으로 " +
                "약분된다) — 후자를 관측하는 것은 이 블록의 " +
                "analysis_resolution/process_resolution/aspect_matches뿐이다"
        )
        return json
    }

    /** ④ H칸의 정책값과 그 출처. 🔴 **정책값 넷은 전부 임의 측정값이다.** */
    private fun buildOverlaySmoothing(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        json.put("stage", "④ 좌표 평활·hold (버짓 H칸)")
        json.put("cpu_column", "stage_h_ms")
        json.put("pipeline_stage_token", "stage4_smoothing")
        json.put("scope", RenderArm.OVERLAY_STAGE_H_SCOPE)
        json.put("hold_frames", RenderArm.OVERLAY_HOLD_FRAMES_MEASUREMENT_VALUE)
        json.put("match_iou", RenderArm.OVERLAY_MATCH_IOU_MEASUREMENT_VALUE.toDouble())
        json.put("iir_alpha", RenderArm.OVERLAY_IIR_ALPHA_MEASUREMENT_VALUE.toDouble())
        json.put("box_cap", RenderArm.OVERLAY_BOX_CAP_MEASUREMENT_VALUE)
        // 🔴 지어낸 계약값을 조용히 굳히지 않기 위한 문장이다. 지우지 말 것.
        json.put("provenance", RenderArm.OVERLAY_SMOOTHING_PROVENANCE)
        json.put("hold_cadence_note", RenderArm.OVERLAY_HOLD_CADENCE_NOTE)
        json.put("no_flicker_design", RenderArm.OVERLAY_NO_FLICKER_DESIGN)
        json.put(
            "discard_condition",
            "match_iou 이상인 측정이 hold_frames 프레임 **연속으로** 없으면 폐기한다. " +
                "🔴 **TTL은 새 게시가 왔을 때만 다시 찬다** — 같은 스냅샷을 다시 보는 " +
                "프레임에서는 깎인다(게시 슬롯은 새 게시가 올 때까지 직전 스냅샷을 " +
                "보존하므로, 프레임마다 재충전하면 탐지가 멈춰도 낡은 박스가 무한히 " +
                "그려진다). 게시 주기가 실측 ≈9프레임이고 hold가 " +
                "${RenderArm.OVERLAY_HOLD_FRAMES_MEASUREMENT_VALUE}프레임이므로 **탐지가 " +
                "정상인 동안은 만료되지 않고**, 사라지는 것은 새 게시에서 그 박스가 빠졌거나 " +
                "게시가 끊긴 경우다(no_flicker_design 참고). " +
                "🔴 연결은 **같은 클래스끼리만** 한다(사람과 계단이 겹쳤을 때 색이 서로 " +
                "뒤바뀌는 것을 막는다)"
        )
        json.put(
            "latency_note",
            "⚠ **H는 render_latency_ms를 키운다** — GL 스레드에서 도는 CPU 구간이고 " +
                "onDrawFrame 안에 있으므로 그렇다. **예상된 결과이며 결함이 아니다.** " +
                "그 증분은 짝 arm(detect_cpu / detect_cpu_1q)의 같은 열과 나란히 놓아야 " +
                "보이고, 오버레이 GPU 패스(stage_i_ms)와 **다른 물리량**이다"
        )
        val s = facts.overlaySmoothing
        if (s == null) {
            json.put("run_facts", JSONObject.NULL)
        } else {
            json.put(
                "run_facts",
                JSONObject()
                    .put("tracks_created", s.tracksCreated)
                    .put("tracks_expired", s.tracksExpired)
                    .put("dropped_over_cap", s.droppedOverCap)
                    .put("map_failed_frames", s.mapFailedFrames)
                    .put(
                        "note",
                        "🔴 **관측값이지 판정이 아니다.** tracks_expired가 0이 아닌 것은 " +
                            "결함일 수도 있고 '장면에서 위험물이 실제로 사라졌다'일 수도 " +
                            "있는데, 가르려면 정답 라벨이 필요하다(하네스의 " +
                            "safety_regression이 evaluated=false인 이유와 같다). " +
                            "🔴 map_failed_frames가 0이 아니면 그 프레임들은 좌표를 만들 " +
                            "치수가 없어(FBO가 아직 없거나 분석 치수가 0) **새 게시를 " +
                            "소비하지 못했다** — 값을 지어내지 않고 다음 프레임에 다시 " +
                            "시도한다. 그 프레임에도 hold 중인 트랙은 그대로 그려지므로 " +
                            "overlay_boxes가 반드시 0인 것은 아니다(런 앞자락에서는 트랙이 " +
                            "없어 0이다). ⚠ 이 수가 크면 그 런의 앞자락에서 게시가 여러 번 " +
                            "버려졌다는 뜻이고, t_overlay_source_ns가 그만큼 낡은 값에 " +
                            "머문다"
                    )
            )
        }
        return json
    }

    /** ③→④ 게시의 사실. 🔴 **역전·상한 초과를 조용히 버리지 않았다는 증거다.** */
    private fun buildOverlayPublish(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        json.put("site", "DetectPipeline.infer — gNs 확정 + detect.csv 행 기록 뒤 (parity와 같은 자리)")
        json.put("allocation_note", RenderArm.OVERLAY_PUBLISH_ALLOCATION_NOTE)
        json.put(
            "clock",
            "t_overlay_source_ns = SystemClock.elapsedRealtimeNanos()(CLOCK_BOOTTIME)이며 " +
                "frames.csv의 t_recv_ns·t_render_start_ns와 **같은 시계**다. 시각은 슬롯 교체 " +
                "**직전에** 찍고, GL 스레드는 t_render_start_ns를 찍기 **전에** 읽는다 — " +
                "그래서 어떤 프레임도 자기 렌더 시작보다 미래에 게시된 결과를 쓰지 않는다"
        )
        val p = facts.overlayPublish
        if (p == null) {
            json.put("run_facts", JSONObject.NULL)
            return json
        }
        json.put(
            "run_facts",
            JSONObject()
                .put("enabled", p.enabled)
                .put("publish_count", p.publishCount)
                .put("boxes_published", p.boxesPublished)
                .put(
                    "boxes_published_note",
                    "🔴 **게시된 박스의 런 총계이고 그린 박스 수가 아니다** — hold 때문에 한 " +
                        "게시가 여러 표시 프레임에 걸쳐 그려진다. 프레임당 개수는 " +
                        "frames.csv의 overlay_boxes다"
                )
        )
        // 🔴 버린 것의 개수는 **overlay 블록 최상위**에 있다(putOverlayDiscards) —
        //    여기 사본을 만들지 않는다.
        return json
    }

    /** ④ 오버레이 CSV 열 3개의 규약. 🔴 **열의 뜻을 로그 자체에 남긴다.** */
    private fun buildOverlayCsvColumns(): JSONObject = JSONObject()
        .put(
            "stage_h_ms",
            "④ 좌표 평활·hold의 구간 길이. 🔴 **CPU 벽시계**(GL 스레드)이므로 gpu_sum_ms에도 " +
                "stage_d_total_ms에도 들어가지 않는다. ms **소수 6자리**로 쓴다 — 소수 1자리로 " +
                "쓰면 싼 샘플이 0.0이 되어 하한 가드(> 0)가 그 샘플만 골라 폐기한다"
        )
        .put(
            "overlay_boxes",
            "🔴 **그 프레임에 실제로 그린 박스 수. `0`은 정상값이고 `-1`만 '기록 안 함'이다.** " +
                "session.json의 overlay.box_count와 **다른 값이다**(그쪽은 선언된 조건이고 " +
                "이 arm에서는 null이다)"
        )
        .put(
            "t_overlay_source_ns",
            "그 프레임이 쓴 탐지 결과의 게시 시각(CLOCK_BOOTTIME). 🔴 **박스가 0개(빈 결과)여도 " +
                "적는다** — `-1`은 **첫 추론 완료 전**만이다. 신선도" +
                "(t_render_start_ns − 이 값)는 **PC가 계산한다** — 유도값을 앱이 저장하지 않는다"
        )

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
        // 🔴 `_prof`와 **같은 자리·같은 취지**의 인용 금지 문장이다(사유만 다르다 — 덤프 I/O).
        if (facts.arm.usesDetectParityDump) {
            json.put("parity_arm_not_quotable", RenderArm.DETECT_PARITY_NOT_QUOTABLE)
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
        // ③ 기하 왕복 자체검사. 🔴 실패하면 런이 시작되지 않으므로 이 블록이 있는 런은
        //    통과한 런이다 — 그래도 관측값(max|d|)을 싣는다.
        facts.detectGeometry?.let { json.put("geometry_selfcheck", buildDetectGeometry(it)) }
        if (run != null) {
            json.put("run", buildDetectRun(run))
        }
        // ③ 대조 덤프. 덤프 arm이 아니면 null이라 키 자체가 나가지 않는다.
        facts.detectParity?.let { json.put("parity", buildDetectParity(it)) }

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
        json.put("preprocess_assumptions", buildDetectPreprocess(facts.detectRotation))
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
        // 🔴 **런타임이 스스로 말한 버전.** 위 `version`은 build.gradle.kts에 우리가 적은
        //    문자열이라 실제로 로드된 .so가 그 버전이라는 보장이 아니다. PC 대조에서
        //    "같은 ORT 버전인데 값이 다르면 빌드/플랫폼 차이"라고 말하려면 그 전제가
        //    **기계로 확인된 것**이어야 한다. 못 읽었으면 null이고 지어내지 않는다.
        .put("version_runtime", report.ortVersionRuntime ?: JSONObject.NULL)
        .put("version_mismatch", report.ortVersionMismatch ?: JSONObject.NULL)
        .put(
            "version_note",
            "`version`은 gradle 좌표에 **선언한** 값이고 `version_runtime`은 " +
                "OrtEnvironment.getVersion()이 **말한** 값이다. 인용은 " +
                "**version_runtime을 우선**한다 — 둘이 다르면 version_mismatch가 문장으로 " +
                "말하고, 그 자체가 발견이다. (모델 해시를 metadata.json의 선언값 대신 로드 " +
                "바이트에서 직접 계산하는 것과 같은 논거다)"
        )
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
    private fun buildDetectPreprocess(rotation: DetectRotationFacts?): JSONObject = JSONObject()
        .put("letterbox_align", DetectContract.LETTERBOX_ALIGN_ASSUMPTION)
        .put("letterbox_align_value", DetectContract.LETTERBOX_ALIGN)
        .put("pad_value", DetectContract.PAD_VALUE_ASSUMPTION)
        .put("pad_value_u8", DetectContract.PAD_VALUE_U8)
        .put("resize_interpolation", DetectContract.RESIZE_INTERPOLATION_ASSUMPTION)
        .put("yuv_to_rgb", DetectContract.YUV_TO_RGB_ASSUMPTION)
        // 🔴 회전은 이제 **적용된다**(규약 §4). 문장은 arm이 고른다 — 대조군
        //    (detect_cpu_norot)만 "의도적으로 적용하지 않았다"는 쪽이다.
        //    ⚠ 이 문장이 말하는 것은 E의 **값**이 바뀐다는 것이지 E의 **정의**가 아니다.
        .put("rotation", rotation?.note ?: JSONObject.NULL)
        .put("rotation_site", rotation?.site ?: JSONObject.NULL)
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
        // 🔴 **회전 후 치수로** 계산한다 — letterbox가 실제로 그 치수에서 나온다(규약 §5).
        //    ⚠ 입력이 정사각이면 90° 스왑이 이 값을 바꾸지 않지만, 그건 이 모델의 우연이지
        //      규칙이 아니다. 치수가 직사각으로 바뀌는 날 조용히 틀리지 않게 여기서 맞춰 둔다.
        val rotation = facts.detectRotation
        val srcW = if (rotation != null && rotation.rotatedWidth > 0) {
            rotation.rotatedWidth
        } else {
            analysis.width
        }
        val srcH = if (rotation != null && rotation.rotatedHeight > 0) {
            rotation.rotatedHeight
        } else {
            analysis.height
        }
        val fraction = DetectContract.paddingPixelFraction(
            srcW,
            srcH,
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
        val rotation = facts.detectRotation
        val rotated = if (rotation != null && rotation.rotatedWidth > 0) {
            " → 회전 후 ${rotation.rotatedWidth}x${rotation.rotatedHeight}" +
                "(rotation_degrees=${rotation.degrees}, applied=${rotation.applied})"
        } else {
            ""
        }
        return "1 − (내용 픽셀 수 / 입력 픽셀 수). 소스 " +
            "${analysis.width}x${analysis.height}(**camera_analysis_actual** — Preview가 " +
            "아니라 분석 use case의 값이며 **센서 방향**이다)$rotated → 입력 " +
            "${shape[3]}x${shape[2]}(그래프에서 읽은 값)로 **계산한 값이며 상수 복사가 " +
            "아니다.** 🔴 **letterbox는 회전 후 치수에서 나온다**(규약 §5) — 이 값도 그 " +
            "치수로 계산했다. ⚠ 입력이 정사각이면 90° 스왑이 이 값을 바꾸지 않지만 그건 " +
            "이 모델의 우연이지 규칙이 아니다. 🔴 **F의 일부는 회색 패딩을 미는 " +
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
        // ⚠ 이것은 **마지막 프레임의 관측값**이다(카메라 소스가 들고 있는 값).
        //    런이 실제로 **쓴** 각은 아래 rotation_degrees_locked다 — 규약 §4-3.
        json.put("rotation_degrees", analysis.rotationDegrees)
        putAnalysisRotation(json, facts.detectRotation)
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
                "다른 해상도를 줄 수 있으므로 섞어 읽지 말 것. 🔴 이 값은 **센서 방향**이고 " +
                "letterbox는 **회전 후 치수**에서 나온다(규약 §5) — 90/270°면 아래 " +
                "rotated_resolution과 폭·높이가 바뀐다"
        )
        return json
    }

    /**
     * ③ 회전의 사실을 `detect.input` 옆에 붙인다(규약 §4). 🔴 **회전각은 첫 분석
     * 프레임에서 잠근다**(§4-3) — 런 도중 바뀌면 letterbox 기하가 갈려 E와 박스 좌표가
     * 한 런 안에서 두 뜻을 갖는다. 앱은 **잠근 값을 계속 쓰면서 센다.**
     */
    private fun putAnalysisRotation(json: JSONObject, rotation: DetectRotationFacts?) {
        if (rotation == null) {
            json.put("rotation_applied", JSONObject.NULL)
            json.put("rotation_site", JSONObject.NULL)
            json.put("rotation_locked", JSONObject.NULL)
            json.put("rotation_changed_frames", JSONObject.NULL)
            json.put(
                "rotation_note",
                "이 arm은 ③ 전처리를 돌리지 않으므로(detect_bind_only) 회전 사실이 없다 — " +
                    "값을 지어내지 않는다"
            )
            return
        }
        json.put("rotation_degrees_locked", if (rotation.locked) rotation.degrees else JSONObject.NULL)
        json.put("rotation_applied", rotation.applied)
        json.put("rotation_site", rotation.site)
        json.put("rotation_locked", rotation.locked)
        json.put("rotation_changed_frames", rotation.changedFrames)
        json.put(
            "rotated_resolution",
            if (rotation.rotatedWidth > 0) {
                "${rotation.rotatedWidth}x${rotation.rotatedHeight}"
            } else {
                JSONObject.NULL
            }
        )
        json.put("rotation_note", rotation.note)
        json.put(
            "rotation_lock_meaning",
            "🔴 **회전각은 첫 분석 프레임에서 잠근다**(규약 §4-3). 이후 다른 값이 오면 " +
                "**잠근 값을 계속 쓰면서 rotation_changed_frames를 센다** — 조용히 따라가지 " +
                "않는다. 따라가면 letterbox 기하가 런 중간에 갈려 **E와 박스 좌표가 한 런 " +
                "안에서 두 뜻을 갖고**, 그건 그 런의 숫자를 통째로 못 쓰게 만든다. " +
                "⚠ **rotation_changed_frames != 0인 런은 승격 대상이 아니다.** " +
                "⚠ **rotation_degrees=0과 rotation_applied=false는 다른 사실이다**(§4-2) — " +
                "기기가 0°를 주면 회전은 적용됐는데 항등인 것이고 그때도 applied는 true다"
        )
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
                "했는가와 (b) G 비용의 설명 변수까지다. " +
                "🔎 **사유가 하나 줄었다(2026-08-07)** — 회전을 적용하면서 '모델이 옆으로 " +
                "누운 장면을 본다'는 이유는 사라졌다(대조군 arm detect_cpu_norot 제외. " +
                "실제 값은 preprocess_assumptions.rotation과 input.rotation_applied가 말한다). " +
                "🔴 **그래도 결론은 그대로다.** 남은 이유: **정답 라벨이 없고 골든 샘플" +
                "(INTERFACES.md §A-6)도 오지 않았다** — 그러니 mAP·누락률은 여전히 말할 수 " +
                "없다. 그리고 전처리 가정 넷이 아직 미확정이다" +
                "(preprocess_assumptions.used_this_round_note). " +
                "⚠ **탐지가 돈다는 것과 안전을 평가했다는 것은 다른 사실이다** — " +
                "안전 회귀는 여전히 evaluated=false다"
        )
        // 🔴 역전 박스(규약 §5-3) — **거른 개수가 아니라 센 개수다.** detect.csv에는 열을
        //    더하지 않는다(행 하나 = 추론 1회인데 이 값은 런 전체의 성질에 가깝고,
        //    좌표 표본은 CSV 한 칸에 담을 모양이 아니다).
        json.put("inverted_boxes", run.invertedBoxes)
        val invertedSamples = JSONArray()
        for (b in run.invertedSamples) {
            invertedSamples.put(
                JSONObject()
                    .put("cls", b.cls)
                    .put("conf", b.conf.toDouble())
                    // letterbox 역변환 직후 = ② 회전 후 좌표계
                    .put("rot_x1", b.rotX1.toDouble())
                    .put("rot_y1", b.rotY1.toDouble())
                    .put("rot_x2", b.rotX2.toDouble())
                    .put("rot_y2", b.rotY2.toDouble())
                    // 회전 역변환까지 끝난 값 = ① 센서 좌표계
                    .put("x1", b.x1.toDouble())
                    .put("y1", b.y1.toDouble())
                    .put("x2", b.x2.toDouble())
                    .put("y2", b.y2.toDouble())
            )
        }
        json.put("inverted_box_samples", invertedSamples)
        json.put(
            "inverted_boxes_meaning",
            "🔴 **x2<x1 또는 y2<y1인 박스의 수다. 거른 개수가 아니라 센 개수이며 그 박스는 " +
                "boxes_out 안에 그대로 있다**(규약 §5-3). 거르면 면적 0인 박스가 화면 " +
                "가장자리에 남아 ④가 얇은 선을 그린다 — 사용자에게 보이는 쓰레기다. " +
                "**나오면 그 자체가 결함**이고 조용히 지우면 결함이 숨는다. " +
                "🔴 **좌표 표본을 함께 내는 이유**: 개수만으로는 못 고친다 — 알려진 이슈 34가 " +
                "잡힌 것은 `x1=0.0, x2=-148.75`라는 구체 좌표 덕이었다. 두 좌표계를 같이 " +
                "싣는 이유도 같다 — rot_*가 이미 뒤집혀 있으면 원인은 **모델의 w<0**이고" +
                "(부등호를 보장하는 것은 scale이 아니라 모델이다), rot_*는 멀쩡한데 x/y가 " +
                "뒤집혔으면 원인은 **회전 역변환**이다. " +
                "⚠ 표본은 앞 몇 개만 남긴다 — inverted_boxes와 inverted_box_samples의 크기가 " +
                "다르면 잘린 것이다(총계 쪽이 참이다)"
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
                            "역변환이 반올림에서 어긋나지 않는다. " +
                            "🔴 **`src`는 ② 회전 후 치수다**(센서가 아니다 — 규약 §5). " +
                            "90/270°면 camera_analysis_actual의 해상도와 폭·높이가 바뀌어 " +
                            "있고, 그때 pad_left와 pad_top도 자리를 바꾼다"
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
     * ③ **이식 정확성 대조 덤프**의 사실. 🔴 **덤프가 반쪽이면 반쪽이라고 낸다** —
     * 앱이 맞춰 주지 않는다. 덤프가 모자란 채로 PC가 대조를 돌리면 그 결과의 뜻이 사라진다.
     *
     * 파일 포맷의 출처는 `docs/plans/20260806_detect_parity_dump_format.md` 하나이고, 이
     * 블록은 **그 파일들이 실제로 남았는가**만 말한다(내용의 판정은 `scripts/detect_parity.py`가 한다).
     */
    private fun buildDetectParity(parity: DetectParityResult): JSONObject {
        val json = JSONObject()
        json.put("enabled", true)
        json.put("not_quotable", RenderArm.DETECT_PARITY_NOT_QUOTABLE)
        json.put("format", DetectParityDumper.FORMAT)
        json.put("byte_order", DetectParityDumper.BYTE_ORDER)
        json.put("dir", parity.dirName ?: JSONObject.NULL)
        json.put("manifest", DetectParityDumper.MANIFEST_NAME)
        json.put("manifest_written", parity.manifestWritten)
        json.put("samples_requested", parity.requestedSamples)
        json.put("samples_captured", parity.capturedSamples)
        json.put("bytes_written", parity.bytesWritten)
        json.put("move_method", parity.moveMethod)
        val files = JSONArray()
        for (name in parity.files) files.put(name)
        json.put("files", files)
        val failures = JSONArray()
        for (f in parity.failures) failures.put(f)
        json.put("failures", failures)
        json.put(
            "complete",
            parity.manifestWritten &&
                parity.capturedSamples == parity.requestedSamples &&
                parity.failures.isEmpty()
        )
        json.put(
            "sample_count_note",
            "샘플 수 K의 근거는 규약 §9다: 입력 텐서 하나가 4.9MB라 K가 커지면 pull이 " +
                "오래 걸리고, 이식 정확성은 장면 다양성보다 **연산 경로 일치**의 문제라 " +
                "표본이 많을 필요가 없다. ⚠ **통계적 표본이 아니다** — " +
                "'${parity.capturedSamples}장에서 일치했다'를 '항상 일치한다'로 쓰지 말 것"
        )
        json.put(
            "rotation_note",
            "🔴 매니페스트의 source 블록이 **회전을 어떻게 다뤘는지**를 말한다(규약 §4): " +
                "rotation_degrees(첫 프레임에서 **잠근** 값) · rotation_applied · " +
                "rotation_site · rotated_width/height · rotation_locked · " +
                "rotation_changed_frames. " +
                "🔴 **`sample_NN_src.yuv`는 센서 방향 원본 그대로다**(회전 전 — 규약 §4-5). " +
                "앱이 회전한 결과를 덤프하면 PC는 그 회전을 검사할 수 없고 **E 대조가 회전을 " +
                "건너뛴다.** PC가 rotation_degrees·rotation_site를 읽어 **같은 회전을 같은 " +
                "자리에서 자기 힘으로 재현**해야 E 대조가 YUV→회전→letterbox→RGB→/255→NCHW " +
                "**전체**를 덮는다. " +
                "⚠ **rotation_applied=false의 뜻이 둘이다**(§4-2): site=none이면 **의도된 " +
                "대조군**(detect_cpu_norot)이고, site=preprocess_*면 **모순**이라 PC가 죽는다. " +
                "알려진 이슈 29(미구현)는 이 라운드에서 닫혔다 — false가 보이면 대조군을 " +
                "의심할 것이지 미구현을 의심할 것이 아니다. " +
                "🔴 회전이 붙어도 이 덤프로 '모델이 잘 맞힌다/못 맞힌다'를 말하면 안 된다 — " +
                "정답 라벨도 골든 샘플(INTERFACES.md §A-6)도 여전히 없다"
        )
        json.put(
            "scope_note",
            "이 덤프가 답하는 것: 폰의 E가 같은 소스에서 PC와 같은 텐서를 만드는가 / " +
                "폰의 F가 **같은 입력 텐서**에서 PC ORT와 같은 출력을 내는가 / 폰의 G가 " +
                "**같은 출력 텐서**에서 같은 박스를 내는가 / EP를 바꾸면 답이 달라지는가. " +
                "🔴 답하지 못하는 것: **모델이 옳은가**(mAP·누락률 — 정답 라벨이 필요하다), " +
                "상류 PyTorch와 같은가(상류 레포가 이 저장소에 없다), 실제 야간 장면에서의 성능"
        )
        json.put(
            "boxes_coordinate_space",
            "매니페스트의 boxes는 **① 센서 좌표계**다(규약 §5-2) — src.width/height와 **같은 " +
                "공간**이며 '바로 선' 회전 후 공간이 아니다. ⚠ **두 공간의 좌표를 동시에 " +
                "싣지 않는다**(두 사본은 반드시 어긋나는 날이 온다). " +
                "사슬은 다섯 칸이다: ① 센서 → **회전** → ② 회전 후 → **letterbox** → " +
                "③ 640 → 모델 → ④ 출력 텐서 → **역변환** → ① 센서. " +
                "후처리 순서는 상류 README의 네 단계 + 회전 하나이고 PC 재구현도 **반드시 이 " +
                "순서 그대로**여야 한다: conf 필터 → cxcywh→xyxy → **클래스별 NMS(letterbox " +
                "640 좌표계)** → letterbox 역변환(② 회전 후) → **회전 역변환(① 센서)**. " +
                "🔴 **회전 역변환은 NMS 뒤다.** 아핀이고 90° 배수라 순수 좌표 치환이지만 " +
                "그래도 앞으로 옮기지 않는다 — *'아핀이니 앞에 둬도 된다'*는 논거가 **바로 " +
                "클램프 사고를 낳았다.** " +
                "⚠ **회전 역변환의 두 규칙(규약 §5-1이 축 대응표와 함께 정한다).** " +
                "(1) **축 대응 + 순서 동반 이동**: 회전이 뒤집는 축에서는 두 끝점의 순서까지 " +
                "함께 옮긴다(`x1' = N − x2`). min/max가 아니다 — min/max로 뭉개면 모델이 낸 " +
                "역전(w<0)까지 조용히 고쳐져 inverted_boxes가 아무것도 못 잡는다. 이것을 " +
                "안 하면 90/270°에서 **모든 박스가 역전**으로 나온다. " +
                "(2) 🔴 **모서리(연속) 좌표 규약**: 반사식이 `N − v`이고 `(N−1) − v`가 " +
                "아니다. 박스 좌표는 letterbox 역변환이 낸 연속 좌표라 `[0, N]`을 채운다 — " +
                "전처리 샘플 맵(픽셀 인덱스, `(N−1) − v`)과 **규약이 다르고**, 한 식을 두 " +
                "곳에 쓰면 프레임 전체가 원점 쪽으로 **정확히 1px** 밀린다. " +
                "폰은 `Rotation.inverseBox`(모서리)와 `toSensorX/Y`(인덱스)를 **따로** 두고 " +
                "geometry_selfcheck가 프레임 전체 박스로 그 자리를 감시한다. " +
                "🔴 **원본 경계 클램프는 없다(2026-08-07 제거).** 상류 명세에 없는 5번째 " +
                "단계였고, 게다가 비대칭이라(x1/y1은 아래만, x2/y2는 위만) 탐지가 letterbox " +
                "패딩 안에만 있으면 **x2 < x1인 역전 박스**가 나왔다. " +
                "⚠ **그 이전 빌드가 만든 덤프와 지금 PC 재구현을 대조하면 경계에 걸친 박스에서 " +
                "차이가 난다** — 그것은 이식 결함이 아니라 **코드 버전 차이**다. " +
                "따라서 좌표는 **화면 밖으로 나갈 수 있다**(음수이거나 폭·높이를 넘을 수 있다). " +
                "소비자가 in-frame을 가정하면 안 된다"
        )
        return json
    }

    /**
     * ③ 기하 왕복 자체검사(`DetectGeometryCheck`). 🔴 **실패하면 앱이 런을 시작하지
     * 않으므로** 이 블록이 있는 런은 통과한 런이다 — 그래도 **관측값을 싣는다.**
     * 통과/실패보다 관측값이 먼저다(규약 §7).
     */
    private fun buildDetectGeometry(check: DetectGeometryCheck.Result): JSONObject {
        val json = JSONObject()
        json.put("cases", check.cases)
        json.put("max_abs_delta_px", check.maxAbsDelta.toDouble())
        json.put("tolerance_px", check.tolerancePx.toDouble())
        json.put("passed", check.passed)
        val failures = JSONArray()
        for (f in check.failures) failures.put(f)
        json.put("failures", failures)
        json.put(
            "what_it_checks",
            "letterbox 640 좌표의 박스를 **역변환(letterbox 역 → 회전 역)으로 센서까지 " +
                "보냈다가 정변환으로 되돌려** 원래 값으로 돌아오는지 본다. 케이스는 " +
                "**회전 4각 × 소스 치수 6종(패딩이 홀수로 남는 것 포함) × 박스 8종**이고, " +
                "박스에는 **letterbox 패딩 안에만 있는 것 · 프레임 경계를 넘는 것 · 1px · " +
                "코너 · 역전 박스**가 들어 있다. " +
                "🔴 **프로덕션 함수를 그대로 태운다 — 사본을 만들지 않는다.** 검사용으로 같은 " +
                "공식을 다시 적으면 같은 오타를 두 번 적고 통과한다. " +
                "🔴 **왕복만으로는 못 잡는 것이 둘 있어 표 대조를 따로 붙였다**(정·역이 같은 " +
                "방향/같은 규약으로 같이 틀리면 왕복은 통과한다): (a) 회전 **방향**" +
                "(시계/반시계) — 회전 후 (0,0)이 센서의 어디인가, (b) **모서리 규약** — " +
                "회전 후 프레임 전체 박스가 센서 프레임 전체로 가는가. " +
                "🔴 (b)가 잡는 것은 **정확히 1px**이다: 샘플 맵은 픽셀 인덱스라 반사가 " +
                "`(N−1)−v`이고 박스는 연속 모서리 좌표라 `N−v`인데, 한 함수를 두 규약에 쓰면 " +
                "프레임 전체가 원점 쪽으로 1px 밀린다"
        )
        json.put(
            "why_here",
            "🔴 **런을 거부하는 자리다** — '임계를 숫자로 못 읽으면 후처리를 시작하지 " +
                "않는다'와 **같은 자리·같은 취지**다. 기하가 틀린 채로 돈 런은 E가 엉뚱한 " +
                "픽셀을 읽고 G의 박스가 통째로 어긋나는데 **둘 다 그럴듯한 숫자로 나온다.** " +
                "11분을 찍고 나서 아는 것보다 시작 전에 거부하는 쪽이 싸다"
        )
        json.put(
            "tolerance_provenance",
            "⚠ **우리가 선언한 검사 조건이지 계약값이 아니다**(SAMPLE_COUNT와 같은 부류). " +
                "🔴 상류가 자기 대조에 쓴 바(0.0001px)와 **다른 검사다** — 그쪽은 " +
                "PyTorch↔ONNX 값 대조이고 이쪽은 우리 기하의 왕복이다. 그 바를 여기 " +
                "끌어오지 않는다"
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
            RenderArm.DRAGO_CLAHE_FUSED_1Q -> RenderArm.DRAGO_CLAHE_CHAIN
            RenderArm.DRAGO_CLAHE_FUSED_BF_1Q -> RenderArm.DRAGO_CLAHE_CHAIN_BF
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
            RenderArm.DRAGO_CLAHE_FUSED_1Q -> declaredCounts(RenderArm.DRAGO_CLAHE_FUSED)
            RenderArm.DRAGO_CLAHE_FUSED_BF_1Q -> declaredCounts(RenderArm.DRAGO_CLAHE_FUSED_BF)
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
                facts.arm.usesDynamicHighlightBoxes ->
                    "패스1·2·4는 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4), " +
                        "패스3(④ 오버레이)은 glDrawArrays(" +
                        "${HighlightOverlay.GL_PRIMITIVE_NAME}) **최대 1회**다 — 정점 수가 " +
                        "**프레임마다 다르다**(그 프레임에 그린 박스 수 × 박스당 " +
                        "${HighlightOverlay.VERTS_PER_BOX}정점, 상한 " +
                        "${HighlightOverlay.MAX_BOX_COUNT * HighlightOverlay.VERTS_PER_BOX}). " +
                        "🔴 **박스가 0개인 프레임에서는 드로우콜을 내지 않는다**(0은 정상값이다) " +
                        "— 그때 패스3은 바인드·뷰포트뿐이다. 개수는 frames.csv의 overlay_boxes"
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
                            "④ 이중 스트로크 박스 " +
                                (if (facts.arm.usesDynamicHighlightBoxes) {
                                    "**프레임마다 다른 개수**(frames.csv의 overlay_boxes)"
                                } else {
                                    "${facts.arm.highlightBoxCount}개"
                                }) +
                                "(검정 밑선 + 대비색 본선, 비채움). " +
                                "얇은 사각형 스트로크 quad를 " +
                                "${HighlightOverlay.GL_PRIMITIVE_NAME} **드로우콜 1회**로 " +
                                "그린다. 두께는 처리 해상도의 짧은 변에서 계산한다" +
                                "(720p 기준 ${RenderArm.HIGHLIGHT_STROKE_PX_AT_720P}px). " +
                                (if (facts.arm.usesDynamicHighlightBoxes) {
                                    "🔴 박스는 **③ 탐지 결과**를 H칸(좌표 평활·hold)에 태운 " +
                                        "것이며 정적 더미가 아니다 — 평활 정책과 좌표 사슬은 "
                                } else {
                                    "박스는 정적 더미이고 ③ 결과가 아니다 — 자세한 것은 "
                                }) +
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
                        RenderArm.DRAGO_CLAHE_FUSED_1Q -> FUSED_STAGE2_PASSES
                        RenderArm.DRAGO_CLAHE_FUSED_BF_1Q ->
                            FUSED_STAGE2_PASSES + bilateralPass("FBO_A (처리 해상도. 핑퐁)")
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
