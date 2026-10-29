package com.tanasi.streamflix.data.local

import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.model.CatalogsResponse
import com.tanasi.streamflix.data.model.MetaResponse
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.StreamsResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Inyectable: los tests JVM no necesitan cargar libpython ni un Context. */
fun interface EmbeddedRuntime {
    suspend fun call(operation: String, payload: String): String
}

/** Adaptador JSON al motor local. baseUrl se ignora deliberadamente. */
class EmbeddedContentClient(
    private val runtime: EmbeddedRuntime,
    private val metadataCatalogs: () -> Boolean = { false },
) : ContentClient {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    override suspend fun catalogs(baseUrl: String) = json.decodeFromString(
        CatalogsResponse.serializer(),
        runtime.call("catalogs", buildJsonObject { put("metadataCatalogs", metadataCatalogs()) }.toString()),
    ).catalogs

    override suspend fun catalog(baseUrl: String, type: String, id: String, search: String?, genre: String?, skip: Int?) =
        json.decodeFromString(
            RemoteCatalogResponse.serializer(),
            runtime.call("catalog", buildJsonObject {
                put("type", type)
                put("id", id)
                search?.let { put("search", it) }
                genre?.let { put("genre", it) }
                skip?.let { put("skip", it) }
            }.toString()),
        )

    override suspend fun meta(baseUrl: String, type: String, id: String) = json.decodeFromString(
        MetaResponse.serializer(), runtime.call("meta", request(type, id)),
    ).meta

    override suspend fun streams(baseUrl: String, type: String, id: String) = json.decodeFromString(
        StreamsResponse.serializer(), runtime.call("streams", request(type, id)),
    )

    private fun request(type: String, id: String) = buildJsonObject {
        put("type", type)
        put("id", id)
    }.toString()
}
