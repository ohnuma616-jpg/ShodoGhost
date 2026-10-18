package dev.shodoghost.protocol

data class Frame(
    val type: MessageType,
    val flags: Int,
    val sequence: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return type == other.type &&
            flags == other.flags &&
            sequence == other.sequence &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + flags
        result = 31 * result + sequence
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

sealed interface FrameDecode {
    data class Ok(val frame: Frame) : FrameDecode
    data class Rejected(val code: ErrorCode, val detail: String) : FrameDecode
}

object FrameCodec {
    const val SOF: Int = 0xA5
    const val HEADER_SIZE: Int = 8
    const val CRC_SIZE: Int = 2
    const val OVERHEAD: Int = HEADER_SIZE + CRC_SIZE
    const val FRAGMENT_HEADER: Int = 4

    fun maxValueLength(attMtu: Int): Int = attMtu - 3

    fun maxPayload(attMtu: Int): Int = maxValueLength(attMtu) - OVERHEAD

    fun encode(frame: Frame): ByteArray {
        require(frame.sequence in 0..0xFFFF)
        require(frame.payload.size <= 0xFFFF)
        val writer = ByteWriter(OVERHEAD + frame.payload.size)
        writer.u8(SOF)
        writer.u8(Limits.PROTOCOL_VERSION)
        writer.u8(frame.type.code)
        writer.u8(frame.flags)
        writer.u16(frame.sequence)
        writer.u16(frame.payload.size)
        writer.bytes(frame.payload)
        val body = writer.toByteArray()
        val crc = Crc16.ccittFalse(body)
        writer.u16(crc)
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): FrameDecode {
        if (bytes.size < OVERHEAD) {
            return FrameDecode.Rejected(ErrorCode.BAD_FRAME, "shorter than overhead")
        }
        if ((bytes[0].toInt() and 0xFF) != SOF) {
            return FrameDecode.Rejected(ErrorCode.BAD_FRAME, "bad sof")
        }
        val version = bytes[1].toInt() and 0xFF
        if (version != Limits.PROTOCOL_VERSION) {
            return FrameDecode.Rejected(ErrorCode.PROTOCOL_MISMATCH, "version $version")
        }
        val bodyLength = bytes.size - CRC_SIZE
        val expected = Crc16.ccittFalse(bytes, 0, bodyLength)
        val actual = (bytes[bodyLength].toInt() and 0xFF) or
            ((bytes[bodyLength + 1].toInt() and 0xFF) shl 8)
        if (expected != actual) {
            return FrameDecode.Rejected(ErrorCode.CRC_FAIL, "crc")
        }
        val reader = ByteReader(bytes.copyOf(bodyLength))
        reader.u8()
        reader.u8()
        val typeCode = reader.u8()
        val flags = reader.u8()
        val sequence = reader.u16()
        val length = reader.u16()
        if (reader.remaining() != length) {
            return FrameDecode.Rejected(ErrorCode.BAD_FRAME, "length $length")
        }
        val type = MessageType.fromCode(typeCode)
            ?: return FrameDecode.Rejected(ErrorCode.BAD_FRAME, "type $typeCode")
        return FrameDecode.Ok(Frame(type, flags, sequence, reader.bytes(length)))
    }

    fun nextSequence(current: Int): Int = (current + 1) and 0xFFFF
}
