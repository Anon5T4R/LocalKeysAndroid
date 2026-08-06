package com.localkeys.android.data.import

import com.localkeys.android.data.vault.Item
import org.json.JSONObject

enum class ImportFormat { BITWARDEN_JSON, BITWARDEN_ENCRYPTED, CSV, KDBX, UNKNOWN }

/**
 * Detecção de formato de import e helpers compartilhados (mesma heurística do
 * `detectFormat` do desktop em `src/import.ts`).
 */
object Importers {

    /** Reconhece por extensão e conteúdo. Export cifrado é detectado à parte. */
    fun detectFormat(filename: String?, content: String?): ImportFormat {
        val lower = filename?.lowercase()
        val encrypted = content?.let { isEncryptedBw(it) } == true
        if (lower?.endsWith(".kdbx") == true) return ImportFormat.KDBX
        if (lower?.endsWith(".json") == true) {
            return if (encrypted) ImportFormat.BITWARDEN_ENCRYPTED else ImportFormat.BITWARDEN_JSON
        }
        if (lower?.endsWith(".csv") == true) return ImportFormat.CSV
        val t = content?.trimStart()
        if (t?.startsWith("{") == true || t?.startsWith("[") == true) {
            return if (encrypted) ImportFormat.BITWARDEN_ENCRYPTED else ImportFormat.BITWARDEN_JSON
        }
        if (t != null) return ImportFormat.CSV
        return ImportFormat.UNKNOWN
    }

    /** O export cifrado do Bitwarden marca `passwordProtected`/`encrypted`. */
    fun isEncryptedBw(json: String): Boolean = try {
        val obj = JSONObject(json)
        obj.optBoolean("passwordProtected", false) || obj.optBoolean("encrypted", false)
    } catch (e: Exception) {
        false
    }

    /** Extrai só o segredo base32 de um campo TOTP que pode vir como `otpauth://…?secret=X`. */
    fun extractSecret(raw: String): String {
        val r = raw.trim()
        for (m in listOf("secret=", "key=")) {
            val idx = r.lowercase().indexOf(m)
            if (idx >= 0) {
                val rest = r.substring(idx + m.length)
                val end = rest.indexOf("&")
                return if (end >= 0) rest.substring(0, end) else rest
            }
        }
        return r
    }

    /** Parser final para conteúdo já identificado (exceto cifrado/KDBX). */
    fun parseText(text: String, format: ImportFormat): List<Item> = when (format) {
        ImportFormat.BITWARDEN_JSON -> BitwardenImport.parse(text)
        else -> CsvImport.parse(text)
    }

    /** Cofre KeePass (.kdbx): decifra com a senha-mestra e converte em itens. */
    fun parseKdbx(bytes: ByteArray, password: String): List<Item> =
        KdbxImport.parse(bytes, password)
}
