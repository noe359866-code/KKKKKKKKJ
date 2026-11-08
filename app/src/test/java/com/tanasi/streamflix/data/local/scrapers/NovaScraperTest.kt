package com.tanasi.streamflix.data.local.scrapers

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NovaScraperTest {
    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `series require an episode without requesting the network`() {
        MockWebServer().use { server ->
            val scraper = NovaScraper(server.url("/").toString().trimEnd('/'), server.url("/").toString().trimEnd('/'), OkHttpClient())
            val response = Json.parseToJsonElement(scraper.scrape("""{"id":"novahd:1396","type":"series"}""")).jsonObject
            assertEquals("no_episode", response["meta"]!!.jsonObject.text("reason"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `streams keep language priority media type headers and subtitles`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"sources":[
              {"url":"https://cdn.test/english.m3u8","language":"English","quality":"720p","origAudio":true,"provider":"Orion"},
              {"url":"https://cdn.test/video?sig=abc","language":"Spanish","quality":"FHD","type":"hls","provider":"Swift"},
              {"url":"https://cdn.test/video?sig=abc","language":"Spanish"},
              {"url":"javascript:bad"}],
              "subtitles":[{"url":"https://cdn.test/sub.vtt","language":"Spanish","lang":"es"}]}"""))
            val base = server.url("/").toString().trimEnd('/')
            val scraper = NovaScraper(base, base, OkHttpClient())
            val response = Json.parseToJsonElement(scraper.scrape("""{"id":"novahd:1396","type":"series","season":1,"episode":2,"referer":"https://nova.test/","userAgent":"test-agent"}""")).jsonObject
            val request = server.takeRequest()
            assertEquals("/api/sources?tmdbId=1396&type=show&season=1&episode=2", request.path)
            assertEquals("https://nova.test/", request.getHeader("Referer"))
            val streams = response["streams"]!!.jsonArray
            assertEquals(2, streams.size)
            val first = streams[0].jsonObject
            assertEquals("https://cdn.test/video?sig=abc", first.text("url"))
            assertEquals("1080p", first.text("quality"))
            assertEquals("hls", first["behaviorHints"]!!.jsonObject.text("mediaType"))
            assertEquals("test-agent", first.text("userAgent"))
            assertEquals("es", response["meta"]!!.jsonObject["subtitles"]!!.jsonArray[0].jsonObject.text("lang"))
        }
    }

    @Test
    fun `title is translated using Cinemeta before requesting sources`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"metas":[{"id":"tt1345836","name":"The Dark Knight Rises"},{"id":"tt0468569","name":"The Dark Knight"}]}"""))
            server.enqueue(MockResponse().setBody("""{"meta":{"moviedb_id":155}}"""))
            server.enqueue(MockResponse().setBody("""{"sources":[],"subtitles":[]}"""))
            val base = server.url("/").toString().trimEnd('/')
            val scraper = NovaScraper(base, base, OkHttpClient())
            val response = Json.parseToJsonElement(scraper.scrape("""{"id":"novahd:The Dark Knight","type":"movie"}""")).jsonObject
            assertEquals("no_streams", response["meta"]!!.jsonObject.text("reason"))
            assertTrue(server.takeRequest().path!!.contains("search=The%20Dark%20Knight.json"))
            assertEquals("/meta/movie/tt0468569.json", server.takeRequest().path)
            assertEquals("/api/sources?tmdbId=155&type=movie", server.takeRequest().path)
        }
    }
}
