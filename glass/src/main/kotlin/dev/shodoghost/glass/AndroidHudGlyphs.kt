package dev.shodoghost.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import dev.shodoghost.protocol.hud.HudGlyphMask
import dev.shodoghost.protocol.hud.HudGlyphs
import java.io.File

/** グラスのシステムフォントで HUD の文字を二値にする。 */
object AndroidHudGlyphs : HudGlyphs {
    override fun raster(text: String, sizePx: Int): HudGlyphMask {
        val paint = Paint().apply {
            isAntiAlias = false
            isSubpixelText = false
            typeface = face()
            textSize = sizePx.toFloat()
            color = Color.WHITE
        }
        val width = paint.measureText(text).toInt().coerceAtLeast(1)
        val metrics = paint.fontMetricsInt
        val height = (metrics.descent - metrics.ascent).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, 0f, -metrics.ascent.toFloat(), paint)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val bits = BooleanArray(pixels.size) { index -> (pixels[index] and 0xFFFFFF) != 0 }
        return HudGlyphMask(width, height, bits)
    }

    private val face: Typeface by lazy { load() }

    private fun face(): Typeface = face

    private fun load(): Typeface {
        val fonts = File("/system/fonts").listFiles().orEmpty()
        val chosen = fonts
            .filter { it.isFile }
            .sortedBy { rank(it.name.lowercase()) }
            .firstOrNull { rank(it.name.lowercase()) < 9 }
        if (chosen != null) {
            try {
                return Typeface.createFromFile(chosen)
            } catch (_: Exception) {
                // 既定のサンセリフへ落とす。線は文字が無くても出る。
            }
        }
        return Typeface.SANS_SERIF
    }

    private fun rank(name: String): Int = when {
        "notosanscjk" in name && "regular" in name -> 0
        "notosansjp" in name && "regular" in name -> 1
        "droidsansfallback" in name -> 2
        "cjk" in name && "regular" in name -> 3
        "fallback" in name -> 4
        "cjk" in name || name.contains("jp") -> 5
        else -> 9
    }
}
