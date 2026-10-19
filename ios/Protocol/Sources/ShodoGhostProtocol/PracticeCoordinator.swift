import Foundation

/// 練習画面が前面のときだけ JPEG を受け、Wi-Fi を閉じてから添削を BLE で返す。
public final class PracticeCoordinator {
    public let session: PhoneSession
    public let backend: AnalysisBackend
    public let link: WifiBurstLink
    public var ipv4: [UInt8] = [172, 20, 10, 1]
    public var exemplarId: UInt64 = 1
    public var paper: PaperProfile = .hanshi
    public var widthMm = 242
    public var heightMm = 333
    public var exemplarStrokes: [Stroke] = []
    public private(set) var foreground = false
    public private(set) var heldCapture: CaptureReady?
    public private(set) var lastCritique: CritiqueResult?

    public init(session: PhoneSession, backend: AnalysisBackend, link: WifiBurstLink) {
        self.session = session
        self.backend = backend
        self.link = link
    }

    public func setForeground(_ value: Bool) throws {
        foreground = value
        if !value {
            link.stop()
            return
        }
        if let held = heldCapture {
            heldCapture = nil
            try runBurst(held)
        }
    }

    public func noteCaptureReady(_ ready: CaptureReady) throws {
        if !foreground {
            heldCapture = ready
            return
        }
        try runBurst(ready)
    }

    public func requestCapture() throws {
        try session.captureNow(.phone, quality: 90)
    }

    public func commitManualQuad(_ quad: PaperQuad) throws {
        try session.calibrateCommit(
            CalibrateCommit(method: .manual, quad: quad, widthMm: widthMm, heightMm: heightMm)
        )
    }

    /// なぞりモードのあと、永の骨格を1パックで送る。撮影では消さない。
    public func sendLesson() throws {
        let strokes = try EiExemplar.strokes()
        exemplarStrokes = strokes
        exemplarId = EiExemplar.id
        try session.setMode(.trace, paper: paper)
        let points = strokes.reduce(0) { $0 + $1.points.count }
        let meta = ExemplarMeta(
            exemplarId: exemplarId,
            strokeCount: strokes.count,
            pointCount: points,
            layerMask: InkLayer.skeleton.rawValue
        )
        let pack = try StrokePack(
            exemplarOrCritiqueId: exemplarId,
            packIndex: 0,
            packCount: 1,
            strokes: strokes,
            correction: false
        )
        try session.sendExemplar(meta, packs: [pack])
    }

    private func runBurst(_ ready: CaptureReady) throws {
        try session.showAdvice(["添削中"])
        do {
            try link.listen(port: 0)
            let token = try session.beginWifi(ipv4: ipv4, port: link.boundPort, purpose: .uploadJpeg)
            let jpeg = try link.receive(
                token: token,
                expect: ready,
                timeoutSeconds: Double(Limits.wifiHardTimeoutMs) / 1000
            )
            link.stop()
            try session.endWifi(reason: 0)
            let result = try backend.critique(
                CritiqueRequest(
                    jpeg: jpeg,
                    exemplarId: exemplarId,
                    strokes: exemplarStrokes,
                    paper: paper,
                    widthMm: widthMm,
                    heightMm: heightMm
                )
            )
            lastCritique = result
            try session.showAdvice(result.advice)
            try session.sendCorrections(critiqueId: result.critiqueId, strokes: result.strokes)
        } catch {
            link.stop()
            leaveWifi()
            try? session.clearOverlay(OverlayBits.advice)
            throw error
        }
    }

    private func leaveWifi() {
        switch session.phase {
        case .wifiWake, .transfer, .wifiTeardown:
            try? session.endWifi(reason: 0)
        default:
            break
        }
    }
}

/// Python が無いときの添削。赤の一画と2行を返す。四隅の検出はしない。
public final class ManualCritique: AnalysisBackend {
    public init() {}

    public func critique(_ request: CritiqueRequest) throws -> CritiqueResult {
        let stroke = try Stroke(
            layer: .correction,
            widthPx: Limits.minLinePx,
            points: [
                try PaperPoint(x: 2000, y: 2000),
                try PaperPoint(x: 8000, y: 8000),
            ]
        )
        return try CritiqueResult(
            critiqueId: request.exemplarId,
            strokes: [stroke],
            advice: ["起筆を垂直に", "ハネを短く"]
        )
    }
}
