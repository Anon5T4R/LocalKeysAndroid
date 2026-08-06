package com.localkeys.android.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.localkeys.android.MainActivity
import com.localkeys.android.R
import com.localkeys.android.data.autofill.AutofillMatcher
import com.localkeys.android.data.vault.AutofillSaveBridge
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.VaultAutofillCache

/**
 * Autofill nativo do Android (API do SO, zero Play Services) para login/senha.
 * Roda no processo do app: enquanto o cofre está destrancado o [VaultAutofillCache]
 * tem o vault em memória e os campos são preenchidos direto; se estiver trancado,
 * o picker oferece "Desbloquear o LocalKeys" (abre o app).
 *
 * Preenchimento e salvamento (SaveRequest) de credenciais novas — v0.2.0 só
 * preenchia; desde a v0.3.0 também grava logins novos quando o cofre está aberto.
 */
class LocalKeysAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
            ?: return callback.onSuccess(null)

        val auth = findAuthFields(structure)

        val vault = VaultAutofillCache.unlocked
        if (vault == null) {
            callback.onSuccess(unlockResponse(auth.usernameId, auth.passwordId))
            return
        }

        if (auth.usernameId == null && auth.passwordId == null) {
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
            buildDataset(login, auth.usernameId, auth.passwordId)?.let { response.addDataset(it) }
        }
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        val auth = structure?.let { findAuthFields(it) }
        val username = auth?.usernameValue
        val password = auth?.passwordValue

        if (structure == null || auth == null || password == null) {
            callback.onSuccess()
            return
        }
        // Só grava quando há senha preenchida; sem usuário ainda dá pra salvar
        // (campo de usuário vazio é legítimo em alguns cadastros).
        val uris = buildList {
            if (structure.windowNodeCount > 0) {
                structure.getWindowNodeAt(0).rootViewNode.webDomain?.let { add(it) }
            }
            structure.activityComponent?.packageName?.let { add(it) }
        }

        if (!AutofillSaveBridge.save(
                AutofillSaveBridge.LoginSaveRequest(
                    username = username ?: "",
                    password = password,
                    uris = uris,
                )
            )
        ) {
            // Cofre trancado: não há como gravar sem destravar. Um aviso leve
            // orienta abrir o app e salvar manualmente.
            notify(getString(R.string.autofill_save_locked))
        }
        callback.onSuccess()
    }

    /** Notificação leve de feedback do autofill (canal próprio, baixa prioridade). */
    private fun notify(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIF_CHANNEL,
                getString(R.string.autofill_notify_channel),
                android.app.NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
        val intent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_localkeys)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, notification) }
    }

    companion object {
        private const val NOTIF_CHANNEL = "autofill_save"
        private const val NOTIF_ID = 1001
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

    /** Campos de autenticação encontrados (ids para o fill + valores p/ o save). */
    private class AuthFields(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val usernameValue: String?,
        val passwordValue: String?,
    )

    /** Procura os campos de usuário/senha na árvore da tela assistida. */
    private fun findAuthFields(structure: AssistStructure): AuthFields {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var usernameValue: String? = null
        var passwordValue: String? = null

        fun visit(node: AssistStructure.ViewNode) {
            if (usernameId != null && passwordId != null) return
            val id = node.autofillId
            if (id != null) {
                val hints = node.autofillHints.orEmpty().map { it.lowercase() }
                if (hints.any { it == "password" || it == "currentpassword" || it == "newpassword" }) {
                    if (passwordId == null) {
                        passwordId = id
                        val value = node.autofillValue
                        passwordValue = if (value?.isText == true) value.textValue.toString() else null
                    }
                }
                if (hints.any { it == "username" || it == "emailaddress" || it == "loginid" || it == "texturi" }) {
                    if (usernameId == null) {
                        usernameId = id
                        val value = node.autofillValue
                        usernameValue = if (value?.isText == true) value.textValue.toString() else null
                    }
                }
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }

        for (w in 0 until structure.windowNodeCount) {
            visit(structure.getWindowNodeAt(w).rootViewNode)
        }
        return AuthFields(usernameId, passwordId, usernameValue, passwordValue)
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
