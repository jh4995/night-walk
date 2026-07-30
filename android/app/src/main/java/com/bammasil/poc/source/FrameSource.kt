package com.bammasil.poc.source

import android.view.Surface

/**
 * 프레임 공급원 추상화.
 *
 * 지금 구현체는 [CameraFrameSource] 하나뿐인데도 첫날에 인터페이스를 박는 이유:
 * 나중에 영상 파일 디코더나 화면 캡처를 입력으로 끼울 때(`docs/STATUS.md`) 소스가
 * 카메라에 직결돼 있으면 렌더·로깅 경로를 통째로 뜯어야 한다.
 *
 * ⚠ 이번 PoC는 **카메라 구현체만** 만든다. 디코더·화면캡처는 자리만 비워 둔다 —
 * 쓰지 않을 구현을 미리 짜면 검증되지 않은 코드가 저장소에 남는다.
 */
interface FrameSource {

    /** `session.json`에 남는 소스 종류. */
    val kind: String

    /** 소스가 **실제로** 물어온 조건. 시작 전에는 null. */
    val negotiated: NegotiatedConfig?

    /**
     * 프레임 공급을 시작한다.
     *
     * [target]이 Surface를 내주지 못하면(GL 미준비) 소스는 프레임을 흘리지 않고 [onError]로
     * 알린다 — 거짓 Surface를 만들어 "성공한 척" 도는 경로를 만들지 않는다.
     */
    fun start(request: FrameRequest, target: FrameTarget, onError: (String) -> Unit)

    fun stop()
}

/** 소스에 **요청하는** 조건. 실제로 받는 값과 다를 수 있다 → [NegotiatedConfig]. */
data class FrameRequest(val width: Int, val height: Int, val fps: Int)

/**
 * 소스가 실제로 물어온 조건. 요청값만 기록하면 측정 조건이 거짓말을 하므로
 * `session.json`에 요청값과 **따로** 싣는다.
 */
data class NegotiatedConfig(
    val width: Int,
    val height: Int,
    /** 소스가 알려준 프레임레이트 범위 문자열. 모르면 **null** — 0이나 요청값으로 채우지 않는다. */
    val frameRateRange: String?,
)

/**
 * 소스가 프레임을 얹을 대상. GL 렌더러가 구현한다.
 * 콜백은 **메인 스레드**에서 불린다(GL 스레드가 아니다).
 */
interface FrameTarget {

    /** 프레임을 받을 Surface. GL 텍스처가 아직 없으면 **null**을 돌려준다. */
    fun acquireSurface(width: Int, height: Int): Surface?

    /** 소스가 Surface 사용을 끝냈을 때. [resultCode]는 소스별 결과 코드(진단용). */
    fun releaseSurface(surface: Surface, resultCode: Int)
}
