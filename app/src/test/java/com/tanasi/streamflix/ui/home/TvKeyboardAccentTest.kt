package com.tanasi.streamflix.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los acentos del teclado del buscador.
 *
 * Vivían fuera del teclado "porque no cabía una fila más": en 960 × 540 dp el
 * teclado ya ocupa el alto disponible. La solución no fue recortar teclas sino
 * compartir fila (`123` ⇄ `ÁÉÍ`), así que lo que se comprueba aquí es que la
 * fila no cambia de tamaño —si cambiara, `BUSCAR` volvería a salirse de la
 * pantalla— y que lo escrito con tilde llega tal cual a la consulta.
 */
class TvKeyboardAccentTest {

    @Test
    fun `la fila de simbolos mide lo mismo en las dos paginas`() {
        val digits = TvKeyboardLayout.symbolRow(TvKeyboardLayout.SymbolPage.DIGITS)
        val accents = TvKeyboardLayout.symbolRow(TvKeyboardLayout.SymbolPage.ACCENTS)

        assertEquals(TvKeyboardLayout.digitRow, digits)
        /* Diez teclas a cada lado: el ancho del teclado no se mueve ni un dp. */
        assertEquals(digits.size, accents.size)
    }

    @Test
    fun `los acentos que si aparecen en los titulos estan`() {
        val accents = TvKeyboardLayout.symbolRow(TvKeyboardLayout.SymbolPage.ACCENTS)

        listOf("Á", "É", "Í", "Ó", "Ú", "Ü").forEach {
            assertTrue("falta la tilde $it", accents.contains(it))
        }
        /* "¿Qué pasó ayer?" y "¡Asu mare!" no se podían escribir. */
        assertTrue(accents.contains("¿"))
        assertTrue(accents.contains("¡"))
    }

    @Test
    fun `la tecla de pagina dice a donde lleva`() {
        assertEquals(TvKeyboardLayout.SymbolPage.ACCENTS, TvKeyboardLayout.toggle(TvKeyboardLayout.SymbolPage.DIGITS))
        assertEquals(TvKeyboardLayout.SymbolPage.DIGITS, TvKeyboardLayout.toggle(TvKeyboardLayout.SymbolPage.ACCENTS))

        assertEquals("ÁÉÍ", TvKeyboardLayout.toggleLabel(TvKeyboardLayout.SymbolPage.DIGITS))
        assertEquals("123", TvKeyboardLayout.toggleLabel(TvKeyboardLayout.SymbolPage.ACCENTS))
        assertNotEquals(
            TvKeyboardLayout.toggleLabel(TvKeyboardLayout.SymbolPage.DIGITS),
            TvKeyboardLayout.toggleLabel(TvKeyboardLayout.SymbolPage.ACCENTS),
        )
    }

    @Test
    fun `se puede escribir un titulo con tildes y signos`() {
        var text = ""
        "¿QUÉ PASÓ AYER?".forEach { text = TvKeyboardLayout.type(text, it.toString()) }

        assertEquals("¿QUÉ PASÓ AYER?", text)
        /* Y la consulta sigue teniendo el mismo tope. */
        assertEquals(TvKeyboardLayout.MAX_QUERY, TvKeyboardLayout.type("Á".repeat(TvKeyboardLayout.MAX_QUERY), "É").length)
    }

    @Test
    fun `la pagina de acentos no repite letras del resto del teclado`() {
        val letters = TvKeyboardLayout.rows.flatten().toSet()
        val accents = TvKeyboardLayout.symbolRow(TvKeyboardLayout.SymbolPage.ACCENTS).toSet()

        /* La Ñ es la única tecla que comparten las dos páginas (es la misma
         * letra y hace falta siempre). Los acentos no chocan con letras sin
         * tilde: Á no es A, y buscarlos es distinto. */
        assertEquals(setOf("Ñ"), accents.intersect(letters))
    }
}
