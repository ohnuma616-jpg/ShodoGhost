package dev.shodoghost.protocol

enum class MessageType(val code: Int) {
    HELLO(0x01),
    SET_MODE(0x02),
    EXEMPLAR_META(0x03),
    CALIBRATE_START(0x04),
    CALIBRATE_COMMIT(0x05),
    CAPTURE_NOW(0x06),
    SHOW_ADVICE(0x07),
    CLEAR_OVERLAY(0x08),
    BEGIN_WIFI_SESSION(0x09),
    END_WIFI_SESSION(0x0A),
    PING(0x0B),
    SET_HUD(0x0C),
    SLEEP_HINT(0x0D),
    STROKE_PACK(0x20),
    CORRECTION_PACK(0x21),
    HELLO_ACK(0x81),
    STATE(0x82),
    TOUCH(0x83),
    CAPTURE_READY(0x84),
    ERROR(0x85),
    PONG(0x86),
    ACK(0x87),
    WIFI_READY(0x88),
    WIFI_CLOSED(0x89),
    SESSION_INFO(0x8A),
    ;

    companion object {
        fun fromCode(code: Int): MessageType? = entries.firstOrNull { it.code == code }
    }
}

enum class PeerRole(val code: Int) {
    PHONE_CENTRAL(1),
    GLASS_PERIPHERAL(2),
    ;

    companion object {
        fun fromCode(code: Int): PeerRole? = entries.firstOrNull { it.code == code }
    }
}

enum class Thermal(val code: Int) {
    OK(0),
    WARM(1),
    HOT(2),
    ;

    companion object {
        fun fromCode(code: Int): Thermal? = entries.firstOrNull { it.code == code }
    }
}

enum class CameraState(val code: Int) {
    CLOSED(0),
    OPENING(1),
    OPEN(2),
    ;

    companion object {
        fun fromCode(code: Int): CameraState? = entries.firstOrNull { it.code == code }
    }
}

enum class TouchAction(val code: Int) {
    CLICK(1),
    LONG_PRESS(2),
    DOUBLE_CLICK(3),
    TRIPLE_CLICK(4),
    SLIDE_FORWARD(5),
    SLIDE_BACKWARD(6),
    SLIDE_UP(7),
    SLIDE_DOWN(8),
    ;

    companion object {
        fun fromCode(code: Int): TouchAction? = entries.firstOrNull { it.code == code }
    }
}

/** 練習中のタッチ意味。グラスのジェスチャとスマホのリモコンで同じ。 */
enum class GuideCommand {
    CONFIRM,
    FINISH_AND_CAPTURE,
    BACK,
    CYCLE_MODE,
    NEXT_EXEMPLAR,
    PREV_EXEMPLAR,
    NEXT_PAGE,
    PREV_PAGE,
}

fun TouchAction.toGuideCommand(): GuideCommand = when (this) {
    TouchAction.CLICK -> GuideCommand.CONFIRM
    TouchAction.LONG_PRESS -> GuideCommand.FINISH_AND_CAPTURE
    TouchAction.DOUBLE_CLICK -> GuideCommand.BACK
    TouchAction.TRIPLE_CLICK -> GuideCommand.CYCLE_MODE
    TouchAction.SLIDE_FORWARD -> GuideCommand.NEXT_EXEMPLAR
    TouchAction.SLIDE_BACKWARD -> GuideCommand.PREV_EXEMPLAR
    TouchAction.SLIDE_UP -> GuideCommand.NEXT_PAGE
    TouchAction.SLIDE_DOWN -> GuideCommand.PREV_PAGE
}

enum class CaptureReason(val code: Int) {
    TEMPLE(1),
    PHONE(2),
    PTT(3),
    ;

    companion object {
        fun fromCode(code: Int): CaptureReason? = entries.firstOrNull { it.code == code }
    }
}

enum class CalibMethod(val code: Int) {
    ONE_SHOT(1),
    MANUAL(2),
    SLAM(3),
    ;

    companion object {
        fun fromCode(code: Int): CalibMethod? = entries.firstOrNull { it.code == code }
    }
}

enum class WifiPurpose(val code: Int) {
    UPLOAD_JPEG(1),
    UPLOAD_AND_RESULT(2),
    ;

    companion object {
        fun fromCode(code: Int): WifiPurpose? = entries.firstOrNull { it.code == code }
    }
}

enum class AckStatus(val code: Int) {
    OK(0),
    REJECTED(1),
    BUSY(2),
    ;

    companion object {
        fun fromCode(code: Int): AckStatus? = entries.firstOrNull { it.code == code }
    }
}

enum class LinkPhase(val code: Int) {
    IDLE(0),
    DISCOVERING(1),
    CONNECTING(2),
    NEGOTIATING(3),
    BLE_READY(4),
    WIFI_WAKE(5),
    TRANSFER(6),
    WIFI_TEARDOWN(7),
    BACKOFF(8),
    ;

    companion object {
        fun fromCode(code: Int): LinkPhase? = entries.firstOrNull { it.code == code }
    }
}

object FrameFlags {
    const val ACK_REQUEST: Int = 1 shl 0
    const val FIN: Int = 1 shl 1
    const val ERROR: Int = 1 shl 2
    const val FRAGMENT: Int = 1 shl 3
}
