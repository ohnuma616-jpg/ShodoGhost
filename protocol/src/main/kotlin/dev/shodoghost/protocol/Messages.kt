package dev.shodoghost.protocol

sealed interface LinkMessage {
    val type: MessageType
    fun encodeBody(): ByteArray
}

data class Hello(
    val version: Int,
    val requestedMtu: Int,
    val role: PeerRole,
    val nonce: ByteArray,
) : LinkMessage {
    override val type: MessageType = MessageType.HELLO

    init {
        require(nonce.size == Limits.NONCE_BYTES)
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(version)
        u16(requestedMtu)
        u8(role.code)
        bytes(nonce)
    }.toByteArray()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Hello) return false
        return version == other.version &&
            requestedMtu == other.requestedMtu &&
            role == other.role &&
            nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + requestedMtu
        result = 31 * result + role.hashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }

    companion object {
        fun decode(payload: ByteArray): Hello {
            val r = ByteReader(payload)
            val message = Hello(r.u8(), r.u16(), enumRole(r.u8()), r.bytes(Limits.NONCE_BYTES))
            r.expectExhausted()
            return message
        }
    }
}

data class HelloAck(
    val version: Int,
    val accept: Boolean,
    val batteryPercent: Int,
    val thermal: Thermal,
    val batteryMillivolts: Int,
    val hwFlags: Int,
    val nonce: ByteArray,
) : LinkMessage {
    override val type: MessageType = MessageType.HELLO_ACK

    init {
        require(nonce.size == Limits.NONCE_BYTES)
        require(batteryPercent in 0..100)
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(version)
        u8(if (accept) 1 else 0)
        u8(batteryPercent)
        u8(thermal.code)
        u16(batteryMillivolts)
        u16(hwFlags)
        bytes(nonce)
    }.toByteArray()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HelloAck) return false
        return version == other.version &&
            accept == other.accept &&
            batteryPercent == other.batteryPercent &&
            thermal == other.thermal &&
            batteryMillivolts == other.batteryMillivolts &&
            hwFlags == other.hwFlags &&
            nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int = nonce.contentHashCode()

    companion object {
        fun decode(payload: ByteArray): HelloAck {
            val r = ByteReader(payload)
            val message = HelloAck(
                version = r.u8(),
                accept = r.u8() == 1,
                batteryPercent = r.u8(),
                thermal = enumThermal(r.u8()),
                batteryMillivolts = r.u16(),
                hwFlags = r.u16(),
                nonce = r.bytes(Limits.NONCE_BYTES),
            )
            r.expectExhausted()
            return message
        }
    }
}

data class SetMode(val mode: HudMode, val paper: PaperProfile) : LinkMessage {
    override val type: MessageType = MessageType.SET_MODE
    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(mode.code)
        u8(paper.code)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): SetMode {
            val r = ByteReader(payload)
            val message = SetMode(enumMode(r.u8()), enumPaper(r.u8()))
            r.expectExhausted()
            return message
        }
    }
}

data class ExemplarMeta(
    val exemplarId: Long,
    val strokeCount: Int,
    val pointCount: Int,
    val layerMask: Int,
) : LinkMessage {
    override val type: MessageType = MessageType.EXEMPLAR_META
    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u32(exemplarId)
        u16(strokeCount)
        u16(pointCount)
        u8(layerMask)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): ExemplarMeta {
            val r = ByteReader(payload)
            val message = ExemplarMeta(r.u32(), r.u16(), r.u16(), r.u8())
            r.expectExhausted()
            return message
        }
    }
}

data class CalibrateStart(val method: CalibMethod, val timeoutSec: Int) : LinkMessage {
    override val type: MessageType = MessageType.CALIBRATE_START
    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(method.code)
        u8(timeoutSec)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): CalibrateStart {
            val r = ByteReader(payload)
            val message = CalibrateStart(enumCalib(r.u8()), r.u8())
            r.expectExhausted()
            return message
        }
    }
}

