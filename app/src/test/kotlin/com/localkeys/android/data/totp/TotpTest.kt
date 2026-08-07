package com.localkeys.android.data.totp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpTest {

    // ── Base32 (RFC 4648) ─────────────────────────────────────────────────

    @Test
    fun base32_decodifica_secret_conhecido() {
        // "JBSWY3DPEHPK3PXP" = bytes de "Hello!\xDE\xAD\xBE\xEF"
        val expected = byteArrayOf(
            'H'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
            '!'.code.toByte(), 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )
        assertArrayEquals(expected, Base32.decode("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun base32_ignora_padding_e_case() {
        val expected = Base32.decode("JBSWY3DPEHPK3PXP")
        assertArrayEquals(expected, Base32.decode("jbswy3dpehpk3pxp"))
        assertArrayEquals(expected, Base32.decode("JBSWY3DPEHPK3PXP===="))
    }

    @Test
    fun base32_invalida_falha() {
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("!!1") }
    }

    // ── Sanitização ───────────────────────────────────────────────────────

    @Test
    fun sanitize_remove_espacos_e_hifens() {
        assertEquals("JBSWY3DPEHPK3PXP", Totp.sanitize("JBSW Y3DP EHPK 3PXP"))
        assertEquals("JBSWY3DPEHPK3PXP", Totp.sanitize("  jbswy3dp-ehpk3pxp  "))
    }

    @Test
    fun chave_vazia_falha() {
        assertThrows(IllegalArgumentException::class.java) { Totp.generate("   ", 0) }
    }

    // ── Vetores do RFC 6238 (Apêndice B), SHA-1, seed ASCII ──────────────

    @Test
    fun rfc6238_vetores_sha1() {
        // seed ASCII "12345678901234567890" em base32.
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        assertEquals("94287082", Totp.generate(secret, 59, digits = 8))
        assertEquals("07081804", Totp.generate(secret, 1_111_111_109, digits = 8))
        assertEquals("14050471", Totp.generate(secret, 1_111_111_111, digits = 8))
        assertEquals("89005924", Totp.generate(secret, 1_234_567_890, digits = 8))
        assertEquals("69279037", Totp.generate(secret, 2_000_000_000, digits = 8))
    }

    // ── Geração padrão (6 dígitos, passo 30 s) ───────────────────────────

    @Test
    fun gera_6_digitos() {
        val code = Totp.generate("JBSWY3DPEHPK3PXP", 1000)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun preserva_zeros_a_esquerda() {
        // Primeiro dígito do RFC 94287082 → com 6 dígitos pode vir com zero à esquerda;
        // aqui só garantimos o padding: valor curto completa com '0'.
        val code = Totp.generate("JBSWY3DPEHPK3PXP", 59)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun agora_devolve_codigo_e_tempo_restante() {
        val now = Totp.now("JBSWY3DPEHPK3PXP")
        assertEquals(6, now.code.length)
        assertEquals(30L, now.period)
        assertTrue(now.secondsRemaining in 1..30)
    }

    @Test
    fun base32_invalida_falha_no_generate() {
        assertThrows(IllegalArgumentException::class.java) {
            Totp.generate("!!! não é base32 !!!", 1000)
        }
    }

    // ── at() (função pura do relógio compartilhado da UI) ────────────────

    @Test
    fun at_deriva_codigo_e_tempo_restante_do_instante_dado() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val code = Totp.at(secret, 59)
        // Mesmo código do vetor RFC 6238 (59 s), truncado para 6 dígitos.
        assertEquals("287082", code.code)
        assertEquals(30L, code.period)
        // 59 s = 1 passo completo + 29 s do passo corrente → falta 1 s para virar.
        assertEquals(1L, code.secondsRemaining)
    }

    @Test
    fun at_no_inicio_do_passo_tem_30s_restantes() {
        val code = Totp.at("JBSWY3DPEHPK3PXP", 90) // múltiplo exato de 30
        assertEquals(30L, code.secondsRemaining)
    }
}
