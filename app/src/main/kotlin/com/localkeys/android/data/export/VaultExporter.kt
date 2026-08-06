package com.localkeys.android.data.export

import com.localkeys.android.data.vault.Vault
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export do vault em claro (JSON ou CSV) — port do `export.ts` do desktop.
 * SEM cifra: é para migrar para outro app; a UI deixa isso claro antes de gravar.
 */
object VaultExporter {

    /** Só os itens vivos (fora da lixeira), mesmo layout do desktop. */
    fun toJson(vault: Vault): String {
        val items = vault.items.filter { it.deletedAt == null }
        val json = JSONObject().apply {
            put("version", vault.version)
            put("folders", JSONArray().apply { vault.folders.forEach { put(it.toJson()) } })
        }
        json.put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        return json.toString(2)
    }

    /** CSV com as mesmas colunas do desktop. */
    fun toCsv(vault: Vault): String {
        val headers = listOf("name", "type", "username", "password", "url", "totp", "notes", "favorite")
        val sb = StringBuilder(headers.joinToString(","))
        vault.items.filter { it.deletedAt == null }.forEach { item ->
            val row = listOf(
                item.name,
                item.kind.wire,
                item.login?.username ?: item.card?.cardholder ?: "",
                item.login?.password ?: item.card?.number ?: item.card?.code ?: "",
                item.login?.uris?.firstOrNull() ?: "",
                item.login?.totp ?: "",
                item.notes,
                if (item.favorite) "1" else "0",
            ).joinToString(",") { csvEscape(it) }
            sb.append("\r\n").append(row)
        }
        return sb.toString()
    }

    private fun csvEscape(v: String): String =
        if (Regex("[,\"\n\r]").containsMatchIn(v)) "\"${v.replace("\"", "\"\"")}\"" else v
}