data class CalibrateCommit(
    val method: CalibMethod,
    val quad: PaperQuad,
    val widthMm: Int,
    val heightMm: Int,
) : LinkMessage {
    override val type: MessageType = MessageType.CALIBRATE_COMMIT
    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(method.code)
        writeHud(quad.topLeft)
        writeHud(quad.topRight)
        writeHud(quad.bottomRight)
        writeHud(quad.bottomLeft)
        i16(widthMm)
        i16(heightMm)
    }.toByteArray()

    private fun ByteWriter.writeHud(point: HudPoint) {
        i16(point.x)
        i16(point.y)
    }

    companion object {
        fun decode(payload: ByteArray): CalibrateCommit {
            val r = ByteReader(payload)
            val message = CalibrateCommit(
                method = enumCalib(r.u8()),
                quad = PaperQuad(readHud(r), readHud(r), readHud(r), readHud(r)),
                widthMm = r.i16(),
                heightMm = r.i16(),
            )
            r.expectExhausted()
            return message
        }

        private fun readHud(r: ByteReader): HudPoint = HudPoint(r.i16(), r.i16())
    }
}

data class CaptureNow(val reason: CaptureReason, val jpegQuality: Int) : LinkMessage {
    override val type: MessageType = MessageType.CAPTURE_NOW

    init {
        require(jpegQuality in 1..100)
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(reason.code)
        u8(jpegQuality)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): CaptureNow {
            val r = ByteReader(payload)
            val message = CaptureNow(enumCapture(r.u8()), r.u8())
            r.expectExhausted()
            return message
        }
    }
}

data class ShowAdvice(val lines: List<String>) : LinkMessage {
    override val type: MessageType = MessageType.SHOW_ADVICE

    init {
        require(lines.isNotEmpty() && lines.size <= Limits.MAX_ADVICE_LINES)
        for (line in lines) {
            require(!line.contains('\n')) { "advice line contains newline" }
            val size = line.encodeToByteArray().size
            require(size in 1..Limits.MAX_ADVICE_BYTES) { "advice line is $size bytes" }
        }
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(lines.size)
        for (line in lines) {
            val raw = line.encodeToByteArray()
            u8(raw.size)
            bytes(raw)
        }
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): ShowAdvice {
            val r = ByteReader(payload)
            val count = r.u8()
            val lines = List(count) {
                val len = r.u8()
                r.utf8(len)
            }
            r.expectExhausted()
            return ShowAdvice(lines)
        }
    }
}

data class ClearOverlay(val mask: Int) : LinkMessage {
    override val type: MessageType = MessageType.CLEAR_OVERLAY
    override fun encodeBody(): ByteArray = ByteWriter().apply { u8(mask) }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): ClearOverlay {
            val r = ByteReader(payload)
            val message = ClearOverlay(r.u8())
            r.expectExhausted()
            return message
        }
    }
}

data class BeginWifiSession(
    val ipv4: ByteArray,
    val port: Int,
    val token: ByteArray,
    val purpose: WifiPurpose,
) : LinkMessage {
    override val type: MessageType = MessageType.BEGIN_WIFI_SESSION

    init {
        require(ipv4.size == 4)
        require(token.size == Limits.WIFI_TOKEN_BYTES)
        require(port in 1..65535)
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        bytes(ipv4)
        u16(port)
        bytes(token)
        u8(purpose.code)
    }.toByteArray()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BeginWifiSession) return false
        return ipv4.contentEquals(other.ipv4) &&
            port == other.port &&
            token.contentEquals(other.token) &&
            purpose == other.purpose
    }

    override fun hashCode(): Int = port xor token.contentHashCode()

    companion object {
        fun decode(payload: ByteArray): BeginWifiSession {
            val r = ByteReader(payload)
            val message = BeginWifiSession(
                ipv4 = r.bytes(4),
                port = r.u16(),
                token = r.bytes(Limits.WIFI_TOKEN_BYTES),
                purpose = enumWifi(r.u8()),
            )
            r.expectExhausted()
            return message
        }
    }
}

