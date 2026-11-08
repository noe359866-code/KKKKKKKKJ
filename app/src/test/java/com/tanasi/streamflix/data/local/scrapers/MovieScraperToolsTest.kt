package com.tanasi.streamflix.data.local.scrapers

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MovieScraperToolsTest {
    private val tools = MovieScraperTools()
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun text(value: JsonObject, key: String) = value[key]!!.jsonPrimitive.content
    private fun arr(value: String) = Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `movie ids preserve titles imdb and explicit episodes`() {
        val parsed = obj(tools.parseRefJson("movie:gnula:Spider-Man-de-regreso:1:2.json", "gnula"))
        assertEquals("spider-man-de-regreso", text(parsed, "slug"))
        assertEquals("Spider Man de Regreso", text(parsed, "title_hint"))
        assertEquals("1", text(parsed, "season"))
        assertEquals("2", text(parsed, "episode"))
        assertEquals("true", text(parsed, "explicit_episode"))
        val imdb = obj(tools.parseRefJson("gnula:TT0145487:1:2", "gnula"))
        assertEquals("tt0145487", text(imdb, "imdb"))
        assertTrue(imdb["season"] is JsonNull)
        assertTrue(imdb["episode"] is JsonNull)
        val episode = obj(tools.parseRefJson("repelis:one-piece-2x03", "repelis"))
        assertEquals("one-piece", text(episode, "slug"))
        assertEquals("2", text(episode, "season"))
        assertEquals("3", text(episode, "episode"))
    }

    @Test
    fun `url ids keep the original url and paths while malformed urls defer`() {
        val parsed = obj(tools.parseRefJson("https://provider.example/ver/pelicula/?sig=abc", "gnula"))
        assertEquals("https://provider.example/ver/pelicula/?sig=abc", text(parsed, "url"))
        assertEquals("pelicula", text(parsed, "slug"))
        assertEquals("true", text(parsed, "explicit_url"))
        assertEquals("", tools.parseRefJson("https://provider.example/ver/mal espacio", "gnula"))
        assertEquals("", tools.parseRefJson("gnula:serie:١:2", "gnula"))
    }

    @Test
    fun `episode links include numeric and text variants in document order`() {
        val html = """<a href="/ver/one-piece-1180">capítulo</a>
            <a href='/serie/ejemplo/1x05'>otro</a><a href='/custom'>T1 <b>E5</b></a>
            <a href='/serie/ejemplo/1x05'>duplicado</a><a href='/ver/other-5'>Capítulo</a>"""
        assertEquals(listOf("/serie/ejemplo/1x05", "/custom", "/ver/other-5"), arr(tools.candidateEpisodeLinksJson(html, "1", "5")))
        assertEquals(listOf("/ver/one-piece-1180"), arr(tools.candidateEpisodeLinksJson(html, "1", "1180")))
        assertEquals("", tools.candidateEpisodeLinksJson(html, "1", "9".repeat(101)))
    }

    @Test
    fun `direct page plan prioritizes episode paths and caps requests`() {
        val plan = Json.parseToJsonElement(tools.directPagePlanJson(
            """["first","second"]""", """["ep1","ep2","ep3","other","ficha"]""", """["anime","ficha"]"""
        )).jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
        assertEquals(listOf("ep1", "ep2", "ep3", "ficha", "anime", "other", "ep1"), plan.map { it[1] })
        assertEquals("second", plan.last()[0])
        assertEquals("", tools.directPagePlanJson("[1]", "[]", "[]"))
    }

    @Test
    fun `path templates preserve numbering escaping and form encoded query`() {
        val values = """{"slug":"show","season":1,"episode":3,"query":"¿Qué * tal?"}"""
        assertEquals("/serie/show/1x03?q=%C2%BFQu%C3%A9+%2A+tal%3F", tools.fillTemplate("/serie/{slug}/{season}x{episode2}?q={query}", values))
        assertEquals("{show}/None/", tools.fillTemplate("{{{slug}}}/{season}/{season2}", """{"slug":"show","season":null}"""))
        assertEquals("", tools.fillTemplate("{missing}", values))
        assertEquals("", tools.fillTemplate("{episode:02d}", values))
        assertEquals("", tools.fillTemplate("{episode2}", """{"episode":"١"}"""))
    }

    @Test
    fun `fallback search drops years and long tails without repeats`() {
        assertEquals(listOf("Spider-Man: No Way Home (2021)", "Spider-Man: No Way Home"),
            arr(tools.fallbackQueriesJson("  Spider-Man:  No Way Home (2021)  ", 2)))
        assertEquals(listOf("One Two Three Four Five", "One Two Three Four"),
            arr(tools.fallbackQueriesJson("One Two Three Four Five", 4)))
        assertEquals(listOf("Title 2026"), arr(tools.fallbackQueriesJson("Title 2026", 0)))
        assertEquals(emptyList<String>(), arr(tools.fallbackQueriesJson("   ", 2)))
    }

    @Test
    fun `slug variants keep priority and omit duplicates`() {
        assertEquals(listOf("spider-man-2021-2026", "spider-man-2021", "spider-man-2026", "spider-man"),
            arr(tools.slugVariantsJson("Spider-Man (2021)", "2026")))
        assertEquals(listOf("la-pelicula-latino", "la-pelicula"), arr(tools.slugVariantsJson("La Película Latino", "")))
        assertEquals(emptyList<String>(), arr(tools.slugVariantsJson("", "2026")))
        assertEquals("", tools.slugVariantsJson("Title", "٢٠٢٦"))
    }
}
