package com.tanasi.streamflix.data.extension.nuvio

import com.tanasi.streamflix.data.model.BehaviorHints
import com.tanasi.streamflix.data.model.ProviderStatus
import com.tanasi.streamflix.data.model.StreamDto
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.model.SubtitleDto
import com.tanasi.streamflix.data.model.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Installed Nuvio provider source, already downloaded from a selected manifest. */
data class NuvioProviderScript(
    val id: String,
    val name: String,
    val code: String,
    val settings: Map<String, Any> = emptyMap(),
    val enabled: Boolean = true,
)

/** Nuvio's contract requires a TMDB ID; resolve that before calling this engine. */
data class NuvioPlaybackContext(
    val tmdbId: String,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
)

fun interface NuvioPluginScriptExecutor {
    suspend fun executePlugin(
        code: String,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        scraperId: String,
        scraperSettings: Map<String, Any>,
    ): List<NuvioPluginStream>
}

/** Runs opted-in Nuvio scripts and translates their results to Streamflix's player DTOs. */
class NuvioPluginEngine(
    private val runtime: NuvioPluginScriptExecutor = NuvioPluginRuntime(),
    private val maxConcurrentProviders: Int = DEFAULT_CONCURRENCY,
) {
    private val semaphore = Semaphore(maxConcurrentProviders.coerceIn(1, MAX_CONCURRENCY))

    suspend fun streams(
        providers: List<NuvioProviderScript>,
        context: NuvioPlaybackContext,
    ): StreamsResponse = coroutineScope {
        val enabled = providers.filter { it.enabled && it.code.isNotBlank() }
        if (enabled.isEmpty()) return@coroutineScope StreamsResponse()

        val results = enabled.map { provider ->
            async(Dispatchers.IO) {
                val started = System.nanoTime()
                try {
                    val output = semaphore.withPermit {
                        runtime.executePlugin(
                            code = provider.code,
                            tmdbId = context.tmdbId,
                            mediaType = normalizeMediaType(context.mediaType),
                            season = context.season,
                            episode = context.episode,
                            scraperId = provider.id,
                            scraperSettings = provider.settings,
                        )
                    }
                    ProviderResult(
                        provider = provider,
                        streams = output,
                        status = if (output.isEmpty()) "empty" else "ok",
                        elapsedMs = (System.nanoTime() - started) / 1_000_000,
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    ProviderResult(
                        provider = provider,
                        streams = emptyList(),
                        status = "error",
                        elapsedMs = (System.nanoTime() - started) / 1_000_000,
                    )
                }
            }
        }.awaitAll()

        val streams = results.flatMap { result ->
            result.streams.map { item ->
                StreamDto(
                    name = item.name?.takeIf { it.isNotBlank() } ?: result.provider.name,
                    title = item.title,
                    url = item.url,
                    quality = item.quality,
                    provider = item.provider?.takeIf { it.isNotBlank() } ?: result.provider.name,
                    behaviorHints = BehaviorHints(
                        requestHeaders = item.headers,
                        mediaType = item.type,
                    ),
                )
            }
        }
        val subtitles = results.flatMap { result ->
            result.streams.flatMap { item ->
                item.subtitles.map { subtitle ->
                    SubtitleDto(
                        id = subtitle.id,
                        url = subtitle.url,
                        lang = subtitle.lang,
                        label = SubtitleTrack.languageLabel(subtitle.lang).ifBlank { subtitle.addonName },
                        provider = result.provider.name,
                    )
                }
            }
        }
        StreamsResponse(
            streams = streams,
            subtitles = subtitles,
            providers = results.map { result ->
                ProviderStatus(
                    id = result.provider.id,
                    label = result.provider.name,
                    status = result.status,
                    streams = result.streams.size,
                    ms = result.elapsedMs,
                )
            },
        )
    }

    private fun normalizeMediaType(type: String): String =
        when (type.trim().lowercase()) {
            "movie" -> "movie"
            "series", "tv", "anime" -> "tv"
            else -> "movie"
        }

    private data class ProviderResult(
        val provider: NuvioProviderScript,
        val streams: List<NuvioPluginStream>,
        val status: String,
        val elapsedMs: Long,
    )

    companion object {
        private const val DEFAULT_CONCURRENCY = 3
        private const val MAX_CONCURRENCY = 5
    }
}
