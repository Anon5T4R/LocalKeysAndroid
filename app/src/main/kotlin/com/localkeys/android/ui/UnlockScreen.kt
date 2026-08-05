package com.localkeys.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.localkeys.android.R

@Composable
fun UnlockScreen(
    state: VaultViewModel.UiState,
    onPickVault: () -> Unit,
    onCreateVault: () -> Unit,
    onUnlock: (String) -> Unit,
    onBiometricUnlock: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.height(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.unlock_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        if (state.vaultUri == null) {
            Text(
                text = stringResource(R.string.unlock_no_vault),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onPickVault,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.unlock_open))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onCreateVault,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.unlock_create))
            }
        } else {
            if (state.biometricAvailable) {
                Button(
                    onClick = onBiometricUnlock,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.unlock_biometric))
                }
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    onErrorShown()
                },
                label = { Text(stringResource(R.string.unlock_password)) },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(if (showPassword) R.string.unlock_hide else R.string.unlock_show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onUnlock(password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy && password.isNotBlank(),
            ) {
                Text(stringResource(R.string.unlock_button))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onPickVault, enabled = !state.busy) {
                Text(stringResource(R.string.unlock_change_vault))
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
