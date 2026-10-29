package com.tanasi.streamflix.data.embedded

import android.content.Context
import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Catálogos del modo autónomo.
 *
 * Lee la misma configuración que el backend Node (assets/embedded) y resuelve
 * las filas dentro de la APK: canales locales, Cinemeta y addons de catálogo
 * de terceros. No necesita un servidor en la LAN.
 */
class EmbeddedCatalogService(
    private val context: Context,
    private val cinemeta: EmbeddedCinemeta,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private data class Source(
        val kind: String,
        val type: String = "",
        val id: String = "",
        val genre: String? = null,
        val category: String? = null,
        val baseUrl: String = "",
        val addonName: String? = null,
        val limit: Int = 100,
    )

    private data class Definition(
        val descriptor: CatalogDescriptor,
        val source: Source,
        val extraNames: Set<String> = setOf("search", "skip", "genre"),
    )

    private data class LiveItem(val meta: RemoteMeta, val category: String)

    @Volatile
    private var cachedDefinitions: List<Definition>? = null

    suspend fun catalogs(): List<CatalogDescriptor> = definitions().map { it.descriptor }

    suspend fun catalog(
        type: String,
        id: String,
        search: String? = null,
        genre: String? = null,
        skip: Int? = null,
    ): RemoteCatalogResponse {
        val definition = definitions().firstOrNull {
            it.descriptor.type == type && it.descriptor.id == id
        } ?: return RemoteCatalogResponse(degraded = true)

        return try {
            val metas = when (definition.source.kind) {
                "local" -> localMetas(definition.source, search, genre, skip)
                "cinemeta" -> cinemeta.catalog(
                    type = definition.source.type,
                    id = definition.source.id,
                    genre = genre ?: definition.source.genre,
                    skip = skip,
                    search = search,
                ).orEmpty().map { meta ->
                    meta.copy(
                        type = definition.descriptor.type,
                        sourceType = definition.source.type.takeIf { it != definition.descriptor.type },
                        posterShape = meta.posterShape ?: definition.descriptor.posterShape,
                    )
                }
                "remote" -> remoteMetas(definition, search, genre, skip)
                else -> emptyList()
            }.distinctBy { it.id }.take(definition.source.limit)
            RemoteCatalogResponse(metas = metas, catalog = definition.descriptor, degraded = false)
        } catch (error: Exception) {
            AppLog.w("catálogo embebido %s/%s falló: %s", type, id, error.message)
            RemoteCatalogResponse(metas = emptyList(), catalog = definition.descriptor, degraded = true)
        }
    }

    suspend fun meta(type: String, id: String): RemoteMeta? {
        val normalized = EmbeddedIds.normalize(id)
        if (type == "tv") {
            return liveItems().firstOrNull { it.meta.id == normalized }?.meta
        }
        val baseId = normalized.substringBefore(':')
        val upstreamType = if (type == "anime") "series" else type
        return cinemeta.meta(upstreamType, baseId)?.copy(type = type)
    }

    private suspend fun definitions(): List<Definition> {
        cachedDefinitions?.let { return it }
        return withContext(Dispatchers.IO) {
            val config = readJson("embedded/catalogs.json")
            val rows = ArrayList<Definition>()
            (config["catalogs"] as? JsonArray)?.forEachIndexed { index, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                val type = obj.string("type") ?: return@forEachIndexed
                val id = obj.string("id") ?: return@forEachIndexed
                val sourceObj = obj["source"] as? JsonObject ?: return@forEachIndexed
                val kind = sourceObj.string("kind") ?: return@forEachIndexed
                val source = Source(
                    kind = kind,
                    type = sourceObj.string("type") ?: type,
                    id = sourceObj.string("id") ?: id,
                    genre = sourceObj.string("genre"),
                    category = sourceObj.string("category"),
                )
                rows += Definition(
                    descriptor = CatalogDescriptor(
                        type = type,
                        id = id,
                        name = obj.string("name") ?: id,
                        posterShape = obj.string("posterShape") ?: "poster",
                        order = obj["order"]?.jsonPrimitive?.intOrNull ?: (100 + index),
                        genres = (obj["genres"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                        source = kind,
                    ),
                    source = source,
                )
            }

            (config["addons"] as? JsonArray)?.forEach { element ->
                val addon = element as? JsonObject ?: return@forEach
                if ((addon["enabled"] as? JsonPrimitive)?.contentOrNull == "false") return@forEach
                val manifestUrl = addon.string("manifestUrl") ?: return@forEach
                val addonId = addon.string("id") ?: return@forEach
                val manifest = withTimeoutOrNull(8_000) { cinemeta.manifest(manifestUrl) } ?: return@forEach
                val baseUrl = manifestUrl.removeSuffix("/manifest.json")
                val only = (addon["only"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().toSet()
                val types = (addon["types"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().toSet()
                val limit = addon["limit"]?.jsonPrimitive?.intOrNull ?: 100
                val order = addon["order"]?.jsonPrimitive?.intOrNull ?: 100
                val addonName = manifest.string("name") ?: addon.string("name") ?: addonId
                var position = 0
                (manifest["catalogs"] as? JsonArray)?.forEach { catElement ->
                    val cat = catElement as? JsonObject ?: return@forEach
                    val remoteType = cat.string("type") ?: return@forEach
                    val remoteId = cat.string("id") ?: return@forEach
                    if (only.isNotEmpty() && remoteId !in only) return@forEach
                    if (types.isNotEmpty() && remoteType !in types) return@forEach
                    val publishedType = when (remoteType) {
                        "channel", "events", "sport" -> "tv"
                        else -> remoteType
                    }
                    if (publishedType !in setOf("movie", "series", "anime", "tv")) return@forEach
                    val extraNames = (cat["extra"] as? JsonArray)?.mapNotNull {
                        (it as? JsonObject)?.string("name")
                    }.orEmpty().toSet()
                    val safeId = remoteId.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "catalog" }
                    rows += Definition(
                        descriptor = CatalogDescriptor(
                            type = publishedType,
                            id = "${addonId}_$safeId".take(48),
                            name = cat.string("name") ?: remoteId,
                            posterShape = addon.string("posterShape") ?: "poster",
                            order = order + position,
                            source = "remote",
                            provider = addonName,
                        ),
                        source = Source(
                            kind = "remote",
                            type = remoteType,
                            id = remoteId,
                            baseUrl = baseUrl,
                            addonName = addonName,
                            limit = limit,
                        ),
                        extraNames = extraNames,
                    )
                    position += 1
                }
            }

            rows.sortedBy { it.descriptor.order }.also { cachedDefinitions = it }
        }
    }

    private suspend fun remoteMetas(
        definition: Definition,
        search: String?,
        genre: String?,
        skip: Int?,
    ): List<RemoteMeta> {
        val extras = buildList {
            if ("genre" in definition.extraNames && !genre.isNullOrBlank()) add("genre=${com.tanasi.streamflix.data.remote.Urls.encodeSegment(genre)}")
            if ("skip" in definition.extraNames && skip != null && skip > 0) add("skip=$skip")
            if ("search" in definition.extraNames && !search.isNullOrBlank()) add("search=${com.tanasi.streamflix.data.remote.Urls.encodeSegment(search)}")
        }
        return cinemeta.catalogFromBase(
            definition.source.baseUrl,
            definition.source.type,
            definition.source.id,
            extras,
        ).orEmpty().map { meta ->
            meta.copy(
                type = definition.descriptor.type,
                sourceName = definition.source.addonName,
                posterShape = meta.posterShape ?: definition.descriptor.posterShape,
            )
        }
    }

    private suspend fun localMetas(source: Source, search: String?, genre: String?, skip: Int?): List<RemoteMeta> {
        var items = liveItems()
        if (!source.category.isNullOrBlank()) {
            items = items.filter { it.category.equals(source.category, ignoreCase = true) }
        }
        if (!genre.isNullOrBlank()) {
            items = items.filter { item ->
                item.meta.genres.any { it.equals(genre, ignoreCase = true) } ||
                    item.category.equals(genre, ignoreCase = true)
            }
        }
        if (!search.isNullOrBlank()) {
            val q = search.trim()
            items = items.filter {
                it.meta.name.contains(q, ignoreCase = true) ||
                    it.meta.description?.contains(q, ignoreCase = true) == true
            }
        }
        return items.drop(skip ?: 0).map { it.meta }
    }

    private suspend fun liveItems(): List<LiveItem> = withContext(Dispatchers.IO) {
        val raw = readJson("embedded/live-catalog.json")
        (raw["items"] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            val meta = RemoteMeta(
                id = id,
                type = "tv",
                name = obj.string("name") ?: id,
                poster = obj.string("poster") ?: obj.string("logo"),
                background = obj.string("background"),
                description = obj.string("description"),
                releaseInfo = obj.string("releaseInfo") ?: "En directo",
                genres = (obj["genres"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                posterShape = "landscape",
            )
            LiveItem(meta, obj.string("category").orEmpty())
        }.orEmpty()
    }

    private fun readJson(asset: String): JsonObject =
        context.assets.open(asset).bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
