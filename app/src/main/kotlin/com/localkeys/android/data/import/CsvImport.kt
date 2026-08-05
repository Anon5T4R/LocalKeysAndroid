package com.localkeys.android.data.import

import com.localkeys.android.data.vault.CustomField
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import java.util.UUID

/**
 * Import CSV genérico (Chrome/Edge, LastPass, 1Password, ProtonPass, Firefox…)
 * → itens do LocalKeys. Port do `parseCsv`/`parseCsvItems` do desktop
 * (`src/import.ts`): separador detectado, campos entre aspas, mapeamento por
 * coluna (exata e parcial), TOTP extraído de otpauth e colunas não mapeadas
 * viram campos personalizados (segredos ocultos).
 */
object CsvImport {

    fun parse(text: String): List<Item> = parseCsvItems(text)

    // ── Parser de CSV ────────────────────────────────────────────────────

    /** Detecta o separador (vírgula, ponto-e-vírgula ou tab) pela 1ª linha. */
    private fun detectDelimiter(text: String): String {
        val nl = text.indexOf("\n")
        val first = if (nl >= 0) text.substring(0, nl) else text
        val counts = linkedMapOf("," to 0, ";" to 0, "\t" to 0)
        for (c in first) {
            val key = c.toString()
            if (counts.containsKey(key)) counts[key] = counts.getValue(key) + 1
        }
        return counts.entries.maxByOrNull { it.value }?.key ?: ","
    }

    /** Parser tolerante a aspas, separador variável e quebras dentro do campo. */
    private fun parseCsv(text: String, delim: String): List<List<String>> {
        val t = if (text.startsWith("\uFEFF")) text.substring(1) else text // BOM
        val d = delim[0]
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                inQuotes -> if (c == '"') {
                    if (i + 1 < t.length && t[i + 1] == '"') {
                        field.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                } else {
                    field.append(c)
                    i++
                }
                c == '"' -> {
                    inQuotes = true
                    i++
                }
                c == d -> {
                    row.add(field.toString())
                    field = StringBuilder()
                    i++
                }
                c == '\r' -> i++
                c == '\n' -> {
                    row.add(field.toString())
                    rows.add(row)
                    row = mutableListOf()
                    field = StringBuilder()
                    i++
                }
                else -> {
                    field.append(c)
                    i++
                }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows.filter { r -> r.any { it.trim().isNotEmpty() } }
    }

    // ── Mapeamento de colunas → Item ─────────────────────────────────────

    private val NAME = listOf("name", "title", "account", "item name")
    private val USERNAME = listOf("username", "user", "login_username", "user name", "login", "usuário", "usuario")
    private val EMAIL = listOf("email", "e-mail", "e mail")
    private val PASSWORD = listOf("password", "pass", "login_password", "senha")
    private val URL = listOf("url", "uri", "website", "login_uri", "web site", "site", "endereço")
    private val TOTP = listOf("totp", "otpauth", "otp", "one-time password", "verification code", "otp secret", "2fa")
    private val NOTES = listOf("notes", "note", "extra", "comment", "comments", "nota", "observações")

    /** Cabeçalhos de metadados que NÃO viram campo personalizado (só ruído). */
    private val IGNORE = setOf(
        "type", "vault", "guid", "grouping", "fav", "favorite", "httprealm", "formactionorigin",
        "timecreated", "timelastused", "timepasswordchanged", "created", "modified", "reprompt",
        "android_app",
    )

    private fun field(name: String, value: String, hidden: Boolean = false): CustomField =
        CustomField(id = UUID.randomUUID().toString(), name = name, value = value, hidden = hidden)

    fun parseCsvItems(text: String): List<Item> {
        val rows = parseCsv(text, detectDelimiter(text))
        if (rows.size < 2) return emptyList()
        val headers = rows[0]
        val norm = headers.map { it.trim().lowercase() }
        val consumed = mutableSetOf<Int>()

        fun exact(keys: List<String>): Int {
            for (k in keys) {
                val i = norm.indexOf(k)
                if (i >= 0 && i !in consumed) {
                    consumed.add(i)
                    return i
                }
            }
            return -1
        }

        fun partial(keys: List<String>): Int {
            for (i in norm.indices) {
                if (i !in consumed && keys.any { norm[i].contains(it) }) {
                    consumed.add(i)
                    return i
                }
            }
            return -1
        }

        // 1ª rodada exata (evita "name" casar com "username"); 2ª rodada parcial.
        var ciName = exact(NAME)
        val ciEmail = exact(EMAIL)
        var ciUsername = exact(USERNAME)
        var ciPassword = exact(PASSWORD)
        var ciUrl = exact(URL)
        var ciTotp = exact(TOTP)
        var ciNotes = exact(NOTES)
        ciName = if (ciName >= 0) ciName else partial(NAME)
        ciUsername = if (ciUsername >= 0) ciUsername else partial(USERNAME)
        ciPassword = if (ciPassword >= 0) ciPassword else partial(PASSWORD)
        ciUrl = if (ciUrl >= 0) ciUrl else partial(URL)
        ciTotp = if (ciTotp >= 0) ciTotp else partial(TOTP)
        ciNotes = if (ciNotes >= 0) ciNotes else partial(NOTES)

        fun cell(row: List<String>, idx: Int): String = if (idx >= 0 && idx < row.size) row[idx].trim() else ""

        val items = mutableListOf<Item>()
        for (row in rows.drop(1)) {
            val now = System.currentTimeMillis()
            val email = cell(row, ciEmail)
            val username = cell(row, ciUsername).ifEmpty { email } // usuário cai pro email
            val url = cell(row, ciUrl)
            val extras = mutableListOf<CustomField>()

            // Nada se perde: email (se não virou usuário) + colunas não
            // mapeadas/não-ruído viram campos personalizados; segredos ocultos.
            if (email.isNotEmpty() && email != username) extras.add(field("email", email))
            for (i in headers.indices) {
                if (i in consumed || norm[i] in IGNORE) continue
                val v = cell(row, i)
                if (v.isEmpty()) continue
                val secret = Regex("pass|senha|secret|otp|totp|2fa|key|cvv|pin|seed").containsMatchIn(norm[i])
                extras.add(field(headers[i].trim().ifEmpty { "campo ${i + 1}" }, v, secret))
            }

            items.add(
                Item(
                    id = UUID.randomUUID().toString(),
                    kind = ItemKind.LOGIN,
                    name = cell(row, ciName).ifEmpty { url.ifEmpty { username.ifEmpty { "(sem nome)" } } },
                    favorite = false,
                    folderId = null,
                    notes = cell(row, ciNotes),
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    login = Login(
                        username = username,
                        password = cell(row, ciPassword),
                        uris = if (url.isNotEmpty()) listOf(url) else listOf(""),
                        totp = Importers.extractSecret(cell(row, ciTotp)),
                    ),
                    card = null,
                    identity = null,
                    passwordHistory = null,
                    customFields = extras.takeIf { it.isNotEmpty() },
                    attachments = null,
                ),
            )
        }
        return items
    }
}
