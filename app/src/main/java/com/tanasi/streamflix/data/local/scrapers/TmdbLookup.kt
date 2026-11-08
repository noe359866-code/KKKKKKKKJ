package com.tanasi.streamflix.data.local.scrapers

import android.util.Base64
import androidx.annotation.Keep
import com.tanasi.streamflix.data.local.ScraperHttpTransport
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.Locale

/** Consultas opcionales de metadatos TMDB; no imprime ni persiste la clave. */
@Keep
class TmdbLookup internal constructor(
    private val base: String,
    private val fetch: (String, Int) -> String?,
) {
    constructor(transport: ScraperHttpTransport) : this("https://api.themoviedb.org/3", { url, timeout ->
        try {
            val response = Json.parseToJsonElement(transport.request(url, "GET", """{"Accept":"application/json"}""", timeout, null)).jsonObject
            val status = response["status"]?.jsonPrimitive?.intOrNull
            if (status == null || status !in 200..299) null else {
                val bytes = Base64.decode(response["body"]!!.jsonPrimitive.content, Base64.DEFAULT)
                // La ruta Python maneja BOM y charsets no UTF-8: pedir respaldo.
                try {
                    val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    if (decoded.startsWith('\uFEFF')) "\u0000" else decoded
                } catch (_: Exception) { "\u0000" }
            }
        } catch (_: Exception) { null }
    })

    /** Vacío significa que se debe utilizar el código anterior, no que no hubo resultado. */
    fun findByImdbJson(imdb: String, mediaType: String, key: String, timeoutMs: Int): String {
        if (key.isEmpty() || imdb.isEmpty()) return "{}"
        if (!safeKey(key)) return ""
        val query = "external_source=imdb_id&language=es-MX&api_key=$key"
        val path = encode(imdb).replace("%2F", "/").replace("+", "%20")
        val data = document("$base/find/$path?$query", timeoutMs) ?: return ""
        val primary = if (mediaType.lowercase(Locale.ROOT) in setOf("tv", "series")) "tv" else "movie"
        for (section in listOf(primary, if (primary == "tv") "movie" else "tv")) {
            val bucket = data["${section}_results"] ?: continue
            if (bucket is JsonNull) continue
            val entries = bucket as? JsonArray ?: return ""
            for (entry in entries) {
                val picked = pick(entry, section) ?: return ""
                if (picked["title"]?.jsonPrimitive?.content.orEmpty().isNotEmpty()) return picked.toString()
            }
        }
        return "{}"
    }

    fun searchJson(title: String, year: String, mediaType: String, key: String, timeoutMs: Int): String {
        if (key.isEmpty() || title.isBlank()) return "{}"
        if (!safeKey(key) || year.any { it.code > 127 }) return ""
        val section = if (mediaType.lowercase(Locale.ROOT) in setOf("tv", "series")) "tv" else "movie"
        val yearPart = year.take(4)
        val yearQuery = if (yearPart.isNotEmpty() && yearPart.all { it in '0'..'9' }) {
            "&${if (section == "movie") "year" else "first_air_date_year"}=$yearPart"
        } else ""
        val url = "$base/search/$section?query=${encode(title)}&language=es-MX&include_adult=false$yearQuery&api_key=$key"
        val data = document(url, timeoutMs) ?: return ""
        val bucket = data["results"] ?: return "{}"
        if (bucket is JsonNull) return "{}"
        for (entry in bucket as? JsonArray ?: return "") {
            val picked = pick(entry, section) ?: return ""
            if (picked["title"]?.jsonPrimitive?.content.orEmpty().isNotEmpty()) return picked.toString()
        }
        return "{}"
    }

    private fun document(url: String, timeoutMs: Int): JsonObject? {
        val body = fetch(url, timeoutMs.coerceIn(1000, 5000)) ?: return null
        // json.loads en Python admite valores adicionales (NaN, etc.). En esos
        // casos el puente conserva el comportamiento anterior.
        if (!AnimeScraperTools.StrictJson(body).valid()) return null
        return try { Json.parseToJsonElement(body) as? JsonObject } catch (_: IllegalArgumentException) { null }
    }

    private fun pick(value: JsonElement, section: String): JsonObject? {
        val entry = value as? JsonObject ?: return JsonObject(emptyMap())
        fun first(vararg keys: String): String {
            for (key in keys) {
                val value = entry[key] ?: continue
                if (value is JsonNull) continue
                val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException()
                if (!primitive.isString) throw IllegalArgumentException()
                if (primitive.content.isNotEmpty()) return primitive.content
            }
            return ""
        }
        return try {
            val title = first("title", "name", "original_title", "original_name")
            val original = first("original_title", "original_name")
            val date = first("release_date", "first_air_date")
            // Python corta por caracteres Unicode; Kotlin por unidades UTF-16.
            if (date.any { it.code > 127 }) return null
            val overview = first("overview")
            buildJsonObject {
                put("title", pythonStrip(title)); put("original_title", pythonStrip(original))
                put("year", date.take(4)); put("media_type", section)
                put("tmdb_id", entry["id"] ?: JsonNull); put("overview", pythonStrip(overview))
            }
        } catch (_: IllegalArgumentException) { null }
    }

    private fun pythonStrip(value: String): String = value.trim { it.isWhitespace() || it == '\u0085' || it in '\u001c'..'\u001f' }
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("%7E", "~").replace("*", "%2A")
    private fun safeKey(key: String): Boolean = key.all { it.isLetterOrDigit() && it.code < 128 || it in "-_.~" }
}
