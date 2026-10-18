package dev.shodoghost.protocol

data class Outbound(val frames: List<ByteArray>, val nextSequence: Int)

data class InboundMessage(
    val message: LinkMessage,
    val sequence: Int,
    val flags: Int,
    val ackRequested: Boolean,
)

sealed interface Inbound {
    data class Message(val message: InboundMessage) : Inbound
    data object Pending : Inbound
    data class Rejected(val code: ErrorCode, val detail: String) : Inbound
}

object Protocol {
    fun defaultFlags(message: LinkMessage): Int {
        var flags = FrameFlags.FIN
        if (message.type == MessageType.ERROR) flags = flags or FrameFlags.ERROR
        if (message.type == MessageType.CAPTURE_NOW ||
            message.type == MessageType.CALIBRATE_COMMIT ||
            message.type == MessageType.BEGIN_WIFI_SESSION ||
            message.type == MessageType.END_WIFI_SESSION ||
            message.type == MessageType.SET_MODE
        ) {
            flags = flags or FrameFlags.ACK_REQUEST
        }
        return flags
    }

    /**
     * 制御メッセージは1フレーム。筆画パックだけ分割する。
     * 分割時の ACK 対象シーケンスは先頭フラグメント。
     */
    fun encode(message: LinkMessage, sequence: Int, attMtu: Int): Outbound {
        if (attMtu < Limits.MIN_ATT_MTU) {
            throw ProtocolException(ErrorCode.MTU_TOO_SMALL, "att mtu $attMtu")
        }
        val payload = message.encodeBody()
        val maxPayload = FrameCodec.maxPayload(attMtu)
        val flags = defaultFlags(message)
        if (payload.size <= maxPayload) {
            val frame = Frame(message.type, flags, sequence and 0xFFFF, payload)
            return Outbound(listOf(FrameCodec.encode(frame)), FrameCodec.nextSequence(sequence))
        }
        if (message !is StrokePack) {
            throw ProtocolException(ErrorCode.PAYLOAD_TOO_LARGE, message.type.name)
        }
        val chunkRoom = maxPayload - FrameCodec.FRAGMENT_HEADER
        if (chunkRoom < 1) {
            throw ProtocolException(ErrorCode.MTU_TOO_SMALL, "no fragment room")
        }
        val chunks = payload.asList().chunked(chunkRoom).map { it.toByteArray() }
        if (chunks.size > 255) {
            throw ProtocolException(ErrorCode.PAYLOAD_TOO_LARGE, "too many fragments")
        }
        val msgId = sequence and 0xFFFF
        var seq = sequence and 0xFFFF
        val frames = chunks.mapIndexed { index, chunk ->
            var fragFlags = FrameFlags.FRAGMENT
            if (index == 0 && (flags and FrameFlags.ACK_REQUEST) != 0) {
                fragFlags = fragFlags or FrameFlags.ACK_REQUEST
            }
            if (index == chunks.lastIndex) fragFlags = fragFlags or FrameFlags.FIN
            val body = ByteWriter().apply {
                u8(index)
                u8(chunks.size)
                u16(msgId)
                bytes(chunk)
            }.toByteArray()
            val encoded = FrameCodec.encode(Frame(message.type, fragFlags, seq, body))
            seq = FrameCodec.nextSequence(seq)
            encoded
        }
        return Outbound(frames, seq)
    }
}

class InboundAssembler {
    private var partial: Partial? = null

    fun push(bytes: ByteArray): Inbound {
        val decoded = FrameCodec.decode(bytes)
        if (decoded is FrameDecode.Rejected) return Inbound.Rejected(decoded.code, decoded.detail)
        val frame = (decoded as FrameDecode.Ok).frame
        if ((frame.flags and FrameFlags.FRAGMENT) == 0) {
            if (partial != null) {
                partial = null
                return Inbound.Rejected(ErrorCode.FRAGMENT, "interrupted by a full frame")
            }
            return decodeComplete(frame.type, frame.payload, frame.sequence, frame.flags)
        }
        return pushFragment(frame)
    }

    private fun pushFragment(frame: Frame): Inbound {
        if (frame.payload.size < FrameCodec.FRAGMENT_HEADER) {
            partial = null
            return Inbound.Rejected(ErrorCode.FRAGMENT, "short fragment")
        }
        val reader = ByteReader(frame.payload)
        val index = reader.u8()
        val count = reader.u8()
        val msgId = reader.u16()
        val chunk = reader.bytes(reader.remaining())
        if (count == 0 || index >= count) {
            partial = null
            return Inbound.Rejected(ErrorCode.FRAGMENT, "bad index")
        }
        val current = partial
        val partialNow = if (current == null || current.msgId != msgId) {
            Partial(
                type = frame.type,
                msgId = msgId,
                count = count,
                firstSequence = if (index == 0) frame.sequence else -1,
                ackRequested = index == 0 && (frame.flags and FrameFlags.ACK_REQUEST) != 0,
            )
        } else {
            current
        }
        if (index == 0) {
            partialNow.firstSequence = frame.sequence
            partialNow.ackRequested = (frame.flags and FrameFlags.ACK_REQUEST) != 0
        }
        if (partialNow.type != frame.type || partialNow.count != count) {
            partial = null
            return Inbound.Rejected(ErrorCode.FRAGMENT, "fragment header changed")
        }
        if (partialNow.chunks.containsKey(index)) {
            partial = null
            return Inbound.Rejected(ErrorCode.FRAGMENT, "duplicate fragment")
        }
        partialNow.chunks[index] = chunk
        val last = index == count - 1
        val fin = (frame.flags and FrameFlags.FIN) != 0
        if (last != fin) {
            partial = null
            return Inbound.Rejected(ErrorCode.FRAGMENT, "fin flag mismatch")
        }
        if (partialNow.chunks.size < count) {
            partial = partialNow
            return Inbound.Pending
        }
        if (partialNow.firstSequence < 0) {
            partial = null
            return Inbound.Rejected(ErrorCode.FRAGMENT, "missing first fragment")
        }
        partial = null
        val payload = ByteArray(partialNow.chunks.entries.sortedBy { it.key }.sumOf { it.value.size })
        var offset = 0
        for (i in 0 until count) {
            val part = partialNow.chunks[i]
                ?: return Inbound.Rejected(ErrorCode.FRAGMENT, "missing fragment")
            part.copyInto(payload, offset)
            offset += part.size
        }
        var flags = FrameFlags.FIN
        if (partialNow.ackRequested) flags = flags or FrameFlags.ACK_REQUEST
        return decodeComplete(partialNow.type, payload, partialNow.firstSequence, flags)
    }

    private fun decodeComplete(type: MessageType, payload: ByteArray, sequence: Int, flags: Int): Inbound {
        return try {
            val message = MessageCodec.decode(type, payload)
            Inbound.Message(
                InboundMessage(
                    message = message,
                    sequence = sequence,
                    flags = flags,
                    ackRequested = (flags and FrameFlags.ACK_REQUEST) != 0,
                ),
            )
        } catch (ex: ProtocolException) {
            Inbound.Rejected(ex.code, ex.message ?: ex.code.name)
        } catch (ex: IllegalArgumentException) {
            Inbound.Rejected(ErrorCode.BAD_FRAME, ex.message ?: "bad payload")
        }
    }

    private class Partial(
        val type: MessageType,
        val msgId: Int,
        val count: Int,
        var firstSequence: Int,
        var ackRequested: Boolean,
    ) {
        val chunks: MutableMap<Int, ByteArray> = linkedMapOf()
    }
}
