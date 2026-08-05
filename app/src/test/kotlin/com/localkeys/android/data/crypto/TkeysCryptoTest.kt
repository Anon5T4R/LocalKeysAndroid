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

        // Gerado com: cargo test diag_dump_android_fixture --lib (desktop v0.8.0).
        val FIXTURE_BASE64: String =
            "VEtFWVMAAQEAAgAAAQAAAAEAAABVRbyVYVNp4JiO4L8yKOZA5KpVKIQGBMRNNy5e9uuoh2J7ifEC" +
            "WNNDDoSzS5z+WcsWvZfe9qiOnP9ipNnX+Zz/Lt9jmPGD4K1rORD5S5ODFG2BW4BPRv72WGE9WuCY" +
            "IioOQpOCxyy2Ewkxwy69DOp0EnpLsvMYr3KfoVQHtrZLq3zLJkfFwcVHd5D+1C4QjRfFeVriSGxM" +
            "8bs36HnNWEWGzCMVRCx9E5VQeGAnlxSWzDjHy0KSiRM75otRh11mz2BFe3UwX1N13nWHqxZMzAbC" +
            "aXXxNjhcK637Q5zItTDNFnqYTsX5VM9F3fWu2ykZ5aNtxtsqUQVKnZtovlCl8CCrmQ89iPQIFpAg" +
            "ddg1pyAaeo6/JF8WuFTAXUWZkrUlG5e3TI8YxrNy6CnQkht4AqChl+ZIvujR6LLWL+ggfwBCyCsE" +
            "baS769PalQRqxAiSsKHd/0yoCiFoRdObeZxq0XbNo5PvZ9zsGxCY2uGL/3505wZC3zoyDReu16Q8" +
            "KouvJoqZmRwftbgboqT7s8F5jWZigUfo29dYsL5rOpEWJ6rqNFbpAIWtLPSE0bG+78rufcrZ/P9U" +
            "pVuyR90wBD1nSFYx4cAp1lC0lyqaxoV+VhjpJ9BB7DYWK+GQwYi+dczlKtCHICXP5WCOfMaH2a8Q" +
            "JdzSsT4GTwzi73bFr0tXrkoWWN6UZfzH1CabrY1WsZFlPMxKnZMyfX9Dmq8mOjsZypcwIVWmg+zb" +
            "G3IoCVsIOeuwWiB+hd6NYEsFe3zJcrXnoAt9ujk7CyK9XunNTHs9VY0GC4zNt3mZx8bGwxXLxyyF" +
            "7eilBzbedlxCtcpeYlS79v2lwBRf8NRqaNMzokQ="

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
        val actual = Hex.encode(key).lowercase()
        val expected = "ab168e6e540dd619d6f6af8f10db852810a67789b8023b901fb441b6ea3d1b63"
        System.err.println("DIAG KDF kotlin=$actual rust=$expected")
        assertEquals(expected, actual)
    }

    // DIAGNÓSTICO temporário: recifra o fixture com os MESMOS salt/nonce/chave
    // do desktop e compara byte a byte. Se bater, a cifragem é idêntica e o bug
    // está no openWithKey; se não bater, algum input difere.
    @Test
    fun diag_aead_recifra_o_fixture() {
        val file = fixtureBytes()
        val header = parseHeader(file)
        val key = crypto.deriveKey(FIXTURE_PASSWORD, header.salt, header.params)
        val rebuilt = crypto.seal(key, header.salt, header.params, header.nonce, FIXTURE_PLAINTEXT.toByteArray())
        System.err.println("DIAG AEAD rebuilt=${rebuilt.size}B fixture=${file.size}B")
        val firstDiff = (0 until minOf(rebuilt.size, file.size)).firstOrNull { rebuilt[it] != file[it] }
        System.err.println("DIAG AEAD firstDiff=$firstDiff rebuiltByte=${firstDiff?.let { "%02X".format(rebuilt[it]) }} fixtureByte=${firstDiff?.let { "%02X".format(file[it]) }}")
        assertEquals("cifragem Kotlin != fixture do desktop", file.toList(), rebuilt.toList())
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
