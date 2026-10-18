package dev.shodoghost.protocol

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * グラスから iPhone の待受へ JPEG を送る。
 * フレームは [WifiUploader] と同じで、応答はマスクしない。
 */
object WifiJpegClient {
    private val mask = byteArrayOf(1, 2, 3, 4)
    private val key = Base64.getEncoder().encodeToString(ByteArray(16) { 7 })

    fun upload(
        host: String,
        port: Int,
        token: ByteArray,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        connected: () -> Unit = {},
    ) {
        require(token.size == Limits.WIFI_TOKEN_BYTES)
        require(jpeg.isNotEmpty() && jpeg.size <= Limits.MAX_JPEG_BYTES)
        val socket = Socket()
        socket.soTimeout = Limits.WIFI_HARD_TIMEOUT_MS.toInt()
        socket.connect(InetSocketAddress(host, port), Limits.WIFI_HARD_TIMEOUT_MS.toInt())
        socket.use {
            val input = it.getInputStream()
            val output = it.getOutputStream()
            output.write(handshake(host, port).toByteArray(Charsets.US_ASCII))
            output.flush()
            readUntil(input, byteArrayOf(13, 10, 13, 10))
            connected()
            writeText(output, WifiText.encode(WifiSession.OP_HELLO, mapOf("token" to hex(token))))
            readFrame(input)
            writeText(
                output,
                WifiText.encode(
                    WifiSession.OP_JPEG_BEGIN,
                    linkedMapOf(
                        "bytes" to jpeg.size.toString(),
                        "width" to width.toString(),
                        "height" to height.toString(),
                        "crc32" to Crc32.ieee(jpeg).toString(),
                    ),
                ),
            )
            var offset = 0
            while (offset < jpeg.size) {
                val end = minOf(offset + Limits.WIFI_CHUNK_BYTES, jpeg.size)
                writeFrame(output, 0x2, jpeg.copyOfRange(offset, end))
                offset = end
            }
            writeText(output, WifiText.encode(WifiSession.OP_JPEG_END))
            readFrame(input)
        }
    }

    fun host(ipv4: ByteArray): String {
        require(ipv4.size == 4)
        return ipv4.joinToString(".") { (it.toInt() and 0xff).toString() }
    }

    private fun handshake(host: String, port: Int): String = buildString {
        append("GET ${WifiSession.PATH} HTTP/1.1\r\n")
        append("Host: $host:$port\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: $key\r\n")
        append("Sec-WebSocket-Protocol: ${WifiSession.SUBPROTOCOL}\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("\r\n")
    }

    private fun writeText(output: OutputStream, text: String) {
        writeFrame(output, 0x1, text.toByteArray(Charsets.UTF_8))
    }

    private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        val masked = 0x80
        when {
            payload.size < 126 -> output.write(masked or payload.size)
            payload.size <= 0xFFFF -> {
                output.write(masked or 126)
                output.write(payload.size shr 8)
                output.write(payload.size and 0xFF)
            }
            else -> throw ProtocolException(ErrorCode.PAYLOAD_TOO_LARGE, "chunk")
        }
        output.write(mask)
        val maskedPayload = ByteArray(payload.size)
        for (index in payload.indices) {
            maskedPayload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        output.write(maskedPayload)
        output.flush()
    }

    private fun readFrame(input: InputStream): ByteArray {
        val header = input.readExact(2)
        var length = header[1].toInt() and 0x7F
        if (length == 126) {
            val ext = input.readExact(2)
            length = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
        }
        return input.readExact(length)
    }

    private fun readUntil(input: InputStream, marker: ByteArray) {
        val seen = ArrayList<Byte>(256)
        while (seen.size < 8192) {
            val one = input.read()
            if (one < 0) throw ProtocolException(ErrorCode.WIFI_TIMEOUT, "header")
            seen.add(one.toByte())
            if (seen.size >= marker.size && seen.subList(seen.size - marker.size, seen.size) == marker.toList()) {
                return
            }
        }
        throw ProtocolException(ErrorCode.BAD_FRAME, "header")
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

private fun InputStream.readExact(count: Int): ByteArray {
    if (count == 0) return ByteArray(0)
    val buf = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val n = read(buf, offset, count - offset)
        if (n < 0) throw ProtocolException(ErrorCode.WIFI_TIMEOUT, "read")
        offset += n
    }
    return buf
}
