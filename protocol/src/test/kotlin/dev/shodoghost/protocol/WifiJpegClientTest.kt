package dev.shodoghost.protocol

import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WifiJpegClientTest {
    @Test
    fun uploadsTheHeldJpeg() {
        val jpeg = ByteArray(20_000) { (it % 251).toByte() }
        val token = ByteArray(Limits.WIFI_TOKEN_BYTES) { (it + 3).toByte() }
        val server = ServerSocket(0)
        val received = CompletableFuture<ByteArray>()
        val thread = Thread {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                readUntil(input)
                output.write("HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray())
                output.flush()
                val hello = String(readFrame(input), Charsets.UTF_8)
                assertTrue(hello.contains("token=" + token.joinToString("") { "%02x".format(it) }))
                writeFrame(output, "op=hello_ack\nok=1\n")
                val begin = String(readFrame(input), Charsets.UTF_8)
                assertTrue(begin.contains("bytes=${jpeg.size}"))
                assertTrue(begin.contains("crc32=${Crc32.ieee(jpeg)}"))
                val body = ArrayList<Byte>()
                while (true) {
                    val payload = readFrame(input)
                    val text = payload.toString(Charsets.UTF_8)
                    if (text.startsWith("op=jpeg_end")) break
                    body.addAll(payload.toList())
                }
                writeFrame(output, "op=bye\n")
                received.complete(body.toByteArray())
            }
        }
        thread.start()
        try {
            WifiJpegClient.upload("127.0.0.1", server.localPort, token, jpeg, 32, 24)
            assertContentEquals(jpeg, received.get(5, TimeUnit.SECONDS))
        } finally {
            server.close()
            thread.join(5_000)
        }
    }

    @Test
    fun formatsIpv4() {
        assertEquals("172.20.10.1", WifiJpegClient.host(byteArrayOf(172.toByte(), 20, 10, 1)))
    }
}

private fun readUntil(input: InputStream) {
    val tail = ArrayDeque<Int>()
    while (true) {
        val one = input.read()
        check(one >= 0)
        tail.addLast(one)
        if (tail.size > 4) tail.removeFirst()
        if (tail.toList() == listOf(13, 10, 13, 10)) return
    }
}

private fun readFrame(input: InputStream): ByteArray {
    val b0 = input.read()
    val b1 = input.read()
    check(b0 >= 0 && b1 >= 0)
    var length = b1 and 0x7F
    if (length == 126) {
        val hi = input.read()
        val lo = input.read()
        length = (hi shl 8) or lo
    }
    val masked = b1 and 0x80 != 0
    val mask = if (masked) ByteArray(4) { input.read().toByte() } else ByteArray(0)
    val payload = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val n = input.read(payload, offset, length - offset)
        check(n > 0)
        offset += n
    }
    if (masked) {
        for (index in payload.indices) {
            payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
    }
    return payload
}

private fun writeFrame(output: java.io.OutputStream, text: String) {
    val payload = text.toByteArray(Charsets.UTF_8)
    output.write(0x81)
    output.write(payload.size)
    output.write(payload)
    output.flush()
}
