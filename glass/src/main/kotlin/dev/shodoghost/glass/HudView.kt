package dev.shodoghost.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import dev.shodoghost.protocol.hud.HudBitmap

/** 1280×480 の複製済みビットマップを、拡大補間なしで全面に出す。 */
class HudView(context: Context) : View(context) {
    private var image: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false }
    private val dest = Rect()

    fun setPanel(panel: HudBitmap) {
        val argb = IntArray(panel.pixels.size) { index ->
            (0xFF shl 24) or (panel.pixels[index] and 0xFFFFFF)
        }
        val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb, 0, panel.width, 0, 0, panel.width, panel.height)
        image?.recycle()
        image = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bitmap = image ?: return
        dest.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, dest, paint)
    }
}
