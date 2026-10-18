package dev.shodoghost.protocol.hud

import dev.shodoghost.protocol.HudCanvas
import dev.shodoghost.protocol.HudFrame
import dev.shodoghost.protocol.HudMode
import dev.shodoghost.protocol.HudPalette
import dev.shodoghost.protocol.HudPoint
import dev.shodoghost.protocol.InkLayer
import dev.shodoghost.protocol.Limits
import dev.shodoghost.protocol.PaperPoint
import dev.shodoghost.protocol.PaperProfile
import dev.shodoghost.protocol.PaperQuad
import dev.shodoghost.protocol.SetHud
import dev.shodoghost.protocol.Stroke
import dev.shodoghost.protocol.TouchAction
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class HudRendererTest {
    private val palette = setOf(
        HudPalette.UNLIT,
        HudPalette.EXEMPLAR,
        HudPalette.CONTOUR,
        HudPalette.CORRECTION,
        HudPalette.TEXT,
        HudPalette.WARNING,
    )

    @Test
    fun traceLineIsTwoPixelsThickAndInsideOptical() {
        val quad = axisQuad(1000, 9000)
        val frame = HudFrame(
            strokes = listOf(horizontalMidline()),
            quad = quad,
            advice = listOf("起筆を垂直に", "ハネを短く"),
            showCrosshair = false,
            warning = null,
            lineWidthPx = 2,
            mode = HudMode.TRACE,
        )
        val bitmap = renderHud(frame)
        assertPalette(bitmap)
        assertEquals(0, bitmap.count(HudPalette.CORRECTION))
        val column = (0 until bitmap.height).count { bitmap.get(320, it) == HudPalette.CONTOUR }
        assertTrue(column >= 2, "column thickness $column")
        assertInkInsideOptical(bitmap)
        assertChromeInsideText(bitmap)
        assertTrue(bitmap.count(HudPalette.TEXT) > 0)
        assertTrue(bitmap.count(HudPalette.UNLIT) > bitmap.pixels.size * 9 / 10)
        assertEquals(HudPalette.UNLIT, bitmap.get(320, 100))
    }

    @Test
    fun traceWithoutQuadDrawsNoLessonInk() {
        val bitmap = renderHud(
            HudFrame(
                strokes = listOf(horizontalMidline()),
                quad = null,
                advice = emptyList(),
                showCrosshair = true,
                warning = null,
                lineWidthPx = 2,
                mode = HudMode.TRACE,
            ),
        )
        assertEquals(0, bitmap.count(HudPalette.CONTOUR))
        assertEquals(0, bitmap.count(HudPalette.EXEMPLAR))
    }

    @Test
    fun sideBySideKeepsStrokesInCardAndProjectsCrosshair() {
        val quad = axisQuad(1500, 8500, 7800, 9500)
        val frame = HudFrame(
            strokes = listOf(
                Stroke(
                    InkLayer.CONTOUR,
                    2,
                    listOf(PaperPoint(0, 5000), PaperPoint(Limits.NORM_MAX, 5000)),
                ),
            ),
            quad = quad,
            advice = emptyList(),
            showCrosshair = true,
            warning = null,
            lineWidthPx = 2,
            mode = HudMode.SIDE_BY_SIDE,
        )
        val bitmap = renderHud(frame)
        assertPalette(bitmap)
        val card = HudLayout.card()
        forEach(bitmap) { x, y, pixel ->
            if (pixel == HudPalette.CONTOUR) assertTrue(card.contains(x, y), "contour $x,$y")
            if (pixel == HudPalette.EXEMPLAR) assertTrue(y >= card.bottom, "crosshair $x,$y")
        }
        assertTrue(bitmap.count(HudPalette.CONTOUR) > 0)
        assertTrue(bitmap.count(HudPalette.EXEMPLAR) > 0)
    }

    @Test
    fun reviewDrawsCorrectionOverThePaper() {
        val bitmap = renderHud(
            HudFrame(
                strokes = listOf(
                    horizontalMidline(),
                    Stroke(
                        InkLayer.CORRECTION,
                        2,
                        listOf(PaperPoint(5000, 0), PaperPoint(5000, Limits.NORM_MAX)),
                    ),
                ),
                quad = axisQuad(1000, 9000),
                advice = listOf("起筆を垂直に"),
                showCrosshair = false,
                warning = null,
                lineWidthPx = 2,
                mode = HudMode.REVIEW,
            ),
        )
        assertTrue(bitmap.count(HudPalette.CONTOUR) > 0)
        assertTrue(bitmap.count(HudPalette.CORRECTION) > 0)
        assertInkInsideOptical(bitmap)
        assertChromeInsideText(bitmap)
    }

    @Test
    fun disconnectedMarkAndStatusStayInTheTextSafe() {
        val bitmap = renderHud(
            HudFrame(
                strokes = emptyList(),
                quad = null,
                advice = emptyList(),
                showCrosshair = false,
                warning = "未接続",
                lineWidthPx = 2,
                mode = HudMode.IDLE,
            ),
        )
        assertPalette(bitmap)
        assertTrue(bitmap.count(HudPalette.WARNING) > 16)
        assertChromeInsideText(bitmap)
        assertEquals(0, bitmap.count(HudPalette.EXEMPLAR))
        val mark = (0 until HudLayout.MARK_PX).sumOf { row ->
            (0 until HudLayout.MARK_PX).count { col ->
                bitmap.get(HudLayout.text.left + col, HudLayout.text.top + row) == HudPalette.WARNING
            }
        }
        assertEquals(16, mark)
    }

    @Test
    fun bringUpFrameMirrorsThreeLines() {
        val presenter = DeskMirrorPresenter()
        presenter.present(bringUpFrame())
        val panel = presenter.panel
        assertTrue(panel.count(HudPalette.EXEMPLAR) > 20)
        assertTrue(panel.count(HudPalette.CONTOUR) > 20)
        assertTrue(panel.count(HudPalette.CORRECTION) > 20)
        val eyes = presenter.mirrored
        assertEquals(HudCanvas.WIDTH * 2, eyes.width)
        assertEquals(HudCanvas.HEIGHT, eyes.height)
        for (y in 0 until eyes.height) {
            for (x in 0 until HudCanvas.WIDTH) {
                assertEquals(eyes.get(x, y), eyes.get(x + HudCanvas.WIDTH, y))
            }
        }
    }

    @Test
    fun mirrorCopiesBothEyes() {
        val presenter = DeskMirrorPresenter()
        presenter.present(
            HudFrame(
                strokes = listOf(horizontalMidline()),
                quad = axisQuad(1000, 9000),
                advice = emptyList(),
                showCrosshair = false,
                warning = null,
                lineWidthPx = 4,
                mode = HudMode.TRACE,
            ),
        )
        val eyes = presenter.mirrored
        assertEquals(HudCanvas.WIDTH * 2, eyes.width)
        assertEquals(HudCanvas.HEIGHT, eyes.height)
        for (y in 0 until HudCanvas.HEIGHT) {
            for (x in 0 until HudCanvas.WIDTH) {
                assertEquals(presenter.panel.get(x, y), eyes.get(x, y))
                assertEquals(eyes.get(x, y), eyes.get(x + HudCanvas.WIDTH, y))
            }
        }
    }

    @Test
    fun directSegmentIsTwoPixelsEvenBeforeProjection() {
        val bitmap = HudBitmap(32, 32)
        paintSegment(bitmap, 4, 16, 28, 16, width = 2, color = HudPalette.EXEMPLAR, clip = PixelRect(0, 0, 32, 32))
        val thick = (0 until 32).count { bitmap.get(16, it) == HudPalette.EXEMPLAR }
        assertEquals(2, thick)
        assertEquals(0, bitmap.get(16, 0))
    }

    private fun horizontalMidline() = Stroke(
        InkLayer.CONTOUR,
        2,
        listOf(PaperPoint(0, 5000), PaperPoint(Limits.NORM_MAX, 5000)),
    )

    private fun axisQuad(x0: Int, x1: Int, y0: Int = 1000, y1: Int = 9000) = PaperQuad(
        HudPoint(x0, y0),
        HudPoint(x1, y0),
        HudPoint(x1, y1),
        HudPoint(x0, y1),
    )

    private fun assertPalette(bitmap: HudBitmap) {
        for (pixel in bitmap.pixels) assertTrue(pixel in palette, "color ${pixel.toString(16)}")
    }

    private fun assertInkInsideOptical(bitmap: HudBitmap) {
        val ink = setOf(HudPalette.EXEMPLAR, HudPalette.CONTOUR, HudPalette.CORRECTION)
        forEach(bitmap) { x, y, pixel ->
            if (pixel in ink) assertTrue(HudCanvas.opticalContains(x, y), "$pixel at $x,$y")
        }
    }

    private fun assertChromeInsideText(bitmap: HudBitmap) {
        val chrome = setOf(HudPalette.TEXT, HudPalette.WARNING)
        forEach(bitmap) { x, y, pixel ->
            if (pixel in chrome) assertTrue(HudCanvas.textSafeContains(x, y), "$pixel at $x,$y")
        }
    }

    private fun forEach(bitmap: HudBitmap, block: (Int, Int, Int) -> Unit) {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                block(x, y, bitmap.get(x, y))
            }
        }
    }
}

