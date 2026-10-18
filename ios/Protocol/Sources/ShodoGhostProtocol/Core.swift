import Foundation

public enum ErrorCode: Int, CaseIterable, Sendable {
    case protocolMismatch = 1
    case crcFail = 2
    case busy = 3
    case thermal = 4
    case cameraBusy = 5
    case wifiTimeout = 6
    case tokenRejected = 7
    case payloadTooLarge = 8
    case unsupportedMode = 9
    case notCalibrated = 10
    case mtuTooSmall = 11
    case badFrame = 12
    case fragment = 13

    public static func from(code: Int) -> ErrorCode? {
        ErrorCode(rawValue: code)
    }
}

public struct ProtocolError: Error, Equatable, Sendable {
    public let code: ErrorCode
    public let detail: String

    public init(_ code: ErrorCode, _ detail: String) {
        self.code = code
        self.detail = detail
    }
}

public enum Limits {
    public static let protocolVersion = 1
    public static let minAttMtu = 185
    public static let preferredAttMtu = 247
    public static let minLinePx = 2
    public static let maxLinePx = 8
    public static let normMax = 10_000
    public static let maxPointsPerStroke = 400
    public static let maxStrokesPerPack = 32
    public static let maxAdviceBytes = 42
    public static let maxAdviceLines = 2
    public static let maxErrorBytes = 48
    public static let nonceBytes = 8
    public static let wifiTokenBytes = 16
    public static let maxPendingJpeg = 1
    public static let maxJpegBytes = 4 * 1024 * 1024
    public static let wifiHardTimeoutMs: UInt64 = 20_000
    public static let wifiChunkBytes = 16 * 1024
}

public enum HwFlags {
    public static let mainCamera = 1 << 0
    public static let wifi = 1 << 1
    public static let slamAdapter = 1 << 2
    public static let vgaSpatial = 1 << 3
}

public enum OverlayBits {
    public static let exemplar = 1 << 0
    public static let correction = 1 << 1
    public static let advice = 1 << 2
    public static let crosshair = 1 << 3
    public static let all = 0xFF
}

public enum HudCanvas {
    public static let width = 640
    public static let height = 480
    public static let textInsetX = 64
    public static let textInsetY = 48
    public static let opticalInsetX = 30
    public static let opticalInsetY = 20

    public static func textSafeContains(px: Int, py: Int) -> Bool {
        px >= textInsetX && py >= textInsetY && px < width - textInsetX && py < height - textInsetY
    }

    public static func opticalContains(px: Int, py: Int) -> Bool {
        px >= opticalInsetX && py >= opticalInsetY && px < width - opticalInsetX && py < height - opticalInsetY
    }

    public static func normToPxX(_ norm: Int) -> Int {
        min(max(norm, 0), Limits.normMax) * (width - 1) / Limits.normMax
    }

    public static func normToPxY(_ norm: Int) -> Int {
        min(max(norm, 0), Limits.normMax) * (height - 1) / Limits.normMax
    }
}

public enum HudPalette {
    public static let unlit = 0x000000
    public static let exemplar = 0x5CE1FF
    public static let contour = 0x39FF14
    public static let correction = 0xFF453A
    public static let text = 0xFFFFFF
    public static let warning = 0xFFD60A
}

public struct PaperPoint: Equatable, Sendable {
    public let x: Int
    public let y: Int

    public init(x: Int, y: Int) throws {
        guard (0...Limits.normMax).contains(x), (0...Limits.normMax).contains(y) else {
            throw ProtocolError(.badFrame, "paper point out of range: \(x),\(y)")
        }
        self.x = x
        self.y = y
    }
}

public struct HudPoint: Equatable, Sendable {
    public let x: Int
    public let y: Int

    public init(x: Int, y: Int) throws {
        guard (0...Limits.normMax).contains(x), (0...Limits.normMax).contains(y) else {
            throw ProtocolError(.badFrame, "hud point out of range: \(x),\(y)")
        }
        self.x = x
        self.y = y
    }
}

public struct PaperQuad: Equatable, Sendable {
    public let topLeft: HudPoint
    public let topRight: HudPoint
    public let bottomRight: HudPoint
    public let bottomLeft: HudPoint

    public init(topLeft: HudPoint, topRight: HudPoint, bottomRight: HudPoint, bottomLeft: HudPoint) {
        self.topLeft = topLeft
        self.topRight = topRight
        self.bottomRight = bottomRight
        self.bottomLeft = bottomLeft
    }
}

public enum InkLayer: Int, CaseIterable, Sendable {
    case skeleton = 1
    case contour = 2
    case correction = 3
    case crosshair = 4
    case marker = 5
}

public struct Stroke: Equatable, Sendable {
    public let layer: InkLayer
    public let widthPx: Int
    public let points: [PaperPoint]

