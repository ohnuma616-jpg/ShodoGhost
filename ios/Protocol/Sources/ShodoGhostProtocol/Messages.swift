import Foundation

public struct Hello: Equatable, Sendable {
    public let version: Int
    public let requestedMtu: Int
    public let role: PeerRole
    public let nonce: [UInt8]

    public init(version: Int, requestedMtu: Int, role: PeerRole, nonce: [UInt8]) throws {
        guard nonce.count == Limits.nonceBytes else { throw ProtocolError(.badFrame, "nonce") }
        self.version = version
        self.requestedMtu = requestedMtu
        self.role = role
        self.nonce = nonce
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(version)
        w.u16(requestedMtu)
        w.u8(role.rawValue)
        w.bytes(nonce)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> Hello {
        var r = ByteReader(payload)
        let message = try Hello(
            version: r.u8(),
            requestedMtu: r.u16(),
            role: try enumValue(PeerRole.self, r.u8(), "role"),
            nonce: r.bytes(Limits.nonceBytes)
        )
        try r.expectExhausted()
        return message
    }
}

public struct HelloAck: Equatable, Sendable {
    public let version: Int
    public let accept: Bool
    public let batteryPercent: Int
    public let thermal: Thermal
    public let batteryMillivolts: Int
    public let hwFlags: Int
    public let nonce: [UInt8]

    public init(
        version: Int,
        accept: Bool,
        batteryPercent: Int,
        thermal: Thermal,
        batteryMillivolts: Int,
        hwFlags: Int,
        nonce: [UInt8]
    ) throws {
        guard nonce.count == Limits.nonceBytes else { throw ProtocolError(.badFrame, "nonce") }
        guard (0...100).contains(batteryPercent) else { throw ProtocolError(.badFrame, "battery") }
        self.version = version
        self.accept = accept
        self.batteryPercent = batteryPercent
        self.thermal = thermal
        self.batteryMillivolts = batteryMillivolts
        self.hwFlags = hwFlags
        self.nonce = nonce
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(version)
        w.u8(accept ? 1 : 0)
        w.u8(batteryPercent)
        w.u8(thermal.rawValue)
        w.u16(batteryMillivolts)
        w.u16(hwFlags)
        w.bytes(nonce)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> HelloAck {
        var r = ByteReader(payload)
        let message = try HelloAck(
            version: r.u8(),
            accept: r.u8() == 1,
            batteryPercent: r.u8(),
            thermal: try enumValue(Thermal.self, r.u8(), "thermal"),
            batteryMillivolts: r.u16(),
            hwFlags: r.u16(),
            nonce: r.bytes(Limits.nonceBytes)
        )
        try r.expectExhausted()
        return message
    }
}

public struct SetMode: Equatable, Sendable {
    public let mode: HudMode
    public let paper: PaperProfile
    public init(mode: HudMode, paper: PaperProfile) {
        self.mode = mode
        self.paper = paper
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(mode.rawValue)
        w.u8(paper.rawValue)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> SetMode {
        var r = ByteReader(payload)
        let message = try SetMode(
            mode: enumValue(HudMode.self, r.u8(), "mode"),
            paper: enumValue(PaperProfile.self, r.u8(), "paper")
        )
        try r.expectExhausted()
        return message
    }
}

public struct ExemplarMeta: Equatable, Sendable {
    public let exemplarId: UInt64
    public let strokeCount: Int
    public let pointCount: Int
    public let layerMask: Int

    public init(exemplarId: UInt64, strokeCount: Int, pointCount: Int, layerMask: Int) {
        self.exemplarId = exemplarId
        self.strokeCount = strokeCount
        self.pointCount = pointCount
        self.layerMask = layerMask
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u32(exemplarId)
        w.u16(strokeCount)
        w.u16(pointCount)
        w.u8(layerMask)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> ExemplarMeta {
        var r = ByteReader(payload)
        let message = try ExemplarMeta(exemplarId: r.u32(), strokeCount: r.u16(), pointCount: r.u16(), layerMask: r.u8())
        try r.expectExhausted()
        return message
    }
}

public struct CalibrateStart: Equatable, Sendable {
    public let method: CalibMethod
    public let timeoutSec: Int
    public init(method: CalibMethod, timeoutSec: Int) {
        self.method = method
        self.timeoutSec = timeoutSec
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(method.rawValue)
        w.u8(timeoutSec)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> CalibrateStart {
        var r = ByteReader(payload)
        let message = try CalibrateStart(method: enumValue(CalibMethod.self, r.u8(), "calib"), timeoutSec: r.u8())
        try r.expectExhausted()
        return message
    }
}

public struct CalibrateCommit: Equatable, Sendable {
    public let method: CalibMethod
    public let quad: PaperQuad
    public let widthMm: Int
    public let heightMm: Int

