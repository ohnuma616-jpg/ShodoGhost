import Foundation

public protocol PlaneAnchorAdapter: AnyObject {
    var available: Bool { get }
    func enable()
    func disable()
    func currentQuad() -> PaperQuad?
}

public struct CapturedStill: Equatable, Sendable {
    public let jpeg: [UInt8]
    public let width: Int
    public let height: Int
    public let cameraReleased: Bool

    public init(jpeg: [UInt8], width: Int, height: Int, cameraReleased: Bool) throws {
        guard cameraReleased else { throw ProtocolError(.badFrame, "camera still open") }
        guard !jpeg.isEmpty, jpeg.count <= Limits.maxJpegBytes else {
            throw ProtocolError(.badFrame, "jpeg")
        }
        guard width > 0, height > 0 else { throw ProtocolError(.badFrame, "size") }
        self.jpeg = jpeg
        self.width = width
        self.height = height
        self.cameraReleased = cameraReleased
    }
}

public protocol CameraCaptureAdapter: AnyObject {
    func captureStill(jpegQuality: Int) throws -> CapturedStill
}

public struct HudFrame: Equatable, Sendable {
    public let strokes: [Stroke]
    public let quad: PaperQuad?
    public let advice: [String]
    public let showCrosshair: Bool
    public let warning: String?
    public let lineWidthPx: Int

    public init(
        strokes: [Stroke],
        quad: PaperQuad?,
        advice: [String],
        showCrosshair: Bool,
        warning: String?,
        lineWidthPx: Int
    ) throws {
        guard (Limits.minLinePx...Limits.maxLinePx).contains(lineWidthPx) else {
            throw ProtocolError(.badFrame, "line width")
        }
        guard advice.count <= Limits.maxAdviceLines else { throw ProtocolError(.badFrame, "advice") }
        if let warning {
            guard Array(warning.utf8).count <= Limits.maxAdviceBytes else {
                throw ProtocolError(.badFrame, "warning")
            }
        }
        self.strokes = strokes
        self.quad = quad
        self.advice = advice
        self.showCrosshair = showCrosshair
        self.warning = warning
        self.lineWidthPx = lineWidthPx
    }
}

public protocol BinocularPresenter: AnyObject {
    func present(_ frame: HudFrame)
}

public protocol TempleInputSink: AnyObject {
    func onAction(_ action: TouchAction, elapsedMs: UInt64)
}

public struct CritiqueRequest: Equatable, Sendable {
    public let jpeg: [UInt8]
    public let exemplarId: UInt64
    public let strokes: [Stroke]
    public let paper: PaperProfile
    public let widthMm: Int
    public let heightMm: Int

    public init(jpeg: [UInt8], exemplarId: UInt64, strokes: [Stroke], paper: PaperProfile, widthMm: Int, heightMm: Int) {
        self.jpeg = jpeg
        self.exemplarId = exemplarId
        self.strokes = strokes
        self.paper = paper
        self.widthMm = widthMm
        self.heightMm = heightMm
    }
}

public struct CritiqueResult: Equatable, Sendable {
    public let critiqueId: UInt64
    public let strokes: [Stroke]
    public let advice: [String]

    public init(critiqueId: UInt64, strokes: [Stroke], advice: [String]) throws {
        guard !advice.isEmpty, advice.count <= Limits.maxAdviceLines else {
            throw ProtocolError(.badFrame, "advice")
        }
        self.critiqueId = critiqueId
        self.strokes = strokes
        self.advice = advice
    }
}

public protocol AnalysisBackend: AnyObject {
    func critique(_ request: CritiqueRequest) throws -> CritiqueResult
}

public func paperCrosshair(widthPx: Int = Limits.minLinePx) throws -> [Stroke] {
    let mid = Limits.normMax / 2
    return [
        try Stroke(layer: .crosshair, widthPx: widthPx, points: [
            try PaperPoint(x: mid, y: 0),
            try PaperPoint(x: mid, y: Limits.normMax),
        ]),
        try Stroke(layer: .crosshair, widthPx: widthPx, points: [
            try PaperPoint(x: 0, y: mid),
            try PaperPoint(x: Limits.normMax, y: mid),
        ]),
    ]
}
