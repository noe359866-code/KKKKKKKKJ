package com.tanasi.streamflix.data.remote

import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.util.AddonSettings

/**
 * Enruta cada petición al modo elegido por el usuario.
 *
 * Por defecto la APK es autónoma (scrapers embebidos). El addon Node externo
 * sigue disponible como opción avanzada sin cambiar la UI ni los repositorios.
 */
class RoutingContentClient(
    private val settings: AddonSettings,
    private val remote: ContentClient,
    private val embedded: Lazy<ContentClient>,
) : ContentClient {
    private fun active(): ContentClient = if (settings.useEmbedded) embedded.value else remote

    override suspend fun streams(baseUrl: String, type: String, id: String): StreamsResponse =
        active().streams(baseUrl, type, id)

    override suspend fun catalogs(baseUrl: String): List<CatalogDescriptor> = active().catalogs(baseUrl)

    override suspend fun catalog(
        baseUrl: String,
        type: String,
        id: String,
        search: String?,
        genre: String?,
        skip: Int?,
    ): RemoteCatalogResponse? = active().catalog(baseUrl, type, id, search, genre, skip)

    override suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta? =
        active().meta(baseUrl, type, id)

    override suspend fun ping(baseUrl: String): Boolean = active().ping(baseUrl)
}
