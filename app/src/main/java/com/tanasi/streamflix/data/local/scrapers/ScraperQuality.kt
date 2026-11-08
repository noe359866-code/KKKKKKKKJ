package com.tanasi.streamflix.data.local.scrapers

import androidx.annotation.Keep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import java.util.Locale

/** Inferencia compartida de calidad de los proveedores, sin etiquetas inventadas. */
@Keep
class ScraperQuality {
    /** Puente para el motor mientras los otros proveedores siguen en Python. */
    fun guessJson(values: String): String = guess(
        Json.parseToJsonElement(values).jsonArray.map { it.jsonPrimitive.content }
    ).orEmpty()

    companion object {
        private val strong = listOf(
            "(?:2160p?|4k|uhd)" to "4K", "(?:1440p|2k)" to "1440p",
            "(?:1080p|1080|fhd|full[\\s_.-]?hd)" to "1080p", "(?:720p|720)" to "720p",
            "576p" to "576p", "480p" to "480p", "360p" to "360p",
        ).map { (pattern, label) -> Regex("(?<![a-z0-9])$pattern(?![a-z0-9])") to label }
        private val weak = listOf("hd" to "720p", "sd" to "SD", "(?:cam|ts)" to "CAM")
            .map { (pattern, label) -> Regex("(?<![a-z0-9])$pattern(?![a-z0-9])") to label }

        fun guess(values: List<String>): String? {
            val texts = values.map {
                Normalizer.normalize(it, Normalizer.Form.NFKD)
                    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
            }.filter { it.isNotEmpty() }
            for (text in texts) for ((pattern, label) in strong) {
                if (pattern.containsMatchIn(text)) return label
            }
            for (text in texts) {
                if ("://" in text || text.length > 32) continue
                for ((pattern, label) in weak) if (pattern.containsMatchIn(text)) return label
            }
            return null
        }
    }
}
