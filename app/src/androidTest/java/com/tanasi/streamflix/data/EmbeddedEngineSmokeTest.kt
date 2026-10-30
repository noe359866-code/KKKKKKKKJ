package com.tanasi.streamflix.data

import android.os.SystemClock
import com.chaquo.python.PyException
import com.chaquo.python.Python
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tanasi.streamflix.data.local.ChaquopyRuntime
import com.tanasi.streamflix.data.local.EmbeddedContentClient
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No backend and no Internet: proves native Python and every bundled script load on Android. */
@RunWith(AndroidJUnit4::class)
class EmbeddedEngineSmokeTest {
    @Test
    fun bundledPythonAndScrapersRunOnDeviceWithoutAServer() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(app)
        assertTrue("Fresh installations must be autonomous", settings.autonomous)
        val runtime = ChaquopyRuntime(app)
        val health = Json.parseToJsonElement(runtime.call("health", "{}")).jsonObject
        assertEquals("embedded", health["mode"]?.jsonPrimitive?.content)
        // La lista sale del registro empaquetado: añadir un proveedor no debe
        // obligar a tocar el test, pero sí tiene que llegar al APK.
        val registry = Json.parseToJsonElement(
            app.assets.open("providers.json").bufferedReader().use { it.readText() }
        ).jsonObject
        val expected = registry["providers"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["enabled"]?.jsonPrimitive?.content != "false" }
            .map { it["id"]!!.jsonPrimitive.content }
            .filterNot { it.startsWith("demo") }
            .toSet()
        val shipped = health["providers"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(expected, shipped)
        assertTrue(expected.containsAll(setOf("livelive24", "damitv", "ntv", "streamcorner")))
        assertTrue(health["python"]!!.jsonPrimitive.content.startsWith("3.11."))
        val client = EmbeddedContentClient(runtime)
        val descriptors = client.catalogs("")
        assertTrue(descriptors.isNotEmpty())
        assertFalse(descriptors.any { it.source == "cinemeta" })
        // Los scrapers no publican filas: sólo resuelven streams. La Home lleva
        // canales abiertos, los fijados a mano, los que recoge `_liveshelf` y la
        // agenda deportiva.
        assertEquals(listOf("iptvorg", "bundled_live", "canales", "deportes"), descriptors.map { it.id })
        assertEquals("sports", descriptors.last().layout)
        assertTrue(descriptors.none { it.id in health["providers"]!!.jsonArray.map { p -> p.jsonPrimitive.content } })
        val channels = client.catalog("", "tv", "bundled_live")!!.metas
        assertTrue(channels.isNotEmpty())
        assertTrue(channels.all { it.type == "tv" })
    }

    /**
     * El requisito que rompió la versión anterior: la app tiene que reproducir
     * algo sin cuenta, sin addon y sin red de proveedores. La fila `iptvorg`
     * devuelve canales abiertos y cada id se resuelve a una URL HLS directa,
     * sin pasar por ningún scraper. Aquí se corre sin Internet: el motor cae a
     * la copia empaquetada y aun así hay fuentes reproducibles.
     */
    @Test
    fun openChannelsPlayWithNoProviderAndNoNetwork() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val client = EmbeddedContentClient(ChaquopyRuntime(app))

        val rows = client.catalogs("")
        assertTrue("La fila de canales abiertos debe existir", rows.any { it.id == "iptvorg" })
        assertEquals("iptvorg", rows.first { it.id == "iptvorg" }.id)

        val channels = client.catalog("", "tv", "iptvorg")!!.metas
        assertTrue("Sin red debe quedar la lista empaquetada", channels.isNotEmpty())
        assertTrue(channels.all { it.type == "tv" })
        assertTrue(channels.all { it.id.startsWith("iptvorg:") })

        val streams = client.streams("", "tv", channels.first().id)!!.streams
        assertTrue("Cada canal abierto tiene que dar al menos una fuente", streams.isNotEmpty())
        val url = streams.first().url
        assertTrue(url.startsWith("https://") || url.startsWith("http://"))
        assertTrue("Un canal en vivo se marca como tal", streams.first().isLive)
        assertNotNull(streams.first().toSource(0))
    }

    @Test
    fun pythonHttpUsesNativeTransportHeadersAndAnOverallTimeout() = runBlocking {
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
