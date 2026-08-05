package com.localkeys.android.ui

/**
 * O que o BiometricPrompt deve fazer ao autenticar:
 *  - [Wrap]: ativar o desbloqueio biométrico (cifra a chave derivada do vault).
 *  - [Unlock]: abrir o cofre sem senha (decifra a chave guardada).
 */
enum class BiometricRequest {
    Wrap,
    Unlock,
}