data class EndWifiSession(val reason: Int) : LinkMessage {
    override val type: MessageType = MessageType.END_WIFI_SESSION
    override fun encodeBody(): ByteArray = ByteWriter().apply { u8(reason) }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): EndWifiSession {
            val r = ByteReader(payload)
            val message = EndWifiSession(r.u8())
            r.expectExhausted()
            return message
        }
    }
}

data class Ping(val cookie: Long) : LinkMessage {
    override val type: MessageType = MessageType.PING
    override fun encodeBody(): ByteArray = ByteWriter().apply { u32(cookie) }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): Ping {
            val r = ByteReader(payload)
            val message = Ping(r.u32())
            r.expectExhausted()
            return message
        }
    }
}

data class Pong(val cookie: Long, val uptimeSec: Long) : LinkMessage {
    override val type: MessageType = MessageType.PONG
    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u32(cookie)
        u32(uptimeSec)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): Pong {
            val r = ByteReader(payload)
            val message = Pong(r.u32(), r.u32())
            r.expectExhausted()
            return message
        }
    }
}

data class SetHud(val brightness: Int, val lineWidthPx: Int, val showCrosshair: Boolean) : LinkMessage {
    override val type: MessageType = MessageType.SET_HUD

    init {
        require(brightness in 0..255)
        require(lineWidthPx in Limits.MIN_LINE_PX..Limits.MAX_LINE_PX)
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(brightness)
        u8(lineWidthPx)
        u8(if (showCrosshair) 1 else 0)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): SetHud {
            val r = ByteReader(payload)
            val message = SetHud(r.u8(), r.u8(), r.u8() == 1)
            r.expectExhausted()
            return message
        }
    }
}

data class SleepHint(val displayOffDelayMs: Int) : LinkMessage {
    override val type: MessageType = MessageType.SLEEP_HINT
    override fun encodeBody(): ByteArray = ByteWriter().apply { u16(displayOffDelayMs) }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): SleepHint {
            val r = ByteReader(payload)
            val message = SleepHint(r.u16())
            r.expectExhausted()
            return message
        }
    }
}

data class StateReport(
    val mode: HudMode,
    val phase: LinkPhase,
    val thermal: Thermal,
    val batteryPercent: Int,
    val camera: CameraState,
    val overlayBits: Int,
    val batteryMillivolts: Int,
) : LinkMessage {
    override val type: MessageType = MessageType.STATE

    init {
        require(batteryPercent in 0..100)
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(mode.code)
        u8(phase.code)
        u8(thermal.code)
        u8(batteryPercent)
        u8(camera.code)
        u8(overlayBits)
        u16(batteryMillivolts)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): StateReport {
            val r = ByteReader(payload)
            val message = StateReport(
                mode = enumMode(r.u8()),
                phase = enumPhase(r.u8()),
                thermal = enumThermal(r.u8()),
                batteryPercent = r.u8(),
                camera = enumCamera(r.u8()),
                overlayBits = r.u8(),
                batteryMillivolts = r.u16(),
            )
            r.expectExhausted()
            return message
        }
    }
}

data class TouchReport(val action: TouchAction, val elapsedMs: Long) : LinkMessage {
    override val type: MessageType = MessageType.TOUCH
    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(action.code)
        u32(elapsedMs)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): TouchReport {
            val r = ByteReader(payload)
            val message = TouchReport(enumTouch(r.u8()), r.u32())
            r.expectExhausted()
            return message
        }
    }
}

