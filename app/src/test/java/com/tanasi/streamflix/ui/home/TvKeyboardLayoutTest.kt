package com.tanasi.streamflix.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El teclado del buscador es nuestro: no hay `TextField` ni IME, así que las
 * dos operaciones que puede hacer (escribir y borrar) se prueban aquí.
 */
class TvKeyboardLayoutTest {

    @Test
    fun `escribir anade al final`() {
        assertEquals("AV", TvKeyboardLayout.type("A", "V"))
        assertEquals("AVENGERS", TvKeyboardLayout.type("AVENGE", "RS"))
    }

    @Test
    fun `el espacio se escribe y no queda al principio`() {
        assertEquals("AVENGERS ENDGAME", TvKeyboardLayout.type("AVENGERS", " ") + "ENDGAME")
        assertEquals("AVENGERS", TvKeyboardLayout.type("", " ") + "AVENGERS")
    }

    @Test
    fun `la consulta no crece sin tope`() {
        val long = "X".repeat(TvKeyboardLayout.MAX_QUERY + 40)
        assertEquals(TvKeyboardLayout.MAX_QUERY, TvKeyboardLayout.type(long, "Y").length)
    }

    @Test
    fun `borrar quita el ultimo caracter y no rompe el vacio`() {
        assertEquals("AVE", TvKeyboardLayout.backspace("AVEN"))
        assertEquals("", TvKeyboardLayout.backspace("A"))
        assertEquals("", TvKeyboardLayout.backspace(""))
    }

    @Test
    fun `normalizar deja una sola linea de espacios`() {
        assertEquals("LOS SIMPSONS", TvKeyboardLayout.normalize("  LOS   SIMPSONS  "))
        assertEquals("", TvKeyboardLayout.normalize("   "))
    }

    @Test
    fun `los titulos con numeros se pueden escribir`() {
        var text = ""
        "1917".forEach { text = TvKeyboardLayout.type(text, it.toString()) }
        assertEquals("1917", text)
        assertEquals(10, TvKeyboardLayout.digitRow.size)
        assertEquals(listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"), TvKeyboardLayout.digitRow.sorted())
    }

    @Test
    fun `todas las filas tienen teclas y no se repiten`() {
        val keys = TvKeyboardLayout.rows.flatten() + TvKeyboardLayout.digitRow
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(37, keys.size)
    }
}
