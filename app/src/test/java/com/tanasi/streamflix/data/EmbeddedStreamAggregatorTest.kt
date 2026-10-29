package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.embedded.EmbeddedStreamAggregator
import com.tanasi.streamflix.data.embedded.ProviderConfig
import com.tanasi.streamflix.data.embedded.ScraperResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedStreamAggregatorTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun provider(id: String, priority: Int) = ProviderConfig(id = id, script = "$id.py", priority = priority)
    private fun obj(raw: String) = json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `ordena por prioridad y calidad, deduplica y conserva cabeceras`() {
        val results = listOf(
            ScraperResult(
                provider("dos", 2), ok = true,
                streams = listOf(obj("""{"url":"https://cdn.example/b.m3u8","quality":"1080p","referer":"https://b.example/"}""")),
            ),
            ScraperResult(
                provider("uno", 1), ok = true,
                streams = listOf(
                    obj("""{"url":"https://cdn.example/a.m3u8","quality":"720p"}"""),
                    obj("""{"url":"https://cdn.example/a4k.m3u8","quality":"4K","userAgent":"UA/1"}"""),
                    obj("""{"url":"https://cdn.example/a.m3u8","quality":"1080p"}"""),
                ),
            ),
        )
        val response = EmbeddedStreamAggregator.aggregate(results, isLive = false, cacheMaxAge = 21_600)
        assertEquals(3, response.streams.size)
        assertEquals("uno", response.streams[0].provider)
        assertEquals("4K", response.streams[0].quality)
        assertEquals("720p", response.streams[1].quality)
        assertEquals("dos", response.streams[2].provider)
        assertEquals("UA/1", response.streams[0].behaviorHints?.requestHeaders?.get("User-Agent"))
        assertEquals("https://b.example/", response.streams[2].behaviorHints?.requestHeaders?.get("Referer"))
    }

    @Test
    fun `descarta proveedores fallidos y urls no reproducibles`() {
        val results = listOf(
            ScraperResult(provider("malo", 1), ok = false, streams = listOf(obj("""{"url":"https://x.example/a.m3u8"}"""))),
            ScraperResult(provider("bueno", 2), ok = true, streams = listOf(obj("""{"url":"https://x.example/embed"}"""))),
        )
        assertTrue(EmbeddedStreamAggregator.aggregate(results, false, 120).streams.isEmpty())
    }

    @Test
    fun `parsea respuesta del puente python`() {
        val result = EmbeddedStreamAggregator.parseBridgeResult(
            provider("gnula", 11),
            obj("""{"ok":true,"streams":[{"url":"https://x.example/v.mp4"}],"meta":{"site":"gnula"}}"""),
        )
        assertTrue(result.ok)
        assertEquals(1, result.streams.size)
        assertEquals("gnula", result.meta?.get("site").toString().trim('"'))
    }
}
