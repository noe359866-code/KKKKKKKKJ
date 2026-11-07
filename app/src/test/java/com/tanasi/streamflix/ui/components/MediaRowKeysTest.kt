package com.tanasi.streamflix.ui.components

import com.tanasi.streamflix.data.model.MediaEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaRowKeysTest {
    private fun entry(type: String, id: String) = MediaEntry(id = id, type = type, title = id)

    @Test fun `las claves se mantienen al reordenar o anadir titulos`() {
        val a = entry("movie", "a")
        val b = entry("series", "b")
        val original = stableMediaKeys(listOf(a, b))
        val reordered = stableMediaKeys(listOf(b, a, entry("movie", "c")))
        assertEquals(original[0], reordered[1])
        assertEquals(original[1], reordered[0])
    }

    @Test fun `tipos ids y duplicados no generan claves iguales`() {
        val entries = listOf(
            entry("a:b", "c"), entry("a", "b:c"),
            entry("movie", "tt1"), entry("series", "tt1"), entry("movie", "tt1"),
        )
        val keys = stableMediaKeys(entries)
        assertEquals(entries.size, keys.toSet().size)
        assertNotEquals(keys[2], keys[4])
    }
}
