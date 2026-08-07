package com.bammasil.poc.detect

import android.os.SystemClock
import android.util.Log
import com.bammasil.poc.gl.OverlayClassColors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ③ 탐지 결과 하나를 GL 스레드로 넘기는 **불변 스냅샷**.
 *
 * 🔴 **박스 배열은 복사본이다.** [DetectPostprocessor]의 내부 배열은 다음 프레임이 덮어쓰므로
 * 참조를 넘기면 GL 스레드가 반쯤 덮인 값을 읽는다.
 *
 * 🔴 **생성 뒤에는 아무도 쓰지 않는다.** 그래서 `AtomicReference` 교체 하나로 발행이 끝나고
 * (참조 쓰기의 happens-before가 배열 원소까지 덮는다) GL 스레드는 락 없이 읽는다.
 */
class DetectOverlaySnapshot(
    /**
     * 🔴 **게시 시각.** `SystemClock.elapsedRealtimeNanos()` = `CLOCK_BOOTTIME`이며
     * `frames.csv`의 `t_recv_ns`·`t_render_start_ns`와 **같은 시계**다 —
     * 그래서 `t_overlay_source_ns` 열로 그대로 나가고 PC가 신선도를 뺄 수 있다.
     */
    val publishedNs: Long,
    /** 유효 박스 수. **0은 정상값이다**(탐지가 아무것도 못 찾은 게시). */
    val count: Int,
    /** `x1,y1,x2,y2` × [count]. 🔴 **① 센서 좌표계**다(규약 §5-2). */
    val boxes: FloatArray,
    /**
     * `r,g,b` × [count]. 🔴 **색은 게시 시점에 이름으로 정해진다**([OverlayClassColors]) —
     * GL 스레드는 클래스도 이름도 보지 않는다. 인덱스로 고르는 코드가 남을 자리를 없앤 것이다.
     */
    val colors: FloatArray,
    /** 그 박스의 `cls` 인덱스 × [count]. 진단용이며 색 결정에는 쓰지 않는다. */
    val classIds: IntArray,
    /** ① 센서 공간의 치수 = **분석 use case가 실제로 준 `ImageProxy`의 치수**. */
    val srcW: Int,
    val srcH: Int,
    /** 이 게시의 **소스 사실**. 좌표를 나중에 되짚을 때 필요하다. */
    val rotationDegrees: Int,
    val rotationApplied: Boolean,
    /** 그 프레임의 letterbox 패딩(진단용). `align`은 여전히 가정이다(§A-2가 ☐). */
    val letterboxPadX: Int,
    val letterboxPadY: Int,
    /** 이 게시에서 **세고 그리지 않은** 역전 박스 수(규약 §5-3의 소비자 쪽 처리). */
    val rejectedInverted: Int,
    /** 상한을 넘어 **세고 버린** 박스 수. 조용히 버리지 않는다. */
    val droppedOverCap: Int,
)

/**
 * ③(탐지 워커) → ④(GL 스레드) **게시자**. `AtomicReference` 교체 하나가 발행의 전부다.
 *
 * ## 어디서 부르는가 — 🔴 E·F·G 구간 **밖**이다
 *
 * [publish]는 `DetectPipeline.infer()`에서 **`gNs`가 확정되고 `recorder.record`가 끝난 뒤**,
 * `parity.write`와 **같은 자리**에서 불린다. 계측 구간 안에 넣으면 승격된 F 실측 5건과의
 * 비교가 끊긴다 — `parity` 덤프를 그 자리에 둔 것과 글자 그대로 같은 논거다.
 *
 * ## 할당
 *
 * 🔴 **게시당 객체를 만든다**(스냅샷 1개 + 배열 3개). 게시는 실측 약 3.4Hz이고 **G 구간
 * 밖**이므로 허용하며, 그 사실은 `session.json`의 `overlay.publish`에 적는다.
 * 🔴 **GL 스레드 쪽에는 프레임당 할당이 없다** — [latest]는 참조 읽기 하나다.
 *
 * ## arm 게이트
 *
 * 오버레이 arm이 아니면 [enabled]가 false이고 **게시자가 아무 일도 하지 않는다.** 그래야
 * `detect_cpu`의 경로가 바이트 단위로 유지된다(`DetectParityDumper`의 게이트 관행 그대로).
 * 호출자도 [enabled]를 보고 **박스 복사 자체를 건너뛴다.**
 *
 * ## 스레드
 *
 * [publish]는 탐지 워커 스레드 전용. [latest]는 GL 스레드. 통계 게터는 **정지 뒤
 * (A12 (2) quiesce 다음)** 읽는다.
 */
