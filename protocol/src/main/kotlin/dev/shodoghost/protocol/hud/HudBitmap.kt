package dev.shodoghost.protocol.hud

import dev.shodoghost.protocol.HudCanvas
import dev.shodoghost.protocol.HudPalette
import dev.shodoghost.protocol.Limits

/** 右と下は含まない。 */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(x: Int, y: Int): Boolean = x >= left && y >= top && x < right && y < bottom

    init {
        require(right >= left && bottom >= top)
    }
}

/**
 * 文字はセーフ、手本の線は光学クリップ。並置カードは文字セーフの上部。
 * 状態の1行は接続の表示で、助言の2行枠とは別。
 */
object HudLayout {
    const val STATUS_PX: Int = 32
    const val ADVICE_LINE_PX: Int = 28
    const val GAP_PX: Int = 4
    const val MARK_PX: Int = 8

    val optical = PixelRect(
        HudCanvas.OPTICAL_INSET_X,
        HudCanvas.OPTICAL_INSET_Y,
        HudCanvas.WIDTH - HudCanvas.OPTICAL_INSET_X,
        HudCanvas.HEIGHT - HudCanvas.OPTICAL_INSET_Y,
    )
    val text = PixelRect(
        HudCanvas.TEXT_INSET_X,
        HudCanvas.TEXT_INSET_Y,
        HudCanvas.WIDTH - HudCanvas.TEXT_INSET_X,
        HudCanvas.HEIGHT - HudCanvas.TEXT_INSET_Y,
    )

    fun status(): PixelRect = PixelRect(text.left, text.top, text.right, text.top + STATUS_PX)

    fun advice(): PixelRect {
        val height = ADVICE_LINE_PX * Limits.MAX_ADVICE_LINES + GAP_PX
        return PixelRect(text.left, text.bottom - height, text.right, text.bottom)
    }

    fun card(): PixelRect = PixelRect(text.left, status().bottom + GAP_PX, text.right, advice().top - GAP_PX)

    fun adviceLine(index: Int): PixelRect {
        require(index in 0 until Limits.MAX_ADVICE_LINES)
        val band = advice()
        val slot = band.height / Limits.MAX_ADVICE_LINES
        val top = band.top + index * slot
        return PixelRect(band.left, top, band.right, top + slot)
    }
}

/** 1枚の 640×480。値はパレットの RGB。アルファは持たない。 */
class HudBitmap(val width: Int, val height: Int) {
    val pixels: IntArray = IntArray(width * height) { HudPalette.UNLIT }

    fun get(x: Int, y: Int): Int = pixels[y * width + x]

    fun set(x: Int, y: Int, rgb: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        pixels[y * width + x] = rgb and 0xFFFFFF
    }

    fun copy(): HudBitmap {
        val out = HudBitmap(width, height)
        pixels.copyInto(out.pixels)
        return out
    }

    fun count(rgb: Int): Int = pixels.count { it == (rgb and 0xFFFFFF) }
}

/** 同じ 640×480 を左右へ並べる。SDK が無いときの [dev.shodoghost.protocol.BinocularPresenter]。 */
fun mirrorEyes(src: HudBitmap): HudBitmap {
    require(src.width == HudCanvas.WIDTH && src.height == HudCanvas.HEIGHT)
    val out = HudBitmap(src.width * 2, src.height)
    for (y in 0 until src.height) {
        for (x in 0 until src.width) {
            val pixel = src.get(x, y)
            out.set(x, y, pixel)
            out.set(x + src.width, y, pixel)
        }
    }
    return out
}
