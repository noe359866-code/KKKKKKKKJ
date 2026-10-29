package com.tanasi.streamflix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contrato de la distribución exclusiva para Android TV. */
class DeviceProfileTest {

    @Test
    fun `Leanback o modo televisión identifican Android TV`() {
        assertTrue(DeviceDetector.isAndroidTv(uiModeTelevision = false, hasLeanback = true))
        assertTrue(DeviceDetector.isAndroidTv(uiModeTelevision = true, hasLeanback = false))
        assertTrue(DeviceDetector.isAndroidTv(uiModeTelevision = true, hasLeanback = true))
    }

    @Test
    fun `un móvil o tablet sin Leanback no es compatible`() {
        assertFalse(DeviceDetector.isAndroidTv(uiModeTelevision = false, hasLeanback = false))
    }

    @Test
    fun `una televisión táctil conserva medidas y navegación de TV`() {
        val profile = DeviceProfile.Television.copy(hasTouchScreen = true)
        assertTrue(profile.isAndroidTv)
        assertTrue(profile.requiresDpadFocus)
        assertEquals(220, profile.posterWidthDp)
        assertEquals(400, profile.landscapeWidthDp)
        assertEquals(18, profile.cardSpacingDp)
        assertEquals(48, profile.horizontalPaddingDp)
    }

    @Test
    fun `el perfil por defecto es TV aunque el dispositivo no sea compatible`() {
        val profile = DeviceProfile.Television.copy(isAndroidTv = false)
        assertFalse(profile.isAndroidTv)
        assertTrue(profile.requiresDpadFocus)
        assertEquals(220, profile.posterWidthDp)
        assertEquals(48, profile.horizontalPaddingDp)
    }
}
