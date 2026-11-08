package com.tanasi.streamflix.data.local.scrapers

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TmdbLookupTest {
    @Test
    fun `find prefers requested bucket then falls back to other type`() {
        var requested = ""
        var timeout = 0
        val lookup = TmdbLookup("https://tmdb.test/3") { url, ms ->
            requested = url; timeout = ms
            """{"movie_results":[],"tv_results":[{"name":" Serie ","original_name":"Original","first_air_date":"2024-01-02","id":17,"overview":" Sinopsis "}]}"""
        }
        val result = Json.parseToJsonElement(lookup.findByImdbJson("tt1234567", "movie", "secret", 9000)).jsonObject
        assertEquals("https://tmdb.test/3/find/tt1234567?external_source=imdb_id&language=es-MX&api_key=secret", requested)
        assertEquals(5000, timeout)
        assertEquals("Serie", result["title"]!!.jsonPrimitive.content)
        assertEquals("Original", result["original_title"]!!.jsonPrimitive.content)
        assertEquals("2024", result["year"]!!.jsonPrimitive.content)
        assertEquals("tv", result["media_type"]!!.jsonPrimitive.content)
        assertEquals(17, result["tmdb_id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `search encodes title and year and skips empty entries`() {
        var requested = ""
        val lookup = TmdbLookup("https://tmdb.test/3") { url, _ ->
            requested = url
            """{"results":[null,{"title":""},{"title":" Película ","release_date":"2025-09-01","id":3}]}"""
        }
        val result = Json.parseToJsonElement(lookup.searchJson("¿Qué * tal?", "2025", "movie", "abc", 3000)).jsonObject
        assertEquals("https://tmdb.test/3/search/movie?query=%C2%BFQu%C3%A9+%2A+tal%3F&language=es-MX&include_adult=false&year=2025&api_key=abc", requested)
        assertEquals("Película", result["title"]!!.jsonPrimitive.content)
        assertEquals("", result["original_title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no key avoids network and unusual data asks for legacy fallback`() {
        var calls = 0
        val lookup = TmdbLookup("https://tmdb.test/3") { _, _ -> calls++; """{"results":[{"title":42}]}""" }
        assertEquals("{}", lookup.searchJson("Title", "", "movie", "", 1000))
        assertEquals(0, calls)
        assertEquals("", lookup.searchJson("Title", "", "movie", "abc", 1000))
        assertEquals(1, calls)
        assertEquals("", lookup.searchJson("Title", "", "movie", "bad&key", 1000))
        assertEquals(1, calls)
    }
}