class PracticeHudTest {
    @Test
    fun tripleClickCyclesTraceAndSideBySide() {
        val hud = PracticeHud()
        hud.onAction(TouchAction.TRIPLE_CLICK, 0)
        assertEquals(HudMode.TRACE, hud.mode)
        hud.onAction(TouchAction.TRIPLE_CLICK, 0)
        assertEquals(HudMode.SIDE_BY_SIDE, hud.mode)
        hud.onAction(TouchAction.TRIPLE_CLICK, 0)
        assertEquals(HudMode.TRACE, hud.mode)
    }

    @Test
    fun longPressAndUncalibratedClick() {
        val hud = PracticeHud()
        hud.onAction(TouchAction.CLICK, 0)
        assertIs<HudEffect.StartCalibration>(hud.takeEffects().single())
        hud.onAction(TouchAction.LONG_PRESS, 400)
        assertIs<HudEffect.FinishAndCapture>(hud.takeEffects().single())
    }

    @Test
    fun doubleClickClearsOverlayAndSlidesChangePage() {
        val hud = PracticeHud()
        hud.connected = true
        hud.quad = PaperQuad(
            HudPoint(1000, 1000),
            HudPoint(9000, 1000),
            HudPoint(9000, 9000),
            HudPoint(1000, 9000),
        )
        hud.onAction(TouchAction.TRIPLE_CLICK, 0)
        hud.replaceExemplars(
            listOf(
                StoredExemplar(
                    1,
                    listOf(
                        Stroke(InkLayer.SKELETON, 2, listOf(PaperPoint(0, 0), PaperPoint(1000, 1000))),
                    ),
                ),
            ),
        )
        hud.setAdvicePages(listOf(listOf("起筆を垂直に"), listOf("ハネを短く")))
        assertEquals(listOf("起筆を垂直に"), hud.visibleAdvice())
        hud.onAction(TouchAction.SLIDE_UP, 0)
        assertEquals(listOf("ハネを短く"), hud.visibleAdvice())
        hud.onAction(TouchAction.SLIDE_DOWN, 0)
        assertEquals(listOf("起筆を垂直に"), hud.visibleAdvice())
        val shown = hud.render()
        assertTrue(shown.count(HudPalette.EXEMPLAR) > 0)
        hud.onAction(TouchAction.DOUBLE_CLICK, 0)
        assertEquals(emptyList(), hud.visibleAdvice())
        val cleared = hud.render()
        assertEquals(0, cleared.count(HudPalette.EXEMPLAR))
        assertEquals(0, cleared.count(HudPalette.CONTOUR))
    }

