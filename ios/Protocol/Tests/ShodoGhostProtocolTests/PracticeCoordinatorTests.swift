import Foundation
import Testing
@testable import ShodoGhostProtocol

@Test func websocketAcceptMatchesRfcSample() {
    let response = WebSocketHandshake.accept(key: "dGhlIHNhbXBsZSBub25jZQ==")
    #expect(response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
}

@Test func websocketHeaderReadsCrlfKey() {
    let header = "GET /v1/session HTTP/1.1\r\nHost: 127.0.0.1:9\r\nSec-WebSocket-Key: abc+def=\r\n\r\n"
    #expect(WifiExchange.headerValue(header, "Sec-WebSocket-Key") == "abc+def=")
}

@Test func lessonSendsTheEiSkeletonInTrace() throws {
    let radio = BurstRadio()
    let session = PhoneSession(radio: radio)
    session.scheduleRetry = { _, _ in }
    let log = BurstLog()
    session.delegate = log
    session.start()
    let practice = PracticeCoordinator(session: session, backend: ManualCritique(), link: ScriptedWifi())
    try practice.sendLesson()
    #expect(practice.exemplarId == EiExemplar.id)
    #expect(practice.exemplarStrokes.count == 3)
    #expect(practice.exemplarStrokes.allSatisfy { $0.layer == .contour && $0.widthPx == Limits.minLinePx })
    #expect(practice.exemplarStrokes[0].points[0] == (try PaperPoint(x: 3536, y: 955)))
    var sent: [String] = []
    for message in radio.commands {
        switch message {
        case .setMode(let mode):
            #expect(mode.mode == .trace)
            #expect(mode.paper == .hanshi)
            sent.append("mode")
        case .exemplarMeta(let meta):
            #expect(meta.exemplarId == EiExemplar.id)
            #expect(meta.strokeCount == 3)
            #expect(meta.pointCount == 62)
            sent.append("meta")
        case .strokePack(let pack):
            #expect(pack.correction == false)
            #expect(pack.strokes.count == 3)
            sent.append("stroke")
        default:
            break
        }
    }
    #expect(sent == ["mode", "meta", "stroke"])
}

@Test func backgroundCaptureWaitsForThePracticeScreen() throws {
    let radio = BurstRadio()
    let session = PhoneSession(radio: radio)
    session.scheduleRetry = { _, _ in }
    let log = BurstLog()
    session.delegate = log
    session.start()
    let practice = PracticeCoordinator(session: session, backend: ManualCritique(), link: ScriptedWifi())
    let ready = try sampleReady()
    try practice.noteCaptureReady(ready)
    #expect(practice.heldCapture == ready)
    #expect(!radio.commands.contains { if case .beginWifiSession = $0 { return true }; return false })
    try practice.setForeground(true)
    #expect(practice.heldCapture == nil)
    #expect(practice.lastCritique?.advice == ["起筆を垂直に", "ハネを短く"])
    #expect(radio.commands.contains { if case .correctionPack = $0 { return true }; return false })
    #expect(session.phase == .bleReady)
}

@Test func rejectedWifiTokenClearsTheWaitingAdvice() throws {
    let radio = BurstRadio()
    let session = PhoneSession(radio: radio)
    session.scheduleRetry = { _, _ in }
    let log = BurstLog()
    session.delegate = log
    session.start()
    let link = ScriptedWifi()
    link.failToken = true
    let practice = PracticeCoordinator(session: session, backend: ManualCritique(), link: link)
    try practice.setForeground(true)
    #expect(throws: ProtocolError.self) {
        try practice.noteCaptureReady(try sampleReady())
    }
    #expect(practice.lastCritique == nil)
    #expect(radio.commands.contains { if case .clearOverlay = $0 { return true }; return false })
    #expect(session.phase == .bleReady)
}

