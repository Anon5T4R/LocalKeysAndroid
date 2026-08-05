package com.localkeys.android.data.crypto

/**
 * Layout do arquivo `.tkeys` — espelho exato do `crypto.rs` do desktop.
 *
 * Tudo little-endian:
 * ```
 *   MAGIC "TKEYS\0"  (6)  ─┐
 *   format_version   (1)   │
 *   kdf_id           (1)   ├─ HEADER (60 bytes) — em claro, usado como AAD
 *   m_cost u32       (4)   │
 *   t_cost u32       (4)   │
 *   p_cost u32       (4)   │
 *   salt             (16)  │
 *   nonce            (24) ─┘
 *   ciphertext + tag (n)      ← XChaCha20-Poly1305(JSON do vault), tag de 16 no fim
 * ```
 * A chave (32 bytes) é derivada com Argon2id a partir da master password; os
 * parâmetros do KDF ficam no header em claro. Qualquer bit trocado no header
 * quebra a autenticação (o header inteiro é AAD).
 */
object TkeysFormat {
    const val MAGIC_LEN: Int = 6
    val MAGIC: ByteArray = byteArrayOf('T'.code.toByte(), 'K'.code.toByte(), 'E'.code.toByte(), 'Y'.code.toByte(), 'S'.code.toByte(), 0)
    const val FORMAT_VERSION: Int = 1
    const val KDF_ARGON2ID: Int = 1

    const val SALT_LEN: Int = 16
    const val NONCE_LEN: Int = 24
    const val KEY_LEN: Int = 32
    const val HEADER_LEN: Int = 6 + 1 + 1 + 4 + 4 + 4 + SALT_LEN + NONCE_LEN // = 60

    // Parâmetros default do Argon2id (iguais ao desktop): 64 MiB, 3 iterações, 1 lane.
    const val DEFAULT_M_COST_KIB: Long = 65_536
    const val DEFAULT_T_COST: Long = 3
    const val DEFAULT_P_COST: Long = 1

    fun defaultParams(): KdfParams = KdfParams(DEFAULT_M_COST_KIB, DEFAULT_T_COST, DEFAULT_P_COST)
}

/** Parâmetros do KDF de um vault (ficam no header em claro). */
data class KdfParams(
    val mCostKib: Long,
    val tCost: Long,
    val pCost: Long,
) {
    /** O libsodium pede memória em bytes (o Rust usa KiB). */
    val memLimitBytes: Long get() = mCostKib * 1024
}

/** Header parseado de um `.tkeys`. */
data class TkeysHeader(
    val version: Int,
    val kdfId: Int,
    val params: KdfParams,
    val salt: ByteArray,
    val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TkeysHeader) return false
        return version == other.version && kdfId == other.kdfId && params == other.params &&
            salt.contentEquals(other.salt) && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + kdfId
        result = 31 * result + params.hashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}

/** Monta os 60 bytes do header (a ordem exata importa: vira o AAD). */
fun buildHeader(params: KdfParams, salt: ByteArray, nonce: ByteArray): ByteArray {
    require(salt.size == TkeysFormat.SALT_LEN) { "salt deve ter ${TkeysFormat.SALT_LEN} bytes" }
    require(nonce.size == TkeysFormat.NONCE_LEN) { "nonce deve ter ${TkeysFormat.NONCE_LEN} bytes" }
    val out = ByteArray(TkeysFormat.HEADER_LEN)
    var pos = 0
    TkeysFormat.MAGIC.copyInto(out, pos); pos += TkeysFormat.MAGIC_LEN
    out[pos++] = TkeysFormat.FORMAT_VERSION.toByte()
    out[pos++] = TkeysFormat.KDF_ARGON2ID.toByte()
    writeLeU32(out, pos, params.mCostKib); pos += 4
    writeLeU32(out, pos, params.tCost); pos += 4
    writeLeU32(out, pos, params.pCost); pos += 4
    salt.copyInto(out, pos); pos += TkeysFormat.SALT_LEN
    nonce.copyInto(out, pos)
    return out
}

/** Valida o magic/versão/kdf e lê params+salt+nonce de um `.tkeys`. */
fun parseHeader(file: ByteArray): TkeysHeader {
    if (file.size < TkeysFormat.HEADER_LEN) throw TkeysError.BadFormat
    if (!TkeysFormat.MAGIC.contentEquals(file.copyOfRange(0, TkeysFormat.MAGIC_LEN))) {
        throw TkeysError.BadFormat
    }
    val version = file[6].toInt() and 0xFF
    if (version != TkeysFormat.FORMAT_VERSION) throw TkeysError.UnsupportedVersion(version)
    val kdfId = file[7].toInt() and 0xFF
    if (kdfId != TkeysFormat.KDF_ARGON2ID) throw TkeysError.BadFormat
    val params = KdfParams(
        mCostKib = readLeU32(file, 8),
        tCost = readLeU32(file, 12),
        pCost = readLeU32(file, 16),
    )
    return TkeysHeader(
        version = version,
        kdfId = kdfId,
        params = params,
        salt = file.copyOfRange(20, 36),
        nonce = file.copyOfRange(36, 60),
    )
}

private fun writeLeU32(out: ByteArray, offset: Int, value: Long) {
    val v = value.toInt()
    out[offset] = v.toByte()
    out[offset + 1] = (v ushr 8).toByte()
    out[offset + 2] = (v ushr 16).toByte()
    out[offset + 3] = (v ushr 24).toByte()
}

private fun readLeU32(file: ByteArray, offset: Int): Long {
    val b0 = file[offset].toInt() and 0xFF
    val b1 = file[offset + 1].toInt() and 0xFF
    val b2 = file[offset + 2].toInt() and 0xFF
    val b3 = file[offset + 3].toInt() and 0xFF
    return (b0.toLong()) or (b1.toLong() shl 8) or (b2.toLong() shl 16) or (b3.toLong() shl 24)
}
