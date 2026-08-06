package com.localkeys.android.data.generator

import java.security.SecureRandom

/**
 * Gerador de senhas — port do `generator.rs` do desktop, sem o medidor de força.
 * Toda aleatoriedade vem do `SecureRandom` (CSPRNG do SO), nunca de `Random`.
 */
object PasswordGenerator {

    private val rng = SecureRandom()

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?"

    data class Options(
        val length: Int = 20,
        val lowercase: Boolean = true,
        val uppercase: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
    )

    /**
     * Gera uma senha respeitando as classes escolhidas, garantindo ao menos um
     * caractere de cada classe ativa (os obrigatórios também são sorteados e a
     * ordem final é embaralhada — Fisher-Yates).
     */
    fun generate(opts: Options = Options()): String {
        val classes = buildList {
            if (opts.lowercase) add(LOWER)
            if (opts.uppercase) add(UPPER)
            if (opts.digits) add(DIGITS)
            if (opts.symbols) add(SYMBOLS)
        }
        require(classes.isNotEmpty()) { "selecione ao menos uma classe de caractere" }
        val length = opts.length.coerceIn(4, 128)
        require(length >= classes.size) { "comprimento menor que o número de classes exigidas" }

        val pool = classes.joinToString("")
        val chars = CharArray(length)

        // Um obrigatório por classe ativa...
        classes.forEach { chars[classes.indexOf(it)] = it[rng.nextInt(it.length)] }
        // ...o resto do pool geral.
        for (i in classes.size until length) {
            chars[i] = pool[rng.nextInt(pool.length)]
        }
        // Embaralha (Fisher-Yates) para não fixar os obrigatórios no início.
        for (i in length - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars)
    }
}
