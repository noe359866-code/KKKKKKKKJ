package com.tanasi.streamflix.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La guía de programación de un canal.
 *
 * No hay rejilla horaria de ningún proveedor de TV (ni XMLTV ni API de
 * programas), así que lo único que se puede afirmar con datos reales es la
 * agenda deportiva: un partido es una emisión con hora, competición y estado. Lo
 * que se prueba aquí es que eso llega a la guía y que **no** se inventa nada
 * cuando la fuente calla — una guía con huecos falsos es peor que una guía
 * vacía.
 */
class EpgTest {

    private fun match(
        home: String = "Lima",
        away: String = "Alianza",
        start: String? = "19:00",
        league: String? = "Liga 1",
        onAir: Boolean = true,
    ) = MediaEntry(
        /* Un id por partido: dos con el mismo id se colapsarían en la parrilla. */
        id = "tv:damitv:evento:$home-$away",
        type = "tv",
        title = "$home vs $away",
        live = true,
        onAir = onAir,
        homeTeam = home,
        awayTeam = away,
        league = league,
        startTime = start,
    )

    @Test
    fun `un partido en vivo es la emision de ahora`() {
        val entries = Epg.from(match())

        assertEquals(1, entries.size)
        assertEquals("Lima vs Alianza", entries.single().title)
        assertEquals("19:00", entries.single().start)
        assertEquals("Liga 1", entries.single().subtitle)
        assertTrue(entries.single().live)
        assertEquals("AHORA · 19:00 · Liga 1", Epg.line(entries))
    }

    @Test
    fun `un partido que aun no empieza dice hoy`() {
        val entries = Epg.from(match(onAir = false))

        assertEquals("HOY · 19:00 · Liga 1", Epg.line(entries))
    }

    @Test
    fun `sin hora ni competicion la linea no rellena huecos`() {
        assertEquals("HOY", Epg.line(Epg.from(match(start = null, league = null, onAir = false))))
        assertEquals("AHORA", Epg.line(Epg.from(match(start = "", league = "  "))))
    }

    @Test
    fun `un canal sin programacion conocida no inventa nada`() {
        val channel = MediaEntry(id = "tv:futbolibre:espn", type = "tv", title = "ESPN", live = true)

        assertTrue(Epg.from(channel).isEmpty())
        assertNull(Epg.line(Epg.from(channel)))
    }

    @Test
    fun `una pelicula no tiene guia`() {
        val movie = MediaEntry(id = "tt0133093", type = "movie", title = "Matrix")

        assertTrue(Epg.from(movie).isEmpty())
    }

    @Test
    fun `la emision al aire manda sobre la primera de la lista`() {
        val entries = listOf(
            EpgEntry(title = "Antes", start = "17:00", live = false),
            EpgEntry(title = "Ahora", start = "19:00", live = true),
        )

        assertEquals("Ahora", Epg.now(entries)?.title)
        assertEquals("AHORA · 19:00", Epg.line(entries))
        /* Sin ninguna marcada, la primera: es el orden de la fuente. */
        assertEquals("Antes", Epg.now(entries.map { it.copy(live = false) })?.title)
        assertNull(Epg.now(emptyList()))
    }

    @Test
    fun `la programacion viaja con la parrilla por el intent`() {
        val queue = listOf(match(), match(home = "Real", away = "Barcelona", league = "La Liga"))
            .toChannelQueue()

        assertEquals(2, queue.size)
        assertTrue(queue.all { it.epg.isNotEmpty() })

        val decoded = PlaybackQueueCodec.decode(PlaybackQueueCodec.encode(queue))

        assertEquals(queue, decoded)
        assertEquals("AHORA · 19:00 · La Liga", Epg.line(decoded[1].epg))
    }
}
