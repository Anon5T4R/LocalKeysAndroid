package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.OpenedVault
import com.localkeys.android.data.crypto.SessionKey
import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.crypto.TkeysError

/**
 * Estado vivo do vault na memória — espelho do `AppState` do desktop (`lib.rs`):
 * guarda só a sessão (chave derivada + salt/params) e o plaintext decifrado.
 * A **master password nunca é retida**; salvar usa `SessionKey.seal`, que sorteia
 * um nonce novo e recifra com a mesma chave.
 */
class VaultRepository(private val crypto: TkeysCrypto) {

    private var session: SessionKey? = null
    private var current: Vault? = null

    /** Está destrancado? (sessão viva + vault em memória) */
    val isUnlocked: Boolean get() = current != null

    /** Vault destrancado — lança se ainda estiver trancado. */
    val vault: Vault
        get() = current ?: throw IllegalStateException("vault não está destrancado")

    /** Abre um `.tkeys` com a master password (roda o Argon2id). */
    fun unlock(file: ByteArray, password: String): Vault {
        val opened = crypto.openVault(password, file)
        return open(opened)
    }

    /**
     * Abre com a **chave bruta** (32 bytes) recuperada do cofre biométrico do
     * SO — sem Argon2id. O salt/params vêm do header do próprio arquivo, então
     * a sessão de salvar fica igual à do desbloqueio por senha.
     */
    fun unlockWithKey(key: ByteArray, file: ByteArray): Vault {
        val opened = crypto.openVaultWithKey(key, file)
        return open(opened)
    }

    private fun open(opened: OpenedVault): Vault {
        val vault = try {
            Vault.parse(String(opened.plaintext))
        } catch (e: Exception) {
            // Decifrou com a senha certa, mas o conteúdo não é um vault válido —
            // arquivo corrompido/truncado, não "senha incorreta".
            throw TkeysError.Corrupted
        }
        session = opened.session
        current = vault
        return vault
    }

    /** Cria um vault novo e já deixa destrancado. Devolve os bytes cifrados. */
    fun create(vault: Vault, password: String): ByteArray {
        val created = crypto.createVault(password, vault.toJson().toByteArray())
        session = created.session
        current = vault
        return created.file
    }

    /** Recifra o vault atual com um nonce novo (mesmo salt/params). */
    fun save(): ByteArray {
        val s = session ?: throw IllegalStateException("vault não está destrancado")
        return s.seal(vault.toJson().toByteArray())
    }

    /**
     * Valida um blob recém-cifrado antes de gravar: decifra com a chave da
     * sessão (sem re-rodar o Argon2id) e confere que o conteúdo é exatamente o
     * vault atual. Garante que nunca gravamos um arquivo que não reabriria.
     * Lança [TkeysError.Corrupted] se o blob não reabrir ou divergir do vault.
     */
    fun verifySaved(blob: ByteArray): Vault {
        val s = session ?: throw IllegalStateException("vault não está destrancado")
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val plaintext = try {
            crypto.openWithKey(s.keyBytes(), blob)
        } catch (e: TkeysError) {
            throw TkeysError.Corrupted
        }
        // A decifração é autenticada: se o plaintext não for byte a byte o que o
        // save() selou, o blob foi cifrado com outro conteúdo — nunca gravar.
        if (!plaintext.contentEquals(v.toJson().toByteArray())) throw TkeysError.Corrupted
        return v
    }

    /** Cópia da chave derivada (32 bytes) para o cofre biométrico. */
    fun keyBytes(): ByteArray? = session?.keyBytes()

    /**
     * Adota um arquivo modificado externamente (ex.: outro dispositivo
     * sincronizou uma versão nova via OneDrive/Google Drive): decifra com a
     * chave da sessão atual (sem re-rodar o Argon2id) e substitui o vault em
     * memória. Lança [TkeysError.Decrypt] se a senha foi trocada externamente
     * (a chave já não casa — o caminho é travar e reabrir com a senha nova) e
     * [TkeysError.Corrupted] se o arquivo externo está corrompido.
     */
    fun reload(file: ByteArray): Vault {
        val s = session ?: throw IllegalStateException("vault não está destrancado")
        val plaintext = crypto.openWithKey(s.keyBytes(), file)
        val vault = try {
            Vault.parse(String(plaintext))
        } catch (e: Exception) {
            throw TkeysError.Corrupted
        }
        current = vault
        return vault
    }

    /**
     * Anexa itens importados ao vault atual (em memória) e devolve o vault novo.
     * A gravação no arquivo é responsabilidade do chamador (`save`).
     */
    fun appendItems(items: List<Item>): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val merged = v.copy(items = v.items + items)
        current = merged
        return merged
    }

    /** Adiciona um item novo ao vault atual (em memória). */
    fun addItem(item: Item): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(items = v.items + item)
        current = updated
        return updated
    }

    /**
     * Substitui um item existente (mesmo id) no vault atual. Se a senha de um
     * login mudou, guarda a anterior no histórico (mais recente primeiro,
     * cap 20) — mesmo comportamento do `updateItem` do desktop.
     */
    fun updateItem(item: Item): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val prev = v.items.firstOrNull { it.id == item.id }
        var updated = item
        if (
            item.kind == ItemKind.LOGIN &&
            prev?.login != null &&
            item.login != null &&
            prev.login.password.isNotEmpty() &&
            prev.login.password != item.login.password
        ) {
            val history = listOf(PasswordHistoryEntry(prev.login.password, System.currentTimeMillis())) +
                (item.passwordHistory ?: emptyList())
            updated = item.copy(passwordHistory = history.take(20))
        }
        val result = v.copy(items = v.items.map { if (it.id == item.id) updated else it })
        current = result
        return result
    }

    /** Remove um item pelo id. */
    fun deleteItem(id: String): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(items = v.items.filterNot { it.id == id })
        current = updated
        return updated
    }

    /** Alterna o favorito de um item. */
    fun toggleFavorite(id: String): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(items = v.items.map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
        current = updated
        return updated
    }

    /** Adiciona uma pasta nova ao vault (em memória). */
    fun addFolder(folder: Folder): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(folders = v.folders + folder)
        current = updated
        return updated
    }

    /** Renomeia uma pasta. */
    fun renameFolder(id: String, name: String): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(folders = v.folders.map { if (it.id == id) it.copy(name = name) else it })
        current = updated
        return updated
    }

    /** Exclui uma pasta e desvincula os itens dela (os itens ficam sem pasta). */
    fun deleteFolder(id: String): Vault {
        val v = current ?: throw IllegalStateException("vault não está destrancado")
        val updated = v.copy(
            folders = v.folders.filterNot { it.id == id },
            items = v.items.map { if (it.folderId == id) it.copy(folderId = null) else it },
        )
        current = updated
        return updated
    }

    /** Descarta a sessão e o plaintext (travar o cofre). */
    fun lock() {
        session = null
        current = null
    }
}