    public init(layer: InkLayer, widthPx: Int, points: [PaperPoint]) throws {
        guard (Limits.minLinePx...Limits.maxLinePx).contains(widthPx) else {
            throw ProtocolError(.badFrame, "line width \(widthPx)")
        }
        guard !points.isEmpty, points.count <= Limits.maxPointsPerStroke else {
            throw ProtocolError(.badFrame, "stroke point count \(points.count)")
        }
        self.layer = layer
        self.widthPx = widthPx
        self.points = points
    }
}

public enum PaperProfile: Int, CaseIterable, Sendable {
    case hanshi = 0
    case noshi = 1
    case ichiji = 2

    public var widthMm: Int {
        switch self {
        case .hanshi: 242
        case .noshi: 175
        case .ichiji: 135
        }
    }

    public var heightMm: Int {
        switch self {
        case .hanshi: 333
        case .noshi: 85
        case .ichiji: 165
        }
    }
}

public enum HudMode: Int, CaseIterable, Sendable {
    case idle = 0
    case trace = 1
    case sideBySide = 2
    case review = 3
}

public enum MessageType: Int, CaseIterable, Sendable {
    case hello = 0x01
    case setMode = 0x02
    case exemplarMeta = 0x03
    case calibrateStart = 0x04
    case calibrateCommit = 0x05
    case captureNow = 0x06
    case showAdvice = 0x07
    case clearOverlay = 0x08
    case beginWifiSession = 0x09
    case endWifiSession = 0x0A
    case ping = 0x0B
    case setHud = 0x0C
    case sleepHint = 0x0D
    case strokePack = 0x20
    case correctionPack = 0x21
    case helloAck = 0x81
    case state = 0x82
    case touch = 0x83
    case captureReady = 0x84
    case error = 0x85
    case pong = 0x86
    case ack = 0x87
    case wifiReady = 0x88
    case wifiClosed = 0x89
    case sessionInfo = 0x8A
}

public enum PeerRole: Int, Sendable {
    case phoneCentral = 1
    case glassPeripheral = 2
}

public enum Thermal: Int, Sendable {
    case ok = 0
    case warm = 1
    case hot = 2
}

public enum CameraState: Int, Sendable {
    case closed = 0
    case opening = 1
    case open = 2
}

public enum TouchAction: Int, Sendable {
    case click = 1
    case longPress = 2
    case doubleClick = 3
    case tripleClick = 4
    case slideForward = 5
    case slideBackward = 6
    case slideUp = 7
    case slideDown = 8
}

public enum GuideCommand: Sendable {
    case confirm
    case finishAndCapture
    case back
    case cycleMode
    case nextExemplar
    case prevExemplar
    case nextPage
    case prevPage
}

extension TouchAction {
    public func toGuideCommand() -> GuideCommand {
        switch self {
        case .click: .confirm
        case .longPress: .finishAndCapture
        case .doubleClick: .back
        case .tripleClick: .cycleMode
        case .slideForward: .nextExemplar
        case .slideBackward: .prevExemplar
        case .slideUp: .nextPage
        case .slideDown: .prevPage
        }
    }
}

public enum CaptureReason: Int, Sendable {
    case temple = 1
    case phone = 2
    case ptt = 3
}

public enum CalibMethod: Int, Sendable {
    case oneShot = 1
    case manual = 2
    case slam = 3
}

public enum WifiPurpose: Int, Sendable {
    case uploadJpeg = 1
    case uploadAndResult = 2
}

public enum AckStatus: Int, Sendable {
    case ok = 0
    case rejected = 1
    case busy = 2
}

public enum LinkPhase: Int, CaseIterable, Sendable {
    case idle = 0
    case discovering = 1
    case connecting = 2
    case negotiating = 3
    case bleReady = 4
    case wifiWake = 5
    case transfer = 6
    case wifiTeardown = 7
    case backoff = 8
}

public enum FrameFlags {
    public static let ackRequest = 1 << 0
    public static let fin = 1 << 1
    public static let error = 1 << 2
    public static let fragment = 1 << 3
}

public enum GattProperty: Hashable, Sendable {
    case read
    case write
    case writeNoResponse
    case notify
}

public struct GattCharacteristic: Equatable, Sendable {
    public let name: String
    public let uuid: String
    public let properties: Set<GattProperty>
}

public enum GattProfile {
    public static let localName = "ShodoGhost"
    public static let service = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d10"
    public static let command = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d11"
    public static let status = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d12"
    public static let touch = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d13"
    public static let payload = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d14"
    public static let sessionInfo = "7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d15"
    public static let cccd = "00002902-0000-1000-8000-00805f9b34fb"

    public static let characteristics: [GattCharacteristic] = [
        GattCharacteristic(name: "Command", uuid: command, properties: [.write]),
        GattCharacteristic(name: "Status", uuid: status, properties: [.notify]),
        GattCharacteristic(name: "Touch", uuid: touch, properties: [.writeNoResponse]),
        GattCharacteristic(name: "Payload", uuid: payload, properties: [.write, .notify]),
        GattCharacteristic(name: "SessionInfo", uuid: sessionInfo, properties: [.read]),
    ]
}

