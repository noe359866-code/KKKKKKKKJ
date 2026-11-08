package com.tanasi.streamflix.data.local

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeEngineToolsTest {
    private val tools = NativeEngineTools()

    @Test fun `languages and formats retain regions and aliases`() {
        assertEquals("es-419", tools.normalizeLanguage("es_419"))
        assertEquals("es-٤١٩", tools.normalizeLanguage("es-٤١٩"))
        assertEquals("es", tools.normalizeLanguage("Español"))
        assertEquals("Español (Latino)", tools.languageName("es-419"))
        assertEquals("Portugués (Brasil)", tools.languageName("pt-br"))
        assertEquals("", tools.normalizeLanguage("subtítulos en español"))
        assertEquals("vtt", tools.subtitleFormat("https://cdn/sub.WEBVTT?x=1"))
        assertEquals("", tools.subtitleFormat("https://cdn/movie.mp4"))
    }

    @Test fun `subtitle fields are normalized and invalid media rejected`() {
        val result = Json.parseToJsonElement(tools.normalizeSubtitleJson(
            """{"src":" https://cdn.invalid/sub.srt ","language":"SPA"}""", """{"label":"Proveedor"}"""
        )).jsonObject
        assertEquals("https://cdn.invalid/sub.srt", result["url"]!!.jsonPrimitive.content)
        assertEquals("es", result["lang"]!!.jsonPrimitive.content)
        assertEquals("Español", result["label"]!!.jsonPrimitive.content)
        assertEquals("srt", result["format"]!!.jsonPrimitive.content)
        assertEquals("Proveedor", result["provider"]!!.jsonPrimitive.content)
        assertEquals("null", tools.normalizeSubtitleJson("""{"url":"https://cdn.invalid/movie.mp4"}""", """{"label":"P"}"""))
        assertEquals("", tools.normalizeSubtitleJson("""{"url":42}""", "{}"))
        assertEquals("", tools.normalizeSubtitleJson("""{"url":"https://cdn.invalid/x","label":"😀"}""", "{}"))
    }

    @Test fun `catalog descriptors preserve ordering and can be disabled`() {
        assertTrue(Json.parseToJsonElement(tools.catalogDescriptorsJson(false)).jsonObject["catalogs"]!!.jsonArray.isEmpty())
        val rows = Json.parseToJsonElement(tools.catalogDescriptorsJson(true)).jsonObject["catalogs"]!!.jsonArray
        assertEquals(6, rows.size)
        assertEquals("cinemeta_top", rows.first().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Anime popular", rows.last().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("5", rows.last().jsonObject["order"]!!.jsonPrimitive.content)
    }

    @Test fun `metadata preserves optional fields and episode ordering`() {
        val result = Json.parseToJsonElement(tools.normalizeMetadataJson(
            """{"id":" tt123 ","name":"Title","year":2020,"imdbRating":"8.5","genres":["Drama",null,""],"poster":"https://site/p.jpg","videos":[{"season":2,"episode":3,"name":"Third"},{"season":"1","episode":"02"}]}""", "series"
        )).jsonObject
        assertEquals("tt123", result["id"]!!.jsonPrimitive.content)
        assertEquals("2020", result["year"]!!.jsonPrimitive.content)
        assertEquals("8.5", result["rating"]!!.jsonPrimitive.content)
        assertEquals("Drama", result["genres"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("tt123:1:2", result["videos"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Third", result["videos"]!!.jsonArray[1].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("null", tools.normalizeMetadataJson("{}", "movie"))
        assertEquals("", tools.normalizeMetadataJson("""{"id":"tt1","videos":[{"season":true,"episode":1}]}""", "series"))
        assertEquals("", tools.normalizeMetadataJson("""{"id":"tt1","releaseInfo":"٢٠٢٠"}""", "movie"))
        assertEquals("", tools.normalizeMetadataJson("""{"id":"tt1","rating":"0x1.0p1"}""", "movie"))
        assertEquals("", tools.normalizeMetadataJson("""{"id":"tt1","videos":[{"season":"\u00001","episode":1}]}""", "series"))
    }

    @Test fun `stream deduplication uses URL without fragment and retains indexes`() {
        assertEquals("[1,3]", tools.uniqueStreamIndicesJson(
            """["https://a#one","https://b#x","https://b#y","https://c"]""", """["https://a"]"""
        ))
        assertEquals("[]", tools.uniqueStreamIndicesJson("[]", "[]"))
        assertEquals("", tools.uniqueStreamIndicesJson("[42]", "[]"))
    }

    @Test fun `stream parts preserve names quality and canonical headers`() {
        val result = Json.parseToJsonElement(tools.normalizeStreamPartsJson(
            """{"name":"Source","title":"Episode","quality":"1080p"}""", """{"label":"P"}""",
            """{"referer":"https://site","cookie":"x=1","bad key":"x"}"""
        )).jsonObject
        assertEquals("Source", result["name"]!!.jsonPrimitive.content)
        assertEquals("Episode", result["title"]!!.jsonPrimitive.content)
        assertEquals("1080p", result["quality"]!!.jsonPrimitive.content)
        assertEquals("x=1", result["headers"]!!.jsonObject["Cookie"]!!.jsonPrimitive.content)
        assertEquals("", tools.normalizeStreamPartsJson("""{"name":123}""", """{"label":"P"}""", "{}"))
        assertEquals("", tools.normalizeStreamPartsJson("{}", "{}", "{}"))
        assertEquals("", tools.normalizeStreamPartsJson("{}", """{"label":"P"}""", """{"X":3}"""))
    }

    @Test fun `provider selection preserves explicit requests and registry order`() {
        val names = """["gnula","animeflv","cuevana","jkanime"]"""
        assertEquals("[2]", tools.selectProviderIndicesJson(names, "cuevana", """["animeflv"]"""))
        assertEquals("[1,3]", tools.selectProviderIndicesJson(names, "unknown", """["jkanime","animeflv"]"""))
        assertEquals("[]", tools.selectProviderIndicesJson(names, "unknown", "[]"))
        assertEquals("", tools.selectProviderIndicesJson("[1]", "x", "[]"))
    }

    @Test fun `signed url expiry uses earliest supported value`() {
        val query = """{"expires":["1700000000000"],"hdnea":["st=1~exp=1600000000~acl=*"],"exp":["3600"]}"""
        assertEquals("1600000000", tools.declaredExpiryJson(query, "1800000000"))
        assertEquals("null", tools.declaredExpiryJson("{}", "null"))
        assertEquals("", tools.declaredExpiryJson("""{"exp":["١٧٠٠٠٠٠٠٠٠"]}""", "null"))
        assertEquals("", tools.declaredExpiryJson("""{"x":["exp=1700000000\r"]}""", "null"))
    }

    @Test fun `episode links retain occurrence order and reject out of range`() {
        val links = """[{"url":"https://site/fixture/s1","title":"First","attrs":{"data-s":"1","data-e":"2"}},{"url":"https://site/fixture/season/2/episode/3","title":"Next","attrs":{}},{"url":"https://site/fixture/4/5/","title":"5","attrs":{}},{"url":"https://site/fixture/x","title":"Show 2x08","attrs":{}},{"url":"https://site/fixture/other","title":"Out","attrs":{"data-s":"101","data-e":"2"}}]"""
        assertEquals("[[1,2,0],[2,3,1],[4,5,2],[2,8,3]]", tools.catalogEpisodeLinksJson(links, "[]", "fixture", false))
        val animeLinks = """[{"url":"https://site/ver/x-2","title":"two","attrs":{}},{"url":"https://site/ver/y-3","title":"three","attrs":{}}]"""
        val refs = """[{"slug":"x","episode":2},{"slug":"y","episode":3}]"""
        assertEquals("[[1,2,0]]", tools.catalogEpisodeLinksJson(animeLinks, refs, "x", true))
        val videos = Json.parseToJsonElement(tools.catalogEpisodeVideosJson("""[[2,3,"Third"],[1,12,"First"]]""", "gnula:fixture")).jsonArray
        assertEquals("gnula:fixture:1:12", videos[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Third", videos[1].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("", tools.catalogEpisodeVideosJson("""[[2.5,1,"x"]]""", "show"))
        assertEquals("", tools.catalogEpisodeLinksJson(links, "[]", "fixturé", false))
        assertEquals("", tools.catalogEpisodeLinksJson("""[{"url":"https://site/fixture","title":"Capítulo","attrs":{}}]""", "[]", "fixture", false))
    }

    @Test fun `catalog paths map to the same provider ids`() {
        fun card(provider: String, path: String, anime: Boolean) =
            Json.parseToJsonElement(tools.catalogCardRefJson(provider, path, "series", anime))
        assertEquals("animeflv:one-piece:12", card("animeflv", "ver/one-piece-00012", true).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("jkanime:show:3", card("jkanime", "show/3", true).jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("movie", card("gnula", "peliculas/example/extra", false).jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("series", card("gnula", "serie/example", false).jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, card("gnula", "pelicula/buscar", false))
        assertEquals(JsonNull, card("animeflv", "ver/example-123456", true))
        assertEquals("", tools.catalogCardRefJson("gnula", "pelicula/niño", "movie", false))
    }

    @Test fun `request headers retain allowed values and canonicalize names`() {
        val headers = Json.parseToJsonElement(tools.normalizeHeadersJson(
            """{"referer":"https://site","Referer":"https://other","COOKIE":"a=b","X-Token":"yes","Bad Header":"bad","X-Injection":"a\nb","Empty":"","Missing":null}"""
        )).jsonObject
        assertEquals("https://other", headers["Referer"]!!.jsonPrimitive.content)
        assertEquals("a=b", headers["Cookie"]!!.jsonPrimitive.content)
        assertEquals("yes", headers["X-Token"]!!.jsonPrimitive.content)
        assertEquals(3, headers.size)
        assertEquals("", tools.normalizeHeadersJson("""{"key":42}"""))
        assertEquals("", tools.normalizeHeadersJson("""{"ñ":"value"}"""))
    }

    @Test fun `playability rules prioritize explicit media but block pages`() {
        assertTrue(tools.playableDirect("/embed/file.mp4", ""))
        assertTrue(tools.playableDirect("/watch", "src=movie.M3U8&x=1"))
        assertFalse(tools.playableDirect("/player/one", ""))
        assertFalse(tools.playablePage("/player/one", "hls"))
        assertFalse(tools.playablePage("/video.html", "mp4"))
        assertTrue(tools.playablePage("/watch", ""))
    }

    @Test fun `catalog ids parse with python fallback for unsupported integers and unicode`() {
        assertEquals("[\"show\",1,12]", tools.splitCatalogIdJson("show:1:12", "series", false))
        assertEquals("[\"animeflv:show\",1,2]", tools.splitCatalogIdJson("animeflv:show:2", "anime", true))
        assertEquals("[\"animeflv:show:2\",null,null]", tools.splitCatalogIdJson("animeflv:show:2", "series", true))
        assertEquals("[\"a:\",null,null]", tools.splitCatalogIdJson("a:", "anime", true))
        assertEquals("", tools.splitCatalogIdJson("show:1:999999999999", "series", false))
        assertEquals("", tools.splitCatalogIdJson("show:1:٢", "series", false))
    }

    @Test fun `merge deduplicates and prefers spanish while keeping indexes`() {
        val rows = """[{"url":"en","lang":"en"},{"url":"es","lang":"es-419"},{"url":"en","lang":"es"},{"url":"pt","lang":"pt"}]"""
        val indexes = Json.parseToJsonElement(tools.mergeSubtitleIndicesJson(rows)).jsonArray.map { it.jsonPrimitive.int }
        assertEquals(listOf(1, 0, 3), indexes)
        assertEquals("", tools.mergeSubtitleIndicesJson("""[{"url":"x","lang":4}]"""))
    }
}
