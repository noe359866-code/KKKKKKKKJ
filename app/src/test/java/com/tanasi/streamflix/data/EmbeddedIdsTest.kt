package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.embedded.EmbeddedIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedIdsTest {
    @Test
    fun `pelicula IMDb conserva base e imdb`() {
        val id = EmbeddedIds.parse("tt0133093", "movie")
        assertEquals("tt0133093", id.base)
        assertEquals("tt0133093", id.imdbId)
        assertNull(id.season)
        assertNull(id.episode)
        assertFalse(id.isLive)
    }

    @Test
    fun `serie separa temporada y episodio`() {
        val id = EmbeddedIds.parse("tt0944947:1:2.json", "series")
        assertEquals("tt0944947", id.base)
        assertEquals(1, id.season)
        assertEquals(2, id.episode)
        assertEquals("tt0944947:1:2", id.raw)
    }

    @Test
    fun `canal en vivo se detecta por tipo o prefijo`() {
        assertTrue(EmbeddedIds.parse("tv:futbolibre:espn-1", "tv").isLive)
        assertTrue(EmbeddedIds.parse("tv:tvenvivo:fox-sports", "").isLive)
        assertTrue(EmbeddedIds.parse("sports:espn:1", "").isSports)
        assertFalse(EmbeddedIds.parse("gnula:spider-man", "movie").isLive)
    }
}
