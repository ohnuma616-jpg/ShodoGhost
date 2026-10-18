package dev.shodoghost.protocol.link

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * モック GATT のパイプ。オンエアのフレーム形式とは別で、テストとループバックだけが使う。
 * 1 レコードは op 1 バイト、長さ 4 バイト（リトルエンディアン）、本体。
 */
object AttOp {
    const val COMMAND: Int = 1
    const val STATUS: Int = 2
    const val TOUCH: Int = 3
    const val PAYLOAD_WRITE: Int = 4
    const val PAYLOAD_NOTIFY: Int = 5
    const val READ_REQ: Int = 6
    const val READ_RSP: Int = 7
    const val LINK_UP: Int = 8
    const val LINK_DOWN: Int = 9
    const val BOND: Int = 10
}

object AttFraming {
    fun write(out: OutputStream, op: Int, payload: ByteArray) {
        val header = ByteArray(5)
        header[0] = (op and 0xFF).toByte()
        val len = payload.size
        header[1] = (len and 0xFF).toByte()
        header[2] = ((len ushr 8) and 0xFF).toByte()
        header[3] = ((len ushr 16) and 0xFF).toByte()
        header[4] = ((len ushr 24) and 0xFF).toByte()
        synchronized(out) {
            out.write(header)
            out.write(payload)
            out.flush()
        }
    }

    fun read(input: InputStream): AttRecord? {
        val header = ByteArray(5)
        if (!readFully(input, header)) return null
        val op = header[0].toInt() and 0xFF
        val len = (header[1].toInt() and 0xFF) or
            ((header[2].toInt() and 0xFF) shl 8) or
            ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 24)
        if (len < 0 || len > 1_048_576) {
            throw IllegalStateException("att record length $len")
        }
        val payload = ByteArray(len)
        if (!readFully(input, payload)) return null
        return AttRecord(op, payload)
    }

    private fun readFully(input: InputStream, dest: ByteArray): Boolean {
        var offset = 0
        while (offset < dest.size) {
            val n = input.read(dest, offset, dest.size - offset)
            if (n < 0) {
                if (offset == 0) return false
                throw EOFException("att record truncated")
            }
            offset += n
        }
        return true
    }
}

data class AttRecord(val op: Int, val payload: ByteArray)
