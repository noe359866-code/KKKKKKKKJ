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
    fun `todas las filas tienen teclas y no se repiten`() {
        val keys = TvKeyboardLayout.rows.flatten()
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(27, keys.size)
    }
}
