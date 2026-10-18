@file:Suppress("DEPRECATION")

package dev.shodoghost.glass

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import dev.shodoghost.protocol.camera.PictureSize
import dev.shodoghost.protocol.camera.StillCamera
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ARGF20 の確認用 APK と同じ手順。
 * `Camera.open(0)`、4032×3024、`SurfaceTexture(0)` でプレビュー、1枚、`stopPreview`、`release`。
 * このソースは Android SDK が必要なので、`:protocol` の JVM ビルドには入れていない。
 */
@Suppress("DEPRECATION")
class AndroidStillCamera : StillCamera {
    private var camera: Camera? = null
    private var texture: SurfaceTexture? = null

    override fun open(cameraId: Int) {
        check(camera == null) { "camera already open" }
        camera = Camera.open(cameraId)
    }

    override fun supportedPictureSizes(): List<PictureSize> {
        return camera().parameters.supportedPictureSizes.map { PictureSize(it.width, it.height) }
    }

    override fun applyJpeg(width: Int, height: Int, quality: Int) {
        val current = camera()
        val params = current.parameters
        params.setPictureFormat(ImageFormat.JPEG)
        params.setJpegQuality(quality)
        params.setPictureSize(width, height)
        current.parameters = params
    }

    override fun startPreview() {
        val surface = SurfaceTexture(0)
        texture = surface
        val current = camera()
        current.setPreviewTexture(surface)
        current.startPreview()
    }

    override fun takePicture(): ByteArray {
        val latch = CountDownLatch(1)
        val bytes = AtomicReference<ByteArray>()
        val error = AtomicReference<Throwable>()
        camera().takePicture(null, null, Camera.PictureCallback { data, _ ->
            try {
                if (data == null || data.isEmpty()) error.set(IllegalStateException("empty jpeg"))
                else bytes.set(data)
            } catch (thrown: Throwable) {
                error.set(thrown)
            } finally {
                latch.countDown()
            }
        })
        check(latch.await(PICTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "picture timeout" }
        error.get()?.let { throw it }
        return bytes.get()
    }

    override fun stopPreview() {
        camera().stopPreview()
    }

    override fun release() {
        try {
            camera?.release()
        } finally {
            camera = null
            texture?.release()
            texture = null
        }
    }

    private fun camera(): Camera = checkNotNull(camera) { "camera is closed" }

    private companion object {
        const val PICTURE_TIMEOUT_SECONDS: Long = 8
    }
}
