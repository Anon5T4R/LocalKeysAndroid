package com.localkeys.android.data.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.exceptions.SodiumException
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong

/**
 * Erros do `.tkeys`, espelhando o `CryptoError` do desktop.
 * `Decrypt` é proposital: senha errada e arquivo adulterado caem no mesmo caso.
 */
sealed class TkeysError : Exception() {
    object BadFormat : TkeysError() {
        override val message: String get() = "arquivo não é um vault .tkeys válido"
    }
    class UnsupportedVersion(val version: Int) : TkeysError() {
        override val message: String get() = "versão de formato .tkeys não suportada: $version"
    }
    object Kdf : TkeysError() {
        override val message: String get() = "falha ao derivar a chave (KDF)"
    }
    object Decrypt : TkeysError() {
        override val message: String get() = "senha incorreta ou arquivo corrompido/adulterado"
    }
    object Corrupted : TkeysError() {
        override val message: String get() = "arquivo corrompido (estrutura inválida)"
    }
}

/**
 * Núcleo criptográfico — mesma composição do desktop: Argon2id (KDF) +
 * XChaCha20-Poly1305 (AEAD), primitivas do libsodium via lazysodium.
 *
 * `LazySodium` é a base comum de `LazySodiumAndroid` e `LazySodiumJava`, então
 * o mesmo código roda no Android e em teste JVM.
 */
class TkeysCrypto(private val ls: LazySodium) {

    /** Deriva a chave de 32 bytes da master password com Argon2id (V0x13). */
    fun deriveKey(password: String, salt: ByteArray, params: KdfParams): ByteArray {
        val hexKey = try {
            ls.cryptoPwHash(
                password,
                TkeysFormat.KEY_LEN,
                salt,
                params.tCost,
                NativeLong(params.memLimitBytes),
                PwHash.Alg.PWHASH_ALG_ARGON2ID13,
            )
        } catch (e: SodiumException) {
            throw TkeysError.Kdf
        }
        return Hex.decode(hexKey)
    }

    /**
     * Cifra `plaintext` com a chave bruta, sorteando nonce novo e montando
     * `header (60 bytes, em claro, vira AAD) || ciphertext+tag`.
     *
     * Usa as variantes `byte[]` do AEAD (`AEAD.Native`): as variantes `String`
     * codificam a mensagem/AAD em UTF-8, o que quebra a compatibilidade com o
     * desktop (que cifra os bytes crus do JSON e usa o header binário como AAD).
     */
    fun seal(key: ByteArray, salt: ByteArray, params: KdfParams, plaintext: ByteArray): ByteArray {
        val nonce = ls.randomBytesBuf(TkeysFormat.NONCE_LEN)
        return seal(key, salt, params, nonce, plaintext)
    }

