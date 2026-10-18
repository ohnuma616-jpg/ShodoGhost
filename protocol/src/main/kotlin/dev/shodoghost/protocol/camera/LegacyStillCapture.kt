package dev.shodoghost.protocol.camera

import dev.shodoghost.protocol.CameraCaptureAdapter
import dev.shodoghost.protocol.CapturedStill

data class PictureSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}

/**
 * X3 Pro（ARGF20）で通ったレガシー Camera API。
 * `Camera.open(0)` のあとプレビューを始め、1枚撮ってから閉じる。ID 1 は開かない。
 */
interface StillCamera {
    fun open(cameraId: Int)
    fun supportedPictureSizes(): List<PictureSize>
    fun applyJpeg(width: Int, height: Int, quality: Int)
    fun startPreview()
    fun takePicture(): ByteArray
    fun stopPreview()
    fun release()
}

object StillCapture {
    const val CAMERA_ID: Int = 0
    const val WIDTH: Int = 4032
    const val HEIGHT: Int = 3024

    /** 実機の確認では `startPreview` のあと、この時間を置いて `takePicture` した。 */
    const val PREVIEW_SETTLE_MS: Long = 700

    fun selectSize(sizes: List<PictureSize>): PictureSize {
        require(sizes.isNotEmpty()) { "camera reported no picture sizes" }
        return sizes.firstOrNull { it.width == WIDTH && it.height == HEIGHT }
            ?: sizes.maxBy { it.width.toLong() * it.height }
    }
}

/**
 * 呼び出しの中で開いて、1枚取って、閉じてから戻る。
 * GATT のロックを持ったスレッドからは呼ばない。
 */
class LegacyStillCapture(
    private val camera: StillCamera,
    private val settle: (Long) -> Unit = { Thread.sleep(it) },
) : CameraCaptureAdapter {
    override fun captureStill(jpegQuality: Int): CapturedStill {
        require(jpegQuality in 1..100) { "jpeg quality is $jpegQuality" }
        camera.open(StillCapture.CAMERA_ID)
        val shot = try {
            val size = StillCapture.selectSize(camera.supportedPictureSizes())
            camera.applyJpeg(size.width, size.height, jpegQuality)
            camera.startPreview()
            val jpeg = try {
                settle(StillCapture.PREVIEW_SETTLE_MS)
                camera.takePicture()
            } finally {
                runCatching { camera.stopPreview() }
            }
            Taken(jpeg, size)
        } finally {
            camera.release()
        }
        return CapturedStill(
            jpeg = shot.jpeg,
            width = shot.size.width,
            height = shot.size.height,
            cameraReleased = true,
        )
    }

    private data class Taken(val jpeg: ByteArray, val size: PictureSize)
}
