package com.tanasi.streamflix.data.embedded

import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.RemoteVideo
import com.tanasi.streamflix.data.remote.Urls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cliente de metadatos Cinemeta para el modo autónomo.
 *
 * El servidor Node hacía de proxy; en la APK se pide directamente el addon
 * oficial de metadatos. No alojamos contenido: sólo fichas y episodios.
 */
class EmbeddedCinemeta(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val base: String = "https://v3-cinemeta.strem.io",
) {
    suspend fun catalog(
        type: String,
        id: String,
        genre: String? = null,
        skip: Int? = null,
        search: String? = null,
    ): List<RemoteMeta>? {
        val extras = buildList {
            if (!genre.isNullOrBlank()) add("genre=${Urls.encodeSegment(genre)}")
            if (skip != null && skip > 0) add("skip=$skip")
            if (!search.isNullOrBlank()) add("search=${Urls.encodeSegment(search)}")
        }
        val suffix = if (extras.isEmpty()) "" else "/" + extras.joinToString("/")
        val url = "$base/catalog/${Urls.encodeSegment(type)}/${Urls.encodeSegment(id)}$suffix.json"
        val payload = getJson(url) ?: return null
        return (payload["metas"] as? JsonArray)?.mapNotNull { normalizeMeta(it as? JsonObject, type) }
    }

    suspend fun meta(type: String, id: String): RemoteMeta? {
        val url = "$base/meta/${Urls.encodeSegment(type)}/${Urls.encodeSegment(id)}.json"
        val payload = getJson(url) ?: return null
        return normalizeMeta(payload["meta"] as? JsonObject, type)
    }

    suspend fun manifest(url: String): JsonObject? = getJson(url)

    suspend fun catalogFromBase(baseUrl: String, type: String, id: String, extras: List<String> = emptyList()): List<RemoteMeta>? {
        val suffix = if (extras.isEmpty()) "" else "/" + extras.joinToString("/")
        val url = "${baseUrl.trimEnd('/')}/catalog/${Urls.encodeSegment(type)}/${Urls.encodeSegment(id)}$suffix.json"
        val payload = getJson(url) ?: return null
        return (payload["metas"] as? JsonArray)?.mapNotNull { normalizeMeta(it as? JsonObject, type) }
    }

    private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null
                json.parseToJsonElement(body) as? JsonObject
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun normalizeMeta(raw: JsonObject?, fallbackType: String? = null): RemoteMeta? {
            if (raw == null) return null
            val id = raw.string("id") ?: raw.string("imdb_id") ?: return null
            val releaseInfo = raw.string("releaseInfo") ?: raw.string("year")
            val year = Regex("(\\d{4})").find(releaseInfo.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val rating = (raw["imdbRating"] ?: raw["rating"])?.let {
                when (it) {
                    is JsonPrimitive -> it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull()
                    else -> null
                }
            }?.takeIf { it > 0 }
            val videos = (raw["videos"] as? JsonArray)?.mapNotNull { v ->
                val obj = v as? JsonObject ?: return@mapNotNull null
                val season = obj["season"]?.jsonPrimitive?.intOrNull
                val episode = obj["episode"]?.jsonPrimitive?.intOrNull
                val vid = obj.string("id") ?: if (season != null && episode != null) "$season:$episode" else return@mapNotNull null
                RemoteVideo(
                    id = vid,
                    season = season,
                    episode = episode,
                    name = obj.string("name") ?: obj.string("title"),
                    overview = obj.string("overview"),
                    thumbnail = obj.string("thumbnail"),
                )
            }.orEmpty().sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            return RemoteMeta(
                id = id,
                type = raw.string("type") ?: fallbackType ?: "movie",
                name = raw.string("name") ?: raw.string("title") ?: id,
                poster = raw.string("poster"),
                background = raw.string("background") ?: raw.string("backdrop"),
                logo = raw.string("logo"),
                description = raw.string("description"),
                releaseInfo = releaseInfo,
                year = year,
                rating = rating,
                genres = (raw["genres"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                runtime = raw.string("runtime"),
                posterShape = raw.string("posterShape"),
                imdbId = raw.string("imdb_id") ?: id.takeIf { it.startsWith("tt") },
                videos = videos,
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
