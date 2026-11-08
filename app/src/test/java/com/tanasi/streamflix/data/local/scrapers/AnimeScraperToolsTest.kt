package com.tanasi.streamflix.data.local.scrapers

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AnimeScraperToolsTest {
    private val tools = AnimeScraperTools()

    @Test
    fun `iframe extractor keeps order removes duplicates and skips hosts`() {
        val html = """<iframe src="https://streamwish.example/e/one"></iframe>
            <iframe src='//filemoon.example/e/two'></iframe>
            <iframe src='/embed/three?x=1'></iframe>
            <iframe src="https://streamwish.example/e/one"></iframe>
            <iframe src="https://ads.example/e/bad"></iframe>"""
        val rows = Json.parseToJsonElement(tools.iframeServersJson(html, "https://provider.example/show", """["ads.example"]""")).jsonArray
        assertEquals(listOf("https://streamwish.example/e/one", "https://filemoon.example/e/two", "https://provider.example/embed/three?x=1"), rows.map { it.jsonObject["url"]!!.jsonPrimitive.content })
        assertEquals("Streamwish.example", rows[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("", tools.iframeServersJson("<iframe src='../relative/file'></iframe>", "https://provider.example/show", "[]"))
    }
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun text(value: JsonObject, key: String) = value[key]!!.jsonPrimitive.content

    @Test
    fun `episode ids preserve provider, accents, seasons and url`() {
        val id = obj(tools.parseIdJson("anime:animeflv:Pokémon viaje:1:1180", "animeflv"))
        assertEquals("pokemon-viaje", text(id, "slug"))
        assertEquals("1", text(id, "season"))
        assertEquals("1180", text(id, "episode"))
        assertEquals("true", text(id, "explicit_episode"))
        val url = "https://anime.example/ver/one-piece-1180"
        val direct = obj(tools.parseIdJson(url, "animeflv"))
        assertEquals("one-piece", text(direct, "slug"))
        assertEquals("1180", text(direct, "episode"))
        assertEquals(url, text(direct, "url"))
        assertEquals("", tools.parseIdJson("https://example.test/ver/a b", "animeflv"))
    }

    @Test
    fun `payload fills only missing episode and preserves partial conversion`() {
        val initial = tools.parseIdJson("animeflv:one-piece", "animeflv")
        val ref = obj(tools.applyEpisodeJson(initial, """{"season":"2","episode":"3"}"""))
        assertEquals("2", text(ref, "season"))
        assertEquals("3", text(ref, "episode"))
        val explicit = tools.parseIdJson("animeflv:one-piece:7", "animeflv")
        assertEquals("7", text(obj(tools.applyEpisodeJson(explicit, """{"episode":3}""")), "episode"))
        val invalidSeason = obj(tools.applyEpisodeJson(initial, """{"episode":"3","season":"bad"}"""))
        assertEquals("3", text(invalidSeason, "episode"))
        assertTrue(invalidSeason["season"] is JsonNull)
        assertEquals("", tools.applyEpisodeJson(initial, """{"episode":"1_000"}"""))
        assertEquals("", tools.applyEpisodeJson(initial, """{"episode":92233720368547758070}"""))
    }

    @Test
    fun `reads JSON and loose JS literals without executing code`() {
        val html = """<script>var videos = [{server: 'sw', code: 'abc', title: 'A ] { B'}, {server: "voe",},];</script>"""
        val items = Json.parseToJsonElement(tools.literalJson(html, "videos")).jsonArray
        assertEquals(2, items.size)
        assertEquals("A ] { B", text(items[0].jsonObject, "title"))
        assertEquals("voe", text(items[1].jsonObject, "server"))
        assertEquals("", tools.literalJson("var videos = [untrustedFunction()];", "videos"))
        assertEquals("", tools.literalJson("var videos = ['unterminated]", "videos"))
        val nested = Json.parseToJsonElement(tools.literalJson("var episodes = [[1,'a'], [2, 'b']];", "episodes")).jsonArray
        assertEquals("2", nested[1].jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `matching ignores release noise and ranks exact titles without matching partial words`() {
        assertEquals(100, tools.matchScore("Street Fighter", "Street Fighter Latino HD 1080p", "street-fighter"))
        assertEquals(0, tools.matchScore("up", "Upload", "upload"))
        assertEquals(80, tools.matchScore("Dune", "Dune Parte Dos", "dune-parte-dos"))
        val match = obj(tools.bestMatchJson("Dune", """[{"title":"Dune Parte Dos","slug":"dune-parte-dos"},{"title":"Dune","slug":"dune"}]"""))
        assertEquals("100", text(match, "score"))
        assertEquals("1", text(match, "index"))
        val noHit = obj(tools.bestMatchJson("up", """[{"title":"Upload","slug":"upload"}]"""))
        assertEquals("0", text(noHit, "score"))
        assertEquals("-1", text(noHit, "index"))
    }

    @Test
    fun `base64 decoding only returns URLs and defers noncanonical inputs`() {
        assertEquals("https://streamtape.com/e/abc", tools.decodeMaybeBase64("aHR0cHM6Ly9zdHJlYW10YXBlLmNvbS9lL2FiYw=="))
        assertEquals("https://cdn.test/video", tools.decodeMaybeBase64(" https://cdn.test/video "))
        assertEquals("YWJj", tools.decodeMaybeBase64("YWJj"))
        assertNull(tools.decodeMaybeBase64("not canonical!"))
    }
}
