import Foundation
import Testing
@testable import ShodoGhostProtocol

@Test func phoneCompletesHelloAgainstScriptedGlass() throws {
    let radio = ScriptedGlass()
    let session = PhoneSession(radio: radio)
    session.makeNonce = { [UInt8](repeating: 4, count: Limits.nonceBytes) }
    session.scheduleRetry = { _, _ in }
    let log = SessionLog()
    session.delegate = log
    session.start()
    #expect(session.phase == .bleReady)
    let ack = try #require(log.firstHello())
    #expect(ack.accept)
    #expect(ack.nonce == [UInt8](repeating: 4, count: Limits.nonceBytes))
}

@Test func phoneSetModeWaitsForAckBeforeWifi() throws {
    let radio = ScriptedGlass()
    let session = PhoneSession(radio: radio)
    session.scheduleRetry = { _, _ in }
    let log = SessionLog()
    session.delegate = log
    session.start()
    try session.setMode(.trace, paper: .hanshi)
    #expect(log.contains { event in
        if case .ack(let ack) = event { return ack.ackedType == .setMode && ack.status == .ok }
        return false
    })
    let token = try session.beginWifi(ipv4: [172, 20, 10, 1], port: 18765, purpose: .uploadJpeg)
    #expect(token.count == Limits.wifiTokenBytes)
    #expect(session.phase == .wifiWake)
    try session.endWifi(reason: 0)
    #expect(session.phase == .bleReady)
}

@Test func phoneRefusesShortMtu() {
    let radio = ScriptedGlass()
    radio.mtu = 23
    let session = PhoneSession(radio: radio)
    session.scheduleRetry = { _, _ in }
    session.start()
    #expect(session.phase == .backoff)
}

@Test func kotlinGlassLoopback() throws {
    guard let raw = ProcessInfo.processInfo.environment["SHODO_LOOPBACK_PORT"], let port = Int(raw) else {
        return
    }
    let radio = try TcpPhoneRadio.connect(host: "127.0.0.1", port: port)
    let session = PhoneSession(radio: radio)
    session.makeNonce = { [UInt8](repeating: 3, count: Limits.nonceBytes) }
    session.makeToken = { [UInt8](repeating: 9, count: Limits.wifiTokenBytes) }
    session.scheduleRetry = { _, _ in }
    let log = SessionLog()
    session.delegate = log
    session.start()
    try waitUntil(session.phase == .bleReady)
    try session.ping(0x11223344)
    try waitUntil(log.contains { event in
        if case .pong(let pong) = event { return pong.cookie == 0x11223344 }
        return false
    })
    try session.setMode(.trace, paper: .hanshi)
    let pack = try StrokePack(
        exemplarOrCritiqueId: 99,
        packIndex: 0,
        packCount: 1,
        strokes: (0..<20).map { index in
            try Stroke(
                layer: index % 2 == 0 ? .skeleton : .contour,
                widthPx: 2,
                points: (0..<30).map { point in
                    try PaperPoint(x: point * 100, y: index * 200)
                }
            )
        },
        correction: false
    )
    try session.sendExemplar(
        ExemplarMeta(exemplarId: 99, strokeCount: 20, pointCount: 600, layerMask: InkLayer.skeleton.rawValue),
        packs: [pack]
    )
    try waitUntil(log.contains { event in
        if case .state(let state) = event { return state.overlayBits & OverlayBits.exemplar != 0 }
        return false
    })
    _ = try session.beginWifi(ipv4: [172, 20, 10, 1], port: 18765, purpose: .uploadJpeg)
    try waitUntil(session.phase == .wifiWake)
    try session.showAdvice(["起筆を垂直に", "ハネを短く"])
    try waitUntil(log.contains { event in
        if case .state(let state) = event { return state.overlayBits & OverlayBits.advice != 0 }
        return false
    })
    try session.endWifi(reason: 0)
    try waitUntil(session.phase == .bleReady)
    session.stop()
}

