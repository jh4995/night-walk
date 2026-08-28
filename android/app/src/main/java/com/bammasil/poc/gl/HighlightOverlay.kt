package com.bammasil.poc.gl

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * ④ 선택적 강조 오버레이 **1패스**. `stage_i_ms`(버짓 I칸)를 낸다.
 *
 * ### 박스의 출처가 **arm마다 다르다**
 *
 * | arm | 박스 | 진입점 |
 * |---|---|---|
 * | `highlight_boxes` / `_stress` / `_1q` | **정적 더미**(셀 격자, 프레임마다 같다) | [draw] |
 * | `detect_cpu_highlight` / `_1q` | 🔴 **③ 탐지 결과**를 H칸([OverlaySmoother])이 평활한 목록 | [setDynamicGeometry] + [drawPrepared] |
 *
 * ### 명세는 상류 `scripts/emphasize.py`에서 확정된 것이다
 * `FRAME_BUDGET.md` §3 주5 / `docs/research/RESEARCH_20260803_UPSTREAM.md` §5:
 * **이중 스트로크**(검정 밑선 + 대비색 본선) · **비채움** · `stairs`=노랑 / `person`=시안 ·
 * 🔴 **빨강 금지** · 🔴 **깜빡임 금지** · 두께는 **짧은 변 비례로 720p 기준 4px**.
 * 값은 [RenderArm]의 `HIGHLIGHT_*` 상수에서 오며 이 파일에 숫자를 박지 않는다.
 *
 * 🔴 **이 빌드는 그중 '비채움'에서 벗어난다** — 박스 **안쪽**을 클래스 색으로 옅게 채운다
 * (알파 [RenderArm.OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE]). 사용자 지시이며 대가는
 * [RenderArm.HIGHLIGHT_FILL_DEVIATION]에 적혀 `session.json`으로 나간다. 🔴 **알파는 빌드
 * 상수이고 프레임 간·시간에 따라 변하지 않는다** — 알파 변조는 광과민 안전 규약 위반이다
 * ([RenderArm.OVERLAY_NO_FLICKER_DESIGN] (1)).
 *
 * ### 🔴 색을 **인덱스로 고르지 않는다**
 * 색의 출처는 [OverlayClassColors] 하나이고 키는 **클래스 이름**이다. ③ 결과 경로에서는
 * 색이 게시 시점에 이미 정해져 스냅샷에 실려 오므로([com.bammasil.poc.detect.DetectOverlayPublisher])
 * 이 파일은 클래스도 이름도 보지 않고 값을 그대로 옮긴다. 정적 더미 경로의 색은 **우리가
 * 선언한 측정 조건**이며([staticColorName]) 계약 A-4의 인덱스가 아니다 — 예전에 여기 있던
 * `index 0 = stairs` 가정이 그것이었고, ③→④가 이어지는 순간 사람과 계단의 색을 뒤바꾸는
 * 코드였다.
 *
 * ### 왜 전체화면 SDF가 아니라 얇은 사각형 지오메트리인가
 * 프래그먼트 SDF로 그리면 픽셀 셰이더가 **화면 전체**를 돌아 오버레이 비용이 화면 전체
 * 비용으로 부풀고, 그러면 I칸이 다른 물리량이 된다. `FRAME_BUDGET.md` §5는 I칸을 **GL
 * 드로우콜** 계측으로 못 박았고(PoC의 Canvas 오버레이가 "다른 물리량"이라 탈락한 것과 같은
 * 이유다), 지오메트리로 그리면 채워지는 픽셀이 **박스 면적의 합**뿐이다 — SDF였다면
 * 박스가 0개인 프레임에서도 화면 전체를 칠한다.
 * ⚠ 이 판단은 fill이 들어온 뒤에도 유효하지만 **근거의 크기가 달라졌다**: 스트로크만이던
 * 시절에는 "테두리 면적뿐"이었고 지금은 "박스 면적의 합"이다. 그래서 I칸의 설명 변수가
 * 개수에서 **면적**으로 바뀐다([overlayFillFrac]가 그 축을 `frames.csv`에 낸다).
 *
 * ### 프레임당 할당 0
 * 🔴 정점 버퍼를 [MAX_BOX_COUNT]개분으로 **컨텍스트당 한 번** 잡고 이후에는 **in-place로
 * 재기록**한다. ③ 결과 경로는 좌표가 프레임마다 바뀌므로 매 프레임 다시 쓰는데, 그때
 * `allocateDirect`를 부르면 GL 스레드에서 GC가 돌고 그것이 곧 프레임타임 꼬리다.
 *
 * ⚠ 정적 경로의 박스 개수와 배치는 **계약값이 아니라 우리가 선언하는 측정 조건**이다
 * ([RenderArm.HIGHLIGHT_BOX_PROVENANCE]). `session.json`의 `overlay.box_count`에 실린다 —
 * ③ 결과 경로에서는 개수가 프레임마다 다르므로 그 키가 null이고 `frames.csv`의
 * `overlay_boxes` 열이 프레임별로 말한다([RenderArm.OVERLAY_DYNAMIC_BOX_NOTE]).
 *
 * **스레드 규약: 전부 GL 스레드에서만 부른다.**
 */
class HighlightOverlay {

    var ready = false
        private set

