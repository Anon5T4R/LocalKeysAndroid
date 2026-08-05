package com.localkeys.android.data.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Validação cruzada com o desktop: o fixture abaixo foi gerado pelo Rust do
 * LocalKeys (`crypto.rs`, params m=512/t=1/p=1) cifrando um vault JSON real.
 * Se este port Kotlin abrir esse arquivo, Argon2id + XChaCha20-Poly1305 + o
 * layout do header batem 1:1 com o desktop.
 */
class TkeysCryptoTest {

    private lateinit var crypto: TkeysCrypto

    @Before
    fun setUp() {
        crypto = TkeysCrypto(LazySodiumJava(SodiumJava()))
    }

    companion object {
        const val FIXTURE_PASSWORD = "senha-mestra-correta"

        val FIXTURE_PLAINTEXT: String =
            """{"version":1,"folders":[{"id":"f1","name":"Pessoal"}],"items":""" +
                """[{"id":"i1","kind":"login","name":"gmail","favorite":false,"folderId":"f1","notes":"conta principal","createdAt":1710000000000,"updatedAt":1710000000000,"deletedAt":null,"login":{"username":"joao@gmail.com","password":"hunter2","uris":["https://gmail.com"],"totp":"JBSWY3DPEHPK3PXP"},"passwordHistory":[],"customFields":[],"attachments":[]},""" +
                """{"id":"i2","kind":"note","name":"Wi-Fi","favorite":true,"folderId":null,"notes":"SSID: casa\nPW: rede123","createdAt":1710000000001,"updatedAt":1710000000001,"deletedAt":null}]}"""

        // Gerado com: cargo test dump_android_fixture --lib (desktop, v0.8.0).
        val FIXTURE_BASE64: String =
            "VEtFWVMAAQEAAgAAAQAAAAEAAABwIbAgKN03fWZ6neqW8qsXmdBRHQvDhj4Nt9F/ytMtxEM+" +
                "M1vSTbT9IT1LGB4ZWifHzqk4b9xv29BydA7jWuptw82wPGwkdDYLz8YXJIeWCDKzpy2DmV8G7IRrx3xKU5e5oVG7+" +
                "h3dPys6PAZ08grf3kXKD0TdXvkakY5vVmq7faOUwwMUfAqEKjA8NJgbB3IqsgtcfKcaHwCTj++diYSkwSWAo0U" +
                "MtfpPorD9NOwO/V9R8kiClQ30k2v5JLPsPFkVyLw/zDW4vOFH8+XFxvXwTwSmnr61Ixre7CzNaWIFfH0beo+T/" +
                "lk5ipBPtxEyc5/yaxA6S9zeVKTSREFVGFqAS0l27YYTZw4jv2Qoo4d+Xt6sX/Vxwo/gu/w8saVp4Mb2ZkadyZK" +
                "kFvm8LiWpaVM+/O54t4TjFcI2PrlmSEME2fJTrQHp9c8t9Kx+P+/TJBQ6pkXF4hOaHngIv+EeFvt85TPUEKtl7j" +
                "yvwtn/QDtsJ9LvXVupC9CpufqkxWUAd81p6NUoZ3M9qNB4z56Yi8N2pb/iqz1LFVK0GnavIyTn5KRVMB8Kn5Xv" +
                "4mYY8QXB/2pwygzwp7ynMRC/Uz6nqv8iwyOvxYzdGc+fOtK58IYsQdXGecnJQSbTWlMxskeg3DzCL4swLpMmO" +
                "bkZyr3scIY2+gqP/csb93+IQnoDQpPFRB6C2RftWzMY9+yTBixjXXI1A+xWD8usLDfrLa6KNNnPnVJ5KglZ4HQ" +
                "2rZSVTL42bGgZcnHi3SonmcjI0nX/x3LeNXCJlC3LmlLOoYipiEqY9GEDKTYxex2ik9wX1rNuvcds93IgJsYRn" +
                "AbZWSBQn+NP59IzH89MHfD/9YU="

        fun fixtureBytes(): ByteArray =
            Base64.getDecoder().decode(FIXTURE_BASE64)

        val TEST_PARAMS = KdfParams(mCostKib = 512, tCost = 1, pCost = 1)
    }

    // ── Validação cruzada com o fixture do desktop ────────────────────────

    @Test
    fun abre_o_vault_do_desktop_com_a_senha_correta() {
        val opened = crypto.openVault(FIXTURE_PASSWORD, fixtureBytes())
        assertEquals(FIXTURE_PLAINTEXT, String(opened.plaintext))
    }

