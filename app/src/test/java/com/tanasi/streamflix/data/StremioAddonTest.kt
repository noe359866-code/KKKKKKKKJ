package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.local.EmbeddedContentClient
import com.tanasi.streamflix.data.local.EmbeddedRuntime
import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamDto
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.model.StremioAddon
import com.tanasi.streamflix.data.model.StremioManifest
import com.tanasi.streamflix.data.model.StremioManifestCatalog
import com.tanasi.streamflix.data.model.StremioResource
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.data.repo.StreamsResult
import com.tanasi.streamflix.data.stremio.InMemoryAddonStorage
import com.tanasi.streamflix.data.stremio.StremioAddonClient
import com.tanasi.streamflix.data.stremio.StremioAddonManager
import com.tanasi.streamflix.data.stremio.StremioUrls
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StremioAddonTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private class TestSettings(
        override var baseUrl: String = "",
        override var autonomous: Boolean = true,
        override var metadataCatalogs: Boolean = false,
    ) : AddonSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `StremioResource deserializa strings simples y objetos estructurados`() {
        val jsonPayload = """
        {
          "id": "test.addon",
          "name": "Test Addon",
          "version": "1.0.0",
          "resources": [
            "catalog",
            {
              "name": "stream",
              "types": ["movie", "series"],
              "idPrefixes": ["tt"]
            }
          ]
        }
        """.trimIndent()

        val manifest = json.decodeFromString(StremioManifest.serializer(), jsonPayload)
        assertEquals(2, manifest.resources.size)
        assertEquals("catalog", manifest.resources[0].name)
        assertTrue(manifest.resources[0].types.isEmpty())

        assertEquals("stream", manifest.resources[1].name)
        assertEquals(listOf("movie", "series"), manifest.resources[1].types)
        assertEquals(listOf("tt"), manifest.resources[1].idPrefixes)

        val addon = StremioAddon(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            manifestUrl = "https://example.com/manifest.json",
            baseUrl = "https://example.com",
            resources = manifest.resources,
        )

        assertTrue(addon.supportsResource("catalog", "movie", "tt123"))
        assertTrue(addon.supportsResource("stream", "movie", "tt123"))
        assertFalse(addon.supportsResource("stream", "anime", "tt123"))
        assertFalse(addon.supportsResource("stream", "movie", "custom:123"))
        assertFalse(addon.supportsResource("meta", "movie", "tt123"))
    }

    @Test
    fun `StremioUrls normaliza URLs y construye endpoints de acuerdo al protocolo`() {
        assertEquals(
            "https://cinemeta.strem.io/manifest.json",
            StremioUrls.normalizeManifestUrl("cinemeta.strem.io"),
        )
        assertEquals(
            "https://v3-cinemeta.strem.io/manifest.json",
            StremioUrls.normalizeManifestUrl("stremio://v3-cinemeta.strem.io/manifest.json"),
        )
        assertEquals(
            "https://addon.example.com",
            StremioUrls.manifestToBaseUrl("https://addon.example.com/manifest.json"),
        )
        assertEquals(
            "https://addon.example.com/sort=quality",
            StremioUrls.manifestToBaseUrl("https://addon.example.com/sort=quality/manifest.json"),
        )
        assertEquals(
            "https://addon.example.com/catalog/movie/top/search=Matrix.json",
            StremioUrls.catalog("https://addon.example.com", "movie", "top", search = "Matrix"),
        )
        assertEquals(
            "https://addon.example.com/stream/movie/tt0133093.json",
            StremioUrls.stream("https://addon.example.com", "movie", "tt0133093"),
        )
    }

    @Test
    fun `StremioAddonManager instala, guarda y gestiona addons correctamente`() = runBlocking {
        val manifestJson = """
        {
          "id": "org.test.stremio",
          "name": "Addon de Prueba",
          "version": "2.1.0",
          "description": "Addon para testing",
          "resources": ["stream", "catalog"],
          "types": ["movie"],
          "catalogs": [
            {"type": "movie", "id": "top_test", "name": "Top Test"}
          ]
        }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(manifestJson),
        )

        val storage = InMemoryAddonStorage()
        val client = StremioAddonClient()
        val manager = StremioAddonManager(storage, client)

        val manifestUrl = server.url("/manifest.json").toString()
        val result = manager.installAddon(manifestUrl)

        assertTrue(result.isSuccess)
        val installed = result.getOrThrow()
        assertEquals("org.test.stremio", installed.id)
        assertEquals("Addon de Prueba", installed.name)
        assertEquals(1, manager.getAddons().size)
        assertEquals(1, manager.getEnabledAddons().size)

        // Deshabilitar
        manager.setAddonEnabled("org.test.stremio", false)
        assertEquals(0, manager.getEnabledAddons().size)

        // Habilitar de nuevo
        manager.setAddonEnabled("org.test.stremio", true)
        assertEquals(1, manager.getEnabledAddons().size)

        // Nuevo manager con el mismo storage persiste los datos
        val restoredManager = StremioAddonManager(storage, client)
        assertEquals(1, restoredManager.getAddons().size)
        assertEquals("Addon de Prueba", restoredManager.getAddon("org.test.stremio")?.name)

        // Eliminar
        manager.removeAddon("org.test.stremio")
        assertTrue(manager.getAddons().isEmpty())
    }

    @Test
    fun `RoutingContentClient combina catalogos y streams de addons Stremio con el motor local`() = runBlocking {
        val serverBase = server.url("/").toString().trimEnd('/')

        // Servidor simula manifest, catalog y stream
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("/manifest.json") -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"id":"test_addon","name":"Test Addon","resources":["catalog","stream"],"types":["movie"],"catalogs":[{"type":"movie","id":"popular","name":"Populares"}]}""",
                        )
                    path.startsWith("/catalog/movie/popular") -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"metas":[{"id":"tt0001","type":"movie","name":"Película Externa"}]}""",
                        )
                    path.startsWith("/stream/movie/tt0001") -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"streams":[{"name":"Stream Addon","title":"1080p","url":"https://stream.invalid/1080.m3u8","provider":"Test Addon"}]}""",
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val storage = InMemoryAddonStorage()
        val client = StremioAddonClient()
        val manager = StremioAddonManager(storage, client)
        val installResult = manager.installAddon("$serverBase/manifest.json")
        assertTrue("El addon debe instalarse con éxito", installResult.isSuccess)

        val embedded = EmbeddedContentClient(EmbeddedRuntime { op, _ ->
            when (op) {
                "catalogs" -> """{"catalogs":[{"type":"tv","id":"bundled_live","name":"Canales"}]}"""
                "streams" -> """{"streams":[{"name":"Local Scraper","title":"720p","url":"https://local.invalid/720.m3u8","provider":"Local"}]}"""
                else -> "{}"
            }
        })
        val remote = EmbeddedContentClient(EmbeddedRuntime { _, _ -> "{}" })

        val routingClient = RoutingContentClient(
            settings = TestSettings(autonomous = true),
            embedded = embedded,
            remote = remote,
            addonManager = manager,
            stremioClient = client,
        )

        // 1. Catálogos combinados: canal local + fila del addon
        val catalogs = routingClient.catalogs("")
        assertEquals(2, catalogs.size)
        assertEquals("bundled_live", catalogs[0].id)
        assertEquals("addon:test_addon:popular", catalogs[1].id)
        assertEquals("Populares (Test Addon)", catalogs[1].name)

        // 2. Cargar la fila del addon
        val rowMetas = routingClient.catalog("", "movie", "addon:test_addon:popular")
        assertNotNull(rowMetas)
        assertEquals("tt0001", rowMetas!!.metas.single().id)
        assertEquals("Película Externa", rowMetas.metas.single().name)

        // 3. Streams combinados: Local Scraper + Stream Addon
        val streamsResp = routingClient.streams("", "movie", "tt0001")
        assertEquals(2, streamsResp.streams.size)
        assertEquals("Local Scraper", streamsResp.streams[0].name)
        assertEquals("Stream Addon", streamsResp.streams[1].name)
        assertEquals("Test Addon", streamsResp.streams[1].provider)

        // Normalización en StreamsRepository
        val normalized = StreamsRepository.normalize(streamsResp)
        assertEquals(2, normalized.size)
        assertEquals("https://local.invalid/720.m3u8", normalized[0].url)
        assertEquals("https://stream.invalid/1080.m3u8", normalized[1].url)
    }
}
