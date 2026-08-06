package com.localkeys.android.data.generator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Gerador de senhas — mesmas garantias do `generator.rs` do desktop. */
class PasswordGeneratorTest {

    private val lower = "abcdefghijklmnopqrstuvwxyz".toSet()
    private val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toSet()
    private val digits = "0123456789".toSet()
    private val symbols = "!@#$%^&*()-_=+[]{};:,.?".toSet()

    @Test
    fun senha_respeita_comprimento_e_classes() {
        val pw = PasswordGenerator.generate(
            PasswordGenerator.Options(length = 24, lowercase = true, uppercase = true, digits = true, symbols = true),
        )
        assertEquals(24, pw.length)
        assertTrue(pw.any { it in lower })
        assertTrue(pw.any { it in upper })
        assertTrue(pw.any { it in digits })
        assertTrue(pw.any { it in symbols })
    }

    @Test
    fun senha_sem_classe_falha() {
        try {
            PasswordGenerator.generate(
                PasswordGenerator.Options(length = 10, lowercase = false, uppercase = false, digits = false, symbols = false),
            )
            fail("deveria lançar sem nenhuma classe")
        } catch (e: IllegalArgumentException) {
            // esperado
        }
    }

    @Test
    fun senhas_geradas_nao_repetem() {
        val a = PasswordGenerator.generate()
        val b = PasswordGenerator.generate()
        assertTrue("duas senhas de 20 chars não deveriam colidir", a != b)
    }

    @Test
    fun comprimento_e_clampeado() {
        assertEquals(4, PasswordGenerator.generate(PasswordGenerator.Options(length = 1)).length)
        assertEquals(128, PasswordGenerator.generate(PasswordGenerator.Options(length = 999)).length)
    }

    @Test
    fun comprimento_menor_que_classes_falha() {
        try {
            PasswordGenerator.generate(
                PasswordGenerator.Options(length = 3, lowercase = true, uppercase = true, digits = true),
            )
            fail("deveria lançar")
        } catch (e: IllegalArgumentException) {
            // esperado
        }
    }
}
