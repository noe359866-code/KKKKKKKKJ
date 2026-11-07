package com.tanasi.streamflix.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftTest {
    private val saved = SettingsDraft(
        autonomous = true,
        metadataCatalogs = true,
        baseUrl = "",
        autoCheckUpdates = true,
    )

    @Test fun `detecta cada cambio pendiente y permite deshacerlo`() {
        assertFalse(saved.hasChangesFrom(saved.copy()))
        assertTrue(saved.copy(autonomous = false).hasChangesFrom(saved))
        assertTrue(saved.copy(metadataCatalogs = false).hasChangesFrom(saved))
        assertTrue(saved.copy(baseUrl = "nas:7000").hasChangesFrom(saved))
        assertTrue(saved.copy(autoCheckUpdates = false).hasChangesFrom(saved))
    }

    @Test fun `servidor necesita url valida y el modo autonomo no`() {
        assertTrue(saved.isValid)
        assertFalse(saved.copy(autonomous = false).isValid)
        assertFalse(saved.copy(autonomous = false, baseUrl = "ftp://nas").isValid)
        assertTrue(saved.copy(autonomous = false, baseUrl = "nas:7000").isValid)
    }

    @Test fun `guardar espera el addon o su url pendiente`() {
        assertTrue(saved.canSave(addonInput = "  ", installing = false))
        assertFalse(saved.canSave(addonInput = "https://addon/manifest.json", installing = false))
        assertFalse(saved.canSave(addonInput = "", installing = true))
        assertFalse(saved.copy(autonomous = false).canSave(addonInput = "", installing = false))
    }

    @Test fun `normaliza el servidor al guardar`() {
        assertEquals("http://nas:7000", saved.copy(baseUrl = " nas:7000/ ").normalizedBaseUrl())
        assertEquals("", saved.copy(baseUrl = "  ").normalizedBaseUrl())
    }
}
