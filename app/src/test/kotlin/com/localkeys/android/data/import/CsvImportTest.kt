package com.localkeys.android.data.import

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port dos testes do desktop em `src/lib/__tests__/import.test.ts` para a
 * suíte CSV do Android — mesmo mapeamento de colunas e robustez esperados.
 */
class CsvImportTest {

    @Test
    fun lida_com_aspas_virgulas_e_quebras_dentro_do_campo() {
        val csv = "name,username,password\n\"x,y\",\"li\nnha\",\"ele disse \"\"oi\"\"\""
        val items = CsvImport.parse(csv)
        assertEquals(1, items.size)
        assertEquals("x,y", items[0].name)
        assertEquals("li\nnha", items[0].login?.username)
        assertEquals("ele disse \"oi\"", items[0].login?.password)
    }

    @Test
    fun mapeia_colunas_do_chrome() {
        val csv = "name,url,username,password\nGmail,https://gmail.com,joe,s3nha"
        val items = CsvImport.parse(csv)
        assertEquals(1, items.size)
        assertEquals("Gmail", items[0].name)
        assertEquals("joe", items[0].login?.username)
        assertEquals("s3nha", items[0].login?.password)
        assertEquals(listOf("https://gmail.com"), items[0].login?.uris)
    }

    @Test
    fun extrai_o_segredo_totp_de_um_otpauth_do_lastpass() {
        val csv = "url,username,password,totp,name\nhttps://x.com,joe,pw,otpauth://totp/x?secret=JBSWY3DP&period=30,X"
        val items = CsvImport.parse(csv)
        assertEquals(1, items.size)
        assertEquals("X", items[0].name)
        assertEquals("JBSWY3DP", items[0].login?.totp)
    }

    @Test
    fun protonpass_usuario_vazio_cai_pro_email_e_ruido_type_ignorado() {
        val csv = "type,name,url,email,username,password,note,totp\nlogin,Site,https://s.com,me@x.com,,segredo,minha nota,JBSWY3DP"
        val items = CsvImport.parse(csv)
        assertEquals(1, items.size)
        assertEquals("Site", items[0].name)
        assertEquals("me@x.com", items[0].login?.username)
        assertEquals("segredo", items[0].login?.password)
        assertEquals("JBSWY3DP", items[0].login?.totp)
        assertEquals("minha nota", items[0].notes)
        assertEquals(0, items[0].customFields?.size ?: 0)
    }

    @Test
    fun mantem_email_como_campo_quando_ha_usuario_separado() {
        val csv = "name,username,email,password\nSite,joe,joe@x.com,pw"
        val items = CsvImport.parse(csv)
        assertEquals(1, items.size)
        assertEquals("joe", items[0].login?.username)
        assertEquals("joe@x.com", items[0].customFields?.find { it.name == "email" }?.value)
    }

    @Test
    fun detecta_delimitador_ponto_e_virgula() {
        val csv = "name;username;password\nSite;joe;pw"
        val items = CsvImport.parse(csv)
        assertEquals(1, items.size)
        assertEquals("joe", items[0].login?.username)
        assertEquals("pw", items[0].login?.password)
    }

    @Test
    fun coluna_desconhecida_vira_campo_personalizado_e_segredo_fica_oculto() {
        val csv = "name,username,password,pin\nSite,joe,pw,1234"
        val items = CsvImport.parse(csv)
        assertEquals(1, items.size)
        val pin = items[0].customFields?.find { it.name.lowercase() == "pin" }
        assertEquals("1234", pin?.value)
        org.junit.Assert.assertTrue(pin?.hidden == true)
    }

    @Test
    fun lida_com_bom_utf8() {
        val csv = "\uFEFFname,username,password\nSite,joe,pw"
        val items = CsvImport.parse(csv)
        assertEquals(1, items.size)
        assertEquals("Site", items[0].name)
    }
}
