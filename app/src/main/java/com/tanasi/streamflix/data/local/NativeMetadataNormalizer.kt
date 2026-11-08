package com.tanasi.streamflix.data.local

import kotlinx.serialization.json.*

/** JSON normalización de metadatos; "" solicita el respaldo Python para tipos no compatibles. */
internal class NativeMetadataNormalizer {
    fun normalize(rawJson: String, mediaType: String): String {
        return try {
            val raw = Json.parseToJsonElement(rawJson) as? JsonObject ?: return ""
            if (mediaType.any { it.isSurrogate() }) return ""
            fun text(obj: JsonObject, key: String): String {
                val element = obj[key] ?: return ""
                if (element is JsonNull) return ""
                val primitive = element as? JsonPrimitive ?: throw IllegalArgumentException()
                if (!primitive.isString || primitive.content.any { it.isSurrogate() }) throw IllegalArgumentException()
                return primitive.content
            }
            fun integer(element: JsonElement?): Int {
                val primitive = element as? JsonPrimitive ?: throw IllegalArgumentException()
                if (primitive is JsonNull) throw IllegalArgumentException()
                val value = primitive.content
                if (value.any { it.code > 127 }) throw IllegalArgumentException()
                return value.trim(::pythonSpace).toIntOrNull() ?: throw IllegalArgumentException()
            }
            val contentId = text(raw, "id").ifEmpty { text(raw, "imdb_id") }.trim(::pythonSpace)
            if (contentId.isEmpty()) return "null"
            val type = text(raw, "type").ifEmpty { mediaType }
            val name = text(raw, "name").ifEmpty { text(raw, "title") }.ifEmpty { contentId }
            val result = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(contentId), "type" to JsonPrimitive(type), "name" to JsonPrimitive(name))
            for (key in OPTIONAL_TEXT) {
                val value = text(raw, key)
                if (value.isNotEmpty()) result[key] = JsonPrimitive(value)
            }
            fun yearText(): String {
                val value = raw["year"] ?: return ""
                if (value is JsonNull) return ""
                val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException()
                if (primitive.isString) return text(raw, "year")
                val digits = primitive.content
                if (!digits.matches(Regex("-?(?:0|[1-9][0-9]*)"))) throw IllegalArgumentException()
                return if (digits == "0") "" else digits
            }
            val release = text(raw, "releaseInfo").ifEmpty { yearText() }
            if (release.isNotEmpty()) {
                if (release.any { it.code > 127 }) return ""
                result["releaseInfo"] = JsonPrimitive(release)
                Regex("[0-9]{4}").find(release)?.value?.toIntOrNull()?.let { result["year"] = JsonPrimitive(it) }
            }
            fun ratingField(key: String): String {
                val value = raw[key] ?: return ""
                if (value is JsonNull) return ""
                val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException()
                val content = primitive.content
                if (!primitive.isString && (content == "true" || content == "false")) throw IllegalArgumentException()
                if (!primitive.isString && content.toDoubleOrNull() == 0.0) return ""
                return content
            }
            val rating = ratingField("imdbRating").ifEmpty { ratingField("rating") }
            if (rating.isNotEmpty()) {
                val decimal = rating.trim(::pythonSpace)
                if (!DECIMAL.matches(decimal)) throw IllegalArgumentException()
                val value = decimal.toDoubleOrNull() ?: throw IllegalArgumentException()
                if (value.isFinite() && value > 0 && value <= 10) result["rating"] = JsonPrimitive(value)
            }
            val genreElement = raw["genres"].takeUnless { it == null || it is JsonNull || it == JsonArray(emptyList()) }
                ?: raw["genre"]
            val genres = when (genreElement) {
                null, JsonNull -> JsonArray(emptyList())
                is JsonArray -> JsonArray(genreElement.mapNotNull {
                    if (it is JsonNull) null else {
                        val value = it as? JsonPrimitive ?: throw IllegalArgumentException()
                        if (!value.isString || value.content.any { c -> c.isSurrogate() }) throw IllegalArgumentException()
                        value.content.takeIf(String::isNotEmpty)?.let(::JsonPrimitive)
                    }
                })
                // A non-list yields [] in Python, but a falsey non-list can select genre.
                else -> throw IllegalArgumentException()
            }
            result["genres"] = genres
            val videos = raw["videos"]
            if (videos != null && videos !is JsonNull) {
                val list = videos as? JsonArray ?: throw IllegalArgumentException()
                val normalized = mutableListOf<Triple<Int, Int, JsonObject>>()
                for (entry in list) {
                    val video = entry as? JsonObject ?: continue
                    val season = integer(video["season"])
                    val episode = integer(video["episode"])
                    if (season !in 0..100 || episode !in 0..10000) continue
                    val id = text(video, "id").ifEmpty { "$contentId:$season:$episode" }
                    val row = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id), "season" to JsonPrimitive(season), "episode" to JsonPrimitive(episode))
                    for (key in VIDEO_TEXT) text(video, key).takeIf(String::isNotEmpty)?.let { row[key] = JsonPrimitive(it) }
                    normalized.add(Triple(season, episode, JsonObject(row)))
                }
                if (normalized.isNotEmpty()) result["videos"] = JsonArray(normalized.sortedWith(compareBy({ it.first }, { it.second })).map { it.third })
            }
            JsonObject(result).toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    private companion object {
        fun pythonSpace(c: Char) = c.isWhitespace() || c == '\u0085' || c in '\u001c'..'\u001f'
        val DECIMAL = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        val OPTIONAL_TEXT = listOf("poster", "background", "logo", "description", "runtime", "sourceName", "_page", "_detail", "home", "away", "league", "startTime", "status")
        val VIDEO_TEXT = listOf("name", "overview", "thumbnail")
    }
}
