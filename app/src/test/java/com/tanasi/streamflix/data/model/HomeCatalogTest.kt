package com.tanasi.streamflix.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeCatalogTest {
    @Test fun `el destacado distingue id compartido por pelicula y serie`() {
        val movie = MediaEntry(id = "same", type = "movie", title = "Película")
        val series = MediaEntry(id = "same", type = "series", title = "Serie")
        val catalog = HomeCatalog(listOf(CatalogRow(id = "mixed", title = "Mixta", items = listOf(movie, series))))
        assertEquals(movie, catalog.featured?.entry)
        assertEquals(1, catalog.spotlightFor("mixed", "same", "movie")?.position)
        assertEquals(2, catalog.spotlightFor("mixed", "same", "series")?.position)
        assertNull(catalog.spotlightFor("mixed", "same", "anime"))
    }

    @Test fun `ids con barras no colisionan entre filas`() {
        val one = MediaEntry(id = "b/c", type = "movie", title = "Uno")
        val two = MediaEntry(id = "c", type = "movie", title = "Dos")
        val catalog = HomeCatalog(listOf(
            CatalogRow(id = "a", title = "Primera", items = listOf(one)),
            CatalogRow(id = "a/b", title = "Segunda", items = listOf(two)),
        ))
        assertEquals(one, catalog.spotlightFor("a", "b/c", "movie")?.entry)
        assertEquals(two, catalog.spotlightFor("a/b", "c", "movie")?.entry)
    }
}
