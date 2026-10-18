package dev.shodoghost.protocol

/**
 * 設計キャンバスは 640×480。実機の左右パネルへはこの1枚を複製する。
 * 1280 幅の UI を直接組まない。
 */
object HudCanvas {
    const val WIDTH: Int = 640
    const val HEIGHT: Int = 480

    /** 文字・アイコン。依頼どおりキャンバスの 10%。 */
    const val TEXT_INSET_X: Int = 64
    const val TEXT_INSET_Y: Int = 48

    /**
     * 公式の合目マージン（左右 30px、上下 20px）。
     * ゴースト線のクリップはこちら。文字より外側まで使って半紙端を残す。
     */
    const val OPTICAL_INSET_X: Int = 30
    const val OPTICAL_INSET_Y: Int = 20

    fun textSafeContains(px: Int, py: Int): Boolean {
        return px >= TEXT_INSET_X &&
            py >= TEXT_INSET_Y &&
            px < WIDTH - TEXT_INSET_X &&
            py < HEIGHT - TEXT_INSET_Y
    }

    fun opticalContains(px: Int, py: Int): Boolean {
        return px >= OPTICAL_INSET_X &&
            py >= OPTICAL_INSET_Y &&
            px < WIDTH - OPTICAL_INSET_X &&
            py < HEIGHT - OPTICAL_INSET_Y
    }

    fun normToPxX(norm: Int): Int = norm.coerceIn(0, Limits.NORM_MAX) * (WIDTH - 1) / Limits.NORM_MAX

    fun normToPxY(norm: Int): Int = norm.coerceIn(0, Limits.NORM_MAX) * (HEIGHT - 1) / Limits.NORM_MAX
}

/**
 * MicroLED は加算発光。黒は消灯で、現実の墨が透過して見える。
 * アルファで墨を透かす表現は使わない。線幅は導波路の下限 2px。
 */
object HudPalette {
    const val UNLIT: Int = 0x000000
    const val EXEMPLAR: Int = 0x5CE1FF
    const val CONTOUR: Int = 0x39FF14
    const val CORRECTION: Int = 0xFF453A
    const val TEXT: Int = 0xFFFFFF
    const val WARNING: Int = 0xFFD60A
}

/** 半紙上の正規化座標。原点は用紙の左上、範囲は 0..10000。 */
data class PaperPoint(val x: Int, val y: Int) {
    init {
        require(x in 0..Limits.NORM_MAX && y in 0..Limits.NORM_MAX) {
            "paper point out of range: $x,$y"
        }
    }
}

/** HUD キャンバス上の正規化座標。原点は画面左上、範囲は 0..10000。 */
data class HudPoint(val x: Int, val y: Int) {
    init {
        require(x in 0..Limits.NORM_MAX && y in 0..Limits.NORM_MAX) {
            "hud point out of range: $x,$y"
        }
    }
}

/** 半紙四隅。順は左上、右上、右下、左下。 */
data class PaperQuad(
    val topLeft: HudPoint,
    val topRight: HudPoint,
    val bottomRight: HudPoint,
    val bottomLeft: HudPoint,
)

enum class InkLayer(val code: Int) {
    SKELETON(1),
    CONTOUR(2),
    CORRECTION(3),
    CROSSHAIR(4),
    MARKER(5),
    ;

    companion object {
        fun fromCode(code: Int): InkLayer? = entries.firstOrNull { it.code == code }
    }
}

data class Stroke(
    val layer: InkLayer,
    val widthPx: Int,
    val points: List<PaperPoint>,
) {
    init {
        require(widthPx in Limits.MIN_LINE_PX..Limits.MAX_LINE_PX) {
            "line width $widthPx is outside ${Limits.MIN_LINE_PX}..${Limits.MAX_LINE_PX}"
        }
        require(points.isNotEmpty() && points.size <= Limits.MAX_POINTS_PER_STROKE) {
            "stroke point count ${points.size} is outside 1..${Limits.MAX_POINTS_PER_STROKE}"
        }
    }
}

enum class PaperProfile(val code: Int, val widthMm: Int, val heightMm: Int) {
    /** 半紙。横 242mm、縦 333mm。 */
    HANSHI(0, 242, 333),
    /** のし紙の公称。実寸はスマホ側で上書きできる。 */
    NOSHI(1, 175, 85),
    /** 一文字用の色紙に近い公称。 */
    ICHIJI(2, 135, 165),
    ;

    companion object {
        fun fromCode(code: Int): PaperProfile? = entries.firstOrNull { it.code == code }
    }
}

enum class HudMode(val code: Int) {
    IDLE(0),
    TRACE(1),
    SIDE_BY_SIDE(2),
    REVIEW(3),
    ;

    companion object {
        fun fromCode(code: Int): HudMode? = entries.firstOrNull { it.code == code }
    }
}
