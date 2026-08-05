package com.localkeys.android.data.vault

import com.localkeys.android.data.crypto.OpenedVault
import com.localkeys.android.data.crypto.SessionKey
import com.localkeys.android.data.crypto.TkeysCrypto

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
        val vault = Vault.parse(String(opened.plaintext))
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

    /** Cópia da chave derivada (32 bytes) para o cofre biométrico. */
    fun keyBytes(): ByteArray? = session?.keyBytes()

    /** Descarta a sessão e o plaintext (travar o cofre). */
    fun lock() {
        session = null
        current = null
    }
}
