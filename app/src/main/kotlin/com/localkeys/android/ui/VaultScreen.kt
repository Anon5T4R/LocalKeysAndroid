package com.localkeys.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.totp.Totp
import com.localkeys.android.data.totp.TotpCode
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Vault
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    vault: Vault,
    biometricAvailable: Boolean,
    busy: Boolean,
    error: String?,
    notice: String?,
    onLock: () -> Unit,
    onSave: () -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit,
    onNoticeShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(context, com.localkeys.android.R.string.copy_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    var selected by remember { mutableStateOf<Item?>(null) }

    // Tick de 1 s para os códigos TOTP ao vivo.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick++
            delay(1000)
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice)
            onNoticeShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(com.localkeys.android.R.string.app_name)) },
                actions = {
                    TextButton(onClick = onSave, enabled = !busy) {
                        Text(stringResource(com.localkeys.android.R.string.vault_save))
                    }
                    TextButton(onClick = onLock, enabled = !busy) {
                        Text(stringResource(com.localkeys.android.R.string.vault_lock))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BiometricCard(
                    biometricAvailable = biometricAvailable,
                    onEnable = onEnableBiometric,
                    onDisable = onDisableBiometric,
                )
            }
            if (error != null) {
                item {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (vault.items.isEmpty()) {
                item {
                    Text(
                        text = stringResource(com.localkeys.android.R.string.vault_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(vault.items, key = { it.id }) { item ->
                ItemRow(item = item, nowSeconds = tick, onClick = { selected = item })
            }
        }
    }

    selected?.let { item ->
        ItemDetailDialog(item = item, onCopy = copy, onDismiss = { selected = null })
    }
}

@Composable
private fun BiometricCard(
    biometricAvailable: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(com.localkeys.android.R.string.biometric_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (biometricAvailable) com.localkeys.android.R.string.biometric_active
                    else com.localkeys.android.R.string.biometric_hint
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (biometricAvailable) {
                TextButton(onClick = onDisable) {
                    Text(stringResource(com.localkeys.android.R.string.biometric_disable))
                }
            } else {
                Button(onClick = onEnable) {
                    Text(stringResource(com.localkeys.android.R.string.biometric_enable))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(item: Item, nowSeconds: Long, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = item.name, style = MaterialTheme.typography.titleMedium)
            when (item.kind) {
                ItemKind.LOGIN -> {
                    val login = item.login
                    if (login != null && login.username.isNotBlank()) {
                        Text(
                            text = login.username,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (login?.totp?.isNotBlank() == true) {
                        Spacer(Modifier.height(8.dp))
                        TotpRow(secret = login.totp)
                    }
                }
                ItemKind.NOTE -> if (item.notes.isNotBlank()) {
                    Text(
                        text = item.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                ItemKind.CARD -> item.card?.let { card ->
                    Text(
                        text = card.brand.ifBlank { card.cardholder },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ItemKind.IDENTITY -> item.identity?.let { identity ->
                    Text(
                        text = listOf(identity.firstName, identity.lastName).filter { it.isNotBlank() }.joinToString(" "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Código TOTP atual + barra de tempo restante. Recomputa a cada recomposição
 * (o tick de 1 s do `VaultScreen` recompoe a linha), então o código vive.
 */
@Composable
private fun TotpRow(secret: String) {
    val code: TotpCode = Totp.now(secret)
    Column {
        Text(
            text = code.code,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (code.secondsRemaining / code.period.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ItemDetailDialog(
    item: Item,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (item.kind) {
                    ItemKind.LOGIN -> item.login?.let { login ->
                        FieldRow(stringResource(R.string.field_username), login.username, login.username, onCopy)
                        FieldRow(stringResource(R.string.field_password), login.password, login.password, onCopy)
                        login.uris.forEach { uri -> FieldRow(stringResource(R.string.field_uri), uri, uri, onCopy) }
                        if (login.totp.isNotBlank()) {
                            val code = Totp.now(login.totp)
                            FieldRow("TOTP", code.code, code.code, onCopy)
                        }
                    }
                    ItemKind.NOTE -> FieldRow(stringResource(R.string.field_note), item.notes, item.notes, onCopy)
                    ItemKind.CARD -> item.card?.let { card ->
                        FieldRow(stringResource(R.string.field_cardholder), card.cardholder, card.cardholder, onCopy)
                        FieldRow(stringResource(R.string.field_brand), card.brand, card.brand, onCopy)
                        FieldRow(stringResource(R.string.field_number), card.number, card.number, onCopy)
                        FieldRow(stringResource(R.string.field_exp), card.exp, card.exp, onCopy)
                        FieldRow(stringResource(R.string.field_code), card.code, card.code, onCopy)
                    }
                    ItemKind.IDENTITY -> item.identity?.let { identity ->
                        FieldRow(stringResource(R.string.field_first_name), identity.firstName, identity.firstName, onCopy)
                        FieldRow(stringResource(R.string.field_last_name), identity.lastName, identity.lastName, onCopy)
                        FieldRow(stringResource(R.string.field_email), identity.email, identity.email, onCopy)
                        FieldRow(stringResource(R.string.field_phone), identity.phone, identity.phone, onCopy)
                        FieldRow(stringResource(R.string.field_address), identity.address, identity.address, onCopy)
                    }
                }
                item.notes.takeIf { it.isNotBlank() && item.kind != ItemKind.NOTE }?.let { notes ->
                    FieldRow(stringResource(R.string.field_notes), notes, notes, onCopy)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        },
    )
}

@Composable
private fun FieldRow(label: String, value: String, copyable: String, onCopy: (String) -> Unit) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (copyable.isNotBlank()) {
                TextButton(onClick = { onCopy(copyable) }) {
                    Text(stringResource(R.string.dialog_copy))
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }
}
