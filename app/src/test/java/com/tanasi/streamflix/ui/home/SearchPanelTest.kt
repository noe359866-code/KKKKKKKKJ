package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.model.MediaEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué enseña el panel derecho del buscador, en cada momento.
 *
 * Es la regla de producto del buscador y la que decide si el usuario tiene que
 * pulsar `BUSCAR` para saber si su consulta sirve de algo. También decide qué
 * fila recibe el foco al entrar con `▶` desde el teclado: si el primer elemento
 * es un encabezado (no enfocable), el mando se queda sin destino.
 */
class SearchPanelTest {

    private fun entry(id: String, title: String = id) =
        MediaEntry(id = id, type = "movie", title = title)

    @Test
    fun `con resultados el panel solo ensena resultados`() {
        val items = searchPanelItems(
            query = "matrix",
            preview = SearchPreview.Ready(listOf(entry("tt1"), entry("tt2"))),
            suggestions = listOf(entry("tt9", "Recomendada")),
            recentSearches = listOf("alien"),
        )

        assertEquals("RESULTADOS", (items.first() as SearchPanelItem.Header).title)
        assertEquals(
            listOf("tt1", "tt2"),
            items.filterIsInstance<SearchPanelItem.Entry>().map { it.entry.id },
        )
        /* Ni historial ni recomendaciones: mientras se escribe manda lo que se
         * encuentra. */
        assertTrue(items.none { it is SearchPanelItem.Recent })
    }

    @Test
    fun `buscando no adelanta caratulas a medio llegar`() {
        val items = searchPanelItems("matrix", SearchPreview.Loading, emptyList(), emptyList())

        assertEquals("BUSCANDO", (items.first() as SearchPanelItem.Header).title)
        assertEquals(1, items.count { it is SearchPanelItem.Searching })
        assertTrue(items.none { it is SearchPanelItem.Entry })
    }

    @Test
    fun `sin resultados lo dice y ofrece lo que ya se busco`() {
        val items = searchPanelItems(
            query = "  zzz  ",
            preview = SearchPreview.Empty,
            suggestions = emptyList(),
            recentSearches = listOf("alien", "los simpsons", "dune", "heat", "otra"),
        )

        val header = items.first() as SearchPanelItem.Header
        assertEquals("SIN RESULTADOS", header.title)
        assertTrue("la consulta se enseña recortada", header.hint.contains("«zzz»"))
        /* Como mucho cuatro atajos: es un panel, no el historial entero. */
        assertEquals(4, items.count { it is SearchPanelItem.Recent })
    }

    @Test
    fun `con el campo vacio primero el historial y despues cinemeta`() {
        val items = searchPanelItems(
            query = "",
            preview = SearchPreview.Idle,
            suggestions = listOf(entry("tt1", "Recomendada")),
            recentSearches = listOf("alien"),
        )

        assertEquals("BÚSQUEDAS ANTERIORES", (items[0] as SearchPanelItem.Header).title)
        assertTrue(items[1] is SearchPanelItem.Recent)
        assertTrue(items.any { it is SearchPanelItem.ForgetRecent })

        val recs = items.indexOfFirst { it is SearchPanelItem.Header && it.title.startsWith("RECOMENDACIONES") }
        val firstEntry = items.indexOfFirst { it is SearchPanelItem.Entry }
        assertTrue("las recomendaciones van después del historial", recs in 0 until firstEntry)
    }

    @Test
    fun `sin historial ni recomendaciones queda un texto que invita a escribir`() {
        val items = searchPanelItems("", SearchPreview.Idle, emptyList(), emptyList())

        assertTrue(items.any { it is SearchPanelItem.Note })
        assertTrue(items.none { it is SearchPanelItem.Recent })
        assertEquals(-1, firstFocusableIndex(items))
    }

    @Test
    fun `el foco cae en la primera fila pulsable aunque el primero sea un encabezado`() {
        val items = searchPanelItems("", SearchPreview.Idle, emptyList(), listOf("alien", "dune"))

        assertEquals(1, firstFocusableIndex(items))
        assertTrue(items[1] is SearchPanelItem.Recent)
    }

    @Test
    fun `olvidar el historial solo aparece si hay historial`() {
        val sin = searchPanelItems("", SearchPreview.Idle, listOf(entry("tt1")), emptyList())
        val con = searchPanelItems("", SearchPreview.Idle, emptyList(), listOf("alien"))

        assertTrue(sin.none { it is SearchPanelItem.ForgetRecent })
        assertTrue(con.any { it is SearchPanelItem.ForgetRecent })
    }
}
