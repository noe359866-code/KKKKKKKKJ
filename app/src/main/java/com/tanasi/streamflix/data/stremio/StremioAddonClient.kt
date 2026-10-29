package com.tanasi.streamflix.data.stremio

import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.MetaResponse
import com.tanasi.streamflix.data.model.StreamDto
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.model.StremioAddon
import com.tanasi.streamflix.data.model.StremioManifest
import com.tanasi.streamflix.data.remote.AddonException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP para comunicarse con addons de Stremio v3.
 */
class StremioAddonClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson(),
) {

    suspend fun fetchManifest(manifestUrl: String): StremioManifest = withContext(Dispatchers.IO) {
        val url = StremioUrls.normalizeManifestUrl(manifestUrl)
        if (url.isBlank()) throw AddonException("URL de manifest vacía")
        val body = get(url)
        try {
            json.decodeFromString(StremioManifest.serializer(), body)
        } catch (e: Exception) {
            throw AddonException("Manifest inválido: ${e.message}", e)
        }
    }

    /**
     * Construye los CatalogDescriptors publicados por los addons habilitados.
     */
    fun getCatalogs(addons: List<StremioAddon>): List<CatalogDescriptor> {
        val result = mutableListOf<CatalogDescriptor>()
        var order = 200
        for (addon in addons) {
            if (!addon.enabled) continue
            if (!addon.supportsResource("catalog")) continue
            for (cat in addon.catalogs) {
                val catName = cat.name?.takeIf { it.isNotBlank() } ?: cat.id
                val shape = if (cat.type.equals("tv", ignoreCase = true)) "landscape" else "poster"
                result += CatalogDescriptor(
                    type = cat.type,
                    id = encodeCatalogId(addon.id, cat.id),
                    name = "$catName (${addon.name})",
                    posterShape = shape,
                    order = order++,
                    source = "stremio_addon",
                    provider = addon.name,
                )
            }
        }
        return result
    }

    /**
     * Pide una fila de catálogo a un addon de Stremio.
     */
    suspend fun getCatalog(
        addon: StremioAddon,
        catalogId: String,
        type: String,
        search: String? = null,
        genre: String? = null,
        skip: Int? = null,
    ): RemoteCatalogResponse? = try {
        val url = StremioUrls.catalog(
            baseUrl = addon.baseUrl,
            type = type,
            id = catalogId,
            search = search,
            genre = genre,
            skip = skip,
        )
        val body = get(url)
        json.decodeFromString(RemoteCatalogResponse.serializer(), body)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Consulta streams en paralelo a todos los addons que declaren soportar 'stream'.
     */
    suspend fun getStreams(
        addons: List<StremioAddon>,
        type: String,
        id: String,
    ): List<StreamDto> = coroutineScope {
        val activeAddons = addons.filter { it.supportsResource("stream", type, id) }
        if (activeAddons.isEmpty()) return@coroutineScope emptyList()

        activeAddons.map { addon ->
            async(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(ADDON_STREAM_TIMEOUT_MS) {
                        val url = StremioUrls.stream(addon.baseUrl, type, id)
                        val body = get(url)
                        val response = json.decodeFromString(StreamsResponse.serializer(), body)
                        response.streams.map { stream ->
                            stream.copy(
                                provider = stream.provider?.takeIf { it.isNotBlank() } ?: addon.name,
                                name = stream.name.ifBlank { addon.name },
                            )
                        }
                    } ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    /**
     * Consulta metadatos en un addon de Stremio que soporte 'meta'.
     */
    suspend fun getMeta(
        addons: List<StremioAddon>,
        type: String,
        id: String,
    ): RemoteMeta? {
        val candidates = addons.filter { it.supportsResource("meta", type, id) }
        for (addon in candidates) {
            try {
                val url = StremioUrls.meta(addon.baseUrl, type, id)
                val body = get(url)
                val metaResp = json.decodeFromString(MetaResponse.serializer(), body)
                if (metaResp.meta != null) return metaResp.meta
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Siguiente candidato
            }
        }
        return null
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Streamflix/1.1 (Stremio Addon Client)")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AddonException("El addon respondió ${response.code} en ${request.url.encodedPath}")
                }
                if (body.isBlank()) throw AddonException("Respuesta vacía del addon")
                body
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AddonException) {
            throw e
        } catch (e: IOException) {
            throw AddonException("No se pudo conectar con ${request.url.host}", e)
        }
    }

    companion object {
        const val PREFIX_ADDON = "addon:"
        const val ADDON_STREAM_TIMEOUT_MS = 8000L

        fun encodeCatalogId(addonId: String, catalogId: String): String =
            "$PREFIX_ADDON$addonId:$catalogId"

        fun parseCatalogId(compositeId: String): Pair<String, String>? {
            if (!compositeId.startsWith(PREFIX_ADDON)) return null
            val remainder = compositeId.removePrefix(PREFIX_ADDON)
            val colon = remainder.indexOf(':')
            if (colon <= 0) return null
            val addonId = remainder.substring(0, colon)
            val catalogId = remainder.substring(colon + 1)
            return addonId to catalogId
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}
