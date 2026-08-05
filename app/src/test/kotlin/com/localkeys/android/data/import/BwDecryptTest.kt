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

    private val pbkdf2Export = """{"data":"2.EREREREREREREREREREREQ==.Qjd2qZkJdbXL7XEZb7NpXjRbCqYr28R/o8tcF28/cniuVPBqxhCTNIHxNwxUr0nNw8pqMpDKIr+O4PC2mdNTbtr8AhWB52PtNQgSCwGNKQEjdzDW/jWK2AC0oFVqc3sKEzRRTNeIvC280Fz9nOAbFLv3lQvSi4eypWOY+gFEiM/G1K83ZXJXEoaJ37AO6Qp2L1BK3EXOzw9sX8wceIaRshL/ebyCGTUpyScjznuTo6jk3U2gwqJYlPhEKv02ZqNTSPm/DJtUVe96+yBKUXdcK0lEaoII2UyMu2Y2DBO5bnYCue9O730YfFFDjwsrwADMPyMSDxP0aphUvK18Tcs+STtJ/BIaTqpVzXToZCEMpPFa6cdh7K2f8SeL8Jmi23TDYfroKVVXk900pNklsKJjDaWjT84VYsxUDtCteAWbtka5miIB+P1jx+o0IAmyE3hVD/FBFuz8rDrzfcLel1a8QRxkaavTxkzTuGnSivVUSWnt0QR9PiXbnI0LwXNRPz3kxbOxKG37dPViwtRiCkTnCU6BZEfdZK5x3rZwbT5VxAX0iFr92/MG6NC5slrnd4pxd1Jbr1Tv+r2N7uA+d11lejJQOxGBicTV+5JVU4kAJqBSbRLGBlp6ET3gxVNMMv65mjpPIS2uWqVJVoQGGkyZfmy1fsNnFSZAe8rDv9rsXww=.1Ow2ICa2zIcpNAVBD4rbKk8Pwp9BdkO0CcD+IKaj6i8=","encrypted":true,"kdfIterations":600000,"kdfType":0,"passwordProtected":true,"salt":"PBKDF2-salt-teste"}"""

    private val argon2Export = """{"data":"2.IiIiIiIiIiIiIiIiIiIiIg==.6uCikwGUx12jtTfNLs9Es/4Oedf3aDlWOjenUCtdmgrL0hqQD/lJQPoPcwUEBsDk51vyBjp2qDQF2GhUUAIGEG95hR+3VtwbMyQbTQqPDDc=.E82KUyl/b9KaN03FXqdzTrRgTuZqAXNrM8B66DkmsjU=","encrypted":true,"kdfIterations":3,"kdfMemory":64,"kdfParallelism":4,"kdfType":1,"passwordProtected":true,"salt":"Argon2-salt-teste"}"""

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
                """{"encrypted":true,"salt":"s","kdfType":0,"kdfIterations":1,"data":"2.@@@.@@@.@@@"}""",
                "x",
            )
        }
        assertEquals("base64 inválido", e.message)
    }
}
