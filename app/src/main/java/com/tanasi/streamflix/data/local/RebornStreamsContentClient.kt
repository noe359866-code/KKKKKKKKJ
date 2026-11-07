package com.tanasi.streamflix.data.local

import android.content.Context
import com.streamflixreborn.streamflix.streams.ResolvedStream
import com.streamflixreborn.streamflix.streams.StreamOnlyResolver
import com.streamflixreborn.streamflix.streams.StreamRequest
import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.model.BehaviorHints
import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamDto
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.model.SubtitleDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Inyectable para tests JVM: el módulo Reborn sólo devuelve streams. */
fun interface RebornStreamSource {
    suspend fun streams(request: StreamRequest): List<ResolvedStream>
}

/**
 * Decorador del motor autónomo: conserva catálogos/meta del motor existente y
 * añade únicamente streams del port Streamflix Reborn2. No publica catálogos,
 * fichas ni filas de Home procedentes de los scrapers Reborn.
 */
class RebornStreamsContentClient(
    private val base: ContentClient,
    private val reborn: RebornStreamSource,
) : ContentClient {
    override suspend fun catalogs(baseUrl: String): List<CatalogDescriptor> = base.catalogs(baseUrl)

    override suspend fun catalog(
        baseUrl: String,
        type: String,
        id: String,
        search: String?,
        genre: String?,
        skip: Int?,
    ): RemoteCatalogResponse? = base.catalog(baseUrl, type, id, search, genre, skip)

    override suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta? = base.meta(baseUrl, type, id)

    override suspend fun streams(baseUrl: String, type: String, id: String): StreamsResponse = coroutineScope {
        val baseDeferred = async { base.streams(baseUrl, type, id) }
        val rebornDeferred = async {
            try {
                val request = requestFor(baseUrl, type, id) ?: return@async emptyList()
                withTimeoutOrNull(REBORN_TIMEOUT_MS) { reborn.streams(request) }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }

        val baseResponse = baseDeferred.await()
        val extra = rebornDeferred.await()
        if (extra.isEmpty()) return@coroutineScope baseResponse

        val merged = ArrayList<StreamDto>(baseResponse.streams.size + extra.size)
        val seen = HashSet<String>()
        baseResponse.streams.forEach { stream ->
            if (seen.add(normalizeUrl(stream.url))) merged += stream
        }
        val subtitles = baseResponse.subtitles.toMutableList()
        val subtitleSeen = subtitles.map { normalizeUrl(it.url) }.toHashSet()
        extra.forEach { stream ->
            if (seen.add(normalizeUrl(stream.url))) merged += stream.toDto()
            stream.subtitles.forEach { subtitle ->
                if (subtitleSeen.add(normalizeUrl(subtitle.url))) {
                    subtitles += SubtitleDto(
                        id = subtitle.url,
                        url = subtitle.url,
                        lang = subtitle.lang,
                        label = subtitle.label,
                        provider = stream.provider,
                    )
                }
            }
        }
        StreamsResponse(streams = merged, cacheMaxAge = baseResponse.cacheMaxAge, subtitles = subtitles)
    }

    private suspend fun requestFor(baseUrl: String, type: String, id: String): StreamRequest? {
        val episode = StreamRequest.parseEpisode(id)
        if (!type.equals("movie", true) && episode == null) return null
        val baseId = if (episode != null) id.split(':').dropLast(2).joinToString(":") else id
        val meta = try {
            base.meta(baseUrl, type, baseId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val title = meta?.name?.takeIf { it.isNotBlank() } ?: return null
        val year = meta.year ?: meta.releaseInfo?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
        val imdb = meta.imdbId ?: baseId.takeIf { it.startsWith("tt") }
        return StreamRequest(
            type = if (type.equals("movie", true)) "movie" else "series",
            id = id,
            title = title,
            year = year,
            season = episode?.first,
            episode = episode?.second,
            imdbId = imdb,
        )
    }

    companion object {
        private const val REBORN_TIMEOUT_MS = 18_000L

        fun forContext(context: Context, base: ContentClient): RebornStreamsContentClient =
            RebornStreamsContentClient(base) { request ->
                StreamOnlyResolver.initialize(context.applicationContext)
                StreamOnlyResolver.streams(request)
            }

        private fun normalizeUrl(url: String): String = url.trim().lowercase()

        private fun ResolvedStream.toDto(): StreamDto = StreamDto(
            name = name,
            title = quality?.let { "$provider · $it" } ?: provider,
            url = url,
            quality = quality,
            provider = provider,
            isLive = false,
            behaviorHints = BehaviorHints(
                requestHeaders = headers.ifEmpty { null },
                notWebReady = headers.isNotEmpty(),
                isLive = false,
                mediaType = mediaType,
            ),
        )
    }
}
