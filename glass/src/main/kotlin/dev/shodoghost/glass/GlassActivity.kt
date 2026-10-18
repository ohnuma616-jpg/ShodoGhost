package dev.shodoghost.glass

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import dev.shodoghost.protocol.CaptureNow
import dev.shodoghost.protocol.LinkPhase
import dev.shodoghost.protocol.camera.GlassStillShot
import dev.shodoghost.protocol.camera.LegacyStillCapture
import dev.shodoghost.protocol.hud.DeskMirrorPresenter
import dev.shodoghost.protocol.hud.bringUpFrame
import dev.shodoghost.protocol.hud.installHudGlyphs
import java.io.File
import java.util.concurrent.Executors

/**
 * 640×480 を左右へ出す。撮影は iPhone の CAPTURE_NOW が来てから、ロックの外で 1 枚。
 * 撮影スレッドは Looper を持たない。
 */
class GlassActivity : Activity() {
    private val presenter = DeskMirrorPresenter()
    private val view by lazy { HudView(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var link: GlassLink
    private lateinit var shot: GlassStillShot
    private var line: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installHudGlyphs(AndroidHudGlyphs)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        link = GlassLink(
            this,
            onCapture = ::capture,
            onPhase = { phase -> runOnUiThread { show(phase) } },
            onStatus = ::note,
        )
        shot = GlassStillShot(link.session, LegacyStillCapture(AndroidStillCamera()))
        show(LinkPhase.IDLE, advice = listOf("起動"))
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        val missing = REQUIRED.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val bleReady = BLE.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (bleReady) link.start()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            link.start()
        } else {
            show(LinkPhase.IDLE, warning = "許可待ち")
        }
    }

    override fun onDestroy() {
        link.close()
        worker.shutdown()
        super.onDestroy()
    }

    private fun note(text: String) {
        runOnUiThread {
            Log.i(TAG, text)
            runCatching { File(filesDir, "link.txt").writeText(text) }
            show(link.session.phase, advice = listOf(text))
        }
    }

    private fun capture(request: CaptureNow) {
        worker.execute {
            try {
                val still = shot.take(request.jpegQuality)
                File(filesDir, STILL_NAME).writeBytes(still.jpeg)
                Log.i(TAG, "bytes ${still.jpeg.size} ${still.width}x${still.height} released ${still.cameraReleased}")
                runOnUiThread { show(link.session.phase, advice = listOf(still.jpeg.size.toString())) }
            } catch (error: Exception) {
                Log.i(TAG, "capture failed ${error.message}")
                runOnUiThread { show(link.session.phase, warning = "撮影失敗") }
            }
        }
    }

    private fun show(phase: LinkPhase, advice: List<String> = line, warning: String? = null) {
        line = advice
        val disconnected = phase != LinkPhase.BLE_READY &&
            phase != LinkPhase.WIFI_WAKE &&
            phase != LinkPhase.TRANSFER &&
            phase != LinkPhase.WIFI_TEARDOWN
        presenter.present(
            bringUpFrame(
                advice = advice,
                warning = warning ?: if (disconnected) "未接続" else null,
            ),
        )
        val panel = presenter.mirrored
        Log.i(TAG, "panel ${panel.width}x${panel.height} phase ${phase.name}")
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
        const val PERMISSION_REQUEST = 1
        val BLE = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        val REQUIRED = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    }
}
