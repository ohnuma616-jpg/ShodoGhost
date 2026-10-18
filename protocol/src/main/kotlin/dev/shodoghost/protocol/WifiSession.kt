package dev.shodoghost.protocol

/**
 * Wi-Fi バーストの契約。実装は Phase 2 以降。
 * テキストフレームは1行のキー値、バイナリフレームは JPEG 断片。
 */
object WifiSession {
    const val PATH: String = "/v1/session"
    const val SUBPROTOCOL: String = "shodo-ghost.v1"

    const val OP_HELLO: String = "hello"
    const val OP_HELLO_ACK: String = "hello_ack"
    const val OP_JPEG_BEGIN: String = "jpeg_begin"
    const val OP_JPEG_END: String = "jpeg_end"
    const val OP_RESULT: String = "result"
    const val OP_BYE: String = "bye"
    const val OP_ERROR: String = "error"
}

/**
 * テキストフレームは `op` を1行目に置き、続きを `key=value` の改行区切りにする。
 * JSON ライブラリを両者に持ち込まないための、依存ゼロの表現。
 */
object WifiText {
    fun encode(op: String, fields: Map<String, String> = emptyMap()): String {
        val body = buildString {
            append("op=")
            append(op)
            append('\n')
            for ((key, value) in fields) {
                require(!key.contains('=') && !key.contains('\n') && !value.contains('\n')) {
                    "wifi field must be a single line"
                }
                append(key)
                append('=')
                append(value)
                append('\n')
            }
        }
        return body
    }

    fun decode(text: String): WifiTextFrame {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            throw ProtocolException(ErrorCode.BAD_FRAME, "empty wifi text")
        }
        val fields = linkedMapOf<String, String>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq <= 0) {
                throw ProtocolException(ErrorCode.BAD_FRAME, "wifi line missing key")
            }
            fields[line.substring(0, eq)] = line.substring(eq + 1)
        }
        val op = fields.remove("op") ?: throw ProtocolException(ErrorCode.BAD_FRAME, "wifi op missing")
        return WifiTextFrame(op, fields)
    }
}

data class WifiTextFrame(val op: String, val fields: Map<String, String>)
