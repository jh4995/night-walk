package com.bammasil.poc.gl

import android.opengl.GLES20

/**
 * **우리 앱의 EGL 컨텍스트에서** 실측한 GL 능력. 추측한 값은 하나도 넣지 않는다.
 *
 * `CaptureClockProbe`가 시계 기준에 대해 하는 것과 같은 원칙이다 — 판별이 안 되면 한쪽을
 * 고르지 않고 [GlCapabilitiesProbe.UNKNOWN]으로 남긴다.
 *
 * ⚠ `dumpsys SurfaceFlinger`나 다른 프로세스의 드라이버 문자열은 **근거가 아니다.**
 * 같은 기기라도 컨텍스트 생성 속성이 다르면 확장 목록이 달라진다.
 */
class GlCapabilities(
    /** `glGetString(GL_VERSION)` 원문. 못 얻으면 null. */
    val version: String?,
    val renderer: String?,
    val vendor: String?,
    val shadingLanguageVersion: String?,
    /** `glGetString(GL_EXTENSIONS)` **원문 그대로**. 잘라내거나 정렬하지 않는다. */
    val extensions: String?,
    /** 위 문자열을 공백으로 쪼갠 개수. 0이면 목록을 못 얻은 것이다. */
    val extensionCount: Int,
    /** `"3.2"` 같은 실제 컨텍스트 버전. GL_VERSION 파싱 실패 시 `unknown`. */
    val contextVersion: String,
    /** `"true"` / `"false"` / `"unknown"` */
    val disjointTimerQuery: String,
    /** ES 3.1 이상인가(= 컴퓨트 셰이더 가능). `"true"` / `"false"` / `"unknown"` */
    val computeShaderCapable: String,
    /** `"true"` / `"false"` / `"unknown"` */
    val oesEglImageExternalEssl3: String,
    /** 어떻게 판별했는지. 값만 남기면 나중에 근거를 되물을 수 없다. */
    val note: String,
)

object GlCapabilitiesProbe {

    const val TRUE = "true"
    const val FALSE = "false"
    const val UNKNOWN = "unknown"

    const val EXT_DISJOINT_TIMER_QUERY = "GL_EXT_disjoint_timer_query"
    const val EXT_OES_EGL_IMAGE_EXTERNAL_ESSL3 = "GL_OES_EGL_image_external_essl3"

    /** `"OpenGL ES 3.2 v1.r32p1-..."` 형태에서 버전만 뽑는다. */
    private val VERSION_PATTERN = Regex("""OpenGL ES (\d+)\.(\d+)""")

    /**
     * **GL 스레드에서, 컨텍스트가 현재인 상태로** 1회 부른다(`onSurfaceCreated`).
     * 다른 스레드에서 부르면 전부 null이 돌아온다.
     */
    fun probe(): GlCapabilities {
        val version = GLES20.glGetString(GLES20.GL_VERSION)
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
        val glsl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
        // ES에서는 3.x 컨텍스트에서도 glGetString(GL_EXTENSIONS)가 유효하다(데스크톱 core
        // 프로파일과 다르다). glGetStringi를 쓰지 않는 이유는 API 표면을 넓히지 않기 위해서다
        // — 확실히 도는 쪽을 쓴다. 목록이 비면 unknown으로 남기므로 조용히 틀리지 않는다.
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)

        // ⚠ 부분 문자열 검색을 하지 않는다. "GL_EXT_disjoint_timer_query"는
        //   "GL_EXT_disjoint_timer_query_webgl2" 같은 다른 토큰의 접두사라 오탐이 난다.
        val tokens: Set<String> = extensions
            ?.split(' ', '\t', '\n')
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        val match = version?.let { VERSION_PATTERN.find(it) }
        val major = match?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val minor = match?.groupValues?.get(2)?.toIntOrNull() ?: -1
        val contextVersion = if (major < 0 || minor < 0) UNKNOWN else "$major.$minor"

        val hasList = tokens.isNotEmpty()
        val timerQuery = triState(hasList, tokens.contains(EXT_DISJOINT_TIMER_QUERY))
        val essl3 = triState(hasList, tokens.contains(EXT_OES_EGL_IMAGE_EXTERNAL_ESSL3))
        val compute = when {
            major < 0 -> UNKNOWN
            major > 3 || (major == 3 && minor >= 1) -> TRUE
            else -> FALSE
        }

        val note = buildString {
            append("앱 EGL 컨텍스트에서 onSurfaceCreated 1회 실측. ")
            append("확장 판별은 glGetString(GL_EXTENSIONS)를 공백으로 쪼갠 토큰 완전일치다")
            append("(부분 문자열 검색이 아니다). ")
            if (!hasList) {
                append("⚠ 확장 목록을 얻지 못해 확장 유무는 전부 $UNKNOWN 이다. ")
            }
            if (major < 0) {
                append("⚠ GL_VERSION 문자열을 파싱하지 못해 컨텍스트 버전과 ES3.1 여부가 $UNKNOWN 이다. ")
            }
            append("다른 프로세스(SurfaceFlinger 등)의 드라이버 문자열은 근거로 쓰지 않았다")
        }

        return GlCapabilities(
            version = version,
            renderer = renderer,
            vendor = vendor,
            shadingLanguageVersion = glsl,
            extensions = extensions,
            extensionCount = tokens.size,
            contextVersion = contextVersion,
            disjointTimerQuery = timerQuery,
            computeShaderCapable = compute,
            oesEglImageExternalEssl3 = essl3,
            note = note,
        )
    }

    /** 목록 자체를 못 얻었으면 "없다"가 아니라 "모른다"다. 둘을 섞지 않는다. */
    private fun triState(hasList: Boolean, present: Boolean): String = when {
        !hasList -> UNKNOWN
        present -> TRUE
        else -> FALSE
    }
}