data class CaptureReady(
    val jpegBytes: Long,
    val crc32: Long,
    val cameraReleased: Boolean,
    val width: Int,
    val height: Int,
) : LinkMessage {
    override val type: MessageType = MessageType.CAPTURE_READY

    init {
        require(cameraReleased) { "capture ready requires the camera to be released" }
        require(jpegBytes in 1..Limits.MAX_JPEG_BYTES)
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u32(jpegBytes)
        u32(crc32)
        u8(1)
        u16(width)
        u16(height)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): CaptureReady {
            val r = ByteReader(payload)
            val message = CaptureReady(r.u32(), r.u32(), r.u8() == 1, r.u16(), r.u16())
            r.expectExhausted()
            return message
        }
    }
}

data class ErrorReport(val code: ErrorCode, val detail: String) : LinkMessage {
    override val type: MessageType = MessageType.ERROR

    init {
        val size = detail.encodeToByteArray().size
        require(size <= Limits.MAX_ERROR_BYTES) { "error detail is $size bytes" }
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u16(code.code)
        val raw = detail.encodeToByteArray()
        u8(raw.size)
        bytes(raw)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): ErrorReport {
            val r = ByteReader(payload)
            val code = ErrorCode.fromCode(r.u16())
                ?: throw ProtocolException(ErrorCode.BAD_FRAME, "unknown error code")
            val detail = r.utf8(r.u8())
            r.expectExhausted()
            return ErrorReport(code, detail)
        }
    }
}

data class Ack(val ackSequence: Int, val ackedType: MessageType, val status: AckStatus) : LinkMessage {
    override val type: MessageType = MessageType.ACK
    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u16(ackSequence)
        u8(ackedType.code)
        u8(status.code)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): Ack {
            val r = ByteReader(payload)
            val seq = r.u16()
            val type = MessageType.fromCode(r.u8())
                ?: throw ProtocolException(ErrorCode.BAD_FRAME, "acked type")
            val message = Ack(seq, type, enumAck(r.u8()))
            r.expectExhausted()
            return message
        }
    }
}

data class WifiReady(val ok: Boolean) : LinkMessage {
    override val type: MessageType = MessageType.WIFI_READY
    override fun encodeBody(): ByteArray = ByteWriter().apply { u8(if (ok) 1 else 0) }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): WifiReady {
            val r = ByteReader(payload)
            val message = WifiReady(r.u8() == 1)
            r.expectExhausted()
            return message
        }
    }
}

data class WifiClosed(val reason: Int) : LinkMessage {
    override val type: MessageType = MessageType.WIFI_CLOSED
    override fun encodeBody(): ByteArray = ByteWriter().apply { u8(reason) }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): WifiClosed {
            val r = ByteReader(payload)
            val message = WifiClosed(r.u8())
            r.expectExhausted()
            return message
        }
    }
}

data class SessionInfo(
    val version: Int,
    val hwFlags: Int,
    val batteryPercent: Int,
    val thermal: Thermal,
    val phase: LinkPhase,
) : LinkMessage {
    override val type: MessageType = MessageType.SESSION_INFO

    init {
        require(batteryPercent in 0..100)
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u8(version)
        u16(hwFlags)
        u8(batteryPercent)
        u8(thermal.code)
        u8(phase.code)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): SessionInfo {
            val r = ByteReader(payload)
            val message = SessionInfo(r.u8(), r.u16(), r.u8(), enumThermal(r.u8()), enumPhase(r.u8()))
            r.expectExhausted()
            return message
        }
    }
}

