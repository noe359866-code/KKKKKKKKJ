package com.tanasi.streamflix.data.embedded

import android.content.Context
import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.remote.ContentClient
import kotlinx.serialization.json.Json

/**
 * Cliente de contenido autónomo: todo ocurre dentro de la APK.
 *
 * - Catálogos y fichas: assets + Cinemeta/addons de catálogo por HTTP.
 * - Fuentes: scrapers Python embebidos con Chaquopy.
 *
 * `baseUrl` se ignora; existe sólo para compartir contrato con el modo externo.
 */
class EmbeddedContentClient(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : ContentClient {
    private val appContext = context.applicationContext
    private val cinemeta = EmbeddedCinemeta(json = json)
    private val catalogService = EmbeddedCatalogService(appContext, cinemeta, json)
    private val streamService by lazy {
        val providersRaw = appContext.assets.open("embedded/providers.json").bufferedReader().use { it.readText() }
        EmbeddedStreamService(
            bridge = PythonScraperBridge(appContext, json),
            providers = EmbeddedProviders(json, providersRaw),
            cinemeta = cinemeta,
        )
    }

    override suspend fun streams(baseUrl: String, type: String, id: String): StreamsResponse =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            streamService.streams(type, id)
        }

    override suspend fun catalogs(baseUrl: String): List<CatalogDescriptor> = catalogService.catalogs()

    override suspend fun catalog(
        baseUrl: String,
        type: String,
        id: String,
        search: String?,
        genre: String?,
        skip: Int?,
    ): RemoteCatalogResponse = catalogService.catalog(type, id, search, genre, skip)

    override suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta? =
        catalogService.meta(type, id)

    override suspend fun ping(baseUrl: String): Boolean = true
}
