package com.tanasi.streamflix.data.embedded

import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.util.AppLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Orquestación de fuentes en modo autónomo.
 *
 * Equivale al `streamService` del backend Node: selecciona proveedores por
 * tipo, los ejecuta en paralelo con un tope, normaliza, ordena por prioridad y
 * cachea el resultado (live 2 h, VOD 6 h, vacío 2 min).
 */
class EmbeddedStreamService(
    private val bridge: PythonScraperBridge,
    private val providers: EmbeddedProviders,
    private val cinemeta: EmbeddedCinemeta,
) {
    private data class CacheEntry(val response: StreamsResponse, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun streams(type: String, id: String): StreamsResponse {
        val parsed = EmbeddedIds.parse(id, type)
        val key = "${type.lowercase()}:${parsed.raw}"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return it.response }

        val selected = providers.forContent(parsed.isLive)
        if (selected.isEmpty()) return StreamsResponse(emptyList(), 120)

        /* Título/año desde Cinemeta ayudan a los scrapers cuando el id es IMDb. */
        var title: String? = null
        var year: Int? = null
        if (!parsed.isLive) {
            val meta = withTimeoutOrNull(6_000) {
                cinemeta.meta(if (type == "anime") "series" else type, parsed.base)
            }
            title = meta?.name
            year = meta?.year
        }

        val results = coroutineScope {
            selected.map { provider ->
                async {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        bridge.run(provider, payload(provider, parsed, type, title, year))
                    } ?: ScraperResult(provider, ok = false, streams = emptyList())
                }
            }.awaitAll()
        }

        val ttlSeconds = if (parsed.isLive) LIVE_TTL_SECONDS else VOD_TTL_SECONDS
        val response = EmbeddedStreamAggregator.aggregate(results, parsed.isLive, ttlSeconds)
        val effectiveTtl = if (response.streams.isEmpty()) MISS_TTL_SECONDS else ttlSeconds
        cache[key] = CacheEntry(response, now + effectiveTtl * 1000)
        if (response.streams.isEmpty()) {
            AppLog.w("sin fuentes embebidas para %s/%s", type, id)
        }
        return response
    }

    private fun payload(
        provider: ProviderConfig,
        parsed: MediaId,
        type: String,
        title: String?,
        year: Int?,
    ): JsonObject = buildJsonObject {
        put("provider", provider.id)
        put("type", type)
        put("id", parsed.raw)
        put("baseId", parsed.base)
        parsed.imdbId?.let { put("imdbId", it) }
        parsed.season?.let { put("season", it) }
        parsed.episode?.let { put("episode", it) }
        put("isLive", parsed.isLive)
        put("isSports", parsed.isSports)
        put("referer", provider.referer)
        put("userAgent", provider.userAgent)
        put("timeoutMs", PROVIDER_TIMEOUT_MS - 500)
        title?.let { put("title", it) }
        year?.let { put("year", it) }
        provider.options?.forEach { (key, value) -> put(key, value) }
    }

    companion object {
        const val PROVIDER_TIMEOUT_MS = 15_000L
        const val LIVE_TTL_SECONDS = 7_200L
        const val VOD_TTL_SECONDS = 21_600L
        const val MISS_TTL_SECONDS = 120L
    }
}
