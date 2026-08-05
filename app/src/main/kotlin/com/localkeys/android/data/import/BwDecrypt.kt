package com.localkeys.android.data.import

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Import do export **cifrado** (password-protected) do Bitwarden — port do
 * `bwdecrypt.rs` do desktop (validado lá com export real em 2026-07-18).
 *
 * O algoritmo segue o `bitwarden/clients` (GPL): deriva a chave da senha
 * (PBKDF2-HMAC-SHA256 ou Argon2id), estica via HKDF em (encKey, macKey), e a
 * `data` é uma enc-string `2.iv|ct|mac` (AES-256-CBC + HMAC-SHA256).
 *
 * Só primitivas do JCE (PBKDF2/AES/HMAC/SHA) + BouncyCastle para o Argon2id
 * (a JVM/Android não tem Argon2 no JCE). A validação de compat é feita por
 * testes com fixtures geradas no próprio Rust do desktop.
 */
object BwDecrypt {

    /** Recebe o JSON do export cifrado + a senha; devolve o JSON claro. */
    fun decryptExport(json: String, password: String): String {
        val export = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw ImportError("JSON inválido: ${e.message}")
        }
        val passwordProtected = export.optBoolean("passwordProtected", false)
        val encrypted = export.optBoolean("encrypted", false)
        if (!passwordProtected && !encrypted) {
            throw ImportError("este arquivo não parece um export cifrado do Bitwarden")
        }
        val salt = export.optString("salt").takeIf { it.isNotEmpty() }
            ?: throw ImportError("export sem `salt`")
        val data = export.optString("data").takeIf { it.isNotEmpty() }
            ?: throw ImportError("export sem `data`")
        val kdfType = export.optInt("kdfType", 0)
        val iters = export.optInt("kdfIterations", 0).takeIf { it > 0 }
            ?: throw ImportError("export sem `kdfIterations`")
        val memMiB = export.optInt("kdfMemory", 64)
        val parallelism = export.optInt("kdfParallelism", 4)

        val master = deriveMaster(password, salt, kdfType, iters, memMiB, parallelism)
        val (encKey, macKey) = stretch(master)
        val plaintext = decryptEncString(data, encKey, macKey)
        return String(plaintext, Charsets.UTF_8)
    }

    // ── KDF ──────────────────────────────────────────────────────────────

    /** Deriva a chave-mestra (32 bytes) do password + salt conforme o KDF. */
    private fun deriveMaster(
        password: String,
        salt: String,
        kdfType: Int,
        iters: Int,
        memMiB: Int,
        parallelism: Int,
    ): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        return when (kdfType) {
            0 -> pbkdf2Sha256(passwordBytes, salt.toByteArray(Charsets.UTF_8), iters)
            1 -> {
                // Argon2id; o Bitwarden usa salt = SHA256(salt).
                val saltHash = sha256(salt.toByteArray(Charsets.UTF_8))
                argon2id(passwordBytes, saltHash, memMiB * 1024, iters, parallelism.coerceAtLeast(1))
            }
            else -> throw ImportError("KDF do Bitwarden não suportado: $kdfType")
        }
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        // O JCE encoda os chars como UTF-8, igual ao `as_bytes()` do Rust.
        val spec = PBEKeySpec(String(password, Charsets.UTF_8).toCharArray(), salt, iterations, 256)
        return factory.generateSecret(spec).encoded
    }

    private fun argon2id(password: ByteArray, salt: ByteArray, memKiB: Int, iters: Int, parallelism: Int): ByteArray {
        return try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iters)
                .withMemoryAsKB(memKiB)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build()
            val gen = Argon2BytesGenerator()
            gen.init(params)
            val out = ByteArray(32)
            gen.generateBytes(password, out)
            out
        } catch (e: Exception) {
            throw ImportError("parâmetros Argon2 inválidos: ${e.message}")
        }
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    // ── HKDF-Expand (RFC 5869) ───────────────────────────────────────────

    /** Estica a chave-mestra em (encKey 32, macKey 32) via HKDF-Expand-SHA256. */
    private fun stretch(master: ByteArray): Pair<ByteArray, ByteArray> =
        hkdfExpand(master, "enc".toByteArray(), 32) to hkdfExpand(master, "mac".toByteArray(), 32)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream(length + 32)
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    // ── Enc-string ───────────────────────────────────────────────────────

    /** Decifra uma enc-string `2.iv|ct|mac` (AesCbc256_HmacSha256_B64). */
    private fun decryptEncString(s: String, encKey: ByteArray, macKey: ByteArray): ByteArray {
        val rest = s.removePrefix("2.")
            .takeUnless { it == s }
            ?: throw ImportError("formato de cifra não suportado (esperado tipo 2)")
        val parts = rest.split("|")
        if (parts.size != 3) throw ImportError("enc-string malformada")
        val iv = b64(parts[0])
        val ct = b64(parts[1])
        val tag = b64(parts[2])
        if (iv.size != 16) throw ImportError("IV inválido")

        // Autentica (iv || ct) ANTES de decifrar.
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(macKey, "HmacSHA256"))
        hmac.update(iv)
        hmac.update(ct)
        val expected = hmac.doFinal()
        if (!MessageDigest.isEqual(expected, tag)) {
            throw ImportError("senha incorreta ou arquivo corrompido (MAC)")
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }

    private fun b64(s: String): ByteArray = try {
        Base64.getDecoder().decode(s.trim())
    } catch (e: IllegalArgumentException) {
        throw ImportError("base64 inválido")
    }
}
