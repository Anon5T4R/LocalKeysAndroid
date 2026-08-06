package com.localkeys.android.data.vault

/**
 * Vault destrancado visível para o `LocalKeysAutofillService` (que roda no
 * mesmo processo do app). Enquanto o cofre está aberto, o autofill preenche
 * sem pedir senha de novo; ao travar, o cache é limpo e o autofill passa a
 * oferecer "Desbloquear o LocalKeys".
 *
 * Guardar o plaintext num singleton é o mesmo nível de confiança que o
 * `VaultViewModel` já tinha — é o mesmo processo, sem superfície extra.
 */
object VaultAutofillCache {

    @Volatile
    private var current: Vault? = null

    /** Vault destrancado para o autofill, ou null se trancado. */
    val unlocked: Vault? get() = current

    fun set(vault: Vault) {
        current = vault
    }

    fun clear() {
        current = null
    }
}
