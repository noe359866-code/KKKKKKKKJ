package com.tanasi.streamflix.data.local.scrapers

import androidx.annotation.Keep
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Scraper NOVA nativo. El motor todavía usa Python para coordinar los demás proveedores. */
@Keep
class NovaScraper internal constructor(
    private val base: String,
    private val cinemeta: String,
    private val http: OkHttpClient,
) {
    constructor() : this("https://novahd.cc", "https://v3-cinemeta.strem.io", OkHttpClient())

    // Interfaz JSON estable para el motor embebido; no devuelve URLs en los logs.
    fun scrape(payloadJson: String): String {
        val payload = Json.parseToJsonElement(payloadJson).jsonObject
        val deadline = System.nanoTime() + (payload.text("timeoutMs").toLongOrNull() ?: 8000L)
            .coerceIn(100L, 120_000L) * 1_000_000
        var media = if (payload.text("type").lowercase() in setOf("series", "show", "tv", "anime")) "show" else "movie"
        var id = payload.text("id").ifBlank { payload.text("canal") }.trim()
        repeat(3) {
            val prefix = listOf("novahd:", "tv:", "vod:").firstOrNull { id.startsWith(it, ignoreCase = true) }
            if (prefix != null) id = id.substring(prefix.length).trim()
        }
        val path = runCatching { URI(id).path.orEmpty() }.getOrDefault("")
        val urlId = if (id.startsWith("http", true)) Regex("^/([ms])/(\\d+)(?:$|/)", RegexOption.IGNORE_CASE).find(path) else null
        if (urlId != null) {
            media = if (urlId.groupValues[1].equals("s", true)) "show" else "movie"
            id = urlId.groupValues[2]
        } else if (':' in id) {
            val head = norm(id.substringBefore(':'))
            val explicit = when (head) {
                "movie", "movies", "film", "pelicula" -> "movie"
                "show", "shows", "series", "serie", "tv" -> "show"
                else -> null
            }
            if (explicit != null && id.substringAfter(':').isNotBlank()) {
                media = explicit
                id = id.substringAfter(':').trim()
            }
        }
        id = id.trim().trim('/')
        val imdb = Regex("^tt\\d{5,9}$", RegexOption.IGNORE_CASE)
        val kind = when {
            id.matches(Regex("^\\d{1,8}$")) -> "tmdb"
            id.matches(imdb) -> "imdb"
            else -> "title"
        }
        val meta = mutableMapOf<String, JsonElement>(
            "provider" to JsonPrimitive("novahd"), "type" to JsonPrimitive(media),
            "requested" to buildJsonObject { put("kind", kind); put("value", id) },
        )
        fun result(streams: List<JsonObject>, reason: String? = null): String {
            if (reason != null) meta["reason"] = JsonPrimitive(reason)
            return buildJsonObject { put("streams", JsonArray(streams)); put("meta", JsonObject(meta)) }.toString()
        }
        val season = payload.text("season")
        val episode = payload.text("episode")
        if (media == "show" && (season.isBlank() || episode.isBlank())) return result(emptyList(), "no_episode")
        fun get(url: String, headers: Map<String, String> = emptyMap()): JsonObject? {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) return null
            return try {
                val client = http.newBuilder().callTimeout(minOf(remaining, 6000L), TimeUnit.MILLISECONDS).build()
                val builder = Request.Builder().url(url).header("Accept", "application/json, text/plain, */*")
                headers.forEach { (key, value) -> if (value.isNotBlank()) builder.header(key, value) }
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.let { body ->
                        // Límites de tamaño y tiempo también aplican a respuestas defectuosas.
                        val source = body.source()
                        source.request(1_500_001)
                        if (source.buffer.size > 1_500_000) null
                        else Json.parseToJsonElement(source.readUtf8()).jsonObject
                    }
                }
            } catch (_: Exception) { null }
        }
        fun lookupImdb(value: String): String {
            for (type in listOf(media, if (media == "movie") "show" else "movie")) {
                val typePath = if (type == "show") "series" else "movie"
                val url = cinemeta.toHttpUrl().newBuilder().addPathSegments("meta/$typePath/$value.json").build()
                val found = get(url.toString())?.obj("meta") ?: continue
                val tmdb = listOf("moviedb_id", "tmdb_id", "moviedbId").firstNotNullOfOrNull { key -> found.text(key).takeIf { it.isNotBlank() } }
                if (tmdb != null) return tmdb
            }
            return ""
        }
        var tmdb = if (kind == "tmdb") id else ""
        var imdbId = payload.text("imdbId").ifBlank { payload.text("baseId") }
        if (!imdbId.matches(imdb)) imdbId = if (kind == "imdb") id else ""
        if (tmdb.isEmpty() && imdbId.isNotEmpty()) tmdb = lookupImdb(imdbId)
        if (tmdb.isEmpty() && kind == "title" && id.isNotEmpty()) {
            val typePath = if (media == "show") "series" else "movie"
            val search = cinemeta.toHttpUrl().newBuilder().addPathSegments("catalog/$typePath/top")
                .addPathSegment("search=$id.json").build()
            val items = get(search.toString())?.array("metas").orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.text("imdb_id").ifBlank { it.text("id") }.matches(imdb) }
            val wanted = norm(id)
            val hit = items.firstOrNull { norm(it.text("name")) == wanted }
                ?: items.firstOrNull { norm(it.text("name")).contains(wanted) } ?: items.firstOrNull()
            if (hit != null) {
                meta["cinemeta_hit"] = JsonPrimitive(hit.text("name"))
                imdbId = hit.text("imdb_id").ifBlank { hit.text("id") }
                tmdb = lookupImdb(imdbId)
            }
        }
        if (imdbId.isNotEmpty()) meta["imdb"] = JsonPrimitive(imdbId)
        if (tmdb.isBlank()) return result(emptyList(), "not_found")
        meta["tmdb"] = JsonPrimitive(tmdb)
        val url = base.toHttpUrl().newBuilder().addPathSegments("api/sources")
            .addQueryParameter("tmdbId", tmdb).addQueryParameter("type", media).apply {
                if (media == "show") { addQueryParameter("season", season); addQueryParameter("episode", episode) }
            }.build()
        val referer = payload.text("referer").ifBlank { "$base/" }
        val agent = payload.text("userAgent").ifBlank { DEFAULT_AGENT }
        val data = get(url.toString(), mapOf("Referer" to referer, "User-Agent" to agent))
            ?: return result(emptyList(), "site_unreachable")
        if (data["sources"] !is JsonArray) return result(emptyList(), "api_error")
        meta["page"] = JsonPrimitive(url.toString())
        val preferred = payload.text("novahdLanguages").ifBlank { "Español,Latino,Castellano,Spanish" }.split(',').map { norm(it.trim()) }.filter { it.isNotEmpty() }
        val only = payload.text("novahdOnlyLanguages").split(',').map { norm(it.trim()) }.filter { it.isNotEmpty() }
        val seen = mutableSetOf<String>()
        val rows = data.array("sources").mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull null
            val link = item.text("url").trim()
            if (!(link.startsWith("https://", true) || link.startsWith("http://", true)) || !seen.add(link)) return@mapIndexedNotNull null
            val raw = item.text("language"); val original = item.text("origAudio") == "true"
            val language = languageLabel(raw).ifBlank { if (original) "Audio original" else "" }
            val keys = listOf(norm(language), norm(raw)).filter { it.isNotEmpty() }
            if (only.isNotEmpty() && keys.none { it in only }) return@mapIndexedNotNull null
            val rank = preferred.indexOfFirst { want -> keys.any { it.startsWith(want) } }.let { if (it < 0) preferred.size else it }
            Row(item, link, language, rank, original, index)
        }.sortedWith(compareBy<Row> { if (it.rank < preferred.size) 0 else if (it.original) 1 else 2 }
            .thenBy { it.rank }.thenByDescending { qualityRank(it.item.text("quality")) }
            .thenBy { if (it.item.text("codecs").lowercase() in setOf("", "h264", "avc", "avc1")) 0 else 1 }.thenBy { it.index })
        val streams = rows.take(12).map { row ->
            val item = row.item
            val provider = item.text("provider").trim()
            val tags = listOf(row.language, provider).filter { it.isNotBlank() }
            val quality = qualityLabel(item.text("quality").trim())
            val codec = item.text("codecs").trim()
            val kindHint = item.text("type").lowercase().takeIf { it in setOf("hls", "dash", "mp4") } ?: mediaKind(row.url)
            buildJsonObject {
                put("url", row.url); put("name", tags.joinToString(" · ").ifBlank { "NOVA" })
                put("title", (listOf("NOVA") + tags + listOfNotNull(quality.takeIf { it.isNotEmpty() }, codec.uppercase().takeIf { codec.isNotEmpty() && codec.lowercase() !in setOf("h264", "avc", "avc1") })).joinToString(" · "))
                if (quality.isNotEmpty()) put("quality", quality)
                put("referer", referer); put("userAgent", agent); put("isLive", false)
                put("behaviorHints", buildJsonObject { put("notWebReady", false); if (kindHint.isNotEmpty()) put("mediaType", kindHint) })
            }
        }
        meta["sources"] = JsonPrimitive(data.array("sources").size)
        meta["streams"] = JsonPrimitive(streams.size)
        meta["languages"] = JsonArray(rows.map { it.language }.filter { it.isNotBlank() }.distinct().map(::JsonPrimitive))
        meta["providers"] = JsonArray(rows.map { it.item.text("provider").trim() }.filter { it.isNotBlank() }.distinct().sorted().map(::JsonPrimitive))
        meta["subtitles"] = JsonArray(data.array("subtitles").mapNotNull { element ->
            val sub = element as? JsonObject ?: return@mapNotNull null
            if (sub.text("url").isBlank()) return@mapNotNull null
            buildJsonObject { put("url", sub.text("url")); put("label", sub.text("label").ifBlank { sub.text("language") }); put("lang", sub.text("lang")) }
        })
        return result(streams, if (streams.isEmpty()) "no_streams" else null)
    }

    private data class Row(val item: JsonObject, val url: String, val language: String, val rank: Int, val original: Boolean, val index: Int)
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = (this[key] as? JsonArray)?.toList().orEmpty()
    private fun norm(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    private fun qualityRank(value: String) = when (norm(value).trim().substringBefore(' ')) { "4k", "2160p" -> 5; "1080p", "fhd" -> 4; "720p", "hd" -> 3; "480p", "sd" -> 2; "360p" -> 1; else -> 0 }
    private fun qualityLabel(value: String) = ScraperQuality.guess(listOf(value)) ?: value
    private fun mediaKind(url: String): String {
        val path = runCatching { URI(url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return when { path.endsWith(".m3u8") || path.endsWith(".m3u") -> "hls"; path.endsWith(".mpd") -> "dash"; path.endsWith(".mp4") || path.endsWith(".m4v") -> "mp4"; else -> "" }
    }
    private fun languageLabel(value: String): String {
        val key = value.trim().lowercase(Locale.ROOT).replace('_', '-')
        return LANGUAGES[key] ?: LANGUAGES[key.split(Regex("[\\s(/]"))[0]] ?: value.trim()
    }
    private companion object {
        const val DEFAULT_AGENT = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val LANGUAGES = mapOf("english" to "Inglés", "en" to "Inglés", "spanish" to "Español", "es" to "Español", "es-la" to "Español (Latino)", "latino" to "Español (Latino)", "portuguese" to "Portugués", "pt" to "Portugués", "pt-br" to "Portugués (Brasil)", "french" to "Francés", "fr" to "Francés", "italian" to "Italiano", "it" to "Italiano", "german" to "Alemán", "de" to "Alemán", "japanese" to "Japonés", "ja" to "Japonés", "korean" to "Coreano", "ko" to "Coreano", "chinese" to "Chino", "zh" to "Chino", "zh-cn" to "Chino", "cantonese" to "Cantonés", "hindi" to "Hindi", "hi" to "Hindi", "arabic" to "Árabe", "ar" to "Árabe", "russian" to "Ruso", "ru" to "Ruso", "turkish" to "Turco", "tr" to "Turco", "thai" to "Tailandés", "th" to "Tailandés", "indonesian" to "Indonesio", "id" to "Indonesio", "vietnamese" to "Vietnamita", "vi" to "Vietnamita", "polish" to "Polaco", "pl" to "Polaco", "dutch" to "Neerlandés", "nl" to "Neerlandés", "swedish" to "Sueco", "sv" to "Sueco", "danish" to "Danés", "da" to "Danés", "norwegian" to "Noruego", "no" to "Noruego", "finnish" to "Finés", "fi" to "Finés", "greek" to "Griego", "el" to "Griego", "hebrew" to "Hebreo", "he" to "Hebreo", "czech" to "Checo", "cs" to "Checo", "hungarian" to "Húngaro", "hu" to "Húngaro", "romanian" to "Rumano", "ro" to "Rumano", "ukrainian" to "Ucraniano", "uk" to "Ucraniano", "filipino" to "Filipino", "tl" to "Filipino", "malay" to "Malayo", "ms" to "Malayo", "tamil" to "Tamil", "ta" to "Tamil", "telugu" to "Telugu", "te" to "Telugu")
    }
}
