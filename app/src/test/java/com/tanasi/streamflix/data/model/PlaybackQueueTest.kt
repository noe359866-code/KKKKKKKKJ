package com.tanasi.streamflix.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La parrilla que viaja al reproductor.
 *
 * Se prueba en JVM porque es la pieza que decide **qué canales existen** al
 * pulsar `CH+`: si mete una película, el mando salta a la nada; si pierde un
 * canal, el zapeo se salta la parrilla que el usuario tiene en pantalla.
 */
class PlaybackQueueTest {

    @Test
    fun `solo entran canales en directo`() {
        val row = listOf(
            channel("tv:futbolibre:espn", "ESPN"),
            movie("pelisplushd:deadpool", "Deadpool"),
            channel("tv:futbolibre:fox", "Fox Sports"),
            movie("tmdb:550", "Fight Club"),
        )

        val queue = row.toChannelQueue()

        assertEquals(listOf("tv:futbolibre:espn", "tv:futbolibre:fox"), queue.map { it.id })
        assertTrue(queue.all { it.isLive })
        assertEquals("ESPN", queue.first().title)
        assertEquals("tv", queue.first().type)
    }

    @Test
    fun `un evento en directo tambien es zapeable`() {
        /* La agenda deportiva se sirve como `tv` con `onAir`: entra igual. */
        val event = MediaEntry(
            id = "tv:damitv:evento:123",
            type = "tv",
            title = "Clásico",
            live = true,
            onAir = true,
        )

        assertEquals(1, listOf(event).toChannelQueue().size)
    }

    @Test
    fun `el mismo canal repetido no se zapea dos veces`() {
        val row = listOf(
            channel("tv:x:a", "A"),
            channel("tv:x:b", "B"),
            channel("tv:x:a", "A"),
        )

        assertEquals(2, row.toChannelQueue().size)
    }

    @Test
    fun `la parrilla tiene tope para no reventar el intent`() {
        val row = (1..200).map { channel("tv:x:$it", "Canal $it") }

        assertEquals(MAX_CHANNEL_QUEUE, row.toChannelQueue().size)
    }

    @Test
    fun `la parrilla sobrevive al viaje por el intent`() {
        val queue = listOf(
            PlaybackChannel(id = "tv:x:1", title = "Uno", poster = "https://cdn/1.jpg"),
            PlaybackChannel(id = "tv:x:2", title = "Dos", isLive = true),
        )

        val decoded = PlaybackQueueCodec.decode(PlaybackQueueCodec.encode(queue))

        assertEquals(queue, decoded)
    }

    @Test
    fun `un extra corrupto deja el reproductor sin parrilla pero entero`() {
        assertTrue(PlaybackQueueCodec.decode(null).isEmpty())
        assertTrue(PlaybackQueueCodec.decode("").isEmpty())
        assertTrue(PlaybackQueueCodec.decode("{no es json}").isEmpty())
        assertTrue(PlaybackQueueCodec.decode("""[{"id":"x"}]""").isEmpty())
    }

    @Test
    fun `la peticion de reproduccion lleva la parrilla y el canal actual`() {
        val row = listOf(channel("tv:x:a", "A"), channel("tv:x:b", "B"))

        val request = row.first().playbackRequest(queue = row.toChannelQueue())

        assertEquals("tv:x:a", request.id)
        assertEquals(listOf("tv:x:a", "tv:x:b"), request.queue.map { it.id })
        assertTrue(request.isLive)
    }

    private fun channel(id: String, title: String) = MediaEntry(
        id = id,
        type = "tv",
        title = title,
        poster = "https://cdn/$title.jpg",
        live = true,
        onAir = true,
    )

    private fun movie(id: String, title: String) = MediaEntry(
        id = id,
        type = "movie",
        title = title,
    )
}
