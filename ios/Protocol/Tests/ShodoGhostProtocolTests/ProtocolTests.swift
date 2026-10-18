import Foundation
import Testing
@testable import ShodoGhostProtocol

@Test func crcVectors() {
    let bytes = Array("123456789".utf8)
    #expect(Crc16.ccittFalse(bytes) == 0x29B1)
    #expect(Crc32.ieee(bytes) == 0xCBF43926)
}

@Test func canonicalVectorsMatchKotlinBytes() throws {
    let text = try String(contentsOf: fixturesFile(), encoding: .utf8)
    let vectors = parseVectors(text)
    #expect(vectors.count >= 24)
    for vector in vectors {
        let assembler = InboundAssembler()
        var decoded: LinkMessage?
        for frame in vector.frames {
            let inbound = assembler.push(frame)
            if case .message(let message) = inbound {
                decoded = message.message
                #expect(message.sequence == vector.sequence)
            } else if case .rejected(let code, let detail) = inbound {
                throw ProtocolError(code, "\(vector.name) \(detail)")
            }
        }
        let message = try #require(decoded)
        let encoded = try Protocol.encode(message, sequence: vector.sequence, attMtu: vector.mtu)
        #expect(encoded.frames == vector.frames)
    }
}

@Test func strokeFragmentsReassembleOutOfOrder() throws {
    let vectors = parseVectors(try String(contentsOf: fixturesFile(), encoding: .utf8))
    let stroke = try #require(vectors.first { $0.name == "STROKE_PACK" })
    #expect(stroke.frames.count > 1)
    let assembler = InboundAssembler()
    var pending = 0
    var got: LinkMessage?
    for frame in stroke.frames.reversed() {
        switch assembler.push(frame) {
        case .pending:
            pending += 1
        case .message(let message):
            got = message.message
            #expect(message.sequence == 10)
        case .rejected(let code, let detail):
            throw ProtocolError(code, detail)
        }
    }
    #expect(pending == stroke.frames.count - 1)
    let message = try #require(got)
    if case .strokePack(let pack) = message {
        #expect(pack.strokes.count == 20)
        #expect(pack.strokes[0].points.count == 30)
    } else {
        throw ProtocolError(.badFrame, "expected stroke pack")
    }
}

@Test func adviceAndQuadSurviveKotlinBytes() throws {
    let vectors = parseVectors(try String(contentsOf: fixturesFile(), encoding: .utf8))
    let adviceFrame = try #require(vectors.first { $0.name == "SHOW_ADVICE" }?.frames.first)
    let inbound = InboundAssembler().push(adviceFrame)
    guard case .message(let message) = inbound, case .showAdvice(let advice) = message.message else {
        throw ProtocolError(.badFrame, "advice missing")
    }
    #expect(advice.lines == ["起筆を垂直に", "ハネを短く"])

    let quadFrame = try #require(vectors.first { $0.name == "CALIBRATE_COMMIT" }?.frames.first)
    let quadInbound = InboundAssembler().push(quadFrame)
    guard case .message(let wrapped) = quadInbound, case .calibrateCommit(let commit) = wrapped.message else {
        throw ProtocolError(.badFrame, "quad missing")
    }
    #expect(commit.quad.topLeft == (try HudPoint(x: 1000, y: 1200)))
    #expect(commit.widthMm == 242)
    #expect(wrapped.ackRequested)
}

@Test func homographyMapsCorners() throws {
    let quad = try PaperQuad(
        topLeft: HudPoint(x: 2000, y: 2000),
        topRight: HudPoint(x: 8000, y: 2200),
        bottomRight: HudPoint(x: 7600, y: 8000),
        bottomLeft: HudPoint(x: 2400, y: 7800)
    )
    let map = try Homography.fromPaperQuad(quad)
    try expectClose(quad.topLeft, map.map(PaperPoint(x: 0, y: 0)))
    try expectClose(quad.topRight, map.map(PaperPoint(x: Limits.normMax, y: 0)))
    try expectClose(quad.bottomRight, map.map(PaperPoint(x: Limits.normMax, y: Limits.normMax)))
    try expectClose(quad.bottomLeft, map.map(PaperPoint(x: 0, y: Limits.normMax)))
    let degenerate = try PaperQuad(
        topLeft: HudPoint(x: 1, y: 1),
        topRight: HudPoint(x: 1, y: 1),
        bottomRight: HudPoint(x: 1, y: 1),
        bottomLeft: HudPoint(x: 1, y: 1)
    )
    #expect(throws: ProtocolError.self) {
        try Homography.fromPaperQuad(degenerate)
    }
}

