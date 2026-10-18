package dev.shodoghost.protocol

/**
 * RayNeo ARDK / MercurySDK へ触れる実装は、グラス側モジュールがこの境界の後ろに置く。
 * プロトコルモジュール自体は Android も Unity も参照しない。
 */
interface PlaneAnchorAdapter {
    /** 既定ビルドは false。true の実装だけが VGA 空間カメラを使ってよい。 */
    val available: Boolean

    fun enable()

    fun disable()

    fun currentQuad(): PaperQuad?
}

interface CameraCaptureAdapter {
    /**
     * オープン、静止画1枚、クローズまでをこの呼び出しの中で終える。
     * プレビューの常時表示は契約違反。
     */
    fun captureStill(jpegQuality: Int): CapturedStill
}

data class CapturedStill(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val cameraReleased: Boolean,
) {
    init {
        require(cameraReleased)
        require(jpeg.isNotEmpty() && jpeg.size <= Limits.MAX_JPEG_BYTES)
        require(width > 0 && height > 0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedStill) return false
        return jpeg.contentEquals(other.jpeg) && width == other.width && height == other.height && cameraReleased
    }

    override fun hashCode(): Int = width xor height xor jpeg.contentHashCode()
}

/** 640×480 を1枚受け取り、左右の目へ同じ絵を出す。 */
interface BinocularPresenter {
    fun present(frame: HudFrame)
}

data class HudFrame(
    val strokes: List<Stroke>,
    val quad: PaperQuad?,
    val advice: List<String>,
    val showCrosshair: Boolean,
    val warning: String?,
    val lineWidthPx: Int,
    val mode: HudMode = HudMode.TRACE,
    val paper: PaperProfile = PaperProfile.HANSHI,
) {
    init {
        require(lineWidthPx in Limits.MIN_LINE_PX..Limits.MAX_LINE_PX)
        require(advice.size <= Limits.MAX_ADVICE_LINES)
        if (warning != null) {
            require(warning.encodeToByteArray().size <= Limits.MAX_ADVICE_BYTES)
        }
    }
}

interface TempleInputSink {
    fun onAction(action: TouchAction, elapsedMs: Long)
}

/** スマホ側。OpenCV も VLM もこの後ろ。グラスはこの型を知って呼びはしない。 */
interface AnalysisBackend {
    fun critique(request: CritiqueRequest): CritiqueResult
}

data class CritiqueRequest(
    val jpeg: ByteArray,
    val exemplarId: Long,
    val strokes: List<Stroke>,
    val paper: PaperProfile,
    val widthMm: Int,
    val heightMm: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CritiqueRequest) return false
        return jpeg.contentEquals(other.jpeg) &&
            exemplarId == other.exemplarId &&
            strokes == other.strokes &&
            paper == other.paper &&
            widthMm == other.widthMm &&
            heightMm == other.heightMm
    }

    override fun hashCode(): Int = exemplarId.toInt() xor jpeg.contentHashCode()
}

data class CritiqueResult(
    val critiqueId: Long,
    val strokes: List<Stroke>,
    val advice: List<String>,
) {
    init {
        require(advice.isNotEmpty() && advice.size <= Limits.MAX_ADVICE_LINES)
    }
}

/** 半紙中央の十字。用紙座標のまま返し、描画時に [Homography] で HUD へ載せる。 */
fun paperCrosshair(widthPx: Int = Limits.MIN_LINE_PX): List<Stroke> {
    val mid = Limits.NORM_MAX / 2
    return listOf(
        Stroke(InkLayer.CROSSHAIR, widthPx, listOf(PaperPoint(mid, 0), PaperPoint(mid, Limits.NORM_MAX))),
        Stroke(InkLayer.CROSSHAIR, widthPx, listOf(PaperPoint(0, mid), PaperPoint(Limits.NORM_MAX, mid))),
    )
}
