import Foundation
import Testing
@testable import ShodoGhostProtocol

@Test func powerPolicyKeepsWifiOffUnlessTransferring() {
    #expect(PowerPolicy.persistsAcrossProcessDeath == false)
    #expect(PowerPolicy.allows(phase: .idle, cameraOpen: false, displayAsleep: false).isEmpty)
    #expect(PowerPolicy.allows(phase: .bleReady, cameraOpen: false, displayAsleep: false) == [.ble])
    #expect(PowerPolicy.allows(phase: .bleReady, cameraOpen: true, displayAsleep: false) == [.ble, .camera])
    #expect(PowerPolicy.allows(phase: .transfer, cameraOpen: false, displayAsleep: false) == [.ble, .wifi, .wakeLock])
    #expect(PowerPolicy.allows(phase: .wifiWake, cameraOpen: false, displayAsleep: true) == [.ble])
}

@Test func reconnectReplaysExemplarWhenTheBitIsMissing() throws {
    let radio = ReplayGlass()
    let session = PhoneSession(radio: radio)
    session.scheduleRetry = { _, work in work() }
    let log = ReplayLog()
    session.delegate = log
    session.start()
    let pack = try StrokePack(
        exemplarOrCritiqueId: 7,
        packIndex: 0,
        packCount: 1,
        strokes: [try Stroke(layer: .skeleton, widthPx: 2, points: [try PaperPoint(x: 0, y: 0), try PaperPoint(x: 1000, y: 1000)])],
        correction: false
    )
    try session.sendExemplar(
        ExemplarMeta(exemplarId: 7, strokeCount: 1, pointCount: 2, layerMask: InkLayer.skeleton.rawValue),
        packs: [pack]
    )
    #expect(radio.payloadWrites == 1)
    session.linkLost()
    #expect(session.phase == .bleReady)
    #expect(radio.payloadWrites == 2)
}

@Test func reconnectSkipsReplayWhenTheExemplarBitSurvives() throws {
    let radio = ReplayGlass()
    radio.keptExemplar = true
    let session = PhoneSession(radio: radio)
    session.scheduleRetry = { _, work in work() }
    let log = ReplayLog()
    session.delegate = log
    session.start()
    let pack = try StrokePack(
        exemplarOrCritiqueId: 7,
        packIndex: 0,
        packCount: 1,
        strokes: [try Stroke(layer: .skeleton, widthPx: 2, points: [try PaperPoint(x: 0, y: 0)])],
        correction: false
    )
    try session.sendExemplar(
        ExemplarMeta(exemplarId: 7, strokeCount: 1, pointCount: 1, layerMask: InkLayer.skeleton.rawValue),
        packs: [pack]
    )
    #expect(radio.payloadWrites == 1)
    session.linkLost()
    #expect(radio.payloadWrites == 1)
    #expect(session.phase == .bleReady)
}

@Test func wifiTimeoutErrorReturnsThePhoneToBle() throws {
    let radio = ReplayGlass()
    let session = PhoneSession(radio: radio)
    session.scheduleRetry = { _, _ in }
    let log = ReplayLog()
    session.delegate = log
    session.start()
    _ = try session.beginWifi(ipv4: [172, 20, 10, 1], port: 9, purpose: .uploadJpeg)
    #expect(session.phase == .wifiWake)
    try radio.failWifi()
    #expect(session.phase == .bleReady)
}

private final class ReplayLog: PhoneSessionDelegate {
    func phoneSession(_ session: PhoneSession, phase: LinkPhase) {}
    func phoneSession(_ session: PhoneSession, event: PhoneEvent) {}
}

private final class ReplayGlass: PhoneRadio {
    weak var sink: PhoneRadioSink?
    var payloadWrites = 0
    var keptExemplar = false
    private var outSeq = 1

    func prepare(mtu: Int, ready: @escaping (Int) -> Void) { ready(247) }

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
            sink?.radioDidNotifyStatus(try! encode(.helloAck(HelloAck(
                version: Limits.protocolVersion,
                accept: true,
                batteryPercent: 80,
                thermal: .ok,
                batteryMillivolts: 3900,
                hwFlags: HwFlags.mainCamera,
                nonce: hello.nonce
            ))))
            let bits = keptExemplar ? OverlayBits.exemplar : 0
            sink?.radioDidNotifyStatus(try! encode(.state(try! StateReport(
                mode: .idle,
                phase: .bleReady,
                thermal: .ok,
                batteryPercent: 80,
                camera: .closed,
                overlayBits: bits,
                batteryMillivolts: 3900
            ))))
        case .beginWifiSession:
            sink?.radioDidNotifyStatus(try! encode(.ack(Ack(
                ackSequence: inbound.sequence,
                ackedType: .beginWifiSession,
                status: .ok
            ))))
        default:
            break
        }
    }

    func writeTouch(_ value: [UInt8]) {}

    func writePayload(_ value: [UInt8]) {
        payloadWrites += 1
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

    func close() {}

    func failWifi() throws {
        sink?.radioDidNotifyStatus(try encode(.error(try ErrorReport(code: .wifiTimeout, detail: "wifi"))))
    }

    private func encode(_ message: LinkMessage) throws -> [UInt8] {
        let outbound = try Protocol.encode(message, sequence: outSeq, attMtu: 247)
        outSeq = outbound.nextSequence
        return outbound.frames[0]
    }
}
