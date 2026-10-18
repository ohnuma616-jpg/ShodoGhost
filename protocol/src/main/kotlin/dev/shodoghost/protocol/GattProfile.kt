package dev.shodoghost.protocol

enum class GattProperty {
    READ,
    WRITE,
    WRITE_NO_RESPONSE,
    NOTIFY,
}

data class GattCharacteristic(
    val name: String,
    val uuid: String,
    val properties: Set<GattProperty>,
)

/**
 * カスタム GATT。標準 Battery Service には依存しない。
 * グラスがペリフェラル、スマホがセントラル。
 */
object GattProfile {
    const val LOCAL_NAME: String = "ShodoGhost"

    const val SERVICE: String = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d10"
    const val COMMAND: String = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d11"
    const val STATUS: String = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d12"
    const val TOUCH: String = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d13"
    const val PAYLOAD: String = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d14"
    const val SESSION_INFO: String = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d15"

    /** Client Characteristic Configuration Descriptor。 */
    const val CCCD: String = "00002902-0000-1000-8000-00805f9b34fb"

    val serviceUuid: String = SERVICE

    val characteristics: List<GattCharacteristic> = listOf(
        GattCharacteristic(
            name = "Command",
            uuid = COMMAND,
            properties = setOf(GattProperty.WRITE),
        ),
        GattCharacteristic(
            name = "Status",
            uuid = STATUS,
            properties = setOf(GattProperty.NOTIFY),
        ),
        GattCharacteristic(
            name = "Touch",
            uuid = TOUCH,
            properties = setOf(GattProperty.WRITE_NO_RESPONSE),
        ),
        GattCharacteristic(
            name = "Payload",
            uuid = PAYLOAD,
            properties = setOf(GattProperty.WRITE, GattProperty.NOTIFY),
        ),
        GattCharacteristic(
            name = "SessionInfo",
            uuid = SESSION_INFO,
            properties = setOf(GattProperty.READ),
        ),
    )

    /** アドバタイズは Flags + 128bit Service UUID。名前はスキャンレスポンス。 */
    const val ADVERTISE_SERVICE_DATA: Boolean = false
}
