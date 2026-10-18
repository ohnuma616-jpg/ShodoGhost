package dev.shodoghost.protocol.loopback

import dev.shodoghost.protocol.BeginWifiSession
import dev.shodoghost.protocol.StrokePack
import dev.shodoghost.protocol.link.AttFraming
import dev.shodoghost.protocol.link.AttOp
import dev.shodoghost.protocol.link.GlassListener
import dev.shodoghost.protocol.link.GlassRadio
import dev.shodoghost.protocol.link.GlassSession
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * グラスの GATT セッションを TCP 1 本で 1 クライアントに出す。
 * iPhone 側のループバック試験が接続する。オンエアの BLE ではない。
 */
object LoopbackGlassHost {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toInt() ?: 18771
        val ready = args.getOrNull(1)?.let { File(it) }
        val server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        ready?.writeText("listening $port\n")
        println("LISTENING $port")
        System.out.flush()
        server.use {
            val socket = server.accept()
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val radio = object : GlassRadio {
                override fun notifyStatus(frame: ByteArray) = AttFraming.write(output, AttOp.STATUS, frame)
                override fun notifyPayload(frame: ByteArray) = AttFraming.write(output, AttOp.PAYLOAD_NOTIFY, frame)
            }
            val session = GlassSession(
                radio,
                object : GlassListener() {
                    override fun onStrokePack(pack: StrokePack) {
                        println("EVENT stroke ${pack.exemplarOrCritiqueId} ${pack.strokes.size}")
                    }

                    override fun onWifiBegin(session: BeginWifiSession) {
                        val ip = session.ipv4.joinToString(".") { (it.toInt() and 0xFF).toString() }
                        println("EVENT wifi $ip:${session.port}")
                    }
                },
            )
            session.startAdvertising()
            socket.use {
                while (true) {
                    val record = AttFraming.read(input) ?: break
                    when (record.op) {
                        AttOp.BOND -> session.onBonded()
                        AttOp.LINK_UP -> {
                            val mtu = record.payload[0].toInt() and 0xFF or ((record.payload[1].toInt() and 0xFF) shl 8)
                            session.onLinkUp(mtu)
                        }
                        AttOp.LINK_DOWN -> session.onLinkDown()
                        AttOp.COMMAND -> session.onCommand(record.payload)
                        AttOp.TOUCH -> session.onTouch(record.payload)
                        AttOp.PAYLOAD_WRITE -> session.onPayload(record.payload)
                        AttOp.READ_REQ -> AttFraming.write(output, AttOp.READ_RSP, session.sessionInfoFrame())
                    }
                }
            }
        }
    }
}
