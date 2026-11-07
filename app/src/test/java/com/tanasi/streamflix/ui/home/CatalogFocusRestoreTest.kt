package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.ui.components.stableMediaKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogFocusRestoreTest {
    private fun entry(id: String, type: String = "movie") = MediaEntry(id = id, type = type, title = id)
    private fun row(id: String, vararg entries: MediaEntry) = CatalogRow(id = id, title = id, items = entries.toList())
    private fun anchor(row: CatalogRow, index: Int) = CatalogFocusAnchor(
        rowId = row.id,
        itemKey = stableMediaKeys(row.items)[index],
        itemIndex = index,
        returnSignal = 3,
    )

    @Test fun `restaura el mismo titulo y fila al reordenar ambos`() {
        val original = row("peliculas", entry("a"), entry("b"), entry("c"))
        val updated = listOf(row("otra", entry("b")), row("peliculas", entry("nuevo"), entry("c"), entry("b")))
        assertEquals(CatalogFocusTarget(1, 2), resolveCatalogFocus(updated, anchor(original, 1)))
    }

    @Test fun `identifica tipo y ocurrencia de entradas duplicadas`() {
        val original = row("fila", entry("1"), entry("1", "series"), entry("1"))
        val reordered = row("fila", entry("1", "series"), entry("1"), entry("1"))
        assertEquals(CatalogFocusTarget(0, 2), resolveCatalogFocus(listOf(reordered), anchor(original, 2)))
    }

    @Test fun `si falta la tarjeta conserva una posicion valida en la fila`() {
        val original = row("fila", entry("a"), entry("b"), entry("c"))
        assertEquals(CatalogFocusTarget(0, 0), resolveCatalogFocus(listOf(row("fila", entry("a"))), anchor(original, 2)))
        assertEquals(CatalogFocusTarget(0, 1), resolveCatalogFocus(listOf(row("fila", entry("a"), entry("d"))), anchor(original, 1)))
    }

    @Test fun `si desaparece la fila usa la primera fila con contenido`() {
        val original = row("fila", entry("a"))
        val updated = listOf(row("vacia"), row("otra", entry("b")))
        assertEquals(CatalogFocusTarget(1, 0), resolveCatalogFocus(updated, anchor(original, 0)))
    }

    @Test fun `no pide foco si no hay tarjetas`() {
        val original = row("fila", entry("a"))
        assertNull(resolveCatalogFocus(emptyList(), anchor(original, 0)))
        assertNull(resolveCatalogFocus(listOf(row("fila")), anchor(original, 0)))
    }
    @Test fun `restauracion vencida no interrumpe nueva accion o pantalla`() {
        val original = anchor(row("fila", entry("a")), 0)
        val newer = original.copy(itemIndex = 1, itemKey = "otra")
        assertTrue(mayRestoreCatalogFocus(original, original, 4, true, false))
        assertFalse(mayRestoreCatalogFocus(original, null, 4, true, false))
        assertFalse(mayRestoreCatalogFocus(original, newer, 4, true, false))
        assertFalse(mayRestoreCatalogFocus(original, original, 3, true, false))
        assertFalse(mayRestoreCatalogFocus(original, original, 4, false, false))
        assertFalse(mayRestoreCatalogFocus(original, original, 4, true, true))
        assertFalse(mayRestoreCatalogFocus(null, null, 4, true, false))
    }

}
