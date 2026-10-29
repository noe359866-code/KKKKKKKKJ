package com.tanasi.streamflix.data

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
}
