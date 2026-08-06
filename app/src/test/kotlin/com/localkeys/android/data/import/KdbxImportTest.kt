package com.localkeys.android.data.import

import com.localkeys.android.data.vault.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compat do import KeePass (.kdbx 4.0) contra o desktop.
 *
 * O fixture foi gerado no crate `keepass` (Rust) com as MESMAS primitivas que
 * o Android tem que decifrar: AES-256-CBC externo, HMAC-block-stream, inner
 * ChaCha20, AES-KDF (sem Argon2 para o teste ser rápido), GZip. Se o port
 * Kotlin deriva a mesma chave e decifra, o Android abre os mesmos .kdbx que o
 * desktop — incluindo campos `Protected="True"`.
 */
class KdbxImportTest {

    private val fixture: ByteArray = loadFixture()

    private fun loadFixture(): ByteArray {
        val stream = checkNotNull(javaClass.getResourceAsStream("/fixture.kdbx")) {
            "fixture.kdbx não encontrado em test/resources"
        }
        return stream.use { it.readBytes() }
    }

    @Test
    fun abre_fixture_kdbx4_com_campos_protegidos() {
        val items = KdbxImport.parse(fixture, "test-password")
        assertEquals(2, items.size) // entrada vazia pulada; entrada do subgrupo ignorada

        val conta = items[0]
        assertEquals(ItemKind.LOGIN, conta.kind)
        assertEquals("Conta do João", conta.name)
        assertEquals("joao", conta.login?.username)
        assertEquals("s3nh4#Forte", conta.login?.password)
        assertEquals(listOf("https://exemplo.com"), conta.login?.uris)
        assertEquals("JBSWY3DP", conta.login?.totp)
        assertEquals("nota de teste com acentuação", conta.notes)

        // Campo extra protegido vira campo personalizado oculto.
        val pin = conta.customFields?.firstOrNull { it.name == "Pin" }
        assertEquals("1234", pin?.value)
        assertTrue(pin?.hidden == true)

        val semTitulo = items[1]
        assertEquals("semtitulo", semTitulo.name)
        assertEquals("semtitulo", semTitulo.login?.username)
        assertEquals("abc123", semTitulo.login?.password)
    }

    @Test
    fun senha_errada_falha_no_hmac_do_cabecalho() {
        val e = assertThrows(ImportError::class.java) {
            KdbxImport.parse(fixture, "senha-errada")
        }
        assertEquals("senha incorreta ou arquivo corrompido (HMAC do cabeçalho)", e.message)
    }

    @Test
    fun bytes_que_nao_sao_kdbx_falham() {
        val e = assertThrows(ImportError::class.java) {
            KdbxImport.parse("isto não é um kdbx".toByteArray(), "x")
        }
        assertEquals("este arquivo não parece ser um .kdbx", e.message)
    }

    @Test
    fun senha_vazia_tambem_e_validada() {
        val e = assertThrows(ImportError::class.java) {
            KdbxImport.parse(fixture, "")
        }
        assertEquals("senha incorreta ou arquivo corrompido (HMAC do cabeçalho)", e.message)
    }

    @Test
    fun campos_protegidos_nao_vazam_como_texto() {
        val items = KdbxImport.parse(fixture, "test-password")
        val conta = items.first()
        // A base64 cifrada nunca deve aparecer como valor legível.
        assertNull(conta.login?.password?.takeIf { it.endsWith("==") })
        assertNull(conta.customFields?.firstOrNull { it.name == "Pin" }?.value?.takeIf { it.endsWith("==") })
    }
}
