package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.remote.AddonClient
import com.tanasi.streamflix.data.remote.Urls
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.data.repo.StreamsResult
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Normalización de la respuesta del addon y del endpoint.
 *
 * `MockWebServer` permite probar el cliente completo (URL, cabeceras, JSON,
 * errores HTTP) sin red ni emulador.
 */
class StreamsRepositoryTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Implementación de tres líneas: los repositorios no dependen de Android. */
    private class FakeSettings(override var baseUrl: String) : AddonSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(): StreamsRepository =
        StreamsRepository(AddonClient(), FakeSettings(server.url("/").toString().trimEnd('/')))

    @Test
    fun `normalize conserva el orden de prioridad del servidor`() {
        val response = json.decodeFromString(
            StreamsResponse.serializer(),
            """
            {"streams":[
              {"name":"A","title":"A","url":"https://a.example/1.m3u8","provider":"Uno"},
              {"name":"B","title":"B","url":"https://b.example/2.m3u8","provider":"Dos"},
              {"name":"C","title":"C","url":"https://c.example/3.mp4","provider":"Tres"}
            ]}
            """.trimIndent(),
        )

        val sources = StreamsRepository.normalize(response)

        assertEquals(listOf("Uno", "Dos", "Tres"), sources.map { it.provider })
        assertEquals(listOf(0, 1, 2), sources.map { it.index })
    }

    @Test
    fun `normalize descarta URLs no reproducibles y duplicadas`() {
        val response = json.decodeFromString(
            StreamsResponse.serializer(),
            """
            {"streams":[
              {"name":"ok","url":"https://a.example/1.m3u8"},
              {"name":"página","url":"https://a.example/embed/xyz"},
              {"name":"dup","url":"https://a.example/1.m3u8"},
              {"name":"vacio","url":""},
              {"name":"mp4","url":"https://b.example/v.mp4?token=1"}
            ]}
            """.trimIndent(),
        )

        val sources = StreamsRepository.normalize(response)

        assertEquals(2, sources.size)
        assertEquals("https://a.example/1.m3u8", sources[0].url)
        assertEquals("https://b.example/v.mp4?token=1", sources[1].url)
    }

    @Test
    fun `las cabeceras se leen sin distinguir mayúsculas`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"streams":[{"name":"SW","title":"Streamwish","url":"https://a.example/1.m3u8",
                 "quality":"1080p","provider":"gnula",
                 "behaviorHints":{"requestHeaders":{"referer":"https://gnula.example/","USER-AGENT":"UA/1"}}}]}
                """.trimIndent(),
            ).setHeader("Content-Type", "application/json"),
        )

        val result = repository().streamsFor("movie", "gnula:street-fighter")

        assertTrue(result is StreamsResult.Ready)
        val source = (result as StreamsResult.Ready).sources.single()
        assertEquals("https://gnula.example/", source.referer)
        assertEquals("UA/1", source.userAgent)
        assertEquals("1080p", source.quality)
        assertEquals("gnula", source.provider)
    }

    @Test
    fun `el endpoint se construye con el id intacto`() = runTest {
        server.enqueue(MockResponse().setBody("""{"streams":[]}"""))

        repository().streamsFor("series", "pelisplushd:the-last-of-us:1:2")

        val recorded = server.takeRequest()
        assertEquals("/stream/series/pelisplushd:the-last-of-us:1:2.json", recorded.path)
    }

    @Test
    fun `una respuesta vacia se reporta como Empty`() = runTest {
        server.enqueue(MockResponse().setBody("""{"streams":[]}"""))

        val result = repository().streamsFor("movie", "x")

        assertEquals(StreamsResult.Empty, result)
    }

    @Test
    fun `un 500 se convierte en Failure con mensaje`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = repository().streamsFor("movie", "x")

        assertTrue(result is StreamsResult.Failure)
        assertTrue((result as StreamsResult.Failure).message.contains("500"))
    }

    @Test
    fun `Urls normaliza lo que escribe el usuario`() {
        assertEquals("http://192.168.1.50:7000", Urls.normalizeBase("192.168.1.50:7000"))
        assertEquals("http://192.168.1.50:7000", Urls.normalizeBase(" http://192.168.1.50:7000/ "))
        assertEquals("https://addon.example", Urls.normalizeBase("https://addon.example/"))

        assertTrue(Urls.isValidBase("10.0.2.2:7000"))
        assertTrue(Urls.isValidBase("http://localhost:7000"))
        assertTrue(Urls.isValidBase("nas:7000"))
        assertTrue(Urls.isValidBase("https://addon.midominio.com"))
        assertTrue(Urls.isValidBase("  https://addon.midominio.com/  "))

        assertFalse(Urls.isValidBase("no es una url"))
        assertFalse(Urls.isValidBase(""))
        assertFalse(Urls.isValidBase("ftp://192.168.1.50:7000"))
        assertFalse(Urls.isValidBase("http://192.168.1.50:abc"))
    }

    @Test
    fun `normalize quita la barra final y anade el esquema una sola vez`() {
        assertEquals("http://192.168.1.50:7000", Urls.normalizeBase("192.168.1.50:7000/"))
        assertEquals("https://addon.midominio.com", Urls.normalizeBase("https://addon.midominio.com//"))
        assertEquals("http://localhost:7000", Urls.normalizeBase("http://localhost:7000"))
        assertEquals("192.168.1.50:7000", Urls.hostLabel("http://192.168.1.50:7000/"))
    }
}
