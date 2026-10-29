package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.remote.AddonClient
import com.tanasi.streamflix.data.repo.CatalogRepository
import com.tanasi.streamflix.data.repo.CatalogResult
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contrato del Home con el backend real (sin assets locales).
 *
 * Usan `runBlocking` (no `runTest`) a propósito: el repositorio aplica
 * `withTimeoutOrNull` a llamadas de red reales contra `MockWebServer`, y el
 * reloj virtual de `runTest` dispararía el tope antes de que el socket conteste.
 *
 * Estas pruebas fijan las tres garantías que sostienen el modo producción:
 *  1. sin servidor configurado la app lo dice y ofrece ajustes (no enseña un
 *     catálogo de prueba);
 *  2. las filas vienen de `/catalogs.json` + `/catalog/{type}/{id}.json`, con la
 *     forma (2:3 / 16:9) decidida por el servidor;
 *  3. una fila rota se descarta sola y el resto del Home sigue vivo.
 */
class CatalogRepositoryTest {

    private lateinit var server: MockWebServer

    /** Los repositorios no dependen de Android: los ajustes son una interfaz. */
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

    private fun repository(baseUrl: String = server.url("/").toString().trimEnd('/')) =
        CatalogRepository(AddonClient(), FakeSettings(baseUrl))

    /** Sirve por ruta: las filas se piden en paralelo, el orden no es fijo. */
    private fun serve(routes: Map<String, MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.path.orEmpty()] ?: MockResponse().setResponseCode(404).setBody("{}")
        }
    }

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private val catalogsFeed = json(
        """
        {"catalogs":[
          {"type":"tv","id":"live","name":"TV en Vivo","posterShape":"landscape","order":1,"source":"local",
           "feed":"/catalog/tv/live.json"},
          {"type":"movie","id":"top","name":"Películas populares","posterShape":"poster","order":2,
           "source":"cinemeta","feed":"/catalog/movie/top.json"}
        ]}
        """.trimIndent(),
    )

    @Test
    fun `sin servidor configurado pide ajustes en lugar de inventar un catálogo`() = runBlocking {
        val result = CatalogRepository(AddonClient(), FakeSettings("")).load()

        assertTrue(result is CatalogResult.Failure)
        result as CatalogResult.Failure
        assertTrue("debe ofrecer el diálogo de servidor", result.needsSetup)
        assertTrue(result.message.contains("servidor", ignoreCase = true))
    }

    @Test
    fun `las filas se construyen desde el backend y la forma la decide el servidor`() = runBlocking {
        serve(
            mapOf(
                "/catalogs.json" to catalogsFeed,
                "/catalog/tv/live.json" to json(
                    """
                    {"metas":[{"id":"tv:futbolibre:espn-1","type":"tv","name":"ESPN 1",
                     "posterShape":"landscape","releaseInfo":"En directo","genres":["Deportes"]}]}
                    """.trimIndent(),
                ),
                "/catalog/movie/top.json" to json(
                    """
                    {"metas":[
                      {"id":"tt0133093","type":"movie","name":"The Matrix","releaseInfo":"1999",
                       "rating":8.7,"poster":"https://img.example/matrix.jpg"},
                      {"id":"tt0133093","type":"movie","name":"Duplicado"},
                      {"id":"tt1375666","type":"movie","name":"Inception","releaseInfo":"2010"}
                    ]}
                    """.trimIndent(),
                ),
            ),
        )

        val result = repository().load()
        assertTrue(result is CatalogResult.Ready)

        val rows = (result as CatalogResult.Ready).catalog.rows
        assertEquals(listOf("remote:tv/live", "remote:movie/top"), rows.map { it.id })
        assertEquals("landscape", rows[0].aspect)
        assertEquals("poster", rows[1].aspect)

        /* Dedup por id y el id de IMDb intacto: es lo que pide /stream. */
        assertEquals(listOf("tv:futbolibre:espn-1"), rows[0].items.map { it.id })
        assertEquals(listOf("tt0133093", "tt1375666"), rows[1].items.map { it.id })
        assertEquals(listOf(1999, 2010), rows[1].items.map { it.year })
        assertTrue("la TV en vivo se pinta en 16:9 y con badge", rows[0].items[0].live)
    }

    @Test
    fun `una fila degradada o rota no tumba el resto del Home`() = runBlocking {
        serve(
            mapOf(
                "/catalogs.json" to json(
                    """
                    {"catalogs":[
                      {"type":"movie","id":"top","name":"Caída","posterShape":"poster"},
                      {"type":"movie","id":"ok","name":"Películas","posterShape":"poster"}
                    ]}
                    """.trimIndent(),
                ),
                "/catalog/movie/top.json" to json("""{"metas":[],"degraded":true}"""),
                "/catalog/movie/ok.json" to json(
                    """{"metas":[{"id":"tt0111161","type":"movie","name":"Cadena perpetua"}]}""",
                ),
            ),
        )

        val result = repository().load()
        assertTrue(result is CatalogResult.Ready)

        val rows = (result as CatalogResult.Ready).catalog.rows
        assertEquals(listOf("remote:movie/ok"), rows.map { it.id })
        assertEquals("Cadena perpetua", rows[0].items[0].title)
    }

    @Test
    fun `un servidor que no publica catálogos se reporta sin culpar a la red`() = runBlocking {
        /* Sin /catalogs.json el cliente cae al manifest (versión antigua), que
         * también responde vacío: el servidor está vivo, sólo no publica filas. */
        serve(
            mapOf(
                "/catalogs.json" to MockResponse().setResponseCode(404),
                "/manifest.json" to json("""{"catalogs":[]}"""),
            ),
        )

        val result = repository().load()
        assertTrue(result is CatalogResult.Failure)
        result as CatalogResult.Failure
        assertFalse("no es un problema de URL", result.needsSetup)
        assertTrue(result.message.contains("catálogos", ignoreCase = true))
    }

    @Test
    fun `un backend caído se reporta como problema de configuración`() = runBlocking {
        /* Puerto cerrado: OkHttp falla antes de que el tope de 4 s importe. */
        val result = CatalogRepository(AddonClient(), FakeSettings("http://127.0.0.1:1")).load()

        assertTrue(result is CatalogResult.Failure)
        result as CatalogResult.Failure
        assertTrue(result.needsSetup)
        assertTrue(result.message.contains("127.0.0.1:1"))
    }
}
