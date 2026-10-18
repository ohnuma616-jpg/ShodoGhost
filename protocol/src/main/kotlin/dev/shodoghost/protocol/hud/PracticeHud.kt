package dev.shodoghost.protocol.hud

import dev.shodoghost.protocol.GuideCommand
import dev.shodoghost.protocol.HudFrame
import dev.shodoghost.protocol.HudMode
import dev.shodoghost.protocol.Limits
import dev.shodoghost.protocol.PaperProfile
import dev.shodoghost.protocol.PaperQuad
import dev.shodoghost.protocol.SetHud
import dev.shodoghost.protocol.Stroke
import dev.shodoghost.protocol.TempleInputSink
import dev.shodoghost.protocol.TouchAction
import dev.shodoghost.protocol.toGuideCommand

sealed interface HudEffect {
    /** 四隅が無いときの決定。撮影はまだしない。 */
    data object StartCalibration : HudEffect

    /** 長押し。カメラを開くのは呼び出し側。 */
    data object FinishAndCapture : HudEffect

    /** 前後スワイプ。手元に手本が無くてもスマホへ伝える。 */
    data class ShiftExemplar(val delta: Int) : HudEffect
}

data class StoredExemplar(val id: Long, val strokes: List<Stroke>)

/**
 * テンプルとスマホの Touch を同じ [TouchAction] で受ける。
 * 生の座標は見ない。ジェスチャへの変換は SDK の TouchDispatcher 側。
 * 輝度は画素を混ぜるとパレットが崩れるので、ラスタには反映しない。
 */
class PracticeHud(
    var paper: PaperProfile = PaperProfile.HANSHI,
) : TempleInputSink {
    var mode: HudMode = HudMode.IDLE
        private set
    var connected: Boolean = false
    var quad: PaperQuad? = null
    var showCrosshair: Boolean = true
    var lineWidthPx: Int = Limits.MIN_LINE_PX
        private set
    var brightness: Int = 255
        private set
    var overlayVisible: Boolean = true
        private set

    private var lessonMode: HudMode = HudMode.TRACE
    private val exemplars = mutableListOf<StoredExemplar>()
    private var exemplarIndex = 0
    private var corrections: List<Stroke> = emptyList()
    private var advicePages: List<List<String>> = emptyList()
    private var advicePage = 0
    private val pending = ArrayDeque<HudEffect>()

    fun replaceExemplars(items: List<StoredExemplar>) {
        exemplars.clear()
        exemplars += items
        exemplarIndex = exemplarIndex.coerceIn(0, (exemplars.size - 1).coerceAtLeast(0))
        overlayVisible = true
    }

    fun setAdvicePages(pages: List<List<String>>) {
        require(pages.all { it.isNotEmpty() && it.size <= Limits.MAX_ADVICE_LINES })
        pages.flatten().forEach { line ->
            require(line.encodeToByteArray().size <= Limits.MAX_ADVICE_BYTES)
        }
        advicePages = pages
        advicePage = 0
        overlayVisible = true
    }

    fun showCorrections(strokes: List<Stroke>) {
        if (mode == HudMode.TRACE || mode == HudMode.SIDE_BY_SIDE) lessonMode = mode
        if (mode == HudMode.IDLE) lessonMode = HudMode.TRACE
        corrections = strokes
        mode = HudMode.REVIEW
        overlayVisible = true
    }

    fun apply(settings: SetHud) {
        brightness = settings.brightness
        lineWidthPx = settings.lineWidthPx
        showCrosshair = settings.showCrosshair
    }

    fun visibleAdvice(): List<String> {
        if (!overlayVisible || advicePages.isEmpty()) return emptyList()
        return advicePages[advicePage.coerceIn(0, advicePages.lastIndex)]
    }

    fun currentExemplar(): StoredExemplar? = exemplars.getOrNull(exemplarIndex)

    override fun onAction(action: TouchAction, elapsedMs: Long) {
        effectFor(action)?.let { pending.addLast(it) }
    }

    fun takeEffects(): List<HudEffect> {
        val copy = pending.toList()
        pending.clear()
        return copy
    }

    fun toFrame(): HudFrame {
        val lesson = if (overlayVisible) currentExemplar()?.strokes.orEmpty() else emptyList()
        val extra = if (overlayVisible && mode == HudMode.REVIEW) corrections else emptyList()
        return HudFrame(
            strokes = lesson + extra,
            quad = quad,
            advice = visibleAdvice(),
            showCrosshair = overlayVisible && showCrosshair && quad != null && mode != HudMode.IDLE,
            warning = if (connected) null else "未接続",
            lineWidthPx = lineWidthPx,
            mode = mode,
            paper = paper,
        )
    }

    fun render(): HudBitmap = renderHud(toFrame())

    private fun effectFor(action: TouchAction): HudEffect? {
        when (action.toGuideCommand()) {
            GuideCommand.CONFIRM -> return confirm()
            GuideCommand.FINISH_AND_CAPTURE -> return HudEffect.FinishAndCapture
            GuideCommand.BACK -> back()
            GuideCommand.CYCLE_MODE -> cycle()
            GuideCommand.NEXT_EXEMPLAR -> return shift(+1)
            GuideCommand.PREV_EXEMPLAR -> return shift(-1)
            GuideCommand.NEXT_PAGE ->
                advicePage = (advicePage + 1).coerceAtMost(advicePages.lastIndex.coerceAtLeast(0))
            GuideCommand.PREV_PAGE -> advicePage = (advicePage - 1).coerceAtLeast(0)
        }
        return null
    }

    private fun confirm(): HudEffect? {
        if (mode == HudMode.REVIEW) {
            corrections = emptyList()
            mode = lessonMode
            return null
        }
        if (quad == null) return HudEffect.StartCalibration
        return null
    }

    private fun back() {
        corrections = emptyList()
        advicePages = emptyList()
        advicePage = 0
        overlayVisible = false
        if (mode == HudMode.REVIEW) mode = lessonMode
    }

    private fun cycle() {
        mode = when (mode) {
            HudMode.IDLE -> HudMode.TRACE
            HudMode.TRACE -> HudMode.SIDE_BY_SIDE
            HudMode.SIDE_BY_SIDE -> HudMode.TRACE
            HudMode.REVIEW -> if (lessonMode == HudMode.SIDE_BY_SIDE) HudMode.TRACE else HudMode.SIDE_BY_SIDE
        }
        corrections = emptyList()
        lessonMode = mode
        overlayVisible = true
    }

    private fun shift(delta: Int): HudEffect {
        if (exemplars.isNotEmpty()) {
            exemplarIndex = (exemplarIndex + delta).coerceIn(0, exemplars.lastIndex)
        }
        overlayVisible = true
        return HudEffect.ShiftExemplar(delta)
    }
}