    public init(method: CalibMethod, quad: PaperQuad, widthMm: Int, heightMm: Int) {
        self.method = method
        self.quad = quad
        self.widthMm = widthMm
        self.heightMm = heightMm
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(method.rawValue)
        writeHud(&w, quad.topLeft)
        writeHud(&w, quad.topRight)
        writeHud(&w, quad.bottomRight)
        writeHud(&w, quad.bottomLeft)
        w.i16(widthMm)
        w.i16(heightMm)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> CalibrateCommit {
        var r = ByteReader(payload)
        let message = try CalibrateCommit(
            method: enumValue(CalibMethod.self, r.u8(), "calib"),
            quad: PaperQuad(topLeft: readHud(&r), topRight: readHud(&r), bottomRight: readHud(&r), bottomLeft: readHud(&r)),
            widthMm: r.i16(),
            heightMm: r.i16()
        )
        try r.expectExhausted()
        return message
    }
}

public struct CaptureNow: Equatable, Sendable {
    public let reason: CaptureReason
    public let jpegQuality: Int

    public init(reason: CaptureReason, jpegQuality: Int) throws {
        guard (1...100).contains(jpegQuality) else { throw ProtocolError(.badFrame, "quality") }
        self.reason = reason
        self.jpegQuality = jpegQuality
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(reason.rawValue)
        w.u8(jpegQuality)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> CaptureNow {
        var r = ByteReader(payload)
        let message = try CaptureNow(reason: enumValue(CaptureReason.self, r.u8(), "capture"), jpegQuality: r.u8())
        try r.expectExhausted()
        return message
    }
}

public struct ShowAdvice: Equatable, Sendable {
    public let lines: [String]

    public init(lines: [String]) throws {
        guard !lines.isEmpty, lines.count <= Limits.maxAdviceLines else {
            throw ProtocolError(.badFrame, "advice lines")
        }
        for line in lines {
            guard !line.contains("\n") else { throw ProtocolError(.badFrame, "advice newline") }
            let size = Array(line.utf8).count
            guard (1...Limits.maxAdviceBytes).contains(size) else {
                throw ProtocolError(.badFrame, "advice line is \(size) bytes")
            }
        }
        self.lines = lines
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(lines.count)
        for line in lines {
            let raw = Array(line.utf8)
            w.u8(raw.count)
            w.bytes(raw)
        }
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> ShowAdvice {
        var r = ByteReader(payload)
        let count = try r.u8()
        var lines: [String] = []
        for _ in 0..<count {
            let len = try r.u8()
            lines.append(try r.utf8(len))
        }
        try r.expectExhausted()
        return try ShowAdvice(lines: lines)
    }
}

public struct ClearOverlay: Equatable, Sendable {
    public let mask: Int
    public init(mask: Int) { self.mask = mask }
    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(mask)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> ClearOverlay {
        var r = ByteReader(payload)
        let message = try ClearOverlay(mask: r.u8())
        try r.expectExhausted()
        return message
    }
}

public struct BeginWifiSession: Equatable, Sendable {
    public let ipv4: [UInt8]
    public let port: Int
    public let token: [UInt8]
    public let purpose: WifiPurpose

