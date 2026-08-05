package com.localkeys.android.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Preferências locais (DataStore) — só configuração, nunca segredo em claro:
 *
 *  - `vault_uri`: URI de documento (SAF) do último cofre aberto/criado. O
 *    arquivo em si fica no armazenamento do usuário; aqui só a referência.
 *  - `wrapped_key_hex`: a chave derivada do vault (32 bytes) cifrada com a
 *    chave AES/GCM autenticada por biometria do Android Keystore (formato
 *    `iv || ct`, em hex). Sem a biometria do dono não é decifrável.
 *  - `biometric_enabled`: opt-in de desbloqueio rápido por biometria.
 */
private val Context.dataStore by preferencesDataStore(name = "localkeys")

class SettingsStore(private val context: Context) {

    val vaultUri: Flow<String?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.VAULT_URI] }

    /** Chave do vault (32 bytes) cifrada + IV, em hex — ou null se não ativada. */
    val wrappedKeyHex: Flow<String?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.WRAPPED_KEY_HEX] }

    val biometricEnabled: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.BIOMETRIC_ENABLED] ?: false }

    suspend fun setVaultUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(Keys.VAULT_URI) else prefs[Keys.VAULT_URI] = uri
        }
    }

    suspend fun setWrappedKeyHex(hex: String?) {
        context.dataStore.edit { prefs ->
            if (hex == null) prefs.remove(Keys.WRAPPED_KEY_HEX) else prefs[Keys.WRAPPED_KEY_HEX] = hex
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BIOMETRIC_ENABLED] = enabled }
    }

    private object Keys {
        val VAULT_URI = stringPreferencesKey("vault_uri")
        val WRAPPED_KEY_HEX = stringPreferencesKey("wrapped_key_hex")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
    }
}