private func waitUntil(_ condition: @autoclosure () -> Bool) throws {
    let deadline = Date().addingTimeInterval(3)
    while !condition() {
        if Date() > deadline { throw ProtocolError(.wifiTimeout, "timed out") }
        Thread.sleep(forTimeInterval: 0.01)
    }
}

private final class SessionLog: PhoneSessionDelegate {
    private let lock = NSLock()
    private var events: [PhoneEvent] = []

    func phoneSession(_ session: PhoneSession, phase: LinkPhase) {}

    func phoneSession(_ session: PhoneSession, event: PhoneEvent) {
        lock.lock()
        events.append(event)
        lock.unlock()
    }

    func contains(_ predicate: (PhoneEvent) -> Bool) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return events.contains(where: predicate)
    }

    func firstHello() -> HelloAck? {
        lock.lock()
        defer { lock.unlock() }
        for event in events {
            if case .helloAck(let ack) = event { return ack }
        }
        return nil
    }
}

private final class ScriptedGlass: PhoneRadio {
    weak var sink: PhoneRadioSink?
    var mtu = 247
    private var outSeq = 1

    func prepare(mtu: Int, ready: @escaping (Int) -> Void) {
        ready(self.mtu)
    }

    func readSessionInfo(ready: @escaping ([UInt8]) -> Void) {
        let info = try! SessionInfo(
            version: Limits.protocolVersion,
            hwFlags: HwFlags.mainCamera | HwFlags.wifi,
            batteryPercent: 80,
            thermal: .ok,
            phase: .negotiating
        )
        ready(try! encode(.sessionInfo(info)))
    }

    func writeCommand(_ value: [UInt8]) {
        guard case .message(let inbound) = InboundAssembler().push(value) else { return }
        switch inbound.message {
        case .hello(let hello):
            let ack = try! HelloAck(
                version: Limits.protocolVersion,
                accept: true,
                batteryPercent: 80,
                thermal: .ok,
                batteryMillivolts: 3900,
                hwFlags: HwFlags.mainCamera,
                nonce: hello.nonce
            )
            sink?.radioDidNotifyStatus(try! encode(.helloAck(ack)))
            sink?.radioDidNotifyStatus(try! encode(.state(try! StateReport(
                mode: .idle,
                phase: .bleReady,
                thermal: .ok,
                batteryPercent: 80,
                camera: .closed,
                overlayBits: 0,
                batteryMillivolts: 3900
            ))))
        case .setMode:
            sink?.radioDidNotifyStatus(try! encode(.ack(Ack(
                ackSequence: inbound.sequence,
                ackedType: .setMode,
                status: .ok
            ))))
        case .beginWifiSession:
            sink?.radioDidNotifyStatus(try! encode(.ack(Ack(
                ackSequence: inbound.sequence,
                ackedType: .beginWifiSession,
                status: .ok
            ))))
        case .endWifiSession:
            sink?.radioDidNotifyStatus(try! encode(.ack(Ack(
                ackSequence: inbound.sequence,
                ackedType: .endWifiSession,
                status: .ok
            ))))
        case .ping(let ping):
            sink?.radioDidNotifyStatus(try! encode(.pong(Pong(cookie: ping.cookie, uptimeSec: 1))))
        default:
            break
        }
    }

    func writeTouch(_ value: [UInt8]) {}

    func writePayload(_ value: [UInt8]) {
        guard case .message(let inbound) = InboundAssembler().push(value) else { return }
        if case .strokePack = inbound.message {
            sink?.radioDidNotifyStatus(try! encode(.state(try! StateReport(
                mode: .trace,
                phase: .bleReady,
                thermal: .ok,
                batteryPercent: 80,
                camera: .closed,
                overlayBits: OverlayBits.exemplar,
                batteryMillivolts: 3900
            ))))
        }
    }

    func close() {}

    private func encode(_ message: LinkMessage) throws -> [UInt8] {
        let outbound = try Protocol.encode(message, sequence: outSeq, attMtu: mtu)
        outSeq = outbound.nextSequence
        return outbound.frames[0]
    }
}
