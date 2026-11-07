package com.streamflixreborn.streamflix.streams

import com.streamflixreborn.streamflix.providers.AfterDarkProvider
import com.streamflixreborn.streamflix.providers.AnimeBumProvider
import com.streamflixreborn.streamflix.providers.AnyMovieProvider
import com.streamflixreborn.streamflix.providers.HiAnimeProvider
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.OtakufrProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.SflixProvider
import com.streamflixreborn.streamflix.providers.StreamingItaProvider
import com.streamflixreborn.streamflix.providers.SuperStreamProvider
import com.streamflixreborn.streamflix.providers.UnJourUnFilmProvider

/**
 * Stream-only provider registry.
 *
 * It keeps the upstream registered VOD providers and also includes the
 * unregistered upstream scraper objects that still implement getServers/getVideo.
 * Catalog/meta sources (TmdbProvider) and live IPTV catalogs are deliberately not
 * exposed here; the app keeps its existing live shelf instead.
 */
object StreamProviderRegistry {
    private val extraStreamProviders = listOf(
        AfterDarkProvider,
        AnimeBumProvider,
        AnyMovieProvider,
        HiAnimeProvider,
        OtakufrProvider,
        SflixProvider,
        StreamingItaProvider,
        SuperStreamProvider,
        UnJourUnFilmProvider,
    )

    val entries: List<Map.Entry<Provider, Provider.Companion.ProviderSupport>> by lazy {
        val merged = LinkedHashMap<Provider, Provider.Companion.ProviderSupport>()
        Provider.providers.forEach { (provider, support) ->
            if (provider !is IptvProvider) merged[provider] = support
        }
        extraStreamProviders.forEach { provider ->
            merged.putIfAbsent(provider, Provider.Companion.ProviderSupport(movies = true, tvShows = true))
        }
        merged.entries.toList()
    }
}
