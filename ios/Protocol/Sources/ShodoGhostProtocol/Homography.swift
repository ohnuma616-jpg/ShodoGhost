import Foundation

public struct Homography: Sendable {
    private let h: [Double]

    private init(h: [Double]) {
        self.h = h
    }

    public func map(_ point: PaperPoint) throws -> HudPoint {
        let x = Double(point.x)
        let y = Double(point.y)
        let w = h[6] * x + h[7] * y + h[8]
        if abs(w) < 1e-9 {
            throw ProtocolError(.unsupportedMode, "homography point at infinity")
        }
        let nx = (h[0] * x + h[1] * y + h[2]) / w
        let ny = (h[3] * x + h[4] * y + h[5]) / w
        let ix = min(max(roundToInt(nx), 0), Limits.normMax)
        let iy = min(max(roundToInt(ny), 0), Limits.normMax)
        return try HudPoint(x: ix, y: iy)
    }

    public static func fromPaperQuad(_ quad: PaperQuad) throws -> Homography {
        let norm = Double(Limits.normMax)
        let sources = [(0.0, 0.0), (norm, 0.0), (norm, norm), (0.0, norm)]
        let dest = [
            (Double(quad.topLeft.x), Double(quad.topLeft.y)),
            (Double(quad.topRight.x), Double(quad.topRight.y)),
            (Double(quad.bottomRight.x), Double(quad.bottomRight.y)),
            (Double(quad.bottomLeft.x), Double(quad.bottomLeft.y)),
        ]
        var a = Array(repeating: Array(repeating: 0.0, count: 8), count: 8)
        var b = Array(repeating: 0.0, count: 8)
        for i in 0..<4 {
            let sx = sources[i].0
            let sy = sources[i].1
            let dx = dest[i].0
            let dy = dest[i].1
            let r0 = i * 2
            let r1 = r0 + 1
            a[r0][0] = sx
            a[r0][1] = sy
            a[r0][2] = 1
            a[r0][6] = -sx * dx
            a[r0][7] = -sy * dx
            b[r0] = dx
            a[r1][3] = sx
            a[r1][4] = sy
            a[r1][5] = 1
            a[r1][6] = -sx * dy
            a[r1][7] = -sy * dy
            b[r1] = dy
        }
        let solved: [Double]
        do {
            solved = try solve(a, b)
        } catch {
            throw ProtocolError(.unsupportedMode, "degenerate paper quad")
        }
        var h = solved
        h.append(1)
        return Homography(h: h)
    }

    private static func solve(_ a: [[Double]], _ b: [Double]) throws -> [Double] {
        let n = 8
        var m = (0..<n).map { r in
            (0...n).map { c in c < n ? a[r][c] : b[r] }
        }
        for col in 0..<n {
            var pivot = col
            for row in (col + 1)..<n where abs(m[row][col]) > abs(m[pivot][col]) {
                pivot = row
            }
            if abs(m[pivot][col]) < 1e-8 { throw ProtocolError(.unsupportedMode, "singular") }
            m.swapAt(col, pivot)
            let div = m[col][col]
            for c in col...n { m[col][c] /= div }
            for row in 0..<n where row != col {
                let factor = m[row][col]
                if factor == 0 { continue }
                for c in col...n { m[row][c] -= factor * m[col][c] }
            }
        }
        return (0..<n).map { m[$0][n] }
    }
}

private func roundToInt(_ value: Double) -> Int {
    Int(value.rounded(.toNearestOrAwayFromZero))
}
