import Foundation

/// 永字八法の固定骨格。検出はしない。用紙座標は 0...10000、線幅は 2px。
public enum EiExemplar {
    public static let id: UInt64 = 1

    public static func strokes() throws -> [Stroke] {
        try paths.map { path in
            let points = try path.map { try PaperPoint(x: $0.0, y: $0.1) }
            return try Stroke(layer: .skeleton, widthPx: Limits.minLinePx, points: points)
        }
    }

    /// 側、勒、努、趯、策、掠、啄、磔。原点は用紙の左上。
    private static let paths: [[(Int, Int)]] = [
        [(5000, 1500), (5600, 2100)],
        [(2600, 2800), (7600, 3000)],
        [(5400, 2800), (4700, 8400)],
        [(4700, 8400), (6400, 7600)],
        [(3000, 4600), (5000, 4000)],
        [(5000, 4400), (2400, 6800)],
        [(5600, 4800), (7000, 4400)],
        [(5600, 5400), (8000, 8000)],
    ]
}