class DetectOverlayPublisher(
    /** 한 게시에 실을 수 있는 박스 수의 상한. 초과분은 **세고 버린다**. */
    private val boxCap: Int,
) {

    /** 🔴 발행의 전부다. 워커가 교체하고 GL 스레드가 읽는다. */
    private val slot = AtomicReference<DetectOverlaySnapshot?>(null)

    /**
     * 이 런이 오버레이 arm인가. false면 [publish]가 즉시 반환한다.
     * 🔴 **호출자도 이 값을 보고 박스 복사를 건너뛴다** — 그래야 비오버레이 arm의 워커 경로에
     * 늘어나는 것이 volatile 읽기 하나뿐이다.
     */
    @Volatile
    var enabled = false
        private set

    /**
     * 모델 임베드 메타에서 읽은 클래스 이름(인덱스 순). 🔴 **색의 유일한 출처다** —
     * 계약 문서(`INTERFACES.md` A-4)의 순서를 쓰지 않는다.
     */
    @Volatile
    private var classNames: List<String> = emptyList()

    // ── 런 통계 (워커가 올리고 정지 뒤 GL 스레드가 읽는다) ──────────────────

    val publishCount = AtomicLong(0)
    val boxesPublished = AtomicLong(0)

    /**
     * 🔴 **세고 그리지 않은** 역전 박스의 런 총계. `detect.run.inverted_boxes`(후처리가 센 값)와
     * **교차 대조한다** — 두 수가 다르면 어느 단계가 역전을 만들었는지가 갈린다.
     */
    val rejectedInverted = AtomicLong(0)

    /** 상한 초과로 버린 박스의 런 총계. 조용히 버리지 않는다는 규약의 실체다. */
    val droppedOverCap = AtomicLong(0)

    /**
     * 그린 박스를 **정규화된 이름**으로 센 값. 어휘 밖 이름과 `cls` 범위 밖도 여기 들어온다
     * (키가 `<cls N: ...>` 꼴이면 범위 밖이다).
     *
     * ⚠ `ArrayList`가 아니라 `ConcurrentHashMap`이다 — quiesce가 타임아웃하면 워커가 아직
     * 쓰는 중일 수 있고, 그때 살아 있는 `ArrayList`를 훑으면 `session.json`이 통째로 날아간다
     * (`DetectPostprocessor.invertedSampleSlots`가 고정 배열인 것과 같은 논거).
     */
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    /** 이 런에서 실제로 관측한 **어휘 밖** 이름들(정규화 후). 로그·`session.json`에 나간다. */
    private val unknownNames = ConcurrentHashMap<String, AtomicLong>()

    // ── 런 수명 ───────────────────────────────────────────────────────────

    /**
     * 런을 시작한다. **UI 스레드에서, `DetectPipeline.start`가 부른다.**
     *
     * 🔴 **런 단위 상태를 여기서 전부 내린다.** 안 내리면 직전 런의 게시가 이 런의 첫
     * 프레임에 그려지고 `t_overlay_source_ns`가 **남의 런 시각**이 된다
     * (`lastLetterbox` 누수와 같은 실패 양식이다).
     */
    fun start(classNames: List<String>) {
        reset()
        this.classNames = classNames
        enabled = true
    }

    /** 오버레이 arm이 아니다. 게시자를 끄고 이전 런의 상태를 내린다. */
    fun disable() {
        enabled = false
        reset()
    }

    private fun reset() {
        slot.set(null)
        classNames = emptyList()
        publishCount.set(0)
        boxesPublished.set(0)
        rejectedInverted.set(0)
        droppedOverCap.set(0)
        counts.clear()
        unknownNames.clear()
    }

    // ── GL 스레드 ─────────────────────────────────────────────────────────

    /**
     * 지금 게시돼 있는 스냅샷. 아직 아무 추론도 끝나지 않았으면 null.
     * 🔴 **참조 읽기 하나다 — 할당이 없다.**
     */
    fun latest(): DetectOverlaySnapshot? = slot.get()

    // ── 탐지 워커 스레드 ──────────────────────────────────────────────────

    /**
     * 스냅샷을 만들어 교체한다.
     *
     * 🔴 **역전 박스(`x2<x1` / `y2<y1`)는 세고 그리지 않는다**(규약 §5-3). 후처리는 **거르지
     * 않고 세기만** 하므로 그 박스가 여기까지 온다. 그리면 면적이 0이거나 음수인 사각형이
     * 화면 가장자리에 얇은 선으로 남는다 — 사용자에게 보이는 쓰레기다.
     * 🔴 **클램프하지 않는다.** 프레임 밖 좌표는 그대로 넘기고 GL 뷰포트가 자른다 —
     * 후처리에서 클램프를 없앤 이유가 바로 "면적 0 박스"였고, 여기서 되살리면 같은 결함이다.
     *
     * @param boxes [DetectPostprocessor.copyBoxes]가 낸 **그 추론의 복사본**.
     * @param rotation 그 프레임에 실제로 쓴 회전 기하. `srcW`/`srcH`가 곧 센서 공간의 치수다.
     */
    fun publish(
        boxes: List<DetectPostprocessor.Box>,
        rotation: DetectContract.Rotation,
        rotationApplied: Boolean,
        letterbox: DetectContract.Letterbox?,
    ) {
        if (!enabled) return
        val names = classNames
        val cap = if (boxes.size < boxCap) boxes.size else boxCap
        val coords = FloatArray(4 * cap)
        val colorBuf = FloatArray(3 * cap)
        val ids = IntArray(cap)

        var kept = 0
        var inverted = 0
        var dropped = 0
        for (b in boxes) {
            if (b.x2 < b.x1 || b.y2 < b.y1) {
                inverted++
                continue
            }
            if (kept >= cap) {
                dropped++
                continue
            }
            // 🔴 색은 **이름**으로 고른다. cls가 모델 이름 목록 밖이면 이름을 지어내지 않고
            //    범위 밖 키로 세며, 색은 중립색이 된다(OverlayClassColors.UNKNOWN_POLICY).
            val raw = names.getOrNull(b.cls)
            val key = if (raw == null) {
                OverlayClassColors.outOfRangeKey(b.cls)
            } else {
                OverlayClassColors.normalize(raw)
            }
            val color = OverlayClassColors.colorFor(key)
            val c4 = kept * 4
            coords[c4] = b.x1
            coords[c4 + 1] = b.y1
            coords[c4 + 2] = b.x2
            coords[c4 + 3] = b.y2
            val c3 = kept * 3
            colorBuf[c3] = color[0]
            colorBuf[c3 + 1] = color[1]
            colorBuf[c3 + 2] = color[2]
            ids[kept] = b.cls
            kept++
            bump(counts, key)
            if (!OverlayClassColors.isKnown(key)) {
                val seen = bump(unknownNames, key)
                // 🔴 처음 볼 때 한 번만 로그로도 낸다 — 현장에서 흰 테두리가 보이면
                //    무슨 이름 때문인지 바로 알아야 한다.
                if (seen == 1L) {
                    Log.w(
                        TAG,
                        "④ 오버레이 어휘 밖 클래스: \"$key\" (cls=${b.cls}) → " +
                            "${OverlayClassColors.UNKNOWN_NAME_COLOR_TEXT}으로 그린다"
                    )
                }
            }
        }

        // 🔴 **시각은 교체 직전에 찍는다.** 그래야 이 값이 "GL 스레드가 볼 수 있게 된 시각"의
        //    상한이 되고, 어떤 프레임도 자기 t_render_start_ns보다 미래의 결과를 쓰지 않는다
        //    (GL 스레드는 t_render_start_ns를 찍기 **전에** latest()를 읽는다).
        val publishedNs = SystemClock.elapsedRealtimeNanos()
        slot.set(
            DetectOverlaySnapshot(
                publishedNs = publishedNs,
                count = kept,
                boxes = coords,
                colors = colorBuf,
                classIds = ids,
                srcW = rotation.srcW,
                srcH = rotation.srcH,
                rotationDegrees = rotation.degrees,
                rotationApplied = rotationApplied,
                letterboxPadX = letterbox?.padX ?: -1,
                letterboxPadY = letterbox?.padY ?: -1,
                rejectedInverted = inverted,
                droppedOverCap = dropped,
            )
        )
        publishCount.incrementAndGet()
        boxesPublished.addAndGet(kept.toLong())
        if (inverted > 0) rejectedInverted.addAndGet(inverted.toLong())
        if (dropped > 0) droppedOverCap.addAndGet(dropped.toLong())
    }

    private fun bump(map: ConcurrentHashMap<String, AtomicLong>, key: String): Long =
        map.getOrPut(key) { AtomicLong(0) }.incrementAndGet()

    // ── 정지 뒤 (GL 스레드) ───────────────────────────────────────────────

    /** 그린 박스의 **정규화 이름별** 개수. 스냅샷을 뜬다(런당 한 번 불린다). */
    fun countsByClass(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((k, v) in counts) out[k] = v.get()
        return out
    }

    /** 이 런에서 관측한 **어휘 밖** 이름들. 비어 있으면 전부 어휘 안이었다. */
    fun unknownNamesSeen(): List<String> = unknownNames.keys.toList().sorted()

    /** 색의 출처가 된 클래스 이름 목록(모델 임베드 메타). 게시자가 꺼져 있으면 비어 있다. */
    fun classNamesUsed(): List<String> = classNames

    /**
     * 이 런의 게시 사실을 **불변 스냅샷**으로 뜬다. `session.json`의 `overlay` 블록이 쓴다.
     *
     * 🔴 **A12 (2) quiesce 뒤에 부른다** — 그 앞에서 부르면 마지막 추론의 게시가 빠진다.
     * ⚠ [disable]이 상태를 내리므로 **런을 다시 시작하기 전에** 떠야 한다.
     */
    fun facts(): DetectOverlayPublishFacts = DetectOverlayPublishFacts(
        enabled = enabled,
        publishCount = publishCount.get(),
        boxesPublished = boxesPublished.get(),
        rejectedInverted = rejectedInverted.get(),
        droppedOverCap = droppedOverCap.get(),
        countsByClass = countsByClass(),
        unknownNamesSeen = unknownNamesSeen(),
        classNamesUsed = classNamesUsed(),
    )

    private companion object {
        const val TAG = "BammasilOverlayPub"
    }
}

/**
 * ③ → ④ 게시의 **런 사실**. `session.json`의 `overlay` 블록으로 나간다.
 *
 * 🔴 [rejectedInverted]는 **거른 개수가 아니라 센 개수**이며(규약 §5-3) 후처리가 센
 * `detect.run.inverted_boxes`와 **교차 대조한다** — 두 수가 다르면 어느 단계가 역전을
 * 만들었는지가 갈린다.
 */
class DetectOverlayPublishFacts(
    val enabled: Boolean,
    val publishCount: Long,
    val boxesPublished: Long,
    val rejectedInverted: Long,
    val droppedOverCap: Long,
    /** 그린 박스를 **정규화된 이름**으로 센 값. 어휘 밖·범위 밖 키도 여기 들어 있다. */
    val countsByClass: Map<String, Long>,
    /** 이 런에서 관측한 **어휘 밖** 이름들. 비어 있으면 전부 어휘 안이었다. */
    val unknownNamesSeen: List<String>,
    /** 색의 출처가 된 클래스 이름 목록(모델 임베드 메타의 `names`). */
    val classNamesUsed: List<String>,
)
