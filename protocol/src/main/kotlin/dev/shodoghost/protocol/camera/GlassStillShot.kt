package dev.shodoghost.protocol.camera

import dev.shodoghost.protocol.CameraCaptureAdapter
import dev.shodoghost.protocol.CameraState
import dev.shodoghost.protocol.CaptureReady
import dev.shodoghost.protocol.CapturedStill
import dev.shodoghost.protocol.Crc32
import dev.shodoghost.protocol.LinkPhase
import dev.shodoghost.protocol.Thermal
import dev.shodoghost.protocol.link.GlassSession

/** 撮影してよい相ではない。カメラは開いていない。 */
class StillCaptureRefused(detail: String) : IllegalStateException(detail)

/**
 * `CAPTURE_NOW` を受けたあと、セッションの外で呼ぶ。
 * JPEG はメモリに1枚だけ残す。転送が `TRANSFER` を完了したとき、セッションがメタデータを捨てたのに合わせてバイト列も捨てる。
 */
class GlassStillShot(
    private val session: GlassSession,
    private val capture: CameraCaptureAdapter,
) {
    private var held: CapturedStill? = null

    fun take(jpegQuality: Int): CapturedStill {
        require(jpegQuality in 1..100) { "jpeg quality is $jpegQuality" }
        if (session.thermal == Thermal.HOT) throw StillCaptureRefused("hot")
        if (session.displayAsleep || session.phase != LinkPhase.BLE_READY || session.camera != CameraState.CLOSED) {
            throw StillCaptureRefused("camera")
        }
        session.noteCamera(CameraState.OPENING)
        try {
            val still = capture.captureStill(jpegQuality)
            held = still
            session.publishCaptureReady(
                CaptureReady(
                    jpegBytes = still.jpeg.size.toLong(),
                    crc32 = Crc32.ieee(still.jpeg),
                    cameraReleased = true,
                    width = still.width,
                    height = still.height,
                ),
            )
            return still
        } catch (error: Throwable) {
            held = null
            if (session.camera != CameraState.CLOSED) session.noteCamera(CameraState.CLOSED)
            throw error
        }
    }

    fun retainedJpeg(): ByteArray? = retainedStill()?.jpeg

    fun retainedStill(): CapturedStill? {
        if (session.pendingCapture == null) {
            held = null
            return null
        }
        val still = held ?: return null
        return still.copy(jpeg = still.jpeg.copyOf())
    }
}
