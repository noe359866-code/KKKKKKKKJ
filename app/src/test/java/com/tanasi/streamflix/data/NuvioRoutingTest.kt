package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.extension.nuvio.NuvioPluginEngine
import com.tanasi.streamflix.data.extension.nuvio.NuvioPluginScriptExecutor
import com.tanasi.streamflix.data.extension.nuvio.NuvioPluginStream
import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.ExternalExtensionEntry
import com.tanasi.streamflix.data.model.ExtensionRepositoryKind
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.remote.ExternalExtensionPackageManager
import com.tanasi.streamflix.data.remote.ExtensionPackageInstallOperation
import com.tanasi.streamflix.data.remote.InstalledExtensionPackage
import com.tanasi.streamflix.data.remote.InMemoryExternalExtensionPackageManagerStorage
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NuvioRoutingTest {
    @Test fun `TMDB IDs encoded as JSON numbers decode for provider routing`() {
        val meta = Json.decodeFromString(RemoteMeta.serializer(), """{"id":"tt123","tmdb_id":603}""")
        assertEquals("603", meta.tmdbId)
    }

    @Test fun `routing resolves TMDB metadata and passes episode coordinates to Nuvio`() = runBlocking {
        val source = File.createTempFile("nuvio-route-", ".js")
        source.writeText("fixture")
        try {
            val packageManager = ExternalExtensionPackageManager(
                storage = InMemoryExternalExtensionPackageManagerStorage(),
                installer = ExtensionPackageInstallOperation { kind, entry ->
                    Result.success(
                        InstalledExtensionPackage(
                            kind = kind,
                            entryId = entry.id,
                            name = entry.name,
                            filePath = source.absolutePath,
                            sha256 = "a".repeat(64),
                            sizeBytes = source.length().toInt(),
                        ),
                    )
                },
                packageRoot = source.parentFile,
            )
            packageManager.install(
                ExtensionRepositoryKind.NUVIO,
                ExternalExtensionEntry("provider", "Provider", packageUrl = "https://example.org/provider.js"),
            ).getOrThrow()
            packageManager.setEnabled(ExtensionRepositoryKind.NUVIO, "provider", true)

            var resolvedTmdbId: String? = null
            var receivedEpisode: Pair<Int?, Int?>? = null
            val engine = NuvioPluginEngine(NuvioPluginScriptExecutor { _, tmdbId, _, season, episode, _, _ ->
                resolvedTmdbId = tmdbId
                receivedEpisode = season to episode
                listOf(NuvioPluginStream(title = "1080p", url = "https://cdn.example/video.m3u8"))
            })
            val content = object : ContentClient {
                override suspend fun catalogs(baseUrl: String) = emptyList<CatalogDescriptor>()
                override suspend fun catalog(baseUrl: String, type: String, id: String, search: String?, genre: String?, skip: Int?) = RemoteCatalogResponse()
                override suspend fun meta(baseUrl: String, type: String, id: String) =
                    RemoteMeta(id = id, type = type, tmdbId = "603")
                override suspend fun streams(baseUrl: String, type: String, id: String) = StreamsResponse()
            }
            val settings = object : AddonSettings {
                override var baseUrl: String = ""
                override val autonomous: Boolean = true
            }
            val routing = RoutingContentClient(
                settings = settings,
                embedded = content,
                remote = content,
                extensionPackageManager = packageManager,
                nuvioPluginEngine = engine,
            )

            val result = routing.streams("", "series", "tt1234567:2:5")

            assertEquals("603", resolvedTmdbId)
            assertEquals(2 to 5, receivedEpisode)
            assertEquals(1, result.streams.size)
        } finally {
            source.delete()
        }
    }

    @Test fun `Nuvio does not receive an IMDb ID when TMDB metadata is missing`() = runBlocking {
        val source = File.createTempFile("nuvio-no-tmdb-", ".js")
        source.writeText("fixture")
        try {
            val packageManager = ExternalExtensionPackageManager(
                storage = InMemoryExternalExtensionPackageManagerStorage(),
                installer = ExtensionPackageInstallOperation { kind, entry ->
                    Result.success(
                        InstalledExtensionPackage(kind, entry.id, entry.name, source.absolutePath, "a".repeat(64), source.length().toInt()),
                    )
                },
                packageRoot = source.parentFile,
            )
            packageManager.install(
                ExtensionRepositoryKind.NUVIO,
                ExternalExtensionEntry("provider", "Provider", packageUrl = "https://example.org/provider.js"),
            ).getOrThrow()
            packageManager.setEnabled(ExtensionRepositoryKind.NUVIO, "provider", true)
            var invoked = false
            val engine = NuvioPluginEngine(NuvioPluginScriptExecutor { _, _, _, _, _, _, _ ->
                invoked = true
                emptyList()
            })
            val content = object : ContentClient {
                override suspend fun catalogs(baseUrl: String) = emptyList<CatalogDescriptor>()
                override suspend fun catalog(baseUrl: String, type: String, id: String, search: String?, genre: String?, skip: Int?) = RemoteCatalogResponse()
                override suspend fun meta(baseUrl: String, type: String, id: String) = RemoteMeta(id = id, type = type)
                override suspend fun streams(baseUrl: String, type: String, id: String) = StreamsResponse()
            }
            val settings = object : AddonSettings {
                override var baseUrl: String = ""
                override val autonomous: Boolean = true
            }
            val routing = RoutingContentClient(settings, content, content, extensionPackageManager = packageManager, nuvioPluginEngine = engine)

            val response = routing.streams("", "movie", "tt1234567")

            assertTrue(response.streams.isEmpty())
            assertTrue(!invoked)
        } finally {
            source.delete()
        }
    }
}
