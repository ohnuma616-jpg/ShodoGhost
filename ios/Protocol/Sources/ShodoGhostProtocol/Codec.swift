public struct Frame: Equatable, Sendable {
    public let type: MessageType
    public let flags: Int
    public let sequence: Int
    public let payload: [UInt8]

    public init(type: MessageType, flags: Int, sequence: Int, payload: [UInt8]) {
        self.type = type
        self.flags = flags
        self.sequence = sequence
        self.payload = payload
    }
}

public enum FrameDecode: Equatable, Sendable {
    case ok(Frame)
    case rejected(ErrorCode, String)
}

public enum FrameCodec {
    public static let sof = 0xA5
    public static let headerSize = 8
    public static let crcSize = 2
    public static let overhead = headerSize + crcSize
    public static let fragmentHeader = 4

    public static func maxValueLength(attMtu: Int) -> Int { attMtu - 3 }

    public static func maxPayload(attMtu: Int) -> Int { maxValueLength(attMtu: attMtu) - overhead }

    public static func encode(_ frame: Frame) -> [UInt8] {
        precondition((0...0xFFFF).contains(frame.sequence))
        precondition(frame.payload.count <= 0xFFFF)
        var writer = ByteWriter()
        writer.u8(sof)
        writer.u8(Limits.protocolVersion)
        writer.u8(frame.type.rawValue)
        writer.u8(frame.flags)
        writer.u16(frame.sequence)
        writer.u16(frame.payload.count)
        writer.bytes(frame.payload)
        let body = writer.toArray()
        let crc = Crc16.ccittFalse(body)
        writer.u16(crc)
        return writer.toArray()
    }

    public static func decode(_ bytes: [UInt8]) -> FrameDecode {
        if bytes.count < overhead {
            return .rejected(.badFrame, "shorter than overhead")
        }
        if Int(bytes[0]) != sof {
            return .rejected(.badFrame, "bad sof")
        }
        let version = Int(bytes[1])
        if version != Limits.protocolVersion {
            return .rejected(.protocolMismatch, "version \(version)")
        }
        let bodyLength = bytes.count - crcSize
        let expected = Crc16.ccittFalse(bytes, offset: 0, length: bodyLength)
        let actual = Int(bytes[bodyLength]) | (Int(bytes[bodyLength + 1]) << 8)
        if expected != actual {
            return .rejected(.crcFail, "crc")
        }
        var reader = ByteReader(Array(bytes[0..<bodyLength]))
        do {
            _ = try reader.u8()
            _ = try reader.u8()
            let typeCode = try reader.u8()
            let flags = try reader.u8()
            let sequence = try reader.u16()
            let length = try reader.u16()
            if reader.remaining() != length {
                return .rejected(.badFrame, "length \(length)")
            }
            guard let type = MessageType(rawValue: typeCode) else {
                return .rejected(.badFrame, "type \(typeCode)")
            }
            return .ok(Frame(type: type, flags: flags, sequence: sequence, payload: try reader.bytes(length)))
        } catch {
            return .rejected(.badFrame, "header")
        }
    }

    public static func nextSequence(_ current: Int) -> Int { (current + 1) & 0xFFFF }
}

public struct Outbound: Equatable, Sendable {
    public let frames: [[UInt8]]
    public let nextSequence: Int
}

public struct InboundMessage: Equatable, Sendable {
    public let message: LinkMessage
    public let sequence: Int
    public let flags: Int
    public let ackRequested: Bool
}

public enum Inbound: Equatable, Sendable {
    case message(InboundMessage)
    case pending
    case rejected(ErrorCode, String)
}

public enum Protocol {
    public static func defaultFlags(_ message: LinkMessage) -> Int {
        var flags = FrameFlags.fin
        if message.type == .error { flags |= FrameFlags.error }
        switch message.type {
        case .captureNow, .calibrateCommit, .beginWifiSession, .endWifiSession, .setMode:
            flags |= FrameFlags.ackRequest
        default:
            break
        }
        return flags
    }

    public static func encode(_ message: LinkMessage, sequence: Int, attMtu: Int) throws -> Outbound {
        if attMtu < Limits.minAttMtu {
            throw ProtocolError(.mtuTooSmall, "att mtu \(attMtu)")
        }
        let payload = message.encodeBody()
        let maxPayload = FrameCodec.maxPayload(attMtu: attMtu)
        let flags = defaultFlags(message)
        if payload.count <= maxPayload {
            let frame = Frame(type: message.type, flags: flags, sequence: sequence & 0xFFFF, payload: payload)
            return Outbound(frames: [FrameCodec.encode(frame)], nextSequence: FrameCodec.nextSequence(sequence))
        }
        guard case .strokePack = message else {
            if case .correctionPack = message {
                return try fragment(message, payload: payload, sequence: sequence, maxPayload: maxPayload, flags: flags)
            }
            throw ProtocolError(.payloadTooLarge, "\(message.type)")
        }
        return try fragment(message, payload: payload, sequence: sequence, maxPayload: maxPayload, flags: flags)
    }

