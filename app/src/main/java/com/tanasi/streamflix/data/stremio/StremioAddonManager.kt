package com.tanasi.streamflix.data.stremio

import com.tanasi.streamflix.data.model.StremioAddon
import com.tanasi.streamflix.data.model.StremioPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Gestor del ciclo de vida de los addons de Stremio instalados en la app.
 */
class StremioAddonManager(
    private val storage: AddonStorage,
    private val client: StremioAddonClient = StremioAddonClient(),
    private val json: Json = StremioAddonClient.defaultJson(),
) {
    private val lock = Any()
    private val _addons = MutableStateFlow<List<StremioAddon>>(loadSavedAddons())
    val addons: StateFlow<List<StremioAddon>> = _addons.asStateFlow()

    fun getAddons(): List<StremioAddon> = synchronized(lock) { _addons.value }

    fun getEnabledAddons(): List<StremioAddon> = getAddons().filter { it.enabled }

    fun getAddon(id: String): StremioAddon? = getAddons().firstOrNull { it.id == id }

    suspend fun installAddon(manifestUrl: String): Result<StremioAddon> = runCatching {
        val normalizedUrl = StremioUrls.normalizeManifestUrl(manifestUrl)
        if (normalizedUrl.isEmpty()) error("URL de manifest no válida")

        val manifest = client.fetchManifest(normalizedUrl)
        val baseUrl = StremioUrls.manifestToBaseUrl(normalizedUrl)

        val addon = StremioAddon(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            icon = manifest.logo,
            manifestUrl = normalizedUrl,
            baseUrl = baseUrl,
            enabled = true,
            resources = manifest.resources,
            types = manifest.types,
            catalogs = manifest.catalogs,
        )

        synchronized(lock) {
            val current = _addons.value.toMutableList()
            // Si ya existe por id o por URL, se actualiza
            val existingIndex = current.indexOfFirst { it.id == addon.id || it.manifestUrl == addon.manifestUrl }
            if (existingIndex >= 0) {
                current[existingIndex] = addon
            } else {
                current.add(addon)
            }
            saveAddons(current)
            _addons.value = current
        }
        addon
    }

    fun removeAddon(id: String) {
        synchronized(lock) {
            val current = _addons.value.filterNot { it.id == id }
            saveAddons(current)
            _addons.value = current
        }
    }

    fun setAddonEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            val current = _addons.value.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
            saveAddons(current)
            _addons.value = current
        }
    }

    fun presets(): List<StremioPreset> = PRESETS

    private fun loadSavedAddons(): List<StremioAddon> = synchronized(lock) {
        val raw = storage.loadAddonsJson() ?: return emptyList()
        try {
            json.decodeFromString(ListSerializer(StremioAddon.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAddons(list: List<StremioAddon>) {
        val raw = json.encodeToString(ListSerializer(StremioAddon.serializer()), list)
        storage.saveAddonsJson(raw)
    }

    companion object {
        val PRESETS = listOf(
            StremioPreset(
                name = "Streaming Catalogs",
                description = "Catálogos de Netflix, Disney+, Prime Video, HBO Max y Apple TV+",
                manifestUrl = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/c2hhLGlxaSxzaGQsYmJvLGFjdCx2aWssbWJpLGRwZSxjcmMsc3R6LG5mayxjdHMsbWdsLGNydSxobHUscG1wLHBjcCxhdHAsZG5wLGFtcCxoYm0sbmZ4OjpVUzoxNzkwNjIyNDQyMjI3OjA6MDpHVA%3D%3D/manifest.json",
            ),
            StremioPreset(
                name = "Cinemeta Oficial",
                description = "Catálogos y metadatos oficiales de películas y series de IMDb y Stremio",
                manifestUrl = "https://v3-cinemeta.strem.io/manifest.json",
            ),
        )
    }
}
