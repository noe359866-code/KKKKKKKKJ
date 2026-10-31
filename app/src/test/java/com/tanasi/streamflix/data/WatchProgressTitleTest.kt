package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Dejar de seguir viendo": qué entradas del perfil son del título abierto.
 *
 * La ficha de una serie tiene que poder borrar **todos** sus capítulos, y sólo
 * los suyos: `serie:slug` no puede arrastrar `serie:slug-2` ni `serie:slugoso`.
 */
class WatchProgressTitleTest {

    private fun progress(id: String) = WatchProgress(
        id = id,
        type = "series",
        title = id,
        positionMs = 600_000L,
        durationMs = 2_400_000L,
        updatedAt = 1L,
    )

    @Test
    fun `un episodio pertenece a su serie`() {
        assertTrue(WatchProgress.sameTitle("cuevana:serie:slug:1:3", "cuevana:serie:slug"))
        assertTrue(WatchProgress.sameTitle("cuevana:serie:slug", "cuevana:serie:slug"))
        assertTrue(progress("gnula:serie:x:2:10").belongsTo("gnula:serie:x"))
    }

    @Test
    fun `no arrastra titulos que solo comparten prefijo`() {
        assertFalse(WatchProgress.sameTitle("cuevana:serie:slug-2", "cuevana:serie:slug"))
        assertFalse(WatchProgress.sameTitle("cuevana:serie:slugoso", "cuevana:serie:slug"))
        assertFalse(WatchProgress.sameTitle("otra:serie:slug", "cuevana:serie:slug"))
        assertFalse(progress("tt0133093").belongsTo("tt0133094"))
    }

    @Test
    fun `un id vacio no coincide con nada`() {
        assertFalse(WatchProgress.sameTitle("", ""))
        assertFalse(WatchProgress.sameTitle("tt1", ""))
        assertFalse(WatchProgress.sameTitle("", "tt1"))
        assertFalse(progress("tt1").belongsTo(""))
    }

    @Test
    fun `la regla tambien sirve para filtrar la lista del perfil`() {
        val entries = listOf(
            progress("cuevana:serie:slug"),
            progress("cuevana:serie:slug:1:1"),
            progress("cuevana:serie:slug:1:2"),
            progress("cuevana:serie:slug-2:1:1"),
            progress("tt0133093"),
        )

        val remaining = entries.filterNot { it.belongsTo("cuevana:serie:slug") }

        assertEquals(
            listOf("cuevana:serie:slug-2:1:1", "tt0133093"),
            remaining.map { it.id },
        )
    }
}
