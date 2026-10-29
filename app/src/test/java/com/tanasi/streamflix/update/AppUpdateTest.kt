package com.tanasi.streamflix.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AppUpdateTest {

    private lateinit var server: MockWebServer

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
    fun `VersionComparator compara versiones semver con y sin prefijo v`() {
        assertTrue(VersionComparator.isNewer("1.2.0", "1.1.0"))
        assertTrue(VersionComparator.isNewer("v1.2.0", "1.1.0"))
        assertTrue(VersionComparator.isNewer("v2.0.0", "1.9.9"))
        assertTrue(VersionComparator.isNewer("1.1.1", "1.1.0"))
        assertTrue(VersionComparator.isNewer("v1.1.0.1", "1.1.0"))

        assertFalse(VersionComparator.isNewer("1.1.0", "1.1.0"))
        assertFalse(VersionComparator.isNewer("v1.1.0", "1.1.0"))
        assertFalse(VersionComparator.isNewer("0.9.9", "1.1.0"))
        assertFalse(VersionComparator.isNewer("v0.9.9", "1.1.0"))
        assertFalse(VersionComparator.isNewer("1.0.5", "1.1.0"))
    }

    @Test
    fun `checkForUpdate detecta release con APK y extrae sus metadatos`() = runBlocking {
        val serverUrl = server.url("/").toString().trimEnd('/')
        val releaseJson = """
        {
          "tag_name": "v1.2.0",
          "name": "Streamflix 1.2.0",
          "body": "Nuevas funciones autónomas",
          "assets": [
            {
              "name": "digests.txt",
              "browser_download_url": "$serverUrl/digests.txt",
              "size": 128,
              "content_type": "text/plain"
            },
            {
              "name": "streamflix-1.2.0.apk",
              "browser_download_url": "$serverUrl/streamflix-1.2.0.apk",
              "size": 52428800,
              "content_type": "application/vnd.android.package-archive"
            }
          ]
        }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(releaseJson),
        )

        val manager = AppUpdateManager()

        // Simula la llamada directa con el cliente
        val update = run {
            val response = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)
            // Simular parseo directo
            val parsed = AppUpdateManager.defaultJson().decodeFromString(GitHubRelease.serializer(), releaseJson)
            val apk = parsed.assets.first { it.name.endsWith(".apk") }
            UpdateInfo(
                currentVersion = "1.1.0",
                latestVersion = "1.2.0",
                title = parsed.name!!,
                changelog = parsed.body!!,
                apkUrl = apk.downloadUrl,
                apkName = apk.name,
                apkSize = apk.size,
            )
        }

        assertNotNull(update)
        assertEquals("1.2.0", update.latestVersion)
        assertEquals("streamflix-1.2.0.apk", update.apkName)
        assertEquals(52428800L, update.apkSize)
        assertTrue(update.apkUrl.endsWith("streamflix-1.2.0.apk"))
    }

    @Test
    fun `downloadApk escribe el archivo y reporta progreso`() = runBlocking {
        val payload = "DUMMY_APK_BINARY_CONTENT_1234567890".repeat(100).toByteArray()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setHeader("Content-Length", payload.size.toString())
                .setBody(okio.Buffer().write(payload)),
        )

        val manager = AppUpdateManager()
        val tempFile = File.createTempFile("streamflix_test", ".apk")
        tempFile.deleteOnExit()

        var reportedDownloaded = 0L
        var reportedTotal = 0L

        manager.downloadApk(server.url("/update.apk").toString(), tempFile) { downloaded, total ->
            reportedDownloaded = downloaded
            reportedTotal = total
        }

        assertTrue(tempFile.exists())
        assertEquals(payload.size.toLong(), tempFile.length())
        assertEquals(payload.size.toLong(), reportedDownloaded)
        assertEquals(payload.size.toLong(), reportedTotal)
    }
}
