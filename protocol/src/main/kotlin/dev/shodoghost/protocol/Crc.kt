package dev.shodoghost.protocol

object Crc16 {
    /** CRC-16/CCITT-FALSE。多項式 0x1021、初期値 0xFFFF、反転なし。 */
    fun ccittFalse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        val end = offset + length
        for (i in offset until end) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}

object Crc32 {
    /** ISO-HDLC / IEEE CRC-32。JPEG の同一性確認に使う。 */
    fun ieee(data: ByteArray): Long {
        var crc = 0xFFFFFFFF.toInt()
        for (element in data) {
            crc = crc xor (element.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor 0xEDB88320.toInt()
                } else {
                    crc ushr 1
                }
            }
        }
        return (crc xor 0xFFFFFFFF.toInt()).toLong() and 0xFFFFFFFFL
    }
}
