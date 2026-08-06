package com.localkeys.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.totp.Totp
import com.localkeys.android.data.totp.TotpCode
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Vault
import com.localkeys.android.ui.VaultViewModel.PendingImport
import kotlinx.coroutines.delay

private val FavoriteAmber = Color(0xFFFFB300)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    vault: Vault,
    biometricAvailable: Boolean,
    busy: Boolean,
    error: String?,
    notice: String?,
    pendingImport: PendingImport?,
    onLock: () -> Unit,
    onSave: () -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit,
    onPickImport: () -> Unit,
    onImportPassword: (String) -> Unit,
    onDismissImport: () -> Unit,
    onSaveItem: (Item) -> Unit,
    onDeleteItem: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onNoticeShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(context, R.string.copy_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    var selected by remember { mutableStateOf<Item?>(null) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Item?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf<Folder?>(null) }
    var foldersOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }

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

    // Busca + pasta selecionada; favoritos primeiro, depois nome.
    val visibleItems = remember(vault.items, query, folderFilter) {
        val q = query.trim().lowercase()
        val filtered = vault.items.filter { item ->
            val inFolder = when (folderFilter) {
                null -> true
                "" -> item.folderId == null
                else -> item.folderId == folderFilter
            }
            if (!inFolder) return@filter false
            if (q.isEmpty()) {
                true
            } else {
                item.name.lowercase().contains(q) ||
                    item.login?.username?.lowercase()?.contains(q) == true ||
                    item.notes.lowercase().contains(q)
            }
        }
        filtered.sortedWith(compareByDescending<Item> { it.favorite }.thenBy { it.name.lowercase() })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onPickImport, enabled = !busy) {
                        Text(stringResource(R.string.vault_import))
                    }
                    TextButton(onClick = onSave, enabled = !busy) {
                        Text(stringResource(R.string.vault_save))
                    }
                    TextButton(onClick = onLock, enabled = !busy) {
                        Text(stringResource(R.string.vault_lock))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editing = null
                    editorOpen = true
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vault_add))
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = folderFilter == null,
                    onClick = { folderFilter = null },
                    label = { Text(stringResource(R.string.folder_filter_all)) },
                )
                FilterChip(
                    selected = folderFilter == "",
                    onClick = { folderFilter = "" },
                    label = { Text(stringResource(R.string.folder_sem)) },
                )
                vault.folders.forEach { folder ->
                    FilterChip(
                        selected = folderFilter == folder.id,
                        onClick = { folderFilter = folder.id },
                        label = { Text(folder.name) },
                    )
                }
                IconButton(onClick = { foldersOpen = true }, enabled = !busy) {
                    Icon(
                        Icons.Filled.CreateNewFolder,
                        contentDescription = stringResource(R.string.folder_manage),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
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
                if (visibleItems.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.vault_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(visibleItems, key = { it.id }) { item ->
                    ItemRow(
                        item = item,
                        nowSeconds = tick,
                        onClick = { selected = item },
                        onToggleFavorite = { onToggleFavorite(item.id) },
                    )
                }
            }
        }
    }

    selected?.let { item ->
        ItemDetailDialog(
            item = item,
            onCopy = copy,
            onEdit = {
                editing = item
                editorOpen = true
            },
            onDelete = { confirmDelete = item },
            onToggleFavorite = { onToggleFavorite(item.id) },
            onDismiss = { selected = null },
        )
    }

    if (editorOpen) {
        ItemEditorDialog(
            initial = editing,
            folders = vault.folders,
            busy = busy,
            onSave = { item ->
                onSaveItem(item)
                editorOpen = false
                editing = null
            },
            onDismiss = {
                editorOpen = false
                editing = null
            },
        )
    }

    if (foldersOpen) {
        FolderManagerDialog(
            folders = vault.folders,
            busy = busy,
            onAdd = onAddFolder,
            onRename = onRenameFolder,
            onDelete = { folder ->
                confirmDeleteFolder = folder
            },
            onDismiss = { foldersOpen = false },
        )
    }

    confirmDeleteFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFolder = null },
            title = { Text(stringResource(R.string.folder_delete)) },
            text = { Text(stringResource(R.string.folder_delete_message, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFolder(folder.id)
                        confirmDeleteFolder = null
                        if (folderFilter == folder.id) folderFilter = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.folder_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFolder = null }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            },
        )
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, item.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteItem(item.id)
                        confirmDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.item_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.editor_cancel))
                }
            },
        )
    }

    pendingImport?.let { pending ->
        ImportDialog(
            pending = pending,
            busy = busy,
            error = error,
            onConfirm = onImportPassword,
            onDismiss = onDismissImport,
        )
    }

}

/**
 * Export cifrado do Bitwarden: pede a senha do próprio Bitwarden (não a do
 * cofre) para decifrar antes de importar.
 */
@Composable
private fun ImportDialog(
    pending: PendingImport,
    busy: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.import_file, pending.fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.import_encrypted_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.import_password_label)) },
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { show = !show }) {
                            Text(
                                stringResource(
                                    if (show) R.string.unlock_hide
                                    else R.string.unlock_show
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotBlank() && !busy) {
                Text(stringResource(R.string.import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.import_cancel))
            }
        },
    )
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
                text = stringResource(R.string.biometric_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (biometricAvailable) R.string.biometric_active
                    else R.string.biometric_hint
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (biometricAvailable) {
                TextButton(onClick = onDisable) {
                    Text(stringResource(R.string.biometric_disable))
                }
            } else {
                Button(onClick = onEnable) {
                    Text(stringResource(R.string.biometric_enable))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(item: Item, nowSeconds: Long, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = itemIcon(item.kind),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
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
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (item.favorite) R.string.item_unfavorite else R.string.item_favorite
                    ),
                    tint = if (item.favorite) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun itemIcon(kind: ItemKind): ImageVector = when (kind) {
    ItemKind.LOGIN -> Icons.Outlined.VpnKey
    ItemKind.NOTE -> Icons.Outlined.Description
    ItemKind.CARD -> Icons.Outlined.CreditCard
    ItemKind.IDENTITY -> Icons.Outlined.Person
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = item.name, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (item.favorite) R.string.item_unfavorite else R.string.item_favorite
                        ),
                        tint = if (item.favorite) FavoriteAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
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
                item.customFields?.forEach { field ->
                    FieldRow(field.name, field.value, field.value, onCopy)
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.item_edit))
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.item_delete))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_close))
                }
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

/** Gerencia pastas: cria, renomeia e exclui. A exclusão pede confirmação no pai. */
@Composable
private fun FolderManagerDialog(
    folders: List<Folder>,
    busy: Boolean,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (Folder) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Folder?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.folder_name_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onAdd(newName)
                            newName = ""
                        },
                        enabled = newName.isNotBlank() && !busy,
                    ) {
                        Text(stringResource(R.string.folder_add))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (folders.isEmpty()) {
                    Text(
                        text = stringResource(R.string.folder_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    folders.forEach { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { renaming = folder }, enabled = !busy) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.folder_rename))
                            }
                            IconButton(onClick = { onDelete(folder) }, enabled = !busy) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.folder_delete))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        },
    )

    renaming?.let { folder ->
        RenameFolderDialog(
            folder = folder,
            onConfirm = { name ->
                onRename(folder.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun RenameFolderDialog(
    folder: Folder,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.folder_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_cancel))
            }
        },
    )
}
