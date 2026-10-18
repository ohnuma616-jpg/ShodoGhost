package dev.shodoghost.protocol

class ProtocolException(val code: ErrorCode, message: String) : Exception(message)

enum class ErrorCode(val code: Int) {
    PROTOCOL_MISMATCH(1),
    CRC_FAIL(2),
    BUSY(3),
    THERMAL(4),
    CAMERA_BUSY(5),
    WIFI_TIMEOUT(6),
    TOKEN_REJECTED(7),
    PAYLOAD_TOO_LARGE(8),
    UNSUPPORTED_MODE(9),
    NOT_CALIBRATED(10),
    MTU_TOO_SMALL(11),
    BAD_FRAME(12),
    FRAGMENT(13),
    ;

    companion object {
        fun fromCode(code: Int): ErrorCode? = entries.firstOrNull { it.code == code }
    }
}

class ByteWriter(initial: Int = 64) {
    private var buf = ByteArray(initial)
    private var size = 0

    fun u8(value: Int) {
        ensure(1)
        buf[size++] = (value and 0xFF).toByte()
    }

    fun u16(value: Int) {
        u8(value)
        u8(value ushr 8)
    }

    fun u32(value: Long) {
        val v = value and 0xFFFFFFFFL
        u16(v.toInt())
        u16((v ushr 16).toInt())
    }

    fun i16(value: Int) {
        u16(value)
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buf, size)
        size += value.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var next = buf.size
        while (next < size + extra) next *= 2
        buf = buf.copyOf(next)
    }
}

class ByteReader(private val buf: ByteArray) {
    private var index = 0

    fun remaining(): Int = buf.size - index

    fun u8(): Int {
        need(1)
        return buf[index++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun u32(): Long = (u16().toLong() and 0xFFFFL) or ((u16().toLong() and 0xFFFFL) shl 16)

    fun i16(): Int {
        val v = u16()
        return if (v and 0x8000 != 0) v or -0x10000 else v
    }

    fun bytes(count: Int): ByteArray {
        need(count)
        val out = buf.copyOfRange(index, index + count)
        index += count
        return out
    }

    fun utf8(count: Int): String = bytes(count).decodeToString()

    fun expectExhausted() {
        if (remaining() != 0) {
            throw ProtocolException(ErrorCode.BAD_FRAME, "trailing ${remaining()} bytes")
        }
    }

    private fun need(count: Int) {
        if (remaining() < count) {
            throw ProtocolException(ErrorCode.BAD_FRAME, "need $count bytes, have ${remaining()}")
        }
    }
}
