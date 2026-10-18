package dev.shodoghost.protocol.link

import dev.shodoghost.protocol.Ack
import dev.shodoghost.protocol.AckStatus
import dev.shodoghost.protocol.BeginWifiSession
import dev.shodoghost.protocol.CalibrateCommit
import dev.shodoghost.protocol.CalibrateStart
import dev.shodoghost.protocol.CaptureNow
import dev.shodoghost.protocol.CaptureReady
import dev.shodoghost.protocol.ClearOverlay
import dev.shodoghost.protocol.EndWifiSession
import dev.shodoghost.protocol.ErrorCode
import dev.shodoghost.protocol.ErrorReport
import dev.shodoghost.protocol.ExemplarMeta
import dev.shodoghost.protocol.FrameCodec
import dev.shodoghost.protocol.Hello
import dev.shodoghost.protocol.HelloAck
import dev.shodoghost.protocol.HwFlags
import dev.shodoghost.protocol.Inbound
import dev.shodoghost.protocol.InboundAssembler
import dev.shodoghost.protocol.Limits
import dev.shodoghost.protocol.LinkMessage
import dev.shodoghost.protocol.LinkPhase
import dev.shodoghost.protocol.LinkSignal
import dev.shodoghost.protocol.LinkStateMachine
import dev.shodoghost.protocol.OverlayBits
import dev.shodoghost.protocol.Ping
import dev.shodoghost.protocol.Pong
import dev.shodoghost.protocol.Protocol
import dev.shodoghost.protocol.SessionInfo
import dev.shodoghost.protocol.SetHud
import dev.shodoghost.protocol.SetMode
import dev.shodoghost.protocol.ShowAdvice
import dev.shodoghost.protocol.SleepHint
import dev.shodoghost.protocol.StateReport
import dev.shodoghost.protocol.StrokePack
import dev.shodoghost.protocol.Thermal
import dev.shodoghost.protocol.TouchReport
import dev.shodoghost.protocol.CameraState
import dev.shodoghost.protocol.HudMode
import dev.shodoghost.protocol.PaperProfile
import dev.shodoghost.protocol.WifiClosed
import dev.shodoghost.protocol.WifiReady

interface GlassRadio {
    fun notifyStatus(frame: ByteArray)
    fun notifyPayload(frame: ByteArray)
}

open class GlassListener {
    open fun onRemoteTouch(report: TouchReport) {}
    open fun onCaptureRequested(request: CaptureNow) {}
    open fun onModeChanged(mode: HudMode, paper: PaperProfile) {}
    open fun onExemplarMeta(meta: ExemplarMeta) {}
    open fun onStrokePack(pack: StrokePack) {}
    open fun onCalibrateStart(start: CalibrateStart) {}
    open fun onCalibrateCommit(commit: CalibrateCommit) {}
    open fun onAdvice(lines: List<String>) {}
    open fun onClear(mask: Int) {}
    open fun onHud(hud: SetHud) {}
    open fun onSleep(delayMs: Int) {}
    open fun onWifiBegin(session: BeginWifiSession) {}
    open fun onWifiEnd(reason: Int) {}
}

/**
 * グラス側の GATT サーバが呼ぶセッション。
 * BluetoothGattServer への委譲は UUID で [onCommand] / [onTouch] / [onPayload] / [sessionInfoFrame] に振り分けるだけ。
 */
