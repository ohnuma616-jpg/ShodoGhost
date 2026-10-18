package dev.shodoghost.glass

import android.content.Context
import android.net.wifi.WifiManager
import dev.shodoghost.protocol.BeginWifiSession
import dev.shodoghost.protocol.WifiJpegClient
import dev.shodoghost.protocol.camera.CapturedStill
import dev.shodoghost.protocol.link.GlassSession

/** BEGIN_WIFI のあと、保持している JPEG を iPhone へ送る。 */
class GlassWifi(context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    fun send(session: GlassSession, begin: BeginWifiSession, still: CapturedStill) {
        val lock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "shodo-wifi")
        lock?.setReferenceCounted(false)
        lock?.acquire()
        try {
            WifiJpegClient.upload(
                host = WifiJpegClient.host(begin.ipv4),
                port = begin.port,
                token = begin.token,
                jpeg = still.jpeg,
                width = still.width,
                height = still.height,
                connected = { session.publishWifiReady(true) },
            )
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }
}
