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

    /**
     * Instala los addons recomendados la primera vez que se abre la app.
     *
     * Cinemeta **no** se instala aquí: el motor local ya publica sus catálogos
     * ("Películas populares", "Series populares"…) con el mismo arte, e
     * instalarlo además duplicaría las filas. Lo que sí falta de fábrica son las
     * filas por plataforma (Netflix, Disney+, HBO Max…), que sólo existen en el
     * addon "Streaming Catalogs".
     *
     * @return `true` si se instaló algo y merece la pena recargar el Home.
     */
    suspend fun seedDefaultAddons(): Boolean {
        if (storage.isSeeded()) return false
        if (getAddons().isNotEmpty()) {
            storage.markSeeded()
            return false
        }
        var installed = false
        for (manifestUrl in SEED_MANIFEST_URLS) {
            if (installAddon(manifestUrl).isSuccess) {
                installed = true
                break
            }
        }
        /* Sólo se marca como sembrado si algo se instaló: si fue un fallo de
         * red, el siguiente arranque vuelve a intentarlo. */
        if (installed) storage.markSeeded()
        return installed
    }

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

        /** Instancia configurada con todas las plataformas disponibles. */
        private const val STREAMING_CATALOGS =
            "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/c2hhLGlxaSxzaGQsYmJvLGFjdCx2aWssbWJpLGRwZSxjcmMsc3R6LG5mayxjdHMsbWdsLGNydSxobHUscG1wLHBjcCxhdHAsZG5wLGFtcCxoYm0sbmZ4OjpVUzoxNzkwNjIyNDQyMjI3OjA6MDpHVA%3D%3D/manifest.json"

        /**
         * Manifest de fábrica del mismo addon (sin configuración guardada).
         *
         * El addon se sirve desde instancias distintas y la configuración
         * incrustada en la URL puede caducar; si la primera falla se prueba
         * esta, que distribuye las plataformas por defecto.
         */
        private const val STREAMING_CATALOGS_DEFAULT =
            "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json"

        /** Alternativa conocida con catálogos por plataforma. */
        private const val CYBERFLIX = "https://cyberflix.elfhosted.com/manifest.json"

        /**
         * Addons que se intentan instalar solos en el primer arranque, en orden.
         * Se queda con el primero que responda: son la misma clase de catálogo y
         * apilarlos llenaría el Home de filas repetidas.
         */
        val SEED_MANIFEST_URLS = listOf(STREAMING_CATALOGS, STREAMING_CATALOGS_DEFAULT)

        val PRESETS = listOf(
            StremioPreset(
                name = "Streaming Catalogs",
                description = "Catálogos de Netflix, Disney+, Prime Video, HBO Max y Apple TV+",
                manifestUrl = STREAMING_CATALOGS,
            ),
            StremioPreset(
                name = "Cyberflix Catalog",
                description = "Filas por plataforma (Netflix, Prime, Hulu, HBO…) y listas por género",
                manifestUrl = CYBERFLIX,
            ),
            StremioPreset(
                name = "Cinemeta Oficial",
                description = "Catálogos y metadatos oficiales de películas y series de IMDb y Stremio",
                manifestUrl = "https://v3-cinemeta.strem.io/manifest.json",
                /* Ya viene integrado en el motor local: instalarlo duplicaría filas. */
                bundled = true,
            ),
        )
    }
}
