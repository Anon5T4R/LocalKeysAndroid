package com.localkeys.android.ui

import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.localkeys.android.R
import com.localkeys.android.data.generator.PasswordGenerator
import com.localkeys.android.data.vault.Attachment
import com.localkeys.android.data.vault.Card
import com.localkeys.android.data.vault.CustomField
import com.localkeys.android.data.vault.Folder
import com.localkeys.android.data.vault.Identity
import com.localkeys.android.data.vault.Item
import com.localkeys.android.data.vault.ItemKind
import com.localkeys.android.data.vault.Login
import com.localkeys.android.data.vault.MAX_ATTACHMENT_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Cria ou edita um item do vault. [initial] null = novo (com seletor de tipo);
 * preenchido = edição (tipo fixo, preserva id/createdAt/favorito).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorDialog(
    initial: Item?,
    folders: List<Folder>,
    busy: Boolean,
    onSave: (Item) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(initial?.kind ?: ItemKind.LOGIN) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var favorite by remember { mutableStateOf(initial?.favorite ?: false) }
    var folderId by remember { mutableStateOf(initial?.folderId) }
    var showPassword by remember { mutableStateOf(false) }

    var username by remember { mutableStateOf(initial?.login?.username ?: "") }
    var password by remember { mutableStateOf(initial?.login?.password ?: "") }
    var uri by remember { mutableStateOf(initial?.login?.uris?.joinToString("\n") ?: "") }
    var totp by remember { mutableStateOf(initial?.login?.totp ?: "") }

    var cardholder by remember { mutableStateOf(initial?.card?.cardholder ?: "") }
    var brand by remember { mutableStateOf(initial?.card?.brand ?: "") }
    var number by remember { mutableStateOf(initial?.card?.number ?: "") }
    var exp by remember { mutableStateOf(initial?.card?.exp ?: "") }
    var code by remember { mutableStateOf(initial?.card?.code ?: "") }

    var firstName by remember { mutableStateOf(initial?.identity?.firstName ?: "") }
    var lastName by remember { mutableStateOf(initial?.identity?.lastName ?: "") }
    var email by remember { mutableStateOf(initial?.identity?.email ?: "") }
    var phone by remember { mutableStateOf(initial?.identity?.phone ?: "") }
    var address by remember { mutableStateOf(initial?.identity?.address ?: "") }

    var customFields by remember {
        mutableStateOf(initial?.customFields?.toMutableList() ?: mutableListOf<CustomField>())
    }

    var attachments by remember {
        mutableStateOf(initial?.attachments?.toMutableList() ?: mutableListOf<Attachment>())
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (name, bytes) = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val displayName = resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: "anexo"
                val data = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext (displayName to ByteArray(0))
                displayName to data
            }
            if (bytes.isEmpty()) {
                Toast.makeText(context, R.string.attach_read_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (bytes.size > MAX_ATTACHMENT_BYTES) {
                Toast.makeText(context, R.string.attach_too_large, Toast.LENGTH_SHORT).show()
                return@launch
            }
            attachments = (attachments + Attachment(
                id = UUID.randomUUID().toString(),
                name = name,
                size = bytes.size.toLong(),
                mime = "",
                dataB64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            )).toMutableList()
        }
    }

    fun removeAttachment(id: String) {
        attachments = attachments.filterNot { it.id == id }.toMutableList()
    }

    fun addField() {
        customFields = (customFields + CustomField(UUID.randomUUID().toString(), "", "", false)).toMutableList()
    }

    fun updateField(index: Int, transform: (CustomField) -> CustomField) {
        customFields = customFields.toMutableList().apply { set(index, transform(get(index))) }
    }

    fun removeField(index: Int) {
        customFields = customFields.toMutableList().apply { removeAt(index) }
    }

    val canSave = name.isNotBlank() && !busy

    fun buildItem(): Item {
        val now = System.currentTimeMillis()
        val uris = uri.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        return Item(
            id = initial?.id ?: UUID.randomUUID().toString(),
            kind = kind,
            name = name.trim(),
            favorite = favorite,
            folderId = folderId,
            notes = notes.trim(),
            createdAt = initial?.createdAt ?: now,
            updatedAt = now,
            deletedAt = initial?.deletedAt,
            login = if (kind == ItemKind.LOGIN) {
                Login(
                    username = username.trim(),
                    password = password,
                    uris = uris,
                    totp = totp.trim(),
                )
            } else {
                initial?.login
            },
            card = if (kind == ItemKind.CARD) {
                Card(
                    cardholder = cardholder.trim(),
                    brand = brand.trim(),
                    number = number.trim(),
                    exp = exp.trim(),
                    code = code.trim(),
                )
            } else {
                initial?.card
            },
            identity = if (kind == ItemKind.IDENTITY) {
                Identity(
                    firstName = firstName.trim(),
                    lastName = lastName.trim(),
                    email = email.trim(),
                    phone = phone.trim(),
                    address = address.trim(),
                )
            } else {
                initial?.identity
            },
            passwordHistory = initial?.passwordHistory,
            customFields = customFields.takeIf { it.isNotEmpty() },
            attachments = attachments.takeIf { it.isNotEmpty() },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.editor_new_title else R.string.editor_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (initial == null) {
                    Text(
                        text = stringResource(R.string.editor_kind_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ItemKind.entries.forEach { k ->
                            FilterChip(
                                selected = kind == k,
                                onClick = { kind = k },
                                label = { Text(kindLabel(k)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.editor_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.field_folder),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = folderId == null,
                        onClick = { folderId = null },
                        label = { Text(stringResource(R.string.folder_sem)) },
                    )
                    folders.forEach { folder ->
                        FilterChip(
                            selected = folderId == folder.id,
                            onClick = { folderId = folder.id },
                            label = { Text(folder.name) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                when (kind) {
                    ItemKind.LOGIN -> {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.field_username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.field_password)) },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = {
                                        password = PasswordGenerator.generate()
                                        showPassword = true
                                    }) {
                                        Icon(
                                            Icons.Filled.AutoFixHigh,
                                            contentDescription = stringResource(R.string.editor_generate),
                                        )
                                    }
                                    TextButton(onClick = { showPassword = !showPassword }) {
                                        Text(
                                            stringResource(
                                                if (showPassword) R.string.unlock_hide
                                                else R.string.unlock_show
                                            ),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = uri,
                            onValueChange = { uri = it },
                            label = { Text(stringResource(R.string.field_uri)) },
                            minLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = totp,
                            onValueChange = { totp = it },
                            label = { Text(stringResource(R.string.field_totp)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ItemKind.NOTE -> Unit
                    ItemKind.CARD -> {
                        OutlinedTextField(
                            value = cardholder,
                            onValueChange = { cardholder = it },
                            label = { Text(stringResource(R.string.field_cardholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = brand,
                            onValueChange = { brand = it },
                            label = { Text(stringResource(R.string.field_brand)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = number,
                            onValueChange = { number = it },
                            label = { Text(stringResource(R.string.field_number)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = exp,
                            onValueChange = { exp = it },
                            label = { Text(stringResource(R.string.field_exp)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text(stringResource(R.string.field_code)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ItemKind.IDENTITY -> {
                        OutlinedTextField(
                            value = firstName,
                            onValueChange = { firstName = it },
                            label = { Text(stringResource(R.string.field_first_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = lastName,
                            onValueChange = { lastName = it },
                            label = { Text(stringResource(R.string.field_last_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text(stringResource(R.string.field_email)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text(stringResource(R.string.field_phone)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text(stringResource(R.string.field_address)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.field_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.custom_fields),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                customFields.forEachIndexed { index, field ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = field.name,
                                onValueChange = { value -> updateField(index) { it.copy(name = value) } },
                                label = { Text(stringResource(R.string.custom_field_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = field.value,
                                onValueChange = { value -> updateField(index) { it.copy(value = value) } },
                                label = { Text(stringResource(R.string.custom_field_value)) },
                                singleLine = true,
                                visualTransformation = if (field.hidden) PasswordVisualTransformation() else VisualTransformation.None,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = field.hidden,
                                    onCheckedChange = { checked -> updateField(index) { it.copy(hidden = checked) } },
                                )
                                Text(
                                    text = stringResource(R.string.custom_field_hidden),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            IconButton(onClick = { removeField(index) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.custom_field_remove),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = { addField() }, enabled = !busy) {
                    Text(stringResource(R.string.custom_field_add))
                }

                Text(
                    text = stringResource(R.string.attach_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                attachments.forEach { att ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = att.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.attach_size_kb, (att.size / 1024).coerceAtLeast(1)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { removeAttachment(att.id) }, enabled = !busy) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.attach_remove),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Text(stringResource(R.string.attach_add))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = favorite, onCheckedChange = { favorite = it })
                    Text(
                        text = stringResource(R.string.editor_favorite),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(buildItem()) }, enabled = canSave) {
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

@Composable
private fun kindLabel(kind: ItemKind): String = stringResource(
    when (kind) {
        ItemKind.LOGIN -> R.string.kind_login
        ItemKind.NOTE -> R.string.kind_note
        ItemKind.CARD -> R.string.kind_card
        ItemKind.IDENTITY -> R.string.kind_identity
    }
)
