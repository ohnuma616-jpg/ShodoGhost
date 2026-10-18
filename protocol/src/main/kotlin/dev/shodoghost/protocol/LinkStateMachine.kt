package dev.shodoghost.protocol

enum class LinkSignal {
    START,
    PEER_FOUND,
    GATT_CONNECTED,
    HELLO_OK,
    HELLO_REJECTED,
    BEGIN_WIFI,
    SOCKET_UP,
    TRANSFER_FINISHED,
    WIFI_TORN_DOWN,
    LINK_LOST,
    TIMEOUT,
    RETRY,
    STOP,
}

enum class LinkEffect {
    OPEN_DISCOVERY,
    CLOSE_DISCOVERY,
    REQUEST_MTU_AND_HELLO,
    ACQUIRE_RADIOS,
    OPEN_WIFI_SOCKET,
    CLOSE_WIFI_SOCKET,
    RELEASE_RADIOS,
    SCHEDULE_RETRY,
    CANCEL_RETRY,
}

data class LinkStep(
    val phase: LinkPhase,
    val accepted: Boolean,
    val effects: List<LinkEffect>,
)

/**
 * BLE 常時、Wi-Fi はバーストだけ。副作用の順序は [LinkStep.effects] の並び。
 * スリープで Wi-Fi ソケットが落ちた場合は [LinkSignal.LINK_LOST] ではなく
 * [LinkSignal.TIMEOUT] を WIFI_WAKE / TRANSFER に入れる。BLE が生きていれば BLE_READY に戻る。
 */
object RetrySchedule {
    private val delaysMs = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

    fun delayMs(attempt: Int): Long {
        require(attempt >= 0)
        return delaysMs[attempt.coerceAtMost(delaysMs.lastIndex)]
    }
}

object LinkStateMachine {
    fun step(phase: LinkPhase, signal: LinkSignal): LinkStep {
        val next = accept(phase, signal)
        if (next == null) return LinkStep(phase, accepted = false, effects = emptyList())
        return LinkStep(next.first, accepted = true, effects = next.second)
    }

    private fun accept(phase: LinkPhase, signal: LinkSignal): Pair<LinkPhase, List<LinkEffect>>? {
        return when (phase) {
            LinkPhase.IDLE -> when (signal) {
                LinkSignal.START -> LinkPhase.DISCOVERING to listOf(LinkEffect.OPEN_DISCOVERY)
                else -> null
            }
            LinkPhase.DISCOVERING -> when (signal) {
                LinkSignal.PEER_FOUND -> LinkPhase.CONNECTING to listOf(LinkEffect.CLOSE_DISCOVERY)
                LinkSignal.STOP -> LinkPhase.IDLE to listOf(LinkEffect.CLOSE_DISCOVERY)
                LinkSignal.LINK_LOST, LinkSignal.TIMEOUT ->
                    LinkPhase.BACKOFF to listOf(LinkEffect.CLOSE_DISCOVERY, LinkEffect.SCHEDULE_RETRY)
                else -> null
            }
            LinkPhase.CONNECTING -> when (signal) {
                LinkSignal.GATT_CONNECTED ->
                    LinkPhase.NEGOTIATING to listOf(LinkEffect.REQUEST_MTU_AND_HELLO)
                LinkSignal.STOP -> LinkPhase.IDLE to listOf(LinkEffect.CLOSE_DISCOVERY)
                LinkSignal.LINK_LOST, LinkSignal.TIMEOUT ->
                    LinkPhase.BACKOFF to listOf(LinkEffect.SCHEDULE_RETRY)
                else -> null
            }
            LinkPhase.NEGOTIATING -> when (signal) {
                LinkSignal.HELLO_OK -> LinkPhase.BLE_READY to emptyList()
                LinkSignal.HELLO_REJECTED, LinkSignal.TIMEOUT, LinkSignal.LINK_LOST ->
                    LinkPhase.BACKOFF to listOf(LinkEffect.SCHEDULE_RETRY)
                LinkSignal.STOP -> LinkPhase.IDLE to emptyList()
                else -> null
            }
            LinkPhase.BLE_READY -> when (signal) {
                LinkSignal.BEGIN_WIFI ->
                    LinkPhase.WIFI_WAKE to listOf(LinkEffect.ACQUIRE_RADIOS, LinkEffect.OPEN_WIFI_SOCKET)
                LinkSignal.LINK_LOST ->
                    LinkPhase.BACKOFF to listOf(LinkEffect.SCHEDULE_RETRY)
                LinkSignal.STOP -> LinkPhase.IDLE to emptyList()
                else -> null
            }
            LinkPhase.WIFI_WAKE -> when (signal) {
                LinkSignal.SOCKET_UP -> LinkPhase.TRANSFER to emptyList()
                LinkSignal.TIMEOUT ->
                    LinkPhase.BLE_READY to listOf(LinkEffect.CLOSE_WIFI_SOCKET, LinkEffect.RELEASE_RADIOS)
                LinkSignal.LINK_LOST ->
                    LinkPhase.BACKOFF to listOf(
                        LinkEffect.CLOSE_WIFI_SOCKET,
                        LinkEffect.RELEASE_RADIOS,
                        LinkEffect.SCHEDULE_RETRY,
                    )
                LinkSignal.STOP ->
                    LinkPhase.WIFI_TEARDOWN to listOf(LinkEffect.CLOSE_WIFI_SOCKET, LinkEffect.RELEASE_RADIOS)
                else -> null
            }
            LinkPhase.TRANSFER -> when (signal) {
                LinkSignal.TRANSFER_FINISHED, LinkSignal.TIMEOUT, LinkSignal.STOP ->
                    LinkPhase.WIFI_TEARDOWN to listOf(LinkEffect.CLOSE_WIFI_SOCKET, LinkEffect.RELEASE_RADIOS)
                LinkSignal.LINK_LOST ->
                    LinkPhase.BACKOFF to listOf(
                        LinkEffect.CLOSE_WIFI_SOCKET,
                        LinkEffect.RELEASE_RADIOS,
                        LinkEffect.SCHEDULE_RETRY,
                    )
                else -> null
            }
            LinkPhase.WIFI_TEARDOWN -> when (signal) {
                LinkSignal.WIFI_TORN_DOWN, LinkSignal.TIMEOUT -> LinkPhase.BLE_READY to emptyList()
                LinkSignal.LINK_LOST ->
                    LinkPhase.BACKOFF to listOf(LinkEffect.SCHEDULE_RETRY)
                else -> null
            }
            LinkPhase.BACKOFF -> when (signal) {
                LinkSignal.RETRY ->
                    LinkPhase.DISCOVERING to listOf(LinkEffect.CANCEL_RETRY, LinkEffect.OPEN_DISCOVERY)
                LinkSignal.STOP ->
                    LinkPhase.IDLE to listOf(LinkEffect.CANCEL_RETRY, LinkEffect.CLOSE_DISCOVERY)
                else -> null
            }
        }
    }
}