    @Test
    fun senha_errada_falha_de_maneira_generica() {
        val err = assertThrows(TkeysError::class.java) {
            crypto.openVault("senha-errada", fixtureBytes())
        }
        assertTrue(err is TkeysError.Decrypt)
    }

    @Test
    fun header_parse_do_fixture() {
        val header = parseHeader(fixtureBytes())
        assertEquals(1, header.version)
        assertEquals(1, header.kdfId)
        assertEquals(TEST_PARAMS, header.params)
        assertEquals(TkeysFormat.SALT_LEN, header.salt.size)
        assertEquals(TkeysFormat.NONCE_LEN, header.nonce.size)
    }

    // DIAGNÓSTICO temporário: a chave Kotlin precisa bater com a do Rust do desktop.
    @Test
    fun diag_kdf_key_hex() {
        val header = parseHeader(fixtureBytes())
        val key = crypto.deriveKey(FIXTURE_PASSWORD, header.salt, header.params)
        assertEquals("9853e9de2809f7ead4ded8f4f73e3f9542e56b499c1255926cbe82b0e7ed5ed9", Hex.encode(key))
    }

    @Test
    fun abre_com_chave_bruta_derivada() {
        val header = parseHeader(fixtureBytes())
        val key = crypto.deriveKey(FIXTURE_PASSWORD, header.salt, header.params)
        val plaintext = String(crypto.openWithKey(key, fixtureBytes()))
        assertEquals(FIXTURE_PLAINTEXT, plaintext)
    }

    // ── Integridade (header é AAD, tag cobre o ciphertext) ───────────────

    @Test
    fun blob_adulterado_falha() {
        val tampered = fixtureBytes()
        tampered[tampered.size - 1] = (tampered.last().toInt() xor 0x01).toByte()
        assertThrows(TkeysError.Decrypt::class.java) {
            crypto.openVault(FIXTURE_PASSWORD, tampered)
        }
    }

    @Test
    fun header_adulterado_falha() {
        val tampered = fixtureBytes()
        tampered[8] = (tampered[8].toInt() xor 0x01).toByte() // m_cost
        assertThrows(TkeysError.Decrypt::class.java) {
            crypto.openVault(FIXTURE_PASSWORD, tampered)
        }
    }

    @Test
    fun arquivo_nao_tkeys_falha() {
        assertThrows(TkeysError.BadFormat::class.java) {
            crypto.openVault("pw", "isto nao eh um vault".toByteArray())
        }
    }

    // ── Round-trip interno (cria → abre) ──────────────────────────────────

    @Test
    fun roundtrip_cria_e_abre_recupera_o_plaintext() {
        val created = crypto.createVault("senha-mestra-correta", FIXTURE_PLAINTEXT.toByteArray(), TEST_PARAMS)
        val opened = crypto.openVault("senha-mestra-correta", created.file)
        assertEquals(FIXTURE_PLAINTEXT, String(opened.plaintext))
    }

    @Test
    fun nonces_sao_unicos_por_cifragem() {
        val a = crypto.createVault("pw", "identico".toByteArray(), TEST_PARAMS)
        val b = crypto.createVault("pw", "identico".toByteArray(), TEST_PARAMS)
        assertNotEquals(a.file.copyOfRange(36, 60).toList(), b.file.copyOfRange(36, 60).toList())
        assertNotEquals(a.file.toList(), b.file.toList())
    }

    @Test
    fun sessao_recifra_sem_rodar_argon_novamente() {
        val created = crypto.createVault("pw", "dados".toByteArray(), TEST_PARAMS)
        val resealed = created.session.seal("dados2".toByteArray())
        val opened = crypto.openVault("pw", resealed)
        assertEquals("dados2", String(opened.plaintext))
        // a sessão expõe a chave bruta (para o cofre do SO no desbloqueio rápido)
        assertEquals(TkeysFormat.KEY_LEN, created.session.keyBytes().size)
    }

    @Test
    fun kdf_deterministico() {
        val a = crypto.deriveKey("mesma senha", ByteArray(TkeysFormat.SALT_LEN) { 7 }, TEST_PARAMS)
        val b = crypto.deriveKey("mesma senha", ByteArray(TkeysFormat.SALT_LEN) { 7 }, TEST_PARAMS)
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun header_tem_60_bytes() {
        val created = crypto.createVault("pw", "x".toByteArray(), TEST_PARAMS)
        assertTrue(created.file.size > TkeysFormat.HEADER_LEN)
        assertEquals("TKEYS\u0000", String(created.file.copyOfRange(0, 6)))
        assertEquals(TEST_PARAMS, parseHeader(created.file).params)
    }
}
