package com.bammasil.poc.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.bammasil.poc.detect.DetectOverlayPublisher
import com.bammasil.poc.detect.DetectOverlaySnapshot
import com.bammasil.poc.log.CaptureClockProbe
import com.bammasil.poc.log.CaptureClockVerdict
import com.bammasil.poc.log.ClockProbeSample
import com.bammasil.poc.log.FrameLogRecorder
import com.bammasil.poc.source.FrameTarget
import com.bammasil.poc.source.PreviewTransform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 표시 경로 2-C의 렌더러. arm에 따라 두 경로 중 하나를 탄다([RenderArm]).
 *
 * - [RenderArm.PASSTHROUGH] — 카메라가 채운 OES 텍스처를 화면에 **그대로 blit**하는 1패스.
 *   기존 승격 베이스라인을 재현하는 경로이므로 여기에 새 GL 호출을 끼워 넣지 않는다.
 * - 그 외 — ②를 얹을 자리를 만드는 **3패스 골격**:
 *   ```
 *   패스1  OES  → FBO_A (처리 해상도)      → stage_b_ms
 *   패스2  FBO_A → FBO_B (처리 해상도) ②   → stage_d_ms
 *   패스3  FBO_B → 화면 (surface 크기)     → gpu_present_ms
 *   ```
 *   **패스마다 `glBindFramebuffer` + `glClear`를 명시**한다. 타일 기반 GPU(Mali-G68)에서
 *   드라이버가 렌더패스를 병합하면 timer query 귀속이 흐려진다.
 * - **② 컴퓨트 arm**([RenderArm.usesSingleComputeStage2] — `drago` · `clahe_gamma` · `agcwd`) —
 *   위 골격의 **패스2 자리가 3단으로 벌어진 5패스**:
 *   ```
 *   패스1  OES   → FBO_A                  → stage_b_ms
 *   패스2  통계 산출(컴퓨트)               → stage_d_analyze_ms
 *   패스3  통계 → LUT·계수(컴퓨트)         → stage_d_build_ms
 *   패스4  FBO_A → FBO_B  적용             → stage_d_apply_ms
 *   패스5  FBO_B → 화면                    → gpu_present_ms
 *   ```
 *   ②의 하위 패스를 **합치지 않는다**(`docs/FRAME_LOG_SCHEMA.md` §2) — 게이트를 넘었을 때
 *   다음 질문이 "어디가 비싼가"이고, 합치면 그 정보가 사라진다. 세 arm이 **같은 GL 호출
 *   시퀀스**([drawComputeStage2])를 타는 것도 같은 이유다 — 시퀀스가 arm마다 다르면 패스
 *   비용을 arm끼리 비교하는 근거가 흔들린다. 각 arm의 내용은 [DragoStage] · [ClaheStage] ·
 *   [AgcwdStage].
 * - **② 조합 arm**([RenderArm.usesChainedComputeStage2] — `drago_clahe_chain` ·
 *   `detect_cpu_chain` · 각각의 `_1q` 판) — 위 3단이
 *   **두 벌 직렬로** 벌어진 8패스([drawChainedComputeStage2], [DragoClaheChainStage]).
 *   `detect_cpu_chain` 계열은 **렌더가 이것과 글자 그대로 같고 ③ 탐지만 함께 돈다**
 *   (탐지는 별 스레드라 GL 패스를 하나도 더하지 않는다):
 *   ```
 *   패스1  OES   → FBO_A                  → stage_b_ms
 *   패스2~4 drago 3단  FBO_A → FBO_B      → stage_d_analyze/build/apply_ms
 *   패스5~7 clahe 3단  FBO_B → FBO_A      → stage_d_analyze2/build2/apply2_ms
 *   패스8  FBO_A → 화면                    → gpu_present_ms
 *   ```
 *   ⚠ 8패스다. [GpuTimerRing.MAX_PASS_COUNT]가 8이던 시절에는 이 arm이 슬롯을 정확히 다
 *   썼고 그래서 `bf`를 얹을 수 없었다 — 그 상수를 **12로 올렸다.** FBO는 2장 핑퐁으로
 *   충분하므로 [FBO_COUNT]를 늘리지 않는다(bf·오버레이 arm도 2장으로 닫힌다).
 * - **② 융합 arm**([RenderArm.usesFusedComputeStage2] — `drago_clahe_fused`) — 위 조합에서
 *   **중간 materialize와 적용 패스 하나를 뺀** 7패스([drawFusedComputeStage2],
 *   [DragoClaheFusedStage]). Drago 톤맵이 CLAHE의 두 패스에 인라인된다.
 *   🔴 이식 최적화가 아니라 **알고리즘 변경**이다([RenderArm.FUSED_DEVIATION]).
 * - **② + bf arm**([RenderArm.usesChainedBilateral] · [RenderArm.usesFusedBilateral]) — 위 두
 *   구성 **뒤에 bilateral 한 패스**를 더한 9패스([drawChainedBilateral]) / 8패스
 *   ([drawFusedBilateral])다. 앞부분은 각각 체인·융합과 **글자 그대로 같은 GL 호출**이라
 *   두 arm의 차분이 곧 bf 한 패스의 비용이 된다([BilateralStage]).
 * - **④ 오버레이 arm**([RenderArm.usesHighlightOverlay]) — 3패스 골격의 ② 자리는 단순 복사이고
 *   그 뒤에 오버레이 패스가 하나 붙는 4패스([drawHighlightOverlay], [HighlightOverlay]):
 *   ```
 *   패스1  OES   → FBO_A                  → stage_b_ms
 *   패스2  FBO_A → FBO_B (복사)            → stage_d_ms
 *   패스3  FBO_B에 스트로크 quad 덧그림     → stage_i_ms
 *   패스4  FBO_B → 화면                    → gpu_present_ms
 *   ```
 *   ⚠ **패스3은 `glClear`를 부르지 않는다** — ② 출력 위에 얹는 패스라 지우면 그림이 사라진다.
 *   패스마다 clear를 명시한다는 아래 규약의 **의도된 예외**이며, 그래서 이 패스에는 타일
 *   재적재 비용이 섞인다(`session.json`의 `overlay` 블록에 그대로 적는다).
 * - **④ ③결과 오버레이 arm**([RenderArm.usesDynamicHighlightBoxes] — `detect_cpu_highlight` ·
 *   `detect_cpu_highlight_1q` · `detect_cpu_chain_highlight` ·
 *   `detect_cpu_chain_highlight_1q` · `detect_cpu_chain_highlight_nofill`) — 앞의 둘은 위와
 *   **같은 4패스**이고, 뒤의 셋은 아래
 *   ②③④ 통합 arm의 9패스다. **다섯 arm 모두** 그 앞에 **H칸(좌표 평활·hold)** 하나가 더 붙는다
 *   ([OverlaySmoother]). 🔴 **GPU 패스가 아니라 GL 스레드의 CPU 구간**이라 GPU query가 아니라
 *   `stage_h_ms`(CPU 벽시계) 열로 나가고, GPU 패스를 **열기 전에** 닫힌다
 *   ([RenderArm.OVERLAY_STAGE_H_SCOPE]). 박스는 [DetectOverlayPublisher]가 게시한 ③ 결과이고
 *   개수가 프레임마다 다르므로 `overlay_boxes` 열이 그 프레임의 개수를 말한다.
 * - **②③④ 통합 arm**([RenderArm.usesChainedHighlight] — `detect_cpu_chain_highlight` ·
 *   그 프레임 단일 query 짝 `detect_cpu_chain_highlight_1q` · fill 대조군
 *   `detect_cpu_chain_highlight_nofill`) — ② 체인 7패스 **뒤에 오버레이
 *   패스를 끼운** 9패스([drawChainedHighlight]). 세 arm은 **이 함수를 그대로 함께 타고**
 *   갈리는 것은 둘뿐이다: GPU timer query를 거는 방식(`_1q`)과 ④ 오버레이가 fill quad를
 *   넣는가([RenderArm.drawsOverlayFill] — `_nofill`):
 *   ```
 *   패스1   OES   → FBO_A                 → stage_b_ms
 *   패스2~4 drago 3단  FBO_A → FBO_B      → stage_d_analyze/build/apply_ms
 *   패스5~7 clahe 3단  FBO_B → FBO_A      → stage_d_analyze2/build2/apply2_ms
 *   패스8   **FBO_A**에 스트로크 quad 덧그림 → stage_i_ms
 *   패스9   FBO_A → 화면                   → gpu_present_ms
 *   ```
 *   🔴 **패스8의 타깃이 FBO_A다** — 4패스 오버레이 arm은 FBO_B에 그린다(그 arm의 ② 자리가
 *   거기 썼다). 체인은 마지막 처리 패스가 FBO_A에 쓰고 present가 FBO_A를 읽으므로, 그 함수를
 *   그대로 복사해 오면 **박스가 화면에 안 뜨는데 `overlay_boxes`·`stage_i_ms`는 정상값이
 *   나온다.** H칸도 4패스 arm과 같은 자리(GPU 패스 앞)에서 돈다.
 *
 * 패스스루가 아닌 arm은 [GpuTimerRing]으로 패스별 GPU 시간을 잰다. **패스스루 arm에는
 * query를 하나도 걸지 않는다** — query 자체가 GPU 동작과 드라이버 스케줄링을 바꾸므로,
 * 승격 베이스라인을 재현하는 경로에 넣으면 그 기준이 기준이 아니게 된다.
 *
 * ### 타임스탬프를 찍는 위치 (정직하게 문서화해야 하는 지점)
 * `GLSurfaceView`를 쓰면 `swapBuffers`는 프레임워크가 `onDrawFrame` 반환 **후에** 하므로
 * 우리가 잴 수 없다. 그래서:
 * - `t_render_start_ns` = `onDrawFrame` 진입 직후(`updateTexImage` 전)
 * - `t_render_end_ns` = `onDrawFrame` 반환 직전(드로우콜 제출 완료)
 *
 * 귀결 두 개를 함께 봐야 한다:
 * - `output_interval_ms`(연속 `onDrawFrame` 종료 간격)는 `swapBuffers`가 다음 사이클 앞에서
 *   블록하므로 **실제 표시 주기 = 진짜 프레임타임**이 된다.
 * - `render_latency_ms`는 `glDrawArrays`가 즉시 반환하므로 **CPU 제출 비용이고 GPU 실행
 *   시간이 아니다.** "렌더가 사실상 무료"로 읽으면 틀린다. 실제 GPU 비용은 `stage_*_ms` /
 *   `gpu_present_ms` 열에 따로 있다. 같은 문장이 `session.json`에도 남는다.
 */
