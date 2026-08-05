package com.localkeys.android.data.crypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.exceptions.SodiumException
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.utils.Key
import com.sun.jna.NativeLong
import javax.crypto.AEADBadTagException

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
     */
    fun seal(key: ByteArray, salt: ByteArray, params: KdfParams, plaintext: ByteArray): ByteArray {
        require(key.size == TkeysFormat.KEY_LEN) { "chave deve ter ${TkeysFormat.KEY_LEN} bytes" }
        val nonce = ls.randomBytesBuf(TkeysFormat.NONCE_LEN)
        val header = buildHeader(params, salt, nonce)
        val cipherHex = ls.encrypt(
            Hex.encode(plaintext),
            Hex.encode(header),
            nonce,
            Key.fromBytes(key),
            AEAD.Method.XCHACHA20_POLY1305_IETF,
        )
        val cipher = Hex.decode(cipherHex)
        val out = ByteArray(header.size + cipher.size)
        header.copyInto(out)
        cipher.copyInto(out, header.size)
        return out
    }

    /** Decifra um `.tkeys` com a **chave bruta** (sem re-rodar o Argon2). */
    fun openWithKey(key: ByteArray, file: ByteArray): ByteArray {
        require(key.size == TkeysFormat.KEY_LEN) { "chave deve ter ${TkeysFormat.KEY_LEN} bytes" }
        val header = parseHeader(file)
        val ciphertext = file.copyOfRange(TkeysFormat.HEADER_LEN, file.size)
        val aad = file.copyOfRange(0, TkeysFormat.HEADER_LEN)
        val plainHex = try {
            ls.decrypt(
                Hex.encode(ciphertext),
                Hex.encode(aad),
                header.nonce,
                Key.fromBytes(key),
                AEAD.Method.XCHACHA20_POLY1305_IETF,
            )
        } catch (e: AEADBadTagException) {
            throw TkeysError.Decrypt
        } catch (e: IllegalArgumentException) {
            throw TkeysError.Decrypt
        }
        return Hex.decode(plainHex)
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
