import Foundation

public protocol PhoneRadioSink: AnyObject {
    func radioDidNotifyStatus(_ bytes: [UInt8])
    func radioDidNotifyPayload(_ bytes: [UInt8])
    func radioDidDrop()
}

public protocol PhoneRadio: AnyObject {
    var sink: PhoneRadioSink? { get set }
    func prepare(mtu: Int, ready: @escaping (Int) -> Void)
    func readSessionInfo(ready: @escaping ([UInt8]) -> Void)
    func writeCommand(_ value: [UInt8])
    func writeTouch(_ value: [UInt8])
    func writePayload(_ value: [UInt8])
    func close()
}

public enum PhoneEvent: Equatable, Sendable {
    case sessionInfo(SessionInfo)
    case helloAck(HelloAck)
    case state(StateReport)
    case touch(TouchReport)
    case captureReady(CaptureReady)
    case correction(StrokePack)
    case ack(Ack)
    case error(ErrorReport)
    case wifiReady(WifiReady)
    case wifiClosed(WifiClosed)
    case pong(Pong)
}

public protocol PhoneSessionDelegate: AnyObject {
    func phoneSession(_ session: PhoneSession, phase: LinkPhase)
    func phoneSession(_ session: PhoneSession, event: PhoneEvent)
}

public final class PhoneSession: PhoneRadioSink, @unchecked Sendable {
    public weak var delegate: PhoneSessionDelegate?
    public var phase: LinkPhase {
        lock.lock()
        defer { lock.unlock() }
        return storedPhase
    }
    public private(set) var attMtu: Int = 0
    public var makeToken: () -> [UInt8] = {
        (0..<Limits.wifiTokenBytes).map { _ in UInt8.random(in: 0...255) }
    }
    public var makeNonce: () -> [UInt8] = {
        (0..<Limits.nonceBytes).map { _ in UInt8.random(in: 0...255) }
    }
    /// 切断後の再試行。既定はグローバルキュー。テストは空の関数に差し替える。
    public var scheduleRetry: @Sendable (UInt64, @escaping @Sendable () -> Void) -> Void = { delayMs, work in
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(Int(delayMs)), execute: work)
    }

    private let radio: PhoneRadio
    private let lock = NSRecursiveLock()
    private let statusIn = InboundAssembler()
    private let payloadIn = InboundAssembler()
    private var storedPhase: LinkPhase = .idle
    private var outSeq = 1
    private var retryAttempt = 0
    private var nonce: [UInt8] = []
    private var pendingAck: [Int: MessageType] = [:]
    private var stopped = false

    public init(radio: PhoneRadio) {
        self.radio = radio
        radio.sink = self
    }

    public func start() {
        lock.lock()
        stopped = false
        if storedPhase == .idle || storedPhase == .backoff {
            advanceLocked(.start)
        }
        lock.unlock()
        radio.prepare(mtu: Limits.preferredAttMtu) { [weak self] mtu in
            self?.didPrepare(mtu)
        }
    }

    public func stop() {
        lock.lock()
        stopped = true
        advanceLocked(.stop)
        if storedPhase != .idle {
            storedPhase = .idle
            delegate?.phoneSession(self, phase: storedPhase)
        }
        lock.unlock()
        radio.close()
    }

    public func linkLost() {
        radioDidDrop()
    }

    public func setMode(_ mode: HudMode, paper: PaperProfile) throws {
        try send(.setMode(SetMode(mode: mode, paper: paper)), channel: .command)
    }

    public func sendExemplar(_ meta: ExemplarMeta, packs: [StrokePack]) throws {
        try send(.exemplarMeta(meta), channel: .command)
        for pack in packs {
            try send(.strokePack(pack), channel: .payload)
        }
    }

    public func calibrateStart(_ method: CalibMethod, timeoutSec: Int) throws {
        try send(.calibrateStart(CalibrateStart(method: method, timeoutSec: timeoutSec)), channel: .command)
    }

    public func calibrateCommit(_ commit: CalibrateCommit) throws {
        try send(.calibrateCommit(commit), channel: .command)
    }

    public func captureNow(_ reason: CaptureReason, quality: Int) throws {
        try send(.captureNow(try CaptureNow(reason: reason, jpegQuality: quality)), channel: .command)
    }

    public func showAdvice(_ lines: [String]) throws {
        try send(.showAdvice(try ShowAdvice(lines: lines)), channel: .command)
    }

    public func clearOverlay(_ mask: Int) throws {
        try send(.clearOverlay(ClearOverlay(mask: mask)), channel: .command)
    }

    public func beginWifi(ipv4: [UInt8], port: Int, purpose: WifiPurpose) throws -> [UInt8] {
        let token = makeToken()
        let message = try BeginWifiSession(ipv4: ipv4, port: port, token: token, purpose: purpose)
        try send(.beginWifiSession(message), channel: .command)
        return token
    }

    public func endWifi(reason: Int) throws {
        try send(.endWifiSession(EndWifiSession(reason: reason)), channel: .command)
    }

    public func sendTouch(_ action: TouchAction, elapsedMs: UInt64) throws {
        try send(.touch(TouchReport(action: action, elapsedMs: elapsedMs)), channel: .touch)
    }

    public func ping(_ cookie: UInt64) throws {
        try send(.ping(Ping(cookie: cookie)), channel: .command)
    }

    public func setHud(brightness: Int, lineWidthPx: Int, showCrosshair: Bool) throws {
        try send(
            .setHud(try SetHud(brightness: brightness, lineWidthPx: lineWidthPx, showCrosshair: showCrosshair)),
            channel: .command
        )
    }

    public func sleepHint(displayOffDelayMs: Int) throws {
        try send(.sleepHint(SleepHint(displayOffDelayMs: displayOffDelayMs)), channel: .command)
    }

    public func radioDidNotifyStatus(_ bytes: [UInt8]) {
        let events = absorb(bytes, assembler: statusIn)
        deliver(events)
    }

    public func radioDidNotifyPayload(_ bytes: [UInt8]) {
        let events = absorb(bytes, assembler: payloadIn)
        deliver(events)
    }

    public func radioDidDrop() {
        lock.lock()
        guard !stopped else {
            lock.unlock()
            return
        }
        advanceLocked(.linkLost)
        let delay = RetrySchedule.delayMs(attempt: retryAttempt)
        retryAttempt += 1
        lock.unlock()
        radio.close()
        scheduleRetry(delay) { [weak self] in
            self?.retry()
        }
    }

    private func retry() {
        lock.lock()
        let go = !stopped && storedPhase == .backoff
        if go { advanceLocked(.retry) }
        lock.unlock()
        if go { start() }
    }

    private func didPrepare(_ mtu: Int) {
        lock.lock()
        attMtu = mtu
        if storedPhase == .discovering { advanceLocked(.peerFound) }
        if storedPhase == .connecting { advanceLocked(.gattConnected) }
        if mtu < Limits.minAttMtu {
            advanceLocked(.helloRejected)
            lock.unlock()
            return
        }
        lock.unlock()
        radio.readSessionInfo { [weak self] bytes in
            self?.noteSessionInfo(bytes)
            self?.sendHello()
        }
    }

    private func noteSessionInfo(_ bytes: [UInt8]) {
        guard case .message(let inbound) = InboundAssembler().push(bytes),
              case .sessionInfo(let info) = inbound.message
        else { return }
        deliver([.sessionInfo(info)])
    }

    private func sendHello() {
        let fresh = makeNonce()
        lock.lock()
        guard storedPhase == .negotiating, !stopped else {
            lock.unlock()
            return
        }
        nonce = fresh
        let hello: LinkMessage
        do {
            hello = .hello(try Hello(
                version: Limits.protocolVersion,
                requestedMtu: attMtu,
                role: .phoneCentral,
                nonce: fresh
            ))
        } catch {
            lock.unlock()
            return
        }
        let frames: [[UInt8]]
        do {
            frames = try takeFramesLocked(hello)
        } catch {
            lock.unlock()
            return
        }
        lock.unlock()
        for frame in frames {
            radio.writeCommand(frame)
        }
    }

    private func send(_ message: LinkMessage, channel: Channel) throws {
        lock.lock()
        guard canSendLocked, !stopped else {
            lock.unlock()
            throw ProtocolError(.busy, "phase")
        }
        let seq = outSeq
        let frames: [[UInt8]]
        do {
            frames = try takeFramesLocked(message)
        } catch {
            lock.unlock()
            throw error
        }
        if Protocol.defaultFlags(message) & FrameFlags.ackRequest != 0 {
            pendingAck[seq] = message.type
        }
        lock.unlock()
        write(frames, channel: channel)
    }

    private func takeFramesLocked(_ message: LinkMessage) throws -> [[UInt8]] {
        let outbound = try Protocol.encode(message, sequence: outSeq, attMtu: attMtu)
        outSeq = outbound.nextSequence
        return outbound.frames
    }

    private func write(_ frames: [[UInt8]], channel: Channel) {
        for frame in frames {
            switch channel {
            case .command: radio.writeCommand(frame)
            case .touch: radio.writeTouch(frame)
            case .payload: radio.writePayload(frame)
            }
        }
    }

    private func absorb(_ bytes: [UInt8], assembler: InboundAssembler) -> [PhoneEvent] {
        lock.lock()
        defer { lock.unlock() }
        switch assembler.push(bytes) {
        case .pending:
            return []
        case .rejected(let code, let detail):
            let clipped = String(detail.prefix(Limits.maxErrorBytes))
            if let report = try? ErrorReport(code: code, detail: clipped) {
                return [.error(report)]
            }
            return []
        case .message(let inbound):
            return eventsLocked(inbound.message)
        }
    }

    private func eventsLocked(_ message: LinkMessage) -> [PhoneEvent] {
        switch message {
        case .helloAck(let ack):
            if ack.accept && ack.nonce == nonce {
                advanceLocked(.helloOk)
                retryAttempt = 0
            } else {
                advanceLocked(.helloRejected)
            }
            return [.helloAck(ack)]
        case .state(let state):
            return [.state(state)]
        case .touch(let touch):
            return [.touch(touch)]
        case .captureReady(let ready):
            return [.captureReady(ready)]
        case .correctionPack(let pack):
            return [.correction(pack)]
        case .ack(let ack):
            pendingAck[ack.ackSequence] = nil
            if ack.ackedType == .beginWifiSession && ack.status == .ok {
                advanceLocked(.beginWifi)
            }
            if ack.ackedType == .endWifiSession && ack.status == .ok {
                leaveWifiLocked()
            }
            return [.ack(ack)]
        case .error(let error):
            return [.error(error)]
        case .wifiReady(let ready):
            if ready.ok { advanceLocked(.socketUp) }
            return [.wifiReady(ready)]
        case .wifiClosed(let closed):
            leaveWifiLocked()
            return [.wifiClosed(closed)]
        case .pong(let pong):
            return [.pong(pong)]
        default:
            return []
        }
    }

    private var canSendLocked: Bool {
        switch storedPhase {
        case .bleReady, .wifiWake, .transfer, .wifiTeardown: true
        default: false
        }
    }

    private func leaveWifiLocked() {
        switch storedPhase {
        case .wifiWake:
            advanceLocked(.stop)
        case .transfer:
            advanceLocked(.transferFinished)
        default:
            break
        }
        if storedPhase == .wifiTeardown { advanceLocked(.wifiTornDown) }
    }

    private func advanceLocked(_ signal: LinkSignal) {
        let step = LinkStateMachine.step(phase: storedPhase, signal: signal)
        guard step.accepted else { return }
        storedPhase = step.phase
        delegate?.phoneSession(self, phase: storedPhase)
    }

    private func deliver(_ events: [PhoneEvent]) {
        guard let delegate, !events.isEmpty else { return }
        for event in events {
            delegate.phoneSession(self, event: event)
        }
    }

    private enum Channel {
        case command
        case touch
        case payload
    }
}
