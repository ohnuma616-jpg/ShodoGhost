package dev.shodoghost.protocol.hud

import dev.shodoghost.protocol.BinocularPresenter
import dev.shodoghost.protocol.Homography
import dev.shodoghost.protocol.HudCanvas
import dev.shodoghost.protocol.HudFrame
import dev.shodoghost.protocol.HudMode
import dev.shodoghost.protocol.HudPalette
import dev.shodoghost.protocol.InkLayer
import dev.shodoghost.protocol.Limits
import dev.shodoghost.protocol.PaperPoint
import dev.shodoghost.protocol.PaperProfile
import dev.shodoghost.protocol.PaperQuad
import dev.shodoghost.protocol.Stroke
import dev.shodoghost.protocol.paperCrosshair
import kotlin.math.min
import kotlin.math.roundToInt

private val PALETTE = intArrayOf(
    HudPalette.UNLIT,
    HudPalette.EXEMPLAR,
    HudPalette.CONTOUR,
    HudPalette.CORRECTION,
    HudPalette.TEXT,
    HudPalette.WARNING,
)

fun renderHud(frame: HudFrame): HudBitmap {
    val bitmap = HudBitmap(HudCanvas.WIDTH, HudCanvas.HEIGHT)
    val strokes = frame.strokes.filter { it.layer != InkLayer.CROSSHAIR }
    val showHair = frame.showCrosshair && frame.quad != null && frame.mode != HudMode.IDLE
    for (stroke in strokes) {
        if (stroke.layer == InkLayer.CORRECTION) continue
        paintPlaced(bitmap, stroke, frame)
    }
    if (showHair) {
        for (stroke in paperCrosshair(frame.lineWidthPx)) {
            paintPlaced(bitmap, stroke, frame)
        }
    }
    for (stroke in strokes) {
        if (stroke.layer == InkLayer.CORRECTION) paintPlaced(bitmap, stroke, frame)
    }
    val warning = frame.warning
    val status = if (warning != null) {
        paintWarningMark(bitmap)
        warning to HudPalette.WARNING
    } else {
        statusLabel(frame.mode) to HudPalette.TEXT
    }
    val statusClip = if (warning != null) {
        val band = HudLayout.status()
        PixelRect(band.left + HudLayout.MARK_PX + 4, band.top, band.right, band.bottom)
    } else {
        HudLayout.status()
    }
    paintText(bitmap, status.first, statusClip, status.second)
    frame.advice.forEachIndexed { index, line ->
        if (index < Limits.MAX_ADVICE_LINES) {
            paintText(bitmap, line, HudLayout.adviceLine(index), HudPalette.TEXT)
        }
    }
    check(bitmap.pixels.all { it in PALETTE }) { "hud pixel left the palette" }
    return bitmap
}

/** 左右へ同じ絵を渡す。RayNeo の BaseMirrorActivity が無い実行環境用。 */
class DeskMirrorPresenter : BinocularPresenter {
    var panel: HudBitmap = HudBitmap(HudCanvas.WIDTH, HudCanvas.HEIGHT)
        private set
    var mirrored: HudBitmap = HudBitmap(HudCanvas.WIDTH * 2, HudCanvas.HEIGHT)
        private set

    override fun present(frame: HudFrame) {
        panel = renderHud(frame)
        mirrored = mirrorEyes(panel)
    }
}

private fun statusLabel(mode: HudMode): String = when (mode) {
    HudMode.IDLE -> "待機"
    HudMode.TRACE -> "なぞり"
    HudMode.SIDE_BY_SIDE -> "並置"
    HudMode.REVIEW -> "添削"
}

private fun paintPlaced(bitmap: HudBitmap, stroke: Stroke, frame: HudFrame) {
    val placed = place(stroke, frame) ?: return
    paintPolyline(bitmap, placed.points, stroke.widthPx, stroke.layer.inkColor(), placed.clip)
}

private data class Placed(val points: List<Pair<Int, Int>>, val clip: PixelRect)

private fun place(stroke: Stroke, frame: HudFrame): Placed? {
    if (stroke.layer == InkLayer.CORRECTION && frame.mode != HudMode.REVIEW) return null
    val quad = frame.quad
    if (stroke.layer == InkLayer.CROSSHAIR) {
        if (quad == null) return null
        return Placed(project(stroke.points, quad), HudLayout.optical)
    }
    return when (frame.mode) {
        HudMode.SIDE_BY_SIDE -> Placed(cardPoints(stroke.points, frame.paper), HudLayout.card())
        HudMode.TRACE, HudMode.REVIEW -> {
            if (quad == null) null else Placed(project(stroke.points, quad), HudLayout.optical)
        }
        HudMode.IDLE -> null
    }
}

private fun project(points: List<PaperPoint>, quad: PaperQuad): List<Pair<Int, Int>> {
    val homography = Homography.fromPaperQuad(quad)
    return points.map { point ->
        val mapped = homography.map(point)
        HudCanvas.normToPxX(mapped.x) to HudCanvas.normToPxY(mapped.y)
    }
}

private fun cardPoints(points: List<PaperPoint>, paper: PaperProfile): List<Pair<Int, Int>> {
    val dest = fitPaper(paper, HudLayout.card())
    return points.map { point ->
        val x = dest.left + (point.x.toLong() * (dest.width - 1) / Limits.NORM_MAX).toInt()
        val y = dest.top + (point.y.toLong() * (dest.height - 1) / Limits.NORM_MAX).toInt()
        x to y
    }
}

internal fun fitPaper(paper: PaperProfile, card: PixelRect): PixelRect {
    val scale = min(card.width.toDouble() / paper.widthMm, card.height.toDouble() / paper.heightMm)
    val width = (paper.widthMm * scale).roundToInt().coerceAtLeast(1)
    val height = (paper.heightMm * scale).roundToInt().coerceAtLeast(1)
    val left = card.left + (card.width - width) / 2
    val top = card.top + (card.height - height) / 2
    return PixelRect(left, top, left + width, top + height)
}

private fun InkLayer.inkColor(): Int = when (this) {
    InkLayer.SKELETON, InkLayer.CROSSHAIR -> HudPalette.EXEMPLAR
    InkLayer.CONTOUR -> HudPalette.CONTOUR
    InkLayer.CORRECTION -> HudPalette.CORRECTION
    InkLayer.MARKER -> HudPalette.WARNING
}
