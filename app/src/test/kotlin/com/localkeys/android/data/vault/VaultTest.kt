package com.localkeys.android.data.vault

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTest {

    companion object {
        // Mesmo JSON do fixture cripto (gerado no desktop).
        val SAMPLE_JSON: String =
            """{"version":1,"folders":[{"id":"f1","name":"Pessoal"}],"items":""" +
                """[{"id":"i1","kind":"login","name":"gmail","favorite":false,"folderId":"f1","notes":"conta principal","createdAt":1710000000000,"updatedAt":1710000000000,"deletedAt":null,"login":{"username":"joao@gmail.com","password":"hunter2","uris":["https://gmail.com"],"totp":"JBSWY3DPEHPK3PXP"},"passwordHistory":[],"customFields":[],"attachments":[]},""" +
                """{"id":"i2","kind":"note","name":"Wi-Fi","favorite":true,"folderId":null,"notes":"SSID: casa\nPW: rede123","createdAt":1710000000001,"updatedAt":1710000000001,"deletedAt":null}]}"""
    }

    @Test
    fun parse_do_json_do_desktop() {
        val vault = Vault.parse(SAMPLE_JSON)
        assertEquals(1, vault.version)
        assertEquals(1, vault.folders.size)
        assertEquals("f1", vault.folders[0].id)
        assertEquals("Pessoal", vault.folders[0].name)

        assertEquals(2, vault.items.size)

        val login = vault.items[0]
        assertEquals(ItemKind.LOGIN, login.kind)
        assertEquals("gmail", login.name)
        assertFalse(login.favorite)
        assertEquals("f1", login.folderId)
        assertEquals("conta principal", login.notes)
        assertEquals(1710000000000, login.createdAt)
        assertNull(login.deletedAt)
        val lg = login.login!!
        assertEquals("joao@gmail.com", lg.username)
        assertEquals("hunter2", lg.password)
        assertEquals(listOf("https://gmail.com"), lg.uris)
        assertEquals("JBSWY3DPEHPK3PXP", lg.totp)
        assertNull(login.card)
        assertNull(login.identity)

        val note = vault.items[1]
        assertEquals(ItemKind.NOTE, note.kind)
        assertEquals("Wi-Fi", note.name)
        assertTrue(note.favorite)
        assertNull(note.folderId)
        assertEquals("SSID: casa\nPW: rede123", note.notes)
        assertNull(note.login)
        assertNull(note.card)
        assertNull(note.identity)
    }

    @Test
    fun roundtrip_json_e_semanticamente_igual() {
        val vault = Vault.parse(SAMPLE_JSON)
        val roundTrip = Vault.parse(vault.toJson())
        assertTrue(JSONObject(SAMPLE_JSON).similar(JSONObject(roundTrip.toJson())))
    }

    @Test
    fun roundtrip_com_anexos_e_historico_de_senhas() {
        val item = Vault.parse(SAMPLE_JSON).items[0].copy(
            attachments = listOf(Attachment("a1", "doc.pdf", 4, "application/pdf", "AQID")),
            passwordHistory = listOf(
                PasswordHistoryEntry("antiga", 1710000000000),
                PasswordHistoryEntry("mais-antiga", 1710000000001),
            ),
        )
        val out = Vault(1, emptyList(), listOf(item)).toJson()
        val back = Vault.parse(out)

        assertEquals(1, back.items[0].attachments?.size)
        assertEquals("a1", back.items[0].attachments?.first()?.id)
        assertEquals("doc.pdf", back.items[0].attachments?.first()?.name)
        assertEquals(4L, back.items[0].attachments?.first()?.size)
        assertEquals("application/pdf", back.items[0].attachments?.first()?.mime)
        assertEquals("AQID", back.items[0].attachments?.first()?.dataB64)

        assertEquals(2, back.items[0].passwordHistory?.size)
        assertEquals("antiga", back.items[0].passwordHistory?.first()?.password)
        assertEquals(1710000000000, back.items[0].passwordHistory?.first()?.at)

        assertTrue(JSONObject(out).similar(JSONObject(back.toJson())))
    }

    @Test
    fun campos_opcionais_ausentes_ficam_null_e_omitidos_no_json() {
        val item = Vault.parse(SAMPLE_JSON).items[0].copy(
            passwordHistory = null,
            customFields = null,
            attachments = null,
        )
        val json = item.toJson()
        assertFalse(json.has("passwordHistory"))
        assertFalse(json.has("customFields"))
        assertFalse(json.has("attachments"))
    }

    @Test
    fun empty_vault() {
        val empty = Vault.empty()
        assertEquals(1, empty.version)
        assertTrue(empty.folders.isEmpty())
        assertTrue(empty.items.isEmpty())
    }
}
