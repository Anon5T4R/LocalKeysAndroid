package com.localkeys.android.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.localkeys.android.R
import com.localkeys.android.data.biometric.BiometricVaultKey
import com.localkeys.android.data.autofill.AutofillMatcher
import com.localkeys.android.data.crypto.Hex
import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.crypto.TkeysError
import com.localkeys.android.data.import.BitwardenImport
import com.localkeys.android.data.import.BwDecrypt
import com.localkeys.android.data.import.ImportFormat
import com.localkeys.android.data.import.Importers
import com.localkeys.android.data.export.VaultExporter
import com.localkeys.android.data.store.SettingsStore
import com.localkeys.android.data.vault.AutofillSaveBridge
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.Vault
import com.localkeys.android.data.vault.VaultAutofillCache
import com.localkeys.android.data.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

/**
 * O arquivo no disco mudou desde a última leitura/gravação conhecida (ex.: o
 * OneDrive/Google Drive sincronizou uma versão nova vinda do desktop). Lançada
 * por [VaultViewModel.writeAndVerify] ANTES de gravar, para nunca sobrescrever
 * em silêncio o trabalho do outro dispositivo. A UI oferece recarregar ou
 * sobrescrever.
 */
class ExternalChangeException : IOException("o arquivo foi modificado fora do app")

/**
 * Estado da UI + ponte entre telas, `.tkeys` e cofre biométrico do SO.
 *
 * A master password só entra em [unlock]/[onCreatedDocument] e morre no escopo
 * da chamada; o resto do tempo o app guarda apenas a **chave derivada** (na
 * sessão, em memória, ou cifrada pela biometria no DataStore).
 */
class VaultViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Screen {
        data object Unlock : Screen
        data object Vault : Screen
    }

    /** Origem do import pendente: muda a mensagem do diálogo de senha. */
    enum class ImportSource { BITWARDEN, KDBX }

    /** Import em andamento: arquivo escolhido + se precisa da senha do export. */
    data class PendingImport(
        val fileName: String,
        val needsPassword: Boolean,
        val source: ImportSource,
    )

    data class UiState(
        val screen: Screen = Screen.Unlock,
        val vault: Vault? = null,
        val vaultUri: String? = null,
        val biometricAvailable: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
        val pendingImport: PendingImport? = null,
        /** Arquivo mudou no disco fora do app → UI mostra o diálogo de conflito. */
        val externalChange: Boolean = false,
    )

    private val crypto = TkeysCrypto(LazySodiumAndroid(SodiumAndroid()))
    private val repository = VaultRepository(crypto)
    private val settings = SettingsStore(application)
    private val biometricKey = BiometricVaultKey()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Últimos bytes conhecidos do arquivo no disco. Serve tanto para o unlock
     * (decifrar) quanto como **baseline da detecção de mudança externa**: antes
     * de gravar, comparamos o disco atual com isto; depois de gravar, atualizamos
     * para os bytes recém-gravados.
     */
    private var cachedBlob: ByteArray? = null
    private var wrappedKeyHex: String? = null
    private var biometricOn: Boolean = false
    private var encryptedImport: String? = null
    private var pendingKdbx: ByteArray? = null
    private var pendingExport: String? = null

    init {
        viewModelScope.launch {
            settings.vaultUri.collect { uri ->
                _state.update { it.copy(vaultUri = uri) }
                if (uri != null) loadBlob(uri)
            }
        }
        viewModelScope.launch {
            settings.biometricEnabled.collect { enabled ->
                biometricOn = enabled
                _state.update { it.copy(biometricAvailable = enabled && !wrappedKeyHex.isNullOrEmpty()) }
            }
        }
        viewModelScope.launch {
            settings.wrappedKeyHex.collect { hex ->
                wrappedKeyHex = hex
                _state.update { it.copy(biometricAvailable = biometricOn && !hex.isNullOrEmpty()) }
            }
        }
    }

    // ── Abrir cofre existente ────────────────────────────────────────────

    /** URI escolhida no SAF (abrir). Persiste e carrega os bytes do arquivo. */
    fun onVaultUriChosen(uri: Uri) {
        viewModelScope.launch { settings.setVaultUri(uri.toString()) }
    }

    private fun loadBlob(uri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val resolver = getApplication<Application>().contentResolver
                runCatching {
                    resolver.takePersistableUriPermission(
                        Uri.parse(uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                val bytes = resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                    ?: throw IOException(str(R.string.err_file_not_found))
                cachedBlob = bytes
            } catch (e: Exception) {
                cachedBlob = null
                _state.update {
                    it.copy(
                        vaultUri = null,
                        error = str(R.string.msg_load_failed, e.message ?: ""),
                    )
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Desbloqueia com a master password (roda o Argon2id em background). */
    fun unlock(password: String) {
        if (password.isBlank()) {
            _state.update { it.copy(error = str(R.string.msg_password_empty)) }
            return
        }
        val blob = cachedBlob
        if (blob == null) {
            _state.update { it.copy(error = str(R.string.msg_no_vault_loaded)) }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val vault = repository.unlock(blob, password)
                syncAutofillCache(vault)
                _state.update { it.copy(vault = vault, screen = Screen.Vault, busy = false) }
            } catch (e: TkeysError) {
                _state.update { it.copy(error = tkeysErrorMessage(e), busy = false) }
            }
        }
    }

    // ── Criar cofre novo ─────────────────────────────────────────────────

    /**
     * Cria o vault (vazio) e grava no documento escolhido no SAF. A senha vem
     * da tela só até aqui — o repositório guarda a chave derivada, não a senha.
     */
    fun onCreatedDocument(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val file = repository.create(Vault.empty(), password)
                repository.verifySaved(file) // nunca grava um blob que não reabriria
                val resolver = getApplication<Application>().contentResolver
                withContext(Dispatchers.IO) {
                    runCatching {
                        resolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
                writeAndVerify(resolver, uri, file)
                settings.setVaultUri(uri.toString())
                syncAutofillCache(repository.vault)
                _state.update { it.copy(vault = repository.vault, screen = Screen.Vault, busy = false) }
            } catch (e: Exception) {
                _state.update { it.copy(error = str(R.string.msg_create_failed, e.message ?: ""), busy = false) }
            }
        }
    }

    // ── Salvar (recifrar e gravar no mesmo arquivo) ──────────────────────

    fun save() {
        if (_state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                persistAndCommit(repository.vault, notice = str(R.string.msg_vault_saved))
            } catch (e: Exception) {
                failSave(e, R.string.op_save)
            }
        }
    }

    /**
     * Sobrescreve o arquivo com o vault em memória mesmo havendo mudança externa
     * (usuário confirmou no diálogo de conflito). Como as edições pendentes já
     * estão no vault em memória, isto serve de "retry" para qualquer ação que
     * tenha esbarrado no [ExternalChangeException].
     */
    fun forceSave() {
        if (_state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, externalChange = false, error = null) }
            try {
                persistAndCommit(repository.vault, notice = str(R.string.msg_vault_saved), force = true)
            } catch (e: Exception) {
                failSave(e, R.string.op_save)
            }
        }
    }

    /**
     * Recarrega o arquivo do disco, adotando a versão externa e descartando as
     * edições em memória não salvas (usuário confirmou no diálogo de conflito).
     * Reusa a chave da sessão atual, sem pedir a senha de novo.
     */
    fun reloadFromDisk() {
        val uri = _state.value.vaultUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, externalChange = false, error = null) }
            try {
                val resolver = getApplication<Application>().contentResolver
                val bytes = readDocumentBytes(resolver, Uri.parse(uri))
                    ?: throw IOException(str(R.string.err_file_not_found))
                val vault = repository.reload(bytes)
                cachedBlob = bytes
                syncAutofillCache(vault)
                _state.update { it.copy(vault = vault, busy = false, notice = str(R.string.msg_reloaded)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = str(R.string.msg_reload_failed, e.message ?: ""), busy = false) }
            }
        }
    }

    /** Fecha o diálogo de conflito sem agir (usuário decide depois). */
    fun dismissExternalChange() {
        _state.update { it.copy(externalChange = false) }
    }

    // ── Desbloqueio biométrico (chave do vault cifrada no Keystore) ──────

    /** Cipher ENCRYPT pronto para o BiometricPrompt — ativar biometria. */
    fun newWrapCipher(): Cipher = biometricKey.createEncryptCipher()

    /** Cipher DECRYPT do cofre biométrico guardado — ou null se indisponível. */
    fun biometricDecryptCipher(): Cipher? {
        val hex = wrappedKeyHex ?: return null
        return runCatching { biometricKey.createDecryptCipher(Hex.decode(hex)) }.getOrNull()
    }

    /** Após a biometria: cifra a chave derivada e guarda no DataStore. */
    fun enableBiometricWithCipher(cipher: Cipher) {
        val key = repository.keyBytes() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val wrapped = biometricKey.wrapWithCipher(key, cipher)
                settings.setWrappedKeyHex(Hex.encode(wrapped))
                settings.setBiometricEnabled(true)
                _state.update { it.copy(notice = str(R.string.msg_biometric_enabled)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = str(R.string.biometric_unavailable, e.message ?: "")) }
            }
        }
    }

    /** Após a biometria: recupera a chave e abre o vault sem senha. */
    fun unlockWithBiometricCipher(cipher: Cipher) {
        val hex = wrappedKeyHex
        val blob = cachedBlob
        if (hex == null || blob == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val key = biometricKey.unwrapWithCipher(Hex.decode(hex), cipher)
                val vault = repository.unlockWithKey(key, blob)
                syncAutofillCache(vault)
                _state.update { it.copy(vault = vault, screen = Screen.Vault, busy = false) }
            } catch (e: Exception) {
                // Cadastrar dedo/rosto novo invalida a chave do Keystore →
                // o desbloqueio rápido morre e o app volta para a senha.
                if (e is TkeysError || e is AEADBadTagException || e is KeyPermanentlyInvalidatedException) {
                    disableBiometric()
                    _state.update { it.copy(error = str(R.string.msg_biometric_changed), busy = false) }
                } else {
                    _state.update { it.copy(error = str(R.string.msg_biometric_failed, e.message ?: ""), busy = false) }
                }
            }
        }
    }

    /** Desativa o desbloqueio biométrico (apaga chave do Keystore + prefs). */
    fun disableBiometric() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { biometricKey.delete() }
            settings.setBiometricEnabled(false)
            settings.setWrappedKeyHex(null)
            _state.update { it.copy(biometricAvailable = false) }
        }
    }

    // ── Importar de outro gerenciador ────────────────────────────────────

    /** Arquivo escolhido no SAF: detecta formato e importa ou pede a senha. */
    fun onImportUriChosen(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val resolver = getApplication<Application>().contentResolver
                val fileName = resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException(str(R.string.err_file_not_found))
                val content = bytes.toString(Charsets.UTF_8)

                when (val format = Importers.detectFormat(fileName, content)) {
                    ImportFormat.KDBX -> {
                        pendingKdbx = bytes
                        _state.update {
                            it.copy(
                                busy = false,
                                pendingImport = PendingImport(
                                    fileName ?: str(R.string.import_default_kdbx),
                                    needsPassword = true,
                                    source = ImportSource.KDBX,
                                ),
                            )
                        }
                    }
                    ImportFormat.BITWARDEN_ENCRYPTED -> {
                        encryptedImport = content
                        _state.update {
                            it.copy(
                                busy = false,
                                pendingImport = PendingImport(
                                    fileName ?: str(R.string.import_default_bitwarden),
                                    needsPassword = true,
                                    source = ImportSource.BITWARDEN,
                                ),
                            )
                        }
                    }
                    else -> performImport(Importers.parseText(content, format), fileName)
                }
            } catch (e: Exception) {
                encryptedImport = null
                pendingKdbx = null
                _state.update {
                    it.copy(busy = false, pendingImport = null, error = e.message ?: str(R.string.msg_import_generic))
                }
            }
        }
    }

    /** Senha do export: decifra Bitwarden ou KeePass e importa. */
    fun onImportPassword(password: String) {
        if (encryptedImport == null && pendingKdbx == null) return
        if (password.isBlank()) {
            _state.update { it.copy(error = str(R.string.msg_import_password_empty)) }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val items = pendingKdbx?.let { Importers.parseKdbx(it, password) }
                    ?: run {
                        val json = encryptedImport ?: return@launch
                        BitwardenImport.parse(BwDecrypt.decryptExport(json, password))
                    }
                performImport(items, null)
            } catch (e: Exception) {
                // Senha errada: mantém o diálogo aberto para tentar de novo.
                _state.update { it.copy(busy = false, error = e.message ?: str(R.string.msg_import_generic)) }
            }
        }
    }

    /** Cancela o import pendente. */
    fun dismissImport() {
        encryptedImport = null
        pendingKdbx = null
        _state.update { it.copy(pendingImport = null) }
    }

    /** Anexa os itens ao vault, recifra e grava no arquivo. */
    private suspend fun performImport(items: List<Item>, fileName: String?) {
        if (items.isEmpty()) {
            _state.update {
                it.copy(
                    busy = false,
                    pendingImport = null,
                    notice = fileName?.let { name -> str(R.string.msg_import_empty_in, name) }
                        ?: str(R.string.msg_import_empty),
                )
            }
            return
        }
        try {
            val merged = repository.appendItems(items)
            persistAndCommit(merged, notice = str(R.string.msg_imported, items.size))
            encryptedImport = null
            pendingKdbx = null
            _state.update { it.copy(pendingImport = null) }
        } catch (e: Exception) {
            encryptedImport = null
            pendingKdbx = null
            _state.update { it.copy(pendingImport = null) }
            failSave(e, R.string.op_import)
        }
    }

    // ── Editar / criar / excluir itens ───────────────────────────────────

    /** Cria (id novo) ou atualiza (id existente) um item e grava no arquivo. */
    fun saveItem(item: Item) {
        if (_state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val isNew = repository.vault.items.none { it.id == item.id }
                val updated = if (isNew) repository.addItem(item) else repository.updateItem(item)
                persistAndCommit(
                    updated,
                    notice = str(if (isNew) R.string.msg_item_added else R.string.msg_item_updated),
                )
            } catch (e: Exception) {
                failSave(e, R.string.op_save_item)
            }
        }
    }

    /** Remove um item e grava no arquivo. */
    fun deleteItem(id: String) {
        if (_state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val updated = repository.deleteItem(id)
                persistAndCommit(updated, notice = str(R.string.msg_item_deleted))
            } catch (e: Exception) {
                failSave(e, R.string.op_delete_item)
            }
        }
    }

    /** Alterna o favorito de um item e grava no arquivo. */
    fun toggleFavorite(id: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val updated = repository.toggleFavorite(id)
                persistAndCommit(updated, notice = null)
            } catch (e: Exception) {
                failSave(e, R.string.op_favorite)
            }
        }
    }

    // ── Pastas ───────────────────────────────────────────────────────────
    /** Cria uma pasta nova e grava no arquivo. */
    fun addFolder(name: String) {
        if (_state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val folder = Folder(UUID.randomUUID().toString(), name.trim())
                val updated = repository.addFolder(folder)
                persistAndCommit(updated, notice = str(R.string.msg_folder_created))
            } catch (e: Exception) {
                failSave(e, R.string.op_create_folder)
            }
        }
    }

    /** Renomeia uma pasta e grava no arquivo. */
    fun renameFolder(id: String, name: String) {
        if (_state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val updated = repository.renameFolder(id, name.trim())
                persistAndCommit(updated, notice = str(R.string.msg_folder_renamed))
            } catch (e: Exception) {
                failSave(e, R.string.op_rename_folder)
            }
        }
    }

    /** Exclui uma pasta (os itens ficam sem pasta) e grava no arquivo. */
    fun deleteFolder(id: String) {
        if (_state.value.vaultUri == null) return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val updated = repository.deleteFolder(id)
                persistAndCommit(updated, notice = str(R.string.msg_folder_deleted))
            } catch (e: Exception) {
                failSave(e, R.string.op_delete_folder)
            }
        }
    }

    /**
     * Recifra com nonce novo, **valida o blob antes de gravar** (decifra com a
     * sessão e confere que reabre exatamente o vault atual) e, depois de gravar,
     * **relê o documento** para conferir que o provider não truncou/embaralhou
     * nada — se falhou, reescreve uma vez. O estado só é comitado se o arquivo
     * gravado reabre de verdade.
     */
    private suspend fun persistAndCommit(updated: Vault, notice: String?, force: Boolean = false) {
        val file = repository.save()
        repository.verifySaved(file) // nunca grava um blob que não reabriria
        val uri = _state.value.vaultUri
            ?: throw IOException(str(R.string.err_no_file))
        writeAndVerify(getApplication<Application>().contentResolver, Uri.parse(uri), file, force)
        syncAutofillCache(updated)
        _state.update { it.copy(vault = updated, busy = false, notice = notice) }
    }

    /**
     * Grava o blob no documento SAF e confere **relendo o que ficou gravado**
     * (abre com a sessão e confere o conteúdo): se o provider truncou ou
     * corrompeu, reescreve uma vez. Lança [IOException] se o arquivo gravado
     * não reabre — o estado do chamador nunca é comitado nesse caso.
     *
     * Antes de gravar, **detecta mudança externa**: se o que está no disco
     * difere do último estado conhecido ([cachedBlob]), outro dispositivo
     * sincronizou uma versão nova → lança [ExternalChangeException] em vez de
     * sobrescrever (a não ser com `force = true`, confirmado pelo usuário).
     */
    private suspend fun writeAndVerify(
        resolver: ContentResolver,
        document: Uri,
        file: ByteArray,
        force: Boolean = false,
    ) {
        suspend fun writeOnce(): Boolean = withContext(Dispatchers.IO) {
            resolver.openOutputStream(document)?.use { it.write(file) } != null
        }
        val known = cachedBlob
        if (!force && known != null) {
            val currentDisk = readDocumentBytes(resolver, document)
            // Se não dá pra ler o disco não dá pra comparar; a verificação
            // pós-gravação continua valendo.
            if (currentDisk != null && !currentDisk.contentEquals(known)) {
                throw ExternalChangeException()
            }
        }
        if (!writeOnce()) throw IOException(str(R.string.err_write_failed))
        if (writtenIsSane(resolver, document)) {
            cachedBlob = file
            return
        }
        if (!writeOnce()) throw IOException(str(R.string.err_write_retry_failed))
        if (!writtenIsSane(resolver, document)) throw IOException(str(R.string.err_write_unrecoverable))
        cachedBlob = file
    }

    /** Lê os bytes do documento SAF (null se não der pra ler). */
    private suspend fun readDocumentBytes(resolver: ContentResolver, document: Uri): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(document)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Tratamento comum de falha de gravação: [ExternalChangeException] vira o
     * diálogo de conflito; [TkeysError] vira a mensagem mapeada do erro; o
     * resto vira "contexto: mensagem".
     */
    private fun failSave(e: Exception, @StringRes context: Int) {
        if (e is ExternalChangeException) {
            _state.update { it.copy(externalChange = true, busy = false) }
        } else {
            val detail = if (e is TkeysError) tkeysErrorMessage(e) else (e.message ?: "")
            _state.update {
                it.copy(error = str(R.string.msg_op_failed, str(context), detail), busy = false)
            }
        }
    }

    /** Mensagem de erro do `.tkeys` mapeada por tipo (recursos, não o message interno). */
    private fun tkeysErrorMessage(e: TkeysError): String = when (e) {
        TkeysError.BadFormat -> str(R.string.tkeys_bad_format)
        is TkeysError.UnsupportedVersion -> str(R.string.tkeys_unsupported_version, e.version)
        TkeysError.Kdf -> str(R.string.tkeys_kdf)
        TkeysError.Decrypt -> str(R.string.tkeys_decrypt)
        TkeysError.Corrupted -> str(R.string.tkeys_corrupted)
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /** Relê o documento e confere que reabre com a sessão e bate com o vault atual. */
    private suspend fun writtenIsSane(resolver: ContentResolver, document: Uri): Boolean {
        val blob = withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(document)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        } ?: return false
        return runCatching { repository.verifySaved(blob) }.isSuccess
    }

    /** Mantém o cache do autofill em sincronia com o estado do cofre. */
    private fun syncAutofillCache(vault: Vault?) {
        if (vault == null) {
            VaultAutofillCache.clear()
            AutofillSaveBridge.unregister()
        } else {
            VaultAutofillCache.set(vault)
            AutofillSaveBridge.register(::persistAutofillLogin)
        }
    }

    /**
     * Credencial nova vinda de um SaveRequest do autofill: adiciona como item
     * de login e recifra/grava. Sem duplicar quando já existe a mesma
     * combinação usuário+senha+site.
     */
    private fun persistAutofillLogin(request: AutofillSaveBridge.LoginSaveRequest) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val vault = repository.vault
                val dupe = vault.items.any { item ->
                    item.kind == ItemKind.LOGIN &&
                        item.login?.username == request.username &&
                        item.login?.password == request.password &&
                        request.uris.isNotEmpty() &&
                        item.login?.uris?.intersect(request.uris.toSet())?.isNotEmpty() == true
                }
                if (dupe) return@launch

                val now = System.currentTimeMillis()
                val domain = request.uris.firstNotNullOfOrNull { AutofillMatcher.normalizeDomain(it) }
                val item = Item(
                    id = UUID.randomUUID().toString(),
                    kind = ItemKind.LOGIN,
                    name = domain?.takeIf { it.isNotBlank() }
                        ?: request.username.takeIf { it.isNotBlank() }
                        ?: "Credencial do autofill",
                    favorite = false,
                    folderId = null,
                    notes = "",
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    login = Login(
                        username = request.username,
                        password = request.password,
                        uris = request.uris,
                        totp = "",
                    ),
                    card = null,
                    identity = null,
                    passwordHistory = null,
                    customFields = null,
                    attachments = null,
                )
                repository.addItem(item)
                persistAndCommit(repository.vault, notice = null)
            } catch (_: Exception) {
                // Cofre trancou no meio do caminho ou falha de gravação: o
                // autofill segue normal e o usuário salva manualmente depois.
            }
        }
    }

    // ── Exportar ─────────────────────────────────────────────────────────

    /** "json" ou "csv": monta o conteúdo em claro e guarda até o SAF responder. */
    fun exportRequested(format: String) {
        try {
            pendingExport = when (format) {
                "csv" -> VaultExporter.toCsv(repository.vault)
                else -> VaultExporter.toJson(repository.vault)
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = str(R.string.msg_export_prepare_failed, e.message ?: "")) }
        }
    }

    /** Documento escolhido no SAF: grava o export em claro nele. */
    fun onExportUriChosen(uri: Uri) {
        val content = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val written = getApplication<Application>().contentResolver
                    .openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } != null
                if (!written) throw IOException(str(R.string.err_write_failed))
                _state.update { it.copy(notice = str(R.string.msg_export_saved)) }
            } catch (e: Exception) {
                _state.update { it.copy(error = str(R.string.msg_export_save_failed, e.message ?: "")) }
            }
        }
    }

    // ── Travar / limpar ──────────────────────────────────────────────────

    fun lock() {
        repository.lock()
        syncAutofillCache(null)
        _state.update { it.copy(vault = null, screen = Screen.Unlock) }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