@Test func linkReturnsToBleWhenWifiTimesOut() {
    var phase = LinkPhase.idle
    phase = expect(phase, .start, .discovering, [.openDiscovery])
    phase = expect(phase, .peerFound, .connecting, [.closeDiscovery])
    phase = expect(phase, .gattConnected, .negotiating, [.requestMtuAndHello])
    phase = expect(phase, .helloOk, .bleReady)
    phase = expect(phase, .beginWifi, .wifiWake, [.acquireRadios, .openWifiSocket])
    phase = expect(phase, .timeout, .bleReady, [.closeWifiSocket, .releaseRadios])
    let lost = LinkStateMachine.step(phase: phase, signal: .linkLost)
    #expect(lost.phase == .backoff)
    #expect(lost.effects == [.scheduleRetry])
    #expect(RetrySchedule.delayMs(attempt: 0) == 1_000)
    #expect(RetrySchedule.delayMs(attempt: 20) == 15_000)
    let ignored = LinkStateMachine.step(phase: .idle, signal: .beginWifi)
    #expect(!ignored.accepted)
}

@Test func templeLongPressFinishes() {
    #expect(TouchAction.longPress.toGuideCommand() == .finishAndCapture)
    #expect(TouchAction.doubleClick.toGuideCommand() == .back)
}

@Test func wifiTextRoundTrip() throws {
    let text = try WifiText.encode(op: WifiSession.opJpegBegin, fields: [
        "bytes": "120000",
        "width": "4000",
        "height": "3000",
    ])
    let frame = try WifiText.decode(text)
    #expect(frame.op == WifiSession.opJpegBegin)
    #expect(frame.fields["bytes"] == "120000")
}

private struct Vector {
    let name: String
    let sequence: Int
    let mtu: Int
    let frames: [[UInt8]]
}

private func fixturesFile() -> URL {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 {
        url.deleteLastPathComponent()
    }
    return url.appendingPathComponent("fixtures/canonical.txt")
}

private func parseVectors(_ text: String) -> [Vector] {
    var vectors: [Vector] = []
    var name = ""
    var sequence = 0
    var mtu = 0
    var frames: [[UInt8]] = []
    for raw in text.split(separator: "\n", omittingEmptySubsequences: false) {
        let line = String(raw)
        if line.hasPrefix("VECTOR ") {
            name = String(line.dropFirst(7))
            frames = []
        } else if line.hasPrefix("SEQUENCE ") {
            sequence = Int(line.dropFirst(9)) ?? 0
        } else if line.hasPrefix("MTU ") {
            mtu = Int(line.dropFirst(4)) ?? 0
        } else if line.hasPrefix("FRAME ") {
            frames.append(hexBytes(String(line.dropFirst(6))))
        } else if line == "END" {
            vectors.append(Vector(name: name, sequence: sequence, mtu: mtu, frames: frames))
        }
    }
    return vectors
}

private func hexBytes(_ hex: String) -> [UInt8] {
    var bytes: [UInt8] = []
    var index = hex.startIndex
    while index < hex.endIndex {
        let next = hex.index(index, offsetBy: 2)
        bytes.append(UInt8(hex[index..<next], radix: 16) ?? 0)
        index = next
    }
    return bytes
}

private func expect(
    _ phase: LinkPhase,
    _ signal: LinkSignal,
    _ next: LinkPhase,
    _ effects: [LinkEffect] = []
) -> LinkPhase {
    let step = LinkStateMachine.step(phase: phase, signal: signal)
    #expect(step.accepted)
    #expect(step.phase == next)
    #expect(step.effects == effects)
    return step.phase
}

private func expectClose(_ expected: HudPoint, _ actual: HudPoint) {
    #expect(abs(expected.x - actual.x) <= 1)
    #expect(abs(expected.y - actual.y) <= 1)
}
