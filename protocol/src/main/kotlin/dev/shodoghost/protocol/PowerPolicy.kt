package dev.shodoghost.protocol

/**
 * どの相でどのラジオを持ってよいか。実機の mAh は測っていない。
 * プロセスをまたいで JPEG も手本も残さない。
 */
enum class RadioKind {
    BLE,
    WIFI,
    WAKE_LOCK,
    CAMERA,
}

object PowerPolicy {
    const val PERSISTS_ACROSS_PROCESS_DEATH: Boolean = false

    fun allows(phase: LinkPhase, cameraOpen: Boolean, displayAsleep: Boolean): Set<RadioKind> {
        if (phase == LinkPhase.IDLE) return emptySet()
        val held = mutableSetOf(RadioKind.BLE)
        val transferring = phase == LinkPhase.WIFI_WAKE || phase == LinkPhase.TRANSFER
        if (transferring && !displayAsleep) {
            held += RadioKind.WIFI
            held += RadioKind.WAKE_LOCK
        }
        if (cameraOpen && phase == LinkPhase.BLE_READY && !displayAsleep) {
            held += RadioKind.CAMERA
        }
        return held
    }
}
