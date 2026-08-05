package com.localkeys.android.data.import

import com.localkeys.android.data.vault.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compat do decrypt do export cifrado do Bitwarden contra o desktop.
 *
 * Os dois fixtures foram **gerados no próprio Rust do LocalKeys** (bwdecrypt.rs
 * decifra, então um teste temporário local cifrou com as mesmas primitivas
 * RustCrypto: PBKDF2/Argon2id → HKDF → AES-256-CBC + HMAC-SHA256). Se o port
 * Kotlin deriva a mesma chave e decifra, o Android é compatível byte a byte
 * com os exports reais do Bitwarden. As senhas incluem acentos de propósito
 * (valida o pipeline UTF-8: `as_bytes()` do Rust ↔ JCE).
 */
class BwDecryptTest {

    private val pbkdf2Export = """{"data":"2.EREREREREREREREREREREQ==|t+bXMMwxt5LHUAS3fRhaCILA5EFoFgDwyVw/iy9vHysDf7utyJrGrbBA8KMrbm0qgVDbYM8/hECz17BQwlcMS26CUrydgnjvk48InFV5oQuqLphAMLEUIypG4NU+SgXB327n3QCVTmtRC0ILdorlSTnjiEn9P9RPbWLO2GKyJfhdylgjW1C4gewwI+3eBU03HvoDMmBIBz+7ykcEiNNxPGQKOI9ImGyI8uAytz9SmPy0EJh1owJbalNcRGZAc+kdM+553gBSU2UAVvZ8Jn6uq6bqmvc3TGjhaSTTFL31JOtJ1zyfeqbnyUtLJJXASLpHFluqi4+O5J7QUseWHhFJ3v/T7mx7n9w/twlZ346TpaHjEGi1jT/+Ehdl1tbMruKX/Xubjeyifmzv3GgAXAR7f2wi4B5xAuWGZMzHFMZc51lTHehSS4Wgm+CiNwX3ho3lrioSHpmTvj4aKZK+rzx954UmyiIykF1FA0wBLKax9WnsIG4wCv8RoYeVfQpASVSlPySvO0igtxj3z7udPMw0QPOg9+IwyD1WxyY0FB0m88K8hwtFeG01zyvgC1AAmGICbTsxWFcQc7UNhg9yjPZzAF4lHKfJC8+JrpdA7DCTXJTgRcP4hU6ZQlrHKPiIychuglZnBdxqSzraHwR/km1aKwH72rFH+RH42ud4I/guApu2zD2+qUbEeNEEBF6wmodeWRYfhObLmC4K6RKthxi9mA==|qaHFrKvJ+bQNpEXsCUwO3MTe+66JmJspsDSFCkp6jj4=","encrypted":true,"kdfIterations":600000,"kdfType":0,"passwordProtected":true,"salt":"PBKDF2-salt-teste"}"""

    private val argon2Export = """{"data":"2.IiIiIiIiIiIiIiIiIiIiIg==|8J1il/P0fKPns5qwEyMQtPfG0IzFZR2rCBqwFv/U/X+DwXUqrbrBgJXeJI4gCU2Gnce6Xfvh3DYqmM0Wj0da3NLBIoFTHKHBOhn8bZMAfP69EqU82SKsRjSxTO56TiOd|3i9GypasqGxZMtjuiX/wGZy0HdS0lLtDzyyzz2GAGxk=","encrypted":true,"kdfIterations":3,"kdfMemory":64,"kdfParallelism":4,"kdfType":1,"passwordProtected":true,"salt":"Argon2-salt-teste"}"""

    @Test
    fun decifra_export_pbkdf2_gerado_no_desktop() {
        val plain = BwDecrypt.decryptExport(pbkdf2Export, "senha-mestra-Ç-Jõão")
        val items = BitwardenImport.parse(plain)
        assertEquals(3, items.size)

        assertEquals(ItemKind.LOGIN, items[0].kind)
        assertEquals("Site", items[0].name)
        assertTrue(items[0].favorite)
        assertEquals("joao", items[0].login?.username)
        assertEquals("segredo", items[0].login?.password)
        assertEquals(listOf("https://site.com"), items[0].login?.uris)
        assertEquals("JBSWY3DP", items[0].login?.totp)

        assertEquals(ItemKind.CARD, items[1].kind)
        assertEquals("Visa", items[1].card?.brand)
        assertEquals("Joao", items[1].card?.cardholder)
        assertEquals("4111111111111111", items[1].card?.number)
        assertEquals("05/30", items[1].card?.exp)
        assertEquals("123", items[1].card?.code)

        assertEquals(ItemKind.IDENTITY, items[2].kind)
        assertEquals("Joao", items[2].identity?.firstName)
        assertEquals("Teste", items[2].identity?.lastName)
        assertEquals("j@x.com", items[2].identity?.email)
        assertEquals("119999", items[2].identity?.phone)
        assertEquals("Rua A, SP, SP, 00000, BR", items[2].identity?.address)
    }

    @Test
    fun decifra_export_argon2id_gerado_no_desktop() {
        val plain = BwDecrypt.decryptExport(argon2Export, "outra-senha-áéíóú")
        val items = BitwardenImport.parse(plain)
        assertEquals(1, items.size)
        assertEquals(ItemKind.NOTE, items[0].kind)
        assertEquals("Nota", items[0].name)
        assertEquals("uma nota qualquer", items[0].notes)
    }

    @Test
    fun senha_errada_falha_na_autenticacao() {
        val e = assertThrows(ImportError::class.java) {
            BwDecrypt.decryptExport(pbkdf2Export, "senha-errada")
        }
        assertEquals("senha incorreta ou arquivo corrompido (MAC)", e.message)
    }

    @Test
    fun nao_e_export_cifrado() {
        val e = assertThrows(ImportError::class.java) {
            BwDecrypt.decryptExport("""{"items":[]}""", "x")
        }
        assertEquals("este arquivo não parece um export cifrado do Bitwarden", e.message)
    }

    @Test
    fun kdf_nao_suportado() {
        val e = assertThrows(ImportError::class.java) {
            BwDecrypt.decryptExport(
                """{"encrypted":true,"salt":"s","kdfType":9,"kdfIterations":1,"data":"2.a.b.c"}""",
                "x",
            )
        }
        assertEquals("KDF do Bitwarden não suportado: 9", e.message)
    }

    @Test
    fun json_invalido() {
        assertThrows(ImportError::class.java) { BwDecrypt.decryptExport("não é json", "x") }
    }

    @Test
    fun base64_invalido() {
        val e = assertThrows(ImportError::class.java) {
            BwDecrypt.decryptExport(
                """{"encrypted":true,"salt":"s","kdfType":0,"kdfIterations":1,"data":"2.@@@|@@@|@@@"}""",
                "x",
            )
        }
        assertEquals("base64 inválido", e.message)
    }
}
