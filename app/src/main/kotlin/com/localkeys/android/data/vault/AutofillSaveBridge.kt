package com.localkeys.android.data.vault

/**
 * Ponte do autofill para a persistência do vault.
 *
 * O `LocalKeysAutofillService` roda no mesmo processo do app, mas quem detém o
 * `SessionKey` (necessário para recifrar o `.tkeys` com nonce novo) é o
 * `VaultViewModel`. Enquanto o cofre está destrancado, o VM registra um handler
 * aqui; o serviço só encaminha o pedido de salvar. Cofre trancado = handler
 * ausente = `canSave` falso e o serviço não grava nada.
 */
object AutofillSaveBridge {

    /** Pedido de salvar uma credencial de login vinda de um SaveRequest. */
    data class LoginSaveRequest(
        val username: String,
        val password: String,
        val uris: List<String>,
    )

    @Volatile
    private var handler: ((LoginSaveRequest) -> Unit)? = null

    /** Cofre destrancado com persistência disponível para o autofill. */
    val canSave: Boolean get() = handler != null

    /** O VM chama ao destrancar o cofre. */
    fun register(onSave: (LoginSaveRequest) -> Unit) {
        handler = onSave
    }

    /** O VM chama ao travar o cofre. */
    fun unregister() {
        handler = null
    }

    /** Devolve false se não há quem persista (cofre trancado). */
    fun save(request: LoginSaveRequest): Boolean {
        val h = handler ?: return false
        h(request)
        return true
    }
}