    @Test
    fun reviewClickReturnsToTheLessonMode() {
        val hud = PracticeHud()
        hud.onAction(TouchAction.TRIPLE_CLICK, 0)
        hud.onAction(TouchAction.TRIPLE_CLICK, 0)
        assertEquals(HudMode.SIDE_BY_SIDE, hud.mode)
        hud.showCorrections(listOf(Stroke(InkLayer.CORRECTION, 2, listOf(PaperPoint(1, 1), PaperPoint(2, 2)))))
        assertEquals(HudMode.REVIEW, hud.mode)
        hud.onAction(TouchAction.CLICK, 0)
        assertEquals(HudMode.SIDE_BY_SIDE, hud.mode)
        assertEquals(0, hud.render().count(HudPalette.CORRECTION))
    }

    @Test
    fun slideRequestsTheNextExemplar() {
        val hud = PracticeHud()
        hud.replaceExemplars(
            listOf(
                StoredExemplar(1, listOf(Stroke(InkLayer.SKELETON, 2, listOf(PaperPoint(0, 0), PaperPoint(10, 10))))),
                StoredExemplar(2, listOf(Stroke(InkLayer.CONTOUR, 2, listOf(PaperPoint(0, 0), PaperPoint(10, 10))))),
            ),
        )
        hud.onAction(TouchAction.SLIDE_FORWARD, 0)
        assertEquals(2L, hud.currentExemplar()?.id)
        assertIs<HudEffect.ShiftExemplar>(hud.takeEffects().single())
    }

    @Test
    fun brightnessDoesNotRecolorPixels() {
        val hud = PracticeHud()
        hud.connected = true
        hud.onAction(TouchAction.TRIPLE_CLICK, 0)
        hud.apply(SetHud(brightness = 10, lineWidthPx = 8, showCrosshair = true))
        hud.quad = PaperQuad(
            HudPoint(2000, 2000),
            HudPoint(8000, 2000),
            HudPoint(8000, 8000),
            HudPoint(2000, 8000),
        )
        hud.replaceExemplars(
            listOf(StoredExemplar(1, listOf(Stroke(InkLayer.CONTOUR, 8, listOf(PaperPoint(0, 5000), PaperPoint(10000, 5000)))))),
        )
        val bitmap = hud.render()
        assertTrue(bitmap.pixels.all { it == HudPalette.UNLIT || it == HudPalette.CONTOUR || it == HudPalette.TEXT || it == HudPalette.EXEMPLAR })
        assertEquals(8, hud.lineWidthPx)
        assertEquals(PaperProfile.HANSHI, hud.paper)
    }
}
