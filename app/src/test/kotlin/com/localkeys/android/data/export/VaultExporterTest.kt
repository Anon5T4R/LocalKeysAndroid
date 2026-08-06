package com.localkeys.android.data.export

import com.localkeys.android.data.vault.Card
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Vault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultExporterTest {

    private fun vault(
        items: List<Item> = listOf(
            Item(
                id = "a",
                kind = ItemKind.LOGIN,
                name = "Gmail",
                favorite = true,
                folderId = "f1",
                notes = "conta principal",
                createdAt = 1,
                updatedAt = 2,
                deletedAt = null,
                login = Login("joao@gmail.com", "senha\",com,vírgula", listOf("https://gmail.com"), "JBSWY3DPEHPK3PXP"),
                card = null,
                identity = null,
                passwordHistory = null,
                customFields = null,
                attachments = null,
            ),
        ),
        folders: List<Folder> = listOf(Folder("f1", "Trabalho")),
    ) = Vault(1, folders, items)

    @Test
    fun json_sai_em_claro_com_itens_vivos_e_sem_itens_na_lixeira() {
        val comLixo = vault(items = listOf(
            vault().items.first(),
            vault().items.first().copy(id = "lixo", name = "Apagado", deletedAt = 999),
        ))
        val json = VaultExporter.toJson(comLixo)

        assertTrue(json.contains("\"name\": \"Gmail\""))
        assertTrue(json.contains("\"Trabalho\""))
        assertTrue(json.contains("joao@gmail.com"))
        assertFalse(json.contains("Apagado"))
        // JSON válido, pretty-printed.
        assertTrue(json.startsWith("{") && json.contains("\n  "))
    }

    @Test
    fun csv_cobre_aspas_e_vírgulas_e_ignora_a_lixeira() {
        val comLixo = vault(items = listOf(
            vault().items.first(),
            vault().items.first().copy(id = "lixo", name = "Apagado", deletedAt = 999),
        ))
        val csv = VaultExporter.toCsv(comLixo)

        assertTrue(csv.startsWith("name,type,username,password,url,totp,notes,favorite"))
        assertTrue(csv.contains("\"senha\"\"com,convírgula\""))
        assertTrue(csv.contains("Trabalho"))
        assertFalse(csv.contains("Apagado"))
    }

    @Test
    fun card_exporta_numero_no_campo_de_senha_do_csv() {
        val comCartao = vault(items = listOf(
            Item(
                id = "c",
                kind = ItemKind.CARD,
                name = "Nubank",
                favorite = false,
                folderId = null,
                notes = "",
                createdAt = 1,
                updatedAt = 1,
                deletedAt = null,
                login = null,
                card = Card("João da Silva", "Visa", "4555 1111 2222 3333", "12/28", "123"),
                identity = null,
                passwordHistory = null,
                customFields = null,
                attachments = null,
            ),
        ))
        val csv = VaultExporter.toCsv(comCartao)
        assertTrue(csv.contains("4555 1111 2222 3333"))
        assertTrue(csv.contains("card"))
    }
}
