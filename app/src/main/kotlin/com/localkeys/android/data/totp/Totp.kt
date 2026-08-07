package com.localkeys.android.data.totp

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP (RFC 6238) — os códigos de 6 dígitos que trocam a cada 30 s.
 *
 * Espelho do `totp.rs` do desktop: HMAC-SHA1, 6 dígitos, período 30 s, skew=1,
 * aceita segredos de 80 bits (16 chars base32). A chave vem em base32 (o que
 * apps como Google Authenticator dão); o código é gerado sob demanda.
 */
object Totp {
    const val PERIOD: Long = 30
    const val DIGITS: Int = 6

    /** Limpa a chave como o desktop: trim, remove espaços/hífens, uppercase. */
    fun sanitize(secretB32: String): String =
        secretB32.trim().replace(" ", "").replace("-", "").uppercase()

    /**
     * Código para o instante `unixSeconds` (segundos desde a época). Erro se a
     * chave não for base32 válida. `digits` é parametrizado para os vetores do
     * RFC 6238 (8 dígitos); em uso real é sempre 6. Assim como o `totp-rs` do
     * desktop, divide o tempo por 30 internamente.
     */
    fun generate(secretB32: String, unixSeconds: Long, digits: Int = DIGITS): String {
        val cleaned = sanitize(secretB32)
        if (cleaned.isEmpty()) throw IllegalArgumentException("chave TOTP vazia")
        val keyBytes = Base32.decode(cleaned)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))

        // Counter (big-endian 64 bits) do passo de tempo.
        val counter = ByteArray(8)
        var value = unixSeconds / PERIOD
        for (i in 7 downTo 0) {
            counter[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        val hmac = mac.doFinal(counter)

        // Dynamic truncation (RFC 4226): usa os 4 bits baixos do último byte
        // como offset e lê 31 bits como inteiro.
        val offset = hmac[hmac.size - 1].toInt() and 0x0F
        val binCode = ((hmac[offset].toInt() and 0x7F) shl 24) or
            ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
            ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
            (hmac[offset + 3].toInt() and 0xFF)
        val modulus = binCode % pow10(digits)
        return modulus.toString().padStart(digits, '0')
    }

    /**
     * Código + tempo restante para o instante `unixSeconds` (função pura do
     * relógio — quem mostra TOTP chama com o segundo do relógio compartilhado).
     */
    fun at(secretB32: String, unixSeconds: Long): TotpCode = TotpCode(
        code = generate(secretB32, unixSeconds, DIGITS),
        period = PERIOD,
        secondsRemaining = PERIOD - (unixSeconds % PERIOD),
    )

    /** Código atual + quanto falta para virar. */
    fun now(secretB32: String): TotpCode = at(secretB32, System.currentTimeMillis() / 1000)

    private fun pow10(n: Int): Int {
        var p = 1
        repeat(n) { p *= 10 }
        return p
    }
}

data class TotpCode(
    /** Código atual (6 dígitos, como string para preservar zeros à esquerda). */
    val code: String,
    /** Passo em segundos (30). */
    val period: Long,
    /** Segundos restantes até o código trocar. */
    val secondsRemaining: Long,
)

/** Base32 (RFC 4648) — decodifica o segredo que o app de TOTP mostrou. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(encoded: String): ByteArray {
        val cleaned = encoded.uppercase().filter { it != '=' }
        if (cleaned.isEmpty()) return ByteArray(0)
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>(cleaned.length * 5 / 8)
        for (ch in cleaned) {
            val value = ALPHABET.indexOf(ch)
            if (value < 0) throw IllegalArgumentException("chave base32 inválida: '$ch'")
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer ushr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
