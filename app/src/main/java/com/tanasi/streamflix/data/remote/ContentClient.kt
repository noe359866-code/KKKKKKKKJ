package com.tanasi.streamflix.data.remote

import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamsResponse

/**
 * Contrato de datos del Home, ficha y reproductor.
 *
 * Lo implementan dos clientes:
 *  - [AddonClient]: servidor Node externo (modo avanzado).
 *  - `EmbeddedContentClient`: scrapers y catálogos dentro de la APK.
 *
 * `baseUrl` se conserva en la firma por compatibilidad con el modo externo;
 * el cliente embebido lo ignora.
 */
interface ContentClient {
    suspend fun streams(baseUrl: String, type: String, id: String): StreamsResponse
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
    suspend fun ping(baseUrl: String): Boolean
}
