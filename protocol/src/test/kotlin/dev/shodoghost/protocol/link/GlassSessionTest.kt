package dev.shodoghost.protocol.link

import dev.shodoghost.protocol.Ack
import dev.shodoghost.protocol.AckStatus
import dev.shodoghost.protocol.CalibMethod
import dev.shodoghost.protocol.CalibrateCommit
import dev.shodoghost.protocol.CaptureNow
import dev.shodoghost.protocol.CaptureReason
import dev.shodoghost.protocol.Hello
import dev.shodoghost.protocol.HelloAck
import dev.shodoghost.protocol.HudMode
import dev.shodoghost.protocol.HudPoint
import dev.shodoghost.protocol.Inbound
import dev.shodoghost.protocol.InboundAssembler
import dev.shodoghost.protocol.InkLayer
import dev.shodoghost.protocol.Limits
import dev.shodoghost.protocol.LinkPhase
import dev.shodoghost.protocol.OverlayBits
import dev.shodoghost.protocol.PaperPoint
import dev.shodoghost.protocol.PaperProfile
import dev.shodoghost.protocol.PaperQuad
import dev.shodoghost.protocol.PeerRole
import dev.shodoghost.protocol.BeginWifiSession
import dev.shodoghost.protocol.EndWifiSession
import dev.shodoghost.protocol.Ping
import dev.shodoghost.protocol.Pong
import dev.shodoghost.protocol.WifiPurpose
import dev.shodoghost.protocol.Protocol
import dev.shodoghost.protocol.SetMode
import dev.shodoghost.protocol.ShowAdvice
import dev.shodoghost.protocol.StateReport
import dev.shodoghost.protocol.Stroke
import dev.shodoghost.protocol.StrokePack
import dev.shodoghost.protocol.Thermal
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GlassSessionTest {
    private val radio = RecordingRadio()
    private val heard = RecordingListener()
    private val session = GlassSession(radio, heard, nowMs = { 5_000 })

    @Test
    fun helloEchoesNonceAndPublishesState() {
        connect()
        val nonce = ByteArray(Limits.NONCE_BYTES) { (it + 1).toByte() }
        write(Hello(Limits.PROTOCOL_VERSION, Limits.PREFERRED_ATT_MTU, PeerRole.PHONE_CENTRAL, nonce))
        val ack = assertIs<HelloAck>(radio.messages[0])
        assertTrue(ack.accept)
        assertTrue(nonce.contentEquals(ack.nonce))
        val state = assertIs<StateReport>(radio.messages[1])
        assertEquals(LinkPhase.BLE_READY, state.phase)
        assertEquals(LinkPhase.BLE_READY, session.phase)
    }

    @Test
    fun dropsCommandsUntilBonded() {
        session.startAdvertising()
        session.onLinkUp(Limits.PREFERRED_ATT_MTU)
        write(Ping(1))
        assertEquals(0, radio.status.size)
        session.onBonded()
        write(Ping(4))
        assertEquals(0, radio.status.size)
    }

    @Test
    fun shortMtuBacksOffBeforeHello() {
        session.startAdvertising()
        session.onBonded()
        session.onLinkUp(23)
        assertEquals(LinkPhase.BACKOFF, session.phase)
        write(Ping(1))
        assertEquals(0, radio.status.size)
    }

    @Test
    fun rejectsMismatchedVersion() {
        connect()
        val nonce = ByteArray(Limits.NONCE_BYTES) { 9 }
        write(Hello(99, Limits.PREFERRED_ATT_MTU, PeerRole.PHONE_CENTRAL, nonce))
        val ack = assertIs<HelloAck>(radio.messages.single())
        assertEquals(false, ack.accept)
        assertEquals(LinkPhase.BACKOFF, session.phase)
    }

    @Test
    fun setModeIsAcked() {
        ready()
        write(SetMode(HudMode.TRACE, PaperProfile.NOSHI))
        val ack = radio.messages.filterIsInstance<Ack>().single()
        assertEquals(AckStatus.OK, ack.status)
        assertEquals(HudMode.TRACE, session.mode)
        assertEquals(PaperProfile.NOSHI, heard.paper)
    }

    @Test
    fun hotGlassRefusesCapture() {
        ready()
        session.thermal = Thermal.HOT
        write(CaptureNow(CaptureReason.PHONE, 80))
        val ack = radio.messages.filterIsInstance<Ack>().single()
        assertEquals(AckStatus.BUSY, ack.status)
        assertEquals(0, heard.captures)
    }

    @Test
    fun fragmentedStrokesRaiseExemplarBit() {
        ready()
        val pack = StrokePack(
            exemplarOrCritiqueId = 99,
            packIndex = 0,
            packCount = 1,
            strokes = List(20) { index ->
                Stroke(
                    layer = if (index % 2 == 0) InkLayer.SKELETON else InkLayer.CONTOUR,
                    widthPx = 2,
                    points = List(30) { point -> PaperPoint(point * 100, index * 200) },
                )
            },
            correction = false,
        )
        val outbound = Protocol.encode(pack, sequence = 10, attMtu = Limits.MIN_ATT_MTU)
        assertTrue(outbound.frames.size > 1)
        for (frame in outbound.frames) session.onPayload(frame)
        assertEquals(99L, heard.strokeId)
        assertTrue(session.overlayBits and OverlayBits.EXEMPLAR != 0)
        assertTrue(radio.messages.filterIsInstance<StateReport>().any { it.overlayBits and OverlayBits.EXEMPLAR != 0 })
    }

    @Test
    fun pingAndWifiRoundTrip() {
        ready()
        write(Ping(0x11223344))
        assertEquals(0x11223344L, radio.messages.filterIsInstance<Pong>().single().cookie)
        val quad = PaperQuad(
            HudPoint(1000, 1000),
            HudPoint(9000, 1000),
            HudPoint(9000, 9000),
            HudPoint(1000, 9000),
        )
        write(CalibrateCommit(CalibMethod.ONE_SHOT, quad, 242, 333))
        assertEquals(1, heard.commits)
        write(ShowAdvice(listOf("起筆を垂直に", "ハネを短く")))
        assertEquals(listOf("起筆を垂直に", "ハネを短く"), heard.advice)
        val token = ByteArray(Limits.WIFI_TOKEN_BYTES) { 7 }
        write(BeginWifiSession(byteArrayOf(172.toByte(), 20, 10, 1), 18765, token, WifiPurpose.UPLOAD_JPEG))
        assertEquals(LinkPhase.WIFI_WAKE, session.phase)
        write(EndWifiSession(0))
        assertEquals(LinkPhase.BLE_READY, session.phase)
    }

    private fun connect() {
        session.startAdvertising()
        session.onBonded()
        session.onLinkUp(Limits.PREFERRED_ATT_MTU)
        assertEquals(LinkPhase.NEGOTIATING, session.phase)
    }

    private fun ready() {
        connect()
        radio.status.clear()
        radio.messages.clear()
        val nonce = ByteArray(Limits.NONCE_BYTES)
        write(Hello(Limits.PROTOCOL_VERSION, Limits.PREFERRED_ATT_MTU, PeerRole.PHONE_CENTRAL, nonce))
        radio.status.clear()
        radio.messages.clear()
    }

    private fun write(message: dev.shodoghost.protocol.LinkMessage) {
        val frame = Protocol.encode(message, sequence = 3, attMtu = Limits.PREFERRED_ATT_MTU).frames.single()
        session.onCommand(frame)
    }

    private class RecordingRadio : GlassRadio {
        val status = mutableListOf<ByteArray>()
        val messages = mutableListOf<dev.shodoghost.protocol.LinkMessage>()
        private val assembler = InboundAssembler()

        override fun notifyStatus(frame: ByteArray) {
            status += frame
            val inbound = assembler.push(frame)
            if (inbound is Inbound.Message) messages += inbound.message.message
        }

        override fun notifyPayload(frame: ByteArray) = Unit
    }

    private class RecordingListener : GlassListener() {
        var paper: PaperProfile? = null
        var captures = 0
        var strokeId = -1L
        var commits = 0
        var advice: List<String> = emptyList()

        override fun onModeChanged(mode: HudMode, paper: PaperProfile) {
            this.paper = paper
        }

        override fun onCaptureRequested(request: CaptureNow) {
            captures += 1
        }

        override fun onStrokePack(pack: StrokePack) {
            strokeId = pack.exemplarOrCritiqueId
        }

        override fun onCalibrateCommit(commit: CalibrateCommit) {
            commits += 1
        }

        override fun onAdvice(lines: List<String>) {
            advice = lines
        }
    }
}