    public init(ipv4: [UInt8], port: Int, token: [UInt8], purpose: WifiPurpose) throws {
        guard ipv4.count == 4 else { throw ProtocolError(.badFrame, "ipv4") }
        guard token.count == Limits.wifiTokenBytes else { throw ProtocolError(.badFrame, "token") }
        guard (1...65535).contains(port) else { throw ProtocolError(.badFrame, "port") }
        self.ipv4 = ipv4
        self.port = port
        self.token = token
        self.purpose = purpose
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.bytes(ipv4)
        w.u16(port)
        w.bytes(token)
        w.u8(purpose.rawValue)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> BeginWifiSession {
        var r = ByteReader(payload)
        let message = try BeginWifiSession(
            ipv4: r.bytes(4),
            port: r.u16(),
            token: r.bytes(Limits.wifiTokenBytes),
            purpose: enumValue(WifiPurpose.self, r.u8(), "wifi purpose")
        )
        try r.expectExhausted()
        return message
    }
}

public struct EndWifiSession: Equatable, Sendable {
    public let reason: Int
    public init(reason: Int) { self.reason = reason }
    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(reason)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> EndWifiSession {
        var r = ByteReader(payload)
        let message = try EndWifiSession(reason: r.u8())
        try r.expectExhausted()
        return message
    }
}

public struct Ping: Equatable, Sendable {
    public let cookie: UInt64
    public init(cookie: UInt64) { self.cookie = cookie }
    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u32(cookie)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> Ping {
        var r = ByteReader(payload)
        let message = try Ping(cookie: r.u32())
        try r.expectExhausted()
        return message
    }
}

public struct Pong: Equatable, Sendable {
    public let cookie: UInt64
    public let uptimeSec: UInt64
    public init(cookie: UInt64, uptimeSec: UInt64) {
        self.cookie = cookie
        self.uptimeSec = uptimeSec
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u32(cookie)
        w.u32(uptimeSec)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> Pong {
        var r = ByteReader(payload)
        let message = try Pong(cookie: r.u32(), uptimeSec: r.u32())
        try r.expectExhausted()
        return message
    }
}

public struct SetHud: Equatable, Sendable {
    public let brightness: Int
    public let lineWidthPx: Int
    public let showCrosshair: Bool

    public init(brightness: Int, lineWidthPx: Int, showCrosshair: Bool) throws {
        guard (0...255).contains(brightness) else { throw ProtocolError(.badFrame, "brightness") }
        guard (Limits.minLinePx...Limits.maxLinePx).contains(lineWidthPx) else {
            throw ProtocolError(.badFrame, "line width")
        }
        self.brightness = brightness
        self.lineWidthPx = lineWidthPx
        self.showCrosshair = showCrosshair
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(brightness)
        w.u8(lineWidthPx)
        w.u8(showCrosshair ? 1 : 0)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> SetHud {
        var r = ByteReader(payload)
        let message = try SetHud(brightness: r.u8(), lineWidthPx: r.u8(), showCrosshair: r.u8() == 1)
        try r.expectExhausted()
        return message
    }
}

public struct SleepHint: Equatable, Sendable {
    public let displayOffDelayMs: Int
    public init(displayOffDelayMs: Int) { self.displayOffDelayMs = displayOffDelayMs }
    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u16(displayOffDelayMs)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> SleepHint {
        var r = ByteReader(payload)
        let message = try SleepHint(displayOffDelayMs: r.u16())
        try r.expectExhausted()
        return message
    }
}

public struct StateReport: Equatable, Sendable {
    public let mode: HudMode
    public let phase: LinkPhase
    public let thermal: Thermal
    public let batteryPercent: Int
    public let camera: CameraState
    public let overlayBits: Int
    public let batteryMillivolts: Int

    public init(
        mode: HudMode,
        phase: LinkPhase,
        thermal: Thermal,
        batteryPercent: Int,
        camera: CameraState,
        overlayBits: Int,
        batteryMillivolts: Int
    ) throws {
        guard (0...100).contains(batteryPercent) else { throw ProtocolError(.badFrame, "battery") }
        self.mode = mode
        self.phase = phase
        self.thermal = thermal
        self.batteryPercent = batteryPercent
        self.camera = camera
        self.overlayBits = overlayBits
        self.batteryMillivolts = batteryMillivolts
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(mode.rawValue)
        w.u8(phase.rawValue)
        w.u8(thermal.rawValue)
        w.u8(batteryPercent)
        w.u8(camera.rawValue)
        w.u8(overlayBits)
        w.u16(batteryMillivolts)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> StateReport {
        var r = ByteReader(payload)
        let message = try StateReport(
            mode: enumValue(HudMode.self, r.u8(), "mode"),
            phase: enumValue(LinkPhase.self, r.u8(), "phase"),
            thermal: enumValue(Thermal.self, r.u8(), "thermal"),
            batteryPercent: r.u8(),
            camera: enumValue(CameraState.self, r.u8(), "camera"),
            overlayBits: r.u8(),
            batteryMillivolts: r.u16()
        )
        try r.expectExhausted()
        return message
    }
}

public struct TouchReport: Equatable, Sendable {
    public let action: TouchAction
    public let elapsedMs: UInt64
    public init(action: TouchAction, elapsedMs: UInt64) {
        self.action = action
        self.elapsedMs = elapsedMs
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(action.rawValue)
        w.u32(elapsedMs)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> TouchReport {
        var r = ByteReader(payload)
        let message = try TouchReport(action: enumValue(TouchAction.self, r.u8(), "touch"), elapsedMs: r.u32())
        try r.expectExhausted()
        return message
    }
}

public struct CaptureReady: Equatable, Sendable {
    public let jpegBytes: UInt64
    public let crc32: UInt64
    public let cameraReleased: Bool
    public let width: Int
    public let height: Int

