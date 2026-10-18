package dev.shodoghost.protocol

import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ProtocolTest {
    @Test
    fun crc16MatchesCcittFalseVector() {
        assertEquals(0x29B1, Crc16.ccittFalse("123456789".encodeToByteArray()))
    }

    @Test
    fun crc32MatchesIeeeVector() {
        assertEquals(0xCBF43926L, Crc32.ieee("123456789".encodeToByteArray()))
    }

    @Test
    fun frameRoundTripAndRejection() {
        val frame = Frame(MessageType.PING, FrameFlags.FIN, 7, byteArrayOf(1, 2, 3, 4))
        val bytes = FrameCodec.encode(frame)
        val decoded = FrameCodec.decode(bytes)
        assertEquals(frame, (decoded as FrameDecode.Ok).frame)

        val flipped = bytes.copyOf()
        flipped[flipped.lastIndex] = (flipped[flipped.lastIndex].toInt() xor 0xFF).toByte()
        assertEquals(ErrorCode.CRC_FAIL, (FrameCodec.decode(flipped) as FrameDecode.Rejected).code)

        val badSof = bytes.copyOf()
        badSof[0] = 0
        assertEquals(ErrorCode.BAD_FRAME, (FrameCodec.decode(badSof) as FrameDecode.Rejected).code)
    }

    @Test
    fun controlMessagesFitInMinimumMtuAndRoundTrip() {
        val nonce = ByteArray(Limits.NONCE_BYTES) { it.toByte() }
        val token = ByteArray(Limits.WIFI_TOKEN_BYTES) { (it + 3).toByte() }
        val quad = PaperQuad(
            HudPoint(1000, 1200),
            HudPoint(9000, 1400),
            HudPoint(8600, 8800),
            HudPoint(1400, 8600),
        )
        val messages: List<LinkMessage> = listOf(
            Hello(Limits.PROTOCOL_VERSION, Limits.PREFERRED_ATT_MTU, PeerRole.PHONE_CENTRAL, nonce),
            HelloAck(1, true, 80, Thermal.OK, 3900, HwFlags.MAIN_CAMERA or HwFlags.WIFI, nonce),
            SetMode(HudMode.TRACE, PaperProfile.HANSHI),
            ExemplarMeta(42, 3, 120, InkLayer.SKELETON.code),
            CalibrateStart(CalibMethod.ONE_SHOT, 8),
            CalibrateCommit(CalibMethod.ONE_SHOT, quad, 242, 333),
            CaptureNow(CaptureReason.TEMPLE, 85),
            ShowAdvice(listOf("起筆を垂直に", "ハネを短く")),
            ClearOverlay(OverlayBits.ALL),
            BeginWifiSession(byteArrayOf(192.toByte(), 168.toByte(), 1, 20), 18765, token, WifiPurpose.UPLOAD_AND_RESULT),
            EndWifiSession(0),
            Ping(0x11223344),
            Pong(0x11223344, 15),
            SetHud(180, 2, true),
            SleepHint(1500),
            StateReport(HudMode.TRACE, LinkPhase.BLE_READY, Thermal.WARM, 70, CameraState.CLOSED, OverlayBits.EXEMPLAR, 3800),
            TouchReport(TouchAction.LONG_PRESS, 1200),
            CaptureReady(120_000, 0xCBF43926, true, 4000, 3000),
            ErrorReport(ErrorCode.THERMAL, "hot"),
            Ack(9, MessageType.CAPTURE_NOW, AckStatus.OK),
            WifiReady(true),
            WifiClosed(1),
            SessionInfo(1, HwFlags.MAIN_CAMERA, 70, Thermal.OK, LinkPhase.BLE_READY),
        )
        val assembler = InboundAssembler()
        var sequence = 1
        for (message in messages) {
            val outbound = Protocol.encode(message, sequence, Limits.MIN_ATT_MTU)
            assertEquals(1, outbound.frames.size, message.type.name)
            assertTrue(outbound.frames[0].size <= FrameCodec.maxValueLength(Limits.MIN_ATT_MTU))
            val inbound = assembler.push(outbound.frames[0])
            val got = assertIs<Inbound.Message>(inbound)
            assertEquals(message, got.message.message)
            assertEquals(sequence, got.message.sequence)
            sequence = outbound.nextSequence
        }
    }

    @Test
    fun criticalCommandsRequestAck() {
        val outbound = Protocol.encode(
            CaptureNow(CaptureReason.PHONE, 80),
            sequence = 4,
            attMtu = Limits.MIN_ATT_MTU,
        )
        val frame = (FrameCodec.decode(outbound.frames.single()) as FrameDecode.Ok).frame
        assertTrue((frame.flags and FrameFlags.ACK_REQUEST) != 0)
        assertTrue((frame.flags and FrameFlags.FIN) != 0)
    }

    @Test
    fun strokePackFragmentsAndReassemblesOutOfOrder() {
        val strokes = List(20) { index ->
            Stroke(
                layer = if (index % 2 == 0) InkLayer.SKELETON else InkLayer.CONTOUR,
                widthPx = 2,
                points = List(30) { point -> PaperPoint(point * 100, index * 200) },
            )
        }
        val pack = StrokePack(99, 0, 1, strokes, correction = false)
        val outbound = Protocol.encode(pack, sequence = 10, attMtu = Limits.MIN_ATT_MTU)
        assertTrue(outbound.frames.size > 1)
        val assembler = InboundAssembler()
        var pending = 0
        for (frame in outbound.frames.reversed()) {
            when (val inbound = assembler.push(frame)) {
                is Inbound.Pending -> pending += 1
                is Inbound.Message -> {
                    assertEquals(pack, inbound.message.message)
                    assertEquals(10, inbound.message.sequence)
                }
                is Inbound.Rejected -> error(inbound.detail)
            }
        }
        assertEquals(outbound.frames.size - 1, pending)
    }

    @Test
    fun rejectsUndersizedMtuAndOnePixelLines() {
        assertFailsWith<ProtocolException> {
            Protocol.encode(Ping(1), 0, attMtu = 23)
        }
        assertFailsWith<IllegalArgumentException> {
            Stroke(InkLayer.SKELETON, 1, listOf(PaperPoint(0, 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            ShowAdvice(listOf("あ".repeat(20)))
        }
    }

    @Test
    fun homographyMapsPaperCornersOntoQuad() {
        val quad = PaperQuad(
            HudPoint(2000, 2000),
            HudPoint(8000, 2200),
            HudPoint(7600, 8000),
            HudPoint(2400, 7800),
        )
        val map = Homography.fromPaperQuad(quad)
        assertClose(quad.topLeft, map.map(PaperPoint(0, 0)))
        assertClose(quad.topRight, map.map(PaperPoint(Limits.NORM_MAX, 0)))
        assertClose(quad.bottomRight, map.map(PaperPoint(Limits.NORM_MAX, Limits.NORM_MAX)))
        assertClose(quad.bottomLeft, map.map(PaperPoint(0, Limits.NORM_MAX)))
        val degenerate = PaperQuad(HudPoint(1, 1), HudPoint(1, 1), HudPoint(1, 1), HudPoint(1, 1))
        assertFailsWith<ProtocolException> { Homography.fromPaperQuad(degenerate) }
    }

    @Test
    fun hudSafeAreasAndPixelMapping() {
        assertTrue(HudCanvas.textSafeContains(64, 48))
        assertTrue(!HudCanvas.textSafeContains(63, 48))
        assertTrue(HudCanvas.opticalContains(30, 20))
        assertTrue(!HudCanvas.opticalContains(29, 20))
        assertEquals(0, HudCanvas.normToPxX(0))
        assertEquals(639, HudCanvas.normToPxX(Limits.NORM_MAX))
        assertEquals(479, HudCanvas.normToPxY(Limits.NORM_MAX))
    }

    @Test
    fun templeGesturesShareOneMeaning() {
        assertEquals(GuideCommand.FINISH_AND_CAPTURE, TouchAction.LONG_PRESS.toGuideCommand())
        assertEquals(GuideCommand.BACK, TouchAction.DOUBLE_CLICK.toGuideCommand())
        assertEquals(GuideCommand.CYCLE_MODE, TouchAction.TRIPLE_CLICK.toGuideCommand())
        assertEquals(GuideCommand.NEXT_EXEMPLAR, TouchAction.SLIDE_FORWARD.toGuideCommand())
    }

    @Test
    fun linkDropsWifiWithoutDroppingBleOnTimeout() {
        var phase = LinkPhase.IDLE
        phase = expect(phase, LinkSignal.START, LinkPhase.DISCOVERING, LinkEffect.OPEN_DISCOVERY)
        phase = expect(phase, LinkSignal.PEER_FOUND, LinkPhase.CONNECTING, LinkEffect.CLOSE_DISCOVERY)
        phase = expect(phase, LinkSignal.GATT_CONNECTED, LinkPhase.NEGOTIATING, LinkEffect.REQUEST_MTU_AND_HELLO)
        phase = expect(phase, LinkSignal.HELLO_OK, LinkPhase.BLE_READY)
        phase = expect(
            phase,
            LinkSignal.BEGIN_WIFI,
            LinkPhase.WIFI_WAKE,
            LinkEffect.ACQUIRE_RADIOS,
            LinkEffect.OPEN_WIFI_SOCKET,
        )
        phase = expect(
            phase,
            LinkSignal.TIMEOUT,
            LinkPhase.BLE_READY,
            LinkEffect.CLOSE_WIFI_SOCKET,
            LinkEffect.RELEASE_RADIOS,
        )
        val lost = LinkStateMachine.step(phase, LinkSignal.LINK_LOST)
        assertEquals(LinkPhase.BACKOFF, lost.phase)
        assertEquals(listOf(LinkEffect.SCHEDULE_RETRY), lost.effects)
        phase = expect(
            lost.phase,
            LinkSignal.RETRY,
            LinkPhase.DISCOVERING,
            LinkEffect.CANCEL_RETRY,
            LinkEffect.OPEN_DISCOVERY,
        )
        assertEquals(LinkPhase.DISCOVERING, phase)
        assertEquals(1_000L, RetrySchedule.delayMs(0))
        assertEquals(15_000L, RetrySchedule.delayMs(20))
        val ignored = LinkStateMachine.step(LinkPhase.IDLE, LinkSignal.BEGIN_WIFI)
        assertEquals(false, ignored.accepted)
        assertEquals(LinkPhase.IDLE, ignored.phase)
    }

    @Test
    fun wifiTextRoundTrip() {
        val text = WifiText.encode(
            WifiSession.OP_JPEG_BEGIN,
            mapOf("bytes" to "120000", "width" to "4000", "height" to "3000"),
        )
        val frame = WifiText.decode(text)
        assertEquals(WifiSession.OP_JPEG_BEGIN, frame.op)
        assertEquals("120000", frame.fields["bytes"])
        assertEquals("4000", frame.fields["width"])
    }

    @Test
    fun canonicalFixtureMatchesEncoder() {
        val file = repoRoot().resolve("fixtures/canonical.txt")
        val text = renderCanonicalFixtures()
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        assertEquals(text, file.readText())
    }

    @Test
    fun messageTypeCodesAreUnique() {
        val codes = MessageType.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun gattUuidsAreUnique() {
        val uuids = GattProfile.characteristics.map { it.uuid } + GattProfile.SERVICE
        assertEquals(uuids.size, uuids.toSet().size)
        assertContentEquals("ShodoGhost".encodeToByteArray(), GattProfile.LOCAL_NAME.encodeToByteArray())
    }

    private fun expect(
        phase: LinkPhase,
        signal: LinkSignal,
        next: LinkPhase,
        vararg effects: LinkEffect,
    ): LinkPhase {
        val step = LinkStateMachine.step(phase, signal)
        assertTrue(step.accepted, "$phase + $signal")
        assertEquals(next, step.phase)
        assertEquals(effects.toList(), step.effects)
        return step.phase
    }

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        var dir: File? = start
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found from $start")
    }

    private fun renderCanonicalFixtures(): String = buildString {
        var sequence = 1
        for (message in controlMessages()) {
            val outbound = Protocol.encode(message, sequence, Limits.MIN_ATT_MTU)
            appendLine("VECTOR ${message.type.name}")
            appendLine("SEQUENCE $sequence")
            appendLine("MTU ${Limits.MIN_ATT_MTU}")
            for (frame in outbound.frames) appendLine("FRAME ${frame.toHex()}")
            appendLine("END")
            sequence = outbound.nextSequence
        }
        val pack = sampleStrokePack()
        val outbound = Protocol.encode(pack, 10, Limits.MIN_ATT_MTU)
        appendLine("VECTOR ${pack.type.name}")
        appendLine("SEQUENCE 10")
        appendLine("MTU ${Limits.MIN_ATT_MTU}")
        for (frame in outbound.frames) appendLine("FRAME ${frame.toHex()}")
        appendLine("END")
    }

    private fun controlMessages(): List<LinkMessage> {
        val nonce = ByteArray(Limits.NONCE_BYTES) { it.toByte() }
        val token = ByteArray(Limits.WIFI_TOKEN_BYTES) { (it + 3).toByte() }
        val quad = PaperQuad(
            HudPoint(1000, 1200),
            HudPoint(9000, 1400),
            HudPoint(8600, 8800),
            HudPoint(1400, 8600),
        )
        return listOf(
            Hello(Limits.PROTOCOL_VERSION, Limits.PREFERRED_ATT_MTU, PeerRole.PHONE_CENTRAL, nonce),
            HelloAck(1, true, 80, Thermal.OK, 3900, HwFlags.MAIN_CAMERA or HwFlags.WIFI, nonce),
            SetMode(HudMode.TRACE, PaperProfile.HANSHI),
            ExemplarMeta(42, 3, 120, InkLayer.SKELETON.code),
            CalibrateStart(CalibMethod.ONE_SHOT, 8),
            CalibrateCommit(CalibMethod.ONE_SHOT, quad, 242, 333),
            CaptureNow(CaptureReason.TEMPLE, 85),
            ShowAdvice(listOf("起筆を垂直に", "ハネを短く")),
            ClearOverlay(OverlayBits.ALL),
            BeginWifiSession(byteArrayOf(192.toByte(), 168.toByte(), 1, 20), 18765, token, WifiPurpose.UPLOAD_AND_RESULT),
            EndWifiSession(0),
            Ping(0x11223344),
            Pong(0x11223344, 15),
            SetHud(180, 2, true),
            SleepHint(1500),
            StateReport(HudMode.TRACE, LinkPhase.BLE_READY, Thermal.WARM, 70, CameraState.CLOSED, OverlayBits.EXEMPLAR, 3800),
            TouchReport(TouchAction.LONG_PRESS, 1200),
            CaptureReady(120_000, 0xCBF43926, true, 4000, 3000),
            ErrorReport(ErrorCode.THERMAL, "hot"),
            Ack(9, MessageType.CAPTURE_NOW, AckStatus.OK),
            WifiReady(true),
            WifiClosed(1),
            SessionInfo(1, HwFlags.MAIN_CAMERA, 70, Thermal.OK, LinkPhase.BLE_READY),
        )
    }

    private fun sampleStrokePack(): StrokePack {
        val strokes = List(20) { index ->
            Stroke(
                layer = if (index % 2 == 0) InkLayer.SKELETON else InkLayer.CONTOUR,
                widthPx = 2,
                points = List(30) { point -> PaperPoint(point * 100, index * 200) },
            )
        }
        return StrokePack(99, 0, 1, strokes, correction = false)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun assertClose(expected: HudPoint, actual: HudPoint) {
        assertTrue(kotlin.math.abs(expected.x - actual.x) <= 1, "x ${actual.x} vs ${expected.x}")
        assertTrue(kotlin.math.abs(expected.y - actual.y) <= 1, "y ${actual.y} vs ${expected.y}")
    }
}
