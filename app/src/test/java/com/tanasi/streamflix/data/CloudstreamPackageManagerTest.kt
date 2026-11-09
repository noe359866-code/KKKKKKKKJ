package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.ExternalExtensionEntry
import com.tanasi.streamflix.data.model.ExtensionRepositoryKind
import com.tanasi.streamflix.data.remote.ExternalExtensionPackageManager
import com.tanasi.streamflix.data.remote.ExtensionPackageInstallOperation
import com.tanasi.streamflix.data.remote.InMemoryExternalExtensionPackageManagerStorage
import com.tanasi.streamflix.data.remote.InstalledExtensionPackage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CloudstreamPackageManagerTest {
    @Test fun `Cloudstream package stays inactive until explicit opt in and can be disabled`() = runBlocking {
        val packageFile = File.createTempFile("cloudstream-", ".cs3")
        packageFile.writeBytes(byteArrayOf(1, 2, 3))
        try {
            val manager = ExternalExtensionPackageManager(
                storage = InMemoryExternalExtensionPackageManagerStorage(),
                installer = ExtensionPackageInstallOperation { kind, entry ->
                    Result.success(
                        InstalledExtensionPackage(
                            kind = kind,
                            entryId = entry.id,
                            name = entry.name,
                            filePath = packageFile.absolutePath,
                            sha256 = "abc",
                            sizeBytes = packageFile.length().toInt(),
                        ),
                    )
                },
                packageRoot = packageFile.parentFile,
            )
            manager.install(
                ExtensionRepositoryKind.CLOUDSTREAM,
                ExternalExtensionEntry("plugin", "Plugin", packageUrl = "https://example.org/plugin.cs3"),
            ).getOrThrow()
            assertTrue(manager.enabledCloudstreamPackages().isEmpty())
            manager.setEnabled(ExtensionRepositoryKind.CLOUDSTREAM, "plugin", true)
            assertEquals(1, manager.enabledCloudstreamPackages().size)
            manager.setEnabled(ExtensionRepositoryKind.CLOUDSTREAM, "plugin", false)
            assertTrue(manager.enabledCloudstreamPackages().isEmpty())
        } finally {
            packageFile.delete()
        }
    }
}
