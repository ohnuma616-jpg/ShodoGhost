package dev.shodoghost.glass

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import dev.shodoghost.protocol.BeginWifiSession
import dev.shodoghost.protocol.CalibrateCommit
import dev.shodoghost.protocol.CaptureNow
import dev.shodoghost.protocol.LinkPhase
import dev.shodoghost.protocol.PaperQuad
import dev.shodoghost.protocol.Stroke
import dev.shodoghost.protocol.StrokePack
import dev.shodoghost.protocol.camera.GlassStillShot
import dev.shodoghost.protocol.camera.LegacyStillCapture
import dev.shodoghost.protocol.hud.DeskMirrorPresenter
import dev.shodoghost.protocol.hud.bringUpFrame
import dev.shodoghost.protocol.hud.installHudGlyphs
import dev.shodoghost.protocol.hud.lessonFrame
import java.io.File
import java.util.concurrent.Executors

/**
 * 640×480 を左右へ出す。撮影は iPhone の CAPTURE_NOW が来てから、ロックの外で 1 枚。
 * 撮影の待ちは Looper を持たないスレッドで行う。カメラのコールバックは別スレッドが受ける。
 */
class GlassActivity : Activity() {
    private val presenter = DeskMirrorPresenter()
    private val view by lazy { HudView(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val stillCamera = AndroidStillCamera()
    private lateinit var link: GlassLink
    private lateinit var shot: GlassStillShot
    private var bottom = "起動"
    private var captureShown = false
    private var reviewAdvice: List<String> = emptyList()
    private var reviewStrokes: List<Stroke> = emptyList()
    private var exemplarStrokes: List<Stroke> = emptyList()
    private var paperQuad: PaperQuad? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installHudGlyphs(AndroidHudGlyphs)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        link = GlassLink(
            this,
            onCapture = ::capture,
            onPhase = { phase -> runOnUiThread { onPhase(phase) } },
            onStatus = ::note,
            onWifi = ::upload,
            adviceSink = ::advice,
            correctionSink = ::correction,
            exemplarSink = ::exemplar,
            cornerSink = ::corners,
        )
        shot = GlassStillShot(link.session, LegacyStillCapture(stillCamera))
        show(LinkPhase.IDLE)
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
        stillCamera.closeThread()
        super.onDestroy()
    }

    private fun phaseWord(phase: LinkPhase): String = when (phase) {
        LinkPhase.IDLE -> "停止"
        LinkPhase.DISCOVERING -> "待受"
        LinkPhase.CONNECTING -> "接続中"
        LinkPhase.NEGOTIATING -> "折衝"
        LinkPhase.BLE_READY -> "準備完了"
        LinkPhase.WIFI_WAKE -> "無線"
        LinkPhase.TRANSFER -> "転送"
        LinkPhase.WIFI_TEARDOWN -> "切断中"
        LinkPhase.BACKOFF -> "再接続"
    }

    private fun onPhase(phase: LinkPhase) {
        val linked = phase == LinkPhase.BLE_READY ||
            phase == LinkPhase.WIFI_WAKE ||
            phase == LinkPhase.TRANSFER ||
            phase == LinkPhase.WIFI_TEARDOWN
        if (!linked) captureShown = false
        if (!captureShown) bottom = phaseWord(phase)
        show(phase)
    }

    private fun note(text: String) {
        runOnUiThread {
            captureShown = false
            bottom = text
            Log.i(TAG, text)
            runCatching { File(filesDir, "link.txt").writeText(text) }
            show(link.session.phase)
        }
    }

    private fun corners(commit: CalibrateCommit) {
        runOnUiThread {
            paperQuad = commit.quad
            captureShown = true
            bottom = "四隅"
            show(link.session.phase)
        }
    }

    private fun advice(lines: List<String>) {
        runOnUiThread {
            reviewAdvice = lines
            show(link.session.phase)
        }
    }

    private fun correction(pack: StrokePack) {
        runOnUiThread {
            reviewStrokes = if (pack.packIndex == 0) pack.strokes else reviewStrokes + pack.strokes
            show(link.session.phase)
        }
    }

    private fun exemplar(pack: StrokePack) {
        runOnUiThread {
            exemplarStrokes = if (pack.packIndex == 0) pack.strokes else exemplarStrokes + pack.strokes
            if (reviewAdvice.isEmpty()) {
                captureShown = true
                bottom = "手本"
            }
            show(link.session.phase)
        }
    }

    private fun capture(request: CaptureNow) {
        runOnUiThread {
            captureShown = true
            reviewAdvice = emptyList()
            reviewStrokes = emptyList()
            bottom = "撮影"
            show(link.session.phase)
        }
        worker.execute {
            try {
                val still = shot.take(request.jpegQuality)
                File(filesDir, STILL_NAME).writeBytes(still.jpeg)
                val text = still.jpeg.size.toString()
                Log.i(TAG, "bytes $text ${still.width}x${still.height} released ${still.cameraReleased}")
                runCatching { File(filesDir, "link.txt").writeText(text) }
                runOnUiThread {
                    captureShown = true
                    bottom = text
                    show(link.session.phase)
                }
            } catch (error: Exception) {
                val text = clip("失敗 ${error.message}")
                Log.i(TAG, "capture failed ${error.message}")
                runCatching { File(filesDir, "link.txt").writeText(text) }
                runOnUiThread {
                    captureShown = true
                    bottom = text
                    show(link.session.phase)
                }
            }
        }
    }

    private fun upload(begin: BeginWifiSession) {
        worker.execute {
            val still = shot.retainedStill()
            if (still == null) {
                pin("失敗 写真がない")
                return@execute
            }
            note("送信")
            try {
                GlassWifi(this).send(link.session, begin, still)
            } catch (error: Exception) {
                pin(clip("失敗 ${error.message}"))
            }
        }
    }

    private fun pin(text: String) {
        runOnUiThread {
            captureShown = true
            bottom = text
            show(link.session.phase)
        }
    }

    private fun clip(text: String): String {
        val out = StringBuilder()
        var bytes = 0
        for (ch in text) {
            val size = ch.toString().encodeToByteArray().size
            if (bytes + size > 42) break
            out.append(ch)
            bytes += size
        }
        return out.toString().ifEmpty { "失敗" }
    }

    private fun show(phase: LinkPhase, warning: String? = null) {
        val disconnected = phase != LinkPhase.BLE_READY &&
            phase != LinkPhase.WIFI_WAKE &&
            phase != LinkPhase.TRANSFER &&
            phase != LinkPhase.WIFI_TEARDOWN
        val linkWarning = warning ?: if (disconnected) "未接続" else null
        val advice = if (reviewAdvice.isNotEmpty()) reviewAdvice else listOf(bottom)
        val lesson = paperQuad != null ||
            reviewAdvice.isNotEmpty() ||
            reviewStrokes.isNotEmpty() ||
            exemplarStrokes.isNotEmpty()
        val frame = if (lesson) {
            lessonFrame(exemplarStrokes, reviewStrokes, advice, linkWarning, paperQuad, paperQuad != null)
        } else {
            bringUpFrame(advice = listOf(bottom), warning = linkWarning)
        }
        presenter.present(frame)
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