class GlassSession(
    private val radio: GlassRadio,
    val listener: GlassListener = GlassListener(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    var batteryPercent: Int = 80
    var batteryMillivolts: Int = 3900
    var thermal: Thermal = Thermal.OK
    var hwFlags: Int = HwFlags.MAIN_CAMERA or HwFlags.WIFI
    var camera: CameraState = CameraState.CLOSED
        private set
    var mode: HudMode = HudMode.IDLE
        private set
    var paper: PaperProfile = PaperProfile.HANSHI
        private set
    var phase: LinkPhase = LinkPhase.IDLE
        private set
    var attMtu: Int = 0
        private set
    var overlayBits: Int = 0
        private set
    var bonded: Boolean = false
        private set

    private val startedAt = nowMs()
    private val commandIn = InboundAssembler()
    private val payloadIn = InboundAssembler()
    private var outSeq = 1
    private val lock = Any()

    fun startAdvertising() = synchronized(lock) {
        advance(LinkSignal.START)
    }

    fun onBonded() = synchronized(lock) {
        bonded = true
    }

    fun onLinkUp(attMtu: Int) = synchronized(lock) {
        this.attMtu = attMtu
        if (phase == LinkPhase.IDLE || phase == LinkPhase.BACKOFF) advance(LinkSignal.START)
        if (phase == LinkPhase.DISCOVERING) advance(LinkSignal.PEER_FOUND)
        if (phase == LinkPhase.CONNECTING) advance(LinkSignal.GATT_CONNECTED)
        if (attMtu < Limits.MIN_ATT_MTU) {
            advance(LinkSignal.HELLO_REJECTED)
        }
    }

    fun onLinkDown() = synchronized(lock) {
        bonded = false
        attMtu = 0
        advance(LinkSignal.LINK_LOST)
    }

    fun onCommand(bytes: ByteArray) = synchronized(lock) {
        if (!acceptsWrites()) return
        when (val inbound = commandIn.push(bytes)) {
            is Inbound.Pending -> Unit
            is Inbound.Rejected -> sendError(inbound.code, inbound.detail)
            is Inbound.Message -> handleCommand(inbound.message.message, inbound.message.sequence, inbound.message.ackRequested)
        }
    }

    fun onTouch(bytes: ByteArray) = synchronized(lock) {
        if (!acceptsWrites() || phase == LinkPhase.NEGOTIATING) return
        when (val inbound = InboundAssembler().push(bytes)) {
            is Inbound.Message -> {
                val message = inbound.message.message
                if (message is TouchReport) listener.onRemoteTouch(message)
            }
            is Inbound.Rejected -> sendError(inbound.code, inbound.detail)
            is Inbound.Pending -> Unit
        }
    }

    fun onPayload(bytes: ByteArray) = synchronized(lock) {
        if (!acceptsWrites() || phase == LinkPhase.NEGOTIATING) return
        when (val inbound = payloadIn.push(bytes)) {
            is Inbound.Pending -> Unit
            is Inbound.Rejected -> sendError(inbound.code, inbound.detail)
            is Inbound.Message -> handlePayload(inbound.message.message)
        }
    }

    fun sessionInfoFrame(): ByteArray = synchronized(lock) {
        encode(SessionInfo(Limits.PROTOCOL_VERSION, hwFlags, batteryPercent, thermal, phase))
    }

    fun noteCamera(state: CameraState) = synchronized(lock) {
        camera = state
        publishState()
    }

    fun publishCaptureReady(message: CaptureReady) = synchronized(lock) {
        camera = CameraState.CLOSED
        notifyStatus(encode(message))
        publishState()
    }

    fun publishWifiReady(ok: Boolean) = synchronized(lock) {
        if (ok && phase == LinkPhase.WIFI_WAKE) advance(LinkSignal.SOCKET_UP)
        notifyStatus(encode(WifiReady(ok)))
    }

    fun publishWifiClosed(reason: Int) = synchronized(lock) {
        finishWifi()
        notifyStatus(encode(WifiClosed(reason)))
        publishState()
    }

    fun publishLocalTouch(report: TouchReport) = synchronized(lock) {
        notifyStatus(encode(report))
    }

    private fun acceptsWrites(): Boolean {
        if (!bonded || attMtu < Limits.MIN_ATT_MTU) return false
        return phase == LinkPhase.NEGOTIATING ||
            phase == LinkPhase.BLE_READY ||
            phase == LinkPhase.WIFI_WAKE ||
            phase == LinkPhase.TRANSFER ||
            phase == LinkPhase.WIFI_TEARDOWN
    }

    private fun handleCommand(message: LinkMessage, sequence: Int, ackRequested: Boolean) {
        if (phase == LinkPhase.NEGOTIATING && message !is Hello) return
        if (message is Hello && phase != LinkPhase.NEGOTIATING) return
        when (message) {
            is Hello -> onHello(message)
            is SetMode -> {
                mode = message.mode
                paper = message.paper
                ack(sequence, message, ackRequested, AckStatus.OK)
                listener.onModeChanged(mode, paper)
                publishState()
            }
            is ExemplarMeta -> {
                listener.onExemplarMeta(message)
                publishState()
            }
            is CalibrateStart -> listener.onCalibrateStart(message)
            is CalibrateCommit -> {
                overlayBits = overlayBits or OverlayBits.CROSSHAIR
                ack(sequence, message, ackRequested, AckStatus.OK)
                listener.onCalibrateCommit(message)
                publishState()
            }
            is CaptureNow -> onCapture(message, sequence, ackRequested)
            is ShowAdvice -> {
                overlayBits = overlayBits or OverlayBits.ADVICE
                listener.onAdvice(message.lines)
                publishState()
            }
            is ClearOverlay -> {
                overlayBits = if (message.mask == OverlayBits.ALL) 0 else overlayBits and message.mask.inv()
                listener.onClear(message.mask)
                publishState()
            }
            is BeginWifiSession -> {
                if (phase != LinkPhase.BLE_READY) {
                    ack(sequence, message, ackRequested, AckStatus.BUSY)
                    return
                }
                advance(LinkSignal.BEGIN_WIFI)
                ack(sequence, message, ackRequested, AckStatus.OK)
                listener.onWifiBegin(message)
            }
            is EndWifiSession -> {
                finishWifi()
                ack(sequence, message, ackRequested, AckStatus.OK)
                listener.onWifiEnd(message.reason)
                publishState()
            }
            is Ping -> notifyStatus(encode(Pong(message.cookie, (nowMs() - startedAt) / 1000)))
            is SetHud -> listener.onHud(message)
            is SleepHint -> listener.onSleep(message.displayOffDelayMs)
            else -> sendError(ErrorCode.UNSUPPORTED_MODE, message.type.name)
        }
    }

    private fun handlePayload(message: LinkMessage) {
        if (message is StrokePack) {
            overlayBits = overlayBits or if (message.correction) OverlayBits.CORRECTION else OverlayBits.EXEMPLAR
            listener.onStrokePack(message)
            publishState()
        }
    }

    private fun onHello(hello: Hello) {
        if (hello.version != Limits.PROTOCOL_VERSION || attMtu < Limits.MIN_ATT_MTU) {
            notifyStatus(
                encode(
                    HelloAck(
                        version = Limits.PROTOCOL_VERSION,
                        accept = false,
                        batteryPercent = batteryPercent,
                        thermal = thermal,
                        batteryMillivolts = batteryMillivolts,
                        hwFlags = hwFlags,
                        nonce = hello.nonce,
                    ),
                ),
            )
            advance(LinkSignal.HELLO_REJECTED)
            return
        }
        notifyStatus(
            encode(
                HelloAck(
                    version = Limits.PROTOCOL_VERSION,
                    accept = true,
                    batteryPercent = batteryPercent,
                    thermal = thermal,
                    batteryMillivolts = batteryMillivolts,
                    hwFlags = hwFlags,
                    nonce = hello.nonce,
                ),
            ),
        )
        advance(LinkSignal.HELLO_OK)
        publishState()
    }

    private fun onCapture(message: CaptureNow, sequence: Int, ackRequested: Boolean) {
        if (thermal == Thermal.HOT) {
            ack(sequence, message, ackRequested, AckStatus.BUSY)
            sendError(ErrorCode.THERMAL, "hot")
            return
        }
        if (camera != CameraState.CLOSED) {
            ack(sequence, message, ackRequested, AckStatus.BUSY)
            sendError(ErrorCode.CAMERA_BUSY, "camera")
            return
        }
        ack(sequence, message, ackRequested, AckStatus.OK)
        listener.onCaptureRequested(message)
    }

    private fun finishWifi() {
        when (phase) {
            LinkPhase.WIFI_WAKE -> advance(LinkSignal.STOP)
            LinkPhase.TRANSFER -> advance(LinkSignal.TRANSFER_FINISHED)
            else -> Unit
        }
        if (phase == LinkPhase.WIFI_TEARDOWN) advance(LinkSignal.WIFI_TORN_DOWN)
    }

    private fun ack(sequence: Int, message: LinkMessage, requested: Boolean, status: AckStatus) {
        if (!requested) return
        notifyStatus(encode(Ack(sequence, message.type, status)))
    }

    private fun publishState() {
        notifyStatus(
            encode(
                StateReport(
                    mode = mode,
                    phase = phase,
                    thermal = thermal,
                    batteryPercent = batteryPercent,
                    camera = camera,
                    overlayBits = overlayBits,
                    batteryMillivolts = batteryMillivolts,
                ),
            ),
        )
    }

    private fun sendError(code: ErrorCode, detail: String) {
        val clipped = detail.encodeToByteArray().let { raw ->
            if (raw.size <= Limits.MAX_ERROR_BYTES) detail else raw.copyOf(Limits.MAX_ERROR_BYTES).decodeToString()
        }
        notifyStatus(encode(ErrorReport(code, clipped)))
    }

    private fun encode(message: LinkMessage): ByteArray {
        val mtu = if (attMtu >= Limits.MIN_ATT_MTU) attMtu else Limits.MIN_ATT_MTU
        val outbound = Protocol.encode(message, outSeq, mtu)
        outSeq = outbound.nextSequence
        check(outbound.frames.size == 1) { "${message.type} must fit in one status frame" }
        return outbound.frames.single()
    }

    private fun notifyStatus(frame: ByteArray) {
        check(frame.size <= FrameCodec.maxValueLength(if (attMtu >= Limits.MIN_ATT_MTU) attMtu else Limits.MIN_ATT_MTU))
        radio.notifyStatus(frame)
    }

    private fun advance(signal: LinkSignal) {
        val step = LinkStateMachine.step(phase, signal)
        if (step.accepted) phase = step.phase
    }
}
