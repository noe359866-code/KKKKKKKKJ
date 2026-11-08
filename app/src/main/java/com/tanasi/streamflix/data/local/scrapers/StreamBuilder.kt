package com.tanasi.streamflix.data.local.scrapers

import kotlinx.serialization.json.*
import java.util.Locale

/** Construye los streams de anime y de películas a partir de señales ya resueltas. */
internal object StreamBuilder {
    // La cadena vacía pide al puente que conserve el comportamiento Python.
    fun build(provider: String, display: String, signalsJson: String, userAgent: String): String {
        val signals = try { Json.parseToJsonElement(signalsJson) as? JsonArray ?: return "" }
            catch (_: IllegalArgumentException) { return "" }
        val result = mutableListOf<JsonObject>()
        for (entry in signals) {
            val signal = entry as? JsonObject ?: return ""
            // Python tolera valores dinámicos; fuera del contrato normal de
            // strings/null se deja que siga construyendo el stream anterior.
            fun stringValue(key: String): String? {
                val value = signal[key] ?: return null
                if (value is JsonNull) return null
                val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException()
                if (!primitive.isString) throw IllegalArgumentException()
                return primitive.content
            }
            try {
                val url = stringValue("url") ?: return ""
                val server = stringValue("server").orEmpty().ifEmpty { stringValue("label").orEmpty().ifEmpty { "Servidor" } }
                val quality = ScraperQuality.guess(listOf(server, stringValue("quality").orEmpty(), url))
                val referer = stringValue("referer").orEmpty()
                val mediaType = stringValue("media_type").orEmpty()
                if (mediaType.any { it.code > 127 }) return ""
                result += buildJsonObject {
                    put("url", url.trim { it.isWhitespace() || it == '\u0085' || it in '\u001c'..'\u001f' })
                    put("name", display)
                    put("title", "$provider · $display · $server")
                    put("behaviorHints", buildJsonObject {
                        put("notWebReady", false)
                        if (mediaType.isNotEmpty()) put("mediaType", mediaType.lowercase(Locale.ROOT))
                    })
                    put("isLive", false)
                    if (quality != null) put("quality", quality)
                    if (referer.isNotEmpty()) put("referer", referer)
                    if (userAgent.isNotEmpty()) put("userAgent", userAgent)
                }
            } catch (_: IllegalArgumentException) { return "" }
        }
        return JsonArray(result).toString()
    }
}
