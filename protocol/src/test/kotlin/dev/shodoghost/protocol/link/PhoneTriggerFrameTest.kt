package dev.shodoghost.protocol.link

import dev.shodoghost.protocol.CaptureNow
import dev.shodoghost.protocol.CaptureReason
import dev.shodoghost.protocol.Hello
import dev.shodoghost.protocol.Inbound
import dev.shodoghost.protocol.InboundAssembler
import dev.shodoghost.protocol.Limits
import dev.shodoghost.protocol.PeerRole
import dev.shodoghost.protocol.Protocol
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class PhoneTriggerFrameTest {
    @Test
    fun helloAndCaptureRoundTrip() {
        val nonce = ByteArray(Limits.NONCE_BYTES) { (it + 1).toByte() }
        val hello = Protocol.encode(
            Hello(Limits.PROTOCOL_VERSION, Limits.MIN_ATT_MTU, PeerRole.PHONE_CENTRAL, nonce),
            sequence = 1,
            attMtu = Limits.MIN_ATT_MTU,
        ).frames.single()
        val capture = Protocol.encode(
            CaptureNow(CaptureReason.PHONE, 90),
            sequence = 2,
            attMtu = Limits.MIN_ATT_MTU,
        ).frames.single()
        val helloIn = assertIs<Inbound.Message>(InboundAssembler().push(hello))
        assertEquals(nonce.toList(), (helloIn.message.message as Hello).nonce.toList())
        val captureIn = assertIs<Inbound.Message>(InboundAssembler().push(capture))
        assertEquals(90, (captureIn.message.message as CaptureNow).jpegQuality)
        assertEquals(22, hello.size)
        assertEquals(12, capture.size)
    }
}
