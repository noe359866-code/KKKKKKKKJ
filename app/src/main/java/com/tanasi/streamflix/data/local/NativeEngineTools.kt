package com.tanasi.streamflix.data.local

import androidx.annotation.Keep
import com.tanasi.streamflix.util.unicodeRegex
import kotlinx.serialization.json.*
import java.util.Locale

/** Operaciones del motor y del adaptador de catálogos usadas desde Python. */
@Keep
class NativeEngineTools {
    fun dispatchPlanJson(operation: String, requestJson: String): String =
        NativeEngineDispatch().plan(operation, requestJson)

    fun registryIndicesJson(rowsJson: String, allowedJson: String): String =
        NativeEngineRegistry().selectIndices(rowsJson, allowedJson)

    fun normalizeMetadataJson(rawJson: String, mediaType: String): String =
        NativeMetadataNormalizer().normalize(rawJson, mediaType)

    fun catalogEpisodeLinksJson(linksJson: String, refsJson: String, slug: String, anime: Boolean): String =
        NativeCatalogEpisodes().linkIndices(linksJson, refsJson, slug, anime)

    fun catalogEpisodeVideosJson(entriesJson: String, baseId: String): String =
        NativeCatalogEpisodes().renderVideos(entriesJson, baseId)

    /** Preserve registry insertion order and let Python retain provider object identity. */
    fun selectProviderIndicesJson(namesJson: String, explicit: String, allowedJson: String): String {
        return try {
            fun readStrings(raw: String): List<String> = Json.parseToJsonElement(raw).jsonArray.map {
                val value = it as? JsonPrimitive ?: throw IllegalArgumentException()
                if (!value.isString) throw IllegalArgumentException()
                value.content
            }
            val names = readStrings(namesJson)
            val allowed = readStrings(allowedJson).toSet()
            val exact = names.indexOf(explicit)
            JsonArray((if (exact >= 0) listOf(exact) else names.indices.filter { names[it] in allowed }).map(::JsonPrimitive)).toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    /** Query decoding and the shared token parser remain on the Python side. */
    fun declaredExpiryJson(queryJson: String, tokenJson: String): String {
        return try {
            val query = Json.parseToJsonElement(queryJson) as? JsonObject ?: return ""
            val token = Json.parseToJsonElement(tokenJson)
            var earliest = if (token is JsonNull) null else (token as? JsonPrimitive)?.longOrNull ?: return ""
            fun include(value: String) {
                val parsed = value.toLong()
                val seconds = if (parsed > 100_000_000_000L) parsed / 1000 else parsed
                earliest = earliest?.let { minOf(it, seconds) } ?: seconds
            }
            for ((key, entries) in query) {
                if (key.any { it.code > 127 || it == '\r' || it == '\n' }) return ""
                val values = entries as? JsonArray ?: return ""
                for (entry in values) {
                    val primitive = entry as? JsonPrimitive ?: return ""
                    if (!primitive.isString) return ""
                    val value = primitive.content
                    if (value.any { it.code > 127 || it == '\r' || it == '\n' }) return ""
                    if (key.lowercase(Locale.ROOT) in EXPIRY_KEYS && EXPIRY_DIGITS.matches(value)) include(value)
                    for (match in NESTED_EXPIRY.findAll(value)) include(match.groupValues[1])
                }
            }
            earliest?.toString() ?: "null"
        } catch (_: IllegalArgumentException) { "" }
    }

    fun catalogDescriptorsJson(metadataCatalogs: Boolean): String = buildJsonObject {
        put("catalogs", buildJsonArray {
            if (metadataCatalogs) CINEMETA_CATALOGS.forEachIndexed { index, (type, id, name) ->
                add(buildJsonObject {
                    put("id", "cinemeta_$id"); put("type", type); put("name", name)
                    put("posterShape", "poster"); put("order", index)
                    put("source", "cinemeta"); put("provider", "Cinemeta")
                })
            }
        })
    }.toString()

    /** Empty output requests Python fallback for Unicode digits and unbounded integers. */
    fun splitCatalogIdJson(contentId: String, mediaType: String, animeProvider: Boolean): String {
        if (contentId.any { it.code > 127 }) return ""
        val parts = contentId.split(':')
        fun numeric(part: String) = part.isNotEmpty() && part.all { it in '0'..'9' }
        fun result(base: String, season: Int?, episode: Int?) = buildJsonArray {
            add(JsonPrimitive(base))
            add(season?.let(::JsonPrimitive) ?: JsonNull)
            add(episode?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
        if (parts.size >= 3 && numeric(parts[parts.size - 2]) && numeric(parts.last())) {
            val season = parts[parts.size - 2].toIntOrNull() ?: return ""
            val episode = parts.last().toIntOrNull() ?: return ""
            return result(parts.dropLast(2).joinToString(":"), season, episode)
        }
        if (mediaType == "anime" && parts.size == 3 && animeProvider && numeric(parts.last())) {
            val episode = parts.last().toIntOrNull() ?: return ""
            return result(parts.dropLast(1).joinToString(":"), 1, episode)
        }
        return result(contentId, null, null)
    }

    /** The URL has already been parsed and origin-checked with Python's urlparse. */
    fun catalogCardRefJson(provider: String, path: String, defaultType: String, anime: Boolean): String {
        // Python's Unicode regex and integer semantics remain authoritative outside ASCII.
        if ((provider + path + defaultType).any { it.code > 127 }) return ""
        var slug = ""
        var episode: Int? = null
        var type = defaultType
        if (anime) {
            type = "anime"
            val match = when (provider) {
                "jkanime" -> Regex("([\\w.-]+)(?:/(\\d{1,5}))?").matchEntire(path)
                "animeflv" -> Regex("anime/([\\w.-]+)").matchEntire(path)
                    ?: Regex("ver/([\\w.-]+?)-(\\d{1,5})").matchEntire(path)
                else -> Regex("anime/([\\w.-]+)").matchEntire(path)
                    ?: Regex("ver/([\\w.-]+?)-episodio-(\\d{1,5})").matchEntire(path)
            }
            if (match != null) {
                slug = match.groupValues[1]
                episode = if (match.groups.size > 2) match.groups[2]?.value?.toIntOrNull() else null
            }
        } else {
            val match = Regex("^(peliculas?|movies?|ver|series?|anime)/([\\w.-]+)(?:/|$)").find(path)
            if (match != null) {
                slug = match.groupValues[2]
                type = if (match.groupValues[1] == "serie" || match.groupValues[1] == "series") "series" else "movie"
            }
        }
        if (slug.isEmpty() || slug.lowercase(Locale.ROOT) in NON_TITLES) return "null"
        val contentId = "$provider:$slug" + (episode?.let { ":$it" } ?: "")
        return buildJsonObject {
            put("id", contentId); put("type", type); put("slug", slug)
            put("episode", episode?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
    }

    fun playableDirect(path: String, query: String): Boolean =
        PLAYABLE.any { path.endsWith(it) } || MEDIA_QUERY.containsMatchIn(query)

    fun playablePage(path: String, hint: String): Boolean {
        val blockedExtension = NOT_PLAYABLE.any { path.endsWith(it) }
        val blockedPath = NOT_PLAYABLE_HINTS.any { it in path }
        if (hint in MEDIA_TYPES && !blockedExtension && !blockedPath) return true
        return !blockedExtension && !blockedPath
    }

    /** Empty output means Python must handle unusual key or value types. */
    fun normalizeHeadersJson(headersJson: String): String {
        return try {
            val headers = Json.parseToJsonElement(headersJson) as? JsonObject ?: return ""
            val result = linkedMapOf<String, JsonElement>()
            for ((key, raw) in headers) {
                if (key.any { it.code > 127 }) return ""
                if (raw is JsonNull) continue
                val value = raw as? JsonPrimitive ?: return ""
                if (!value.isString || value.content.any { it.isSurrogate() }) return ""
                if (value.content.isEmpty() || !key.matches(Regex("[A-Za-z0-9_-]+")) ||
                    '\n' in value.content || '\r' in value.content) continue
                val name = when (key.lowercase(Locale.ROOT)) {
                    "referer" -> "Referer"
                    "user-agent" -> "User-Agent"
                    "origin" -> "Origin"
                    "cookie" -> "Cookie"
                    else -> key
                }
                result[name] = value
            }
            JsonObject(result).toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    /** Indices preserve the original stream objects and the first occurrence order. */
    fun uniqueStreamIndicesJson(urlsJson: String, seenJson: String): String {
        return try {
            fun strings(raw: String) = Json.parseToJsonElement(raw).jsonArray.map {
                val value = it as? JsonPrimitive ?: throw IllegalArgumentException()
                if (!value.isString) throw IllegalArgumentException()
                value.content
            }
            val seen = strings(seenJson).toMutableSet()
            val indices = strings(urlsJson).mapIndexedNotNull { index, url ->
                if (seen.add(url.substringBefore('#'))) JsonPrimitive(index) else null
            }
            JsonArray(indices).toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    /** Returns only normalized fields, so the Python caller retains other hint objects. */
    fun normalizeStreamPartsJson(rawJson: String, providerJson: String, headersJson: String): String {
        return try {
            val raw = Json.parseToJsonElement(rawJson) as? JsonObject ?: return ""
            val provider = Json.parseToJsonElement(providerJson) as? JsonObject ?: return ""
            fun text(obj: JsonObject, key: String): String {
                val value = obj[key] ?: return ""
                if (value is JsonNull) return ""
                val field = value as? JsonPrimitive ?: throw IllegalArgumentException()
                if (!field.isString || field.content.any { it.isSurrogate() }) throw IllegalArgumentException()
                return field.content
            }
            // The Python code requires provider['label'] even when raw has a name.
            if (provider["label"] == null || provider["label"] is JsonNull) return ""
            val label = text(provider, "label")
            val name = text(raw, "name").ifEmpty { label }
            val title = text(raw, "title").ifEmpty { text(raw, "name") }.ifEmpty { label }
            val quality = text(raw, "quality")
            val normalized = normalizeHeadersJson(headersJson)
            if (normalized.isEmpty()) return ""
            val headers = Json.parseToJsonElement(normalized).jsonObject
            buildJsonObject {
                put("name", name); put("title", title); put("provider", label)
                put("headers", headers)
                if (quality.isNotEmpty()) put("quality", quality)
            }.toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    fun normalizeLanguage(value: String): String {
        val text = value.trim(::pythonSpace).lowercase(Locale.ROOT).replace(WHITESPACE, "-")
        if (text.isEmpty()) return ""
        val parts = text.split('-', limit = 2)
        val base = ALIASES[parts[0]] ?: parts[0]
        if (!base.matches(Regex("[a-z]{2,3}"))) return ""
        val region = parts.getOrElse(1) { "" }
        return if (region.matches(unicodeRegex("[a-z]{2}|\\d{3}"))) "$base-$region" else base
    }

    fun languageName(value: String): String {
        val parts = normalizeLanguage(value).split('-', limit = 2)
        val base = parts[0]
        val region = parts.getOrElse(1) { "" }
        val name = NAMES[base] ?: return ""
        if (region.isEmpty()) return name
        if (base == "es" && region == "419") return "$name (Latino)"
        if (base == "pt" && region == "br") return "$name (Brasil)"
        return "$name (${region.uppercase(Locale.ROOT)})"
    }

    fun subtitleFormat(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?').lowercase(Locale.ROOT)
        return if ('.' in clean) FORMATS[clean.substringAfterLast('.')] ?: "" else ""
    }

    /** JSON null indica rechazo; cadena vacía pide utilizar el motor anterior. */
    fun normalizeSubtitleJson(rawJson: String, providerJson: String): String {
        return try {
            val raw = Json.parseToJsonElement(rawJson) as? JsonObject ?: return ""
            val provider = Json.parseToJsonElement(providerJson) as? JsonObject ?: return ""
            fun field(obj: JsonObject, key: String): String {
                val item = obj[key] ?: return ""
                if (item is JsonNull) return ""
                val value = item as? JsonPrimitive ?: throw IllegalArgumentException()
                if (!value.isString || value.content.any { it.isSurrogate() }) throw IllegalArgumentException()
                return value.content
            }
            val url = field(raw, "url").ifEmpty { field(raw, "src") }.trim(::pythonSpace)
            if (url.isEmpty() || url.length > 2048 || !URL_SCHEME.containsMatchIn(url)) return "null"
            val path = url.substringBefore('#').substringBefore('?').lowercase(Locale.ROOT)
            if (NOT_SUBTITLE.any { path.endsWith(it) }) return "null"
            val lang = normalizeLanguage(field(raw, "lang").ifEmpty { field(raw, "language") })
            val providerLabel = field(provider, "label")
            val rawLabel = field(raw, "label").ifEmpty { field(raw, "name") }.trim(::pythonSpace)
            val label = rawLabel.ifEmpty { languageName(lang) }.ifEmpty { lang.uppercase(Locale.ROOT) }.ifEmpty { providerLabel }
            if (label.isEmpty()) return "null"
            val id = field(raw, "id").ifEmpty { url }
            val format = subtitleFormat(url).ifEmpty { subtitleFormat("." + field(raw, "format")) }
            buildJsonObject {
                put("id", id); put("url", url); put("lang", lang); put("label", label.take(64))
                if (providerLabel.isNotEmpty()) put("provider", providerLabel)
                if (format.isNotEmpty()) put("format", format)
            }.toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    /** Índices preservan la identidad de las entradas Python y su orden. */
    fun mergeSubtitleIndicesJson(collectedJson: String): String {
        return try {
            val entries = Json.parseToJsonElement(collectedJson).jsonArray
            val seen = mutableSetOf<String>()
            val spanish = mutableListOf<Int>()
            val others = mutableListOf<Int>()
            for ((index, entry) in entries.withIndex()) {
                if (entry is JsonNull) continue
                val obj = entry as? JsonObject ?: return ""
                if (obj.isEmpty()) continue
                val url = obj["url"] as? JsonPrimitive ?: return ""
                val lang = obj["lang"]
                if (!url.isString || (lang != null && lang !is JsonNull && (lang !is JsonPrimitive || !lang.isString))) return ""
                if (!seen.add(url.content)) continue
                if ((lang as? JsonPrimitive)?.content.orEmpty().startsWith("es")) spanish.add(index) else others.add(index)
                if (spanish.size + others.size >= 40) break
            }
            JsonArray((spanish + others).map(::JsonPrimitive)).toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    private companion object {
        fun pythonSpace(c: Char) = c.isWhitespace() || c == '\u0085' || c in '\u001c'..'\u001f'
        val CINEMETA_CATALOGS = listOf(
            Triple("movie", "top", "Películas populares"), Triple("series", "top", "Series populares"),
            Triple("movie", "imdbRating", "Mejor valoradas"), Triple("series", "imdbRating", "Series mejor valoradas"),
            Triple("movie", "year", "Estrenos recientes"), Triple("anime", "top", "Anime popular"),
        )
        val EXPIRY_KEYS = setOf("e", "exp", "expires", "expire", "expiry")
        val EXPIRY_DIGITS = Regex("[0-9]{9,13}")
        val NESTED_EXPIRY = Regex("(?:^|[~&])exp=([0-9]{9,13})(?:$|[~&])")
        val PLAYABLE = listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".ts")
        val NOT_PLAYABLE = listOf(".html", ".htm", ".php", ".asp", ".aspx", ".jsp", ".json", ".txt", ".css", ".js", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".ico", ".xml")
        val NOT_PLAYABLE_HINTS = listOf("/embed/", "/player/", "/reproductor/", "/pelicula/", "/peliculas/", "/serie/", "/anime/", "/ver/")
        val MEDIA_TYPES = setOf("hls", "dash", "mp4")
        val MEDIA_QUERY = Regex("\\.(?:m3u8|mpd|mp4|m4v)(?:[&#]|$)", RegexOption.IGNORE_CASE)
        val NON_TITLES = setOf("anime", "animes", "directorio", "buscar", "busqueda", "search", "browse", "programacion", "horario", "calendario", "generos", "genero", "login", "registro", "contacto", "politica", "privacidad", "terminos", "dmca", "peliculas", "series", "ver", "estrenos", "page", "category")
        val WHITESPACE = unicodeRegex("[_\\s\\x1c-\\x1f]+")
        val URL_SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
        val NOT_SUBTITLE = listOf(".html", ".htm", ".php", ".asp", ".aspx", ".jsp", ".json", ".txt", ".css", ".js", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".mp4", ".m3u8", ".mpd")
        val FORMATS = mapOf("vtt" to "vtt", "webvtt" to "vtt", "srt" to "srt", "sub" to "srt", "ass" to "ass", "ssa" to "ass", "ttml" to "ttml", "dfxp" to "ttml", "xml" to "ttml")
        val ALIASES = mapOf("spa" to "es", "esp" to "es", "spanish" to "es", "espanol" to "es", "español" to "es", "castellano" to "es", "latino" to "es", "eng" to "en", "english" to "en", "ingles" to "en", "inglés" to "en", "por" to "pt", "portuguese" to "pt", "portugues" to "pt", "fre" to "fr", "fra" to "fr", "french" to "fr", "ger" to "de", "deu" to "de", "german" to "de", "ita" to "it", "italian" to "it", "jpn" to "ja", "japanese" to "ja", "kor" to "ko", "korean" to "ko", "zho" to "zh", "chi" to "zh", "chinese" to "zh", "rus" to "ru", "russian" to "ru")
        val NAMES = mapOf("es" to "Español", "en" to "Inglés", "pt" to "Portugués", "fr" to "Francés", "de" to "Alemán", "it" to "Italiano", "ja" to "Japonés", "ko" to "Coreano", "zh" to "Chino", "ru" to "Ruso")
    }
}
