package com.tanasi.streamflix.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsEnrichmentTest {
    @Test fun `metadatos vacios no borran arte y datos validos`() {
        val original = MediaEntry(
            id = "id", type = "movie", title = "Título", poster = "https://example.com/poster",
            backdrop = "https://example.com/backdrop", runtime = "90 min", provider = "Proveedor",
        )
        val result = original.enrichedBy(RemoteMeta(
            id = "id", name = "", poster = "  ", background = "", runtime = " ", sourceName = " ",
        ))
        assertEquals(original, result)
    }

    @Test fun `arte nuevo se normaliza sin cambiar id ni posicion`() {
        val original = MediaEntry(id = "id", type = "movie", title = "Título", resumePositionMs = 123L)
        val result = original.enrichedBy(RemoteMeta(id = "other", name = "Título nuevo", poster = "//example.com/poster"))
        assertEquals("https://example.com/poster", result.poster)
        assertEquals("id", result.id)
        assertEquals(123L, result.resumePositionMs)
    }
}
