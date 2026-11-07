package com.tanasi.streamflix.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun `insets del perfil predeterminado protegen el cinco por ciento`() {
        assertEquals(48, DeviceProfile.Tv.horizontalPaddingDp)
        assertEquals(27, DeviceProfile.Tv.verticalPaddingDp)
    }

    @Test
    fun `insets siguen el espacio logico y redondean hacia arriba`() {
        val profile = DeviceProfile(screenWidthDp = 1281, screenHeightDp = 721)
        assertEquals(65, profile.horizontalPaddingDp)
        assertEquals(37, profile.verticalPaddingDp)
    }

    @Test
    fun `dimensiones invalidas usan los valores predeterminados`() {
        val profile = DeviceProfile(screenWidthDp = 0, screenHeightDp = -1)
        assertEquals(48, profile.horizontalPaddingDp)
        assertEquals(27, profile.verticalPaddingDp)
    }
    @Test
    fun `perfil normalizado sigue cambios de dimensiones e ignora valores invalidos`() {
        val initial = DeviceProfile.fromDimensions(960, 540)
        val changed = DeviceProfile.fromDimensions(1280, 720)
        assertEquals(48, initial.horizontalPaddingDp)
        assertEquals(64, changed.horizontalPaddingDp)
        assertEquals(36, changed.verticalPaddingDp)
        assertEquals(DeviceProfile.Tv, DeviceProfile.fromDimensions(0, -1))
    }
}
