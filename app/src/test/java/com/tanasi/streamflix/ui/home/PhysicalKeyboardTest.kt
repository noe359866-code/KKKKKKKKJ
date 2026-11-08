package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.ui.home.PhysicalKeyboard.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresión de los cuatro fallos que tenía el teclado físico.
 *
 * Todos ellos compartían causa: el manejador recorría la `'A'` a la `'Z'` en
 * ASCII y guardaba un mapa de 36 entradas. Lo que no estuviera en el mapa se
 * perdía **sin avisar**. Aquí se fija el comportamiento nuevo.
 */
class PhysicalKeyboardTest {

    /** Atajo: tecla que escribe texto, sin modificadores. */
    private fun typed(char: Char): Action = PhysicalKeyboard.actionFor(
        codePoint = char.code,
        isBackspace = false,
        isDelete = false,
        isEnter = false,
        isDirectional = false,
        wordModifier = false,
    )

    // ------------------------------------------------------------------ //
    // 1. La Ñ                                                //
    // ------------------------------------------------------------------ //

    @Test
    fun `la enie se escribe`() {
        assertEquals(Action.Type("ñ"), typed('ñ'))
        assertEquals(Action.Type("Ñ"), typed('Ñ'))
    }

    @Test
    fun `la enie compone palabras`() {
        var text = ""
        for (c in "SEÑOR") text = TvKeyboardLayout.type(text, (typed(c) as Action.Type).text)
        assertEquals("SEÑOR", text)
    }

    // ------------------------------------------------------------------ //
    // 2. El espacio                                                        //
    // ------------------------------------------------------------------ //

    @Test
    fun `el espacio se escribe`() {
        assertEquals(Action.Type(" "), typed(' '))
    }

    @Test
    fun `una frase con espacios se escribe entera`() {
        var text = ""
        for (c in "EL SEÑOR") text = TvKeyboardLayout.type(text, (typed(c) as Action.Type).text)
        assertEquals("EL SEÑOR", text)
    }

    // ------------------------------------------------------------------ //
    // 3. Acentos y signos de apertura, que antes se perdían                //
    // ------------------------------------------------------------------ //

    @Test
    fun `acentos y signos de apertura llegan intactos`() {
        for (c in "áéíóúüÁÉÍÓÚÜ¿¡") {
            assertEquals("se perdió '$c'", Action.Type(c.toString()), typed(c))
        }
    }

    @Test
    fun `no se cuelan caracteres de control`() {
        /* `utf16CodePoint` devuelve 8 para Retroceso y 27 para Esc. Si se
         * trataran como texto, la consulta llevaría basura invisible. */
        assertEquals(Action.Passthrough, typed('\u0008'))
        assertEquals(Action.Passthrough, typed('\u001B'))
        assertEquals(Action.Passthrough, typed('\u007F'))
        assertEquals(Action.Passthrough, typed('\u009F'))
    }

    @Test
    fun `las teclas con nombre mandan sobre el codePoint`() {
        /* Retroceso y Enter llegan con un carácter de control; la prioridad de
         * las teclas con nombre es lo que evita escribirlo. */
        assertEquals(
            Action.Backspace,
            PhysicalKeyboard.actionFor(8, true, false, false, false, false),
        )
        assertEquals(
            Action.Submit,
            PhysicalKeyboard.actionFor(13, false, false, true, false, false),
        )
    }

    // ------------------------------------------------------------------ //
    // 4. Borrar más de un carácter de golpe                                //
    // ------------------------------------------------------------------ //

    @Test
    fun `control retroceso borra la palabra`() {
        assertEquals(
            Action.DeleteWord,
            PhysicalKeyboard.actionFor(
                codePoint = 8, isBackspace = true, isDelete = false,
                isEnter = false, isDirectional = false, wordModifier = true,
            ),
        )
    }

    @Test
    fun `supr borra la palabra`() {
        assertEquals(
            Action.DeleteWord,
            PhysicalKeyboard.actionFor(
                codePoint = 0, isBackspace = false, isDelete = true,
                isEnter = false, isDirectional = false, wordModifier = false,
            ),
        )
    }

    @Test
    fun `retroceso sin modificador borra una letra`() {
        assertEquals(
            Action.Backspace,
            PhysicalKeyboard.actionFor(
                codePoint = 8, isBackspace = true, isDelete = false,
                isEnter = false, isDirectional = false, wordModifier = false,
            ),
        )
    }

    // ------------------------------------------------------------------ //
    // Las flechas no se comen                                             //
    // ------------------------------------------------------------------ //

    @Test
    fun `las flechas pasan de largo`() {
        assertEquals(
            Action.Passthrough,
            PhysicalKeyboard.actionFor(
                codePoint = 0, isBackspace = false, isDelete = false,
                isEnter = false, isDirectional = true, wordModifier = false,
            ),
        )
    }

    @Test
    fun `una flecha no escribe aunque traiga codePoint`() {
        /* Depende del sistema: algunas versiones dan 0, otras un carácter. Si
         * una flecha escribiera, el campo se llenaría de basura mientras el
         * usuario intenta moverse. */
        assertEquals(
            Action.Passthrough,
            PhysicalKeyboard.actionFor(
                codePoint = 'a'.code, isBackspace = false, isDelete = false,
                isEnter = false, isDirectional = true, wordModifier = false,
            ),
        )
    }

    @Test
    fun `lo que no es texto ni mando se ignora`() {
        assertEquals(
            Action.Passthrough,
            PhysicalKeyboard.actionFor(0, false, false, false, false, false),
        )
    }
}
