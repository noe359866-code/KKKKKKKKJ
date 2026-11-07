package com.streamflixreborn.streamflix.streams

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.AniWorldProvider
import com.streamflixreborn.streamflix.providers.AnimeOnlineNinjaProvider
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.SerienStreamProvider
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stream-only facade over the vendored Reborn providers/extractors.
 *
 * It intentionally never calls getHome/getMovies/getTvShows/getGenre/getPeople.
 * search/getTvShow/getEpisodesBySeason are used only as internal target lookup;
 * their metadata is not returned to the app. The public result is playable media.
 */
object StreamOnlyResolver {
    private const val MAX_PARALLEL_PROVIDERS = 6
    private const val PROVIDER_TIMEOUT_MS = 7_000L
    private const val VIDEO_TIMEOUT_MS = 5_000L
    private const val GLOBAL_TIMEOUT_MS = 16_000L
    private const val MAX_SERVERS_PER_PROVIDER = 3

    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            StreamFlixApp.initialize(context)
            UserPreferences.providerLanguage = "es"
            runCatching { AniWorldProvider.initialize(context.applicationContext) }
            runCatching { SerienStreamProvider.initialize(context.applicationContext) }
            runCatching { AnimeOnlineNinjaProvider.init(context.applicationContext) }
            initialized = true
        }
    }

    /** Normal entry point for the app: streams only, never catalogs. */
    suspend fun streams(request: StreamRequest): List<ResolvedStream> = resolveInternal(request)

    private suspend fun resolveInternal(request: StreamRequest): List<ResolvedStream> {
        if (request.title.isBlank()) return emptyList()
        if (!request.isMovie && !request.isEpisode) return emptyList()
        val providers = StreamProviderRegistry.entries
            .filter { it.key !is IptvProvider }
            .filter { if (request.isMovie) it.value.movies else it.value.tvShows }
            .map { it.key }
            .sortedWith(compareBy<Provider> { languagePriority(it.language) }.thenBy { it.name })
        if (providers.isEmpty()) return emptyList()

        val semaphore = Semaphore(MAX_PARALLEL_PROVIDERS)
        val channel = Channel<List<ResolvedStream>>(Channel.UNLIMITED)
        val completed = AtomicInteger(0)
        val results = mutableListOf<ResolvedStream>()
        val seen = HashSet<String>()
        val deadline = System.currentTimeMillis() + GLOBAL_TIMEOUT_MS

        val jobs = mutableListOf<Job>()
        coroutineScope {
            providers.forEach { provider ->
                jobs += launch(Dispatchers.IO) {
                    try {
                        val streams = semaphore.withPermit {
                            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { resolveProvider(provider, request) }
                        }.orEmpty()
                        channel.send(streams)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A broken scraper must not hide the remaining sources.
                        runCatching { channel.send(emptyList()) }
                    } finally {
                        completed.incrementAndGet()
                    }
                }
            }

            while (completed.get() < providers.size && System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val chunk = withTimeoutOrNull(remaining) { channel.receive() } ?: break
                chunk.forEach { stream ->
                    if (seen.add(stream.url)) results += stream
                }
            }
            jobs.forEach { it.cancel() }
            jobs.forEach { runCatching { it.cancelAndJoin() } }
            channel.close()
        }
        return results
    }

    private fun languagePriority(language: String): Int = when {
        language.startsWith("es", ignoreCase = true) -> 0
        language.startsWith("en", ignoreCase = true) -> 1
        else -> 2
    }

    private suspend fun resolveProvider(provider: Provider, request: StreamRequest): List<ResolvedStream> {
        UserPreferences.currentProvider = provider
        val items = provider.search(request.title)
        return if (request.isMovie) {
            val movies = items.filterIsInstance<Movie>()
            val index = TitleMatcher.best(movies.map { it.title to it.released?.get(Calendar.YEAR) }, request)
                ?: return emptyList()
            val movie = movies[index]
            val videoType = Video.Type.Movie(
                id = movie.id,
                title = movie.title,
                releaseDate = movie.released?.format("yyyy-MM-dd") ?: request.year?.toString().orEmpty(),
                poster = movie.poster.orEmpty(),
                imdbId = request.imdbId ?: movie.imdbId,
            )
            resolveServers(provider, movie.id, videoType)
        } else {
            val shows = items.filterIsInstance<TvShow>()
            val index = TitleMatcher.best(shows.map { it.title to it.released?.get(Calendar.YEAR) }, request)
                ?: return emptyList()
            val show = provider.getTvShow(shows[index].id)
            val seasonNumber = request.season ?: return emptyList()
            val episodeNumber = request.episode ?: return emptyList()
            val season = show.seasons.firstOrNull { it.number == seasonNumber }
                ?: show.seasons.firstOrNull { it.number == 1 && seasonNumber == 0 }
                ?: return emptyList()
            val episodes = season.episodes.ifEmpty { provider.getEpisodesBySeason(season.id) }
            val episode = episodes.firstOrNull { it.number == episodeNumber } ?: return emptyList()
            val videoType = Video.Type.Episode(
                id = episode.id,
                number = episode.number,
                title = episode.title,
                poster = episode.poster ?: show.poster,
                overview = episode.overview,
                tvShow = Video.Type.Episode.TvShow(
                    id = show.id,
                    title = show.title,
                    poster = show.poster,
                    banner = show.banner,
                    releaseDate = show.released?.format("yyyy-MM-dd"),
                    imdbId = request.imdbId ?: show.imdbId,
                ),
                season = Video.Type.Episode.Season(number = season.number, title = season.title),
            )
            resolveServers(provider, episode.id, videoType)
        }
    }

    private suspend fun resolveServers(provider: Provider, id: String, videoType: Video.Type): List<ResolvedStream> {
        val servers = provider.getServers(id, videoType).take(MAX_SERVERS_PER_PROVIDER)
        val results = mutableListOf<ResolvedStream>()
        for (server in servers) {
            val video = withTimeoutOrNull(VIDEO_TIMEOUT_MS) { provider.getVideo(server) } ?: continue
            VideoConverter.convert(provider.name, server, video)?.let { results += it }
        }
        return results
    }
}
