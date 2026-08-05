package com.localkeys.android.data.import

/**
 * Erro de importação exibível ao usuário (mensagens em pt-BR, mesmo padrão do
 * `TkeysError` do núcleo). Import falha sem tocar no cofre — pior caso, nada
 * é gravado.
 */
class ImportError(message: String) : Exception(message)