    var status: String = "아직 준비하지 않았다 (arm이 ④ 오버레이 arm이 아니다)"
        private set

    /** 이번 처리 해상도에서 실제로 쓴 본선 두께(px). 협상 전에는 0. */
    var strokePx = 0f
        private set

    /** 검정 밑선의 두께(px). 본선보다 양쪽으로 [RenderArm.HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P]씩 넓다. */
    var underlinePx = 0f
        private set

    /** 이번 지오메트리의 정점 수. `session.json`에 실어 두면 비용을 나중에 되짚을 수 있다. */
    var vertexCount = 0
        private set

    /**
     * 🔴 **이번 지오메트리의 fill 면적 비율** = Σ(박스 면적) ÷ 화면 면적.
     * NDC 기하에서 `Σ|(x1-x0)(y1-y0)| / 4`다(NDC 전면적이 2×2 = 4). 박스가 0개면 0.
     *
     * ⚠ **겹침을 보정하지 않는다** — 두 박스가 포개지면 그 넓이를 두 번 센다. 그래서 이 값은
     * 1을 넘을 수 있고 "칠해진 픽셀의 비율"이 아니라 **면적의 합**이다. 같은 이유로 뷰포트
     * **밖으로 나간 부분도 빼지 않는다**(③ 결과 경로는 좌표를 클램프하지 않는다 —
     * [setDynamicGeometry]). 두 사실을 보정하려면 픽셀을 세야 하고 그건 프레임당 할당 0과
     * GL 스레드 규약을 함께 깬다.
     *
     * `frames.csv`의 `overlay_fill_frac` 열이 이 값이다(스키마 v8). 개수 축인 `overlay_boxes`와
     * **짝을 이룬다** — 스트로크 비용은 개수·둘레가, fill 비용은 이 면적이 설명한다.
     * ⚠ 그 열은 ④ 오버레이 열 묶음과 **같은 게이트**라 ③ 결과 arm에서만 나간다. 정적 더미
     * arm에서도 이 값은 계산되지만(격자가 고정이라 프레임마다 같다) CSV로 나가지 않는다.
     */
    var overlayFillFrac = 0f
        private set

    private var program = 0
    private var aPosition = -1
    private var aColor = -1

    /**
     * fill의 투명도. 🔴 **`aColor`를 vec4로 바꾸지 않고 별도 float 속성으로 둔다** —
     * 색의 출처인 [OverlayClassColors]와 [OverlaySmoother.colors]가 3-stride이고
     * `colorFor`가 `FloatArray(3)`을 준다. 색 소유권을 건드리지 않는 유일한 길이다.
     */
    private var aAlpha = -1

    private var processWidth = 0
    private var processHeight = 0

    /**
     * 지금 담고 있는 **정적** 지오메트리가 몇 개짜리인가. [NO_GEOMETRY]면 아직 만들지 않았다.
     * ⚠ ③ 결과 경로가 같은 버퍼를 덮어쓰면 이 값을 [NO_GEOMETRY]로 되돌린다 — 그러지 않으면
     * arm을 되돌렸을 때 캐시가 "정적 박스가 들어 있다"고 거짓말한다.
     */
    private var geometryBoxCount = NO_GEOMETRY
    private var geometryWidth = 0
    private var geometryHeight = 0

    /**
     * 🔴 **[MAX_BOX_COUNT]개분으로 컨텍스트당 한 번 잡는다.** 이후 모든 갱신은 in-place다
     * (위 KDoc "프레임당 할당 0").
     */
    private var vertexBuffer: FloatBuffer? = null

    /** [setDynamicGeometry]가 [putRing]에 넘길 색. 🔴 프레임당 할당을 없애기 위한 스크래치다. */
    private val dynamicColor = FloatArray(3)

    /** 컴파일러 원문. 실패했을 때 [status]에 실어 `session.json`으로 내보낸다. */
    private var compileLog = ""

    // ── 컨텍스트 수명 ─────────────────────────────────────────────────────