data class StrokePack(
    val exemplarOrCritiqueId: Long,
    val packIndex: Int,
    val packCount: Int,
    val strokes: List<Stroke>,
    val correction: Boolean,
) : LinkMessage {
    override val type: MessageType = if (correction) MessageType.CORRECTION_PACK else MessageType.STROKE_PACK

    init {
        require(packCount in 1..255)
        require(packIndex in 0 until packCount)
        require(strokes.isNotEmpty() && strokes.size <= Limits.MAX_STROKES_PER_PACK)
    }

    override fun encodeBody(): ByteArray = ByteWriter().apply {
        u32(exemplarOrCritiqueId)
        u16(packIndex)
        u16(packCount)
        u16(strokes.size)
        for (stroke in strokes) {
            u8(stroke.layer.code)
            u8(stroke.widthPx)
            u16(stroke.points.size)
            for (point in stroke.points) {
                i16(point.x)
                i16(point.y)
            }
        }
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray, correction: Boolean): StrokePack {
            val r = ByteReader(payload)
            val id = r.u32()
            val index = r.u16()
            val count = r.u16()
            val strokeCount = r.u16()
            val strokes = List(strokeCount) {
                val layer = InkLayer.fromCode(r.u8())
                    ?: throw ProtocolException(ErrorCode.BAD_FRAME, "ink layer")
                val width = r.u8()
                val points = List(r.u16()) { PaperPoint(r.i16(), r.i16()) }
                Stroke(layer, width, points)
            }
            r.expectExhausted()
            return StrokePack(id, index, count, strokes, correction)
        }
    }
}

object MessageCodec {
    fun decode(type: MessageType, payload: ByteArray): LinkMessage = when (type) {
        MessageType.HELLO -> Hello.decode(payload)
        MessageType.SET_MODE -> SetMode.decode(payload)
        MessageType.EXEMPLAR_META -> ExemplarMeta.decode(payload)
        MessageType.CALIBRATE_START -> CalibrateStart.decode(payload)
        MessageType.CALIBRATE_COMMIT -> CalibrateCommit.decode(payload)
        MessageType.CAPTURE_NOW -> CaptureNow.decode(payload)
        MessageType.SHOW_ADVICE -> ShowAdvice.decode(payload)
        MessageType.CLEAR_OVERLAY -> ClearOverlay.decode(payload)
        MessageType.BEGIN_WIFI_SESSION -> BeginWifiSession.decode(payload)
        MessageType.END_WIFI_SESSION -> EndWifiSession.decode(payload)
        MessageType.PING -> Ping.decode(payload)
        MessageType.SET_HUD -> SetHud.decode(payload)
        MessageType.SLEEP_HINT -> SleepHint.decode(payload)
        MessageType.STROKE_PACK -> StrokePack.decode(payload, correction = false)
        MessageType.CORRECTION_PACK -> StrokePack.decode(payload, correction = true)
        MessageType.HELLO_ACK -> HelloAck.decode(payload)
        MessageType.STATE -> StateReport.decode(payload)
        MessageType.TOUCH -> TouchReport.decode(payload)
        MessageType.CAPTURE_READY -> CaptureReady.decode(payload)
        MessageType.ERROR -> ErrorReport.decode(payload)
        MessageType.PONG -> Pong.decode(payload)
        MessageType.ACK -> Ack.decode(payload)
        MessageType.WIFI_READY -> WifiReady.decode(payload)
        MessageType.WIFI_CLOSED -> WifiClosed.decode(payload)
        MessageType.SESSION_INFO -> SessionInfo.decode(payload)
    }
}

private fun enumRole(code: Int): PeerRole =
    PeerRole.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "role $code")

private fun enumThermal(code: Int): Thermal =
    Thermal.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "thermal $code")

private fun enumMode(code: Int): HudMode =
    HudMode.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "mode $code")

private fun enumPaper(code: Int): PaperProfile =
    PaperProfile.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "paper $code")

private fun enumCalib(code: Int): CalibMethod =
    CalibMethod.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "calib $code")

private fun enumCapture(code: Int): CaptureReason =
    CaptureReason.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "capture $code")

private fun enumWifi(code: Int): WifiPurpose =
    WifiPurpose.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "wifi purpose $code")

private fun enumPhase(code: Int): LinkPhase =
    LinkPhase.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "phase $code")

private fun enumCamera(code: Int): CameraState =
    CameraState.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "camera $code")

private fun enumTouch(code: Int): TouchAction =
    TouchAction.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "touch $code")

private fun enumAck(code: Int): AckStatus =
    AckStatus.fromCode(code) ?: throw ProtocolException(ErrorCode.BAD_FRAME, "ack $code")
