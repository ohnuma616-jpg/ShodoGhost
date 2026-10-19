import Foundation

/// ワンショットの輝度から半紙の四隅を出す。OpenCV は使わない。
/// 明るい塊の端を用紙座標 0...10000 の四角形にする。机と紙の境が無いときは nil。
public enum PaperFinder {
    public static func quad(width: Int, height: Int, gray: [UInt8]) -> PaperQuad? {
        guard width >= 8, height >= 8, gray.count == width * height else { return nil }
        let grid = downsample(width: width, height: height, gray: gray, maxSide: 64)
        guard let threshold = otsu(grid.pixels), threshold < 250 else { return nil }
        var bright: [(Int, Int)] = []
        bright.reserveCapacity(grid.pixels.count / 3)
        for y in 0..<grid.height {
            for x in 0..<grid.width where grid.pixels[y * grid.width + x] > threshold {
                bright.append((x, y))
            }
        }
        let fraction = Double(bright.count) / Double(grid.pixels.count)
        guard fraction >= 0.08, fraction <= 0.85 else { return nil }
        let topLeft = bright.min { $0.0 + $0.1 < $1.0 + $1.1 }!
        let topRight = bright.max { $0.0 - $0.1 < $1.0 - $1.1 }!
        let bottomRight = bright.max { $0.0 + $0.1 < $1.0 + $1.1 }!
        let bottomLeft = bright.min { $0.0 - $0.1 < $1.0 - $1.1 }!
        let corners = [topLeft, topRight, bottomRight, bottomLeft].map { point in
            norm(point.0, of: grid.width, source: width)
        }
        let ys = [topLeft, topRight, bottomRight, bottomLeft].map { point in
            norm(point.1, of: grid.height, source: height)
        }
        guard let quad = try? PaperQuad(
            topLeft: HudPoint(x: corners[0], y: ys[0]),
            topRight: HudPoint(x: corners[1], y: ys[1]),
            bottomRight: HudPoint(x: corners[2], y: ys[2]),
            bottomLeft: HudPoint(x: corners[3], y: ys[3])
        ) else { return nil }
        guard separated(quad), area(quad) >= 1_500 * 1_500 else { return nil }
        return quad
    }

    private static func norm(_ index: Int, of bins: Int, source: Int) -> Int {
        let last = max(bins - 1, 1)
        let pixel = index * max(source - 1, 1) / last
        return min(Limits.normMax, max(0, pixel * Limits.normMax / max(source - 1, 1)))
    }

    private static func separated(_ quad: PaperQuad) -> Bool {
        let points = [quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft]
        for i in 0..<points.count {
            let a = points[i]
            let b = points[(i + 1) % points.count]
            let dx = a.x - b.x
            let dy = a.y - b.y
            if dx * dx + dy * dy < 800 * 800 { return false }
        }
        return quad.topLeft.x < quad.topRight.x && quad.bottomLeft.x < quad.bottomRight.x
            && quad.topLeft.y < quad.bottomLeft.y && quad.topRight.y < quad.bottomRight.y
    }

    private static func area(_ quad: PaperQuad) -> Int {
        let p = [quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft]
        var sum = 0
        for i in 0..<p.count {
            let a = p[i]
            let b = p[(i + 1) % p.count]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2
    }

    private static func downsample(width: Int, height: Int, gray: [UInt8], maxSide: Int) -> (width: Int, height: Int, pixels: [UInt8]) {
        let scale = max(1, max(width, height) / maxSide)
        let outW = max(1, width / scale)
        let outH = max(1, height / scale)
        var pixels = [UInt8](repeating: 0, count: outW * outH)
        for y in 0..<outH {
            for x in 0..<outW {
                pixels[y * outW + x] = gray[min(y * scale, height - 1) * width + min(x * scale, width - 1)]
            }
        }
        return (outW, outH, pixels)
    }

    private static func otsu(_ pixels: [UInt8]) -> Int? {
        var hist = [Int](repeating: 0, count: 256)
        for pixel in pixels { hist[Int(pixel)] += 1 }
        let total = pixels.count
        var sum = 0
        for tone in 0..<256 { sum += tone * hist[tone] }
        var sumBackground = 0
        var weightBackground = 0
        var best = 0.0
        var threshold = 0
        for tone in 0..<256 {
            weightBackground += hist[tone]
            if weightBackground == 0 { continue }
            let weightForeground = total - weightBackground
            if weightForeground == 0 { break }
            sumBackground += tone * hist[tone]
            let meanBackground = Double(sumBackground) / Double(weightBackground)
            let meanForeground = Double(sum - sumBackground) / Double(weightForeground)
            let variance = Double(weightBackground) * Double(weightForeground) * (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if variance > best {
                best = variance
                threshold = tone
            }
        }
        return best > 0 ? threshold : nil
    }
}
