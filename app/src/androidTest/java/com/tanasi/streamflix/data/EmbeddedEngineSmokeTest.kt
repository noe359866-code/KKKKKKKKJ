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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No backend and no Internet: proves native Python and all twelve scripts load on Android. */
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
        assertEquals(12, health["providers"]?.jsonArray?.size)
        assertTrue(health["python"]!!.jsonPrimitive.content.startsWith("3.11."))
        val client = EmbeddedContentClient(runtime)
        val descriptors = client.catalogs("")
        assertTrue(descriptors.isNotEmpty())
        assertFalse(descriptors.any { it.source == "cinemeta" })
        val channels = client.catalog("", "tv", "bundled_live")!!.metas
        assertTrue(channels.isNotEmpty())
        assertTrue(channels.all { it.type == "tv" })
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
