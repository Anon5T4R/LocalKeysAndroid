package com.localkeys.android.data.autofill

import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillMatcherTest {

    private fun login(username: String, vararg uris: String): Login =
        Login(username = username, password = "p", uris = uris.toList(), totp = "")

    private fun vault(vararg logins: Login): Vault {
        val items = logins.mapIndexed { i, l ->
            com.localkeys.android.data.vault.Item(
                id = "id$i", kind = ItemKind.LOGIN, name = l.username, favorite = false,
                folderId = null, notes = "", createdAt = 1, updatedAt = 1, deletedAt = null,
                login = l, card = null, identity = null, passwordHistory = emptyList(),
                customFields = emptyList(), attachments = emptyList(),
            )
        }
        return Vault(version = 1, folders = emptyList(), items = items)
    }

    @Test
    fun normaliza_dominios_de_todo_tipo() {
        assertEquals("example.com", AutofillMatcher.normalizeDomain("https://www.Example.com/login?x=1"))
        assertEquals("example.com", AutofillMatcher.normalizeDomain("example.com"))
        assertEquals("example.com", AutofillMatcher.normalizeDomain("http://example.com:8080/"))
        assertEquals("example.com", AutofillMatcher.normalizeDomain("https://user:pass@example.com"))
        assertEquals("example.com", AutofillMatcher.normalizeDomain("   https://example.com  "))
        assertEquals("", AutofillMatcher.normalizeDomain(""))
        assertEquals("", AutofillMatcher.normalizeDomain("   "))
    }

    @Test
    fun casa_dominio_exato_e_subdominio_dos_dois_lados() {
        assertTrue(AutofillMatcher.matchesDomain("https://google.com", "https://google.com"))
        assertTrue(AutofillMatcher.matchesDomain("https://mail.google.com", "google.com"))
        assertTrue(AutofillMatcher.matchesDomain("google.com", "https://mail.google.com"))
        assertTrue(AutofillMatcher.matchesDomain("https://www.github.com", "https://github.com"))
    }

    @Test
    fun nao_casa_dominios_nao_relacionados() {
        org.junit.Assert.assertFalse(AutofillMatcher.matchesDomain("https://google.com", "https://goggle.com"))
        org.junit.Assert.assertFalse(AutofillMatcher.matchesDomain("https://a.com", "https://b.com"))
        org.junit.Assert.assertFalse(AutofillMatcher.matchesDomain("https://google.com", ""))
    }

    @Test
    fun filtra_logins_pelo_dominio_e_ordena_exato_primeiro() {
        val v = vault(
            login("outro@x.com", "https://foo.com"),
            login("joao@gmail.com", "https://mail.google.com"),
            login("trabalho@x.com", "https://x.com"),
        )
        val result = AutofillMatcher.loginsFor(v, packageName = null, webDomain = "https://mail.google.com")
        assertEquals(1, result.size)
        assertEquals("joao@gmail.com", result[0].username)
    }

    @Test
    fun sem_casa_devolve_sugestoes_com_teto() {
        val v = vault(
            login("a@x.com", "https://a.com"),
            login("b@x.com", "https://b.com"),
            login("c@x.com", "https://c.com"),
            login("d@x.com", "https://d.com"),
            login("e@x.com", "https://e.com"),
            login("f@x.com", "https://f.com"),
        )
        val result = AutofillMatcher.loginsFor(v, packageName = null, webDomain = "https://nada.com")
        assertEquals(AutofillMatcher.MAX_FALLBACK, result.size)
    }

    @Test
    fun vault_nulo_ou_vazio_nao_da_nada() {
        assertEquals(emptyList<Login>(), AutofillMatcher.loginsFor(null, "com.x", "https://x.com"))
        assertEquals(emptyList<Login>(), AutofillMatcher.loginsFor(vault(), "com.x", "https://x.com"))
    }

    @Test
    fun ignora_itens_que_nao_sao_login() {
        val v = Vault(
            version = 1,
            folders = emptyList(),
            items = listOf(
                com.localkeys.android.data.vault.Item(
                    id = "n", kind = ItemKind.NOTE, name = "nota", favorite = false,
                    folderId = null, notes = "x", createdAt = 1, updatedAt = 1, deletedAt = null,
                    login = null, card = null, identity = null, passwordHistory = emptyList(),
                    customFields = emptyList(), attachments = emptyList(),
                ),
            ),
        )
        assertEquals(emptyList<Login>(), AutofillMatcher.loginsFor(v, "com.x", "https://x.com"))
    }
}
