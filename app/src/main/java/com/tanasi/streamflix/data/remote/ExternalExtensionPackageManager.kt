package com.tanasi.streamflix.data.remote

import android.content.Context
import androidx.core.content.edit
import com.tanasi.streamflix.data.extension.nuvio.NuvioPluginScript
import com.tanasi.streamflix.data.model.ExternalExtensionEntry
import com.tanasi.streamflix.data.model.ExtensionRepositoryKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class InstalledExtensionRecord(
    val kind: ExtensionRepositoryKind,
    val entry: ExternalExtensionEntry,
    val filePath: String,
    val sha256: String,
    val sizeBytes: Int,
    val enabled: Boolean = false,
)

/** Persists explicitly installed packages; installation never means execution is enabled. */
class ExternalExtensionPackageManager(
    private val storage: ExternalExtensionPackageManagerStorage,
    private val installer: ExtensionPackageInstallOperation,
    private val packageRoot: File? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val lock = Any()
    private val state = MutableStateFlow(load())
    val installed: StateFlow<List<InstalledExtensionRecord>> = state.asStateFlow()

    suspend fun install(
        kind: ExtensionRepositoryKind,
        entry: ExternalExtensionEntry,
    ): Result<InstalledExtensionRecord> {
        val packageResult = try {
            installer.install(kind, entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.failure(error)
        }
        return packageResult.mapCatching { packageInfo ->
            val record = InstalledExtensionRecord(
                kind = kind,
                entry = entry,
                filePath = packageInfo.filePath,
                sha256 = packageInfo.sha256,
                sizeBytes = packageInfo.sizeBytes,
            )
            synchronized(lock) {
                val next = state.value.filterNot { it.kind == kind && it.entry.id == entry.id } + record
                save(next)
                state.value = next
            }
            record
        }
    }

    fun setEnabled(kind: ExtensionRepositoryKind, id: String, enabled: Boolean) = synchronized(lock) {
        val next = state.value.map { record ->
            if (record.kind == kind && record.entry.id == id) record.copy(enabled = enabled) else record
        }
        save(next)
        state.value = next
    }

    fun remove(kind: ExtensionRepositoryKind, id: String) = synchronized(lock) {
        val removed = state.value.filter { it.kind == kind && it.entry.id == id }
        removed.forEach { record ->
            managedFile(record.filePath)?.let { file -> if (file.isFile) file.delete() }
        }
        val next = state.value.filterNot { it.kind == kind && it.entry.id == id }
        save(next)
        state.value = next
    }

    /** Returns only enabled JavaScript providers whose downloaded source still passes bounds. */
    suspend fun enabledNuvioScripts(): List<NuvioPluginScript> = withContext(Dispatchers.IO) {
        synchronized(lock) {
        state.value.asSequence()
            .filter { it.kind == ExtensionRepositoryKind.NUVIO && it.enabled }
            .mapNotNull { record ->
                val file = managedFile(record.filePath) ?: return@mapNotNull null
                if (!file.isFile || file.length() !in 1..ExternalExtensionPackageInstaller.MAX_NUVIO_BYTES.toLong()) {
                    return@mapNotNull null
                }
                val code = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                NuvioPluginScript(
                    id = record.entry.id,
                    name = record.entry.name,
                    code = code,
                    enabled = true,
                )
            }
            .toList()
        }
    }

    fun enabledCloudstreamPackages(): List<InstalledExtensionRecord> = synchronized(lock) {
        state.value.filter {
            val file = managedFile(it.filePath)
            it.kind == ExtensionRepositoryKind.CLOUDSTREAM && it.enabled && file != null &&
                file.isFile && file.length() in 1..ExternalExtensionPackageInstaller.MAX_CLOUDSTREAM_BYTES.toLong()
        }
    }

    private fun managedFile(path: String): File? {
        val root = packageRoot ?: return null
        val canonicalRoot = root.canonicalFile.path.trimEnd(File.separatorChar) + File.separator
        val file = File(path).canonicalFile
        return file.takeIf { it.path.startsWith(canonicalRoot) }
    }

    private fun load(): List<InstalledExtensionRecord> = synchronized(lock) {
        val raw = storage.load() ?: return emptyList()
        runCatching {
            json.decodeFromString(ListSerializer(InstalledExtensionRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun save(records: List<InstalledExtensionRecord>) {
        storage.save(json.encodeToString(ListSerializer(InstalledExtensionRecord.serializer()), records))
    }
}

interface ExternalExtensionPackageManagerStorage {
    fun load(): String?
    fun save(json: String)
}

class SharedPreferencesExternalExtensionPackageManagerStorage(context: Context) : ExternalExtensionPackageManagerStorage {
    private val preferences = context.applicationContext.getSharedPreferences("streamflix.installed_extensions", Context.MODE_PRIVATE)
    override fun load(): String? = preferences.getString(KEY, null)
    override fun save(json: String) { preferences.edit { putString(KEY, json) } }

    private companion object { const val KEY = "packages" }
}

class InMemoryExternalExtensionPackageManagerStorage : ExternalExtensionPackageManagerStorage {
    private var value: String? = null
    override fun load(): String? = value
    override fun save(json: String) { value = json }
}
