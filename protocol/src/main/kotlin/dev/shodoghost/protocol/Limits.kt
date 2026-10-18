package dev.shodoghost.protocol

/**
 * グラスとスマホが共有する数値契約。
 * 制御メッセージは [MIN_ATT_MTU] の1フレームに収まること。
 * それを超えるのは筆画パックだけで、トランスポート分割の対象になる。
 */
object Limits {
    const val PROTOCOL_VERSION: Int = 1

    /** これ未満の ATT MTU では HELLO を送らず、リンクを張り直す。 */
    const val MIN_ATT_MTU: Int = 185
    const val PREFERRED_ATT_MTU: Int = 247

    const val MIN_LINE_PX: Int = 2
    const val MAX_LINE_PX: Int = 8
    const val NORM_MAX: Int = 10_000

    const val MAX_POINTS_PER_STROKE: Int = 400
    const val MAX_STROKES_PER_PACK: Int = 32
    const val MAX_ADVICE_BYTES: Int = 42
    const val MAX_ADVICE_LINES: Int = 2
    const val MAX_ERROR_BYTES: Int = 48

    const val NONCE_BYTES: Int = 8
    const val WIFI_TOKEN_BYTES: Int = 16

    /** 保留できる未送信 JPEG は1枚。熱と 32GB とは別の、セッション中メモリの上限。 */
    const val MAX_PENDING_JPEG: Int = 1
    const val MAX_JPEG_BYTES: Int = 4 * 1024 * 1024
    const val WIFI_HARD_TIMEOUT_MS: Long = 20_000L
    const val WIFI_CHUNK_BYTES: Int = 16 * 1024
}

object HwFlags {
    const val MAIN_CAMERA: Int = 1 shl 0
    const val WIFI: Int = 1 shl 1
    const val SLAM_ADAPTER: Int = 1 shl 2
    const val VGA_SPATIAL: Int = 1 shl 3
}

object OverlayBits {
    const val EXEMPLAR: Int = 1 shl 0
    const val CORRECTION: Int = 1 shl 1
    const val ADVICE: Int = 1 shl 2
    const val CROSSHAIR: Int = 1 shl 3
    const val ALL: Int = 0xFF
}
