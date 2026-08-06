package com.localkeys.android.data.autofill

import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Vault

/**
 * Lógica pura de casar os logins do vault com o site/pacote que pediu
 * preenchimento. Roda na JVM nos testes — sem nenhum Android.
 */
object AutofillMatcher {

    /** Limite de datasets sugeridos por pedido (evita lista infinita). */
    const val MAX_DATASETS = 8

    /** Nº máximo de logins "sugestões" quando nada casou exato. */
    const val MAX_FALLBACK = 4

    /**
     * Normaliza um domínio/site para comparação: remove esquema, credenciais,
     * porta, caminho, query, fragmento e `www.`.
     *   "https://www.Example.com/login?x=1" -> "example.com"
     *   "example.com"                        -> "example.com"
     */
    fun normalizeDomain(raw: String): String {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isBlank()) return ""
        val noScheme = trimmed.substringAfter("://", trimmed)
        val noCredentials = noScheme.substringAfterLast('@', noScheme)
        val host = noCredentials
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .removePrefix("www.")
        return host.trim()
    }

    /** Host de uma URI do vault ("https://mail.google.com/xx" → "google.com"? não — mail.google.com). */
    fun hostOfUri(uri: String): String = normalizeDomain(uri)

    /**
     * Casa um site cadastrado no item com o domínio do site que pediu fill.
     * Cobre exato e subdomínio dos dois lados (item com "google.com" casa com
     * "mail.google.com" e vice-versa).
     */
    fun matchesDomain(uri: String, domain: String): Boolean {
        val host = hostOfUri(uri)
        val dom = normalizeDomain(domain)
        if (host.isBlank() || dom.isBlank()) return false
        return host == dom || host.endsWith(".$dom") || dom.endsWith(".$host")
    }

    /**
     * Logins candidatos para o pedido de autofill, na ordem de preferência:
     * casou pelo domínio (ou pacote) primeiro; se nada casar, devolve as
     * primeiras como sugestões (usuário escolhe no picker).
     */
    fun loginsFor(vault: Vault?, packageName: String?, webDomain: String?): List<Login> {
        val v = vault ?: return emptyList()
        val logins = v.items
            .filter { it.kind == ItemKind.LOGIN && it.login != null }
            .mapNotNull { it.login }
            .distinctBy { it.username to it.password }
        if (logins.isEmpty()) return emptyList()

        val domain = webDomain?.let { normalizeDomain(it) }
        val matched = if (domain != null) {
            logins.filter { login -> login.uris.any { matchesDomain(it, domain) } }
        } else {
            emptyList()
        }
        val byPackage = if (domain == null && packageName != null) {
            logins.filter { login -> login.uris.any { hostOfUri(it).contains(packageName.lowercase()) } }
        } else {
            emptyList()
        }

        val exact = (matched + byPackage).distinctBy { it.username to it.password }
        return if (exact.isNotEmpty()) {
            exact.take(MAX_DATASETS)
        } else {
            logins.take(MAX_FALLBACK)
        }
    }
}
