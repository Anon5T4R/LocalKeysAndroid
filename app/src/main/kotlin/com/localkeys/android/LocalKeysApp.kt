package com.localkeys.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.localkeys.android.ui.BiometricRequest
import com.localkeys.android.ui.CreateScreen
import com.localkeys.android.ui.UnlockScreen
import com.localkeys.android.ui.VaultScreen
import com.localkeys.android.ui.VaultViewModel

/**
 * Raiz da navegação:
 *  - Sem cofre ainda: [UnlockScreen] (abrir/criar) + [CreateScreen] (senha nova).
 *  - Vault destrancado (VM): [VaultScreen] com a lista de itens e TOTP ao vivo.
 *
 * Os gatilhos de SAF (abrir/criar documento) e de BiometricPrompt vivem na
 * [MainActivity] e chegam como lambdas.
 */
@Composable
fun LocalKeysApp(
    viewModel: VaultViewModel,
    onPickDocument: () -> Unit,
    onCreateDocument: (String) -> Unit,
    onPickImport: () -> Unit,
    onRequestBiometric: (BiometricRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    var showCreate by rememberSaveable { mutableStateOf(false) }

    when (state.screen) {
        VaultViewModel.Screen.Vault -> VaultScreen(
            vault = state.vault ?: return,
            biometricAvailable = state.biometricAvailable,
            busy = state.busy,
            error = state.error,
            notice = state.notice,
            pendingImport = state.pendingImport,
            onSave = viewModel::save,
            onLock = viewModel::lock,
            onEnableBiometric = { onRequestBiometric(BiometricRequest.Wrap) },
            onDisableBiometric = viewModel::disableBiometric,
            onPickImport = onPickImport,
            onImportPassword = viewModel::onImportPassword,
            onDismissImport = viewModel::dismissImport,
            onSaveItem = viewModel::saveItem,
            onDeleteItem = viewModel::deleteItem,
            onToggleFavorite = viewModel::toggleFavorite,
            onAddFolder = viewModel::addFolder,
            onRenameFolder = viewModel::renameFolder,
            onDeleteFolder = viewModel::deleteFolder,
            onNoticeShown = viewModel::consumeNotice,
            modifier = modifier,
        )

        VaultViewModel.Screen.Unlock -> if (showCreate) {
            CreateScreen(
                busy = state.busy,
                error = state.error,
                onBack = {
                    showCreate = false
                    viewModel.clearError()
                },
                onCreate = { password ->
                    viewModel.clearError()
                    onCreateDocument(password)
                },
                modifier = modifier,
            )
        } else {
            UnlockScreen(
                state = state,
                onPickVault = onPickDocument,
                onCreateVault = { showCreate = true },
                onUnlock = viewModel::unlock,
                onBiometricUnlock = { onRequestBiometric(BiometricRequest.Unlock) },
                onErrorShown = viewModel::clearError,
                modifier = modifier,
            )
        }
    }
}
