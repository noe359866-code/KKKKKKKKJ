package com.tanasi.streamflix.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El historial del buscador.
 *
 * Lo que se juega aquí es si repetir la búsqueda de ayer cuesta una pulsación o
 * veinte: en una TV se escribe con el mando. Las reglas son pocas pero fáciles
 * de romper (duplicados, mayúsculas, consultas de una letra que son sólo un
 * tecleo).
 */
class SearchHistoryTest {

    @Test
    fun `lo mas reciente va primero`() {
        assertEquals(listOf("matrix", "alien"), SearchHistory.add(listOf("alien"), "matrix"))
        assertEquals(listOf("c", "b", "a"), SearchHistory.add(listOf("b", "a"), "c"))
    }

    @Test
    fun `repetir una busqueda la sube al principio y no la duplica`() {
        assertEquals(listOf("alien", "matrix"), SearchHistory.add(listOf("matrix", "alien"), "alien"))
        /* Misma consulta con otras mayúsculas: para el usuario es la misma. */
        assertEquals(listOf("Alien", "matrix"), SearchHistory.add(listOf("matrix", "alien"), "Alien"))
    }

    @Test
    fun `una letra suelta no es una busqueda`() {
        assertEquals(emptyList<String>(), SearchHistory.add(emptyList(), "a"))
        assertEquals(emptyList<String>(), SearchHistory.add(emptyList(), "   "))
        assertEquals(listOf("al"), SearchHistory.add(emptyList(), "al"))
    }

    @Test
    fun `los espacios de mas se recortan`() {
        assertEquals(listOf("los simpsons"), SearchHistory.add(emptyList(), "  los   simpsons  "))
    }

    @Test
    fun `el historial no crece sin tope`() {
        var history = emptyList<String>()
        repeat(SearchHistory.MAX_ENTRIES + 5) { index -> history = SearchHistory.add(history, "busqueda $index") }

        assertEquals(SearchHistory.MAX_ENTRIES, history.size)
        assertEquals("busqueda ${SearchHistory.MAX_ENTRIES + 4}", history.first())
        assertTrue(history.none { it == "busqueda 0" })
    }

    @Test
    fun `olvidar una consulta no toca las demas`() {
        assertEquals(listOf("matrix"), SearchHistory.forget(listOf("alien", "matrix"), "ALIEN"))
        /* Lo que no está, no rompe nada. */
        assertEquals(listOf("matrix"), SearchHistory.forget(listOf("matrix"), "alien"))
    }

    @Test
    fun `al leer del disco se sanea`() {
        val stored = listOf("matrix", "  ", "a", " ", "MATRIX", "alien", "matrix")

        assertEquals(listOf("matrix", "alien"), SearchHistory.sanitize(stored))
    }

    @Test
    fun `la consulta de hoy no se repite como sugerencia`() {
        assertEquals(listOf("alien"), SearchHistory.forQuery(listOf("matrix", "alien"), "Matrix"))
        /* Con el campo vacío se enseña todo. */
        assertEquals(listOf("matrix", "alien"), SearchHistory.forQuery(listOf("matrix", "alien"), "  "))
    }
}
