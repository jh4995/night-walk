package com.bammasil.poc.gl

import android.opengl.GLES20
import android.opengl.GLES31
import android.util.Log

/**
 * ② 자리가 **셰이더 하나로 끝나지 않는 arm**의 공통 모양. `analyze → build → apply` 3단이고
 * 각각 `stage_d_analyze_ms` / `stage_d_build_ms` / `stage_d_apply_ms`에 그대로 대응한다
 * (`docs/FRAME_LOG_SCHEMA.md` §2의 슬롯 역할 표).
 *
 * 이 인터페이스가 있는 이유는 하나다 — [PassthroughRenderer]의 5패스 드로우가 arm마다
 * 복붙되면, 나중에 패스 경계 하나를 고칠 때 **한 arm만 고치고 나머지를 놓친다.** GL 호출
 * 시퀀스는 세 arm이 글자 그대로 같아야 계측 조건이 같다.
 *
 * 구현: [DragoStage] · [ClaheStage] · [AgcwdStage].
 *
 * **스레드 규약: 전부 GL 스레드에서만 부른다.**
 */
interface Stage2ComputeStage {

    /** 프로그램·버퍼가 모두 준비됐는가. false면 이 arm은 그릴 수 없다. */
    val ready: Boolean

    /** 왜 준비됐는지/왜 못 됐는지. `session.json`의 `stage2_params.gpu_status`로 나간다. */
    val status: String

    /** `onSurfaceCreated`에서 GL 능력 프로브 직후. 못 쓰면 스스로 꺼지고 이유를 남긴다. */
    fun onContextCreated(capabilities: GlCapabilities?)

    /** 처리 해상도가 정해졌을 때(또는 바뀌었을 때). 해상도를 셰이더에 하드코딩하지 않는 지점. */
    fun onProcessSizeChanged(width: Int, height: Int)

    /** 컨텍스트가 사라졌다. 자원을 전부 반납한다(빠뜨리면 재생성마다 샌다). */
    fun releaseGl()

    /** 패스2 — 입력을 훑어 통계를 만든다. `stage_d_analyze_ms`. */
    fun analyze(textureId: Int, width: Int, height: Int)

    /** 패스3 — 그 통계로 LUT·계수를 만든다. `stage_d_build_ms`. */
    fun build()

    /** 패스4 앞 — build가 쓴 것을 프래그먼트가 볼 수 있게 하고 SSBO를 바인드한다. */
    fun beforeApply()
}

/**
 * 컴퓨트 프로그램 하나를 컴파일·링크한다. 실패하면 0을 돌려주고 **원문 로그를 남긴다** —
 * 링크 실패를 삼키면 arm이 조용히 폴백하고 그 이유를 되물을 수 없다.
 *
 * ⚠ [DragoStage]에는 같은 일을 하는 private 함수가 이미 있다. 그쪽을 이리로 옮기지 않은
 * 이유: `drago`는 실측이 끝난 arm이라 이번 라운드에서 그 파일의 동작 경로를 건드리지 않는다.
 */
internal fun compileComputeProgram(tag: String, label: String, source: String): Int {
    val shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] != GLES20.GL_TRUE) {
        Log.e(tag, "컴퓨트 셰이더 컴파일 실패($label): ${GLES20.glGetShaderInfoLog(shader)}")
        GLES20.glDeleteShader(shader)
        return 0
    }
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, shader)
    GLES20.glLinkProgram(program)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    GLES20.glDeleteShader(shader)
    if (status[0] != GLES20.GL_TRUE) {
        Log.e(tag, "컴퓨트 프로그램 링크 실패($label): ${GLES20.glGetProgramInfoLog(program)}")
        GLES20.glDeleteProgram(program)
        return 0
    }
    return program
}

/**
 * 적용 패스(패스4)의 정점 셰이더. 적용 프래그먼트가 SSBO를 읽어야 해서 `#version 310 es`이고,
 * ESSL은 한 프로그램 안에서 버전을 섞지 못하므로 정점도 310이다. 텍스처 좌표 변환은 패스1에서
 * 끝났으므로 `uTexMatrix`가 없다(다른 2D 패스와 같다).
 *
 * 세 arm이 **같은 문자열**을 쓴다 — 사본을 만들면 정점 단계가 arm마다 달라질 수 있고
 * 그러면 패스 비용을 arm끼리 비교하는 근거가 흔들린다.
 */
internal val ES31_QUAD_VERTEX_SHADER = """
    #version 310 es
    in vec4 aPosition;
    in vec2 aTexCoord;
    out vec2 vTexCoord;
    void main() {
        gl_Position = aPosition;
        vTexCoord = aTexCoord;
    }
""".trimIndent()

/** 이전 GL 호출이 남긴 에러를 비운다. 안 비우면 우리 에러인지 남의 에러인지 모른다. */
internal fun drainStageGlErrors() {
    var guard = 0
    while (GLES20.glGetError() != GLES20.GL_NO_ERROR && guard < 16) {
        guard++
    }
}
