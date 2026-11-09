package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.extension.nuvio.NuvioPlaybackContext
import com.tanasi.streamflix.data.extension.nuvio.NuvioPluginEngine
import com.tanasi.streamflix.data.extension.nuvio.NuvioPluginScriptExecutor
import com.tanasi.streamflix.data.extension.nuvio.NuvioPluginScript
import com.tanasi.streamflix.data.extension.nuvio.NuvioPluginStream
import com.tanasi.streamflix.data.extension.nuvio.NuvioPluginSubtitle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioPluginEngineTest {
    @Test fun `engine translates Nuvio streams subtitles and per-provider failures`() = runBlocking {
        var receivedType: String? = null
        var receivedEpisode: Pair<Int?, Int?>? = null
        val executor = NuvioPluginScriptExecutor { _, tmdbId, mediaType, season, episode, scraperId, _ ->
            assertEquals("603", tmdbId)
            if (scraperId == "broken") error("provider failed")
            receivedType = mediaType
            receivedEpisode = season to episode
            listOf(
                NuvioPluginStream(
                    title = "1080p",
                    url = "https://cdn.example/video.m3u8",
                    quality = "1080p",
                    headers = mapOf("Referer" to "https://source.example/"),
                    subtitles = listOf(
                        NuvioPluginSubtitle(
                            id = "es",
                            url = "https://cdn.example/es.vtt",
                            lang = "es",
                            addonName = "Proveedor A",
                        ),
                    ),
                ),
            )
        }
        val engine = NuvioPluginEngine(executor)
        val result = engine.streams(
            providers = listOf(
                NuvioPluginScript(id = "working", name = "Proveedor A", code = "fixture"),
                NuvioPluginScript(id = "broken", name = "Proveedor B", code = "fixture"),
                NuvioPluginScript(id = "disabled", name = "Deshabilitado", code = "fixture", enabled = false),
            ),
            context = NuvioPlaybackContext("603", "series", season = 2, episode = 4),
        )

        assertEquals("tv", receivedType)
        assertEquals(2 to 4, receivedEpisode)
        assertEquals(1, result.streams.size)
        assertEquals("Proveedor A", result.streams.single().provider)
        assertEquals("https://source.example/", result.streams.single().behaviorHints?.requestHeaders?.get("Referer"))
        assertEquals(1, result.subtitles.size)
        assertEquals("Proveedor A", result.subtitles.single().provider)
        assertEquals(setOf("ok", "error"), result.providers.map { it.status }.toSet())
        assertTrue(result.providers.none { it.id == "disabled" })
    }
}
