package com.localkeys.android.data.crypto

/**
 * Codec hex puro (sem dependência de Android/JDK específico) — usado para
 * trocar bytes com a API String-encoded do lazysodium.
 */
object Hex {
    private const val HEX = "0123456789ABCDEF"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        require(s.length % 2 == 0) { "string hex de tamanho ímpar" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "string hex inválida" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