    public init(jpegBytes: UInt64, crc32: UInt64, cameraReleased: Bool, width: Int, height: Int) throws {
        guard cameraReleased else { throw ProtocolError(.badFrame, "capture ready requires the camera to be released") }
        guard jpegBytes >= 1, jpegBytes <= UInt64(Limits.maxJpegBytes) else {
            throw ProtocolError(.badFrame, "jpeg size")
        }
        self.jpegBytes = jpegBytes
        self.crc32 = crc32
        self.cameraReleased = cameraReleased
        self.width = width
        self.height = height
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u32(jpegBytes)
        w.u32(crc32)
        w.u8(1)
        w.u16(width)
        w.u16(height)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> CaptureReady {
        var r = ByteReader(payload)
        let message = try CaptureReady(
            jpegBytes: r.u32(),
            crc32: r.u32(),
            cameraReleased: r.u8() == 1,
            width: r.u16(),
            height: r.u16()
        )
        try r.expectExhausted()
        return message
    }
}

public struct ErrorReport: Equatable, Sendable {
    public let code: ErrorCode
    public let detail: String

    public init(code: ErrorCode, detail: String) throws {
        let size = Array(detail.utf8).count
        guard size <= Limits.maxErrorBytes else { throw ProtocolError(.badFrame, "error detail is \(size) bytes") }
        self.code = code
        self.detail = detail
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u16(code.rawValue)
        let raw = Array(detail.utf8)
        w.u8(raw.count)
        w.bytes(raw)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> ErrorReport {
        var r = ByteReader(payload)
        let codeRaw = try r.u16()
        guard let code = ErrorCode.from(code: codeRaw) else {
            throw ProtocolError(.badFrame, "unknown error code")
        }
        let detail = try r.utf8(r.u8())
        try r.expectExhausted()
        return try ErrorReport(code: code, detail: detail)
    }
}

public struct Ack: Equatable, Sendable {
    public let ackSequence: Int
    public let ackedType: MessageType
    public let status: AckStatus

    public init(ackSequence: Int, ackedType: MessageType, status: AckStatus) {
        self.ackSequence = ackSequence
        self.ackedType = ackedType
        self.status = status
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u16(ackSequence)
        w.u8(ackedType.rawValue)
        w.u8(status.rawValue)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> Ack {
        var r = ByteReader(payload)
        let seq = try r.u16()
        let type = try enumValue(MessageType.self, r.u8(), "acked type")
        let message = try Ack(ackSequence: seq, ackedType: type, status: enumValue(AckStatus.self, r.u8(), "ack"))
        try r.expectExhausted()
        return message
    }
}

public struct WifiReady: Equatable, Sendable {
    public let ok: Bool
    public init(ok: Bool) { self.ok = ok }
    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(ok ? 1 : 0)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> WifiReady {
        var r = ByteReader(payload)
        let message = try WifiReady(ok: r.u8() == 1)
        try r.expectExhausted()
        return message
    }
}

public struct WifiClosed: Equatable, Sendable {
    public let reason: Int
    public init(reason: Int) { self.reason = reason }
    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(reason)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> WifiClosed {
        var r = ByteReader(payload)
        let message = try WifiClosed(reason: r.u8())
        try r.expectExhausted()
        return message
    }
}

public struct SessionInfo: Equatable, Sendable {
    public let version: Int
    public let hwFlags: Int
    public let batteryPercent: Int
    public let thermal: Thermal
    public let phase: LinkPhase

    public init(version: Int, hwFlags: Int, batteryPercent: Int, thermal: Thermal, phase: LinkPhase) throws {
        guard (0...100).contains(batteryPercent) else { throw ProtocolError(.badFrame, "battery") }
        self.version = version
        self.hwFlags = hwFlags
        self.batteryPercent = batteryPercent
        self.thermal = thermal
        self.phase = phase
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u8(version)
        w.u16(hwFlags)
        w.u8(batteryPercent)
        w.u8(thermal.rawValue)
        w.u8(phase.rawValue)
        return w.toArray()
    }

