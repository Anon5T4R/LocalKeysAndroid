package com.localkeys.android.data.import

import org.junit.Assert.assertEquals
import org.junit.Test

/** Detecção de formato e helpers compartilhados dos importadores. */
class ImportersTest {

    @Test
    fun detecta_formato_por_extensao_e_conteudo() {
        assertEquals(ImportFormat.KDBX, Importers.detectFormat("x.kdbx", null))
        assertEquals(ImportFormat.BITWARDEN_JSON, Importers.detectFormat("x.json", null))
        assertEquals(ImportFormat.CSV, Importers.detectFormat("x.csv", null))
        assertEquals(ImportFormat.BITWARDEN_JSON, Importers.detectFormat("blob", """{"items":[]}"""))
        assertEquals(ImportFormat.CSV, Importers.detectFormat("blob", "a,b,c"))
        assertEquals(
            ImportFormat.BITWARDEN_ENCRYPTED,
            Importers.detectFormat("blob", """{"encrypted":true,"data":"2.a.b.c"}"""),
        )
    }

    @Test
    fun extrai_segredo_de_otpauth() {
        assertEquals("JBSWY3DP", Importers.extractSecret("otpauth://totp/x?secret=JBSWY3DP&period=30"))
        assertEquals("JBSWY3DP", Importers.extractSecret("  JBSWY3DP  "))
        assertEquals("CHAVE", Importers.extractSecret("key=CHAVE&algo=sha1"))
    }
}
