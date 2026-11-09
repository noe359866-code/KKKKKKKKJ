package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.model.StremioAddon
import com.tanasi.streamflix.data.model.StremioManifestCatalog
import com.tanasi.streamflix.data.model.StremioResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPlatformsTest {
    @Test
    fun `solo expone plataformas publicadas por addons habilitados`() {
        val enabled = StremioAddon(
            id = "catalog.addon",
            name = "Streaming Catalogs",
            manifestUrl = "https://catalog.example/manifest.json",
            baseUrl = "https://catalog.example",
            resources = listOf(StremioResource("catalog")),
            catalogs = listOf(
                StremioManifestCatalog(type = "movie", id = "nfx", name = "Netflix"),
                StremioManifestCatalog(type = "series", id = "netflix", name = "Netflix Series"),
                StremioManifestCatalog(type = "movie", id = "disney_plus", name = "Disney Plus"),
                StremioManifestCatalog(type = "movie", id = "top", name = "Popular"),
            ),
        )
        val disabled = enabled.copy(
            id = "disabled.addon",
            enabled = false,
            catalogs = listOf(StremioManifestCatalog(type = "movie", id = "prime", name = "Prime")),
        )

        val available = StreamingPlatforms.availableFrom(listOf(enabled, disabled))

        assertEquals(listOf("netflix", "disney-plus"), available.map { it.platform.id })
        assertEquals(2, available.first().catalogs.size)
        assertTrue(available.first().catalogs.any { it.type == "movie" })
        assertTrue(available.first().catalogs.any { it.type == "series" })
    }

    @Test
    fun `respeta los tipos autorizados por el recurso catalog`() {
        val addon = StremioAddon(
            id = "movies.only",
            name = "Movies only",
            manifestUrl = "https://movies.example/manifest.json",
            baseUrl = "https://movies.example",
            resources = listOf(StremioResource("catalog", types = listOf("movie"))),
            catalogs = listOf(
                StremioManifestCatalog(type = "movie", id = "netflix"),
                StremioManifestCatalog(type = "series", id = "netflix"),
            ),
        )

        val netflix = StreamingPlatforms.availableFrom(listOf(addon)).single()

        assertEquals(listOf("movie"), netflix.catalogs.map { it.type })
    }

    @Test
    fun `oculta servicios sin manifiesto de catalogo compatible`() {
        val noCatalogPermission = StremioAddon(
            id = "stream.only",
            name = "Streams only",
            manifestUrl = "https://streams.example/manifest.json",
            baseUrl = "https://streams.example",
            resources = listOf(StremioResource("stream")),
            catalogs = listOf(StremioManifestCatalog(type = "movie", id = "netflix")),
        )

        assertTrue(StreamingPlatforms.availableFrom(listOf(noCatalogPermission)).isEmpty())
    }
}
