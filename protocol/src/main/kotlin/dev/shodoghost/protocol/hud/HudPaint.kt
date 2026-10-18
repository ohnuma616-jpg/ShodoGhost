package dev.shodoghost.protocol.hud

import dev.shodoghost.protocol.HudPalette
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs

/** アンチエイリアスなしの太線。クリップの外の画素は書かない。 */
fun paintSegment(
    bitmap: HudBitmap,
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    width: Int,
    color: Int,
    clip: PixelRect,
) {
    require(width >= 2)
    var x = x0
    var y = y0
    val dx = abs(x1 - x0)
    val dy = abs(y1 - y0)
    val sx = if (x0 < x1) 1 else -1
    val sy = if (y0 < y1) 1 else -1
    var err = dx - dy
    while (true) {
        stamp(bitmap, x, y, width, color, clip)
        if (x == x1 && y == y1) break
        val doubled = err * 2
        if (doubled > -dy) {
            err -= dy
            x += sx
        }
        if (doubled < dx) {
            err += dx
            y += sy
        }
    }
}

fun paintPolyline(
    bitmap: HudBitmap,
    points: List<Pair<Int, Int>>,
    width: Int,
    color: Int,
    clip: PixelRect,
) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        stamp(bitmap, points[0].first, points[0].second, width, color, clip)
        return
    }
    for (index in 0 until points.lastIndex) {
        val from = points[index]
        val to = points[index + 1]
        paintSegment(bitmap, from.first, from.second, to.first, to.second, width, color, clip)
    }
}

private fun stamp(bitmap: HudBitmap, x: Int, y: Int, width: Int, color: Int, clip: PixelRect) {
    val origin = width / 2
    for (dy in 0 until width) {
        for (dx in 0 until width) {
            val px = x - origin + dx
            val py = y - origin + dy
            if (clip.contains(px, py)) bitmap.set(px, py, color)
        }
    }
}

/** 未接続の印。文字セーフ左上の 8×8 に収まる 2px の折れ線。 */
fun paintWarningMark(bitmap: HudBitmap) {
    val x0 = HudLayout.text.left
    val y0 = HudLayout.text.top
    val rows = arrayOf(
        "xx......",
        "xx......",
        ".xx.....",
        ".xx.....",
        "..xx....",
        "..xx....",
        "...xx...",
        "...xx...",
    )
    for (row in rows.indices) {
        for (col in rows[row].indices) {
            if (rows[row][col] == 'x') bitmap.set(x0 + col, y0 + row, HudPalette.WARNING)
        }
    }
}

fun paintText(bitmap: HudBitmap, text: String, clip: PixelRect, color: Int) {
    if (text.isEmpty() || clip.width <= 0 || clip.height <= 0) return
    var size = 22
    var mask = rasterize(text, size)
    while (size > 14 && (mask.width > clip.width || mask.height > clip.height)) {
        size -= 2
        mask = rasterize(text, size)
    }
    val x = clip.left
    val y = clip.top + ((clip.height - mask.height).coerceAtLeast(0)) / 2
    for (sy in 0 until mask.height) {
        for (sx in 0 until mask.width) {
            if (!mask.on(sx, sy)) continue
            val px = x + sx
            val py = y + sy
            if (clip.contains(px, py)) bitmap.set(px, py, color)
        }
    }
}

private class TextMask(val width: Int, val height: Int, private val bits: BooleanArray) {
    fun on(x: Int, y: Int): Boolean = bits[y * width + x]
}

private fun rasterize(text: String, size: Int): TextMask {
    val font = HudTypeface.font.deriveFont(Font.PLAIN, size.toFloat())
    val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
    val metrics = probe.createGraphics().let { graphics ->
        graphics.font = font
        val measured = graphics.fontMetrics
        graphics.dispose()
        measured
    }
    val width = metrics.stringWidth(text).coerceAtLeast(1)
    val height = metrics.height.coerceAtLeast(1)
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    configureText(graphics)
    graphics.font = font
    graphics.color = java.awt.Color.WHITE
    graphics.drawString(text, 0, metrics.ascent)
    graphics.dispose()
    val bits = BooleanArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            bits[y * width + x] = (image.getRGB(x, y) and 0xFFFFFF) != 0
        }
    }
    return TextMask(width, height, bits)
}

private fun configureText(graphics: Graphics2D) {
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
}

internal object HudTypeface {
    private const val SAMPLE = "未接続なぞり並置添削待機起筆を垂直にハネ短く"

    val font: Font by lazy { load() }

    private fun load(): Font {
        System.setProperty("java.awt.headless", "true")
        val paths = listOfNotNull(
            System.getProperty("shodo.hud.font"),
            System.getenv("SHODO_HUD_FONT"),
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
        )
        for (path in paths) {
            val file = File(path)
            if (!file.isFile) continue
            val loaded = try {
                Font.createFont(Font.TRUETYPE_FONT, file)
            } catch (_: Exception) {
                continue
            }
            if (loaded.canDisplayUpTo(SAMPLE) == -1) return loaded
        }
        val logical = Font(Font.SANS_SERIF, Font.PLAIN, 16)
        if (logical.canDisplayUpTo(SAMPLE) == -1) return logical
        error("HUD font cannot draw Japanese. Set shodo.hud.font to a CJK font file")
    }
}
