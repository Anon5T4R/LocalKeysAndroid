package com.localkeys.android.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ponte do autofill: só persiste com o handler registrado (cofre aberto). */
class AutofillSaveBridgeTest {

    @Test
    fun com_cofre_destrancado_salva_e_despacha_o_pedido() {
        var captured: AutofillSaveBridge.LoginSaveRequest? = null
        AutofillSaveBridge.register { captured = it }

        assertTrue(AutofillSaveBridge.canSave)
        val ok = AutofillSaveBridge.save(
            AutofillSaveBridge.LoginSaveRequest("ana", "segredo", listOf("https://exemplo.com")),
        )

        assertTrue(ok)
        assertEquals("ana", captured?.username)
        assertEquals("segredo", captured?.password)
        assertEquals(listOf("https://exemplo.com"), captured?.uris)
    }

    @Test
    fun com_cofre_trancado_recusa_sem_chamar_handler() {
        var calls = 0
        AutofillSaveBridge.register { calls++ }
        AutofillSaveBridge.unregister()

        assertFalse(AutofillSaveBridge.canSave)
        val ok = AutofillSaveBridge.save(
            AutofillSaveBridge.LoginSaveRequest("ana", "segredo", emptyList()),
        )

        assertFalse(ok)
        assertEquals(0, calls)
    }
}
