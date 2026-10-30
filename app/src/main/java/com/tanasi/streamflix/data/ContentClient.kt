package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.stremio.StremioAddonClient
import com.tanasi.streamflix.data.stremio.StremioAddonManager
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Mismo contrato para el motor en el APK y el addon remoto opcional. */
interface ContentClient {
    suspend fun catalogs(baseUrl: String): List<CatalogDescriptor>
    suspend fun catalog(
        baseUrl: String,
        type: String,
        id: String,
        search: String? = null,
        genre: String? = null,
        skip: Int? = null,
    ): RemoteCatalogResponse?
    suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta?
    suspend fun streams(baseUrl: String, type: String, id: String): StreamsResponse
}

/**
 * Encamina peticiones entre el motor local (autónomo) o remoto, e integra
 * catálogos y streams de addons de Stremio de terceros cuando están configurados.
 */
class RoutingContentClient(
    private val settings: AddonSettings,
    private val embedded: ContentClient,
    private val remote: ContentClient,
    private val addonManager: StremioAddonManager? = null,
    private val stremioClient: StremioAddonClient? = null,
) : ContentClient {
    private fun current(): ContentClient = if (settings.autonomous) embedded else remote

    override suspend fun catalogs(baseUrl: String): List<CatalogDescriptor> {
        /* Un motor caído no debe dejar el Home vacío si hay addons de Stremio
         * instalados: se pierden las filas del motor, no las de los addons. */
        val baseCatalogs = try {
            current().catalogs(baseUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emptyList()
        }
        val addons = addonManager?.getEnabledAddons().orEmpty()
        val addonCatalogs = if (stremioClient != null && addons.isNotEmpty()) {
            try {
                stremioClient.getCatalogs(addons)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
        /* El orden lo fija la fuente: Cinemeta 0..n (arte de calidad, va
         * primero), después la TV en vivo y los addons. */
        return (baseCatalogs + addonCatalogs).sortedBy { it.order }
    }

    override suspend fun catalog(
        baseUrl: String,
        type: String,
        id: String,
        search: String?,
        genre: String?,
        skip: Int?,
    ): RemoteCatalogResponse? {
        val parsed = StremioAddonClient.parseCatalogId(id)
        if (parsed != null && addonManager != null && stremioClient != null) {
            val (addonId, catalogId) = parsed
            val addon = addonManager.getAddon(addonId)
            if (addon != null && addon.enabled) {
                return stremioClient.getCatalog(
                    addon = addon,
                    catalogId = catalogId,
                    type = type,
                    search = search,
                    genre = genre,
                    skip = skip,
                )
            }
        }
        return current().catalog(baseUrl, type, id, search, genre, skip)
    }

    override suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta? {
        val baseMeta = current().meta(baseUrl, type, id)
        if (baseMeta != null && (baseMeta.videos.isNotEmpty() || type == "movie")) {
            return baseMeta
        }
        val addons = addonManager?.getEnabledAddons().orEmpty()
        if (stremioClient != null && addons.isNotEmpty()) {
            val addonMeta = stremioClient.getMeta(addons, type, id)
            if (addonMeta != null) {
                return if (baseMeta != null) {
                    baseMeta.copy(
                        description = baseMeta.description ?: addonMeta.description,
                        background = baseMeta.background ?: addonMeta.background,
                        poster = baseMeta.poster ?: addonMeta.poster,
                        videos = if (baseMeta.videos.isEmpty()) addonMeta.videos else baseMeta.videos,
                    )
                } else addonMeta
            }
        }
        return baseMeta
    }

    override suspend fun streams(baseUrl: String, type: String, id: String): StreamsResponse = coroutineScope {
        val addons = addonManager?.getEnabledAddons().orEmpty().filter {
            it.supportsResource("stream", type, id)
        }

        if (stremioClient == null || addons.isEmpty()) {
            return@coroutineScope current().streams(baseUrl, type, id)
        }

        val baseDeferred = async {
            try {
                current().streams(baseUrl, type, id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                StreamsResponse()
            }
        }

        val addonDeferred = async {
            try {
                stremioClient.getStreams(addons, type, id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

        val baseResponse = baseDeferred.await()
        val addonStreams = addonDeferred.await()

        val merged = baseResponse.streams + addonStreams
        StreamsResponse(
            streams = merged,
            cacheMaxAge = baseResponse.cacheMaxAge,
        )
    }
}
