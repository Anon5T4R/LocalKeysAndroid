package com.localkeys.android.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
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
    )

    private val crypto = TkeysCrypto(LazySodiumAndroid(SodiumAndroid()))
    private val repository = VaultRepository(crypto)
    private val settings = SettingsStore(application)
    private val biometricKey = BiometricVaultKey()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

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
                    ?: throw IOException("arquivo não encontrado")
                cachedBlob = bytes
            } catch (e: Exception) {
                cachedBlob = null
                _state.update {
                    it.copy(
                        vaultUri = null,
                        error = "Não foi possível ler o cofre: ${e.message}",
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
            _state.update { it.copy(error = "Digite a senha-mestra.") }
            return
        }
        val blob = cachedBlob
        if (blob == null) {
            _state.update { it.copy(error = "Nenhum cofre carregado.") }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val vault = repository.unlock(blob, password)
                syncAutofillCache(vault)
                _state.update { it.copy(vault = vault, screen = Screen.Vault, busy = false) }
            } catch (e: TkeysError) {
                _state.update { it.copy(error = e.message, busy = false) }
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
                _state.update { it.copy(error = "Não foi possível criar o cofre: ${e.message}", busy = false) }
            }
        }
    }

    // ── Salvar (recifrar e gravar no mesmo arquivo) ──────────────────────

    fun save() {
        val uri = _state.value.vaultUri ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val file = repository.save()
                repository.verifySaved(file) // nunca grava um blob que não reabriria
                val resolver = getApplication<Application>().contentResolver
                writeAndVerify(resolver, Uri.parse(uri), file)
                _state.update { it.copy(busy = false, notice = "Cofre salvo.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Falha ao salvar: ${e.message}", busy = false) }
            }
        }
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
                _state.update { it.copy(notice = "Desbloqueio biométrico ativado.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Biometria indisponível: ${e.message}") }
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
                    _state.update { it.copy(error = "Biometria alterada; desbloqueie com a senha.", busy = false) }
                } else {
                    _state.update { it.copy(error = "Falha na biometria: ${e.message}", busy = false) }
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
                    ?: throw IOException("arquivo não encontrado")
                val content = bytes.toString(Charsets.UTF_8)

                when (val format = Importers.detectFormat(fileName, content)) {
                    ImportFormat.KDBX -> {
                        pendingKdbx = bytes
                        _state.update {
                            it.copy(
                                busy = false,
                                pendingImport = PendingImport(
                                    fileName ?: "cofre KeePass",
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
                                    fileName ?: "export bitwarden",
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
                    it.copy(busy = false, pendingImport = null, error = e.message ?: "Falha ao importar.")
                }
            }
        }
    }

    /** Senha do export: decifra Bitwarden ou KeePass e importa. */
    fun onImportPassword(password: String) {
        if (encryptedImport == null && pendingKdbx == null) return
        if (password.isBlank()) {
            _state.update { it.copy(error = "Digite a senha do export.") }
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
                _state.update { it.copy(busy = false, error = e.message ?: "Falha ao importar.") }
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
                    notice = "Nenhum item para importar${fileName?.let { " em $it" } ?: ""}.",
                )
            }
            return
        }
        try {
            val merged = repository.appendItems(items)
            val file = repository.save()
            repository.verifySaved(file) // nunca grava um blob que não reabriria
            val uri = _state.value.vaultUri
                ?: throw IOException("cofre não está associado a um arquivo")
            writeAndVerify(getApplication<Application>().contentResolver, Uri.parse(uri), file)
            encryptedImport = null
            pendingKdbx = null
            syncAutofillCache(merged)
            _state.update {
                it.copy(
                    busy = false,
                    pendingImport = null,
                    vault = merged,
                    notice = "${items.size} itens importados.",
                )
            }
        } catch (e: Exception) {
            encryptedImport = null
            pendingKdbx = null
            _state.update {
                it.copy(busy = false, pendingImport = null, error = "Falha ao importar: ${e.message}")
            }
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
                persistAndCommit(updated, notice = if (isNew) "Item adicionado." else "Item atualizado.")
            } catch (e: Exception) {
                _state.update { it.copy(error = "Falha ao salvar o item: ${e.message}", busy = false) }
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
                persistAndCommit(updated, notice = "Item removido.")
            } catch (e: Exception) {
                _state.update { it.copy(error = "Falha ao remover o item: ${e.message}", busy = false) }
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
                _state.update { it.copy(error = "Falha ao atualizar o favorito: ${e.message}", busy = false) }
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
                persistAndCommit(updated, notice = "Pasta criada.")
            } catch (e: Exception) {
                _state.update { it.copy(error = "Falha ao criar a pasta: ${e.message}", busy = false) }
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
                persistAndCommit(updated, notice = "Pasta renomeada.")
            } catch (e: Exception) {
                _state.update { it.copy(error = "Falha ao renomear a pasta: ${e.message}", busy = false) }
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
                persistAndCommit(updated, notice = "Pasta excluída.")
            } catch (e: Exception) {
                _state.update { it.copy(error = "Falha ao excluir a pasta: ${e.message}", busy = false) }
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
    private suspend fun persistAndCommit(updated: Vault, notice: String?) {
        val file = repository.save()
        repository.verifySaved(file) // nunca grava um blob que não reabriria
        val uri = _state.value.vaultUri
            ?: throw IOException("cofre não está associado a um arquivo")
        writeAndVerify(getApplication<Application>().contentResolver, Uri.parse(uri), file)
        syncAutofillCache(updated)
        _state.update { it.copy(vault = updated, busy = false, notice = notice) }
    }

    /**
     * Grava o blob no documento SAF e confere **relendo o que ficou gravado**
     * (abre com a sessão e confere o conteúdo): se o provider truncou ou
     * corrompeu, reescreve uma vez. Lança [IOException] se o arquivo gravado
     * não reabre — o estado do chamador nunca é comitado nesse caso.
     */
    private suspend fun writeAndVerify(resolver: ContentResolver, document: Uri, file: ByteArray) {
        suspend fun writeOnce(): Boolean = withContext(Dispatchers.IO) {
            resolver.openOutputStream(document)?.use { it.write(file) } != null
        }
        if (!writeOnce()) throw IOException("não foi possível gravar o cofre")
        if (writtenIsSane(resolver, document)) return
        if (!writeOnce()) throw IOException("a gravação saiu corrompida e a correção falhou")
        if (!writtenIsSane(resolver, document)) throw IOException("arquivo gravado não reabre; tente abrir outro cofre")
    }

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
            _state.update { it.copy(error = "Falha ao preparar o export: ${e.message}") }
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
                if (!written) throw IOException("não foi possível gravar o export")
                _state.update { it.copy(notice = "Export salvo. Sem cifra — use só para migrar.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Falha ao salvar o export: ${e.message}") }
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
