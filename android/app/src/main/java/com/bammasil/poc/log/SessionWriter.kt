package com.bammasil.poc.log

import com.bammasil.poc.gl.GlCapabilities
import com.bammasil.poc.gl.GlCapabilitiesProbe
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
)

object SessionWriter {

    /** `lib/frame_log.py`의 `SCHEMA_VERSION`. 열이 늘면 양쪽을 함께 올린다. */
    const val SCHEMA_VERSION = 2

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
                "틀린다 — 실제 GPU 비용은 timer query가 필요하고 이번 범위가 아니다. " +
                "반면 output_interval_ms는 swapBuffers가 다음 사이클 앞에서 블록하므로 " +
                "실제 표시 주기(=프레임타임)가 된다"
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
     * ⚠ **`supported`의 의미를 이 앱에서는 "이 런이 GPU 패스 시간 표본을 낼 수 있는가"로
     * 쓴다.** "확장이 존재하는가"는 `extension_present`로 따로 낸다.
     *
     * 이유: 하네스의 모순 검사(`gpu_timer_contradicted`)는 "true라고 선언했는데 유효 표본이
     * 0"인 경우를 잡는 장치다. 이번 라운드는 계측 자체가 없으므로 확장 존재만으로 true를
     * 선언하면, 그 경고는 **다음 라운드에 진짜 계측 실패를 잡아야 할 검출기를 미리 태워
     * 무디게 만든다**(늘 켜져 있는 경고는 아무도 안 본다). 확장 유무라는 사실은 위 gl
     * 블록과 여기 extension_present에 그대로 남으므로 숨기는 것이 아니다.
     */
    private fun buildGpuTimer(facts: SessionFacts): JSONObject {
        val present = facts.gl?.disjointTimerQuery ?: GlCapabilitiesProbe.UNKNOWN
        return JSONObject()
            .put("supported", false)
            .put("extension", GlCapabilitiesProbe.EXT_DISJOINT_TIMER_QUERY)
            .put("extension_present", present)
            .put("instrumented", false)
            .put(
                "note",
                "supported = '이 런이 GPU 패스 시간 표본을 낼 수 있는가'. 이번 라운드에는 " +
                    "timer query 계측이 아예 없으므로 false다(확장 유무와 무관). 확장 존재 " +
                    "여부는 extension_present에 실측으로 있다. 따라서 frames.csv에 " +
                    "stage_*_ms / gpu_present_ms 열도 싣지 않았다 — '열이 없다'와 " +
                    "'열은 있는데 전부 -1이다'는 하네스에서 다르게 보고되며, 후자는 " +
                    "'쟀는데 못 얻었다'는 거짓 주장이 된다"
            )
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
                        "**② 비용의 하한**만 본다. CLAHE는 다음 라운드다"
                )
            }
        }
        return json
    }

    private fun buildRender(facts: SessionFacts): JSONObject {
        val json = JSONObject()
        json.put("display_path", "2-C: CameraX Preview -> 우리 SurfaceTexture(OES) -> GL")
        json.put("arm", facts.arm.id)
        json.put("gl_surface_size", "${facts.glSurfaceWidth}x${facts.glSurfaceHeight}")
        json.put("egl_context_client_version", facts.eglContextClientVersion)
        json.put("render_mode", "RENDERMODE_WHEN_DIRTY (onFrameAvailable에서 requestRender)")
        json.put("draw_call", "패스당 glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)")
        json.put("shader_language", "GLSL ES 1.00 (전 패스 공통)")

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
        // 0이 아니면 선언한 pipeline_stages와 실제로 탄 경로가 어긋난 것이다.
        process.put("frames_fell_back_to_passthrough", facts.offscreenFallbackDraws)
        json.put("processing", process)

        val passes = JSONArray()
        if (facts.arm == RenderArm.PASSTHROUGH) {
            passes.put(
                JSONObject()
                    .put("index", 1)
                    .put("name", "oes_to_screen")
                    .put("target", "default framebuffer")
                    .put("shader", "OES 패스스루 (처리 없음)")
                    .put("future_gpu_column", "gpu_present_ms")
            )
        } else {
            passes.put(
                JSONObject()
                    .put("index", 1)
                    .put("name", "oes_to_fbo_a")
                    .put("target", "FBO_A (처리 해상도)")
                    .put("shader", "OES 패스스루 + uTexMatrix")
                    .put("future_gpu_column", "stage_b_ms")
            )
            passes.put(
                JSONObject()
                    .put("index", 2)
                    .put("name", "stage2_slot")
                    .put("target", "FBO_B (처리 해상도)")
                    .put(
                        "shader",
                        if (facts.arm == RenderArm.GAMMA_ONLY) "감마 (uGamma)" else "단순 복사"
                    )
                    .put("future_gpu_column", "stage_d_ms")
            )
            passes.put(
                JSONObject()
                    .put("index", 3)
                    .put("name", "present")
                    .put("target", "default framebuffer (surface 크기)")
                    .put("shader", "단순 복사")
                    .put("future_gpu_column", "gpu_present_ms")
            )
        }
        json.put("passes", passes)
        json.put(
            "pass_boundary_note",
            "패스마다 glBindFramebuffer + glClear를 명시한다. 타일 기반 GPU(Mali)에서 " +
                "드라이버가 렌더패스를 병합하면 다음 라운드의 timer query 귀속이 흐려진다"
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
