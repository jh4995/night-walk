package com.bammasil.poc.gl

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ④ 선택적 강조 오버레이 **1패스**. `stage_i_ms`(버짓 I칸)를 낸다
 * ([RenderArm.HIGHLIGHT_BOXES] · [RenderArm.HIGHLIGHT_BOXES_STRESS]).
 *
 * ### 명세는 상류 `scripts/emphasize.py`에서 확정된 것이다
 * `FRAME_BUDGET.md` §3 주5 / `docs/research/RESEARCH_20260803_UPSTREAM.md` §5:
 * **이중 스트로크**(검정 밑선 + 대비색 본선) · **비채움** · `stairs`=노랑 / `person`=시안 ·
 * 🔴 **빨강 금지** · 🔴 **깜빡임 금지** · 두께는 **짧은 변 비례로 720p 기준 4px**.
 * 값은 [RenderArm]의 `HIGHLIGHT_*` 상수에서 오며 이 파일에 숫자를 박지 않는다.
 *
 * ### 왜 전체화면 SDF가 아니라 얇은 사각형 지오메트리인가
 * 프래그먼트 SDF로 그리면 픽셀 셰이더가 **화면 전체**를 돌아 오버레이 비용이 화면 전체
 * 비용으로 부풀고, 그러면 I칸이 다른 물리량이 된다. `FRAME_BUDGET.md` §5는 I칸을 **GL
 * 드로우콜** 계측으로 못 박았고(PoC의 Canvas 오버레이가 "다른 물리량"이라 탈락한 것과 같은
 * 이유다), 스트로크 quad로 그리면 채워지는 픽셀이 실제 테두리 면적뿐이다.
 *
 * ### 박스는 정적·결정적이다
 * 난수를 쓰지 않는다. 깜빡임 금지가 **안전 규약**이고, 난수는 재현성도 깨뜨린다.
 * ⚠ 그러므로 이 arm에 깜빡임이 없다는 사실은 **성능 근거가 아니다** — 더미 박스라 구조적으로
 * 없을 뿐이다. 실제 깜빡임 위험은 ③을 N프레임마다 돌릴 때 생기고(버짓 H칸) **미구현**이다.
 *
 * ⚠ 박스 개수와 배치는 **계약값이 아니라 우리가 선언하는 측정 조건**이다
 * ([RenderArm.HIGHLIGHT_BOX_PROVENANCE]). `session.json`의 `overlay.box_count`에 반드시 실린다.
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

    private var program = 0
    private var aPosition = -1
    private var aColor = -1

    private var processWidth = 0
    private var processHeight = 0

    /** 지금 담고 있는 지오메트리가 몇 개짜리인가. [NO_GEOMETRY]면 아직 만들지 않았다. */
    private var geometryBoxCount = NO_GEOMETRY
    private var geometryWidth = 0
    private var geometryHeight = 0

    private var vertexBuffer: FloatBuffer? = null

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
        if (aPosition < 0 || aColor < 0) {
            GLES20.glDeleteProgram(handle)
            program = 0
            disable(
                "④ 오버레이 정점 속성을 찾지 못했다 " +
                    "(aPosition=$aPosition, aColor=$aColor) — 드라이버가 링크는 했지만 " +
                    "속성을 지웠다. 값을 지어내지 않고 arm을 끈다"
            )
            return
        }
        ready = true
        status =
            "준비 완료 — ④ 오버레이 **1패스**(얇은 사각형 스트로크 quad, glDrawArrays " +
                "${GL_PRIMITIVE_NAME} 1회). 이중 스트로크(검정 밑선 + 대비색 본선) · 비채움 · " +
                "정적 더미 박스(난수 없음). 두께는 **처리 해상도의 짧은 변에서 계산**한다 " +
                "(720p 기준 ${RenderArm.HIGHLIGHT_STROKE_PX_AT_720P}px). " +
                "텍스처를 읽지 않으므로 셰이더는 GLSL ES 1.00이다"
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
        vertexBuffer = null
        vertexCount = 0
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
     * @param boxCount 이 arm이 선언한 박스 수([RenderArm.highlightBoxCount]).
     */
    fun draw(boxCount: Int) {
        ensureGeometry(boxCount)
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
        // 블렌딩·깊이 테스트를 켜지 않는다. 불투명 스트로크이므로 필요 없고, 상태를 켜면
        // 그 상태가 다음 패스(present)까지 남아 다른 arm과 조건이 달라진다.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    /**
     * 정점 데이터를 만든다. **개수나 해상도가 바뀔 때만** 돈다 — 프레임당 할당이 없어야 한다
     * (박스가 정적이라 다시 만들 이유도 없다).
     *
     * 배치: [CELL_COLS] × [CELL_ROWS] 셀 격자에서 **서로 겹치지 않는 같은 크기**의 박스를
     * 뽑는다. 뽑는 순서를 stride [CELL_STRIDE]로 돌려(gcd(7,32)=1이라 순열이다) 개수가 적은
     * arm에서도 화면 전체에 퍼지게 했다. **박스 크기가 arm 간 같으므로** 32개 arm의 값을
     * 4개 arm과 나란히 놓아 개당 비용 기울기로 쓸 수 있다 — 격자를 개수에 맞춰 바꾸면
     * 박스마다 둘레가 달라져 그 기울기가 성립하지 않는다.
     */
    private fun ensureGeometry(boxCount: Int) {
        if (geometryBoxCount == boxCount &&
            geometryWidth == processWidth && geometryHeight == processHeight
        ) {
            return
        }
        val w = processWidth
        val h = processHeight
        if (boxCount <= 0 || w <= 0 || h <= 0) {
            vertexBuffer = null
            vertexCount = 0
            geometryBoxCount = NO_GEOMETRY
            return
        }
        // 픽셀 두께 → NDC. x·y의 픽셀 크기가 다르므로 각각 환산한다(정사각형이 아니다).
        val mainHalfX = strokePx / w
        val mainHalfY = strokePx / h
        val underHalfX = underlinePx / w
        val underHalfY = underlinePx / h

        val verts = boxCount * VERTS_PER_BOX
        val floats = verts * FLOATS_PER_VERTEX
        val buffer = ByteBuffer
            .allocateDirect(floats * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
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
            // index 0만 stairs, 나머지는 person. 4개 arm이 "stairs 1 + person 3"이 되는
            // 규칙이며, 색은 픽셀 비용에 영향이 없다(어느 색이든 같은 면적을 채운다).
            val color = if (i == 0) COLOR_STAIRS else COLOR_PERSON
            // 🔴 **검정 밑선을 먼저** 넣는다. 한 드로우콜 안에서도 프리미티브 순서는 보장되므로
            //    겹치는 부분은 나중에 오는 본선이 덮는다 = 밖으로 검정 테두리가 남는다.
            putRing(buffer, nx0, ny0, nx1, ny1, underHalfX, underHalfY, COLOR_UNDERLINE)
            putRing(buffer, nx0, ny0, nx1, ny1, mainHalfX, mainHalfY, color)
        }
        buffer.position(0)
        vertexBuffer = buffer
        vertexCount = verts
        geometryBoxCount = boxCount
        geometryWidth = w
        geometryHeight = h
    }

    /**
     * 경계선 위에 **가운데를 맞춘** 띠 4개(위·아래·왼·오른). `cv2.rectangle(thickness=t)`가
     * 경계 안쪽으로 t/2, 바깥으로 t/2를 채우는 것과 같은 배치다.
     *
     * **박스 내부를 칠하지 않는다** — 좌·우 띠의 y 범위를 위·아래 띠만큼 줄여 모서리를
     * 두 번 덮지도 않는다(겹치면 색은 같지만 오버드로가 생기고 그만큼 I칸이 부푼다).
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
    ) {
        // 아래 띠 / 위 띠 — 모서리까지 덮는다.
        putQuad(buffer, x0 - halfX, y0 - halfY, x1 + halfX, y0 + halfY, color)
        putQuad(buffer, x0 - halfX, y1 - halfY, x1 + halfX, y1 + halfY, color)
        // 왼 띠 / 오른 띠 — 모서리는 위에서 이미 덮었으므로 그만큼 뺀다.
        // ⚠ 박스 높이가 두께보다 작으면 이 범위가 **뒤집혀** 내부를 칠해 버린다(비채움 위반).
        //   중앙으로 클램프해 그 경우 높이 0이 되게 한다 — 정점 수는 그대로 유지된다
        //   ([VERTS_PER_BOX]가 상수여야 하므로 조건부로 빼지 않는다).
        val midY = 0.5f * (y0 + y1)
        val innerY0 = minOf(y0 + halfY, midY)
        val innerY1 = maxOf(y1 - halfY, midY)
        putQuad(buffer, x0 - halfX, innerY0, x0 + halfX, innerY1, color)
        putQuad(buffer, x1 - halfX, innerY0, x1 + halfX, innerY1, color)
    }

    /** 축 정렬 사각형 하나 = 삼각형 2개 = 정점 6개. */
    private fun putQuad(
        buffer: FloatBuffer,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: FloatArray,
    ) {
        putVertex(buffer, x0, y0, color)
        putVertex(buffer, x1, y0, color)
        putVertex(buffer, x0, y1, color)
        putVertex(buffer, x1, y0, color)
        putVertex(buffer, x1, y1, color)
        putVertex(buffer, x0, y1, color)
    }

    private fun putVertex(buffer: FloatBuffer, x: Float, y: Float, color: FloatArray) {
        buffer.put(x)
        buffer.put(y)
        buffer.put(color[0])
        buffer.put(color[1])
        buffer.put(color[2])
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

        /** x, y, r, g, b. */
        private const val FLOATS_PER_VERTEX = 5
        private const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

        /** 띠 4개 × 삼각형 2개 × 정점 3개 = 24. 이중 스트로크라 박스당 그 두 배다. */
        const val VERTS_PER_RING = 24
        const val VERTS_PER_BOX = VERTS_PER_RING * 2

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
         * 🔴 **빨강이 없다.** 상류가 금지한 이유: 빨강은 휘도가 낮아 야간 배경에 묻히고
         * 적록색약에서 무너진다(`RESEARCH_20260803_UPSTREAM.md` §5).
         */
        private val COLOR_STAIRS = floatArrayOf(1f, 1f, 0f)
        private val COLOR_PERSON = floatArrayOf(0f, 1f, 1f)
        private val COLOR_UNDERLINE = floatArrayOf(0f, 0f, 0f)

        /**
         * 정점 색을 그대로 보낸다. 좌표는 이미 NDC이므로 변환이 없다 —
         * 오버레이 비용에 정점 단계가 섞이지 않게 하려는 것이기도 하다.
         */
        private val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vColor = aColor;
            }
        """.trimIndent()

        /** 불투명 단색. 블렌딩을 켜지 않으므로 알파는 1이다. */
        private val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
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
