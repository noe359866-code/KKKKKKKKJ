package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.ExternalExtensionEntry
import com.tanasi.streamflix.data.model.ExternalExtensionRepository
import com.tanasi.streamflix.data.model.ExtensionRepositoryKind
import com.tanasi.streamflix.data.remote.ExternalExtensionPackageInstaller
import com.tanasi.streamflix.data.remote.ExternalExtensionPackageManager
import com.tanasi.streamflix.data.remote.ExternalExtensionPackageManagerStorage
import com.tanasi.streamflix.data.remote.ExtensionPackageFetcher
import com.tanasi.streamflix.data.remote.ExtensionPackageInstallOperation
import com.tanasi.streamflix.data.remote.InstalledExtensionPackage
import com.tanasi.streamflix.data.remote.ExtensionPackageStorage
import com.tanasi.streamflix.data.remote.ExternalExtensionRepositoryClient
import com.tanasi.streamflix.data.remote.ExternalExtensionRepositoryFetcher
import com.tanasi.streamflix.data.remote.ExternalExtensionRepositoryManager
import com.tanasi.streamflix.data.remote.InMemoryExtensionRepositoryStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExternalExtensionRepositoryTest {
    private fun cloudstreamFixture(): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            val dexHeader = ByteArray(112)
            dexHeader[0] = 'd'.code.toByte()
            dexHeader[1] = 'e'.code.toByte()
            dexHeader[2] = 'x'.code.toByte()
            dexHeader[3] = 10
            dexHeader[4] = '0'.code.toByte()
            dexHeader[5] = '3'.code.toByte()
            dexHeader[6] = '9'.code.toByte()
            fun putInt(offset: Int, value: Int) {
                repeat(4) { dexHeader[offset + it] = (value ushr (it * 8)).toByte() }
            }
            putInt(32, dexHeader.size)
            putInt(36, 112)
            zip.write(dexHeader)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"pluginClassName":"fixture.Plugin"}""".toByteArray())
            zip.closeEntry()
        }
        output.toByteArray()
    }

    @Test fun `Nuvio indexes parse as metadata without treating JS files as executable`() {
        val parsed = ExternalExtensionRepositoryClient().parseNuvioIndex(
            "https://raw.githubusercontent.com/example/repo/main/manifest.json",
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"name":"Example Nuvio","scrapers":[{"id":"provider-a","name":"Provider A","version":"2.1","supportedTypes":["movie","tv"],"contentLanguage":["es"],"filename":"providers/a.js"}]}""",
            ).jsonObject,
        )
        assertEquals("Example Nuvio", parsed.name)
        assertEquals(1, parsed.itemCount)
        assertEquals("https://raw.githubusercontent.com/example/repo/main/providers/a.js", parsed.entries.single().packageUrl)
        assertEquals(listOf("movie", "tv"), parsed.entries.single().supportedTypes)
    }

    @Test fun `Cloudstream plugin list preserves the real fileHash and package size fields`() {
        val client = ExternalExtensionRepositoryClient()
        val rows = kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"internalName":"DailymotionProvider","name":"DailymotionProvider","version":4,"url":"https://raw.githubusercontent.com/recloudstream/extensions/builds/DailymotionProvider.cs3","fileHash":"sha256-9036525a64e8b3c8fe04f94e9fe89a744c13d69af684ed0d2ad2a7eb0f332cd9","fileSize":11472,"tvTypes":["Others"]}]""",
        ).jsonArray
        val entry = client.parseCloudstreamPluginList("https://example.org/plugins.json", rows).single()
        assertEquals("DailymotionProvider", entry.id)
        assertEquals("https://raw.githubusercontent.com/recloudstream/extensions/builds/DailymotionProvider.cs3", entry.packageUrl)
        assertEquals("sha256-9036525a64e8b3c8fe04f94e9fe89a744c13d69af684ed0d2ad2a7eb0f332cd9", entry.packageSha256)
        assertEquals(11472L, entry.packageSizeBytes)
        assertEquals(listOf("Others"), entry.supportedTypes)
    }

    @Test fun `package installer verifies Cloudstream hash and stores only validated bytes`() = runBlocking {
        val bytes = cloudstreamFixture()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        var saved: ByteArray? = null
        val fetcher = ExtensionPackageFetcher { _, maxBytes ->
            assertTrue(bytes.size <= maxBytes)
            bytes
        }
        val storage = ExtensionPackageStorage { _, _, extension, data ->
            assertEquals("cs3", extension)
            saved = data
            "/private/extensions/test.cs3"
        }
        val installer = ExternalExtensionPackageInstaller(fetcher, storage)
        val entry = ExternalExtensionEntry(
            id = "provider-a",
            name = "Provider A",
            packageUrl = "https://example.org/provider-a.cs3",
            packageSha256 = "sha256-$digest",
            packageSizeBytes = bytes.size.toLong(),
        )
        val installed = installer.install(ExtensionRepositoryKind.CLOUDSTREAM, entry).getOrThrow()
        assertEquals(digest, installed.sha256)
        assertEquals(bytes.size, installed.sizeBytes)
        assertTrue(bytes.contentEquals(saved!!))
    }

    @Test fun `Cloudstream package installer rejects hash-correct non cs3 bytes`() = runBlocking {
        val bytes = "not a zip package".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val installer = ExternalExtensionPackageInstaller(
            fetcher = ExtensionPackageFetcher { _, _ -> bytes },
            storage = ExtensionPackageStorage { _, _, _, _ -> error("un paquete inválido no se guarda") },
        )
        val result = installer.install(
            ExtensionRepositoryKind.CLOUDSTREAM,
            ExternalExtensionEntry(
                id = "broken",
                name = "Broken",
                packageUrl = "https://example.org/broken.cs3",
                packageSha256 = digest,
            ),
        )
        assertTrue(result.isFailure)
    }

    @Test fun `package installer rejects missing or mismatched Cloudstream checksums`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        var fetched = false
        val fetcher = ExtensionPackageFetcher { _, _ -> fetched = true; bytes }
        val storage = ExtensionPackageStorage { _, _, _, _ -> error("no debe guardar") }
        val installer = ExternalExtensionPackageInstaller(fetcher, storage)
        val base = ExternalExtensionEntry(
            id = "plugin",
            name = "Plugin",
            packageUrl = "https://example.org/plugin.cs3",
        )
        assertTrue(installer.install(ExtensionRepositoryKind.CLOUDSTREAM, base).isFailure)
        assertFalse("Sin hash no se debe iniciar la descarga", fetched)
        assertTrue(installer.install(ExtensionRepositoryKind.CLOUDSTREAM, base.copy(packageSha256 = "sha256-${"0".repeat(64)}")).isFailure)
    }

    @Test fun `Nuvio package installer accepts bounded JS without a published checksum`() = runBlocking {
        val bytes = "module.exports.getStreams = async () => []".toByteArray()
        var stored = false
        val installer = ExternalExtensionPackageInstaller(
            fetcher = ExtensionPackageFetcher { _, maxBytes ->
                assertTrue(bytes.size <= maxBytes)
                bytes
            },
            storage = ExtensionPackageStorage { _, _, extension, _ ->
                assertEquals("js", extension)
                stored = true
                "/private/extensions/nuvio.js"
            },
        )
        val result = installer.install(
            ExtensionRepositoryKind.NUVIO,
            ExternalExtensionEntry("provider", "Provider", packageUrl = "https://example.org/provider.js"),
        )
        assertTrue(result.isSuccess)
        assertTrue(stored)
    }

    @Test fun `package manager keeps new scripts disabled until explicit opt in and removes the source`() = runBlocking {
        val file = File.createTempFile("nuvio-provider-", ".js")
        file.writeText("module.exports.getStreams = async () => []")
        val storage = object : ExternalExtensionPackageManagerStorage {
            private var value: String? = null
            override fun load() = value
            override fun save(json: String) { value = json }
        }
        val installer = ExtensionPackageInstallOperation { kind, entry ->
            Result.success(
                InstalledExtensionPackage(
                    kind = kind,
                    entryId = entry.id,
                    name = entry.name,
                    filePath = file.absolutePath,
                    sha256 = "a".repeat(64),
                    sizeBytes = file.length().toInt(),
                ),
            )
        }
        val manager = ExternalExtensionPackageManager(storage, installer, packageRoot = file.parentFile)
        manager.install(
            ExtensionRepositoryKind.NUVIO,
            ExternalExtensionEntry("provider", "Provider", packageUrl = "https://example.org/provider.js"),
        ).getOrThrow()
        assertTrue(manager.enabledNuvioScripts().isEmpty())
        manager.setEnabled(ExtensionRepositoryKind.NUVIO, "provider", true)
        assertEquals("module.exports.getStreams = async () => []", manager.enabledNuvioScripts().single().code)
        manager.remove(ExtensionRepositoryKind.NUVIO, "provider")
        assertTrue(manager.installed.value.isEmpty())
        assertTrue("remove deletes the stored source", !file.exists())
    }

    @Test fun `Cloudstream repo shape is not mistaken for a Nuvio manifest`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"Cloudstream","pluginLists":["https://example.org/plugins.json"]}""",
        ).jsonObject
        val failure = runCatching {
            ExternalExtensionRepositoryClient().parseNuvioIndex("https://example.org/repo.json", root)
        }
        assertTrue(failure.isFailure)
    }

    @Test fun `only HTTPS public repository URLs are accepted`() {
        val client = ExternalExtensionRepositoryClient()
        assertTrue(client.isSafeHttpsUrl("https://example.org/repo.json"))
        assertFalse(client.isSafeHttpsUrl("http://example.org/repo.json"))
        assertFalse(client.isSafeHttpsUrl("https://localhost/repo.json"))
        assertFalse(client.isSafeHttpsUrl("https://user:pass@example.org/repo.json"))
    }

    @Test fun `repository manager refreshes by type and URL and persists entries`() = runBlocking {
        val storage = InMemoryExtensionRepositoryStorage()
        val fetcher = ExternalExtensionRepositoryFetcher { url, kind ->
            ExternalExtensionRepository(
                kind = kind,
                url = url,
                name = "repo-${kind.name}",
                itemCount = 1,
                entries = listOf(ExternalExtensionEntry("plugin", "Plugin")),
            )
        }
        val manager = ExternalExtensionRepositoryManager(storage, fetcher)
        manager.addOrRefresh("https://example.org/manifest.json", ExtensionRepositoryKind.NUVIO).getOrThrow()
        manager.addOrRefresh("https://example.org/manifest.json", ExtensionRepositoryKind.CLOUDSTREAM).getOrThrow()
        manager.addOrRefresh("https://example.org/manifest.json", ExtensionRepositoryKind.NUVIO).getOrThrow()
        assertEquals(2, manager.repositories.value.size)
        assertEquals(1, manager.repositories.value.count { it.kind == ExtensionRepositoryKind.NUVIO })
        val restored = ExternalExtensionRepositoryManager(storage, fetcher)
        assertEquals(manager.repositories.value, restored.repositories.value)
        restored.remove(ExtensionRepositoryKind.NUVIO, "https://example.org/manifest.json")
        assertEquals(listOf(ExtensionRepositoryKind.CLOUDSTREAM), restored.repositories.value.map { it.kind })
    }
}
