package com.tanasi.streamflix.data.extension.cloudstream

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.tanasi.streamflix.data.model.BehaviorHints
import com.tanasi.streamflix.data.model.ProviderStatus
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamDto
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.model.SubtitleDto
import com.tanasi.streamflix.data.remote.InstalledExtensionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/** Executes explicitly enabled Cloudstream .cs3 packages against the pinned v4.7.0 API. */
class CloudstreamPluginEngine(
    context: Context,
    private val loader: CloudstreamExtensionLoader = CloudstreamExtensionLoader(context),
    maxConcurrentPackages: Int = 3,
) {
    private val semaphore = Semaphore(maxConcurrentPackages.coerceIn(1, 5))

    fun retainEnabledPackages(packages: List<InstalledExtensionRecord>) = loader.retainEnabledPackages(packages)

    suspend fun streams(
        packages: List<InstalledExtensionRecord>,
        meta: RemoteMeta,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null,
    ): StreamsResponse = coroutineScope {
        val enabled = packages.filter { it.enabled }
        loader.retainEnabledPackages(enabled)
        if (enabled.isEmpty()) return@coroutineScope StreamsResponse()
        val results = enabled.map { record ->
            async(Dispatchers.IO) {
                semaphore.withPermit { executePackage(record, meta, mediaType, season, episode) }
            }
        }.awaitAll()
        StreamsResponse(
            streams = results.flatMap { it.response.streams },
            subtitles = results.flatMap { it.response.subtitles },
            providers = results.flatMap { it.response.providers },
        )
    }

    private suspend fun executePackage(
        record: InstalledExtensionRecord,
        meta: RemoteMeta,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): PackageResult {
        val started = System.nanoTime()
        return try {
            val apis = loader.load(record)
            val outputs = apis.map { api -> executeApi(api, meta, mediaType, season, episode) }
            val streams = outputs.flatMap { it.streams }
            val subtitles = outputs.flatMap { it.subtitles }
            val statuses = outputs.map { output ->
                ProviderStatus(
                    id = "${record.entry.id}:${output.api.name}",
                    label = "${record.entry.name} · ${output.api.name}",
                    status = output.status,
                    streams = output.streams.size,
                    ms = output.elapsedMs,
                )
            }.ifEmpty {
                listOf(ProviderStatus(record.entry.id, record.entry.name, "error", 0, (System.nanoTime() - started) / 1_000_000))
            }
            PackageResult(StreamsResponse(streams, subtitles = subtitles, providers = statuses))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Cloudstream package ${record.entry.id} failed", error)
            PackageResult(
                StreamsResponse(
                    providers = listOf(
                        ProviderStatus(
                            id = record.entry.id,
                            label = record.entry.name,
                            status = "error",
                            streams = 0,
                            ms = (System.nanoTime() - started) / 1_000_000,
                        ),
                    ),
                ),
            )
        }
    }

    private suspend fun executeApi(
        api: MainAPI,
        meta: RemoteMeta,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): ApiResult {
        val started = System.nanoTime()
        return try {
            val data = withTimeoutOrNull(API_TIMEOUT_MS) {
                val loaded = if (api is TmdbProvider) {
                    val tmdbId = meta.tmdbId?.toIntOrNull()
                        ?: throw IllegalArgumentException("TMDB ID requerido para ${api.name}")
                    val type = if (mediaType.equals("movie", true)) "movie" else "tv"
                    api.load("""{"id":$tmdbId,"type":"$type"}""")
                } else {
                    loadBySearch(api, meta, mediaType)
                } ?: return@withTimeoutOrNull null
                extractData(loaded, season, episode)
            } ?: return ApiResult(api, emptyList(), emptyList(), "timeout", elapsed(started))
            if (data == null) return ApiResult(api, emptyList(), emptyList(), "empty", elapsed(started))

            val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val subtitles = Collections.synchronizedList(mutableListOf<com.lagradost.cloudstream3.SubtitleFile>())
            val completed = withTimeoutOrNull(LINKS_TIMEOUT_MS) {
                api.loadLinks(
                    data = data,
                    isCasting = false,
                    subtitleCallback = { subtitles.add(it) },
                    callback = { links.add(it) },
                )
            }
            val validLinks = links.filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
            val streams = validLinks.map { it.toStreamDto(api.name) }
            val subtitleDtos = subtitles.mapNotNull { file ->
                file.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { url ->
                    SubtitleDto(
                        id = "${api.name}:${file.lang}:$url",
                        url = url,
                        lang = file.lang,
                        label = file.lang,
                        provider = api.name,
                    )
                }
            }
            ApiResult(
                api = api,
                streams = streams,
                subtitles = subtitleDtos,
                status = if (completed == null) "timeout" else if (streams.isEmpty()) "empty" else "ok",
                elapsedMs = elapsed(started),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Cloudstream API ${api.name} failed", error)
            ApiResult(api, emptyList(), emptyList(), "error", elapsed(started))
        }
    }

    private suspend fun loadBySearch(api: MainAPI, meta: RemoteMeta, mediaType: String): com.lagradost.cloudstream3.LoadResponse? {
        val title = meta.name.trim().takeIf { it.isNotEmpty() } ?: return null
        val resultList = api.search(title, 1)?.items.orEmpty()
        val normalizedTitle = normalizeTitle(title)
        val best = resultList
            .map { it to similarity(normalizeTitle(it.name), normalizedTitle) }
            .filter { it.second >= MIN_TITLE_SIMILARITY }
            .maxByOrNull { (candidate, score) ->
                val typeBonus = if (mediaType.equals("movie", true)) {
                    if (candidate.type?.name?.contains("movie", true) == true) 0.1 else 0.0
                } else if (candidate.type?.name?.contains("movie", true) == true) 0.0 else 0.05
                score + typeBonus
            }
            ?.first ?: return null
        return api.load(best.url)
    }

    private fun extractData(response: com.lagradost.cloudstream3.LoadResponse, season: Int?, episode: Int?): String? = when (response) {
        is MovieLoadResponse -> response.dataUrl
        is LiveStreamLoadResponse -> response.dataUrl
        is TvSeriesLoadResponse -> chooseEpisode(response.episodes, season, episode)?.data
        is AnimeLoadResponse -> chooseEpisode(response.episodes.values.flatten(), season, episode)?.data
        else -> null
    }

    private fun chooseEpisode(entries: List<Episode>, season: Int?, episode: Int?): Episode? {
        if (episode == null) return entries.firstOrNull()
        return entries.firstOrNull { it.episode == episode && (season == null || it.season == season) }
            ?: entries.firstOrNull { it.episode == episode }
    }

    private fun ExtractorLink.toStreamDto(apiName: String): StreamDto {
        val typeHint = when (type) {
            ExtractorLinkType.M3U8 -> "hls"
            ExtractorLinkType.DASH -> "dash"
            else -> null
        }
        val requestHeaders = buildMap {
            putAll(headers)
            if (referer.isNotBlank() && keys.none { it.equals("Referer", true) }) put("Referer", referer)
        }.ifEmpty { null }
        return StreamDto(
            name = name.ifBlank { source },
            title = name,
            url = url,
            quality = Qualities.getStringByInt(quality).ifBlank { null },
            provider = apiName,
            behaviorHints = BehaviorHints(requestHeaders = requestHeaders, mediaType = typeHint),
        )
    }

    private fun normalizeTitle(value: String): String = value.lowercase()
        .replace(Regex("\\(\\d{4}\\)"), " ")
        .replace(Regex("\\b\\d{4}\\b"), " ")
        .replace(Regex("\\b(season|temporada)\\s*\\d+\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun similarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left.contains(right) || right.contains(left)) return 0.82
        val distance = levenshtein(left, right)
        return 1.0 - distance.toDouble() / maxOf(left.length, right.length)
    }

    private fun levenshtein(left: String, right: String): Int {
        val row = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            var previous = row[0]
            row[0] = i + 1
            for (j in right.indices) {
                val old = row[j + 1]
                row[j + 1] = minOf(row[j + 1] + 1, row[j] + 1, previous + if (left[i] == right[j]) 0 else 1)
                previous = old
            }
        }
        return row[right.length]
    }

    private fun elapsed(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private data class PackageResult(val response: StreamsResponse)
    private data class ApiResult(
        val api: MainAPI,
        val streams: List<StreamDto>,
        val subtitles: List<SubtitleDto>,
        val status: String,
        val elapsedMs: Long,
    )

    companion object {
        private const val TAG = "CloudstreamEngine"
        private const val MIN_TITLE_SIMILARITY = 0.65
        private const val API_TIMEOUT_MS = 90_000L
        private const val LINKS_TIMEOUT_MS = 60_000L
    }
}
