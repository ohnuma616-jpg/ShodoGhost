package dev.shodoghost.protocol.hud

import dev.shodoghost.protocol.HudFrame
import dev.shodoghost.protocol.HudMode
import dev.shodoghost.protocol.HudPoint
import dev.shodoghost.protocol.InkLayer
import dev.shodoghost.protocol.Limits
import dev.shodoghost.protocol.PaperPoint
import dev.shodoghost.protocol.PaperQuad
import dev.shodoghost.protocol.Stroke

/** 確認用の3本。シアン、緑、赤。左右へ複製する前の1枚。 */
fun bringUpFrame(advice: List<String> = emptyList(), warning: String? = null): HudFrame {
    return HudFrame(
        strokes = listOf(
            Stroke(InkLayer.SKELETON, 4, line(2500)),
            Stroke(InkLayer.CONTOUR, 4, line(5000)),
            Stroke(InkLayer.CORRECTION, 4, line(7500)),
        ),
        quad = PaperQuad(
            HudPoint(0, 0),
            HudPoint(Limits.NORM_MAX, 0),
            HudPoint(Limits.NORM_MAX, Limits.NORM_MAX),
            HudPoint(0, Limits.NORM_MAX),
        ),
        advice = advice,
        showCrosshair = false,
        warning = warning,
        lineWidthPx = 4,
        mode = HudMode.REVIEW,
    )
}

private fun line(y: Int) = listOf(PaperPoint(800, y), PaperPoint(9200, y))
