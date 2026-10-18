package dev.shodoghost.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 用紙座標（0..10000）を HUD 正規化座標へ写す平面射影。
 * 4点対応の DLT。ピクセル化は最後に一度だけ丸める。
 */
class Homography private constructor(private val h: DoubleArray) {
    fun map(point: PaperPoint): HudPoint {
        val x = point.x.toDouble()
        val y = point.y.toDouble()
        val w = h[6] * x + h[7] * y + h[8]
        if (abs(w) < 1e-9) {
            throw ProtocolException(ErrorCode.UNSUPPORTED_MODE, "homography point at infinity")
        }
        val nx = (h[0] * x + h[1] * y + h[2]) / w
        val ny = (h[3] * x + h[4] * y + h[5]) / w
        val ix = nx.roundToInt().coerceIn(0, Limits.NORM_MAX)
        val iy = ny.roundToInt().coerceIn(0, Limits.NORM_MAX)
        return HudPoint(ix, iy)
    }

    companion object {
        fun fromPaperQuad(quad: PaperQuad): Homography {
            val sources = arrayOf(
                0.0 to 0.0,
                Limits.NORM_MAX.toDouble() to 0.0,
                Limits.NORM_MAX.toDouble() to Limits.NORM_MAX.toDouble(),
                0.0 to Limits.NORM_MAX.toDouble(),
            )
            val dest = arrayOf(
                quad.topLeft.x.toDouble() to quad.topLeft.y.toDouble(),
                quad.topRight.x.toDouble() to quad.topRight.y.toDouble(),
                quad.bottomRight.x.toDouble() to quad.bottomRight.y.toDouble(),
                quad.bottomLeft.x.toDouble() to quad.bottomLeft.y.toDouble(),
            )
            val a = Array(8) { DoubleArray(8) }
            val b = DoubleArray(8)
            for (i in 0 until 4) {
                val (sx, sy) = sources[i]
                val (dx, dy) = dest[i]
                val r0 = i * 2
                val r1 = r0 + 1
                a[r0][0] = sx
                a[r0][1] = sy
                a[r0][2] = 1.0
                a[r0][6] = -sx * dx
                a[r0][7] = -sy * dx
                b[r0] = dx
                a[r1][3] = sx
                a[r1][4] = sy
                a[r1][5] = 1.0
                a[r1][6] = -sx * dy
                a[r1][7] = -sy * dy
                b[r1] = dy
            }
            val solved = try {
                solve(a, b)
            } catch (ex: IllegalArgumentException) {
                throw ProtocolException(ErrorCode.UNSUPPORTED_MODE, "degenerate paper quad")
            }
            val h = DoubleArray(9)
            solved.copyInto(h)
            h[8] = 1.0
            return Homography(h)
        }

        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = 8
            val m = Array(n) { r ->
                DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] }
            }
            for (col in 0 until n) {
                var pivot = col
                for (row in col + 1 until n) {
                    if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
                }
                if (abs(m[pivot][col]) < 1e-8) {
                    throw IllegalArgumentException("singular")
                }
                val swap = m[col]
                m[col] = m[pivot]
                m[pivot] = swap
                val div = m[col][col]
                for (c in col..n) m[col][c] /= div
                for (row in 0 until n) {
                    if (row == col) continue
                    val factor = m[row][col]
                    if (factor == 0.0) continue
                    for (c in col..n) m[row][c] -= factor * m[col][c]
                }
            }
            return DoubleArray(n) { m[it][n] }
        }
    }
}