    private static func fragment(
        _ message: LinkMessage,
        payload: [UInt8],
        sequence: Int,
        maxPayload: Int,
        flags: Int
    ) throws -> Outbound {
        let chunkRoom = maxPayload - FrameCodec.fragmentHeader
        if chunkRoom < 1 { throw ProtocolError(.mtuTooSmall, "no fragment room") }
        var chunks: [[UInt8]] = []
        var offset = 0
        while offset < payload.count {
            let end = min(offset + chunkRoom, payload.count)
            chunks.append(Array(payload[offset..<end]))
            offset = end
        }
        if chunks.count > 255 { throw ProtocolError(.payloadTooLarge, "too many fragments") }
        let msgId = sequence & 0xFFFF
        var seq = sequence & 0xFFFF
        var frames: [[UInt8]] = []
        for (index, chunk) in chunks.enumerated() {
            var fragFlags = FrameFlags.fragment
            if index == 0 && (flags & FrameFlags.ackRequest) != 0 {
                fragFlags |= FrameFlags.ackRequest
            }
            if index == chunks.count - 1 { fragFlags |= FrameFlags.fin }
            var body = ByteWriter()
            body.u8(index)
            body.u8(chunks.count)
            body.u16(msgId)
            body.bytes(chunk)
            frames.append(FrameCodec.encode(Frame(
                type: message.type,
                flags: fragFlags,
                sequence: seq,
                payload: body.toArray()
            )))
            seq = FrameCodec.nextSequence(seq)
        }
        return Outbound(frames: frames, nextSequence: seq)
    }
}

public final class InboundAssembler {
    private var partial: Partial?

    public init() {}

    public func push(_ bytes: [UInt8]) -> Inbound {
        let decoded = FrameCodec.decode(bytes)
        if case .rejected(let code, let detail) = decoded {
            return .rejected(code, detail)
        }
        guard case .ok(let frame) = decoded else { return .rejected(.badFrame, "decode") }
        if frame.flags & FrameFlags.fragment == 0 {
            if partial != nil {
                partial = nil
                return .rejected(.fragment, "interrupted by a full frame")
            }
            return decodeComplete(type: frame.type, payload: frame.payload, sequence: frame.sequence, flags: frame.flags)
        }
        return pushFragment(frame)
    }

    private func pushFragment(_ frame: Frame) -> Inbound {
        if frame.payload.count < FrameCodec.fragmentHeader {
            partial = nil
            return .rejected(.fragment, "short fragment")
        }
        var reader = ByteReader(frame.payload)
        let index: Int
        let count: Int
        let msgId: Int
        let chunk: [UInt8]
        do {
            index = try reader.u8()
            count = try reader.u8()
            msgId = try reader.u16()
            chunk = try reader.bytes(reader.remaining())
        } catch {
            partial = nil
            return .rejected(.fragment, "short fragment")
        }
        if count == 0 || index >= count {
            partial = nil
            return .rejected(.fragment, "bad index")
        }
        let partialNow: Partial
        if let current = partial, current.msgId == msgId {
            partialNow = current
        } else {
            partialNow = Partial(
                type: frame.type,
                msgId: msgId,
                count: count,
                firstSequence: index == 0 ? frame.sequence : -1,
                ackRequested: index == 0 && (frame.flags & FrameFlags.ackRequest) != 0
            )
        }
        if index == 0 {
            partialNow.firstSequence = frame.sequence
            partialNow.ackRequested = (frame.flags & FrameFlags.ackRequest) != 0
        }
        if partialNow.type != frame.type || partialNow.count != count {
            partial = nil
            return .rejected(.fragment, "fragment header changed")
        }
        if partialNow.chunks[index] != nil {
            partial = nil
            return .rejected(.fragment, "duplicate fragment")
        }
        partialNow.chunks[index] = chunk
        let last = index == count - 1
        let fin = (frame.flags & FrameFlags.fin) != 0
        if last != fin {
            partial = nil
            return .rejected(.fragment, "fin flag mismatch")
        }
        if partialNow.chunks.count < count {
            partial = partialNow
            return .pending
        }
        if partialNow.firstSequence < 0 {
            partial = nil
            return .rejected(.fragment, "missing first fragment")
        }
        partial = nil
        var payload: [UInt8] = []
        for i in 0..<count {
            guard let part = partialNow.chunks[i] else {
                return .rejected(.fragment, "missing fragment")
            }
            payload.append(contentsOf: part)
        }
        var flags = FrameFlags.fin
        if partialNow.ackRequested { flags |= FrameFlags.ackRequest }
        return decodeComplete(type: partialNow.type, payload: payload, sequence: partialNow.firstSequence, flags: flags)
    }

    private func decodeComplete(type: MessageType, payload: [UInt8], sequence: Int, flags: Int) -> Inbound {
        do {
            let message = try MessageCodec.decode(type: type, payload: payload)
            return .message(InboundMessage(
                message: message,
                sequence: sequence,
                flags: flags,
                ackRequested: (flags & FrameFlags.ackRequest) != 0
            ))
        } catch let error as ProtocolError {
            return .rejected(error.code, error.detail)
        } catch {
            return .rejected(.badFrame, "bad payload")
        }
    }

    private final class Partial {
        let type: MessageType
        let msgId: Int
        let count: Int
        var firstSequence: Int
        var ackRequested: Bool
        var chunks: [Int: [UInt8]] = [:]

        init(type: MessageType, msgId: Int, count: Int, firstSequence: Int, ackRequested: Bool) {
            self.type = type
            self.msgId = msgId
            self.count = count
            self.firstSequence = firstSequence
            self.ackRequested = ackRequested
        }
    }
}
