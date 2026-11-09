package com.tanasi.streamflix.data

import android.os.SystemClock
import com.chaquo.python.PyException
import com.chaquo.python.Python
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tanasi.streamflix.data.local.ChaquopyRuntime
import com.tanasi.streamflix.data.local.EmbeddedContentClient
import com.tanasi.streamflix.data.local.NativeProviderScheduler
import com.tanasi.streamflix.util.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Sin backend: el APK conserva el catálogo local, pero no empaqueta resolvers de sitios. */
@RunWith(AndroidJUnit4::class)
class EmbeddedEngineSmokeTest {
    @Test(timeout = 30_000)
    fun bundledCatalogRuntimeRunsWithoutBundledScrapers(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(app)
        assertTrue("Fresh installations must be autonomous", settings.autonomous)
        val runtime = ChaquopyRuntime(app)
        val health = Json.parseToJsonElement(runtime.call("health", "{}")).jsonObject
        assertEquals("embedded", health["mode"]?.jsonPrimitive?.content)
        // El APK no empaqueta código de proveedores. Todo stream debe llegar
        // desde addons Stremio elegidos por la persona usuaria.
        val registry = Json.parseToJsonElement(
            app.assets.open("providers.json").bufferedReader().use { it.readText() }
        ).jsonObject
        val packaged = registry["providers"]!!.jsonArray
        val shipped = health["providers"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue("No scrapers should be bundled", packaged.isEmpty())
        assertTrue("No scraper runtime should be active", shipped.isEmpty())
        assertTrue(health["python"]!!.jsonPrimitive.content.startsWith("3.11."))
        val client = EmbeddedContentClient(runtime)
        val descriptors = client.catalogs("")
        // Sin la opción de metadatos la Home no publica ninguna fila: los
        // scrapers sólo resuelven streams.
        assertTrue(descriptors.isEmpty())
    }

    @Test(timeout = 30_000)
    fun engineAndCatalogHelpersRunInKotlinFromPython(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ChaquopyRuntime(app).call("health", "{}")
        val python = Python.getInstance()
        val engine = python.getModule("embedded_engine")
        assertEquals("es-419", engine.callAttr("normalize_language", "es_419").toString())
        assertEquals("Español (Latino)", engine.callAttr("language_name", "es-419").toString())
        assertEquals("vtt", engine.callAttr("subtitle_format", "https://cdn.invalid/sub.webvtt").toString())
        assertEquals("True", engine.callAttr("is_playable_stream", "https://cdn.invalid/video.mp4").toString())
        assertEquals("False", engine.callAttr("is_playable_stream", "https://cdn.invalid/player/embed").toString())
        assertEquals("1600000000", engine.callAttr("declared_expiry", "https://cdn.invalid/a?hdnea=st%3D1~exp%3D1600000000~acl%3D%2A").toString())
        val rawMeta = python.getModule("json").callAttr("loads", """{"id":"tt1","name":"Fixture","year":2020,"genres":["Drama"]}""")
        val meta = engine.callAttr("normalize_meta", rawMeta, "movie").asMap()
        assertEquals("Fixture", meta.entries.first { it.key.toString() == "name" }.value.toString())
        assertEquals("2020", meta.entries.first { it.key.toString() == "year" }.value.toString())
        val rawStream = python.getModule("json").callAttr("loads", """{"url":"https://cdn.invalid/v.mp4","name":"Fixture","behaviorHints":{"requestHeaders":{"cookie":"test=1"}}}""")
        val provider = python.getModule("json").callAttr("loads", """{"label":"Provider"}""")
        val stream = engine.callAttr("normalize_stream", rawStream, provider).asMap()
        assertEquals("Fixture", stream.entries.first { it.key.toString() == "name" }.value.toString())
        val headers = python.getModule("json").callAttr("loads", """{"cookie":"test=1"}""")
        val cleaned = engine.callAttr("normalize_stream_headers", headers).asMap()
        assertEquals("test=1", cleaned.entries.first { it.key.toString() == "Cookie" }.value.toString())
        val catalogs = python.getModule("_embedded_catalogs")
        val document = catalogs.callAttr("Document", "<h1>Title</h1><a href='/ver/show-12'><img data-src='/poster.jpg' alt='Show'></a>", "https://site.invalid/")
        assertEquals("Title", document.get("heading")!!.asList()[0].toString())
        val documentLink = document.get("links")!!.asList()[0].asMap()
        assertEquals("https://site.invalid/ver/show-12", documentLink.entries.first { it.key.toString() == "url" }.value.toString())
        assertEquals("https://site.invalid/poster.jpg", documentLink.entries.first { it.key.toString() == "poster" }.value.toString())
        val card = catalogs.callAttr("card_ref", "animeflv", "https://site.invalid/ver/show-12", "https://site.invalid/").asMap()
        assertEquals("animeflv:show:12", card.entries.first { it.key.toString() == "id" }.value.toString())
        val episodes = catalogs.callAttr("episode_videos", "gnula", "gnula:show", "show",
            "<a href='/show/season/2/episode/3'>Third</a>", "https://site.invalid/").asList()
        assertEquals("gnula:show:2:3", episodes[0].asMap().entries.first { it.key.toString() == "id" }.value.toString())
        val result = catalogs.callAttr("split_id", "animeflv:show:12", "anime").asList()
        assertEquals(listOf("animeflv:show", "1", "12"), result.map { it.toString() })
    }

    @Test(timeout = 30_000)
    fun scraperQualityIsResolvedInKotlinFromPython(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ChaquopyRuntime(app).call("health", "{}")
        val helper = Python.getInstance().getModule("_htmlkit")
        assertEquals("1080p", helper.callAttr("guess_quality", "Latino HD", "https://cdn.invalid/1080/master.m3u8").toString())
        assertEquals("720p", helper.callAttr("guess_quality", "Latino HD").toString())
        assertTrue(helper.callAttr("guess_quality", "https://hd.example/player") == null)
    }

    @Test(timeout = 30_000)
    fun hosterHelpersRunInKotlinFromPython(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ChaquopyRuntime(app).call("health", "{}")
        val hosters = Python.getInstance().getModule("_hosters")
        assertEquals("Streamwish", hosters.callAttr("hoster_label", "https://streamwish.example/e/1").toString())
        assertEquals("hls", hosters.callAttr("media_type_of_response", "application/x-mpegURL").toString())
        assertEquals("https://cdn.example/m3u8/abc?sig=123", hosters.callAttr("hls_variants", "https://cdn.example/play/abc?sig=123").asList()[0].toString())
        assertEquals("https://streamtape.example/get_video?id=1&token=abc&dl=1s", hosters.callAttr("_streamtape", "<div id='ideoooolink'>//streamtape.example/get_video?id=1</div><script>token='abc'</script>").asList()[0].toString())
        val okru = """<div data-module='OKVideo' data-options='{&quot;flashvars&quot;:{&quot;metadata&quot;:&quot;{\&quot;hlsManifestUrl\&quot;:\&quot;https://cdn.example/master\&quot;}&quot;}}'>"""
        assertEquals("https://cdn.example/master", hosters.callAttr("_okru", okru).asList()[0].toString())
    }

    @Test(timeout = 30_000)
    fun animeParsersRunInKotlinFromPython(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ChaquopyRuntime(app).call("health", "{}")
        val anime = Python.getInstance().getModule("_animekit")
        val ref = anime.callAttr("parse_id", "animeflv:one-piece:1:1180", "animeflv").asMap()
        assertEquals("1180", ref.entries.first { it.key.toString() == "episode" }.value.toString())
        val literal = anime.callAttr("objects", "var videos = [{server: 'sw', code: 'abc'}];", "videos").asList()
        assertEquals("sw", literal[0].asMap().entries.first { it.key.toString() == "server" }.value.toString())
        assertEquals("https://cdn.invalid/x", anime.callAttr("decode_maybe_base64", "aHR0cHM6Ly9jZG4uaW52YWxpZC94").toString())
        val iframe = anime.callAttr("iframe_servers", "<iframe src='https://cdn.invalid/embed/one'></iframe>", "https://site.invalid/").asList()[0].asMap()
        assertEquals("https://cdn.invalid/embed/one", iframe.entries.first { it.key.toString() == "url" }.value.toString())
        val hit = Python.getInstance().getModule("json").callAttr("loads", """{"title":"Street Fighter HD","slug":"street-fighter"}""")
        assertEquals("100", anime.callAttr("match_score", "Street Fighter", hit).toString())
    }

    @Test(timeout = 30_000)
    fun nativeProviderSchedulerCallsPythonAndReturnsPythonObjects(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ChaquopyRuntime(app).call("health", "{}")
        val python = Python.getInstance()
        val builtins = python.getModule("builtins")
        val json = python.getModule("json")
        val callback = builtins.callAttr("eval", "lambda provider, payload: {'streams': [{'url': payload['id']}], 'provider': provider['id']}")
        val provider = json.callAttr("loads", """{"id":"gnula"}""")
        val payload = json.callAttr("loads", """{"id":"fixture"}""")
        val scheduler = NativeProviderScheduler()
        try {
            val ticket = scheduler.submit("fixture", callback, provider, payload)
            assertTrue(ticket.awaitReady(5000))
            val wrapper = python.getModule("embedded_engine").callAttr("NativeProviderFuture", ticket)
            val result = wrapper.callAttr("result").asMap()
            assertEquals("gnula", result.entries.first { it.key.toString() == "provider" }.value.toString())
            assertEquals("fixture", result.entries.first { it.key.toString() == "streams" }.value.asList()[0]
                .asMap().entries.first { it.key.toString() == "url" }.value.toString())
        } finally {
            scheduler.shutdown()
        }
    }

    @Test(timeout = 30_000)
    fun engineUsesNativeDiskCacheFromPython(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ChaquopyRuntime(app).call("health", "{}")
        val python = Python.getInstance()
        val cache = python.getModule("embedded_engine").get("_engine")!!.get("cache")!!
        val value = python.getModule("json").callAttr("loads", """{"title":"Prueba"}""")
        cache.callAttr("put", "smoke:native-cache", value, 60)
        val restored = cache.callAttr("get", "smoke:native-cache").asMap()
        assertEquals("Prueba", restored.entries.first { it.key.toString() == "title" }.value.toString())
        cache.callAttr("clear")
    }

    @Test(timeout = 30_000)
    fun movieParsersRunInKotlinFromPython(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ChaquopyRuntime(app).call("health", "{}")
        val movies = Python.getInstance().getModule("_peliskit")
        val ref = movies.callAttr("parse_ref", "movie:gnula:spider-man:1:2", "gnula").asMap()
        assertEquals("spider-man", ref.entries.first { it.key.toString() == "slug" }.value.toString())
        assertEquals("2", ref.entries.first { it.key.toString() == "episode" }.value.toString())
        assertEquals("spider-man-2026", movies.callAttr("slug_variants", "Spider Man", "2026").asList()[0].toString())
        assertEquals("/show/1x03", movies.callAttr("candidate_episode_links", "<a href='/show/1x03'>Episodio</a>", 1, 3).asList()[0].toString())
        assertEquals("Title", movies.callAttr("fallback_queries", "Title (2026)").asList()[1].toString())
        val signals = Python.getInstance().getModule("json").callAttr("loads", """[{"url":"https://cdn.example/video","server":"Latino HD","media_type":"hls"}]""")
        val stream = movies.callAttr("build_streams", "Sitio", "Título", signals, "UA").asList()[0].asMap()
        assertEquals("720p", stream.entries.first { it.key.toString() == "quality" }.value.toString())
    }

    @Test(timeout = 30_000)
    fun tmdbLookupIsInstalledWithoutRequiringAnApiKey(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ChaquopyRuntime(app).call("health", "{}")
        val tmdb = Python.getInstance().getModule("_tmdb")
        assertEquals("{}", tmdb.get("_native_tmdb")!!.callAttr("searchJson", "Title", "", "movie", "", 1000).toString())
    }

    @Test(timeout = 30_000)
    fun packedPlayersAreUnpackedInKotlinFromPython(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ChaquopyRuntime(app).call("health", "{}")
        val htmlkit = Python.getInstance().getModule("_htmlkit")
        val packed = "eval(function(p,a,c,k,e,d){return p}('0 1=\"2://cdn.example/v.m3u8\";',3,3,'var|file|https'.split('|'),0,{}))"
        assertEquals("var file=\"https://cdn.example/v.m3u8\";", htmlkit.callAttr("unpack_js", packed).toString())
    }

    @Test(timeout = 30_000)
    fun pythonHttpUsesNativeTransportHeadersAndAnOverallTimeout(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        ChaquopyRuntime(app).call("health", "{}")
        val python = Python.getInstance()
        val helper = python.getModule("_htmlkit")
        val headers = python.getModule("json").callAttr("loads", """{"Referer":"https://fixture.invalid/"}""")
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("Señal autónoma"))
            val response = helper.callAttr("http_get", server.url("/fixture").toString(), headers, 2.0).asList()
            assertEquals("Señal autónoma", response[0].toString())
            assertEquals(server.url("/fixture").toString(), response[1].toString())
            assertEquals("https://fixture.invalid/", server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Referer"))

            server.enqueue(MockResponse().setBody("posted"))
            val formData = python.getModule("builtins").callAttr("bytes", "id=fixture", "utf-8")
            val form = python.getModule("urllib.request").callAttr("Request", server.url("/form").toString(), formData)
            val post = helper.callAttr("open_url", form, 2.0)
            assertEquals("posted", post.callAttr("read").callAttr("decode", "utf-8").toString())
            post.callAttr("close")
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", recorded.method)
            assertEquals("application/x-www-form-urlencoded", recorded.getHeader("Content-Type"))
            assertEquals("id=fixture", recorded.body.readUtf8())

            server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
            val missing = python.getModule("urllib.request").callAttr("Request", server.url("/missing").toString())
            try {
                helper.callAttr("open_url", missing, 2.0)
                fail("HTTP errors must remain urllib HTTPError")
            } catch (error: PyException) {
                assertTrue(error.message.orEmpty().contains("HTTPError"))
                assertTrue(error.message.orEmpty().contains("404"))
            }

            server.enqueue(MockResponse().setBody("Delayed body").setBodyDelay(3, TimeUnit.SECONDS))
            val start = SystemClock.elapsedRealtime()
            try {
                helper.callAttr("http_get", server.url("/slow").toString(), headers, 0.2)
                fail("The complete HTTP call must time out, not wait indefinitely for the body")
            } catch (error: PyException) {
                assertTrue(error.message.orEmpty().contains("Android HTTP request failed"))
            }
            assertTrue("Overall timeout should release the Python worker", SystemClock.elapsedRealtime() - start < 2_000)
        } finally {
            server.shutdown()
        }
    }

}
