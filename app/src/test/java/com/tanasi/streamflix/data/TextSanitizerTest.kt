package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.TextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reparación de texto mal decodificado (UTF-8 leído como Windows-1252).
 *
 * La regla que importa es **cuándo no tocar nada**: si el saneado se aplica a
 * todo, un título legítimo con acentos se puede corromper. Cada caso de aquí
 * es o una secuencia que los proveedores producen de verdad, o un texto sano
 * que tiene que salir intacto.
 */
class TextSanitizerTest {

    @Test
    fun `arregla las comillas tipograficas rotas`() {
        assertEquals("Don’t Stop", TextSanitizer.repair("Donâ€™t Stop"))
        assertEquals("L’amour", TextSanitizer.repair("Lâ€™amour"))
        assertEquals("“Cita”", TextSanitizer.repair("â€œCitaâ€\u009D"))
    }

    @Test
    fun `arregla los signos de apertura en espanol`() {
        assertEquals("¡Hola!", TextSanitizer.repair("Â¡Hola!"))
        assertEquals("¿Qué tal?", TextSanitizer.repair("Â¿Qué tal?"))
        /* Sin daño no se toca: el signo ya está bien. */
        assertEquals("¿Qué tal?", TextSanitizer.repair("¿Qué tal?"))
        assertEquals("«Cita»", TextSanitizer.repair("Â«CitaÂ»"))
    }

    @Test
    fun `arregla los guiones y los puntos suspensivos`() {
        assertEquals("parte 1 – parte 2", TextSanitizer.repair("parte 1 â€“ parte 2"))
        assertEquals("espera…", TextSanitizer.repair("esperaâ€¦"))
        assertEquals("sí — claro", TextSanitizer.repair("sí â€” claro"))
    }

    @Test
    fun `arregla las notas musicales de los subtitulos`() {
        /* Típico en una escena con música: el `.srt` trae "♪ ♪" y el
         * proveedor sirve latin-1. */
        assertEquals("♪ ♪", TextSanitizer.repair("â™ª â™ª"))
        assertEquals("♫", TextSanitizer.repair("â™«"))
    }

    @Test
    fun `un texto sano sale exactamente igual`() {
        val titles = listOf(
            "Café Ñandú",
            "Amélie",
            "El niño y el mundo",
            "¿Quién engañó a Roger Rabbit?",
            "Spider-Man: No Way Home",
            "",
        )
        for (title in titles) {
            assertEquals(title, TextSanitizer.repair(title))
        }
    }

    @Test
    fun `devuelve la misma instancia cuando no hay nada que arreglar`() {
        /* Importa en las listas de Compose: una `String` nueva con el mismo
         * contenido invalida `remember` que compara por identidad. */
        val clean = "Café Ñandú"
        assertSame(clean, TextSanitizer.repair(clean))
    }

    @Test
    fun `no inventa arreglos que no puede hacer`() {
        /* `PokÃ©mon` es otro mojibake (UTF-8 leído como latin-1) y no está en
         * la tabla. Dejarlo como está es correcto: adivinar la codificación de
         * origen mal sería peor que no arreglar nada. Este test documenta el
         * límite a propósito. */
        assertEquals("PokÃ©mon", TextSanitizer.repair("PokÃ©mon"))
    }

    @Test
    fun `el caracter de reemplazo desaparece`() {
        assertEquals("Hola mundo", TextSanitizer.repair("Hola\uFFFD mundo"))
    }

    @Test
    fun `needsRepair solo se dispara con las senales conocidas`() {
        assertTrue(TextSanitizer.needsRepair("Donâ€™t"))
        assertTrue(TextSanitizer.needsRepair("Â¡Hola!"))
        assertTrue(TextSanitizer.needsRepair("x\uFFFDy"))
        assertFalse(TextSanitizer.needsRepair("Café Ñandú"))
        assertFalse(TextSanitizer.needsRepair(""))
        assertFalse(TextSanitizer.needsRepair(null))
    }

    @Test
    fun `null sigue siendo null`() {
        assertNull(TextSanitizer.repair(null))
        assertNull(TextSanitizer.repairOrNull(null))
        assertNull("un texto que queda en blanco no se convierte en cadena vacía", TextSanitizer.repairOrNull("  "))
    }

    @Test
    fun `las secuencias largas se resuelven antes que sus prefijos`() {
        /* `â€“` (guion) empieza por `â€`. Si la regla corta se aplicara antes,
         * saldría `”“` en vez de `–`. */
        assertEquals("–", TextSanitizer.repair("â€“"))
        assertEquals("…", TextSanitizer.repair("â€¦"))
    }
}
