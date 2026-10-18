package dev.shodoghost.glass

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import dev.shodoghost.protocol.camera.LegacyStillCapture
import dev.shodoghost.protocol.hud.DeskMirrorPresenter
import dev.shodoghost.protocol.hud.bringUpFrame
import dev.shodoghost.protocol.hud.installHudGlyphs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 640×480 を左右へ出してから、カメラ 0 を1枚だけ開く。
 * 撮影スレッドは Looper を持たない。コールバックはメインへ戻り、ここで待たない。
 */
class GlassActivity : Activity() {
    private val presenter = DeskMirrorPresenter()
    private val view by lazy { HudView(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val started = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installHudGlyphs(AndroidHudGlyphs)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        show(advice = emptyList(), warning = null)
        setContentView(view)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scheduleCapture()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scheduleCapture()
        } else {
            show(advice = emptyList(), warning = "カメラ拒否")
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun scheduleCapture() {
        view.postDelayed({ shoot() }, CAPTURE_DELAY_MS)
    }

    private fun shoot() {
        if (!started.compareAndSet(false, true)) return
        worker.execute {
            try {
                val still = LegacyStillCapture(AndroidStillCamera()).captureStill(JPEG_QUALITY)
                File(filesDir, STILL_NAME).writeBytes(still.jpeg)
                Log.i(TAG, "bytes ${still.jpeg.size} ${still.width}x${still.height} released ${still.cameraReleased}")
                runOnUiThread { show(advice = listOf(still.jpeg.size.toString()), warning = null) }
            } catch (error: Exception) {
                Log.i(TAG, "capture failed ${error.message}")
                runOnUiThread { show(advice = emptyList(), warning = "撮影失敗") }
            }
        }
    }

    private fun show(advice: List<String>, warning: String?) {
        presenter.present(bringUpFrame(advice, warning))
        val panel = presenter.mirrored
        Log.i(TAG, "panel ${panel.width}x${panel.height}")
        view.setPanel(panel)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private companion object {
        const val TAG = "ShodoGlass"
        const val STILL_NAME = "still.jpg"
        const val JPEG_QUALITY = 90
        const val CAPTURE_DELAY_MS = 4_000L
        const val CAMERA_REQUEST = 1
    }
}
