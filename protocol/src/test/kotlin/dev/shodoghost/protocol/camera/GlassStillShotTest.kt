package dev.shodoghost.protocol.camera

import dev.shodoghost.protocol.BeginWifiSession
import dev.shodoghost.protocol.CameraState
import dev.shodoghost.protocol.Crc32
import dev.shodoghost.protocol.EndWifiSession
import dev.shodoghost.protocol.Hello
import dev.shodoghost.protocol.Inbound
import dev.shodoghost.protocol.InboundAssembler
import dev.shodoghost.protocol.Limits
import dev.shodoghost.protocol.LinkMessage
import dev.shodoghost.protocol.LinkPhase
import dev.shodoghost.protocol.PeerRole
import dev.shodoghost.protocol.Protocol
import dev.shodoghost.protocol.RadioKind
import dev.shodoghost.protocol.Thermal
import dev.shodoghost.protocol.WifiPurpose
import dev.shodoghost.protocol.link.GlassListener
import dev.shodoghost.protocol.link.GlassRadio
import dev.shodoghost.protocol.link.GlassSession
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GlassStillShotTest {
    private val radio = RecordingRadio()
    private val session = GlassSession(radio)
    private val camera = ScriptedCamera()
    private val shot = GlassStillShot(session, LegacyStillCapture(camera) { })

    @Test
    fun takePublishesTheReleasedStillAndKeepsOneJpeg() {
        ready()
        camera.jpeg = byteArrayOf(9, 8, 7, 6)
        val still = shot.take(90)

        assertEquals(4032, still.width)
        assertEquals(3024, still.height)
        assertTrue(still.cameraReleased)
        assertEquals(CameraState.CLOSED, session.camera)
        assertEquals(setOf(RadioKind.BLE), session.radios())
        assertEquals(4L, session.pendingCapture?.jpegBytes)
        assertEquals(Crc32.ieee(still.jpeg), session.pendingCapture?.crc32)
        assertContentEquals(byteArrayOf(9, 8, 7, 6), shot.retainedJpeg())
        assertEquals(listOf("open 0", "release"), camera.boundary)
    }

    @Test
    fun aSecondTakeReplacesTheRetainedJpeg() {
        ready()
        camera.jpeg = byteArrayOf(1)
        shot.take(80)
        camera.jpeg = byteArrayOf(2, 2)
        shot.take(80)
        assertContentEquals(byteArrayOf(2, 2), shot.retainedJpeg())
        assertEquals(2L, session.pendingCapture?.jpegBytes)
    }

    @Test
    fun deliveredTransferDropsTheRetainedJpeg() {
        ready()
        camera.jpeg = byteArrayOf(4, 5)
        shot.take(80)
        val token = ByteArray(Limits.WIFI_TOKEN_BYTES)
        write(BeginWifiSession(byteArrayOf(172.toByte(), 20, 10, 1), 9, token, WifiPurpose.UPLOAD_JPEG))
        session.publishWifiReady(true)
        write(EndWifiSession(0))
        assertNull(session.pendingCapture)
        assertNull(shot.retainedJpeg())
    }

    @Test
    fun asleepDoesNotOpenTheCamera() {
        ready()
        session.noteDisplaySleep()
        assertFailsWith<StillCaptureRefused> { shot.take(80) }
        assertTrue(camera.boundary.isEmpty())
        assertEquals(CameraState.CLOSED, session.camera)
        assertNull(session.pendingCapture)
    }

    @Test
    fun hotDoesNotOpenTheCamera() {
        ready()
        session.thermal = Thermal.HOT
        assertFailsWith<StillCaptureRefused> { shot.take(80) }
        assertTrue(camera.boundary.isEmpty())
    }

    @Test
    fun wifiDoesNotOpenTheCamera() {
        ready()
        write(BeginWifiSession(byteArrayOf(172.toByte(), 20, 10, 1), 9, ByteArray(Limits.WIFI_TOKEN_BYTES), WifiPurpose.UPLOAD_JPEG))
        assertEquals(LinkPhase.WIFI_WAKE, session.phase)
        assertFailsWith<StillCaptureRefused> { shot.take(80) }
        assertTrue(camera.boundary.isEmpty())
    }

    @Test
    fun aFailedPictureClosesTheSessionCameraAndKeepsNoJpeg() {
        ready()
        camera.failPicture = true
        assertFailsWith<IllegalStateException> { shot.take(80) }
        assertEquals(CameraState.CLOSED, session.camera)
        assertNull(session.pendingCapture)
        assertNull(shot.retainedJpeg())
        assertEquals(listOf("open 0", "release"), camera.boundary)
    }

    private fun ready() {
        session.startAdvertising()
        session.onBonded()
        session.onLinkUp(Limits.PREFERRED_ATT_MTU)
        write(Hello(Limits.PROTOCOL_VERSION, Limits.PREFERRED_ATT_MTU, PeerRole.PHONE_CENTRAL, ByteArray(Limits.NONCE_BYTES)))
        radio.messages.clear()
    }

    private fun write(message: LinkMessage) {
        val frame = Protocol.encode(message, sequence = 3, attMtu = Limits.PREFERRED_ATT_MTU).frames.single()
        session.onCommand(frame)
    }

    private class ScriptedCamera : StillCamera {
        var jpeg = byteArrayOf(1)
        var failPicture = false
        val boundary = mutableListOf<String>()

        override fun open(cameraId: Int) {
            boundary += "open $cameraId"
        }

        override fun supportedPictureSizes(): List<PictureSize> = listOf(PictureSize(640, 480), PictureSize(4032, 3024))

        override fun applyJpeg(width: Int, height: Int, quality: Int) = Unit

        override fun startPreview() = Unit

        override fun takePicture(): ByteArray {
            if (failPicture) throw IllegalStateException("picture")
            return jpeg
        }

        override fun stopPreview() = Unit

        override fun release() {
            boundary += "release"
        }
    }

    private class RecordingRadio : GlassRadio {
        val messages = mutableListOf<LinkMessage>()
        private val assembler = InboundAssembler()

        override fun notifyStatus(frame: ByteArray) {
            val inbound = assembler.push(frame)
            if (inbound is Inbound.Message) messages += inbound.message.message
        }

        override fun notifyPayload(frame: ByteArray) = Unit
    }
}
