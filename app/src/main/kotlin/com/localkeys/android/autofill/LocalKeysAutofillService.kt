package com.localkeys.android.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.localkeys.android.MainActivity
import com.localkeys.android.R
import com.localkeys.android.data.autofill.AutofillMatcher
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.VaultAutofillCache

/**
 * Autofill nativo do Android (API do SO, zero Play Services) para login/senha.
 * Roda no processo do app: enquanto o cofre está destrancado o [VaultAutofillCache]
 * tem o vault em memória e os campos são preenchidos direto; se estiver trancado,
 * o picker oferece "Desbloquear o LocalKeys" (abre o app).
 *
 * v0.2.0 só preenche — o SaveRequest (gravar credenciais novas) fica p/ a próxima.
 */
class LocalKeysAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
            ?: return callback.onSuccess(null)

        val (usernameId, passwordId) = findAuthFields(structure)

        val vault = VaultAutofillCache.unlocked
        if (vault == null) {
            callback.onSuccess(unlockResponse(usernameId, passwordId))
            return
        }

        if (usernameId == null && passwordId == null) {
            callback.onSuccess(null)
            return
        }

        val webDomain = if (structure.windowNodeCount > 0) {
            structure.getWindowNodeAt(0).rootViewNode.webDomain
        } else {
            null
        }
        val packageName = structure.activityComponent?.packageName

        val logins = AutofillMatcher.loginsFor(vault, packageName, webDomain)
        if (logins.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val response = FillResponse.Builder()
        logins.forEach { login ->
            buildDataset(login, usernameId, passwordId)?.let { response.addDataset(it) }
        }
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    /**
     * Cofre trancado: devolve uma resposta de "autenticação" — o picker mostra
     * o atalho e, ao tocar, abre o app para o usuário destrancar. Depois de
     * voltar ao campo, um novo onFillRequest encontra o vault aberto.
     */
    private fun unlockResponse(usernameId: AutofillId?, passwordId: AutofillId?): FillResponse? {
        val ids = listOfNotNull(usernameId, passwordId).toTypedArray()
        if (ids.isEmpty()) return null
        val unlockSender = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
        val view = RemoteViews(packageName, R.layout.autofill_unlock)
        return FillResponse.Builder()
            .setAuthentication(ids, unlockSender, view)
            .build()
    }

    /** Procura os campos de usuário/senha na árvore da tela assistida. */
    private fun findAuthFields(structure: AssistStructure): Pair<AutofillId?, AutofillId?> {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null

        fun visit(node: AssistStructure.ViewNode) {
            if (usernameId != null && passwordId != null) return
            val id = node.autofillId
            if (id != null) {
                val hints = node.autofillHints.orEmpty().map { it.lowercase() }
                if (hints.any { it == "password" || it == "currentpassword" || it == "newpassword" }) {
                    passwordId = id
                }
                if (hints.any { it == "username" || it == "emailaddress" || it == "loginid" || it == "texturi" }) {
                    usernameId = id
                }
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }

        for (w in 0 until structure.windowNodeCount) {
            visit(structure.getWindowNodeAt(w).rootViewNode)
        }
        return usernameId to passwordId
    }

    private fun buildDataset(
        login: Login,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
    ): Dataset? {
        val builder = Dataset.Builder()
        var any = false
        if (passwordId != null) {
            builder.setValue(passwordId, AutofillValue.forText(login.password))
            any = true
        }
        if (usernameId != null) {
            builder.setValue(usernameId, AutofillValue.forText(login.username))
            any = true
        }
        if (!any) return null
        return builder.build()
    }
}
