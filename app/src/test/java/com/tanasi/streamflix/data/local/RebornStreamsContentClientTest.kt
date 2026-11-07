package com.tanasi.streamflix.data.local

import com.streamflixreborn.streamflix.streams.ResolvedStream
import com.streamflixreborn.streamflix.streams.ResolvedSubtitle
import com.streamflixreborn.streamflix.streams.StreamRequest
import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamDto
import com.tanasi.streamflix.data.model.StreamsResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebornStreamsContentClientTest {
    private class FakeBase : ContentClient {
        var catalogCalls = 0
        var metaCalls = 0
        override suspend fun catalogs(baseUrl: String): List<CatalogDescriptor> {
            catalogCalls++
            return listOf(CatalogDescriptor(id = "cinemeta", type = "movie", name = "Cinemeta"))
        }
        override suspend fun catalog(
            baseUrl: String,
            type: String,
            id: String,
            search: String?,
            genre: String?,
            skip: Int?,
        ): RemoteCatalogResponse? {
            catalogCalls++
            return null
        }
        override suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta {
            metaCalls++
            return RemoteMeta(id = id, type = type, name = "Película de prueba", year = 2024, imdbId = "tt123")
        }
        override suspend fun streams(baseUrl: String, type: String, id: String): StreamsResponse =
            StreamsResponse(streams = listOf(StreamDto(name = "Python", url = "https://cdn.example/base.mp4")))
    }

    @Test
    fun `catalogs and meta are delegated without reborn catalog output`() = runTest {
        val base = FakeBase()
        var rebornCalls = 0
        val client = RebornStreamsContentClient(base) { rebornCalls++; emptyList() }
        assertEquals(1, client.catalogs("").size)
        assertEquals("Película de prueba", client.meta("", "movie", "tt123")?.name)
        assertEquals(0, rebornCalls)
        assertEquals(1, base.catalogCalls)
        assertEquals(1, base.metaCalls)
    }

    @Test
    fun `streams merge reborn media and dedupe by url`() = runTest {
        val base = FakeBase()
        val client = RebornStreamsContentClient(base) { request ->
            assertEquals("Película de prueba", request.title)
            assertEquals(2024, request.year)
            listOf(
                ResolvedStream(
                    url = "https://cdn.example/base.mp4",
                    name = "duplicate",
                    provider = "Reborn",
                ),
                ResolvedStream(
                    url = "https://cdn.example/reborn.m3u8",
                    name = "Reborn · Server",
                    provider = "Reborn",
                    quality = "1080p",
                    headers = mapOf("Referer" to "https://provider.example/"),
                    mediaType = "hls",
                    subtitles = listOf(ResolvedSubtitle("https://subs.example/es.srt", "Español", "es")),
                ),
            )
        }
        val response = client.streams("", "movie", "tt123")
        assertEquals(2, response.streams.size)
        assertEquals("https://cdn.example/base.mp4", response.streams[0].url)
        assertEquals("hls", response.streams[1].behaviorHints?.mediaType)
        assertEquals("https://provider.example/", response.streams[1].behaviorHints?.requestHeaders?.get("Referer"))
        assertEquals(1, response.subtitles.size)
        assertFalse(response.streams.any { it.url.contains("catalog") })
    }

    @Test
    fun `episode id is parsed for series streams`() = runTest {
        val base = FakeBase()
        var seen: StreamRequest? = null
        val client = RebornStreamsContentClient(base) { request -> seen = request; emptyList() }
        client.streams("", "series", "tt123:2:5")
        assertEquals(2, seen?.season)
        assertEquals(5, seen?.episode)
        assertEquals("series", seen?.type)
        assertTrue(seen?.isEpisode == true)
    }

    @Test
    fun `live tv does not call reborn vod scrapers`() = runTest {
        val base = FakeBase()
        var calls = 0
        val client = RebornStreamsContentClient(base) { calls++; emptyList() }
        val response = client.streams("", "tv", "channel-1")
        assertEquals(1, response.streams.size)
        assertEquals(0, calls)
    }
}