public enum WifiSession {
    public static let path = "/v1/session"
    public static let subprotocol = "shodo-ghost.v1"
    public static let opHello = "hello"
    public static let opHelloAck = "hello_ack"
    public static let opJpegBegin = "jpeg_begin"
    public static let opJpegEnd = "jpeg_end"
    public static let opResult = "result"
    public static let opBye = "bye"
    public static let opError = "error"
}

public struct WifiTextFrame: Equatable, Sendable {
    public let op: String
    public let fields: [String: String]
}

public enum WifiText {
    public static func encode(op: String, fields: [String: String] = [:]) throws -> String {
        var body = "op=\(op)\n"
        for (key, value) in fields {
            guard !key.contains("="), !key.contains("\n"), !value.contains("\n") else {
                throw ProtocolError(.badFrame, "wifi field must be a single line")
            }
            body += "\(key)=\(value)\n"
        }
        return body
    }

    public static func decode(_ text: String) throws -> WifiTextFrame {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: true)
        guard !lines.isEmpty else { throw ProtocolError(.badFrame, "empty wifi text") }
        var fields: [String: String] = [:]
        for line in lines {
            guard let eq = line.firstIndex(of: "="), eq != line.startIndex else {
                throw ProtocolError(.badFrame, "wifi line missing key")
            }
            fields[String(line[..<eq])] = String(line[line.index(after: eq)...])
        }
        guard let op = fields.removeValue(forKey: "op") else {
            throw ProtocolError(.badFrame, "wifi op missing")
        }
        return WifiTextFrame(op: op, fields: fields)
    }
}

public enum Crc16 {
    public static func ccittFalse(_ data: [UInt8], offset: Int = 0, length: Int? = nil) -> Int {
        let count = length ?? (data.count - offset)
        var crc = 0xFFFF
        let end = offset + count
        for i in offset..<end {
            crc ^= Int(data[i]) << 8
            for _ in 0..<8 {
                if crc & 0x8000 != 0 {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF
                } else {
                    crc = (crc << 1) & 0xFFFF
                }
            }
        }
        return crc
    }
}

public enum Crc32 {
    public static func ieee(_ data: [UInt8]) -> UInt64 {
        var crc: UInt32 = 0xFFFF_FFFF
        for element in data {
            crc ^= UInt32(element)
            for _ in 0..<8 {
                if crc & 1 != 0 {
                    crc = (crc >> 1) ^ 0xEDB8_8320
                } else {
                    crc >>= 1
                }
            }
        }
        return UInt64(crc ^ 0xFFFF_FFFF)
    }
}

public struct ByteWriter {
    private var buf: [UInt8] = []

    public init() {}

    public mutating func u8(_ value: Int) {
        buf.append(UInt8(truncatingIfNeeded: value))
    }

    public mutating func u16(_ value: Int) {
        u8(value)
        u8(value >> 8)
    }

    public mutating func u32(_ value: UInt64) {
        let v = value & 0xFFFF_FFFF
        u16(Int(v & 0xFFFF))
        u16(Int((v >> 16) & 0xFFFF))
    }

    public mutating func i16(_ value: Int) {
        u16(value)
    }

    public mutating func bytes(_ value: [UInt8]) {
        buf.append(contentsOf: value)
    }

    public func toArray() -> [UInt8] { buf }
}

public struct ByteReader {
    private let buf: [UInt8]
    private var index = 0

    public init(_ buf: [UInt8]) {
        self.buf = buf
    }

    public func remaining() -> Int { buf.count - index }

    public mutating func u8() throws -> Int {
        guard index < buf.count else { throw ProtocolError(.badFrame, "need 1 bytes, have 0") }
        let value = Int(buf[index])
        index += 1
        return value
    }

    public mutating func u16() throws -> Int {
        let lo = try u8()
        let hi = try u8()
        return lo | (hi << 8)
    }

    public mutating func u32() throws -> UInt64 {
        let lo = try UInt64(u16())
        let hi = try UInt64(u16())
        return lo | (hi << 16)
    }

    public mutating func i16() throws -> Int {
        let v = try u16()
        return v >= 0x8000 ? v - 0x10000 : v
    }

    public mutating func bytes(_ count: Int) throws -> [UInt8] {
        guard count >= 0, remaining() >= count else {
            throw ProtocolError(.badFrame, "need \(count) bytes, have \(remaining())")
        }
        let out = Array(buf[index..<(index + count)])
        index += count
        return out
    }

    public mutating func utf8(_ count: Int) throws -> String {
        let raw = try bytes(count)
        guard let text = String(bytes: raw, encoding: .utf8) else {
            throw ProtocolError(.badFrame, "utf8")
        }
        return text
    }

    public func expectExhausted() throws {
        if remaining() != 0 {
            throw ProtocolError(.badFrame, "trailing \(remaining()) bytes")
        }
    }
}
