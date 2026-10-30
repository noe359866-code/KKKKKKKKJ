package com.tanasi.streamflix.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las recomendaciones del buscador salen de las filas de Cinemeta ya cargadas.
 * Si la selección se equivoca, el buscador recomienda canales o filas de un
 * addon de terceros con la etiqueta de Cinemeta.
 */
class CinemetaSuggestionsTest {

    @Test
    fun `solo entran las filas de cinemeta`() {
        val rows = listOf(
            row("remote:movie/top", "Películas populares", source = "cinemeta", items = listOf(entry("tt1", "Uno"))),
            row("remote:movie/streaming_nfx", "Netflix", source = "remote", items = listOf(entry("tt2", "Dos"))),
            row("remote:tv/canales", "Canales", source = "live", items = listOf(entry("tv:x:1", "Canal", live = true))),
        )

        assertEquals(listOf("tt1"), CinemetaSuggestions.from(rows).map { it.id })
    }

    @Test
    fun `sin source se deduce del id del catalogo`() {
        /* Servidores antiguos no publican `source`: los ids de Cinemeta son
         * `cinemeta_*` en el motor embebido y `top`/`imdbRating` en el addon. */
        assertTrue(CinemetaSuggestions.isCinemeta(row("remote:movie/cinemeta_movie_top", "Populares")))
        assertTrue(CinemetaSuggestions.isCinemeta(row("remote:movie/top", "Populares")))
        assertTrue(CinemetaSuggestions.isCinemeta(row("remote:series/imdbRating", "Valoradas")))
        assertFalse(CinemetaSuggestions.isCinemeta(row("remote:movie/streaming_nfx", "Netflix")))
        assertFalse(CinemetaSuggestions.isCinemeta(row("remote:movie/top", "Populares", source = "remote")))
    }

    @Test
    fun `no se recomiendan canales ni titulos sin caratula`() {
        val rows = listOf(
            row(
                "remote:movie/top",
                "Populares",
                source = "cinemeta",
                items = listOf(
                    entry("tt1", "Con poster"),
                    entry("tt2", "Sin poster", poster = null),
                    entry("tv:x:1", "Canal", live = true),
                ),
            ),
        )

        assertEquals(listOf("tt1"), CinemetaSuggestions.from(rows).map { it.id })
    }

    @Test
    fun `el mismo titulo no se repite en dos filas`() {
        val rows = listOf(
            row("remote:movie/top", "Populares", source = "cinemeta", items = listOf(entry("tt1", "Uno"))),
            row("remote:movie/imdbRating", "Valoradas", source = "cinemeta", items = listOf(entry("tt1", "Uno"), entry("tt9", "Nueve"))),
        )

        assertEquals(listOf("tt1", "tt9"), CinemetaSuggestions.from(rows).map { it.id })
    }

    @Test
    fun `las recomendaciones tienen tope`() {
        val rows = listOf(
            row(
                "remote:movie/top",
                "Populares",
                source = "cinemeta",
                items = (1..80).map { entry("tt$it", "Título $it") },
            ),
        )

        assertEquals(CinemetaSuggestions.DEFAULT_LIMIT, CinemetaSuggestions.from(rows).size)
        assertEquals(5, CinemetaSuggestions.from(rows, limit = 5).size)
    }

    private fun row(
        id: String,
        title: String,
        source: String? = null,
        items: List<MediaEntry> = emptyList(),
    ) = CatalogRow(id = id, title = title, items = items, source = source)

    private fun entry(id: String, title: String, poster: String? = "https://cdn/$id.jpg", live: Boolean = false) =
        MediaEntry(id = id, type = "movie", title = title, poster = poster, live = live)
}
