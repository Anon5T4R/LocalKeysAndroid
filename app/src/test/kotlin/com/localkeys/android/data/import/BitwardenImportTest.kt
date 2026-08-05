package com.localkeys.android.data.import

import com.localkeys.android.data.vault.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port dos testes do desktop em `src/lib/__tests__/import.test.ts` para o
 * parser de Bitwarden JSON e a detecção de formato.
 */
class BitwardenImportTest {

    @Test
    fun mapeia_login_cartao_e_identidade() {
        val json = """{"items":[
            {"type":1,"name":"Site","favorite":true,"login":{"username":"u","password":"p","uris":[{"uri":"https://s"}],"totp":"ABC"}},
            {"type":3,"name":"Visa","card":{"number":"4111","code":"123","expMonth":"05","expYear":"30"}},
            {"type":4,"name":"Eu","identity":{"firstName":"Jo","lastName":"Fe","email":"j@x"}}
        ]}"""
        val items = BitwardenImport.parse(json)
        assertEquals(3, items.size)

        assertEquals(ItemKind.LOGIN, items[0].kind)
        assertEquals("Site", items[0].name)
        assertTrue(items[0].favorite)
        assertEquals("u", items[0].login?.username)
        assertEquals("p", items[0].login?.password)
        assertEquals(listOf("https://s"), items[0].login?.uris)
        assertEquals("ABC", items[0].login?.totp)

        assertEquals(ItemKind.CARD, items[1].kind)
        assertEquals("4111", items[1].card?.number)
        assertEquals("123", items[1].card?.code)
        assertEquals("05/30", items[1].card?.exp)

        assertEquals(ItemKind.IDENTITY, items[2].kind)
        assertEquals("Jo", items[2].identity?.firstName)
        assertEquals("Fe", items[2].identity?.lastName)
        assertEquals("j@x", items[2].identity?.email)
    }

    @Test
    fun aceita_json_de_array_raiz() {
        val json = """[{"type":2,"name":"Nota","notes":"texto"}]"""
        val items = BitwardenImport.parse(json)
        assertEquals(1, items.size)
        assertEquals(ItemKind.NOTE, items[0].kind)
        assertEquals("texto", items[0].notes)
    }

    @Test
    fun extrai_totp_de_otpauth_no_json() {
        val json = """{"items":[{"type":1,"name":"S","login":{"totp":"otpauth://totp/x?secret=JBSWY3DP&issuer=X"}}]}"""
        val items = BitwardenImport.parse(json)
        assertEquals("JBSWY3DP", items[0].login?.totp)
    }

    @Test
    fun json_invalido_lanca_import_error() {
        org.junit.Assert.assertThrows(ImportError::class.java) {
            BitwardenImport.parse("isto não é json")
        }
    }
}
