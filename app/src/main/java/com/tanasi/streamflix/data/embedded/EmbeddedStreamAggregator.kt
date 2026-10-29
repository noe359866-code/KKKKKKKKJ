package com.tanasi.streamflix.data.embedded

import com.tanasi.streamflix.data.model.BehaviorHints
import com.tanasi.streamflix.data.model.StreamDto
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.data.model.StreamsResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Una respuesta cruda del puente Python. */
data class ScraperResult(
    val provider: ProviderConfig,
    val ok: Boolean,
    val streams: List<JsonObject>,
    val meta: JsonObject? = null,
)

/**
 * Normaliza y ordena fuentes igual que el backend Node:
 * prioridad de proveedor ascendente, calidad descendente, duplicados fuera.
 */
object EmbeddedStreamAggregator {
    fun aggregate(results: List<ScraperResult>, isLive: Boolean, cacheMaxAge: Long): StreamsResponse {
        data class Candidate(val priority: Int, val qualityRank: Int, val stream: StreamDto)

        val candidates = ArrayList<Candidate>()
        results.forEach { result ->
            if (!result.ok) return@forEach
            result.streams.forEach { raw ->
                val url = raw["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
                val hints = raw["behaviorHints"] as? JsonObject
                val requestHeaders = (hints?.get("requestHeaders") as? JsonObject)
                    ?: (raw["requestHeaders"] as? JsonObject)
                val headers = buildMap {
                    fun header(name: String): String? =
                        requestHeaders?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }
                            ?.value?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val referer = header("Referer")
                        ?: raw["referer"]?.jsonPrimitive?.contentOrNull
                        ?: result.provider.referer
                    val ua = header("User-Agent")
                        ?: raw["userAgent"]?.jsonPrimitive?.contentOrNull
                        ?: result.provider.userAgent
                    if (!referer.isNullOrBlank()) put("Referer", referer)
                    if (!ua.isNullOrBlank()) put("User-Agent", ua)
                }
                val quality = normalizeQuality(
                    raw["quality"]?.jsonPrimitive?.contentOrNull
                        ?: Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.let { "${it}p" }
                        ?: "Unknown",
                )
                val name = raw["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: (result.provider.label + if (quality != "Unknown") " · $quality" else "")
                val title = raw["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(
                        result.provider.label,
                        quality.takeIf { it != "Unknown" },
                    ).joinToString(" · ")
                val streamLive = (raw["isLive"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
                    ?: isLive
                candidates += Candidate(
                    priority = result.provider.priority,
                    qualityRank = qualityRank(quality),
                    stream = StreamDto(
                        name = name,
                        title = title,
                        url = url,
                        quality = quality.takeIf { it != "Unknown" },
                        provider = result.provider.id,
                        isLive = streamLive,
                        behaviorHints = BehaviorHints(
                            requestHeaders = headers.takeIf { it.isNotEmpty() },
                            notWebReady = (hints?.get("notWebReady") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true,
                            isLive = streamLive,
                        ),
                    ),
                )
            }
        }

        val seen = HashSet<String>()
        val streams = candidates
            .sortedWith(compareBy<Candidate> { it.priority }.thenByDescending { it.qualityRank })
            .map { it.stream }
            .filter { StreamSource.looksPlayable(it.url) }
            .filter { seen.add(it.url.substringBefore('#')) }
        return StreamsResponse(streams = streams, cacheMaxAge = cacheMaxAge)
    }

    fun parseBridgeResult(provider: ProviderConfig, json: JsonObject): ScraperResult {
        val ok = (json["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val streams = (json["streams"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return ScraperResult(provider, ok, streams, json["meta"] as? JsonObject)
    }

    fun normalizeQuality(value: String): String {
        val v = value.trim().lowercase()
        return when {
            v in setOf("4k", "2160p", "uhd") -> "4K"
            v == "1080p" || v == "1080" -> "1080p"
            v == "720p" || v == "720" -> "720p"
            v == "480p" || v == "480" -> "480p"
            v == "360p" || v == "360" -> "360p"
            v.contains("cam") -> "CAM"
            v == "unknown" || v.isBlank() -> "Unknown"
            else -> value.trim()
        }
    }

    fun qualityRank(quality: String): Int = when (quality) {
        "4K" -> 5
        "1080p" -> 4
        "720p" -> 3
        "480p" -> 2
        "360p" -> 1
        else -> 0
    }
}
