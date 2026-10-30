package com.tanasi.streamflix.player

import com.tanasi.streamflix.data.model.PlaybackChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reglas del cambio de canal, probadas en JVM.
 *
 * Un `CH+` que no responde al final de la parrilla se vive como "la app se
 * colgó": por eso la vuelta y el caso "canal que no está en la lista" están aquí
 * y no sólo en el reproductor.
 */
class ChannelZappingTest {

    @Test
    fun `subir y bajar recorre la parrilla`() {
        assertEquals(1, ChannelZapping.step(0, 1, 5))
        assertEquals(3, ChannelZapping.step(4, -1, 5))
    }

    @Test
    fun `los extremos dan la vuelta`() {
        assertEquals(0, ChannelZapping.step(4, 1, 5))
        assertEquals(4, ChannelZapping.step(0, -1, 5))
    }

    @Test
    fun `un canal fuera de la parrilla arranca por el borde`() {
        /* Abierto desde fuera (ficha, historial): `CH+` entra por el primero y
         * `CH−` por el último, que es lo que hace un televisor. */
        assertEquals(0, ChannelZapping.step(-1, 1, 5))
        assertEquals(4, ChannelZapping.step(-1, -1, 5))
    }

    @Test
    fun `sin parrilla no hay salto`() {
        assertEquals(-1, ChannelZapping.step(0, 1, 0))
        assertEquals(-1, ChannelZapping.step(3, 1, 0))
    }

    @Test
    fun `un solo canal no se mueve`() {
        assertEquals(0, ChannelZapping.step(0, 1, 1))
        assertEquals(0, ChannelZapping.step(0, -1, 1))
    }

    @Test
    fun `zapear exige directo y al menos dos canales`() {
        assertEquals(false, ChannelZapping.canZap(isLive = false, size = 9))
        assertEquals(false, ChannelZapping.canZap(isLive = true, size = 1))
        assertEquals(false, ChannelZapping.canZap(isLive = true, size = 0))
        assertEquals(true, ChannelZapping.canZap(isLive = true, size = 2))
    }

    @Test
    fun `el rotulo numera desde uno y calla si no aplica`() {
        assertEquals("1 de 3", ChannelZapping.positionLabel(queue(3), 0))
        assertEquals("3 de 3", ChannelZapping.positionLabel(queue(3), 2))
        assertNull(ChannelZapping.positionLabel(queue(3), 3))
        assertNull(ChannelZapping.positionLabel(queue(3), -1))
    }

    @Test
    fun `el indice se busca por id y no por posicion`() {
        assertEquals(1, ChannelZapping.indexOf(queue(3), "tv:test:2"))
        assertEquals(-1, ChannelZapping.indexOf(queue(3), "tv:test:99"))
        assertNull(ChannelZapping.channelAt(queue(3), 9))
    }

    private fun queue(size: Int): List<PlaybackChannel> = (1..size).map { index ->
        PlaybackChannel(id = "tv:test:$index", title = "Canal $index")
    }
}
