package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.util.AddonSettings

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

/** Nunca intenta un servidor como respaldo implícito del modo autónomo. */
class RoutingContentClient(
    private val settings: AddonSettings,
    private val embedded: ContentClient,
    private val remote: ContentClient,
) : ContentClient {
    private fun current(): ContentClient = if (settings.autonomous) embedded else remote

    override suspend fun catalogs(baseUrl: String) = current().catalogs(baseUrl)
    override suspend fun catalog(baseUrl: String, type: String, id: String, search: String?, genre: String?, skip: Int?) =
        current().catalog(baseUrl, type, id, search, genre, skip)
    override suspend fun meta(baseUrl: String, type: String, id: String) = current().meta(baseUrl, type, id)
    override suspend fun streams(baseUrl: String, type: String, id: String) = current().streams(baseUrl, type, id)
}
