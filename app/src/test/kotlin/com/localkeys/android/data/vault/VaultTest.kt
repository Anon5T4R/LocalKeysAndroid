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
        val lg = login.login
        assertTrue(lg != null)
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
    fun empty_vault() {
        val empty = Vault.empty()
        assertEquals(1, empty.version)
        assertTrue(empty.folders.isEmpty())
        assertTrue(empty.items.isEmpty())
    }
}