@Test func tcpUploadReturnsCorrectionAfterWifiCloses() throws {
    let radio = BurstRadio()
    let session = PhoneSession(radio: radio)
    session.makeToken = { [UInt8](repeating: 9, count: Limits.wifiTokenBytes) }
    session.scheduleRetry = { _, _ in }
    let log = BurstLog()
    session.delegate = log
    session.start()
    let jpeg = [UInt8](repeating: 0xAB, count: Limits.wifiChunkBytes + 32)
    let ready = try CaptureReady(
        jpegBytes: UInt64(jpeg.count),
        crc32: Crc32.ieee(jpeg),
        cameraReleased: true,
        width: 640,
        height: 480
    )
    let link = TcpWifiListener()
    let practice = PracticeCoordinator(session: session, backend: ManualCritique(), link: link)
    let failure = FailureBox()
    let worker = Thread {
        do {
            for _ in 0..<200 {
                if link.boundPort != 0 { break }
                Thread.sleep(forTimeInterval: 0.01)
            }
            try WifiUploader.upload(
                host: "127.0.0.1",
                port: link.boundPort,
                token: [UInt8](repeating: 9, count: Limits.wifiTokenBytes),
                jpeg: jpeg,
                width: 640,
                height: 480
            )
        } catch {
            failure.message = String(describing: error)
        }
    }
    try practice.setForeground(true)
    worker.start()
    try practice.noteCaptureReady(ready)
    #expect(failure.message == nil)
    #expect(practice.lastCritique?.advice.count == 2)
    let correctionIndex = radio.commands.firstIndex { if case .correctionPack = $0 { return true }; return false }
    let endIndex = radio.commands.firstIndex { if case .endWifiSession = $0 { return true }; return false }
    let waitingIndex = radio.commands.firstIndex { message in
        if case .showAdvice(let advice) = message { return advice.lines == ["添削中"] }
        return false
    }
    #expect(waitingIndex != nil)
    #expect(endIndex != nil)
    #expect(correctionIndex != nil)
    #expect(endIndex! < correctionIndex!)
}

private func sampleReady() throws -> CaptureReady {
    let jpeg: [UInt8] = [0xFF, 0xD8, 0xFF, 0xD9]
    return try CaptureReady(
        jpegBytes: UInt64(jpeg.count),
        crc32: Crc32.ieee(jpeg),
        cameraReleased: true,
        width: 4,
        height: 4
    )
}

private final class ScriptedWifi: WifiBurstLink {
    var boundPort = 18765
    var failToken = false
    private let jpeg: [UInt8] = [0xFF, 0xD8, 0xFF, 0xD9]

    func listen(port: Int) throws {
        boundPort = port == 0 ? 18765 : port
    }

    func stop() {}

    func receive(token: [UInt8], expect: CaptureReady, timeoutSeconds: Double) throws -> [UInt8] {
        if failToken { throw ProtocolError(.tokenRejected, "token") }
        guard jpeg.count == Int(expect.jpegBytes), Crc32.ieee(jpeg) == expect.crc32 else {
            throw ProtocolError(.crcFail, "jpeg")
        }
        return jpeg
    }
}

private final class BurstLog: PhoneSessionDelegate {
    func phoneSession(_ session: PhoneSession, phase: LinkPhase) {}
    func phoneSession(_ session: PhoneSession, event: PhoneEvent) {}
}

private final class FailureBox: @unchecked Sendable {
    var message: String?
}

private final class BurstRadio: PhoneRadio, @unchecked Sendable {
    weak var sink: PhoneRadioSink?
    var commands: [LinkMessage] = []
    private var outSeq = 1
    private let lock = NSLock()
    private let payloadAssembler = InboundAssembler()

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
        lock.lock()
        commands.append(inbound.message)
        lock.unlock()
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
        default:
            break
        }
    }

    func writeTouch(_ value: [UInt8]) {}
    func writePayload(_ value: [UInt8]) {
        guard case .message(let inbound) = payloadAssembler.push(value) else { return }
        lock.lock()
        commands.append(inbound.message)
        lock.unlock()
    }
    func close() {}

    private func encode(_ message: LinkMessage) throws -> [UInt8] {
        let outbound = try Protocol.encode(message, sequence: outSeq, attMtu: 247)
        outSeq = outbound.nextSequence
        return outbound.frames[0]
    }
}
