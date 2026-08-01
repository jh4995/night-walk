package com.bammasil.poc.log

import com.bammasil.poc.gl.GlCapabilities
import com.bammasil.poc.gl.GlCapabilitiesProbe
import com.bammasil.poc.gl.GpuTimerReport
import com.bammasil.poc.gl.GpuTimerRing
import com.bammasil.poc.gl.LabGlsl
import com.bammasil.poc.gl.RenderArm
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
    /** 이 런의 GPU timer 실적. 계측 arm이 아니면 전부 0이고 `instrumented=false`다. */
    val gpuTimer: GpuTimerReport,
)

object SessionWriter {

    /**
     * `lib/frame_log.py`의 `SCHEMA_VERSION`. 열이 늘면 양쪽을 함께 올린다.
     *
     * v3에서 D 계열 하위 열(`stage_d_analyze_ms` / `stage_d_build_ms` / `stage_d_apply_ms` /
     * `stage_d_denoise_ms`)이 들어왔고, 이 앱은 그중 앞의 셋을 ② 컴퓨트 arm 세 개
     * (`drago` · `clahe_gamma` · `agcwd`)에서 낸다. `stage_d_denoise_ms`는 아직 아무 arm도
     * 내지 않는다 — bilateral(`+bf`)이 붙는 라운드에서 채운다.
     */
    const val SCHEMA_VERSION = 3

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

        root.put("frames_emitted", facts.framesEmitted)
        // ⚠ 0으로 채우면 "드롭 없음"이라는 거짓 주장이 된다. 모르므로 null이다.
        root.put("camera_frames_offered", JSONObject.NULL)
        root.put("frames_dropped", JSONObject.NULL)
        root.put(
            "drop_accounting_note",
            "표시 경로 2-C에는 ImageProxy가 없어 버려진 프레임 수를 셀 수 없다. " +
                "frames.csv의 dropped_since_last는 전부 -1이며, camera_frames_offered/" +
                "frames_dropped는 0이 아니라 null이다(0은 '드롭 없음'이라는 적극적 주장이다). " +
                "아래 surface_frames_available은 카메라 출력 수가 아니라 우리 SurfaceTexture " +
                "큐에 도착한 수이므로 드롭의 하한 단서일 뿐이다"
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
        json.put("method", GpuTimerRing.METHOD)
        json.put("target_enum", "GL_TIME_ELAPSED_EXT=0x88BF, GL_GPU_DISJOINT_EXT=0x8FBB")
        json.put("ring_depth_frames", GpuTimerRing.RING_DEPTH)
        // arm마다 패스 수가 다르다(3패스 골격 3개 / drago 5개) → 상수가 아니라 실적에서 낸다.
        json.put("queries_per_frame", report.passesPerFrame)
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

        if (facts.arm.usesComputeStage2) {
            json.put(
                "compute_pass_note",
                "이 arm의 패스2·패스3은 glDispatchCompute다. 컴퓨트는 타일러를 거치지 않으므로 " +
                    "아래 attribution_note의 '렌더패스 병합' 갈래가 그대로 적용되지는 않는다. " +
                    "대신 SSBO 배리어(glMemoryBarrier)의 실제 대기가 어느 query에 담기는지는 " +
                    "드라이버가 정한다 — 배리어를 **소비하는 쪽 패스의 맨 앞**에 두었으므로 " +
                    "대기 비용은 소비자(패스3·패스4) 쪽으로 청구되도록 의도했다. 그것이 " +
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
                "기준이 아니게 된다. stage_i_ms는 ④ 오버레이 arm이 생길 때 붙는다"
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
            RenderArm.BLIT_2PASS -> {
                json.put("algorithm", "copy")
                json.put(
                    "note",
                    "3패스 골격은 다 돌지만 ② 자리는 단순 복사다. 여기서 나오는 비용은 " +
                        "②의 비용이 아니라 **골격 자체(오프스크린 왕복)의 비용**이다"
                )
            }
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
        }
        return json
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
            if (facts.arm.usesComputeStage2) {
                "그리기 패스는 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4), " +
                    "통계 패스 2개는 glDispatchCompute"
            } else {
                "패스당 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)"
            }
        )
        json.put(
            "shader_language",
            if (facts.arm.usesComputeStage2) {
                "GLSL ES 1.00 (패스1·패스5) + GLSL ES 3.10 (패스2·3 컴퓨트, 패스4 적용). " +
                    "적용 패스가 SSBO를 읽어야 해서 310으로 올렸고, ESSL은 한 프로그램 안에서 " +
                    "버전을 섞지 못하므로 그 패스의 정점 셰이더도 310이다"
            } else {
                "GLSL ES 1.00 (전 패스 공통)"
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
                if (facts.arm.usesComputeStage2) {
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
                        else -> listOf(
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
                            Triple(
                                "stage2_drago_apply",
                                "FBO_B (처리 해상도)",
                                "Drago 톤맵 적용 + 감마 (uSrcGamma / uOutGamma / uSaturation)",
                            ),
                        )
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
                            if (i < columns.size) columns[i] else JSONObject.NULL
                        )
                        .put("instrumented", instrumented)
                )
            }
        }
        json.put("passes", passes)
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