    static func decode(_ payload: [UInt8]) throws -> SessionInfo {
        var r = ByteReader(payload)
        let message = try SessionInfo(
            version: r.u8(),
            hwFlags: r.u16(),
            batteryPercent: r.u8(),
            thermal: enumValue(Thermal.self, r.u8(), "thermal"),
            phase: enumValue(LinkPhase.self, r.u8(), "phase")
        )
        try r.expectExhausted()
        return message
    }
}

public struct StrokePack: Equatable, Sendable {
    public let exemplarOrCritiqueId: UInt64
    public let packIndex: Int
    public let packCount: Int
    public let strokes: [Stroke]
    public let correction: Bool

    public init(
        exemplarOrCritiqueId: UInt64,
        packIndex: Int,
        packCount: Int,
        strokes: [Stroke],
        correction: Bool
    ) throws {
        guard (1...255).contains(packCount) else { throw ProtocolError(.badFrame, "pack count") }
        guard (0..<packCount).contains(packIndex) else { throw ProtocolError(.badFrame, "pack index") }
        guard !strokes.isEmpty, strokes.count <= Limits.maxStrokesPerPack else {
            throw ProtocolError(.badFrame, "strokes")
        }
        self.exemplarOrCritiqueId = exemplarOrCritiqueId
        self.packIndex = packIndex
        self.packCount = packCount
        self.strokes = strokes
        self.correction = correction
    }

    func encodeBody() -> [UInt8] {
        var w = ByteWriter()
        w.u32(exemplarOrCritiqueId)
        w.u16(packIndex)
        w.u16(packCount)
        w.u16(strokes.count)
        for stroke in strokes {
            w.u8(stroke.layer.rawValue)
            w.u8(stroke.widthPx)
            w.u16(stroke.points.count)
            for point in stroke.points {
                w.i16(point.x)
                w.i16(point.y)
            }
        }
        return w.toArray()
    }

    static func decode(_ payload: [UInt8], correction: Bool) throws -> StrokePack {
        var r = ByteReader(payload)
        let id = try r.u32()
        let index = try r.u16()
        let count = try r.u16()
        let strokeCount = try r.u16()
        var strokes: [Stroke] = []
        for _ in 0..<strokeCount {
            let layer = try enumValue(InkLayer.self, r.u8(), "ink layer")
            let width = try r.u8()
            let pointCount = try r.u16()
            var points: [PaperPoint] = []
            for _ in 0..<pointCount {
                points.append(try PaperPoint(x: r.i16(), y: r.i16()))
            }
            strokes.append(try Stroke(layer: layer, widthPx: width, points: points))
        }
        try r.expectExhausted()
        return try StrokePack(
            exemplarOrCritiqueId: id,
            packIndex: index,
            packCount: count,
            strokes: strokes,
            correction: correction
        )
    }
}

public enum LinkMessage: Equatable, Sendable {
    case hello(Hello)
    case setMode(SetMode)
    case exemplarMeta(ExemplarMeta)
    case calibrateStart(CalibrateStart)
    case calibrateCommit(CalibrateCommit)
    case captureNow(CaptureNow)
    case showAdvice(ShowAdvice)
    case clearOverlay(ClearOverlay)
    case beginWifiSession(BeginWifiSession)
    case endWifiSession(EndWifiSession)
    case ping(Ping)
    case setHud(SetHud)
    case sleepHint(SleepHint)
    case strokePack(StrokePack)
    case correctionPack(StrokePack)
    case helloAck(HelloAck)
    case state(StateReport)
    case touch(TouchReport)
    case captureReady(CaptureReady)
    case error(ErrorReport)
    case pong(Pong)
    case ack(Ack)
    case wifiReady(WifiReady)
    case wifiClosed(WifiClosed)
    case sessionInfo(SessionInfo)