    /**
     * `onSurfaceCreated`에서 GL 능력 프로브 직후. 실패하면 스스로 꺼지고 **컴파일러 원문을
     * 상태 문장에 남긴다** — 성공 서술을 뒤에 잇지 않는다(그랬다가 11분 런이 통째로 무효가
     * 된 선례가 `PassthroughRenderer.applyProgramFailureStatus`에 있다).
     *
     * 셰이더는 GLSL ES 1.00이다. 텍스처도 SSBO도 읽지 않으므로 올릴 이유가 없고, 확실히 도는
     * 문법을 쓰는 쪽이 기기 의존성을 줄인다(`PassthroughRenderer`의 3패스 골격과 같은 판단).
     */
    fun onContextCreated(capabilities: GlCapabilities?) {
        releaseGl()
        drainStageGlErrors()
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) GLES20.glDeleteShader(vertex)
            if (fragment != 0) GLES20.glDeleteShader(fragment)
            disable(
                "④ 오버레이 셰이더 컴파일에 실패했다. 이 arm은 그릴 수 없고 모든 프레임이 " +
                    "패스스루로 폴백한다(render.processing.frames_fell_back_to_passthrough " +
                    "확인). 컴파일러 원문 = ${compileLog.ifEmpty { "(드라이버가 원문을 주지 않았다)" }}"
            )
            return
        }
        val handle = GLES20.glCreateProgram()
        GLES20.glAttachShader(handle, vertex)
        GLES20.glAttachShader(handle, fragment)
        GLES20.glLinkProgram(handle)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (linked[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(handle)
            GLES20.glDeleteProgram(handle)
            disable(
                "④ 오버레이 프로그램 링크에 실패했다. 이 arm은 그릴 수 없고 모든 프레임이 " +
                    "패스스루로 폴백한다. 컴파일러 원문 = ${flatten(info)}"
            )
            return
        }
        program = handle
        aPosition = GLES20.glGetAttribLocation(handle, "aPosition")
        aColor = GLES20.glGetAttribLocation(handle, "aColor")
        // 🔴 aAlpha도 **같은 검사에 넣는다.** 드라이버가 미사용 속성을 지우면 -1이 되고,
        //    -1로 glVertexAttribPointer를 부르면 조용히 무시돼 fill이 불투명해진다
        //    (= 박스 내부가 통째로 단색이 되는 무음 실패다). 이 파일의 규약은
        //    "값을 지어내지 않고 arm을 끈다"이므로 여기서 함께 끈다.
        aAlpha = GLES20.glGetAttribLocation(handle, "aAlpha")
        if (aPosition < 0 || aColor < 0 || aAlpha < 0) {
            GLES20.glDeleteProgram(handle)
            program = 0
            disable(
                "④ 오버레이 정점 속성을 찾지 못했다 " +
                    "(aPosition=$aPosition, aColor=$aColor, aAlpha=$aAlpha) — 드라이버가 " +
                    "링크는 했지만 속성을 지웠다. 값을 지어내지 않고 arm을 끈다"
            )
            return
        }
        // 🔴 정점 버퍼는 **여기서 한 번만** 잡는다(최대 개수분). 프레임 경로에서
        //    allocateDirect를 부르면 GL 스레드 GC가 프레임타임 꼬리를 만든다.
        vertexBuffer = ByteBuffer
            .allocateDirect(MAX_BOX_COUNT * VERTS_PER_BOX * FLOATS_PER_VERTEX * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexCount = 0
        overlayFillFrac = 0f
        geometryBoxCount = NO_GEOMETRY
        ready = true
        // 🔴 **이 문장은 박스의 출처를 말하지 않는다.** `onContextCreated`는 arm보다 먼저 돌고
        //    arm을 모르는데, 이 값은 session.json의 `overlay.gpu_status`로 그대로 나간다 —
        //    예전에 여기 있던 "정적 더미 박스(난수 없음)"가 ③ 결과 arm의 로그에도 실려
        //    같은 블록의 `box_source`("③ 탐지 결과다")와 **정면으로 모순됐다.**
        //    거짓 사유가 로그로 나가는 것이 이 라운드가 막으려는 실패 양식이다.
        //    박스 출처는 arm이 정하고 `overlay.box_source`가 arm별로 말한다.
        status =
            "준비 완료 — ④ 오버레이 **1패스**(사각형 quad, glDrawArrays " +
                "${GL_PRIMITIVE_NAME} 1회). 이중 스트로크(검정 밑선 + 대비색 본선) + " +
                "**박스 안쪽을 클래스 색으로 채운다**(알파 " +
                "${RenderArm.OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE}, 빌드 상수라 시간 변조가 " +
                "없다 — 상류 '비채움' 명세로부터의 이탈이며 사유는 overlay.fill_deviation). " +
                "그리기 순서는 **fill 전량 → 스트로크 전량**이고 드로우콜은 그대로 1회다. " +
                "두께는 **처리 해상도의 짧은 변에서 계산**한다 " +
                "(720p 기준 ${RenderArm.HIGHLIGHT_STROKE_PX_AT_720P}px). " +
                "정점 버퍼는 최대 ${MAX_BOX_COUNT}개분을 **컨텍스트당 한 번** 잡고 이후 " +
                "in-place로 재기록한다(프레임당 할당 0). " +
                "텍스처를 읽지 않으므로 셰이더는 GLSL ES 1.00이다. " +
                "⚠ **이 문장은 GL 자원의 상태이고 박스의 출처를 말하지 않는다** — " +
                "출처(정적 더미인가 ③ 탐지 결과인가)는 arm이 정하며 overlay.box_source가 " +
                "arm별로 말한다"
        Log.i(TAG, status)
    }

    /**
     * 처리 해상도가 정해졌을 때(또는 바뀌었을 때). **두께와 박스 좌표는 여기서 온 값으로만
     * 계산한다** — 픽셀 하드코딩 금지 규약이다. 지오메트리는 다음 [draw]에서 다시 만든다.
     */
    fun onProcessSizeChanged(width: Int, height: Int) {
        processWidth = width
        processHeight = height
        // 짧은 변 비례. 720p의 짧은 변이 720이므로 그 값으로 나눈다.
        val shortSide = minOf(width, height).toFloat()
        val scale = if (shortSide > 0f) shortSide / RenderArm.HIGHLIGHT_SHORT_SIDE_AT_720P else 0f
        strokePx = RenderArm.HIGHLIGHT_STROKE_PX_AT_720P * scale
        underlinePx =
            (RenderArm.HIGHLIGHT_STROKE_PX_AT_720P +
                2f * RenderArm.HIGHLIGHT_UNDERLINE_MARGIN_PX_AT_720P) * scale
        // 해상도가 바뀌면 정점 좌표(NDC)와 두께가 함께 바뀐다 → 다시 만든다.
        geometryBoxCount = NO_GEOMETRY
    }

    /** 컨텍스트가 사라졌다. 프로그램을 반납한다(빠뜨리면 재생성마다 샌다). */
    fun releaseGl() {
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
        aPosition = -1
        aColor = -1
        aAlpha = -1
        vertexBuffer = null
        vertexCount = 0
        overlayFillFrac = 0f
        geometryBoxCount = NO_GEOMETRY
        geometryWidth = 0
        geometryHeight = 0
        compileLog = ""
        ready = false
        status = "GL 컨텍스트가 사라져 자원을 반납했다"
    }

    // ── 프레임 경로 (렌더 스레드 hot path) ────────────────────────────────

    /**
     * ④ 패스. **현재 바인드된 프레임버퍼에 그린다 — `glClear`를 부르지 않는다.**
     * 오버레이는 ② 출력 **위에** 얹는 것이라 지우면 그림이 사라진다.
     *
     * ⚠ 그래서 이 패스는 타일 GPU에서 **컬러 어태치먼트를 다시 load한다.** `stage_i_ms`에는
     * 그 load 비용이 섞여 있고, 그것이 오버레이 패스의 실제 비용이다(빼낼 수단이 없다).
     * 같은 문장이 `session.json`의 `overlay` 블록으로 나간다.
     *
     * @param boxCount 이 arm이 **선언한** 정적 박스 수([RenderArm.highlightBoxCount]).
     *   🔴 ③ 결과 경로에서는 이 함수를 부르지 않는다 — [setDynamicGeometry] + [drawPrepared]다.
     */
    fun draw(boxCount: Int) {
        ensureStaticGeometry(boxCount)
        drawPrepared()
    }

    /**
     * 이미 버퍼에 들어 있는 지오메트리를 그린다. ③ 결과 경로가 쓰는 진입점이며, 정점 재기록은
     * H칸 구간([setDynamicGeometry])에서 이미 끝나 있다 — 🔴 **그 CPU 비용을 `stage_i_ms`
     * 안에 두지 않으려는 것이다**(그 열은 GPU 시계라 CPU 일이 계상되지 않고 사라진다).
     *
     * 박스가 0개면 **아무 GL 명령도 내지 않는다.** 0은 정상값이고(야간에는 그런 프레임이
     * 다수다) 그때 `stage_i_ms`는 패스3의 바인드·뷰포트 비용뿐이다.
     */
    fun drawPrepared() {
        // 🔴 조기 반환 경로에서는 glEnable을 **아예 부르지 않는다** — 켜고 나가면 그 상태가
        //    present 패스까지 남는다(아래 glDisable과 짝이다).
        if (vertexCount <= 0) return
        val buffer = vertexBuffer ?: return
        GLES20.glUseProgram(program)
        buffer.position(0)
        GLES20.glVertexAttribPointer(
            aPosition, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer
        )
        GLES20.glEnableVertexAttribArray(aPosition)
        buffer.position(2)
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer)
        GLES20.glEnableVertexAttribArray(aColor)
        buffer.position(FLOATS_PER_VERTEX - 1)
        GLES20.glVertexAttribPointer(aAlpha, 1, GLES20.GL_FLOAT, false, STRIDE_BYTES, buffer)
        GLES20.glEnableVertexAttribArray(aAlpha)
        // 🔴 **fill이 반투명이므로 블렌딩을 켠다.** 깊이 테스트는 여전히 안 켠다.
        //    알파 채널만 GL_ZERO/GL_ONE으로 분리하는 이유: 통상 블렌드는 FBO의 **dst 알파**를
        //    1 미만으로 떨어뜨리는데 그 FBO를 present 패스가 다시 샘플링한다. 컬러만 섞고
        //    알파는 그대로 두면 present가 읽는 값이 블렌딩 OFF 때와 같다.
        //    ⚠ 스트로크의 알파는 1.0이라 검정 밑선·본선 픽셀은 블렌딩 OFF 때와 **비트 단위로
        //      같다**(src*1 + dst*0).
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(
            GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ZERO, GLES20.GL_ONE
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        // 🔴 **같은 함수 안에서 반드시 끈다.** 상태가 present 패스까지 남으면 다른 arm과
        //    조건이 달라지고, 그 차이는 로그 어디에도 남지 않는다.
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    /**
     * ③ 결과 경로의 정점 데이터를 **in-place로 다시 쓴다.** 🔴 **`stage_h_ms` 구간 안에서
     * 불린다**([RenderArm.OVERLAY_STAGE_H_SCOPE]) — GPU 패스를 열기 전이다.
     *
     * 입력은 [OverlaySmoother]가 낸 **NDC 박스 목록**이다(센서→NDC 매핑은 [OverlayCoordMap]이
     * 그쪽에서 이미 했다). 색도 그쪽이 들고 있는 값을 **그대로 옮긴다** — 이 함수는 클래스도
     * 이름도 보지 않는다.
     *
     * 🔴 **클램프하지 않는다.** 프레임 밖 좌표는 그대로 넘기고 GL 뷰포트가 자른다.
     * ⚠ 그래서 박스가 프레임 가장자리에 붙으면 **검정 밑선이 한쪽만 잘려 보인다** —
     * 이 기하 선택의 알려진 결과이며([RenderArm.HIGHLIGHT_DEVIATION] (4)) 결함이 아니다.
     */
    fun setDynamicGeometry(list: OverlaySmoother) {
        val buffer = vertexBuffer
        val w = processWidth
        val h = processHeight
        if (buffer == null || w <= 0 || h <= 0) {
            vertexCount = 0
            overlayFillFrac = 0f
            return
        }
        // 🔴 정적 캐시를 무효화한다 — 같은 버퍼를 공유하므로 안 내리면 arm을 되돌렸을 때
        //    "정적 박스가 이미 들어 있다"고 거짓말한다.
        geometryBoxCount = NO_GEOMETRY
        val n = if (list.count > MAX_BOX_COUNT) MAX_BOX_COUNT else list.count
        if (n <= 0) {
            vertexCount = 0
            // 🔴 박스 0개는 **정상값**이고 그때 면적도 0이다(없음의 표식이 아니다).
            overlayFillFrac = 0f
            return
        }
        // 픽셀 두께 → NDC. 정적 경로와 **같은 식**이다(두께는 처리 해상도에서만 온다).
        val mainHalfX = strokePx / w
        val mainHalfY = strokePx / h
        val underHalfX = underlinePx / w
        val underHalfY = underlinePx / h
        buffer.position(0)
        // 🔴 **fill 전량을 먼저 돈다** — 박스별 인터리브가 아니다. 알파 fill이 뒤에 그려지면
        //    앞 박스의 스트로크를 물들여 겹친 박스에서 대비가 깎이고, 저시력 사용자에게
        //    스트로크는 마지막 방어선이다. 루프를 두 번 돌 뿐 **할당도 GL 호출도 늘지 않는다**
        //    (n <= MAX_BOX_COUNT = 32).
        var areaSum = 0f
        for (i in 0 until n) {
            val b = i * 4
            val c = i * 3
            dynamicColor[0] = list.colors[c]
            dynamicColor[1] = list.colors[c + 1]
            dynamicColor[2] = list.colors[c + 2]
            val x0 = list.ndc[b]
            val y0 = list.ndc[b + 1]
            val x1 = list.ndc[b + 2]
            val y1 = list.ndc[b + 3]
            // fill 사각형은 **박스 원 좌표 그대로**다(안쪽 인셋 없음). 두께보다 얇은 박스에서
            // putRing의 클램프와 규칙이 갈라지면 그 틈이 그대로 보인다.
            putQuad(
                buffer, x0, y0, x1, y1, dynamicColor,
                RenderArm.OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE
            )
            // 면적 축(overlay_fill_frac). 이미 도는 루프 안이고 새 배열·문자열을 만들지 않는다.
            areaSum += abs((x1 - x0) * (y1 - y0))
        }
        for (i in 0 until n) {
            val b = i * 4
            val c = i * 3
            dynamicColor[0] = list.colors[c]
            dynamicColor[1] = list.colors[c + 1]
            dynamicColor[2] = list.colors[c + 2]
            // 🔴 **검정 밑선을 먼저** 넣는다(정적 경로와 같은 이유 — 나중 프리미티브가 덮는다).
            putRing(
                buffer, list.ndc[b], list.ndc[b + 1], list.ndc[b + 2], list.ndc[b + 3],
                underHalfX, underHalfY, COLOR_UNDERLINE, STROKE_ALPHA
            )
            putRing(
                buffer, list.ndc[b], list.ndc[b + 1], list.ndc[b + 2], list.ndc[b + 3],
                mainHalfX, mainHalfY, dynamicColor, STROKE_ALPHA
            )
        }
        buffer.position(0)
        vertexCount = n * VERTS_PER_BOX
        overlayFillFrac = areaSum / NDC_TOTAL_AREA
    }

    /**
     * **정적 더미** 정점 데이터를 만든다. **개수나 해상도가 바뀔 때만** 돈다 — 버퍼는
     * [onContextCreated]가 이미 잡아 두었으므로 여기서는 **in-place 재기록**뿐이다.
     *
     * 배치: [CELL_COLS] × [CELL_ROWS] 셀 격자에서 **서로 겹치지 않는 같은 크기**의 박스를
     * 뽑는다. 뽑는 순서를 stride [CELL_STRIDE]로 돌려(gcd(7,32)=1이라 순열이다) 개수가 적은
     * arm에서도 화면 전체에 퍼지게 했다. **박스 크기가 arm 간 같으므로** 32개 arm의 값을
     * 4개 arm과 나란히 놓아 개당 비용 기울기로 쓸 수 있다 — 격자를 개수에 맞춰 바꾸면
     * 박스마다 둘레가 달라져 그 기울기가 성립하지 않는다.
     */
    private fun ensureStaticGeometry(boxCount: Int) {
        if (geometryBoxCount == boxCount &&
            geometryWidth == processWidth && geometryHeight == processHeight
        ) {
            return
        }
        val w = processWidth
        val h = processHeight
        val buffer = vertexBuffer
        if (buffer == null || boxCount <= 0 || boxCount > MAX_BOX_COUNT || w <= 0 || h <= 0) {
            vertexCount = 0
            overlayFillFrac = 0f
            geometryBoxCount = NO_GEOMETRY
            return
        }
        // 픽셀 두께 → NDC. x·y의 픽셀 크기가 다르므로 각각 환산한다(정사각형이 아니다).
        val mainHalfX = strokePx / w
        val mainHalfY = strokePx / h
        val underHalfX = underlinePx / w
        val underHalfY = underlinePx / h

        val verts = boxCount * VERTS_PER_BOX
        buffer.position(0)
        // 🔴 **fill 전량 → 스트로크 전량.** [setDynamicGeometry]와 같은 순서·같은 이유다
        //    (알파 fill이 뒤에 오면 앞 박스의 스트로크를 물들인다). 정적 경로는 개수·해상도가
        //    바뀔 때만 도는 함수라 루프를 두 번 도는 비용도 프레임 경로에 없다.
        var areaSum = 0f
        for (i in 0 until boxCount) {
            val cell = (i * CELL_STRIDE) % CELL_COUNT
            val col = cell % CELL_COLS
            val row = cell / CELL_COLS
            val nx0 = ((col + CELL_INSET) / CELL_COLS) * 2f - 1f
            val nx1 = ((col + 1f - CELL_INSET) / CELL_COLS) * 2f - 1f
            val ny0 = ((row + CELL_INSET) / CELL_ROWS) * 2f - 1f
            val ny1 = ((row + 1f - CELL_INSET) / CELL_ROWS) * 2f - 1f
            // fill 사각형은 **박스 원 좌표 그대로**다(안쪽 인셋 없음).
            putQuad(
                buffer, nx0, ny0, nx1, ny1,
                OverlayClassColors.colorFor(staticColorName(i)),
                RenderArm.OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE
            )
            areaSum += abs((nx1 - nx0) * (ny1 - ny0))
        }
        for (i in 0 until boxCount) {
            val cell = (i * CELL_STRIDE) % CELL_COUNT
            val col = cell % CELL_COLS
            val row = cell / CELL_COLS
            // 셀 안쪽으로 INSET만큼 들여 박스를 놓는다(정규화 좌표 0..1).
            val x0 = (col + CELL_INSET) / CELL_COLS
            val x1 = (col + 1f - CELL_INSET) / CELL_COLS
            val y0 = (row + CELL_INSET) / CELL_ROWS
            val y1 = (row + 1f - CELL_INSET) / CELL_ROWS
            // 0..1 → NDC(-1..1).
            val nx0 = x0 * 2f - 1f
            val nx1 = x1 * 2f - 1f
            val ny0 = y0 * 2f - 1f
            val ny1 = y1 * 2f - 1f
            // 🔴 색을 **이름으로** 고른다([staticColorName]) — 인덱스로 고르지 않는다.
            //    i는 탐지 결과의 클래스 인덱스가 아니라 **박스 순번**이고, "앞 몇 개는
            //    stairs"는 우리가 선언한 측정 조건이다(계약 A-4의 순서가 아니다).
            //    색은 픽셀 비용에 영향이 없다(어느 색이든 같은 면적을 채운다).
            val color = OverlayClassColors.colorFor(staticColorName(i))
            // 🔴 **검정 밑선을 먼저** 넣는다. 한 드로우콜 안에서도 프리미티브 순서는 보장되므로
            //    겹치는 부분은 나중에 오는 본선이 덮는다 = 밖으로 검정 테두리가 남는다.
            putRing(
                buffer, nx0, ny0, nx1, ny1, underHalfX, underHalfY,
                COLOR_UNDERLINE, STROKE_ALPHA
            )
            putRing(buffer, nx0, ny0, nx1, ny1, mainHalfX, mainHalfY, color, STROKE_ALPHA)
        }
        buffer.position(0)
        vertexCount = verts
        overlayFillFrac = areaSum / NDC_TOTAL_AREA
        geometryBoxCount = boxCount
        geometryWidth = w
        geometryHeight = h
    }

    /**
     * 경계선 위에 **가운데를 맞춘** 띠 4개(위·아래·왼·오른). `cv2.rectangle(thickness=t)`가
     * 경계 안쪽으로 t/2, 바깥으로 t/2를 채우는 것과 같은 배치다.
     *
     * **이 함수는 박스 내부를 칠하지 않는다** — 좌·우 띠의 y 범위를 위·아래 띠만큼 줄여
     * 모서리를 두 번 덮지도 않는다(겹치면 색은 같지만 오버드로가 생기고 그만큼 I칸이 부푼다).
     * ⚠ **박스 내부는 별도의 fill quad가 칠한다**(호출자가 이 함수보다 **먼저** 넣는다) —
     * 이 함수의 불변식은 "띠 밖에서 아무것도 안 그린다"이지 "박스 안이 비어 있다"가 아니다.
     *
     * @param alpha 스트로크는 항상 [STROKE_ALPHA]다. 인자로 받는 것은 [putVertex]까지
     *   같은 경로를 쓰기 위한 것이며, 🔴 **시간에 따라 바뀌는 값을 넣지 말 것**(알파 변조는
     *   광과민 안전 규약 위반이다 — [RenderArm.OVERLAY_NO_FLICKER_DESIGN]).
     */
    private fun putRing(
        buffer: FloatBuffer,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        halfX: Float,
        halfY: Float,
        color: FloatArray,
        alpha: Float,
    ) {
        // 아래 띠 / 위 띠 — 모서리까지 덮는다.
        putQuad(buffer, x0 - halfX, y0 - halfY, x1 + halfX, y0 + halfY, color, alpha)
        putQuad(buffer, x0 - halfX, y1 - halfY, x1 + halfX, y1 + halfY, color, alpha)
        // 왼 띠 / 오른 띠 — 모서리는 위에서 이미 덮었으므로 그만큼 뺀다.
        // ⚠ 박스 높이가 두께보다 작으면 이 범위가 **뒤집힌다** — quad의 두 y가 교차해
        //   위·아래 띠가 이미 덮은 자리를 한 번 더 칠하는 오버드로가 된다.
        //   🔎 fill이 들어온 뒤에도 이 클램프는 그대로 옳다: 내부를 칠하는 것은 fill quad의
        //   일이고, 스트로크 띠가 그 위에 겹쳐 칠하면 알파가 두 번 곱해져 대비가 어긋난다.
        //   중앙으로 클램프해 그 경우 높이 0이 되게 한다 — 정점 수는 그대로 유지된다
        //   ([VERTS_PER_BOX]가 상수여야 하므로 조건부로 빼지 않는다).
        val midY = 0.5f * (y0 + y1)
        val innerY0 = minOf(y0 + halfY, midY)
        val innerY1 = maxOf(y1 - halfY, midY)
        putQuad(buffer, x0 - halfX, innerY0, x0 + halfX, innerY1, color, alpha)
        putQuad(buffer, x1 - halfX, innerY0, x1 + halfX, innerY1, color, alpha)
    }

    /** 축 정렬 사각형 하나 = 삼각형 2개 = 정점 6개([VERTS_PER_FILL]). */
    private fun putQuad(
        buffer: FloatBuffer,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: FloatArray,
        alpha: Float,
    ) {
        putVertex(buffer, x0, y0, color, alpha)
        putVertex(buffer, x1, y0, color, alpha)
        putVertex(buffer, x0, y1, color, alpha)
        putVertex(buffer, x1, y0, color, alpha)
        putVertex(buffer, x1, y1, color, alpha)
        putVertex(buffer, x0, y1, color, alpha)
    }

    private fun putVertex(
        buffer: FloatBuffer,
        x: Float,
        y: Float,
        color: FloatArray,
        alpha: Float,
    ) {
        buffer.put(x)
        buffer.put(y)
        buffer.put(color[0])
        buffer.put(color[1])
        buffer.put(color[2])
        // 🔴 색 뒤에 **별도 float 하나**다. aColor를 vec4로 만들지 않는 이유는 [aAlpha].
        buffer.put(alpha)
    }

    private fun compileShader(type: Int, source: String): Int {
        val handle = GLES20.glCreateShader(type)
        GLES20.glShaderSource(handle, source)
        GLES20.glCompileShader(handle)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(handle, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetShaderInfoLog(handle)
            Log.e(TAG, "④ 오버레이 셰이더 컴파일 실패(type=$type): $info")
            compileLog = if (compileLog.isEmpty()) {
                "(type=$type) ${flatten(info)}"
            } else {
                "$compileLog ; (type=$type) ${flatten(info)}"
            }
            GLES20.glDeleteShader(handle)
            return 0
        }
        return handle
    }

    /** 드라이버 info log의 줄바꿈만 접는다. 글자는 지우지 않는다. */
    private fun flatten(detail: String?): String = detail
        ?.lines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.joinToString(" / ")
        ?: ""

    private fun disable(reason: String) {
        ready = false
        status = reason
        Log.w(TAG, "④ 오버레이 비활성: $reason")
    }

    companion object {
        const val TAG = "HighlightOverlay"

        /** `session.json`에 그대로 나가는 프리미티브 이름. */
        const val GL_PRIMITIVE_NAME = "GL_TRIANGLES"

        private const val NO_GEOMETRY = -1

        /** x, y, r, g, b, **a**. 알파는 색과 분리된 별도 속성이다([aAlpha]). */
        private const val FLOATS_PER_VERTEX = 6
        private const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

        /** 띠 4개 × 삼각형 2개 × 정점 3개 = 24. 이중 스트로크라 박스당 그 두 배다. */
        const val VERTS_PER_RING = 24

        /** 박스 안쪽 fill 사각형 하나 = 삼각형 2개 × 정점 3개. */
        const val VERTS_PER_FILL = 6

        /** fill 1 + 스트로크 2벌. 🔴 정점 버퍼 용량이 이 상수 하나에서 나온다. */
        const val VERTS_PER_BOX = VERTS_PER_FILL + VERTS_PER_RING * 2

        /**
         * 스트로크(검정 밑선 · 대비색 본선)의 알파. **불투명이다** — 이 값 덕에 스트로크
         * 픽셀은 블렌딩을 켜기 전과 비트 단위로 같다(src*1 + dst*0). fill의 알파는
         * [RenderArm.OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE]이며 그 숫자는 여기 없다.
         */
        const val STROKE_ALPHA = 1f

        /** NDC 전면적. -1..1이 양축이므로 2 × 2다. [overlayFillFrac]의 분모다. */
        const val NDC_TOTAL_AREA = 4f

        // ── 배치(우리가 선언한 측정 조건) ──────────────────────────────────
        // ⚠ 사양이 아니다. 장면마다 다른 값이므로 [RenderArm.HIGHLIGHT_BOX_PROVENANCE]를
        //   session.json에 함께 싣는다.

        /** 셀 격자. 최대 개수([RenderArm.HIGHLIGHT_BOX_COUNT_STRESS])와 같은 셀 수다. */
        const val CELL_COLS = 8
        const val CELL_ROWS = 4
        const val CELL_COUNT = CELL_COLS * CELL_ROWS

        /** 셀을 도는 stride. `gcd(7, 32) = 1`이라 순열이고, 적은 개수도 화면에 퍼진다. */
        const val CELL_STRIDE = 7

        /** 셀 안쪽 여백(셀 크기 비율). 인접 박스의 스트로크가 붙어 보이지 않게 둔다. */
        const val CELL_INSET = 0.12f

        /**
         * 정점 버퍼를 잡아 두는 **최대 박스 수**. 정적 arm의 최대와 ③ 결과 경로의 상한 중
         * 큰 쪽이다 — 🔴 두 경로가 **같은 버퍼**를 쓰므로 용량은 한 숫자여야 한다.
         */
        val MAX_BOX_COUNT = maxOf(
            RenderArm.HIGHLIGHT_BOX_COUNT_STRESS,
            RenderArm.OVERLAY_BOX_CAP_MEASUREMENT_VALUE,
        )

        /**
         * 정적 더미 박스 중 **앞 몇 개를 `stairs`로 칠하는가.** `highlight_boxes`(4개)가
         * "stairs 1 + person 3"이 되는 규칙이다.
         *
         * ⚠ **사양도 계약도 아니라 우리가 선언한 측정 조건**이다
         * ([RenderArm.HIGHLIGHT_BOX_PROVENANCE]) — 박스 순번은 클래스 인덱스가 아니다.
         */
        const val STATIC_STAIRS_BOXES = 1

        /**
         * 정적 더미 박스 순번 → **어휘 이름**. 🔴 색은 이 이름으로 [OverlayClassColors]에서
         * 온다 — 인덱스로 색을 고르지 않는다는 규약이 정적 경로에도 그대로 적용된다.
         */
        fun staticColorName(boxOrdinal: Int): String =
            if (boxOrdinal < STATIC_STAIRS_BOXES) {
                OverlayClassColors.CLASS_STAIRS
            } else {
                OverlayClassColors.CLASS_PERSON
            }

        /**
         * 검정 밑선. 🔴 **빨강이 없다.** 상류가 금지한 이유: 빨강은 휘도가 낮아 야간 배경에
         * 묻히고 적록색약에서 무너진다(`RESEARCH_20260803_UPSTREAM.md` §5).
         * 본선 색은 [OverlayClassColors]가 소유한다 — 여기에 사본을 두지 않는다.
         */
        private val COLOR_UNDERLINE = OverlayClassColors.UNDERLINE_COLOR

        /**
         * 정점 색과 알파를 그대로 보낸다. 좌표는 이미 NDC이므로 변환이 없다 —
         * 오버레이 비용에 정점 단계가 섞이지 않게 하려는 것이기도 하다.
         *
         * 🔴 `aColor`는 **vec3 그대로**다. vec4로 바꾸면 색의 출처인 [OverlayClassColors]와
         * [OverlaySmoother]의 3-stride 배열을 전부 건드려야 한다([aAlpha]).
         */
        private val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec3 aColor;
            attribute float aAlpha;
            varying vec3 vColor;
            varying float vAlpha;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vColor = aColor;
                vAlpha = aAlpha;
            }
        """.trimIndent()

        /**
         * 단색 + **정점이 나른 알파**. 스트로크는 [STROKE_ALPHA]=1이라 불투명하고, fill만
         * [RenderArm.OVERLAY_FILL_ALPHA_MEASUREMENT_VALUE]로 반투명하다.
         * 🔴 알파는 정점에 실린 **빌드 상수**이며 시간·프레임에 따라 변하지 않는다.
         */
        private val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            varying float vAlpha;
            void main() {
                gl_FragColor = vec4(vColor, vAlpha);
            }
        """.trimIndent()

        /**
         * 색공간 변환 **자동 계수**용. ④ 패스는 [LabGlsl]을 쓰지 않으므로 모든 토큰이 0이어야
         * 하고, 그것이 기계로 확증된다.
         */
        fun shaderSourcesByPass(
            oesVertex: String,
            oesFragment: String,
            blitVertex: String,
            blitFragment: String,
        ): List<Pair<String, List<String>>> = listOf(
            "oes_to_fbo_a" to listOf(oesVertex, oesFragment),
            "stage2_slot_copy" to listOf(blitVertex, blitFragment),
            "stage4_highlight" to listOf(VERTEX_SHADER, FRAGMENT_SHADER),
            "present" to listOf(blitVertex, blitFragment),
        )
    }
}
