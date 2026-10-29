package com.tanasi.streamflix.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tabla de teclas del reproductor. Se prueba aquí, sin emulador, porque un
 * mando que no responde al `OK` se percibe como una app colgada.
 */
class PlayerKeyMapTest {

    @Test
    fun `OK y teclas multimedia alternan play pausa`() {
        val toggleKeys = listOf(
            Key.DirectionCenter,
            Key.Enter,
            Key.NumPadEnter,
            Key.Spacebar,
            Key.ButtonA,
            Key.MediaPlayPause,
            Key.MediaPlay,
            Key.MediaPause,
        )

        toggleKeys.forEach { key ->
            assertEquals(key.toString(), PlayerKeyAction.TOGGLE_PLAY_PAUSE, PlayerKeyMap.actionFor(key))
        }
    }

    @Test
    fun `las flechas horizontales saltan diez segundos`() {
        assertEquals(PlayerKeyAction.SEEK_BACK, PlayerKeyMap.actionFor(Key.DirectionLeft))
        assertEquals(PlayerKeyAction.SEEK_FORWARD, PlayerKeyMap.actionFor(Key.DirectionRight))
        assertEquals(PlayerKeyAction.SEEK_BACK, PlayerKeyMap.actionFor(Key.MediaRewind))
        assertEquals(PlayerKeyAction.SEEK_FORWARD, PlayerKeyMap.actionFor(Key.MediaFastForward))
    }

    @Test
    fun `arriba y abajo abren los controles`() {
        assertEquals(PlayerKeyAction.OPEN_CONTROLS, PlayerKeyMap.actionFor(Key.DirectionUp))
        assertEquals(PlayerKeyAction.OPEN_CONTROLS, PlayerKeyMap.actionFor(Key.DirectionDown))
    }

    @Test
    fun `el resto de teclas no son del reproductor`() {
        assertNull(PlayerKeyMap.actionFor(Key.Q))
        assertNull(PlayerKeyMap.actionFor(Key.Back))
    }
}