    public var type: MessageType {
        switch self {
        case .hello: .hello
        case .setMode: .setMode
        case .exemplarMeta: .exemplarMeta
        case .calibrateStart: .calibrateStart
        case .calibrateCommit: .calibrateCommit
        case .captureNow: .captureNow
        case .showAdvice: .showAdvice
        case .clearOverlay: .clearOverlay
        case .beginWifiSession: .beginWifiSession
        case .endWifiSession: .endWifiSession
        case .ping: .ping
        case .setHud: .setHud
        case .sleepHint: .sleepHint
        case .strokePack: .strokePack
        case .correctionPack: .correctionPack
        case .helloAck: .helloAck
        case .state: .state
        case .touch: .touch
        case .captureReady: .captureReady
        case .error: .error
        case .pong: .pong
        case .ack: .ack
        case .wifiReady: .wifiReady
        case .wifiClosed: .wifiClosed
        case .sessionInfo: .sessionInfo
        }
    }

    func encodeBody() -> [UInt8] {
        switch self {
        case .hello(let value): value.encodeBody()
        case .setMode(let value): value.encodeBody()
        case .exemplarMeta(let value): value.encodeBody()
        case .calibrateStart(let value): value.encodeBody()
        case .calibrateCommit(let value): value.encodeBody()
        case .captureNow(let value): value.encodeBody()
        case .showAdvice(let value): value.encodeBody()
        case .clearOverlay(let value): value.encodeBody()
        case .beginWifiSession(let value): value.encodeBody()
        case .endWifiSession(let value): value.encodeBody()
        case .ping(let value): value.encodeBody()
        case .setHud(let value): value.encodeBody()
        case .sleepHint(let value): value.encodeBody()
        case .strokePack(let value): value.encodeBody()
        case .correctionPack(let value): value.encodeBody()
        case .helloAck(let value): value.encodeBody()
        case .state(let value): value.encodeBody()
        case .touch(let value): value.encodeBody()
        case .captureReady(let value): value.encodeBody()
        case .error(let value): value.encodeBody()
        case .pong(let value): value.encodeBody()
        case .ack(let value): value.encodeBody()
        case .wifiReady(let value): value.encodeBody()
        case .wifiClosed(let value): value.encodeBody()
        case .sessionInfo(let value): value.encodeBody()
        }
    }
}

enum MessageCodec {
    static func decode(type: MessageType, payload: [UInt8]) throws -> LinkMessage {
        switch type {
        case .hello: .hello(try Hello.decode(payload))
        case .setMode: .setMode(try SetMode.decode(payload))
        case .exemplarMeta: .exemplarMeta(try ExemplarMeta.decode(payload))
        case .calibrateStart: .calibrateStart(try CalibrateStart.decode(payload))
        case .calibrateCommit: .calibrateCommit(try CalibrateCommit.decode(payload))
        case .captureNow: .captureNow(try CaptureNow.decode(payload))
        case .showAdvice: .showAdvice(try ShowAdvice.decode(payload))
        case .clearOverlay: .clearOverlay(try ClearOverlay.decode(payload))
        case .beginWifiSession: .beginWifiSession(try BeginWifiSession.decode(payload))
        case .endWifiSession: .endWifiSession(try EndWifiSession.decode(payload))
        case .ping: .ping(try Ping.decode(payload))
        case .setHud: .setHud(try SetHud.decode(payload))
        case .sleepHint: .sleepHint(try SleepHint.decode(payload))
        case .strokePack: .strokePack(try StrokePack.decode(payload, correction: false))
        case .correctionPack: .correctionPack(try StrokePack.decode(payload, correction: true))
        case .helloAck: .helloAck(try HelloAck.decode(payload))
        case .state: .state(try StateReport.decode(payload))
        case .touch: .touch(try TouchReport.decode(payload))
        case .captureReady: .captureReady(try CaptureReady.decode(payload))
        case .error: .error(try ErrorReport.decode(payload))
        case .pong: .pong(try Pong.decode(payload))
        case .ack: .ack(try Ack.decode(payload))
        case .wifiReady: .wifiReady(try WifiReady.decode(payload))
        case .wifiClosed: .wifiClosed(try WifiClosed.decode(payload))
        case .sessionInfo: .sessionInfo(try SessionInfo.decode(payload))
        }
    }
}

private func enumValue<T: RawRepresentable>(_ type: T.Type, _ code: Int, _ name: String) throws -> T where T.RawValue == Int {
    guard let value = T(rawValue: code) else {
        throw ProtocolError(.badFrame, "\(name) \(code)")
    }
    return value
}

private func writeHud(_ writer: inout ByteWriter, _ point: HudPoint) {
    writer.i16(point.x)
    writer.i16(point.y)
}

private func readHud(_ reader: inout ByteReader) throws -> HudPoint {
    try HudPoint(x: reader.i16(), y: reader.i16())
}