class PassthroughRenderer(
    private val recorder: FrameLogRecorder,
    /**
     * `onFrameAvailable`을 받을 **전용 스레드**의 Handler.
     * 넘기지 않으면 SurfaceTexture는 콜백을 메인 루퍼로 보내고, 그러면 UI 작업 지연이
     * `t_recv_ns`에 섞여 "프레임 도착 시각"이 도착 시각이 아니게 된다.
     */
    private val frameSignalHandler: Handler,
    /**
     * ③ → ④ 게시자. 🔴 **읽기만 한다**(`latest()`는 참조 읽기 하나다). 오버레이 arm이 아니면
     * 게시자가 꺼져 있어 이 객체가 있다는 사실이 프레임 경로에 새지 않는다.
     */
    private val overlayPublisher: DetectOverlayPublisher,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener, FrameTarget {

    /** GL 스레드에서 SurfaceTexture가 준비되면 호출된다(GL 스레드에서 불린다). */
    var onGlReady: (() -> Unit)? = null

    /** 새 프레임이 왔을 때 `GLSurfaceView.requestRender()`를 부르기 위한 훅. */
    var onFrameSignal: (() -> Unit)? = null

    @Volatile
    var surfaceWidth = 0
        private set

    @Volatile
    var surfaceHeight = 0
        private set

    /**
     * **협상된 처리 해상도.** 값의 출처는 `CameraFrameSource`가 `acquireSurface`에 넘기는
     * `SurfaceRequest.resolution`이고, 그건 `NegotiatedConfig`와 같은 값이다.
     * 협상 전에는 0이며 **그 경우 값을 지어내지 않는다** — FBO를 만들지 않고 그 사실을 남긴다.
     */
    @Volatile
    var processWidth = 0
        private set

    @Volatile
    var processHeight = 0
        private set

    /** `onSurfaceCreated`에서 1회 실측한 GL 능력. 수집 전에는 null. */
    @Volatile
    var capabilities: GlCapabilities? = null
        private set

    /** 오프스크린 자원의 현재 상태(사람이 읽는 문장). `session.json`에 그대로 나간다. */
    @Volatile
    var offscreenStatus: String = "아직 필요하지 않았다 (arm=${RenderArm.DEFAULT.id})"
        private set

    /**
     * 3패스 arm인데 오프스크린을 못 써서 1패스로 되돌아간 드로우 수.
     * 0이 아니면 그 런의 `pipeline_stages` 선언과 실제 경로가 어긋난 것이다.
     */
    @Volatile
    var offscreenFallbackDraws = 0
        private set

    /**
     * ④ 오버레이 자원의 현재 상태(사람이 읽는 문장). `session.json`의 `overlay` 블록으로
     * 나간다. 오버레이 arm이 아니면 그 사실을 적는다 — 값을 지어내지 않는다.
     *
     * 🔴 [stage2Status]와 **같은 문장에 섞지 않는다.** 실패 서술이 성공 서술 뒤에 묻히는 것을
     * 막는 것이 [applyProgramFailureStatus]의 교훈이고, 두 자리는 서로 다른 자원이다.
     */
    val overlayStatus: String
        get() = if (arm.usesHighlightOverlay) {
            highlightOverlay.status
        } else {
            "이 arm에는 ④ 오버레이 패스가 없다 (arm=${arm.id})"
        }

    /** ② 자리 자원의 현재 상태(사람이 읽는 문장). `session.json`의 `stage2_params`로 나간다. */
    val stage2Status: String
        get() {
            // 🔴 **usesHighlightOverlay 분기보다 앞에 있어야 한다.** 통합 arm도 그 술어가
            //    true이므로 뒤에 두면 "② 자리는 단순 복사다"라고 **거짓 선언**한다 —
            //    그 arm의 ② 자리는 체인 6패스다. (아래 분기의 조건을 좁히지 않고 앞에서
            //    돌려주는 쪽을 택했다: 기존 arm이 타는 문장을 한 글자도 건드리지 않는다.)
            if (arm.usesChainedHighlight) {
                // 적용 프래그먼트가 **둘**이라 한쪽만 없어도 이 arm은 그릴 수 없다
                // (usesChainedComputeStage2 분기와 같은 논거).
                // 🔴 **이 arm이 실제로 쓰는 것은 복제 프로그램이다**(시연 ② 토글의 mix가
                //    붙은 것) — 공유본을 보면 복제본의 컴파일 실패가 여기 안 잡히고,
                //    dispatchDraw가 전 프레임 패스스루로 폴백하는데 프레임타임은 정상으로
                //    보인다. `FRAME_BUDGET.md` §3 주6이 기록한 실패가 정확히 그것이다.
                if (demoChainDragoApplyProgram == null || demoChainClaheApplyProgram == null) {
                    return applyProgramFailureStatus(
                        chainStage.ready, chainStage.status,
                        listOf(
                            PROGRAM_LABEL_DEMO_CHAIN_DRAGO_APPLY,
                            PROGRAM_LABEL_DEMO_CHAIN_CLAHE_APPLY
                        )
                    )
                }
                // ④ 자원 상태를 여기에 이어 붙이지 않는다 — overlayStatus가 따로 낸다.
                return chainStage.status +
                    " ── ④ 오버레이 자원 상태는 overlay 블록(overlay.gpu_status)에 있다"
            }
            if (arm.usesHighlightOverlay) {
                // ② 자리가 단순 복사인 arm이다. ④ 자원 상태는 overlayStatus가 따로 낸다 —
                // 여기에 이어 붙이면 오버레이 컴파일 실패가 ② 문장 뒤에 묻힌다.
                return "② 자리는 단순 복사다(3패스 골격 그대로). 이 arm이 재는 것은 " +
                    "stage_i_ms(④ 오버레이)이며, 오버레이 자원 상태는 overlay 블록에 있다"
            }
            if (arm.usesBilateral) {
                // 앞 스테이지(체인/융합)와 bf **둘 다** 있어야 그릴 수 있다.
                val chained = arm.usesChainedBilateral
                val baseStatus = if (chained) chainStage.status else fusedStage.status
                val baseReady = if (chained) chainStage.ready else fusedStage.ready
                val baseProgramsMissing = if (chained) {
                    chainDragoApplyProgram == null || chainClaheApplyProgram == null
                } else {
                    fusedApplyProgram == null
                }
                if (baseProgramsMissing) {
                    val labels = if (chained) {
                        listOf(PROGRAM_LABEL_CHAIN_DRAGO_APPLY, PROGRAM_LABEL_CHAIN_CLAHE_APPLY)
                    } else {
                        listOf(PROGRAM_LABEL_FUSED_APPLY)
                    }
                    return applyProgramFailureStatus(baseReady, baseStatus, labels)
                }
                if (!baseReady) {
                    // 🔴 성공 서술을 이어 붙이지 않는다. 앞 스테이지가 없으면 이 arm은 못 그린다.
                    return "실패: bf 앞 스테이지(${if (chained) "체인" else "융합"})가 준비되지 " +
                        "않았다 — $baseStatus. 이 arm은 그릴 수 없고 모든 프레임이 패스스루로 " +
                        "폴백한다(render.processing.frames_fell_back_to_passthrough 확인)"
                }
                if (bilateralProgram == null || !bilateralStage.ready) {
                    // 🔴 applyProgramFailureStatus를 쓰지 않는다 — 그 문장은 "통계 패스"를
                    //    말하는데 bf에는 통계 패스가 없어 **거짓 서술**이 된다.
                    val log = programFailureLogs[PROGRAM_LABEL_BILATERAL]
                    val diagnostics = if (log == null) {
                        "컴파일러 원문을 잡지 못했다(대상 라벨=$PROGRAM_LABEL_BILATERAL)"
                    } else {
                        "컴파일러 원문 = [$PROGRAM_LABEL_BILATERAL] $log"
                    }
                    // 🔴 스테이지가 준비된 경우에는 그 **성공 서술을 인용하지 않는다**
                    //    (applyProgramFailureStatus와 같은 이유 — 뒤에 붙은 "준비 완료"만
                    //    읽고 정상으로 오독한 사고가 있었다).
                    val stagePart = if (bilateralStage.ready) {
                        "bf 스테이지(해상도·파라미터)는 준비됐으나 프래그먼트 프로그램이 없어 " +
                            "arm 전체가 실패다"
                    } else {
                        "bf 스테이지도 준비되지 않았다 — ${bilateralStage.status}"
                    }
                    return "실패: bf 패스(프래그먼트)를 준비하지 못했다. 이 arm은 그릴 수 없고 " +
                        "모든 프레임이 패스스루로 폴백한다(render.processing." +
                        "frames_fell_back_to_passthrough 확인). $stagePart. $diagnostics"
                }
                return "$baseStatus ── +bf: ${bilateralStage.status}"
            }
            if (arm.usesChainedComputeStage2) {
                // 적용 프래그먼트가 **둘**이라 한쪽만 없어도 이 arm은 그릴 수 없다.
                return if (chainDragoApplyProgram == null || chainClaheApplyProgram == null) {
                    applyProgramFailureStatus(
                        chainStage.ready, chainStage.status,
                        listOf(
                            PROGRAM_LABEL_CHAIN_DRAGO_APPLY, PROGRAM_LABEL_CHAIN_CLAHE_APPLY
                        )
                    )
                } else {
                    chainStage.status
                }
            }
            if (arm.usesFusedComputeStage2) {
                return if (fusedApplyProgram == null) {
                    applyProgramFailureStatus(
                        fusedStage.ready, fusedStage.status,
                        listOf(PROGRAM_LABEL_FUSED_APPLY)
                    )
                } else {
                    fusedStage.status
                }
            }
            val stage = computeStage(arm)
                ?: return "② 자리에 통계 패스가 필요 없는 arm이다 (셰이더 1패스)"
            return if (computeApplyProgram(arm) == null) {
                applyProgramFailureStatus(
                    stage.ready, stage.status, listOf(computeApplyProgramLabel(arm))
                )
            } else {
                stage.status
            }
        }

    /**
     * 조합 arm의 셰이더 소스에서 **기계가 센** 색공간 변환 호출 지점. 키는 arm id다.
     * `onSurfaceCreated`에서 1회 채운다(hot path가 아니다).
     *
     * **체인과 융합을 둘 다 담는다.** 그래야 한 세션 파일만으로 "변환 몇 회를 줄여 얼마를
     * 아꼈는가"를 계산할 수 있다 — 두 파일을 대조하다가 짝을 잘못 고르는 실패 모드를 없앤다.
     */
    @Volatile
    var colorTransformSites: Map<String, List<Pair<String, Map<String, Int>>>> = emptyMap()
        private set

    /**
     * 통합 arm의 폴백 사유를 logcat에 이미 냈는가. **GL 스레드 전용**이라 volatile이 아니다.
     * 리셋하지 않는다 — 런 안에서 한 번만 내는 것이 목적이다.
     */
    private var chainHighlightFallbackLogged = false

    @Volatile
    private var arm: RenderArm = RenderArm.DEFAULT

    @Volatile
    private var displayMode: DisplayMode = DisplayMode.DEFAULT

    /**
     * 시연용 ② 토글. `false`면 통합 arm의 패스4·7이 `mix(uEnhance)`로 **원본을 그대로**
     * 낸다(패스 수·GL 호출 시퀀스는 그대로다 — 셰이더 안에서만 갈린다).
     *
     * 🔴 **[setDemoToggles]로만 바꾼다**(= `glView.queueEvent`로 GL 스레드에서). `@Volatile`은
     * 가시성 보장일 뿐이라 프레임 **중간에** 값이 갈리면 그 프레임의 화면과 로그가 어긋난다.
     */
    @Volatile
    private var stage2Enabled = true

    /**
     * 시연용 ④ 토글. `false`면 패스8이 정점 0개를 그린다(패스는 그대로 열린다 —
     * `stage_i_ms` 열이 네 조합에서 같은 것을 재야 비교가 된다).
     *
     * 🔴 `overlay_boxes`와 `overlay_fill_frac`은 **짝**이다 — [drawChainedHighlight]의 주석.
     */
    @Volatile
    private var overlayEnabled = true

    private var cardboardImageScale = DEFAULT_CARDBOARD_IMAGE_SCALE
    private var cardboardEyeOffset = DEFAULT_CARDBOARD_EYE_OFFSET

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    private var cameraSurface: Surface? = null

    private var oesTextureId = 0

    /** 패스1용(OES 샘플러). 패스스루 arm도 이것 하나만 쓴다. */
    private var oesProgram: QuadProgram? = null

    private var cardboardOesProgram: QuadProgram? = null

    /**
     * cardboard SBS의 **2D 눈 그리기**용. 🔴 이것도 present 전용이므로
     * [VERTEX_SHADER_PRESENT]로 만든다 — 오프스크린 패스에서는 절대 쓰이지 않는다.
     */
    private var cardboard2dProgram: QuadProgram? = null

    /**
     * 🔴 **오프스크린 복사 전용.** 회전 수단이 없다([VERTEX_SHADER_2D]에 `uPositionMatrix`가
     * 없다) — 그게 이 프로그램이 present와 갈라진 이유다. present는 [presentProgram]이다.
     */
    private var blitProgram: QuadProgram? = null

    /**
     * **마지막 패스(화면) 전용.** [blitProgram]과 프래그먼트는 같고 정점만 다르다
     * ([VERTEX_SHADER_PRESENT] — `uPositionMatrix`가 있다).
     *
     * ⚠ `FRAME_BUDGET.md`가 "`gpu_present_ms`는 전 arm에서 글자 그대로 같은 셰이더"를
     * 분석의 기둥으로 쓰는데, **그 전제는 이 커밋 경계에서 바뀐다** — 이 커밋 이후의
     * `gpu_present_ms`는 정점에 mat4 곱이 하나 더 붙은 셰이더의 값이다(전 arm이 같은
     * 프로그램을 쓴다는 사실 자체는 그대로다). 커밋을 가로질러 present 비용을 비교하지 말 것.
     */
    private var presentProgram: QuadProgram? = null

    /** 패스2(감마). ② 자리의 비용 하한. */
    private var gammaProgram: QuadProgram? = null

    /** `drago` arm의 적용 패스(패스4). `#version 310 es`라 정점 셰이더도 따로 쓴다. */
    private var dragoApplyProgram: QuadProgram? = null

    /** `clahe_gamma` arm의 적용 패스(패스4). 타일 LUT 이중선형 보간 + 감마. */
    private var claheApplyProgram: QuadProgram? = null

    /** `agcwd` arm의 적용 패스(패스4). 1D LUT 적용. */
    private var agcwdApplyProgram: QuadProgram? = null

    /** `drago_clahe_chain` arm의 **첫** 적용 패스(패스4). SSBO binding만 단품과 다르다. */
    private var chainDragoApplyProgram: QuadProgram? = null

    /** `drago_clahe_chain` arm의 **둘째** 적용 패스(패스7). SSBO binding만 단품과 다르다. */
    private var chainClaheApplyProgram: QuadProgram? = null

    /**
     * ②③④ 통합 arm **전용** 패스4. 위 [chainDragoApplyProgram]과 산식은 같고 시연용
     * `mix(uEnhance)` 한 줄이 더 있다 → [DragoClaheChainStage.DEMO_DRAGO_APPLY_SHADER].
     *
     * 🔴 **공유본을 고치지 않고 복제한 이유**가 그 상수의 KDoc에 있다(아홉 arm이 공유한다).
     */
    private var demoChainDragoApplyProgram: QuadProgram? = null

    /** 위와 같은 취지의 통합 arm 전용 패스7. */
    private var demoChainClaheApplyProgram: QuadProgram? = null

    /**
     * `drago_clahe_fused` arm의 **유일한** 적용 패스(패스6). 톤맵 + LUT 보간 + 감마를
     * 한 프래그먼트에서 하며 SSBO 블록을 **둘** 읽는다.
     */
    private var fusedApplyProgram: QuadProgram? = null

    /**
     * `+bf` arm의 **bilateral 패스**. 체인+bf(패스8)와 융합+bf(패스7)가 **같은 프로그램을
     * 공유한다** — bf는 앞 스테이지와 무관한 gather 필터이고, 사본을 만들면 두 arm의 bf 비용을
     * 비교하는 근거가 흔들린다(같은 판단이 [ES31_QUAD_VERTEX_SHADER] 주석에 있다).
     */
    private var bilateralProgram: QuadProgram? = null

    /**
     * 프로그램을 만들지 못했을 때의 **컴파일러 원문**(`glGetShaderInfoLog` /
     * `glGetProgramInfoLog`). 키는 [buildProgram]에 넘긴 라벨이다.
     *
     * 🔴 logcat에만 남기면 버퍼가 밀린 뒤에는 원인을 되물을 수 없다 — 융합 arm의 Mali
     * 컴파일 거부를 logcat이 아직 살아 있던 덕에 겨우 찾았고, 그건 운이었다. 그래서
     * 원문을 들고 있다가 [stage2Status]로 `session.json`에 함께 낸다.
     *
     * GL 스레드가 쓰고 상태 문자열을 읽는 스레드가 읽는다 → [java.util.Collections]의
     * 동기화 래퍼 대신 `ConcurrentHashMap`을 쓴다(문자열 하나가 늦게 보이는 것은
     * 괜찮지만 맵이 깨지는 것은 안 된다).
     */
    private val programFailureLogs = ConcurrentHashMap<String, String>()

    /** `drago` arm의 전역 통계(리덕션 + 계수). 자원과 상태를 통째로 소유한다. */
    private val dragoStage = DragoStage()

    /** `clahe_gamma` arm의 타일 통계(히스토그램 + LUT). */
    private val claheStage = ClaheStage()

    /** `agcwd` arm의 전역 통계(히스토그램 + LUT). */
    private val agcwdStage = AgcwdStage()

    /** `drago_clahe_chain` arm의 통계 **두 벌**(drago 전역 + clahe 타일). 자원을 따로 소유한다. */
    private val chainStage = DragoClaheChainStage()

    /** `drago_clahe_fused` arm. 체인과도 자원을 공유하지 않는다(체인 실측 조건을 고정한다). */
    private val fusedStage = DragoClaheFusedStage()

    /**
     * `+bf` arm의 bilateral 단. **GL 객체를 갖지 않는다**(프로그램은 [bilateralProgram]) —
     * 해상도에서 유도한 texel과 상태 문장만 소유한다.
     *
     * ⚠ bf arm은 앞 스테이지로 [chainStage]/[fusedStage]를 **그대로 재사용한다.** 융합이 체인과
     * 자원을 나눈 것과 판단이 다른데 이유가 있다: 융합은 **셰이더 자체가 다르므로** 별 자원이
     * 필연이었지만, bf arm의 앞 7패스(또는 6패스)는 **글자 그대로 같은 프로그램·같은 SSBO를
     * 같은 순서로** 쓴다. 그래야 "chain_bf − chain = bf 한 패스"가 문자 그대로 성립한다.
     * arm은 동시에 돌지 않으므로(측정 중 전환 금지) 버퍼를 공유해도 서로를 오염시키지 않고,
     * 자원 수명도 컨텍스트 수명 하나뿐이라 체인 실측의 조건이 바뀌지 않는다.
     */
    private val bilateralStage = BilateralStage()

    /** ④ 오버레이 arm. 프로그램·정점 데이터를 스스로 소유한다. */
    private val highlightOverlay = HighlightOverlay()

    /**
     * ④ **H칸(좌표 평활·hold).** ③ 결과를 그리는 arm에서만 돈다.
     * 🔴 트랙·출력 배열을 상한 크기로 **여기서 한 번** 잡는다 — GL 스레드 프레임당 할당 0.
     */
    private val overlaySmoother =
        OverlaySmoother(RenderArm.OVERLAY_BOX_CAP_MEASUREMENT_VALUE)

    /**
     * 이번 [onDrawFrame]이 쓸 게시 스냅샷. 🔴 **`t_render_start_ns`를 찍기 전에** 읽어 둔 것이다
     * — 그래야 어떤 프레임도 자기 렌더 시작보다 **미래에** 게시된 결과를 쓰지 않는다
     * (`docs/FRAME_LOG_SCHEMA.md` §2의 요구이며, 어기면 하네스가 신선도 폐기로 세고 "두 시각의
     * 순서가 뒤집혔다"고 경고한다). GL 스레드 전용.
     */
    private var frameOverlaySnapshot: DetectOverlaySnapshot? = null

    /** 이번 프레임의 `stage_h_ms`(ns). 재지 않았으면 [FrameLogRecorder.MISSING_NS]. */
    private var frameStageHNs = FrameLogRecorder.MISSING_NS

    /**
     * 이번 프레임에 **실제로 그린** 박스 수. `0`은 정상값이고
     * [FrameLogRecorder.OVERLAY_BOXES_UNRECORDED]만 "기록 안 함"이다.
     */
    private var frameOverlayBoxes = FrameLogRecorder.OVERLAY_BOXES_UNRECORDED

    /**
     * 이번 프레임의 `overlay_fill_frac`을 [FrameLogRecorder.FILL_FRAC_SCALE]배 한 정수.
     * `0`은 정상값(채운 면적이 없었다)이고
     * [FrameLogRecorder.OVERLAY_FILL_FRAC_UNRECORDED]만 "기록 안 함"이다.
     *
     * 🔴 값의 출처는 [HighlightOverlay.overlayFillFrac] **하나**이고 여기서 다시 계산하지
     * 않는다 — 면적을 두 곳에서 재면 두 값이 갈리는 날 어느 쪽이 그린 것인지 알 수 없다.
     */
    private var frameOverlayFillFracScaled = FrameLogRecorder.OVERLAY_FILL_FRAC_UNRECORDED

    /** [0]=FBO_A, [1]=FBO_B. 0이면 미생성. GL 스레드 전용. */
    private val fbos = IntArray(FBO_COUNT)
    private val fboTextures = IntArray(FBO_COUNT)
    private var fboWidth = 0
    private var fboHeight = 0

    /**
     * 패스별 GPU 시간. **3패스 arm에서만** 쓴다. 링·query 객체는 GL 스레드 전용이고,
     * 회수된 값은 [FrameLogRecorder.setGpuTiming]으로 **그 query를 건 프레임의 행**에 간다.
     */
    private val gpuTimer = GpuTimerRing(recorder)

    private val texMatrix = FloatArray(16)
    private val positionMatrix = FloatArray(16)

    @Volatile
    private var requestedPreviewRotationDegrees = 0

    @Volatile
    private var requestedPreviewMirror = false

    /**
     * 🔴 **④ 박스에 걸 회전각.** [requestedPreviewRotationDegrees]와 **같은 조건으로 0이
     * 된다**(`hasCameraTransform=true`) — CameraX가 표시 방향을 처리하고 Preview와
     * ImageAnalysis가 같은 방향 기준 위에 있으므로 어느 쪽에도 추가 회전이 필요 없다.
     *
     * ⚠ **그래도 별도 필드로 둔다.** 두 값은 뜻이 다르고(하나는 present 정점, 하나는 센서
     * 좌표 매핑) `hasCameraTransform=false` 경로에서 갈릴 수 있다. 한 필드로 합치면 그때
     * 한쪽이 조용히 틀리고, 이 세션에서 이미 두 번 그렇게 틀렸다.
     *
     * 실제로 걸린 각도는 [OverlaySmoother.appliedBoxRotationDegrees]가 증언한다.
     */
    @Volatile
    private var requestedOverlayRotationDegrees = 0

    private var appliedPreviewRotationDegrees = Int.MIN_VALUE
    private var appliedPreviewMirror = false

    /**
     * 🔴 **CameraX가 준 표시 변환의 원값**(사람이 읽는 문장). `session.json`으로 나간다.
     *
     * 왜 남기는가: 이 문장의 원값 4개가 **방향 결함을 두 번 갈라냈다.** 여기에는 영상 쪽
     * 회전만 실린다 — ④ 박스의 회전은 [OverlaySmoother.appliedBoxRotationDegrees]가 따로
     * 증언하고, **두 값이 다른 것이 정상이다.**
     *
     * 🔴 **`has_camera_transform=true`면 우리가 걸 회전은 0이다** — CameraX가 표시 회전을
     * `SurfaceTexture`의 `texMatrix`에 이미 넣어 줬고 패스1이 그 행렬로 샘플링하므로
     * **영상은 그것만으로 바로 선다.**
     *
     * 🔴 **첫 번째 결함**: 회전각을 `targetRotation`(표시 방향 상수)에서 읽고 있어 각도가
     * 0으로 죽어 있었다.
     * A34 세로 실측에서 `applied_rotation_degrees=0`인데 이 문장의 `rotation_degrees=90`
     * 이었던 것이 원인을 가리켰다.
     *
     * 🔴 **두 번째 결함**: 그것을 고쳐 `rotationDegrees`를 present에 그대로 걸었더니 이번엔
     * **바로 서 있던 영상이 90° 누웠다**(실측: `applied=90` + 육안). `texMatrix`가 이미
     * 돌리고 있었다는 사실을 몰랐기 때문이다 — *"표시 경로는 방향 보정을 하지 않는다"*는
     * 서술을 **코드에서 추론해 받았고 그것이 틀렸다.** 어긋난 것은 처음부터 **박스뿐**이었다
     * (알려진 이슈 67).
     *
     * → **원값 4개를 다 적어 두지 않았다면 두 번 다 되물을 수 없었다.** 분기가 단순해진
     * 지금도 전부 남긴다.
     *
     * ⚠ **관측 기록이지 보정이 아니다** — 보정은 CameraX의 `texMatrix`(영상)와
     * [OverlayCoordMap](박스)이 한다. 이 문장은 그것이 무엇을 근거로 돌았는지만 말한다.
     */
    @Volatile
    var previewTransformNote: String = NO_PREVIEW_TRANSFORM_NOTE
        private set

    /**
     * 표시 변환 콜백이 **몇 번** 왔는가. arm을 바꾸면 ③ 분석 use case의 유무가 달라져 카메라를
     * 다시 바인딩하고 그때 리스너가 새로 붙으므로, 한 앱 실행에서 1보다 클 수 있다.
     */
    @Volatile
    var previewTransformArrivals = 0
        private set

    /**
     * present 정점에 **실제로 건** 회전각. [PREVIEW_ROTATION_NOT_APPLIED]면 한 번도 걸지
     * 않았다(단위행렬 그대로 그렸다).
     *
     * 🔴 **`hasCameraTransform=true`면 0이 정상이다** — 그 경우 영상을 세우는 것은 CameraX의
     * `texMatrix`이고 여기서 또 돌리면 두 번 돈다. 이 기계는 그 플래그가 false인 경로를 위해
     * 남아 있다.
     * ⚠ **④ 박스의 회전은 이 값이 아니다** — 박스는 [OverlayCoordMap]이 따로 돌린다.
     * 🔴 **부호(90 대 270)는 이 값으로 확인되지 않는다** — [PREVIEW_ROTATION_SIGN] 참고.
     */
    @Volatile
    var previewRotationApplied = PREVIEW_ROTATION_NOT_APPLIED
        private set

    /** present 정점에 실제로 건 좌우 반전. 회전과 같은 행렬·같은 자리에서 걸린다. */
    @Volatile
    var previewMirrorApplied = false
        private set

    /** 행렬을 실제로 다시 만든 횟수. **2 이상이면 표시 축이 도중에 바뀐 것이다.** */
    @Volatile
    var previewRotationApplyCount = 0
        private set

    /**
     * 마지막 적용이 **그 런의 몇 번째 기록 프레임**에서 일어났는가.
     * [PREVIEW_ROTATION_APPLIED_WHILE_IDLE]이면 측정 중이 아닐 때(런 시작 전) 적용된 것이고,
     * 0 이상이면 **런 도중에 바뀐 것**이다 — 그 프레임 앞뒤로 영상의 기준 축이 다르다.
     */
    @Volatile
    var previewRotationAppliedAtRecordedFrame = PREVIEW_ROTATION_APPLIED_WHILE_IDLE
        private set
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTEX_DATA.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTEX_DATA)
            position(0)
        }

    /**
     * `onFrameAvailable`이 찍은 도착 시각. 프레임 신호 스레드가 쓰고 GL 스레드가 가져간다.
     * 한 사이클에 두 번 도착하면 **최신 값이 남는다** — `updateTexImage()`가 최신 프레임을
     * 물어오므로 그게 짝이 맞는다.
     */
    private val pendingRecvNs = AtomicLong(NO_FRAME)

    /** 시계 기준 판별용 표본. GL 스레드 전용. */
    private val probeSamples = ArrayList<ClockProbeSample>(CaptureClockProbe.SAMPLE_LIMIT)

    init {
        Matrix.setIdentityM(texMatrix, 0)
        Matrix.setIdentityM(positionMatrix, 0)
    }

    // ── GLSurfaceView.Renderer ────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 컨텍스트가 재생성되면 이전 텍스처·FBO·프로그램·SurfaceTexture는 전부 죽은 것이다.
        releaseGlResources()
        // 능력 프로브는 프로그램을 만들기 전에 찍는다 — 컴파일 실패로 조기 반환하는 경로가
        // 생기더라도 GL 문자열은 남아야 원인을 되물을 수 있다.
        capabilities = GlCapabilitiesProbe.probe()
        // 확장 유무는 프로브 실측이 유일한 근거다. 링은 그 값을 받아 두고, 실제로 걸 수
        // 있는지는 자기 프로브(glBeginQuery + glGetError)로 따로 확인한다.
        gpuTimer.onContextCreated(
            capabilities?.disjointTimerQuery ?: GlCapabilitiesProbe.UNKNOWN
        )
        oesProgram = buildProgram(VERTEX_SHADER_OES, FRAGMENT_SHADER_OES, PROGRAM_LABEL_OES)
        blitProgram = buildProgram(VERTEX_SHADER_2D, FRAGMENT_SHADER_BLIT, PROGRAM_LABEL_BLIT)
        // present는 blit과 프래그먼트가 같고 정점만 다르다. 실패하면 buildProgram이 라벨과
        // 함께 원문을 남기고 null을 돌려주며, dispatchDraw의 null 검사가 패스스루로 떨군다 —
        // 조용히 넘어가는 경로는 없다.
        presentProgram = buildProgram(
            VERTEX_SHADER_PRESENT,
            FRAGMENT_SHADER_BLIT,
            PROGRAM_LABEL_PRESENT,
        )
        cardboardOesProgram = buildProgram(
            VERTEX_SHADER_OES,
            FRAGMENT_SHADER_CARDBOARD_OES,
            PROGRAM_LABEL_CARDBOARD_OES,
        )
        // 🔴 cardboard 2D도 **present 전용**이라 회전 셰이더로 만든다. 라벨은 그대로 둔다 —
        //    역할이 같으므로 로그 키를 바꾸면 예전 런과 대조가 끊긴다.
        cardboard2dProgram = buildProgram(
            VERTEX_SHADER_PRESENT,
            FRAGMENT_SHADER_CARDBOARD_2D,
            PROGRAM_LABEL_CARDBOARD_2D,
        )
        gammaProgram = buildProgram(VERTEX_SHADER_2D, FRAGMENT_SHADER_GAMMA, PROGRAM_LABEL_GAMMA)
        // ② 컴퓨트 arm 3종. 컴퓨트를 못 쓰는 컨텍스트면 각 Stage가 스스로 꺼지고 이유를 남긴다.
        // ⚠ 지금 고른 arm과 무관하게 셋 다 준비한다 — arm 전환이 GL 스레드 이벤트라
        //   전환 시점에 컴파일하면 그 비용이 전환 직후 첫 프레임에 얹힌다.
        dragoStage.onContextCreated(capabilities)
        dragoApplyProgram = if (dragoStage.ready) {
            buildProgram(
                ES31_QUAD_VERTEX_SHADER, DragoStage.APPLY_SHADER, PROGRAM_LABEL_DRAGO_APPLY
            )
        } else {
            null
        }
        claheStage.onContextCreated(capabilities)
        claheApplyProgram = if (claheStage.ready) {
            buildProgram(
                ES31_QUAD_VERTEX_SHADER, ClaheStage.APPLY_SHADER, PROGRAM_LABEL_CLAHE_APPLY
            )
        } else {
            null
        }
        agcwdStage.onContextCreated(capabilities)
        agcwdApplyProgram = if (agcwdStage.ready) {
            buildProgram(
                ES31_QUAD_VERTEX_SHADER, AgcwdStage.APPLY_SHADER, PROGRAM_LABEL_AGCWD_APPLY
            )
        } else {
            null
        }
        // ② 조합 arm. 적용 프래그먼트가 **둘**이라는 것만 위 셋과 다르다.
        chainStage.onContextCreated(capabilities)
        if (chainStage.ready) {
            chainDragoApplyProgram = buildProgram(
                ES31_QUAD_VERTEX_SHADER, DragoClaheChainStage.DRAGO_APPLY_SHADER,
                PROGRAM_LABEL_CHAIN_DRAGO_APPLY
            )
            chainClaheApplyProgram = buildProgram(
                ES31_QUAD_VERTEX_SHADER, DragoClaheChainStage.CLAHE_APPLY_SHADER,
                PROGRAM_LABEL_CHAIN_CLAHE_APPLY
            )
            // ②③④ 통합 arm 전용 복제본. 같은 SSBO·같은 산식이고 mix(uEnhance)만 더 있다.
            // 🔴 **여기서 실패하면 통합 arm이 전 프레임 패스스루로 폴백한다** — stage2Status가
            //    그 실패를 잡도록 아래 분기도 이 두 프로그램을 본다(FRAME_BUDGET.md §3 주6의
            //    실패가 정확히 이것이다: 컴파일 실패인데 프레임타임이 정상으로 보였다).
            demoChainDragoApplyProgram = buildProgram(
                ES31_QUAD_VERTEX_SHADER, DragoClaheChainStage.DEMO_DRAGO_APPLY_SHADER,
                PROGRAM_LABEL_DEMO_CHAIN_DRAGO_APPLY
            )
            demoChainClaheApplyProgram = buildProgram(
                ES31_QUAD_VERTEX_SHADER, DragoClaheChainStage.DEMO_CLAHE_APPLY_SHADER,
                PROGRAM_LABEL_DEMO_CHAIN_CLAHE_APPLY
            )
        } else {
            chainDragoApplyProgram = null
            chainClaheApplyProgram = null
            demoChainDragoApplyProgram = null
            demoChainClaheApplyProgram = null
        }
        // ② 융합 arm. 적용 프래그먼트가 **하나**이고 그 하나가 SSBO 블록을 둘 읽는다.
        fusedStage.onContextCreated(capabilities)
        fusedApplyProgram = if (fusedStage.ready) {
            buildProgram(
                ES31_QUAD_VERTEX_SHADER, DragoClaheFusedStage.FUSED_APPLY_SHADER,
                PROGRAM_LABEL_FUSED_APPLY
            )
        } else {
            null
        }
        // `+bf` arm의 bilateral 패스. 프래그먼트 하나이고 SSBO를 읽지 않는다.
        bilateralStage.onContextCreated(capabilities)
        bilateralProgram = if (bilateralStage.ready) {
            buildProgram(
                ES31_QUAD_VERTEX_SHADER, BilateralStage.DENOISE_SHADER, PROGRAM_LABEL_BILATERAL
            )
        } else {
            null
        }
        // ④ 오버레이 arm. 프로그램을 스스로 만들고 실패 원문도 스스로 들고 있는다.
        highlightOverlay.onContextCreated(capabilities)
        // 색공간 변환 호출 지점을 **셰이더 문자열에서** 센다. 1회이고 hot path가 아니다.
        // 위에서 실제로 컴파일한 것과 **같은 String 객체**를 세므로 텍스트와 어긋날 수 없다.
        // 조합 arm을 **함께** 센다 — 한 세션 파일에서 둘의 차이를 계산할 수 있게.
        // bf arm은 앞 arm의 패스 목록에 bf 패스 하나를 끼운 것이다(목록을 복사하지 않는다).
        val chainSources = DragoClaheChainStage.shaderSourcesByPass(
            oesVertex = VERTEX_SHADER_OES,
            oesFragment = FRAGMENT_SHADER_OES,
            blitVertex = VERTEX_SHADER_2D,
            blitFragment = FRAGMENT_SHADER_BLIT,
            presentVertex = VERTEX_SHADER_PRESENT,
        )
        val fusedSources = DragoClaheFusedStage.shaderSourcesByPass(
            oesVertex = VERTEX_SHADER_OES,
            oesFragment = FRAGMENT_SHADER_OES,
            blitVertex = VERTEX_SHADER_2D,
            blitFragment = FRAGMENT_SHADER_BLIT,
            presentVertex = VERTEX_SHADER_PRESENT,
        )
        val overlaySources = HighlightOverlay.shaderSourcesByPass(
            oesVertex = VERTEX_SHADER_OES,
            oesFragment = FRAGMENT_SHADER_OES,
            blitVertex = VERTEX_SHADER_2D,
            blitFragment = FRAGMENT_SHADER_BLIT,
            presentVertex = VERTEX_SHADER_PRESENT,
        )
        val chainSites = ColorTransformCensus.countByPass(chainSources)
        val overlaySites = ColorTransformCensus.countByPass(overlaySources)
        // ②③④ 통합 arm의 9패스. 🔴 **체인 계수를 대신 내지 않고 실제로 센다** — 이 arm의
        // 패스4·7은 시연 ② 토글의 mix(uEnhance)가 붙은 **복제본**이고 패스8(④ 오버레이)이
        // 하나 더 있다. 없으면 session.json이 "셰이더를 세지 못했다"는 거짓 사유를 내거나
        // 다른 arm의 계수를 이 arm의 것으로 내보낸다(같은 실수를 highlight_boxes_1q에서
        // 이미 한 번 했다 — 바로 아래 주석).
        // ⚠ 오버레이 패스는 **HighlightOverlay가 낸 항목을 그대로 넘긴다**(문자열을 복사하지
        //   않는다 — 그래야 "같은 String 객체"라는 근거가 유지된다).
        val chainHighlightSites = ColorTransformCensus.countByPass(
            DragoClaheChainStage.demoShaderSourcesByPass(
                chainSources,
                overlaySources.first { it.first == "stage4_highlight" },
            )
        )
        val chainBfSites = ColorTransformCensus.countByPass(
            BilateralStage.withDenoisePass(chainSources)
        )
        colorTransformSites = mapOf(
            RenderArm.DRAGO_CLAHE_CHAIN.id to chainSites,
            RenderArm.DRAGO_CLAHE_FUSED.id to ColorTransformCensus.countByPass(fusedSources),
            RenderArm.DRAGO_CLAHE_CHAIN_BF.id to chainBfSites,
            RenderArm.DRAGO_CLAHE_FUSED_BF.id to ColorTransformCensus.countByPass(
                BilateralStage.withDenoisePass(fusedSources)
            ),
            // 프레임 단일 query arm은 짝과 **같은 셰이더 문자열**을 컴파일한다(계측 방식만
            // 다르다) → 같은 계수를 그 id로도 찾을 수 있게 둔다. **다시 세지 않고 짝의 결과를
            // 그대로 가리킨다** — 두 키의 값이 갈라질 수 없다.
            RenderArm.DRAGO_CLAHE_CHAIN_1Q.id to chainSites,
            RenderArm.DRAGO_CLAHE_CHAIN_BF_1Q.id to chainBfSites,
            // ④ arm은 같은 셰이더를 쓴다(박스 개수·출처만 다르다). 여기 계수는 전부 0이어야
            // 하고, 그 0이 "오버레이가 색공간 변환을 하지 않는다"는 기계 확증이다.
            // 🔴 ③→④ 연결 arm 둘도 **같은 셰이더**다 — 박스가 탐지 결과라고 셰이더가 바뀌지
            //    않는다(색은 정점 속성으로 들어간다). 여기서 빠뜨리면 그 arm의
            //    color_transform_sites가 비고 "셰이더를 세지 못했다"는 **거짓 사유**가 나간다.
            RenderArm.HIGHLIGHT_BOXES.id to overlaySites,
            RenderArm.HIGHLIGHT_BOXES_STRESS.id to overlaySites,
            // 🔴 `highlight_boxes_1q`는 예전부터 빠져 있었다 — 그래서 그 arm의 session.json에
            //    "onSurfaceCreated가 돌기 전에 세션이 끝나 셰이더 소스를 세지 못했다"는
            //    **거짓 사유**가 나갔다(셌는데 키가 없었을 뿐이다). 계측 방식만 다른 arm이라
            //    셰이더가 짝과 같으므로 **같은 계수를 가리킨다**(다시 세지 않는다).
            RenderArm.HIGHLIGHT_BOXES_1Q.id to overlaySites,
            RenderArm.DETECT_CPU_HIGHLIGHT.id to overlaySites,
            RenderArm.DETECT_CPU_HIGHLIGHT_1Q.id to overlaySites,
            // 🔴 ②③④ 통합 arm 3종. 셋은 **같은 셰이더 문자열**을 컴파일한다(`_1q`는 계측
            //    방식만, `_nofill`은 정점 생성만 다르다) → 같은 계수를 가리킨다.
            RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT.id to chainHighlightSites,
            RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT_1Q.id to chainHighlightSites,
            RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL.id to chainHighlightSites,
        )
        oesTextureId = createOesTexture()
        Matrix.setIdentityM(texMatrix, 0)
        pendingRecvNs.set(NO_FRAME)

        // 컨텍스트가 재생성됐는데 이미 계측 arm이면 여기서 다시 준비한다.
        if (arm != RenderArm.PASSTHROUGH) {
            gpuTimer.setPassPlan(
                renderPasses = arm.renderPassCount,
                queryColumns = arm.gpuColumns.size,
                singleFrameQuery = arm.usesSingleFrameQuery,
            )
            gpuTimer.prepare()
        }

        val created = SurfaceTexture(oesTextureId)
        created.setOnFrameAvailableListener(this, frameSignalHandler)
        surfaceTexture = created
        Log.i(
            TAG,
            "GL 준비 완료 (oesTexture=$oesTextureId, arm=${arm.id}, " +
                "gl=${capabilities?.version}, ctx=${capabilities?.contextVersion})"
        )
        onGlReady?.invoke()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // ⚠ 뷰포트(화면)와 처리 해상도는 **분리 관리**한다. 화면 크기가 바뀌어도 FBO는
        //   협상된 처리 해상도 그대로여야 한다 — 여기서 fboWidth/Height를 건드리지 않는다.
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // ① 프레임 도착 시각을 **먼저** 확정한다.
        //    순서를 뒤집으면 이 사이에 onFrameAvailable이 끼어들어 t_recv > t_render_start 가
        //    될 수 있고, 하네스 교차검사 A(render_latency <= recv_to_render)가 **거짓 위반**을
        //    낸다. AtomicLong 읽기 하나이므로 "onDrawFrame 진입 직후"라는 규약과 어긋나지 않는다.
        val tRecvNs = pendingRecvNs.getAndSet(NO_FRAME)
        // ① 바로 뒤, `t_render_start_ns`를 찍기 **전에** ④ 게시 스냅샷을 집는다.
        //    🔴 순서를 뒤집으면 이 사이에 탐지 워커가 게시해 **t_overlay_source_ns >
        //    t_render_start_ns**가 될 수 있고, 그건 "미래에 게시된 결과를 썼다"가 되어
        //    하네스가 신선도 폐기로 세고 시계 순서 결함으로 경고한다
        //    (스키마 v7의 요구다. DetectOverlayPublisher의 게시 시각 KDoc과 짝이다).
        //    ⚠ ③ 결과를 그리는 arm이 아니면 **참조 읽기조차 하지 않는다** — 다른 arm의
        //      프레임 경로에 이 배선이 새지 않게 하는 게이트다(parity 덤프와 같은 관행).
        frameOverlaySnapshot =
            if (arm.usesDynamicHighlightBoxes) overlayPublisher.latest() else null
        frameStageHNs = FrameLogRecorder.MISSING_NS
        frameOverlayBoxes = FrameLogRecorder.OVERLAY_BOXES_UNRECORDED
        frameOverlayFillFracScaled = FrameLogRecorder.OVERLAY_FILL_FRAC_UNRECORDED
        val tRenderStartNs = SystemClock.elapsedRealtimeNanos()
        val hasNewFrame = tRecvNs != NO_FRAME

        var tCaptureNs = MISSING
        val texture = surfaceTexture
        if (texture != null && hasNewFrame) {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
            // 원본 그대로 기록한다. 앱이 보정하면 어느 시계 기준인지 판별할 근거가 사라진다.
            tCaptureNs = texture.timestamp
            collectClockProbe(tCaptureNs)
        }

        // 계측은 **측정 중 + 새 프레임이 있는 드로우**로만 한다. 그래야 CSV 행 하나에
        // query 한 벌이 정확히 대응하고, 대기 화면에서 GPU 동작을 바꾸지 않는다.
        dispatchDraw(hasNewFrame && recorder.isRecording)

        val tRenderEndNs = SystemClock.elapsedRealtimeNanos()
        if (hasNewFrame) {
            val slot = recorder.record(
                tRecvNs, tCaptureNs, tRenderStartNs, tRenderEndNs,
                stageHNs = frameStageHNs,
                overlayBoxes = frameOverlayBoxes,
                // 🔴 **박스가 0개(빈 결과)여도 게시 시각을 적는다** — "결과가 없다"와
                //    "빈 결과가 있다"는 다른 사실이고, 후자에 -1을 적으면 신선도 분포가
                //    박스가 있는 프레임 쪽으로만 치우친다(스키마 v7).
                tOverlaySourceNs =
                    frameOverlaySnapshot?.publishedNs ?: FrameLogRecorder.MISSING_NS,
                overlayFillFracScaled = frameOverlayFillFracScaled,
            )
            // 이번 프레임에 건 query가 어느 행을 채워야 하는지 여기서 확정한다.
            // 계측하지 않은 프레임에서 불러도 안전하다(링이 스스로 걸러 낸다).
            gpuTimer.commitFrame(slot)
        } else {
            recorder.noteDrawWithoutNewFrame()
        }
    }

    // ── SurfaceTexture.OnFrameAvailableListener ───────────────────────────

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        // 2-C에는 분석 콜백이 없으므로 이것이 "프레임 도착" 신호다.
        // 시계는 elapsedRealtimeNanos 하나로 통일한다 — System.nanoTime()을 섞으면
        // 하네스 교차검사가 잡아내고 그 로그의 체류시간은 지연 근거로 못 쓴다.
        pendingRecvNs.set(SystemClock.elapsedRealtimeNanos())
        if (recorder.isRecording) {
            recorder.surfaceFramesAvailable.incrementAndGet()
        }
        onFrameSignal?.invoke()
    }

    // ── FrameTarget (메인 스레드에서 불린다) ──────────────────────────────

    override fun acquireSurface(width: Int, height: Int): Surface? {
        val texture = surfaceTexture ?: return null
        texture.setDefaultBufferSize(width, height)
        // 처리 해상도의 유일한 출처. 하드코딩하지 않는다.
        processWidth = width
        processHeight = height
        val surface = Surface(texture)
        cameraSurface = surface
        return surface
    }

    override fun releaseSurface(surface: Surface, resultCode: Int) {
        Log.i(TAG, "Surface 해제 (resultCode=$resultCode)")
        if (cameraSurface === surface) {
            cameraSurface = null
        }
        surface.release()
    }

    override fun updatePreviewTransform(transform: PreviewTransform) {
        // 🔴 **`hasCameraTransform=true`면 표시 회전은 0이다.** 그 플래그는 "CameraX가 표시
        //    회전을 **이미 `SurfaceTexture`의 `texMatrix`에 넣어 줬다**"는 뜻이고, 패스1이
        //    그 행렬로 샘플링하므로 **영상은 그것만으로 바로 선다.** 여기서 또 돌리면 두 번
        //    도는 것이다 — A34에서 실제로 그렇게 돼서 바로 서 있던 영상이 시계 90°로
        //    떨어져 나갔다(실측: applied=90, 육안으로 영상이 90° 누움).
        //    ⚠ 예전 코드는 이 자리에서 `targetRotation`(표시 방향 상수)을 degrees로 바꿔
        //      썼고, 세로에서 그 값이 우연히 ROTATION_0=0이라 **맞는 것처럼 보였다.**
        //      cardboard(LANDSCAPE 강제)에서는 ROTATION_90=1이 되어 1도가 나갔다.
        //      이제는 우연이 아니라 규약으로 0이다.
        // 🔴 present 회전 기계장치(플래그·전용 셰이더)는 **그대로 둔다** —
        //    `hasCameraTransform=false` 경로에서는 여전히 우리가 돌려야 한다. 이 기기에서
        //    값이 0이 될 뿐이고, 패스1이 영상만 돌리던 구조적 불일치를 없앤 것도 유효하다.
        requestedPreviewRotationDegrees =
            if (transform.hasCameraTransform) 0 else transform.rotationDegrees
        // 🔴 **박스 회전도 같은 조건으로 0이다.** `hasCameraTransform=true`면 Preview와
        //    ImageAnalysis가 **같은 방향 기준 위에** 있고 CameraX가 표시 방향을 처리하므로,
        //    영상에도 박스에도 추가 회전이 필요 없다.
        //    ⚠ 이것은 실기기 판정이다: `표시 0 · 박스 90`으로 빌드했더니 영상은 바로 섰고
        //      (스크린샷 확정) **박스만 시계 90° 어긋났다** — 우리가 건 것이 시계 90°이므로
        //      옳은 값은 0이다.
        //    🔴 그렇게 보면 결함이 하나로 정리된다: 세로 normal에서 옛 코드는
        //      `targetRotation=ROTATION_0`이라 0이 나와 **원래 맞았고**, cardboard에서
        //      `ROTATION_90`이 1도가 아니라 90도로 읽혀 **영상만 돌았다**(박스는 안 돌았다).
        //      진짜 결함은 처음부터 **"회전각을 `targetRotation`에서 뽑은 것" 하나뿐**이었다.
        //    ⚠ **`hasCameraTransform=false` 경로는 이 기기에서 한 번도 밟히지 않았다** —
        //      그쪽 각도(그리고 [OverlayCoordMap.BOX_ROTATION_CLOCKWISE]의 방향)는
        //      **실기기 미검증**이다. 기계장치를 남겨 두는 이유가 그 경로다.
        requestedOverlayRotationDegrees =
            if (transform.hasCameraTransform) 0 else transform.rotationDegrees
        requestedPreviewMirror = transform.mirroring
        // 🔴 **원값 4개를 그대로 남긴다.** 위 원인을 갈라낸 것이 이 기록이다 —
        //    `rotation_degrees`와 `target_rotation`을 나란히 찍어 두지 않았다면 "어느 값을
        //    썼는가"를 되물을 수 없었다. 분기가 사라진 지금도 지우지 않는다.
        // ⚠ 이 콜백은 main executor 하나에서만 온다(CameraFrameSource.provideSurface)라
        //    증가 연산에 락이 필요 없다. 읽는 쪽이 GL 스레드라 @Volatile은 필요하다.
        previewTransformArrivals += 1
        previewTransformNote =
            "도착 #$previewTransformArrivals: rotation_degrees=${transform.rotationDegrees} " +
                "target_rotation=${transform.targetRotation} " +
                "has_camera_transform=${transform.hasCameraTransform} " +
                "mirroring=${transform.mirroring} " +
                "→ 표시(present 정점) 회전각=$requestedPreviewRotationDegrees " +
                "· ④ 박스 회전각=$requestedOverlayRotationDegrees " +
                "(🔴 **has_camera_transform=true면 둘 다 0이 정상이다** — CameraX가 표시 " +
                "방향을 처리하고 Preview와 ImageAnalysis가 같은 기준 위에 있어 영상에도 " +
                "박스에도 추가 회전이 필요 없다. 실기기 판정이며, 박스를 90 돌렸더니 " +
                "박스만 시계 90° 어긋났다. false 경로는 **실기기 미검증**이다. " +
                "target_rotation은 표시 방향 상수라 degrees가 아니며 참고로만 싣는다)"
        Log.i(TAG, "표시 변환 $previewTransformNote")
    }

    // ── arm 전환 (GL 스레드에서 부른다: glView.queueEvent) ────────────────

    /**
     * arm을 바꾼다. **측정 중에는 부르지 않는다** — 런 도중 arm이 바뀌면 그 분포는 오염된
     * 것이다(UI에서 스피너를 잠근다).
     */
    fun setArm(next: RenderArm) {
        if (arm == next) return
        arm = next
        if (next == RenderArm.PASSTHROUGH) {
            // 1패스 경로는 뷰포트를 스스로 세우지 않는다(기존 경로 그대로 두기 위해서다).
            // 3패스에서 처리 해상도로 바꿔 둔 뷰포트를 여기서 화면 크기로 되돌린다.
            releaseOffscreen()
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            }
            offscreenStatus = "arm=passthrough — 오프스크린을 만들지 않는다(기존 1패스 재현)"
        } else {
            // 패스 수와 계측 모드는 arm이 정한다(RenderArm.renderPassCount / gpuColumns /
            // usesSingleFrameQuery). prepare 전에 알려 줘야 링이 이번 arm의 계획으로
            // 엔트리를 건다. ⚠ **렌더 패스 수와 열 수를 따로 넘긴다** — 프레임 단일 query
            // arm은 렌더 패스 3~9개에 열이 1개라 한 값으로 뭉치면 링이 첫 패스만 감싼다.
            gpuTimer.setPassPlan(
                renderPasses = next.renderPassCount,
                queryColumns = next.gpuColumns.size,
                singleFrameQuery = next.usesSingleFrameQuery,
            )
            // 일회성 프로브와 query 객체 생성을 **측정 전에** 끝내 둔다. 지연 초기화에
            // 맡기면 그 비용이 측정 첫 프레임에 얹힌다.
            gpuTimer.prepare()
        }
        Log.i(TAG, "arm 전환: ${next.id}")
    }

    // ── 측정 부수 정보 ───────────────────────────────────────────────────

    /** GL 스레드에서 부른다(표본을 소유한 스레드가 GL 스레드다). */
    fun setDisplayMode(next: DisplayMode) {
        displayMode = next
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        }
    }

    /**
     * 시연용 ②·④ 토글을 세운다. **GL 스레드에서 부른다**(`glView.queueEvent` —
     * [setArm]·[setDisplayMode]와 같은 관행이다).
     *
     * 🔴 UI 스레드에서 직접 부르면 프레임 **중간에** 값이 갈려 그 프레임의 `overlay_boxes`와
     * 실제 화면이 어긋난다. `@Volatile`은 가시성만 보장하지 원자적 프레임 경계를 주지 않는다.
     */
    fun setDemoToggles(stage2: Boolean, overlay: Boolean) {
        stage2Enabled = stage2
        overlayEnabled = overlay
    }

    fun setCardboardTuning(imageScale: Float, eyeOffset: Float) {
        cardboardImageScale = imageScale.coerceIn(MIN_CARDBOARD_IMAGE_SCALE, 1f)
        cardboardEyeOffset = eyeOffset.coerceIn(
            -MAX_CARDBOARD_EYE_OFFSET,
            MAX_CARDBOARD_EYE_OFFSET,
        )
    }

    fun clockVerdict(): CaptureClockVerdict = CaptureClockProbe.resolve(probeSamples.toList())

    /** 측정 시작 시 GL 스레드에서 부른다. */
    fun resetClockProbe() {
        probeSamples.clear()
    }

    /** 측정 시작 시 GL 스레드에서 부른다. 런별로 세야 의미가 있는 카운터들. */
    fun resetRenderCounters() {
        offscreenFallbackDraws = 0
        // 세대를 올려 이전 런의 in-flight query 결과가 이번 런의 행에 들어가는 것을 막는다.
        gpuTimer.beginRun()
        // 🔴 H칸의 런 단위 상태도 여기서 내린다. 안 내리면 **직전 런의 트랙이 이 런의 첫
        //    프레임에 그려지고** 누계가 남의 런 값과 합쳐진다(DetectPipeline.lastLetterbox
        //    누수와 같은 실패 양식이다 — 이 객체는 Activity 수명이고 값은 런 단위 사실이다).
        overlaySmoother.reset()
        frameOverlaySnapshot = null
        frameStageHNs = FrameLogRecorder.MISSING_NS
        frameOverlayBoxes = FrameLogRecorder.OVERLAY_BOXES_UNRECORDED
        frameOverlayFillFracScaled = FrameLogRecorder.OVERLAY_FILL_FRAC_UNRECORDED
    }

    /**
     * H칸의 런 사실. **정지 뒤 GL 스레드에서 부른다**(그 상태를 소유한 스레드가 GL 스레드다).
     * ③ 결과를 그리는 arm이 아니면 값이 전부 0이고 `session.json`이 블록을 내지 않는다.
     */
    fun overlaySmootherFacts(): OverlaySmootherFacts = OverlaySmootherFacts(
        tracksCreated = overlaySmoother.tracksCreated,
        tracksExpired = overlaySmoother.tracksExpired,
        pendingPromoted = overlaySmoother.pendingPromoted,
        pendingDiscarded = overlaySmoother.pendingDiscarded,
        droppedOverCap = overlaySmoother.droppedOverCap,
        mapFailedFrames = overlaySmoother.mapFailedFrames,
        appliedBoxRotationDegrees = overlaySmoother.appliedBoxRotationDegrees,
    )

    /**
     * 측정 정지 후 `frames.csv`를 쓰기 **직전에** GL 스레드에서 부른다.
     * 반환값의 `instrumented`가 CSV에 GPU 열을 실을지를 정하고, 나머지는 `session.json`의
     * `gpu_timer` 블록이 된다.
     */
    fun finishGpuTimerRun(): GpuTimerReport = gpuTimer.finishRun()

    // ── 내부 ─────────────────────────────────────────────────────────────

    private fun collectClockProbe(tCaptureNs: Long) {
        if (!recorder.isRecording) return
        if (tCaptureNs <= 0L) return
        if (probeSamples.size >= CaptureClockProbe.SAMPLE_LIMIT) return
        // 두 시계를 나란히 읽는다. 측정 초반 몇 장에만 추가되는 비용이다.
        probeSamples.add(
            ClockProbeSample(
                tCaptureNs = tCaptureNs,
                monotonicNs = System.nanoTime(),
                boottimeNs = SystemClock.elapsedRealtimeNanos(),
            )
        )
    }

    /**
     * arm 하나로 갈린다. **passthrough는 새 코드 경로를 타지 않는다** — FBO도 만들지 않고,
     * GPU timer query도 걸지 않고, 아래 [drawPassthrough]로 바로 간다.
     */
    private fun dispatchDraw(instrument: Boolean) {
        val oes = oesProgram ?: return
        if (arm == RenderArm.PASSTHROUGH) {
            drawPassthrough(oes)
            return
        }
        // 🔴 **present는 blitProgram이 아니다.** 마지막 패스만 표시 회전을 걸어야 하므로
        //    `uPositionMatrix`가 있는 전용 프로그램을 쓴다. 오프스크린 복사 자리(② 자리)는
        //    여전히 blitProgram이다 — 거기가 돌면 ④ 오버레이와 축이 다시 갈린다.
        val present = presentProgram
        // 🔴 **usesHighlightOverlay 분기보다 앞에 있어야 한다.** 통합 arm도
        //    usesHighlightOverlay가 true이므로(overlay 블록·overlayStatus의 게이트다) 뒤에
        //    두면 9패스 arm이 4패스 단순 복사 경로로 떨어진다 — ② 체인이 조용히 사라지고
        //    로그만 보면 그럴듯하다.
        if (arm.usesChainedHighlight) {
            // 🔴 **복제 프로그램이다** — 시연 ② 토글의 mix(uEnhance)가 붙은 쪽이고,
            //    공유본(chainDragoApplyProgram)은 다른 여덟 arm의 것이다.
            val dragoApply = demoChainDragoApplyProgram
            val claheApply = demoChainClaheApplyProgram
            if (dragoApply == null || claheApply == null || present == null ||
                !chainStage.ready || !highlightOverlay.ready || !ensureOffscreen()
            ) {
                offscreenFallbackDraws++
                // 🔴 야간 실외 스모크에서 처음 도는 경로다 — 실패하면 그 자리에서 원인을
                //    갈라야 하므로 **어느 준비가 실패했는지**를 logcat에 한 번 남긴다.
                //    (session.json에는 stage2_status / overlay.gpu_status /
                //     offscreen_status 셋이 같은 사실을 문장으로 낸다.)
                logChainHighlightFallbackOnce(present, dragoApply, claheApply)
                drawPassthrough(oes)
                return
            }
            drawChainedHighlight(oes, dragoApply, claheApply, present, instrument)
            return
        }
        if (arm.usesHighlightOverlay) {
            // ② 자리는 단순 복사다(3패스 골격의 blit_2pass arm과 같다).
            // 🔴 예전에는 여기에 `present`를 두 번 넘겨 복사와 표시가 **같은 프로그램**을
            //    썼다. 이제는 다르다 — 복사는 회전이 없는 blitProgram, 표시는 회전을 거는
            //    presentProgram이다. 한 개로 되돌리면 ② 자리 FBO가 함께 돌아
            //    ④ 오버레이가 회전 뒤 좌표계에 그려진다(축이 다시 갈린다).
            val stage2Copy = blitProgram
            if (present == null || stage2Copy == null ||
                !highlightOverlay.ready || !ensureOffscreen()
            ) {
                offscreenFallbackDraws++
                drawPassthrough(oes)
                return
            }
            drawHighlightOverlay(oes, stage2Copy, present, instrument)
            return
        }
        if (arm.usesChainedBilateral) {
            val dragoApply = chainDragoApplyProgram
            val claheApply = chainClaheApplyProgram
            val denoise = bilateralProgram
            if (dragoApply == null || claheApply == null || denoise == null || present == null ||
                !chainStage.ready || !bilateralStage.ready || !ensureOffscreen()
            ) {
                offscreenFallbackDraws++
                drawPassthrough(oes)
                return
            }
            drawChainedBilateral(oes, dragoApply, claheApply, denoise, present, instrument)
            return
        }
        if (arm.usesFusedBilateral) {
            val fusedApply = fusedApplyProgram
            val denoise = bilateralProgram
            if (fusedApply == null || denoise == null || present == null ||
                !fusedStage.ready || !bilateralStage.ready || !ensureOffscreen()
            ) {
                offscreenFallbackDraws++
                drawPassthrough(oes)
                return
            }
            drawFusedBilateral(oes, fusedApply, denoise, present, instrument)
            return
        }
        if (arm.usesChainedComputeStage2) {
            val dragoApply = chainDragoApplyProgram
            val claheApply = chainClaheApplyProgram
            if (dragoApply == null || claheApply == null || present == null ||
                !chainStage.ready || !ensureOffscreen()
            ) {
                offscreenFallbackDraws++
                drawPassthrough(oes)
                return
            }
            drawChainedComputeStage2(oes, dragoApply, claheApply, present, instrument)
            return
        }
        if (arm.usesFusedComputeStage2) {
            val fusedApply = fusedApplyProgram
            if (fusedApply == null || present == null ||
                !fusedStage.ready || !ensureOffscreen()
            ) {
                offscreenFallbackDraws++
                drawPassthrough(oes)
                return
            }
            drawFusedComputeStage2(oes, fusedApply, present, instrument)
            return
        }
        if (arm.usesSingleComputeStage2) {
            val stage = computeStage(arm)
            val apply = computeApplyProgram(arm)
            if (stage == null || apply == null || present == null ||
                !stage.ready || !ensureOffscreen()
            ) {
                offscreenFallbackDraws++
                drawPassthrough(oes)
                return
            }
            drawComputeStage2(oes, stage, apply, present, instrument)
            return
        }
        val stage2 = if (arm == RenderArm.GAMMA_ONLY) gammaProgram else blitProgram
        if (stage2 == null || present == null || !ensureOffscreen()) {
            // 3패스를 못 돌면 조용히 넘어가지 않는다. 세어서 session.json에 남긴다 —
            // 선언한 pipeline_stages와 실제 경로가 어긋난 채로 숫자가 나가면 안 된다.
            offscreenFallbackDraws++
            drawPassthrough(oes)
            return
        }
        drawThreePass(oes, stage2, present, instrument)
    }

    /** 기존 1패스. 승격 베이스라인 재현 경로이므로 호출 순서를 바꾸지 않는다. */
    private fun drawPassthrough(oes: QuadProgram) {
        // 타일 기반 GPU(Mali-G68)에서 clear를 생략하면 타일 버퍼를 이전 내용으로 채워 넣는
        // load 비용이 생긴다. 화면 전체를 덮는 quad라도 clear를 부르는 쪽이 싸다.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        presentTexture(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
    }

    /**
     * 3패스 골격. 패스마다 바인드·뷰포트·clear를 **명시**한다.
     * 이걸 생략하면 드라이버가 렌더패스를 병합할 수 있고, 그러면 패스별 timer query를
     * 붙여도 어느 패스의 비용인지 귀속이 흐려진다.
     *
     * ### query 경계를 어디에 두는가 (타일 GPU에서 정직해야 하는 지점)
     * 세 query가 이 시퀀스의 **모든 GL 명령을 빈틈없이** 덮도록 걸었다 —
     * `beginPass()` … `endPass()`가 연달아 붙어 있고 그 사이에 아무 명령도 없다.
     *
     * ⚠ **그러나 "명령을 덮는다"는 "그 명령이 유발한 GPU 작업을 덮는다"가 아니다.**
     * 타일 기반 GPU는 드로우콜을 즉시 실행하지 않고 타일 단위로 몰아 처리하므로, 어떤
     * 작업이 어느 query 구간에 담기는지는 드라이버가 정한다. 확실한 것 하나:
     * **패스3은 기본 프레임버퍼에 그리는데 그 타일 해결은 `eglSwapBuffers`에서 일어나고,
     * `GLSurfaceView`는 그것을 `onDrawFrame` 반환 **후에** 부른다** — 세 query 전부의
     * 바깥이다. `GLSurfaceView`를 쓰는 한 이 경계는 우리가 옮길 수 없다.
     *
     * 그래서 두 갈래가 있고 **어느 쪽이든 "합이 정확하다"고는 말할 수 없다:**
     *  - 드라이버가 `glEndQuery`에서 렌더패스를 쪼갠다 → 계측이 측정 대상 워크로드 자체를
     *    바꾸고 있다.
     *  - 쪼개지 않는다 → 온스크린 해결 비용이 `gpu_present_ms` 밖으로 떨어져 `gpu_sum_ms`가
     *    **과소**가 된다.
     *
     * **우리는 이 기기에서 둘 중 어느 쪽인지 판별하지 못했다.** 따라서 개별 열의 경계는
     * ±1패스만큼 흐리고, **합도 하한으로 읽어야 한다.** 같은 문장이 `session.json`의
     * `gpu_timer.attribution_note`에도 나간다.
     */
    private fun drawThreePass(
        oes: QuadProgram,
        stage2: QuadProgram,
        present: QuadProgram,
        instrument: Boolean,
    ) {
        val timing = instrument && gpuTimer.beginFrame()

        // 패스1: OES → FBO_A (처리 해상도). stage_b_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        // 같은 텍스처 유닛에 OES와 2D를 동시에 물려 두면 드라이버에 따라 경고·미정의가 된다.
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        if (timing) gpuTimer.endPass()

        // 패스2: FBO_A → FBO_B (처리 해상도). ②가 들어갈 자리. stage_d_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(stage2, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()

        // 패스3: FBO_B → 화면 (surface 크기). gpu_present_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        presentTexture(present, GLES20.GL_TEXTURE_2D, fboTextures[1])
        if (timing) gpuTimer.endPass()
    }

    /**
     * arm → 그 arm의 통계 단계. **3단 한 벌**([RenderArm.usesSingleComputeStage2]) arm이
     * 아니면 null이다 — 조합 arm은 3단이 두 벌이라 이 인터페이스에 담기지 않고
     * [chainStage]가 따로 받는다. **프레임당 할당 0**이다.
     */
    private fun computeStage(target: RenderArm): Stage2ComputeStage? = when (target) {
        RenderArm.DRAGO -> dragoStage
        RenderArm.CLAHE_GAMMA -> claheStage
        RenderArm.AGCWD -> agcwdStage
        else -> null
    }

    /** arm → 그 arm의 적용 패스 프로그램. 만들지 못했으면 null. */
    private fun computeApplyProgram(target: RenderArm): QuadProgram? = when (target) {
        RenderArm.DRAGO -> dragoApplyProgram
        RenderArm.CLAHE_GAMMA -> claheApplyProgram
        RenderArm.AGCWD -> agcwdApplyProgram
        else -> null
    }

    /** arm → 그 arm의 적용 패스가 [programFailureLogs]에 원문을 남기는 키. */
    private fun computeApplyProgramLabel(target: RenderArm): String = when (target) {
        RenderArm.DRAGO -> PROGRAM_LABEL_DRAGO_APPLY
        RenderArm.CLAHE_GAMMA -> PROGRAM_LABEL_CLAHE_APPLY
        RenderArm.AGCWD -> PROGRAM_LABEL_AGCWD_APPLY
        else -> "(적용 패스가 없는 arm: ${target.id})"
    }

    /**
     * 적용 프래그먼트를 못 만들었을 때 `gpu_status`로 나가는 문장.
     *
     * 🔴 **성공 서술을 붙이지 않는다.** 이전 판은 `"...만들지 못했다 — " + stage.status`였고,
     * 통계 패스는 준비된 경우가 많아 그 뒤에 `"준비 완료 — ..."`가 그대로 이어졌다. 앞부분을
     * 놓치면 정상으로 읽히고, 실제로 그렇게 융합 arm의 11분 런이 통째로 무효가 됐다
     * (전 프레임이 패스스루로 폴백했는데 `gpu_status`에 "준비 완료"가 보였다).
     * 그래서 스테이지가 준비된 경우에는 스테이지 문장을 **인용하지 않고**, 실패 사실과
     * 컴파일러 원문만 낸다. 스테이지도 실패했을 때만 그 사유를 잇는다(그건 실패 서술이다).
     *
     * 컴파일러 원문을 함께 내는 이유는 [programFailureLogs].
     */
    private fun applyProgramFailureStatus(
        stageReady: Boolean,
        stageStatus: String,
        labels: List<String>,
    ): String {
        val logs = labels.mapNotNull { label ->
            programFailureLogs[label]?.let { "[$label] $it" }
        }
        val diagnostics = if (logs.isEmpty()) {
            "컴파일러 원문을 잡지 못했다(대상 라벨=${labels.joinToString("/")})"
        } else {
            "컴파일러 원문 = " + logs.joinToString(" || ")
        }
        val stagePart = if (stageReady) {
            // 통계 패스가 준비됐다는 사실은 **여기서는 위안이 아니다** — 적용이 없으면 이 arm은
            // 그릴 수 없다. 그래서 스테이지의 "준비 완료 — ..." 문장을 옮기지 않는다.
            "통계 패스(컴퓨트·SSBO)는 준비됐으나 적용 패스가 없어 arm 전체가 실패다"
        } else {
            "통계 패스도 준비되지 않았다 — $stageStatus"
        }
        // 원문을 **맨 뒤**에 둔다 — 드라이버 문장이 마침표로 끝나므로 가운데 끼우면 마침표가
        // 겹치고, 무엇보다 "무슨 일이 났는가"를 먼저 읽게 해야 한다.
        return "실패: 적용 패스(프래그먼트) 프로그램을 만들지 못했다. 이 arm은 그릴 수 없고 " +
            "모든 프레임이 패스스루로 폴백한다(render.processing." +
            "frames_fell_back_to_passthrough 확인). $stagePart. $diagnostics"
    }

    /**
     * ② 컴퓨트 arm의 5패스. [drawThreePass]의 패스2 자리가 **통계 → LUT·계수 → 적용**으로
     * 벌어진 것이고, 나머지(패스1·표시)는 글자 그대로 같은 코드다.
     *
     * **세 arm(`drago`·`clahe_gamma`·`agcwd`)이 이 함수 하나를 공유한다.** arm마다 복붙하면
     * GL 호출 시퀀스가 조용히 갈라져 패스 비용을 arm끼리 비교하는 근거가 흔들린다 —
     * 그 비교가 이 라운드의 목적이다.
     *
     * 🔴 **조합 arm은 여기 넣지 않는다.** 이 함수는 이미 승격된 세 arm의 재현 경로이므로
     * 분기를 더하면 그 숫자의 근거가 바뀐다 → [drawChainedComputeStage2]로 따로 뒀다.
     *
     * ### 왜 하위 3단을 한 query로 묶지 않는가
     * `docs/FRAME_LOG_SCHEMA.md` §2가 금지한다 — 앱이 합치면 그건 유도값이고, **어느 패스가
     * 비싼지가 사라진다.** 게이트를 넘었을 때 팀장에게 넘길 것이 정확히 그 정보다
     * (통계가 지배하는지 적용이 지배하는지에 따라 경량화 레버가 완전히 달라진다).
     *
     * ### 컴퓨트 패스의 귀속은 프래그먼트 패스보다 **덜** 흐리다
     * `glDispatchCompute`는 타일러를 거치지 않으므로 `drawThreePass`의 주의사항(렌더패스
     * 병합)이 그대로 적용되지는 않는다. 다만 `glMemoryBarrier`의 실제 대기가 어느 query에
     * 담기는지는 여전히 드라이버가 정한다 — 배리어를 **소비하는 쪽 패스의 맨 앞**에 두어
     * 대기 비용이 소비자에게 청구되게 했다(임의 선택이며 `session.json`에 그대로 적는다).
     */
    private fun drawComputeStage2(
        oes: QuadProgram,
        stage: Stage2ComputeStage,
        apply: QuadProgram,
        present: QuadProgram,
        instrument: Boolean,
    ) {
        val timing = instrument && gpuTimer.beginFrame()

        // 패스1: OES → FBO_A (처리 해상도). stage_b_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        if (timing) gpuTimer.endPass()

        // 패스2: 통계 산출(전역 리덕션 / 타일별 히스토그램). stage_d_analyze_ms.
        // ⚠ FBO_A를 **어태치먼트에서 떼고** 나서 텍스처로 읽는다. 붙여 둔 채로 샘플링하면
        //   피드백 루프이고 결과가 미정의다.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        stage.analyze(fboTextures[0], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스3: 통계 → LUT·계수. stage_d_build_ms.
        if (timing) gpuTimer.beginPass()
        stage.build()
        if (timing) gpuTimer.endPass()

        // 패스4: FBO_A → FBO_B. ② 적용. stage_d_apply_ms.
        if (timing) gpuTimer.beginPass()
        stage.beforeApply()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(apply, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()

        // 패스5: FBO_B → 화면 (surface 크기). gpu_present_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        presentTexture(present, GLES20.GL_TEXTURE_2D, fboTextures[1])
        if (timing) gpuTimer.endPass()
    }

    /**
     * ② **조합** arm(`drago_clahe_chain`)의 8패스. [drawComputeStage2]의 3단이 **두 벌
     * 직렬로** 벌어지고 FBO를 핑퐁한다:
     * ```
     * 패스1  OES   → FBO_A                     stage_b_ms
     * 패스2  drago analyze (FBO_A)             stage_d_analyze_ms
     * 패스3  drago build                       stage_d_build_ms
     * 패스4  drago apply   FBO_A → FBO_B       stage_d_apply_ms
     * 패스5  clahe analyze (FBO_B)             stage_d_analyze2_ms
     * 패스6  clahe build                       stage_d_build2_ms
     * 패스7  clahe apply   FBO_B → FBO_A       stage_d_apply2_ms
     * 패스8  present       FBO_A → 화면        gpu_present_ms
     * ```
     *
     * ### 왜 [drawComputeStage2]를 고쳐 쓰지 않고 함수를 따로 만드는가
     * 그 함수는 **이미 승격된 `drago`·`clahe_gamma`·`agcwd` 숫자의 재현 경로**다. 조합을
     * 담으려고 그 안에 분기를 넣으면 세 arm의 GL 호출 시퀀스가 바뀌고, 그러면 `docs/baselines/`
     * 의 기존 숫자와 비교할 근거가 사라진다. 같은 판단의 선례가 [Stage2ComputeStage]의
     * `compileComputeProgram` 주석에 있다("drago는 실측이 끝난 arm이라 그 파일의 동작 경로를
     * 건드리지 않는다"). **중복은 알면서 감수한 비용이고, 그 대가로 기존 arm이 고정된다.**
     *
     * ### 패스 수와 열 개수는 정확히 맞아야 한다
     * 이 arm은 8패스다. **예전에는 그것이 [GpuTimerRing.MAX_PASS_COUNT]와 정확히 같아 여유가
     * 0이었는데**, `bf` arm 2개와 ④ 오버레이 arm 2개가 들어오면서 그 상수를 12로 올렸다 —
     * 여유는 생겼지만 아래 규칙은 그대로다. [GpuTimerRing.beginPass]/[GpuTimerRing.endPass]
     * 호출 수가 [RenderArm.gpuColumns]의 개수와 다르면 `commitFrame`이 그 프레임을 통째로
     * 버린다(`malformedFrames`) — 여기서 패스를 더하거나 빼면 그렇게 된다.
     *
     * 패스 경계·배리어·query 귀속의 주의사항은 [drawThreePass]·[drawComputeStage2]와 같다.
     */
    private fun drawChainedComputeStage2(
        oes: QuadProgram,
        dragoApply: QuadProgram,
        claheApply: QuadProgram,
        present: QuadProgram,
        instrument: Boolean,
    ) {
        val timing = instrument && gpuTimer.beginFrame()

        // 패스1: OES → FBO_A (처리 해상도). stage_b_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        if (timing) gpuTimer.endPass()

        // 패스2: drago 전역 통계. stage_d_analyze_ms.
        // ⚠ FBO_A를 어태치먼트에서 떼고 나서 텍스처로 읽는다(피드백 루프 방지).
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        chainStage.dragoAnalyze(fboTextures[0], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스3: drago 통계 → 톤커브 계수. stage_d_build_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.dragoBuild()
        if (timing) gpuTimer.endPass()

        // 패스4: FBO_A → FBO_B. drago 톤맵 적용. stage_d_apply_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.beforeDragoApply()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(dragoApply, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()

        // 패스5: clahe 타일 히스토그램. 입력은 **drago 출력(FBO_B)**이다. stage_d_analyze2_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        chainStage.claheAnalyze(fboTextures[1], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스6: clahe 클립 + 재분배 + CDF → 타일 LUT. stage_d_build2_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.claheBuild()
        if (timing) gpuTimer.endPass()

        // 패스7: FBO_B → FBO_A. clahe 적용(핑퐁으로 되돌아온다). stage_d_apply2_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.beforeClaheApply()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(claheApply, GLES20.GL_TEXTURE_2D, fboTextures[1])
        if (timing) gpuTimer.endPass()

        // 패스8: FBO_A → 화면 (surface 크기). gpu_present_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        presentTexture(present, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()
    }

    /**
     * ②③④ **통합** arm(`detect_cpu_chain_highlight`)의 9패스. [drawChainedComputeStage2]의
     * 8패스에서 present 앞에 **오버레이 패스 하나를 끼운** 것이다:
     * ```
     * 패스1  OES   → FBO_A                     stage_b_ms
     * 패스2  drago analyze (FBO_A)             stage_d_analyze_ms
     * 패스3  drago build                       stage_d_build_ms
     * 패스4  drago apply   FBO_A → FBO_B       stage_d_apply_ms
     * 패스5  clahe analyze (FBO_B)             stage_d_analyze2_ms
     * 패스6  clahe build                       stage_d_build2_ms
     * 패스7  clahe apply   FBO_B → FBO_A       stage_d_apply2_ms
     * 패스8  FBO_A에 ④ 오버레이 덧그림          stage_i_ms
     * 패스9  present       FBO_A → 화면        gpu_present_ms
     * ```
     *
     * ### 왜 [drawChainedComputeStage2]를 고쳐 쓰지 않고 함수를 따로 만드는가
     * 그 함수는 **이미 승격된 `drago_clahe_chain` 숫자의 재현 경로**이고, 그 KDoc이 그것을
     * 못 박고 있다. 여기에 분기를 넣으면 그 arm의 GL 호출 시퀀스가 바뀌어 `docs/baselines/`의
     * 기존 숫자와 비교할 근거가 사라진다. **중복은 알면서 감수한 비용이다** — 앞 7패스는
     * 그 함수와 **같은 순서·같은 SSBO**여야 두 arm의 차분이 ④의 비용이 된다.
     *
     * 🔴 **패스4·7의 프로그램은 예외다**(2026-08-30부터). 시연 ② 토글 때문에 이 arm만
     * `mix(uEnhance)`가 붙은 **복제 프래그먼트**를 쓴다([demoChainDragoApplyProgram]) —
     * 산식과 SSBO는 같지만 셰이더 문자열은 같지 않다
     * ([RenderArm.DEMO_APPLY_SHADER_VARIANT]가 `session.json`에 그 사실을 낸다).
     *
     * ### 🔴 패스8의 타깃이 `fbos[0]`(FBO_A)인 이유 — 이 함수 최대의 무음 실패 지점
     * 체인의 패스7이 clahe 출력을 **FBO_A**에 쓰고 패스9(present)가 **`fboTextures[0]`**을
     * 읽는다. 그런데 4패스 오버레이 arm([drawHighlightOverlay])의 오버레이 패스는 **FBO_B**에
     * 그린다(그 arm의 ② 자리가 거기 썼기 때문이다). 그 코드를 그대로 복사해 오면 박스가
     * **화면에 뜨지 않는데** `overlay_boxes`와 `stage_i_ms`는 정상값이 나온다 — 로그만 보면
     * 성공으로 읽히는 실패다. 그래서 여기서는 FBO_A를 바인드한다.
     *
     * ⚠ 그리고 **패스8에 `glClear`가 없다.** 오버레이는 ② 체인의 출력 **위에** 얹는 것이라
     * 지우면 그 프레임의 ② 결과가 통째로 사라진다(화면이 박스만 남은 검은 화면이 된다).
     * 그 대가로 타일 GPU가 컬러 어태치먼트를 다시 load하고 `stage_i_ms`에 그 비용이 섞인다 —
     * 게다가 패스7과 패스8의 타깃이 같은 FBO라 두 열의 경계도 흐려진다
     * ([RenderArm.chainHighlightTileReloadNote]가 그 사실을 `session.json`에 낸다 — 열 지목은
     * arm마다 다르다. `_1q` 짝에는 패스별 열이 없어 경계 문제 자체가 생기지 않는다).
     *
     * ### 패스 수는 정확히 9여야 한다
     * [GpuTimerRing.beginPass]/[GpuTimerRing.endPass] 호출 수가 [RenderArm.gpuColumns]의
     * 개수(9)와 다르면 `commitFrame`이 그 프레임을 **통째로 버린다**(`malformedFrames`).
     * [GpuTimerRing.MAX_PASS_COUNT]는 12라 여유가 있다.
     *
     * H칸(좌표 평활·hold)은 [drawHighlightOverlay]와 **같은 자리**(GPU 패스를 열기 전)에서
     * 돈다 — 이유는 [RenderArm.OVERLAY_STAGE_H_SCOPE].
     */
    private fun drawChainedHighlight(
        oes: QuadProgram,
        dragoApply: QuadProgram,
        claheApply: QuadProgram,
        present: QuadProgram,
        instrument: Boolean,
    ) {
        // ── ④ H칸(좌표 평활·hold) — 🔴 **GPU 패스를 열기 전에 끝낸다** ────────────
        // [drawHighlightOverlay]의 같은 블록과 **같은 자리·같은 값**이다(사본이지만 두 함수가
        // 서로 다른 패스 구성을 가지므로 함수를 합치지 않는다 — 위 KDoc의 판단 그대로다).
        // stage_h_ms는 CPU 벽시계이며 GPU query 안에 넣으면 어디에도 계상되지 않는다.
        val hStart = SystemClock.elapsedRealtimeNanos()
        // 🔴 **시연 토글과 무관하게 항상 돈다.** 이유 둘: (a) `stage_h_ms`가 네 토글 조합에서
        //    같은 것을 재야 비교가 된다, (b) 스무더가 hold·트랙 상태를 갖는다 — 건너뛰면
        //    재활성 순간 **낡은 트랙이 되살아나 유령 박스**가 뜬다.
        val drawn = overlaySmoother.update(
            frameOverlaySnapshot, fboWidth, fboHeight, requestedOverlayRotationDegrees
        )
        // 정점 재기록도 H 안이다 — 이 CPU 비용을 stage_i_ms(GPU 시계) 쪽에 두면 사라진다.
        // 🔴 fill 여부를 **인자로 넘긴다**(arm이 유일한 출처다 — RenderArm.drawsOverlayFill).
        //    오버레이에 상태로 두면 setArm의 조기 반환 경로에서 초기 arm에 플래그가 안 실린다.
        // 🔴 시연 토글은 **여기서만** 가른다(draw 인자). 그냥 건너뛰면 아래 fill_frac이
        //    직전 프레임 값 그대로 남아 "박스 0개인데 면적 0.12"라는 모순 행이 나간다.
        val overlayOn = overlayEnabled
        highlightOverlay.setDynamicGeometry(overlaySmoother, arm.drawsOverlayFill, overlayOn)
        // 면적 축도 H 안에서 확정한다 — 정점을 쓰면서 이미 센 값을 고정소수로 옮길 뿐이다.
        // 🔴 fill 대조군에서도 이 값은 **그대로 나간다** — 열의 정의가 기하 통계이고 그리기
        //    여부와 무관하다(HighlightOverlay.setDynamicGeometry의 같은 주석).
        frameOverlayFillFracScaled = scaleFillFrac(highlightOverlay.overlayFillFrac)
        frameStageHNs = SystemClock.elapsedRealtimeNanos() - hStart
        // 🔴 열 계약이 "**실제로 그린** 박스 수"다(FrameLogRecorder) — 토글이 꺼져 있으면 0이
        //    맞고, 위 fill_frac도 같은 분기에서 0으로 내려가 있다(둘은 짝이다).
        frameOverlayBoxes = if (overlayOn) drawn else 0

        val timing = instrument && gpuTimer.beginFrame()

        // 패스1: OES → FBO_A (처리 해상도). stage_b_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        if (timing) gpuTimer.endPass()

        // 패스2: drago 전역 통계. stage_d_analyze_ms.
        // ⚠ FBO_A를 어태치먼트에서 떼고 나서 텍스처로 읽는다(피드백 루프 방지).
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        chainStage.dragoAnalyze(fboTextures[0], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스3: drago 통계 → 톤커브 계수. stage_d_build_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.dragoBuild()
        if (timing) gpuTimer.endPass()

        // 패스4: FBO_A → FBO_B. drago 톤맵 적용. stage_d_apply_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.beforeDragoApply()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(dragoApply, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()

        // 패스5: clahe 타일 히스토그램. 입력은 **drago 출력(FBO_B)**이다. stage_d_analyze2_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        chainStage.claheAnalyze(fboTextures[1], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스6: clahe 클립 + 재분배 + CDF → 타일 LUT. stage_d_build2_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.claheBuild()
        if (timing) gpuTimer.endPass()

        // 패스7: FBO_B → FBO_A. clahe 적용(핑퐁으로 되돌아온다). stage_d_apply2_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.beforeClaheApply()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(claheApply, GLES20.GL_TEXTURE_2D, fboTextures[1])
        if (timing) gpuTimer.endPass()

        // 패스8: **FBO_A**에 ④ 오버레이. stage_i_ms.
        // 🔴 **fbos[0]이다** — 패스7이 여기 썼고 패스9가 fboTextures[0]을 읽는다. 4패스
        //    오버레이 arm을 그대로 복사해 fbos[1]에 그리면 박스가 화면에 안 뜨는데
        //    overlay_boxes·stage_i_ms는 정상값이 나온다(위 KDoc).
        // 🔴 **glClear를 부르지 않는다** — 지우면 이 프레임의 ② 체인 결과가 통째로 사라진다.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        // 정점은 위 H 구간에서 이미 다 썼다 — 여기서는 드로우콜만 낸다.
        highlightOverlay.drawPrepared()
        if (timing) gpuTimer.endPass()

        // 패스9: FBO_A → 화면 (surface 크기). gpu_present_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        presentTexture(present, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()
    }

    /**
     * 통합 arm이 폴백했을 때 **어느 준비가 실패했는지**를 logcat에 **한 번만** 남긴다.
     *
     * 🔴 야간 실외 스모크에서 이 경로가 처음 돈다 — 실패하면 현장에서 원인을 갈라야 한다.
     * 프레임마다 찍으면 로그가 쓸려 나가므로 첫 프레임에만 낸다(문자열 할당도 그때 한 번뿐이다).
     * ⚠ **성공 서술을 붙이지 않는다**([applyProgramFailureStatus]의 교훈) — 실패한 항목만 적는다.
     */
    private fun logChainHighlightFallbackOnce(
        present: QuadProgram?,
        dragoApply: QuadProgram?,
        claheApply: QuadProgram?,
    ) {
        if (chainHighlightFallbackLogged) return
        chainHighlightFallbackLogged = true
        val missing = buildList {
            if (present == null) add("present(blit) 프로그램")
            // 🔴 **복제본**의 이름을 적는다 — 이 arm이 실제로 컴파일에 실패한 것은 그쪽이다.
            //    공유본 이름을 적으면 현장에서 원인을 가르려는 사람이 다른 arm의 프로그램을
            //    들여다보게 된다(같은 부류의 실수를 아래 arm.id 주석이 이미 한 번 기록했다).
            if (dragoApply == null) add("체인 drago 적용 프래그먼트(시연 토글 복제본)")
            if (claheApply == null) add("체인 clahe 적용 프래그먼트(시연 토글 복제본)")
            if (!chainStage.ready) add("chainStage(컴퓨트·SSBO) — ${chainStage.status}")
            if (!highlightOverlay.ready) add("highlightOverlay — ${highlightOverlay.status}")
            if (fbos[0] == 0) add("오프스크린 FBO — $offscreenStatus")
        }
        Log.e(
            TAG,
            // 🔴 **arm.id다** — 이 경로는 `_1q` 짝도 함께 탄다(usesChainedHighlight).
            //    상수를 박아 두면 폴백했을 때 logcat에 **돌지 않은 arm의 이름**이 나가고,
            //    현장에서 원인을 가르려는 사람이 첫 줄부터 틀린 조건을 본다.
            "🔴 ${arm.id}: 준비 실패로 전 프레임이 " +
                "패스스루로 폴백한다(② 체인도 ④ 오버레이도 화면에 없다). 실패 항목 = " +
                missing.joinToString(" / ") +
                ". session.json의 render.processing.frames_fell_back_to_passthrough가 그 수다"
        )
    }

    /**
     * ② **융합** arm(`drago_clahe_fused`)의 7패스. [drawChainedComputeStage2]에서 **중간
     * materialize와 적용 패스 하나를 뺀** 것이다:
     * ```
     * 패스1  OES   → FBO_A                        stage_b_ms
     * 패스2  drago analyze (FBO_A)                stage_d_analyze_ms
     * 패스3  drago build                          stage_d_build_ms
     * 패스4  fused analyze (FBO_A, 톤맵 인라인)    stage_d_analyze2_ms
     * 패스5  clahe build                          stage_d_build2_ms
     * 패스6  fused apply   FBO_A → FBO_B          stage_d_apply_ms
     * 패스7  present       FBO_B → 화면            gpu_present_ms
     * ```
     * ⚠ **패스4의 입력이 FBO_A(원본)다.** 체인은 여기서 drago가 적용된 FBO_B를 읽었다 —
     * 융합은 그 중간 이미지를 만들지 않으므로 톤맵을 **다시 계산**한다
     * ([RenderArm.FUSED_DEVIATION]의 (c)).
     *
     * ⚠ **`stage_d_apply2_ms`를 쓰지 않는다.** 적용이 하나로 접혔고, 재지 않은 열은 싣지
     * 않는다. 그래서 열 순서가 `…analyze2, build2, apply, present`가 된다 —
     * [RenderArm.gpuColumns]가 **패스 순서 그대로**라는 규약을 따른 결과다.
     *
     * ### 왜 또 함수를 따로 만드는가
     * [drawComputeStage2]도 [drawChainedComputeStage2]도 **이미 잰 arm의 재현 경로**다.
     * 어느 쪽에 분기를 넣어도 그 arm들의 GL 호출 시퀀스가 바뀐다. 중복은 알면서 감수한
     * 비용이고, 그 대가로 앞선 측정이 고정된다.
     */
    private fun drawFusedComputeStage2(
        oes: QuadProgram,
        fusedApply: QuadProgram,
        present: QuadProgram,
        instrument: Boolean,
    ) {
        val timing = instrument && gpuTimer.beginFrame()

        // 패스1: OES → FBO_A (처리 해상도). stage_b_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        if (timing) gpuTimer.endPass()

        // 패스2: drago 전역 통계. stage_d_analyze_ms.
        // ⚠ FBO_A를 어태치먼트에서 떼고 나서 텍스처로 읽는다(피드백 루프 방지).
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fusedStage.dragoAnalyze(fboTextures[0], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스3: drago 통계 → 톤커브 계수. stage_d_build_ms.
        if (timing) gpuTimer.beginPass()
        fusedStage.dragoBuild()
        if (timing) gpuTimer.endPass()

        // 패스4: 톤맵을 인라인한 타일 히스토그램. 입력은 **FBO_A(원본)**. stage_d_analyze2_ms.
        if (timing) gpuTimer.beginPass()
        fusedStage.fusedAnalyze(fboTextures[0], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스5: 클립 + 재분배 + CDF → 타일 LUT. stage_d_build2_ms.
        if (timing) gpuTimer.beginPass()
        fusedStage.claheBuild()
        if (timing) gpuTimer.endPass()

        // 패스6: FBO_A → FBO_B. 톤맵 + LUT 보간 + 감마를 한 패스에서. stage_d_apply_ms.
        if (timing) gpuTimer.beginPass()
        fusedStage.beforeFusedApply()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(fusedApply, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()

        // 패스7: FBO_B → 화면 (surface 크기). gpu_present_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        presentTexture(present, GLES20.GL_TEXTURE_2D, fboTextures[1])
        if (timing) gpuTimer.endPass()
    }

    /**
     * ② **체인 + bf** arm(`drago_clahe_chain_bf`)의 9패스. [drawChainedComputeStage2]의 8패스
     * **뒤에 bilateral 한 패스를 끼운** 것이다:
     * ```
     * 패스1  OES   → FBO_A                     stage_b_ms
     * 패스2  drago analyze (FBO_A)             stage_d_analyze_ms
     * 패스3  drago build                       stage_d_build_ms
     * 패스4  drago apply   FBO_A → FBO_B       stage_d_apply_ms
     * 패스5  clahe analyze (FBO_B)             stage_d_analyze2_ms
     * 패스6  clahe build                       stage_d_build2_ms
     * 패스7  clahe apply   FBO_B → FBO_A       stage_d_apply2_ms
     * 패스8  bilateral     FBO_A → FBO_B       stage_d_denoise_ms
     * 패스9  present       FBO_B → 화면        gpu_present_ms
     * ```
     * FBO는 여전히 **2장 핑퐁으로 닫힌다** — bf가 A를 읽어 B에 쓰고 present가 B를 읽는다.
     *
     * ### 왜 또 함수를 따로 만드는가
     * [drawChainedComputeStage2]는 **이미 잰 arm의 재현 경로**다. 거기에 `if (bf)`를 넣으면
     * 체인 arm의 GL 호출 시퀀스가 바뀌고 `docs/baselines/`의 숫자와 비교할 근거가 사라진다
     * ([drawFusedComputeStage2]의 같은 주석 참고). **중복은 알면서 감수한 비용이다.**
     *
     * 반대로 **[chainStage]와 적용 프로그램은 그대로 공유한다** — 앞 7패스가 글자 그대로 같은
     * GL 호출이어야 "이 arm − 체인 = bf 한 패스"가 성립한다.
     */
    private fun drawChainedBilateral(
        oes: QuadProgram,
        dragoApply: QuadProgram,
        claheApply: QuadProgram,
        denoise: QuadProgram,
        present: QuadProgram,
        instrument: Boolean,
    ) {
        val timing = instrument && gpuTimer.beginFrame()

        // 패스1: OES → FBO_A (처리 해상도). stage_b_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        if (timing) gpuTimer.endPass()

        // 패스2: drago 전역 통계. stage_d_analyze_ms.
        // ⚠ FBO_A를 어태치먼트에서 떼고 나서 텍스처로 읽는다(피드백 루프 방지).
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        chainStage.dragoAnalyze(fboTextures[0], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스3: drago 통계 → 톤커브 계수. stage_d_build_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.dragoBuild()
        if (timing) gpuTimer.endPass()

        // 패스4: FBO_A → FBO_B. drago 톤맵 적용. stage_d_apply_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.beforeDragoApply()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(dragoApply, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()

        // 패스5: clahe 타일 히스토그램. 입력은 **drago 출력(FBO_B)**이다. stage_d_analyze2_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        chainStage.claheAnalyze(fboTextures[1], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스6: clahe 클립 + 재분배 + CDF → 타일 LUT. stage_d_build2_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.claheBuild()
        if (timing) gpuTimer.endPass()

        // 패스7: FBO_B → FBO_A. clahe 적용(핑퐁으로 되돌아온다). stage_d_apply2_ms.
        if (timing) gpuTimer.beginPass()
        chainStage.beforeClaheApply()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(claheApply, GLES20.GL_TEXTURE_2D, fboTextures[1])
        if (timing) gpuTimer.endPass()

        // 패스8: FBO_A → FBO_B. bilateral(sRGB 그대로 필터한다). stage_d_denoise_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(denoise, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()

        // 패스9: FBO_B → 화면 (surface 크기). gpu_present_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        presentTexture(present, GLES20.GL_TEXTURE_2D, fboTextures[1])
        if (timing) gpuTimer.endPass()
    }

    /**
     * ② **융합 + bf** arm(`drago_clahe_fused_bf`)의 8패스. [drawFusedComputeStage2]의 7패스
     * 뒤에 bilateral 한 패스를 끼운 것이다:
     * ```
     * 패스1  OES   → FBO_A                        stage_b_ms
     * 패스2  drago analyze (FBO_A)                stage_d_analyze_ms
     * 패스3  drago build                          stage_d_build_ms
     * 패스4  fused analyze (FBO_A, 톤맵 인라인)    stage_d_analyze2_ms
     * 패스5  clahe build                          stage_d_build2_ms
     * 패스6  fused apply   FBO_A → FBO_B          stage_d_apply_ms
     * 패스7  bilateral     FBO_B → FBO_A          stage_d_denoise_ms
     * 패스8  present       FBO_A → 화면            gpu_present_ms
     * ```
     * ⚠ 융합 쪽은 핑퐁 방향이 체인+bf와 **반대**다(bf가 B를 읽어 A에 쓴다) — 앞 arm의 마지막
     * 처리 패스가 어느 FBO에 썼는지에 따라 정해지며, 그래도 **2장으로 닫힌다.**
     */
    private fun drawFusedBilateral(
        oes: QuadProgram,
        fusedApply: QuadProgram,
        denoise: QuadProgram,
        present: QuadProgram,
        instrument: Boolean,
    ) {
        val timing = instrument && gpuTimer.beginFrame()

        // 패스1: OES → FBO_A (처리 해상도). stage_b_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        if (timing) gpuTimer.endPass()

        // 패스2: drago 전역 통계. stage_d_analyze_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fusedStage.dragoAnalyze(fboTextures[0], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스3: drago 통계 → 톤커브 계수. stage_d_build_ms.
        if (timing) gpuTimer.beginPass()
        fusedStage.dragoBuild()
        if (timing) gpuTimer.endPass()

        // 패스4: 톤맵을 인라인한 타일 히스토그램. 입력은 **FBO_A(원본)**. stage_d_analyze2_ms.
        if (timing) gpuTimer.beginPass()
        fusedStage.fusedAnalyze(fboTextures[0], fboWidth, fboHeight)
        if (timing) gpuTimer.endPass()

        // 패스5: 클립 + 재분배 + CDF → 타일 LUT. stage_d_build2_ms.
        if (timing) gpuTimer.beginPass()
        fusedStage.claheBuild()
        if (timing) gpuTimer.endPass()

        // 패스6: FBO_A → FBO_B. 톤맵 + LUT 보간 + 감마를 한 패스에서. stage_d_apply_ms.
        if (timing) gpuTimer.beginPass()
        fusedStage.beforeFusedApply()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(fusedApply, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()

        // 패스7: FBO_B → FBO_A. bilateral. stage_d_denoise_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(denoise, GLES20.GL_TEXTURE_2D, fboTextures[1])
        if (timing) gpuTimer.endPass()

        // 패스8: FBO_A → 화면 (surface 크기). gpu_present_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        presentTexture(present, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()
    }

    /**
     * ④ **오버레이** arm의 4패스([RenderArm.usesHighlightOverlay] — 정적 더미 셋과
     * ③ 결과 둘). [drawThreePass]의 세 패스 사이에 오버레이 패스가 하나 끼워진 것이다:
     * ```
     * 패스1  OES   → FBO_A              stage_b_ms
     * 패스2  FBO_A → FBO_B (단순 복사)   stage_d_ms
     * 패스3  FBO_B에 스트로크 quad 덧그림 stage_i_ms
     * 패스4  FBO_B → 화면                gpu_present_ms
     * ```
     * ⚠ **패스3에 `glClear`가 없다.** 오버레이는 ② 출력 **위에** 얹는 것이라 지우면 그림이
     * 사라진다. 그래서 이 패스는 타일 GPU에서 컬러 어태치먼트를 다시 load하고, `stage_i_ms`에는
     * 그 비용이 섞인다 — 오버레이 패스의 실제 비용이며 빼낼 수단이 없다. 같은 문장이
     * `session.json`의 `overlay` 블록으로 나간다.
     *
     * ⚠ 패스2와 패스3의 **타깃이 같다**(FBO_B). 드라이버가 두 렌더패스를 병합하면 두 열의
     * 경계가 흐려진다 — [drawThreePass]가 적은 귀속 주의사항이 여기서 특히 크다. 그래서 패스
     * 사이에 바인드·뷰포트를 다시 명시한다(쪼갤 기회를 주는 것까지가 우리가 할 수 있는 일이다).
     *
     * 왜 또 함수를 따로 만드는가: [drawThreePass]는 `blit_2pass`·`gamma_only`의 재현 경로다.
     *
     * 🔴 **③ 결과 arm에서는 패스1보다 앞에 H칸(좌표 평활·hold)이 하나 더 있다** — GPU 패스가
     * 아니라 **GL 스레드의 CPU 구간**이고 `stage_h_ms`로 나간다. GPU query 안에 두지 않는
     * 이유는 [RenderArm.OVERLAY_STAGE_H_SCOPE]에 있다.
     * ⚠ 정적 더미 arm 셋은 그 블록을 타지 않는다 — 그 arm의 박스는 프레임마다 같아 평활할
     * 것이 없기 때문이다. 🔴 예전에 여기 적혀 있던 사유("그 arm의 GL 호출 열은 이전과 같아야
     * 한다")는 fill이 들어오면서 **거짓이 됐다**: 오버레이 패스에 블렌딩 상태와 알파 속성이
     * 붙어 정적 더미 arm의 GL 호출 열도 바뀌었다([RenderArm.HIGHLIGHT_FILL_DEVIATION]).
     * ⚠ **fill quad를 실제로 넣는지는 arm이 정한다**([RenderArm.drawsOverlayFill]) — 정적 더미
     * arm 셋은 전부 true이고, false인 것은 fill 대조군 하나뿐이다
     * ([RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL]). 블렌딩 상태와 알파 속성은 **어느
     * arm에서도 붙은 채**라 그 둘의 비용은 대조군 차분으로 분리되지 않는다
     * ([RenderArm.HIGHLIGHT_NOFILL_CONTROL_NOTE]).
     */
    private fun drawHighlightOverlay(
        oes: QuadProgram,
        stage2Copy: QuadProgram,
        present: QuadProgram,
        instrument: Boolean,
    ) {
        // ── ④ H칸(좌표 평활·hold) — 🔴 **GPU 패스를 열기 전에 끝낸다** ────────────
        // stage_h_ms는 **CPU 벽시계**다. GPU timer query 안에 넣으면 (a) 그 열은 GPU 시간을
        // 재므로 이 CPU 일이 어디에도 계상되지 않고, (b) 두 시계가 섞인 값이 나가면 하네스
        // 자기검사가 잡는다. 구간의 정확한 범위는 RenderArm.OVERLAY_STAGE_H_SCOPE에 있다.
        // ⚠ **정적 더미 arm은 이 블록을 타지 않는다** — 그 arm의 박스는 프레임마다 같아
        //   평활할 것이 없고, H 열도 싣지 않는다.
        //   🔴 예전 사유("그 arm들의 렌더는 이전과 바이트 단위로 같아야 한다 — 승격 비교")는
        //   더 이상 성립하지 않는다: fill이 들어오면서 정적 더미 arm도 그리는 픽셀이 바뀌었다
        //   (RenderArm.HIGHLIGHT_FILL_DEVIATION). 그 arm의 이전 stage_i_ms 승격 숫자는
        //   이 빌드의 값이 아니다.
        //   ⚠ fill 대조군(RenderArm.DETECT_CPU_CHAIN_HIGHLIGHT_NOFILL)은 ③ 결과 arm이라
        //   이 블록을 **탄다** — 정적 더미 arm 쪽이 아니다.
        val dynamicBoxes = arm.usesDynamicHighlightBoxes
        if (dynamicBoxes) {
            val hStart = SystemClock.elapsedRealtimeNanos()
            val drawn = overlaySmoother.update(
                frameOverlaySnapshot, fboWidth, fboHeight, requestedOverlayRotationDegrees
            )
            // 정점 재기록도 H 안이다 — 이 CPU 비용을 stage_i_ms(GPU 시계) 쪽에 두면 사라진다.
            // 🔴 fill 여부는 arm이 정한다(위 통합 arm 경로와 같은 이유·같은 출처).
            highlightOverlay.setDynamicGeometry(overlaySmoother, arm.drawsOverlayFill)
            // 면적 축도 H 안에서 확정한다(위 통합 arm과 같은 자리·같은 값).
            frameOverlayFillFracScaled = scaleFillFrac(highlightOverlay.overlayFillFrac)
            frameStageHNs = SystemClock.elapsedRealtimeNanos() - hStart
            frameOverlayBoxes = drawn
        }
        val timing = instrument && gpuTimer.beginFrame()

        // 패스1: OES → FBO_A (처리 해상도). stage_b_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        if (timing) gpuTimer.endPass()

        // 패스2: FBO_A → FBO_B. ② 자리이며 단순 복사다. stage_d_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(stage2Copy, GLES20.GL_TEXTURE_2D, fboTextures[0])
        if (timing) gpuTimer.endPass()

        // 패스3: FBO_B에 오버레이. stage_i_ms. 🔴 **clear하지 않는다**(위 주석 참고).
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[1])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        if (dynamicBoxes) {
            // 정점은 위 H 구간에서 이미 다 썼다 — 여기서는 드로우콜만 낸다.
            highlightOverlay.drawPrepared()
        } else {
            highlightOverlay.draw(arm.highlightBoxCount, arm.drawsOverlayFill)
        }
        if (timing) gpuTimer.endPass()

        // 패스4: FBO_B → 화면 (surface 크기). gpu_present_ms.
        if (timing) gpuTimer.beginPass()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        presentTexture(present, GLES20.GL_TEXTURE_2D, fboTextures[1])
        if (timing) gpuTimer.endPass()
    }

    /**
     * `overlay_fill_frac`(비율) → 로그 청크에 담을 **고정소수 정수**.
     * 청크가 `LongArray`라 float를 그대로 담을 수 없다([FrameLogRecorder.FILL_FRAC_SCALE]).
     *
     * ⚠ 곱셈을 Double로 한다 — 겹침 미보정이라 비율이 1을 크게 넘을 수 있고, float 가수로는
     * 그 자릿수에서 소수 6자리가 남지 않는다. 할당은 없다(원시형 산술뿐이다).
     */
    private fun scaleFillFrac(frac: Float): Long {
        // 🔴 0은 **정상값**이다("채운 면적이 없었다"). 없음의 표식은 호출자가 따로 둔다.
        if (frac <= 0f) return 0L
        return (frac.toDouble() * FrameLogRecorder.FILL_FRAC_SCALE + 0.5).toLong()
    }

    /**
     * 프레임당 객체를 만들지 않는다 — 인자는 전부 원시형이고 프로그램은 미리 만들어 둔다.
     *
     * 🔴 **표시 회전이 걸리는 유일한 지점이다**(cardboard 눈은 [drawCardboardEye]로 이어진다).
     * 여기까지 오는 텍스처는 회전 전 좌표계이고 ④ 오버레이도 그 좌표계에 그려져 있으므로,
     * 이 한 번의 회전이 **영상과 박스를 함께** 돌린다.
     *
     * ⚠ **종횡비 보정(S5)은 아직 없다.** 아래 normal 경로는 호출자가 세운 뷰포트를 그대로
     * 쓰고, 90/270°에서는 소스와 뷰포트의 종횡비가 뒤집혀 늘어나 보인다 — 정책이 미정이라
     * (STATUS 이슈 68) 이번에는 손대지 않는다. 들어온다면 자리는 여기다.
     */
    private fun presentTexture(program: QuadProgram, textureTarget: Int, textureId: Int) {
        if (displayMode == DisplayMode.NORMAL) {
            drawQuad(program, textureTarget, textureId, applyPreviewTransform = true)
            return
        }

        val leftWidth = surfaceWidth / 2
        val rightWidth = surfaceWidth - leftWidth
        val eyeProgram = if (textureTarget == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
            cardboardOesProgram
        } else {
            cardboard2dProgram
        } ?: program
        drawCardboardEye(0, leftWidth, -1, eyeProgram, textureTarget, textureId)
        drawCardboardEye(leftWidth, rightWidth, 1, eyeProgram, textureTarget, textureId)
    }

    private fun drawCardboardEye(
        eyeLeft: Int,
        eyeWidth: Int,
        horizontalDirection: Int,
        program: QuadProgram,
        textureTarget: Int,
        textureId: Int,
    ) {
        val sourceAspect = if (processWidth > 0 && processHeight > 0) {
            processWidth.toFloat() / processHeight.toFloat()
        } else {
            16f / 9f
        }
        val maxWidth = eyeWidth * cardboardImageScale
        val maxHeight = surfaceHeight * cardboardImageScale
        var contentWidth = maxWidth
        var contentHeight = contentWidth / sourceAspect
        if (contentHeight > maxHeight) {
            contentHeight = maxHeight
            contentWidth = contentHeight * sourceAspect
        }
        // ⚠ [sourceAspect]는 **회전 전** 처리 해상도의 종횡비다. present가 회전을 걸게 된 뒤로
        //    90/270°에서는 이 비가 뒤집혀야 맞지만, 종횡비 정책이 미정이라(STATUS 이슈 68)
        //    이번 변경에서는 건드리지 않는다. 실제로 cardboard는 MainActivity가 LANDSCAPE를
        //    강제해 rotationDegrees가 0/180이 되므로 회전이 사실상 없어질 것으로 보는데,
        //    **그것은 실기기에서 확인할 항목**이다 — 코드에 분기를 만들지 않는다.

        val offsetPx = eyeWidth * cardboardEyeOffset * horizontalDirection
        val viewportX = (
            eyeLeft + (eyeWidth - contentWidth) * 0.5f + offsetPx
        ).roundToInt()
        val viewportY = ((surfaceHeight - contentHeight) * 0.5f).roundToInt()
        GLES20.glViewport(
            viewportX,
            viewportY,
            contentWidth.roundToInt().coerceAtLeast(1),
            contentHeight.roundToInt().coerceAtLeast(1),
        )
        // cardboard도 normal과 **같은 게이트**를 탄다 — 표시 경로는 하나다.
        drawQuad(program, textureTarget, textureId, applyPreviewTransform = true)
    }

    private fun updatePositionMatrixIfNeeded() {
        val rotation = requestedPreviewRotationDegrees
        val mirror = requestedPreviewMirror
        if (rotation == appliedPreviewRotationDegrees && mirror == appliedPreviewMirror) return

        Matrix.setIdentityM(positionMatrix, 0)
        if (mirror) Matrix.scaleM(positionMatrix, 0, -1f, 1f, 1f)
        // 부호는 [PREVIEW_ROTATION_SIGN] 하나에서만 온다 — 유도와 뒤집는 법은 그 상수에 있다.
        if (rotation != 0) Matrix.rotateM(
            positionMatrix, 0, PREVIEW_ROTATION_SIGN * rotation.toFloat(), 0f, 0f, 1f
        )
        appliedPreviewRotationDegrees = rotation
        appliedPreviewMirror = mirror
        // 🔴 **이 행렬이 걸리는 자리는 present 정점 하나다**(패스1은 항등 행렬을 받는다).
        //    ⚠ **④ 박스는 이 행렬을 타지 않는다** — 박스는 OverlayCoordMap이 센서 좌표를
        //      직접 돌려서 FBO NDC로 넣는다. 두 회전은 서로 다른 자리이고, `texMatrix`가
        //      영상을 이미 세워 주는 경로(hasCameraTransform=true)에서는 **여기가 0도**라
        //      실제로 도는 것은 박스 쪽뿐이다.
        //    ⚠ 그래도 언제 걸렸는지는 남긴다 — 런 도중에 바뀌면 그 런의 화면 축이 둘이다.
        previewRotationApplied = rotation
        previewMirrorApplied = mirror
        previewRotationApplyCount += 1
        previewRotationAppliedAtRecordedFrame = if (recorder.isRecording) {
            recorder.recordedFrames
        } else {
            PREVIEW_ROTATION_APPLIED_WHILE_IDLE
        }
        Log.i(
            TAG,
            "표시 회전 적용 #$previewRotationApplyCount: ${rotation}도 mirror=$mirror " +
                "(기록 프레임=$previewRotationAppliedAtRecordedFrame — " +
                "${PREVIEW_ROTATION_APPLIED_WHILE_IDLE}면 측정 중이 아니었다)"
        )
    }

    /**
     * @param applyPreviewTransform 표시 회전을 이 드로우에 걸 것인가. 🔴 **기본값 false가
     *   핵심이다** — 패스1(OES → FBO)을 포함한 오프스크린 드로우 전부가 인자를 안 적으므로
     *   자동으로 회전이 꺼진다. 드로우 사이트를 하나씩 켜고 끄는 방식이었다면 여덟 자리 중
     *   하나만 빠져도 그 arm의 화면이 조용히 180°(또는 90°) 어긋난다.
     *   **true로 부르는 곳은 [presentTexture]와 [drawCardboardEye] 둘뿐이다.**
     */
    private fun drawQuad(
        program: QuadProgram,
        textureTarget: Int,
        textureId: Int,
        applyPreviewTransform: Boolean = false,
    ) {
        GLES20.glUseProgram(program.handle)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glUniform1i(program.uTexture, 0)
        if (program.uPositionMatrix >= 0) {
            // 🔴 **false에서 업로드를 건너뛰면 안 된다.** 유니폼 값은 프로그램에 남으므로
            //    `oesProgram`처럼 패스1(회전 없음)과 passthrough present(회전 있음)에서
            //    함께 쓰이는 프로그램은 직전 드로우의 회전 행렬을 그대로 물려받는다.
            //    항등 행렬을 **명시적으로** 올린다(할당은 없다 — 상수 배열이다).
            if (applyPreviewTransform) {
                updatePositionMatrixIfNeeded()
                GLES20.glUniformMatrix4fv(program.uPositionMatrix, 1, false, positionMatrix, 0)
            } else {
                GLES20.glUniformMatrix4fv(
                    program.uPositionMatrix, 1, false, IDENTITY_POSITION_MATRIX, 0
                )
            }
        }
        if (program.uTexMatrix >= 0) {
            GLES20.glUniformMatrix4fv(program.uTexMatrix, 1, false, texMatrix, 0)
        }
        if (program.uLensDistortion >= 0) {
            GLES20.glUniform1f(program.uLensDistortion, CARDBOARD_LENS_DISTORTION)
        }
        if (program.uGamma >= 0) {
            // 상수로 박지 않고 uniform으로 넣는다(INTERFACES.md §B-5 요청). 실제로 쓴 값은
            // session.json의 stage2_params에 provenance와 함께 남는다.
            GLES20.glUniform1f(program.uGamma, RenderArm.GAMMA_MEASUREMENT_VALUE)
        }
        // Drago 파라미터도 같은 이유로 전부 uniform이다. 없는 프로그램에서는 -1이라 건너뛴다.
        if (program.uSrcGamma >= 0) {
            GLES20.glUniform1f(program.uSrcGamma, RenderArm.DRAGO_SRC_GAMMA)
        }
        if (program.uOutGamma >= 0) {
            GLES20.glUniform1f(program.uOutGamma, RenderArm.DRAGO_GAMMA)
        }
        if (program.uSaturation >= 0) {
            GLES20.glUniform1f(program.uSaturation, RenderArm.DRAGO_SATURATION)
        }
        // CLAHE도 같은 이유로 uniform이다. 타일 격자는 셰이더 상수가 아니라 여기서 온다.
        if (program.uTiles >= 0) {
            val tiles = RenderArm.CLAHE_TILE_GRID.toFloat()
            GLES20.glUniform2f(program.uTiles, tiles, tiles)
        }
        if (program.uClaheGamma >= 0) {
            GLES20.glUniform1f(program.uClaheGamma, RenderArm.CLAHE_GAMMA_VALUE)
        }
        // bilateral도 같은 이유로 전부 uniform이다. 반경은 d의 유도값이고(radius = d/2)
        // σ는 상류 원문 값 그대로다.
        if (program.uBfRadius >= 0) {
            GLES20.glUniform1i(program.uBfRadius, RenderArm.BF_RADIUS)
        }
        if (program.uBfSigmaColor >= 0) {
            GLES20.glUniform1f(program.uBfSigmaColor, RenderArm.BF_SIGMA_COLOR)
        }
        if (program.uBfSigmaSpace >= 0) {
            GLES20.glUniform1f(program.uBfSigmaSpace, RenderArm.BF_SIGMA_SPACE)
        }
        if (program.uTexel >= 0) {
            // 해상도의 유일한 출처는 협상된 처리 해상도다(BilateralStage.onProcessSizeChanged
            // 에서 1/해상도로 유도해 둔 값). 셰이더에 해상도를 하드코딩하지 않는다.
            GLES20.glUniform2f(program.uTexel, bilateralStage.texelX, bilateralStage.texelY)
        }
        // 시연용 ② 토글. 🔴 **0/1은 알고리즘 제안값이 아니다** — 스위치의 두 끝이고,
        // 그 사실은 RenderArm.DEMO_ENHANCE_PROVENANCE가 session.json에 그대로 낸다
        // (INTERFACES.md §B-5의 ☐를 채운 값으로 오독되지 않게 한다).
        // 이 uniform이 있는 프로그램은 통합 arm의 복제 프래그먼트 둘뿐이다.
        if (program.uEnhance >= 0) {
            GLES20.glUniform1f(
                program.uEnhance,
                if (stage2Enabled) RenderArm.DEMO_ENHANCE_ON else RenderArm.DEMO_ENHANCE_OFF
            )
        }

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            program.aPosition, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(program.aPosition)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(
            program.aTexCoord, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(program.aTexCoord)

        // ⚠ glDrawArrays는 명령을 제출하고 **즉시 반환한다.** 따라서 t_render_end - t_render_start
        //   는 CPU 제출 비용이며 GPU 실행 시간이 아니다.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * 처리 해상도의 FBO 2장을 준비한다. 이미 같은 크기로 있으면 아무것도 하지 않는다.
     * **협상 결과가 없으면 값을 지어내지 않고 false를 돌려준다.**
     */
    private fun ensureOffscreen(): Boolean {
        val w = processWidth
        val h = processHeight
        if (w <= 0 || h <= 0) {
            offscreenStatus =
                "협상된 처리 해상도가 없다 — 카메라가 Surface를 요청하기 전이다. " +
                    "FBO 크기를 지어내지 않고 1패스로 그린다"
            return false
        }
        if (fboWidth == w && fboHeight == h && fbos[0] != 0 && fbos[1] != 0) return true
        // 처리 해상도의 유일한 출처가 여기이므로, 해상도에 의존하는 ② 상수도 여기서 잡는다
        // (셰이더 쪽에 해상도를 하드코딩하지 않기 위해서다).
        dragoStage.onProcessSizeChanged(w, h)
        claheStage.onProcessSizeChanged(w, h)
        agcwdStage.onProcessSizeChanged(w, h)
        chainStage.onProcessSizeChanged(w, h)
        fusedStage.onProcessSizeChanged(w, h)
        // bf의 uTexel(1/해상도)과 ④ 오버레이의 두께·박스 좌표도 여기서만 정해진다 —
        // 둘 다 픽셀 값을 코드에 박지 않는다.
        bilateralStage.onProcessSizeChanged(w, h)
        highlightOverlay.onProcessSizeChanged(w, h)

        releaseOffscreen()
        GLES20.glGenTextures(FBO_COUNT, fboTextures, 0)
        GLES20.glGenFramebuffers(FBO_COUNT, fbos, 0)
        for (i in 0 until FBO_COUNT) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextures[i])
            // RGBA8/UNSIGNED_BYTE만 쓴다. float/half 포맷은 기기 의존성을 늘린다.
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTextures[i], 0
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                releaseOffscreen()
                offscreenStatus =
                    "FBO[$i] 미완성 (glCheckFramebufferStatus=$status, ${w}x$h) — 1패스로 그린다"
                Log.e(TAG, offscreenStatus)
                return false
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        fboWidth = w
        fboHeight = h
        offscreenStatus = "FBO 2장 준비 완료 ${w}x$h (RGBA8, 처리 해상도 = 협상된 카메라 해상도)"
        Log.i(TAG, offscreenStatus)
        return true
    }

    private fun releaseOffscreen() {
        if (fbos[0] != 0 || fbos[1] != 0) {
            GLES20.glDeleteFramebuffers(FBO_COUNT, fbos, 0)
        }
        if (fboTextures[0] != 0 || fboTextures[1] != 0) {
            GLES20.glDeleteTextures(FBO_COUNT, fboTextures, 0)
        }
        for (i in 0 until FBO_COUNT) {
            fbos[i] = 0
            fboTextures[i] = 0
        }
        fboWidth = 0
        fboHeight = 0
    }

    /**
     * 컨텍스트 재생성 시 호출된다. FBO·텍스처·프로그램·SurfaceTexture를 모두 버린다 —
     * 하나라도 빠뜨리면 컨텍스트가 재생성될 때마다 GPU 메모리가 샌다.
     */
    private fun releaseGlResources() {
        // release()가 콜백까지 끊는다. setOnFrameAvailableListener(null)은 부르지 않는다 —
        // null 허용 여부를 확인하지 못한 시그니처를 쓸 이유가 없다.
        surfaceTexture?.release()
        surfaceTexture = null
        releaseOffscreen()
        // query 객체도 컨텍스트에 매달려 있다. 빠뜨리면 컨텍스트가 재생성될 때마다 샌다.
        gpuTimer.releaseGl()
        // ② 컴퓨트 arm 3종의 프로그램과 SSBO도 같은 이유로 여기서 반납한다.
        dragoStage.releaseGl()
        claheStage.releaseGl()
        agcwdStage.releaseGl()
        chainStage.releaseGl()
        fusedStage.releaseGl()
        // bilateralStage는 GL 객체가 없지만(프로그램은 아래에서 지운다) 상태를 되돌린다.
        bilateralStage.releaseGl()
        // ④ 오버레이는 자기 프로그램을 스스로 지운다.
        highlightOverlay.releaseGl()
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        deleteProgram(oesProgram)
        deleteProgram(cardboardOesProgram)
        deleteProgram(cardboard2dProgram)
        deleteProgram(blitProgram)
        deleteProgram(presentProgram)
        deleteProgram(gammaProgram)
        deleteProgram(dragoApplyProgram)
        deleteProgram(claheApplyProgram)
        deleteProgram(agcwdApplyProgram)
        deleteProgram(chainDragoApplyProgram)
        deleteProgram(chainClaheApplyProgram)
        deleteProgram(demoChainDragoApplyProgram)
        deleteProgram(demoChainClaheApplyProgram)
        deleteProgram(fusedApplyProgram)
        deleteProgram(bilateralProgram)
        oesProgram = null
        cardboardOesProgram = null
        cardboard2dProgram = null
        blitProgram = null
        presentProgram = null
        gammaProgram = null
        dragoApplyProgram = null
        claheApplyProgram = null
        agcwdApplyProgram = null
        chainDragoApplyProgram = null
        chainClaheApplyProgram = null
        demoChainDragoApplyProgram = null
        demoChainClaheApplyProgram = null
        fusedApplyProgram = null
        bilateralProgram = null
        // 이전 컨텍스트의 실패 원문은 이 컨텍스트의 근거가 아니다. 남겨 두면 재생성 뒤에
        // 만들지 **않은** 프로그램의 옛 원문이 `session.json`에 실려 나간다.
        programFailureLogs.clear()
    }

    private fun deleteProgram(program: QuadProgram?) {
        if (program != null && program.handle != 0) {
            GLES20.glDeleteProgram(program.handle)
        }
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    /**
     * @param label [programFailureLogs]에 컴파일러 원문을 남길 키. 실패했을 때 어느 셰이더가
     *   거부됐는지는 라벨 없이는 `session.json`에서 되물을 수 없다.
     */
    private fun buildProgram(
        vertexSource: String,
        fragmentSource: String,
        label: String,
    ): QuadProgram? {
        programFailureLogs.remove(label)
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource, label)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource, label)
        if (vertexShader == 0 || fragmentShader == 0) return null
        val handle = GLES20.glCreateProgram()
        GLES20.glAttachShader(handle, vertexShader)
        GLES20.glAttachShader(handle, fragmentShader)
        GLES20.glLinkProgram(handle)
        val status = IntArray(1)
        GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (status[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(handle)
            Log.e(TAG, "프로그램 링크 실패($label): $info")
            noteProgramFailure(label, "링크 실패: $info")
            GLES20.glDeleteProgram(handle)
            return null
        }
        return QuadProgram(
            handle = handle,
            aPosition = GLES20.glGetAttribLocation(handle, "aPosition"),
            aTexCoord = GLES20.glGetAttribLocation(handle, "aTexCoord"),
            uTexture = GLES20.glGetUniformLocation(handle, "uTexture"),
            uPositionMatrix = GLES20.glGetUniformLocation(handle, "uPositionMatrix"),
            uLensDistortion = GLES20.glGetUniformLocation(handle, "uLensDistortion"),
            // 없는 uniform은 -1이 온다. 그 자체가 "이 프로그램에는 없다"는 뜻이라 따로
            // 플래그를 두지 않는다.
            uTexMatrix = GLES20.glGetUniformLocation(handle, "uTexMatrix"),
            uGamma = GLES20.glGetUniformLocation(handle, RenderArm.GAMMA_UNIFORM),
            uSrcGamma = GLES20.glGetUniformLocation(handle, RenderArm.DRAGO_SRC_GAMMA_UNIFORM),
            uOutGamma = GLES20.glGetUniformLocation(handle, RenderArm.DRAGO_OUT_GAMMA_UNIFORM),
            uSaturation = GLES20.glGetUniformLocation(
                handle, RenderArm.DRAGO_SATURATION_UNIFORM
            ),
            uTiles = GLES20.glGetUniformLocation(handle, RenderArm.CLAHE_TILES_UNIFORM),
            uClaheGamma = GLES20.glGetUniformLocation(handle, RenderArm.CLAHE_GAMMA_UNIFORM),
            uBfRadius = GLES20.glGetUniformLocation(handle, RenderArm.BF_RADIUS_UNIFORM),
            uBfSigmaColor = GLES20.glGetUniformLocation(
                handle, RenderArm.BF_SIGMA_COLOR_UNIFORM
            ),
            uBfSigmaSpace = GLES20.glGetUniformLocation(
                handle, RenderArm.BF_SIGMA_SPACE_UNIFORM
            ),
            uTexel = GLES20.glGetUniformLocation(handle, RenderArm.BF_TEXEL_UNIFORM),
            uEnhance = GLES20.glGetUniformLocation(handle, RenderArm.DEMO_ENHANCE_UNIFORM),
        )
    }

    private fun compileShader(type: Int, source: String, label: String): Int {
        val handle = GLES20.glCreateShader(type)
        GLES20.glShaderSource(handle, source)
        GLES20.glCompileShader(handle)
        val status = IntArray(1)
        GLES20.glGetShaderiv(handle, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetShaderInfoLog(handle)
            Log.e(TAG, "셰이더 컴파일 실패(label=$label, type=$type): $info")
            noteProgramFailure(label, "컴파일 실패(type=$type): $info")
            GLES20.glDeleteShader(handle)
            return 0
        }
        return handle
    }

    /**
     * 실패 원문을 모아 둔다. 정점·프래그먼트가 **둘 다** 실패할 수 있어 덮어쓰지 않고 잇는다 —
     * 먼저 난 것만 남기면 나중 것의 원인을 되물을 수 없다.
     *
     * ⚠ 드라이버 info log는 줄바꿈으로 끝나고 오류가 여러 개면 여러 줄이다. 그대로 담으면
     * `gpu_status` 한 문장 가운데에서 줄이 끊겨 읽기 어렵고 줄 단위 grep도 어긋난다 →
     * **줄바꿈만** `" / "`로 접는다(글자는 지우지 않는다. logcat에는 원문 그대로 남는다).
     */
    private fun noteProgramFailure(label: String, detail: String) {
        val flattened = detail.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" / ")
        val previous = programFailureLogs[label]
        programFailureLogs[label] =
            if (previous == null) flattened else "$previous ; $flattened"
    }

    /** 링크된 프로그램 + 로케이션. 프레임당 조회를 하지 않기 위해 한 번만 담아 둔다. */
    private class QuadProgram(
        val handle: Int,
        val aPosition: Int,
        val aTexCoord: Int,
        val uTexture: Int,
        val uPositionMatrix: Int,
        val uLensDistortion: Int,
        /** 이 프로그램에 없으면 -1. */
        val uTexMatrix: Int,
        /** 이 프로그램에 없으면 -1. */
        val uGamma: Int,
        /** Drago: sRGB → 선형 지수. 이 프로그램에 없으면 -1. */
        val uSrcGamma: Int,
        /** Drago: 되씌우는 감마. 이 프로그램에 없으면 -1. */
        val uOutGamma: Int,
        /** Drago: `mapLuminance`의 채도 지수. 이 프로그램에 없으면 -1. */
        val uSaturation: Int,
        /** CLAHE: 타일 격자 (vec2). 이 프로그램에 없으면 -1. */
        val uTiles: Int,
        /** CLAHE: 결과 L에 씌우는 감마. 이 프로그램에 없으면 -1. */
        val uClaheGamma: Int,
        /** bilateral: 반경(= d/2, **int**다). 이 프로그램에 없으면 -1. */
        val uBfRadius: Int,
        /** bilateral: 색 거리 σ(0..255 단위). 이 프로그램에 없으면 -1. */
        val uBfSigmaColor: Int,
        /** bilateral: 공간 거리 σ(픽셀 단위). 이 프로그램에 없으면 -1. */
        val uBfSigmaSpace: Int,
        /** bilateral: 1/처리해상도. 이 프로그램에 없으면 -1. */
        val uTexel: Int,
        /**
         * 시연용 ② 토글의 mix 계수. **통합 arm의 복제 프래그먼트에만 있고** 나머지
         * 프로그램에서는 -1이라 [drawQuad]가 건너뛴다 → 다른 arm의 GL 호출은 그대로다.
         */
        val uEnhance: Int,
    )

    companion object {
        const val TAG = "PassthroughRenderer"

        /** 표시 변환 콜백이 아직 한 번도 오지 않았다. */
        const val NO_PREVIEW_TRANSFORM_NOTE =
            "아직 도착하지 않았다 — CameraX의 TransformationInfo 콜백 전이다"

        /** present 정점에 회전을 **한 번도 걸지 않았다**(단위행렬로 그렸다). */
        const val PREVIEW_ROTATION_NOT_APPLIED = -1

        /** 마지막 적용이 **측정 중이 아닐 때** 일어났다(런이 시작되기 전에 이미 걸려 있었다). */
        const val PREVIEW_ROTATION_APPLIED_WHILE_IDLE = -1

        /** 새 프레임이 없음을 뜻하는 센티넬. 스키마의 "없는 값 = -1"과 같은 뜻이다. */
        private const val NO_FRAME = -1L
        private const val MISSING = -1L

        private const val STRIDE_BYTES = 4 * 4

        /** FBO_A(패스1 출력) + FBO_B(패스2 출력). ②가 stateless라 2장이면 충분하다. */
        private const val FBO_COUNT = 2

        private const val DEFAULT_CARDBOARD_IMAGE_SCALE = 0.90f
        private const val DEFAULT_CARDBOARD_EYE_OFFSET = -0.08f
        private const val MIN_CARDBOARD_IMAGE_SCALE = 0.60f
        private const val MAX_CARDBOARD_EYE_OFFSET = 0.30f
        private const val CARDBOARD_LENS_DISTORTION = 0.12f

        // 프로그램 라벨. logcat 한 줄과 `session.json`의 실패 원문을 같은 이름으로 잇는다.
        // 표기는 `RenderArm`의 패스 이름 규약을 따르지만, **전부가 `shaderSourcesByPass`의
        // 키인 것은 아니다** — 키와 같은 것은 `oes_to_fbo_a` 하나이고 나머지(`blit_present`,
        // `present_rotate`, `cardboard_lite_*`, `gamma_only_apply` …)는 프로그램 식별용
        // 이름일 뿐이다. 라벨을 키라고 믿고 대조하면 없는 키를 찾게 된다.
        private const val PROGRAM_LABEL_OES = "oes_to_fbo_a"

        /**
         * 오프스크린 복사 프로그램([blitProgram])의 라벨.
         *
         * ⚠ **이름이 `blit_present`지만 present에는 쓰이지 않는다.** present는 회전을 걸어야
         * 해서 [PROGRAM_LABEL_PRESENT](`present_rotate`)로 갈라졌고, 이 프로그램은 그 뒤로
         * ② 자리 FBO 복사에만 쓰인다. 🚫 **값을 고치지 않는다** — 예전 런의 `session.json`과
         * 실패 원문 키가 끊긴다. 오칭인 채로 두고 여기서 설명한다.
         */
        private const val PROGRAM_LABEL_BLIT = "blit_present"

        /**
         * present 전용(회전을 거는) 2D 프로그램. 라벨을 `blit_present`와 **따로** 둔다 —
         * 같은 라벨을 쓰면 두 프로그램 중 어느 쪽이 거부됐는지 `session.json`에서 갈리지
         * 않는다(둘이 서로 다른 정점 셰이더를 쓴다).
         */
        private const val PROGRAM_LABEL_PRESENT = "present_rotate"

        /**
         * 🔴 **표시 회전의 부호를 뒤집는 자리는 여기 하나다.**
         *
         * 유도:
         * 1. `PreviewTransform.rotationDegrees`는 CameraX가 준 값이고 뜻은 **"바로 세우려면
         *    시계 방향으로 몇 도 돌려야 하는가"**다. 같은 규약이 `DetectContract`의 회전
         *    규약(`degrees`는 `ImageProxy.imageInfo.rotationDegrees`, 시계 방향)에 문장으로
         *    이미 있고, 이 값과 **같은 축·같은 부호**다.
         * 2. `Matrix.rotateM(m, 0, θ, 0, 0, 1)`은 z축 양의 방향 회전이다. NDC는 +x가 오른쪽,
         *    +y가 위인 오른손 좌표계이므로 화면에서 보면 θ>0은 **반시계**다.
         * 3. 정점을 반시계로 θ 돌리면 그려지는 내용도 반시계로 θ 돈다. 내용을 **시계**로 R도
         *    돌리려면 θ = −R이어야 한다. → 부호는 −1.
         *
         * 🔴 **이 값은 화면으로만 확정된다.** 위 유도가 한 곳에서 틀리면 90과 270이 서로
         * 바뀌고(180은 부호와 무관해 멀쩡하다), 그 오류를 잡아 줄 기계가 이 앱에 없다 —
         * GPU query도 `DetectGeometryCheck`도 자기 일관성까지만 본다. **실기기 세로 화면에서
         * 영상이 반대로 누워 보이면 이 상수 하나만 +1f로 바꾼다.**
         *
         * 🔴 **그 뒤집힘은 이제 추정이 아니라 관측된 사실이다.** 커밋 `34ad86f`에서 이 값으로
         * `rotateM(-90)`(위 유도상 **반시계** 90°)을 걸었더니 **화면에는 시계 90°로
         * 나타났다**(A34 세로, 육안). 즉 NDC와 화면 사이에 **홀수 번의 뒤집힘이 실재한다** —
         * 패스1이 드라이버가 준 `texMatrix`를 그대로 쓰고 그 행렬이 v를 뒤집는 것이 유력하다.
         *
         * 🔴 **따라서 이 상수와 [OverlayCoordMap.FLIP_Y]는 독립이 아니다.** 위 유도 3단계
         * ("정점을 반시계로 θ 돌리면 내용도 반시계로 돈다")가 깔고 있던 "상하 뒤집힘이
         * 없다"는 가정이 **실측으로 깨졌고**, 그러면 이 부호의 옳은 값도 함께 뒤집힌다.
         * ⚠ 화면이 이상할 때 이 상수만 의심하면 안 된다 — 실기기에서는
         * (`PREVIEW_ROTATION_SIGN`, `FLIP_Y`) **네 조합**을 하나씩 봐야 갈린다. 두 스위치가
         * 서로 다른 파일에 있는 것은 각각 한 곳에만 두기 위해서지, 서로 무관해서가 아니다.
         *
         * ⚠ **지금 이 상수는 A34에서 잠자고 있다** — `hasCameraTransform=true`라 회전각이
         * 0이라서 행렬이 항등이다. 값이 옳은지는 그 플래그가 false인 기기·경로에서만 드러난다.
         */
        private const val PREVIEW_ROTATION_SIGN = -1f
        private const val PROGRAM_LABEL_CARDBOARD_OES = "cardboard_lite_oes"
        private const val PROGRAM_LABEL_CARDBOARD_2D = "cardboard_lite_2d"
        private const val PROGRAM_LABEL_GAMMA = "gamma_only_apply"
        private const val PROGRAM_LABEL_DRAGO_APPLY = "stage2_drago_apply"
        private const val PROGRAM_LABEL_CLAHE_APPLY = "stage2_clahe_apply"
        private const val PROGRAM_LABEL_AGCWD_APPLY = "stage2_agcwd_apply"
        private const val PROGRAM_LABEL_CHAIN_DRAGO_APPLY = "stage2_chain_drago_apply"
        private const val PROGRAM_LABEL_CHAIN_CLAHE_APPLY = "stage2_chain_clahe_apply"

        // ②③④ 통합 arm 전용 복제본의 라벨. 🔴 **위 둘과 다른 이름이어야 한다** — 같은
        // 라벨을 쓰면 실패 원문 맵(programFailureLogs)에서 어느 프로그램이 실패했는지
        // 가를 수 없고, buildProgram이 앞 항목을 remove로 지운다.
        private const val PROGRAM_LABEL_DEMO_CHAIN_DRAGO_APPLY = "stage2_demo_chain_drago_apply"
        private const val PROGRAM_LABEL_DEMO_CHAIN_CLAHE_APPLY = "stage2_demo_chain_clahe_apply"
        private const val PROGRAM_LABEL_FUSED_APPLY = "stage2_fused_apply"

        /** bf 패스. 체인+bf와 융합+bf가 **같은 프로그램**을 쓰므로 라벨도 하나다. */
        private const val PROGRAM_LABEL_BILATERAL = "stage2_bilateral"

        /**
         * 🔴 **회전을 걸지 않는 드로우가 올릴 항등 행렬.** 유니폼은 **프로그램별로 값이
         * 남으므로** "안 올린다"는 "직전에 올린 값을 그대로 쓴다"와 같다. `oesProgram`은
         * 패스1(회전 없음)과 passthrough arm의 present(회전 있음) **양쪽**에서 쓰이므로,
         * 건너뛰면 직전 프레임의 회전 행렬이 패스1에 그대로 남아 FBO가 돌아 버린다.
         *
         * ⚠ 한 번만 만든다 — [drawQuad]는 핫패스라 프레임당 할당을 만들지 않는다.
         *   **읽기 전용으로만 쓴다**(어디서도 이 배열에 쓰지 않는다).
         */
        private val IDENTITY_POSITION_MATRIX = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        /** x, y, u, v — 화면 전체를 덮는 triangle strip 4정점. */
        private val VERTEX_DATA = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        /**
         * GLSL ES 1.00으로 쓴다. ES3 컨텍스트에서도 그대로 컴파일되므로, 확실히 도는 문법을
         * 쓰면서 ①②용 GLES 3.x 컨텍스트(`PIPELINE_STACK.md` §G)를 유지할 수 있다.
         *
         * ⚠ **패스1을 `#version 300 es`로 올리지 않는다.** 올리면 OES 샘플러에
         * `GL_OES_EGL_image_external_essl3`가 필요해져 기기 의존성이 는다(그 확장 유무는
         * `session.json`의 `gl` 블록에 실측으로 남는다).
         *
         * `trimIndent()`를 쓰는 이유: `#extension`을 열 0에 놓기 위해서다. 전처리기 지시문
         * 앞의 공백을 까다롭게 보는 드라이버가 있어 들여쓰기를 남기지 않는다.
         */
        private val VERTEX_SHADER_OES = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uPositionMatrix;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uPositionMatrix * aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        /** 패스1(그리고 패스스루 arm) 프래그먼트. 샘플 하나를 그대로 출력한다. */
        private val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_CARDBOARD_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uLensDistortion;
            void main() {
                vec2 p = (vTexCoord - 0.5) * 2.0;
                float r2 = dot(p, p);
                vec2 uv = 0.5 + 0.5 * p * (1.0 + uLensDistortion * r2);
                if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTexture, uv);
                }
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_CARDBOARD_2D = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uLensDistortion;
            void main() {
                vec2 p = (vTexCoord - 0.5) * 2.0;
                float r2 = dot(p, p);
                vec2 uv = 0.5 + 0.5 * p * (1.0 + uLensDistortion * r2);
                if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTexture, uv);
                }
            }
        """.trimIndent()

        /**
         * 패스2·패스3용. OES 샘플러가 없으므로 `#version 300 es`로 올려도 되지만 올리지
         * 않는다 — 한 파일 안에서 셰이더 방언을 섞으면 나중에 ② 이식할 때 어느 쪽 문법인지
         * 매번 되물어야 한다. 텍스처 좌표 변환은 패스1에서 이미 끝났으므로 uTexMatrix가 없다.
         */
        private val VERTEX_SHADER_2D = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        /**
         * **present(마지막 패스) 전용 정점 셰이더.** [VERTEX_SHADER_2D]에 `uPositionMatrix`만
         * 더한 것이다.
         *
         * 🔴 **[VERTEX_SHADER_2D]를 고쳐 쓰지 않는다.** 그 문자열은 `blit_2pass` arm의 패스2와
         * `gamma_only`의 패스2가 **함께 쓰는** 것이고 승격 베이스라인 재현 경로다 — 거기에
         * 회전 수단이 생기면 오프스크린 패스가 회전할 위험이 생기고, 그 arm의 이전 숫자와
         * 글자 그대로 같다는 전제도 깨진다. 그래서 문자열을 **하나 더** 둔다.
         *
         * ⚠ 회전은 **present에서만** 건다 — 오프스크린 패스가 돌면 ② 체인과 ④ 오버레이가
         * 서로 다른 축의 FBO를 주고받게 된다.
         * 🔴 **다만 `hasCameraTransform=true`인 경로에서는 이 행렬이 항등이다**(CameraX의
         * `texMatrix`가 이미 영상을 세운다). 이 셰이더가 실제로 일하는 것은 그 플래그가
         * false인 경로이며, ④ 박스는 어느 경우에도 [OverlayCoordMap]이 따로 돌린다.
         *
         * ⚠ **종횡비 보정은 여기 없다**(정책 미정 — STATUS 이슈 68). 90/270°에서 소스와
         * 뷰포트의 종횡비가 뒤집히므로 화면이 늘어나 보이는데, 그 보정이 들어온다면
         * `uPositionMatrix`에 스케일을 곱하는 형태로 **이 셰이더를 고치지 않고** 들어온다.
         */
        private val VERTEX_SHADER_PRESENT = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uPositionMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uPositionMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        /** 단순 복사. 패스3(표시)과 blit_2pass arm의 패스2가 같이 쓴다. */
        private val FRAGMENT_SHADER_BLIT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        /**
         * ② 자리의 **비용 하한**. 저조도 알고리즘이 아니다 — 실제 알고리즘은
         * [RenderArm.CLAHE_GAMMA] · [RenderArm.AGCWD] · [RenderArm.DRAGO] arm이다.
         * 지수는 상수로 박지 않고 uniform으로 받는다(`INTERFACES.md` §B-5).
         */
        private val FRAGMENT_SHADER_GAMMA = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uGamma;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(pow(c.rgb, vec3(uGamma)), c.a);
            }
        """.trimIndent()
    }
}
