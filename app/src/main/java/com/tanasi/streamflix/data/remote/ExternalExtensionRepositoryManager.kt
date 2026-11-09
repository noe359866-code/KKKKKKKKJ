package com.tanasi.streamflix.data.remote

import android.content.Context
import androidx.core.content.edit
import com.tanasi.streamflix.data.model.ExternalExtensionRepository
import com.tanasi.streamflix.data.model.ExtensionRepositoryKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persists repository indexes so TV settings can show the last successful refresh offline. */
class ExternalExtensionRepositoryManager(
    private val storage: ExtensionRepositoryStorage,
    private val client: ExternalExtensionRepositoryFetcher = ExternalExtensionRepositoryClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private val lock = Any()
    private val state = MutableStateFlow(load())
    val repositories: StateFlow<List<ExternalExtensionRepository>> = state.asStateFlow()

    suspend fun addOrRefresh(url: String, kind: ExtensionRepositoryKind): Result<ExternalExtensionRepository> = try {
        val fetched = client.fetch(url, kind)
        synchronized(lock) {
            val next = state.value.filterNot { it.kind == kind && it.url == fetched.url } + fetched
            persist(next)
            state.value = next
        }
        Result.success(fetched)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun remove(kind: ExtensionRepositoryKind, url: String) = synchronized(lock) {
        val next = state.value.filterNot { it.kind == kind && it.url == url }
        persist(next)
        state.value = next
    }

    private fun load(): List<ExternalExtensionRepository> = synchronized(lock) {
        val raw = storage.load() ?: return emptyList()
        runCatching { json.decodeFromString(ListSerializer(ExternalExtensionRepository.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private fun persist(repositories: List<ExternalExtensionRepository>) {
        storage.save(json.encodeToString(ListSerializer(ExternalExtensionRepository.serializer()), repositories))
    }
}

interface ExtensionRepositoryStorage {
    fun load(): String?
    fun save(json: String)
}

class SharedPreferencesExtensionRepositoryStorage(context: Context) : ExtensionRepositoryStorage {
    private val preferences = context.applicationContext.getSharedPreferences("streamflix.extension_repositories", Context.MODE_PRIVATE)
    override fun load(): String? = preferences.getString(KEY_REPOSITORIES, null)
    override fun save(json: String) { preferences.edit { putString(KEY_REPOSITORIES, json) } }

    private companion object { const val KEY_REPOSITORIES = "repositories" }
}

class InMemoryExtensionRepositoryStorage : ExtensionRepositoryStorage {
    private var value: String? = null
    override fun load(): String? = value
    override fun save(json: String) { value = json }
}
