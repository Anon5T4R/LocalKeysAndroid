package com.localkeys.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.localkeys.android.data.biometric.BiometricVaultKey
import com.localkeys.android.data.crypto.Hex
import com.localkeys.android.data.crypto.TkeysCrypto
import com.localkeys.android.data.crypto.TkeysError
import com.localkeys.android.data.store.SettingsStore
import com.localkeys.android.data.vault.Vault
import com.localkeys.android.data.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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

    data class UiState(
        val screen: Screen = Screen.Unlock,
        val vault: Vault? = null,
        val vaultUri: String? = null,
        val biometricAvailable: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
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
                val written = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    runCatching {
                        resolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    resolver.openOutputStream(uri)?.use { it.write(file) } != null
                }
                if (!written) throw IOException("não foi possível escrever o cofre")
                settings.setVaultUri(uri.toString())
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
                val ok = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openOutputStream(Uri.parse(uri))?.use { it.write(file) } != null
                }
                if (!ok) throw IOException("não foi possível salvar")
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

    // ── Travar / limpar ──────────────────────────────────────────────────

    fun lock() {
        repository.lock()
        _state.update { it.copy(vault = null, screen = Screen.Unlock) }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
