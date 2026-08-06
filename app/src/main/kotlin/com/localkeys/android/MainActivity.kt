package com.localkeys.android

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.localkeys.android.ui.BiometricRequest
import com.localkeys.android.ui.VaultViewModel
import com.localkeys.android.ui.theme.LocalKeysTheme
import javax.crypto.Cipher

class MainActivity : FragmentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    /** Senha nova digitada na tela de criar — guardada só até o SAF responder. */
    private var pendingCreatePassword: String? = null

    private val openVaultLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onVaultUriChosen(uri)
        }

    private val createVaultLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val password = pendingCreatePassword
            pendingCreatePassword = null
            if (uri != null && password != null) {
                viewModel.onCreatedDocument(uri, password)
            }
        }

    private val importLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.onImportUriChosen(uri)
        }

    private val exportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            if (uri != null) viewModel.onExportUriChosen(uri)
        }

    /** "json" ou "csv": prepara o conteúdo e abre o SAF para escolher o destino. */
    private fun startExport(format: String) {
        val name = if (format == "csv") "localkeys-export.csv" else "localkeys-export.json"
        viewModel.exportRequested(format)
        exportLauncher.launch(name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // API 33+: a notificação de feedback do autofill precisa de permissão.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            LocalKeysTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LocalKeysApp(
                        viewModel = viewModel,
                        onPickDocument = {
                            openVaultLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        onCreateDocument = { password ->
                            pendingCreatePassword = password
                            createVaultLauncher.launch("localkeys.tkeys")
                        },
                        onPickImport = {
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        onExport = ::startExport,
                        onRequestBiometric = ::launchBiometric,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    /**
     * Sobe o BiometricPrompt com o Cipher do cofre do SO. O `doFinal` (wrap/
     * unwrap) roda no ViewModel só depois de `onAuthenticationSucceeded`.
     */
    private fun launchBiometric(request: BiometricRequest) {
        val cipher: Cipher = try {
            when (request) {
                BiometricRequest.Wrap -> viewModel.newWrapCipher()
                BiometricRequest.Unlock -> viewModel.biometricDecryptCipher() ?: return
            }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.biometric_unavailable, e.message),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_prompt_cancel))
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    when (request) {
                        BiometricRequest.Wrap -> viewModel.enableBiometricWithCipher(cipher)
                        BiometricRequest.Unlock -> viewModel.unlockWithBiometricCipher(cipher)
                    }
                }

                override fun onAuthenticationFailed() {
                    // Sensor não reconheceu; o usuário pode tentar de novo.
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelou ou biometria indisponível → segue pelo caminho da senha.
                }
            },
        )
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