    /** Cifra com um nonce dado (para testes de compatibilidade byte a byte). */
    fun seal(key: ByteArray, salt: ByteArray, params: KdfParams, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == TkeysFormat.KEY_LEN) { "chave deve ter ${TkeysFormat.KEY_LEN} bytes" }
        require(nonce.size == TkeysFormat.NONCE_LEN) { "nonce deve ter ${TkeysFormat.NONCE_LEN} bytes" }
        val header = buildHeader(params, salt, nonce)
        val cipher = ByteArray(plaintext.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val cipherLen = longArrayOf(0)
        val ok = try {
            ls.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                cipher, cipherLen,
                plaintext, plaintext.size.toLong(),
                header, header.size.toLong(),
                null, nonce, key,
            )
        } catch (e: SodiumException) {
            throw TkeysError.Kdf
        }
        if (!ok) throw TkeysError.Kdf
        val out = ByteArray(header.size + cipherLen[0].toInt())
        header.copyInto(out)
        cipher.copyInto(out, header.size, 0, cipherLen[0].toInt())
        return out
    }

    /**
     * Abre um `.tkeys` com a **chave bruta** (ex.: recuperada do cofre
     * biométrico do SO). Devolve plaintext + sessão viva (salt/params do
     * arquivo) — o caminho do desbloqueio rápido, sem re-rodar o Argon2.
     */
    fun openVaultWithKey(key: ByteArray, file: ByteArray): OpenedVault {
        val header = parseHeader(file)
        val plaintext = openWithKey(key, file)
        return OpenedVault(plaintext, SessionKey(ls, key, header.salt, header.params))
    }

    /** Decifra um `.tkeys` com a **chave bruta** (sem re-rodar o Argon2). */
    fun openWithKey(key: ByteArray, file: ByteArray): ByteArray {
        require(key.size == TkeysFormat.KEY_LEN) { "chave deve ter ${TkeysFormat.KEY_LEN} bytes" }
        val header = parseHeader(file)
        val ciphertext = file.copyOfRange(TkeysFormat.HEADER_LEN, file.size)
        if (ciphertext.size < AEAD.XCHACHA20POLY1305_IETF_ABYTES) throw TkeysError.Corrupted
        val aad = file.copyOfRange(0, TkeysFormat.HEADER_LEN)
        val plaintext = ByteArray(ciphertext.size - AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val plainLen = longArrayOf(0)
        val ok = try {
            ls.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                plaintext, plainLen,
                null,
                ciphertext, ciphertext.size.toLong(),
                aad, aad.size.toLong(),
                header.nonce, key,
            )
        } catch (e: SodiumException) {
            false
        }
        if (!ok) throw TkeysError.Decrypt
        return plaintext
    }

    /**
     * Cria um vault novo: deriva a chave da `password` e cifra o `plaintext`
     * inicial. Devolve o arquivo + a sessão (já fica "destrancado").
     */
    fun createVault(
        password: String,
        plaintext: ByteArray,
        params: KdfParams = TkeysFormat.defaultParams(),
    ): CreatedVault {
        val salt = ls.randomBytesBuf(TkeysFormat.SALT_LEN)
        val key = deriveKey(password, salt, params)
        val file = seal(key, salt, params, plaintext)
        return CreatedVault(file, SessionKey(ls, key, salt, params))
    }

    /**
     * Abre um `.tkeys`: valida o header, deriva a chave e decifra. Devolve o
     * plaintext e a sessão para salvar depois. Falha **genérica** em senha
     * errada OU adulteração.
     */
    fun openVault(password: String, file: ByteArray): OpenedVault {
        val header = parseHeader(file)
        val key = deriveKey(password, header.salt, header.params)
        val plaintext = openWithKey(key, file)
        return OpenedVault(plaintext, SessionKey(ls, key, header.salt, header.params))
    }
}

/** Resultado de criar um vault: bytes do arquivo cifrado + sessão viva. */
data class CreatedVault(
    val file: ByteArray,
    val session: SessionKey,
)

/** Resultado de abrir um vault: plaintext decifrado + sessão viva. */
data class OpenedVault(
    val plaintext: ByteArray,
    val session: SessionKey,
)

/**
 * Chave de sessão: a chave derivada + salt/params do vault aberto. Permite
 * recifrar (salvar) sem re-rodar o Argon2 nem guardar a master password — só
 * sorteia um nonce novo a cada save (exigência do XChaCha20-Poly1305).
 */
class SessionKey internal constructor(
    private val ls: LazySodium,
    private val key: ByteArray,
    private val salt: ByteArray,
    private val params: KdfParams,
) {
    /** Recifra o `plaintext` com a chave da sessão e um nonce novo. */
    fun seal(plaintext: ByteArray): ByteArray =
        TkeysCrypto(ls).seal(key, salt, params, plaintext)

    /** Cópia da chave bruta (32 bytes) — só para o cofre do SO no desbloqueio rápido. */
    fun keyBytes(): ByteArray = key.copyOf()
}
