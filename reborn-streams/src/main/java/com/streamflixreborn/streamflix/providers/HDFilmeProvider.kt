package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TmdbUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import retrofit2.Retrofit
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Body
import com.streamflixreborn.streamflix.utils.UserPreferences

object HDFilmeProvider : Provider {

    override val name: String = "HDFilme"

    private const val DEFAULT_BASE_URL = "https://hdfilme.cafe/"

    override val baseUrl: String
        get() = UserPreferences.resolveProviderBaseUrl(name, DEFAULT_BASE_URL)
    override val logo: String = "$baseUrl/templates/hdfilme/images/apple-touch-icon.png"
    override val language: String = "de"

    private const val USER_AGENT = "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

    private interface HDFilmeService {
        companion object {
            fun build(baseUrl: String): HDFilmeService {
                val clientBuilder = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)

                val client = clientBuilder
                    .addInterceptor(RedirectInterceptor())
                    .dns(DnsResolver.doh)
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(HDFilmeService::class.java)
            }

            private class RedirectInterceptor : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    var request = chain.request()
                    var response = chain.proceed(request)

                    while (response.isRedirect) {
                        val location = response.header("Location") ?: break
                        val newUrl = request.url.resolve(location) ?: break

                        request = request.newBuilder()
                            .url(newUrl)
                            .method(request.method, request.body)
                            .build()

                        response.close()
                        response = chain.proceed(request)
                    }
                    return response
                }
            }
        }

        @Headers(USER_AGENT)
        @GET(".")
        suspend fun getHome(): Document

        @Headers(USER_AGENT)
        @GET("filme1/")
        suspend fun getMovies(): Document

        @Headers(USER_AGENT)
        @GET("filme1/page/{page}/")
        suspend fun getMovies(@Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET("serien/")
        suspend fun getTvShows(): Document

        @Headers(USER_AGENT)
        @GET("serien/page/{page}/")
        suspend fun getTvShows(@Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET
        suspend fun getPage(@Url url: String): Document

        @Headers(USER_AGENT)
        @GET
        suspend fun getRawPage(@Url url: String): ResponseBody

        @Headers(USER_AGENT)
        @GET("{path}page/{page}/")
        suspend fun getPaged(
            @Path(value = "path", encoded = true) path: String,
            @Path("page") page: Int
        ): Document

        @Headers(USER_AGENT, "Content-Type: application/json")
        @POST
        suspend fun postJson(@Url url: String, @Body body: RequestBody): ResponseBody

    }

    @Volatile
    private var cachedService: HDFilmeService? = null

    @Volatile
    private var cachedServiceBaseUrl: String? = null

    private val service: HDFilmeService
        get() {
            val currentBaseUrl = baseUrl.trimEnd('/') + "/"
            val currentService = cachedService

            if (currentService != null && cachedServiceBaseUrl == currentBaseUrl) {
                return currentService
            }

            return synchronized(this) {
                val synchronizedService = cachedService

                if (
                    synchronizedService != null &&
                    cachedServiceBaseUrl == currentBaseUrl
                ) {
                    synchronizedService
                } else {
                    HDFilmeService.build(currentBaseUrl).also {
                        cachedService = it
                        cachedServiceBaseUrl = currentBaseUrl
                    }
                }
            }
        }
    private data class SitemapEntry(val url: String, val searchableSlug: String)

    @Volatile private var sitemapEntries: List<SitemapEntry>? = null
    private val sitemapMutex = Mutex()

    override suspend fun getHome(): List<Category> {
        val doc = service.getHome()
        val categories = mutableListOf<Category>()

        val sliderItems = doc.select("ul.glide__slides li.glide__slide").mapNotNull { parseSliderItem(it) }
        if (sliderItems.isNotEmpty()) {
            categories.add(Category(name = Category.FEATURED, list = sliderItems))
        }

        doc.select("section.hd-row").forEach { section ->
            val category = parseRowSection(section)
            if (category != null) {
                categories.add(category)
            }
        }

        return categories
    }

    private fun parseSliderItem(el: Element): Movie? {
        val titleLogo = el.selectFirst(".hd-slide-title img.hd-slide-logo")?.attr("alt")?.trim()
        val bgImgAlt = el.selectFirst(".hd-slide-bg img")?.attr("alt")?.trim()
        val titleText = el.selectFirst(".hd-slide-title")?.text()?.trim()
        val title = titleLogo.takeIf { !it.isNullOrBlank() }
            ?: bgImgAlt.takeIf { !it.isNullOrBlank() }
            ?: titleText.takeIf { !it.isNullOrBlank() }
            ?: return null

        val cta = el.selectFirst("a.hd-slide-cta") ?: return null
        val href = cta.attr("href").trim().ifBlank { return null }

        val bannerUrl = el.selectFirst(".hd-slide-bg img")?.attr("src") ?: ""
        val banner = normalizeUrl(bannerUrl)

        val ratingText = el.selectFirst(".hd-slide-badge")?.text()?.replace("★", "")?.trim()
        val rating = ratingText?.toDoubleOrNull()

        return Movie(
            id = href,
            title = title,
            banner = banner,
            rating = rating
        )
    }

    private fun parseRowSection(section: Element): Category? {
        val sectionTitle = section.selectFirst(".hd-row-title")?.text()?.trim() ?: return null
        val cards = section.select(".hd-scroller a.hd-c")
        if (cards.isEmpty()) return null

        val items = cards.mapNotNull { card ->
            val href = card.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val titleEl = card.selectFirst(".hd-c-title, .hd-cw-title")
            val title = titleEl?.text()?.trim()
                .takeIf { !it.isNullOrBlank() }
                ?: card.attr("title").trim()
                .takeIf { !it.isNullOrBlank() }
                ?: return@mapNotNull null

            val imgEl = card.selectFirst(".hd-c-img img")
            val imgSrc = imgEl?.attr("src")?.trim() ?: ""
            val poster = normalizeUrl(imgSrc)

            val meta = card.selectFirst(".hd-c-meta, .hd-cw-meta")?.text()?.trim() ?: ""
            val rating = Regex("""★\s*([\d.]+)""").find(meta)?.groupValues?.get(1)?.toDoubleOrNull()

            val isTvShow = sectionTitle.contains("Serien", ignoreCase = true) || href.contains("/serien/", ignoreCase = true)

            if (isTvShow) {
                TvShow(
                    id = href,
                    title = title,
                    poster = poster,
                    rating = rating
                )
            } else {
                Movie(
                    id = href,
                    title = title,
                    poster = poster,
                    rating = rating
                )
            }
        }

        if (items.isEmpty()) return null
        return Category(name = sectionTitle, list = items)
    }

    private fun parseGridItem(el: Element): Movie? {
        val titleElement = el.selectFirst("h3.line-clamp-2.text-sm.mt-1.font-light.leading-snug") ?: return null
        val title = titleElement.text().trim()

        val linkElement = el.selectFirst("a.block.relative[href]") ?: return null
        val href = linkElement.attr("href").trim()

        val posterUrl = el.selectFirst("img")?.attr("data-src") ?: ""
        val poster = normalizeUrl(posterUrl)

        val quality = el.selectFirst("span.absolute")?.text()?.trim()

        return Movie(
            id = href,
            title = title,
            poster = poster,
            quality = quality
        )
    }

    private fun parseGridItemAsTvShow(el: Element): TvShow? {
        val titleElement = el.selectFirst("h3.line-clamp-2.text-sm.mt-1.font-light.leading-snug") ?: return null
        val title = titleElement.text().trim()

        val linkElement = el.selectFirst("a.block.relative[href]") ?: return null
        val href = linkElement.attr("href").trim()

        val posterUrl = el.selectFirst("img")?.attr("data-src") ?: ""
        val poster = normalizeUrl(posterUrl)

        val quality = el.selectFirst("span.absolute")?.text()?.trim()

        return TvShow(
            id = href,
            title = title,
            poster = poster,
            quality = quality
        )
    }

    private fun parseSidebarItemAsMovie(el: Element): Movie? {
        val href = el.attr("href").trim()
        if (href.isBlank()) return null

        val title = el.selectFirst("figcaption.hidden")?.text()?.trim()
            ?: el.selectFirst("h4.movie-title")?.text()?.trim()
            ?: return null

        val posterUrl = el.selectFirst("img")?.attr("data-src") ?: ""
        val poster = normalizeUrl(posterUrl)

        return Movie(
            id = href,
            title = title,
            poster = poster
        )
    }

    private fun parseSidebarItemAsTvShow(el: Element): TvShow? {
        val href = el.attr("href").trim()
        if (href.isBlank()) return null

        val title = el.selectFirst("figcaption.hidden")?.text()?.trim()
            ?: el.selectFirst("h4.movie-title")?.text()?.trim()
            ?: return null

        val posterUrl = el.selectFirst("img")?.attr("data-src") ?: ""
        val poster = normalizeUrl(posterUrl)

        return TvShow(
            id = href,
            title = title,
            poster = poster
        )
    }

    private fun normalizeUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return baseUrl + (if (url.startsWith("/")) url else "/$url")
    }

    private fun normalizeExternalUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http") -> url
        else -> url
    }

    private fun extractImdbId(doc: Document): String? {
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        return Regex("var\\s+imdb\\s*=\\s*['\"](tt\\d+)['\"]")
            .find(scripts)?.groupValues?.get(1)
    }

    private suspend fun getSerialDocument(doc: Document): Document? {
        val imdbId = extractImdbId(doc) ?: return null
        return runCatching {
            service.getPage("https://devideosrc.co/serial/${imdbId.removePrefix("tt")}")
        }.getOrNull()
    }

    private fun extractDevideosrcToken(doc: Document): String? {
        // Movies: token in data-resolve-token on the <main> element
        doc.selectFirst("main[data-resolve-token]")
            ?.attr("data-resolve-token")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // TV shows: token in scripts
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        return Regex("""token:\s*"([^"]+)"""").find(scripts)?.groupValues?.get(1)
    }

    private suspend fun postDevideosrcApi(
        imdbId: String,
        type: String,
        token: String,
        season: String? = null,
        episode: String? = null,
        mode: String? = null
    ): JSONObject? {
        val endpoint: String
        val jsonBody: String
        if (season != null && episode != null && mode != null) {
            endpoint = "https://devideosrc.co/api/resolve"
            jsonBody = """{"type":"$type","id":"$imdbId","season":"$season","episode":"$episode","mode":"$mode","token":"$token"}"""
        } else {
            endpoint = "https://devideosrc.co/api/embed-links"
            jsonBody = """{"type":"$type","id":"$imdbId","token":"$token"}"""
        }
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        return runCatching {
            JSONObject(service.postJson(endpoint, body).string())
        }.getOrNull()
    }

    private suspend fun getApiValidEpisodes(doc: Document): Map<Int, Set<Int>>? {
        val imdbId = extractImdbId(doc) ?: return null
        val serialDoc = getSerialDocument(doc) ?: return null
        val token = extractDevideosrcToken(serialDoc) ?: return null
        val json = postDevideosrcApi(imdbId, "tv", token) ?: return null
        if (!json.optBoolean("ok")) return null
        val tvObj = json.optJSONObject("tv") ?: return null
        val seasonsArr = tvObj.optJSONArray("seasons") ?: return null

        val map = mutableMapOf<Int, Set<Int>>()
        for (i in 0 until seasonsArr.length()) {
            val seasonObj = seasonsArr.getJSONObject(i)
            val sNum = seasonObj.optInt("season_number")
            val episodesArr = seasonObj.optJSONArray("episodes") ?: continue
            val validEps = mutableSetOf<Int>()
            for (j in 0 until episodesArr.length()) {
                val epObj = episodesArr.getJSONObject(j)
                val epNum = epObj.optInt("episode_number")
                val sources = epObj.optJSONArray("sources")
                if (sources != null && sources.length() > 0) {
                    validEps.add(epNum)
                }
            }
            if (validEps.isNotEmpty()) {
                map[sNum] = validEps
            }
        }
        return map
    }

    private fun serialSeasonNumber(season: Element, serialDoc: Document): Int? {
        val seasonId = season.attr("data-season")
        val tabNumber = serialDoc.selectFirst("._stab[data-season='$seasonId']")?.text()
            ?.let { Regex("S(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return tabNumber ?: season.selectFirst("._ep[data-label]")?.attr("data-label")
            ?.let { Regex("S(\\d+)\\s*E\\d+", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun parseSerialEpisodes(
        showUrl: String,
        seasonNumber: Int,
        serialDoc: Document,
        tmdbEpisodes: List<Episode> = emptyList()
    ): List<Episode> {
        val season = serialDoc.select("._grp[data-season]").firstOrNull {
            serialSeasonNumber(it, serialDoc) == seasonNumber
        } ?: return emptyList()

        return season.select("._ep[data-episode]").mapNotNull { element ->
            val episodeNumber = element.attr("data-episode").toIntOrNull()
                ?: element.selectFirst("._epn")?.text()?.trim()?.removePrefix("E")?.toIntOrNull()
                ?: return@mapNotNull null
            val siteTitle = element.selectFirst("._ept")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val tmdbEpisode = tmdbEpisodes.find { it.number == episodeNumber }

            Episode(
                id = "$showUrl#s${seasonNumber}e$episodeNumber",
                number = episodeNumber,
                title = tmdbEpisode?.title ?: siteTitle ?: "Episode $episodeNumber",
                poster = tmdbEpisode?.poster,
                overview = tmdbEpisode?.overview
            )
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()

            val doc = service.getHome()

            val genreContainer = doc.selectFirst("div.dropdown-hover:has(span:containsOwn(Genre))")
            val genreLinks = genreContainer?.select("div.dropdown-content a[href]") ?: emptyList()

            return genreLinks.mapNotNull { a ->
                val href = a.attr("href").trim()
                val text = a.text().trim()
                if (href.isBlank() || text.isBlank()) return@mapNotNull null

                Genre(
                    id = href,
                    name = text
                )
            }
        }

        // The site's DLE search currently dies in sfilter/filter.php. Its
        // generated sitemap remains current and contains the complete index.
        return searchSitemap(query, page)
    }

    private suspend fun searchSitemap(query: String, page: Int): List<AppAdapter.Item> {
        return try {
            val entries = getSitemapEntries()

            val queryTokens = searchTokens(query)
            if (queryTokens.isEmpty()) return emptyList()
            val pageSize = 12
            val matchingUrls = entries.asSequence()
                .filter { entry ->
                    queryTokens.all { queryToken -> entry.searchableSlug.contains(queryToken) }
                }
                .drop((page - 1).coerceAtLeast(0) * pageSize)
                .take(pageSize)
                .map { it.url }
                .toList()

            val detailSemaphore = Semaphore(4)
            coroutineScope {
                matchingUrls.map { url ->
                    async {
                        detailSemaphore.withPermit {
                            runCatching { parseSearchResult(url, service.getPage(url)) }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            searchCataloguePages(query, page)
        }
    }

    private suspend fun getSitemapEntries(): List<SitemapEntry> {
        sitemapEntries?.let { return it }

        sitemapMutex.lock()
        return try {
            sitemapEntries ?: coroutineScope {
                listOf("news_pages.xml", "news_pages2.xml")
                    .map { sitemap -> async { parseSitemap(service.getRawPage("$baseUrl/$sitemap")) } }
                    .awaitAll()
                    .flatten()
                    .distinctBy { it.url }
                    .also { sitemapEntries = it }
            }
        } finally {
            sitemapMutex.unlock()
        }
    }

    private fun parseSitemap(body: ResponseBody): List<SitemapEntry> = body.use { responseBody ->
        val parser = android.util.Xml.newPullParser().apply {
            setInput(responseBody.charStream())
        }
        val entries = ArrayList<SitemapEntry>()
        var event = parser.eventType

        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "loc") {
                val url = parser.nextText().trim()
                if (url.isNotBlank()) {
                    val encodedSlug = url.substringAfterLast('/')
                    val slug = runCatching {
                        URLDecoder.decode(encodedSlug, StandardCharsets.UTF_8.name())
                    }.getOrDefault(encodedSlug)
                    entries.add(SitemapEntry(url = url, searchableSlug = slug.lowercase()))
                }
            }
            event = parser.next()
        }
        entries
    }

    private fun searchTokens(value: String): List<String> = value
        .lowercase()
        .replace(Regex("^\\d+-"), "")
        .replace(Regex("-stream(?:ing)?-stream\\.html$"), "")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }

    private fun parseSearchResult(url: String, doc: Document): AppAdapter.Item? {
        val title = doc.selectFirst("h1.font-bold")?.text()?.trim()
            ?.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" Stream")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = normalizeUrl(doc.selectFirst("figure.inline-block img")?.attr("data-src").orEmpty())
        return if (isTvShowDocument(doc)) {
            TvShow(id = url, title = title, poster = poster)
        } else {
            Movie(id = url, title = title, poster = poster)
        }
    }

    private fun isTvShowDocument(doc: Document): Boolean =
        doc.selectFirst("a[href*='themoviedb.org/tv/'], .info a[href$='/serien/'], #serial_iframe") != null

    private suspend fun searchCataloguePages(query: String, page: Int): List<AppAdapter.Item> =
        coroutineScope {
            val pages = (1..3).map { cataloguePage ->
                async {
                    listOf(
                        service.getMovies(cataloguePage) to false,
                        service.getTvShows(cataloguePage) to true
                    ).flatMap { (document, isTvShow) ->
                        document.select("div.listing.grid[id=dle-content] div.item.relative.mt-3")
                            .filter { element ->
                                element.selectFirst("h3.line-clamp-2")?.text()?.contains(query, ignoreCase = true) == true
                            }
                            .mapNotNull { element ->
                                if (isTvShow) {
                                    parseGridItemAsTvShow(element)
                                } else {
                                    parseGridItem(element)
                                }
                            }
                    }
                }
            }
            pages.awaitAll().flatten().distinctBy { item ->
                when (item) {
                    is Movie -> item.id
                    is TvShow -> item.id
                }
            }
        }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val doc = if (page > 1) service.getMovies(page) else service.getMovies()

            coroutineScope {
                val detailSemaphore = Semaphore(4)
                doc.select("div.listing.grid[id=dle-content] div.item.relative.mt-3").map { element ->
                    async {
                        detailSemaphore.withPermit {
                            val href = element.selectFirst("a.block.relative[href]")
                                ?.attr("href")?.trim()?.takeIf { it.isNotBlank() }
                                ?: return@withPermit null
                            val itemDoc = runCatching { service.getPage(href) }.getOrNull()
                                ?: return@withPermit null

                            if (isTvShowDocument(itemDoc)) null else parseGridItem(element)
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val doc = if (page > 1) service.getTvShows(page) else service.getTvShows()

            coroutineScope {
                val detailSemaphore = Semaphore(4)
                doc.select("div.listing.grid[id=dle-content] div.item.relative.mt-3").map { element ->
                    async {
                        detailSemaphore.withPermit {
                            val href = element.selectFirst("a.block.relative[href]")
                                ?.attr("href")?.trim()?.takeIf { it.isNotBlank() }
                                ?: return@withPermit null
                            val itemDoc = runCatching { service.getPage(href) }.getOrNull()
                                ?: return@withPermit null

                            if (isTvShowDocument(itemDoc)) parseGridItemAsTvShow(element) else null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val doc = service.getPage(id)

        val titleRaw = doc.selectFirst("h1.font-bold")?.text()?.trim() ?: ""
        val title = titleRaw.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "").trim()

        val tmdbMovie = TmdbUtils.getMovie(title, language = language)
        val poster = normalizeUrl(doc.selectFirst("figure.inline-block img")?.attr("data-src") ?: "")

        val overviewDiv = doc.selectFirst("div.font-extralight.prose.max-w-none")
        val overview = overviewDiv?.select("p")?.firstOrNull()?.let { p ->
            val fullText = p.text().trim()
            val refIndex = fullText.indexOf("Referenzen von")
            if (refIndex > 0) {
                fullText.substring(0, refIndex).trim()
            } else {
                fullText
            }
        }

        val trailer = doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")?.let { src ->
            src.replace("/embed/", "/watch?v=").replace("?autoplay=1", "")
        }

        val metadataDiv = doc.selectFirst("div.border-b.border-gray-700.font-extralight")

        val genres = metadataDiv?.let { div ->
            val firstSpan = div.selectFirst("span")
            firstSpan?.select("a")?.mapNotNull { a ->
                val genreName = a.text().trim()
                if (genreName.isNotBlank()) {
                    Genre(id = genreName, name = genreName)
                } else null
            }
        } ?: emptyList()

        val year = metadataDiv?.select("span")?.firstOrNull { span ->
            span.text().trim().matches(Regex("^\\d{4}$"))
        }?.text()?.trim()

        val duration = metadataDiv?.select("span")?.firstOrNull { span ->
            span.text().trim().contains("min")
        }?.text()?.trim()?.replace("min", "")?.trim()?.toIntOrNull()

        val quality = metadataDiv?.children()?.filter { it.tagName() == "span" && !it.hasClass("divider") }
            ?.lastOrNull()?.text()?.trim()?.let { text ->
                if (text.matches(Regex("^\\d{4}$")) || text.contains("min", ignoreCase = true)) null else text
            }


        val rating = metadataDiv?.selectFirst("p.imdb-badge span.imdb-rate")?.text()?.trim()?.toDoubleOrNull()

        val cast = doc.select("ul.space-y-1 li:has(span:containsOwn(Schauspieler:)) a[href*='/xfsearch/actors/']")
            .mapNotNull { a ->
                val actorName = a.text().trim()
                val actorUrl = a.attr("href").trim()
                if (actorName.isNotBlank() && actorName != "N/A") {
                    val tmdbPerson = tmdbMovie?.cast?.find { it.name.equals(actorName, ignoreCase = true) }
                    People(id = actorUrl, name = actorName, image = tmdbPerson?.image)
                } else null
            }

        return Movie(
            id = id,
            title = title,
            poster = poster,
            banner = tmdbMovie?.banner,
            trailer = trailer,
            rating = tmdbMovie?.rating ?: rating,
            overview = tmdbMovie?.overview ?: overview,
            released = tmdbMovie?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: year,
            runtime = tmdbMovie?.runtime ?: duration,
            quality = quality,
            genres = tmdbMovie?.genres ?: genres,
            cast = cast,
            imdbId = tmdbMovie?.imdbId
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = service.getPage(id)

        val titleRaw = doc.selectFirst("h1.font-bold")?.text()?.trim() ?: ""
        val title = titleRaw.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "").trim()

        val tmdbTitle = title.replace(Regex("(?i)\\s*\\((Season|Staffel)s?\\s*\\d+-\\d+\\)"), "").trim()
        val tmdbTvShow = TmdbUtils.getTvShow(tmdbTitle, language = language)
        val poster = normalizeUrl(doc.selectFirst("figure.inline-block img")?.attr("data-src") ?: "")

        val overviewDiv = doc.selectFirst("div.font-extralight.prose.max-w-none")
        val overview = overviewDiv?.select("p")?.firstOrNull()?.let { p ->
            val fullText = p.text().trim()
            val refIndex = fullText.indexOf("Referenzen von")
            if (refIndex > 0) {
                fullText.substring(0, refIndex).trim()
            } else {
                fullText
            }
        }

        val trailer = doc.selectFirst("iframe[src*='youtube.com/embed']")?.attr("src")?.let { src ->
            src.replace("/embed/", "/watch?v=").replace("?autoplay=1", "")
        }

        val metadataDiv = doc.selectFirst("div.border-b.border-gray-700.font-extralight")

        val genres = metadataDiv?.let { div ->
            val firstSpan = div.selectFirst("span")
            firstSpan?.select("a")?.mapNotNull { a ->
                val genreName = a.text().trim()
                if (genreName.isNotBlank()) {
                    Genre(id = genreName, name = genreName)
                } else null
            }
        } ?: emptyList()

        val year = metadataDiv?.select("span")?.firstOrNull { span ->
            span.text().trim().matches(Regex("^\\d{4}$"))
        }?.text()?.trim()

        val duration = metadataDiv?.select("span")?.firstOrNull { span ->
            span.text().trim().contains("min")
        }?.text()?.trim()?.replace("min", "")?.trim()?.toIntOrNull()

        val quality = metadataDiv?.children()?.filter { it.tagName() == "span" && !it.hasClass("divider") }
            ?.lastOrNull()?.text()?.trim()?.let { text ->
                if (text.matches(Regex("^\\d{4}$")) || text.contains("min", ignoreCase = true)) null else text
            }

        val rating = metadataDiv?.selectFirst("p.imdb-badge span.imdb-rate")?.text()?.trim()?.toDoubleOrNull()

        val apiValidEpisodesMap = getApiValidEpisodes(doc)

        val seasons = mutableListOf<Season>()
        val serialDoc = getSerialDocument(doc)
        serialDoc?.select("._grp[data-season]")?.forEach { serialSeason ->
            val seasonNumber = serialSeasonNumber(serialSeason, serialDoc) ?: return@forEach
            if (apiValidEpisodesMap != null && !apiValidEpisodesMap.containsKey(seasonNumber)) {
                return@forEach
            }
            var episodes = parseSerialEpisodes(id, seasonNumber, serialDoc)
            val validEpsForSeason = apiValidEpisodesMap?.get(seasonNumber)
            if (validEpsForSeason != null) {
                episodes = episodes.filter { it.number in validEpsForSeason }
            }

            if (episodes.isNotEmpty()) {
                seasons.add(
                    Season(
                        id = "$id#season-$seasonNumber",
                        number = seasonNumber,
                        poster = tmdbTvShow?.seasons?.find { it.number == seasonNumber }?.poster,
                        episodes = episodes
                    )
                )
            }
        }

        if (seasons.isEmpty()) doc.select("div#se-accordion div.su-spoiler").forEach { spoiler ->
            val seasonTitle = spoiler.selectFirst("div.su-spoiler-title")?.text()?.trim() ?: return@forEach
            val seasonNumberMatch = Regex("Staffel\\s+(\\d+)").find(seasonTitle)
            val seasonNumber = seasonNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach

            val episodes = mutableListOf<Episode>()
            val content = spoiler.selectFirst("div.su-spoiler-content")?.html() ?: ""

            if (content.isNotBlank()) {
                val episodeRegex = Regex("""(\d+)x(\d+)\s+Episode\s+\d+""")
                val lines = content.split("<br>")

                lines.forEach { line ->
                    val match = episodeRegex.find(line) ?: return@forEach
                    val epNumber = match.groupValues[2].toIntOrNull() ?: return@forEach

                    episodes.add(
                        Episode(
                            id = "$id#s${seasonNumber}e$epNumber",
                            number = epNumber,
                            title = "Episode $epNumber",
                            poster = null
                        )
                    )
                }
            }

            if (episodes.isNotEmpty()) {
                seasons.add(
                    Season(
                        id = "$id#season-$seasonNumber",
                        number = seasonNumber,
                        poster = tmdbTvShow?.seasons?.find { it.number == seasonNumber }?.poster,
                        episodes = episodes.distinctBy { it.number }.sortedBy { it.number }
                    )
                )
            }
        }

        val cast = doc.select("ul.space-y-1 li:has(span:containsOwn(Schauspieler:)) a[href*='/xfsearch/actors/']")
            .mapNotNull { a ->
                val actorName = a.text().trim()
                val actorUrl = a.attr("href").trim()
                if (actorName.isNotBlank() && actorName != "N/A") {
                    val tmdbPerson = tmdbTvShow?.cast?.find { it.name.equals(actorName, ignoreCase = true) }
                    People(id = actorUrl, name = actorName, image = tmdbPerson?.image)
                } else null
            }

        return TvShow(
            id = id,
            title = title,
            poster = poster,
            banner = tmdbTvShow?.banner,
            trailer = tmdbTvShow?.trailer ?: trailer,
            rating = tmdbTvShow?.rating ?: rating,
            overview = tmdbTvShow?.overview ?: overview,
            released = tmdbTvShow?.released?.let { "${it.get(java.util.Calendar.YEAR)}" } ?: year,
            runtime = tmdbTvShow?.runtime ?: duration,
            quality = quality,
            genres = tmdbTvShow?.genres ?: genres,
            cast = cast,
            seasons = seasons,
            imdbId = tmdbTvShow?.imdbId
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showUrl = seasonId.substringBefore("#")
        val seasonNumber = seasonId.substringAfter("#season-").toIntOrNull() ?: return emptyValue()

        val doc = service.getPage(showUrl)
        val titleRaw = doc.selectFirst("h1.font-bold")?.text()?.trim() ?: ""
        val title = titleRaw.replace(Regex("\\s*hdfilme\\s*$", RegexOption.IGNORE_CASE), "").trim()

        val tmdbTitle = title.replace(Regex("(?i)\\s*\\((Season|Staffel)s?\\s*\\d+-\\d+\\)"), "").trim()
        val tmdbTvShow = TmdbUtils.getTvShow(tmdbTitle, language = language)
        val tmdbEpisodes = tmdbTvShow?.let { TmdbUtils.getEpisodesBySeason(it.id, seasonNumber, language = language) } ?: emptyList()

        val apiValidEpisodesMap = getApiValidEpisodes(doc)

        getSerialDocument(doc)?.let { serialDoc ->
            var serialEpisodes = parseSerialEpisodes(showUrl, seasonNumber, serialDoc, tmdbEpisodes)
            val validEpsForSeason = apiValidEpisodesMap?.get(seasonNumber)
            if (validEpsForSeason != null) {
                serialEpisodes = serialEpisodes.filter { it.number in validEpsForSeason }
            }
            if (serialEpisodes.isNotEmpty()) return serialEpisodes
        }

        val episodes = mutableListOf<Episode>()

        doc.select("div#se-accordion div.su-spoiler").forEach { spoiler ->
            val seasonTitle = spoiler.selectFirst("div.su-spoiler-title")?.text()?.trim() ?: return@forEach
            val seasonNumberMatch = Regex("Staffel\\s+(\\d+)").find(seasonTitle)
            val currentSeasonNum = seasonNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach

            if (currentSeasonNum == seasonNumber) {
                val content = spoiler.selectFirst("div.su-spoiler-content")?.html() ?: return@forEach

                val episodeRegex = Regex("""(\d+)x(\d+)\s+Episode\s+\d+""")
                val lines = content.split("<br>")

                lines.forEach { line ->
                    val match = episodeRegex.find(line) ?: return@forEach
                    val epNumber = match.groupValues[2].toIntOrNull() ?: return@forEach

                    val tmdbEp = tmdbEpisodes.find { it.number == epNumber }

                    episodes.add(
                        Episode(
                            id = "$showUrl#s${seasonNumber}e$epNumber",
                            number = epNumber,
                            title = tmdbEp?.title ?: "Episode $epNumber",
                            poster = tmdbEp?.poster,
                            overview = tmdbEp?.overview
                        )
                    )
                }
            }
        }

        return episodes.distinctBy { it.number }.sortedBy { it.number }
    }

    private fun <T> emptyValue(): List<T> = emptyList()

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val doc = if (page <= 1) {
                service.getPage(id)
            } else {
                val path = id.substringAfter(baseUrl).trimStart('/')
                service.getPaged(path, page)
            }
            val title = doc.selectFirst("h1")?.text()?.trim() ?: ""

            val elements = doc.select("div.listing.grid[id=dle-content] div.item.relative.mt-3")

            val shows = when {
                id.contains("/serien/", ignoreCase = true) -> {
                    elements.mapNotNull { el -> parseGridItemAsTvShow(el) }
                }
                else -> {
                    coroutineScope {
                        elements.map { el ->
                            async {
                                val href = el.selectFirst("a.block.relative[href]")?.attr("href")?.trim()
                                    ?: return@async null

                                try {
                                    val itemDoc = service.getPage(href)
                                    val hasSeasons = itemDoc.select("div#se-accordion").isNotEmpty()

                                    if (hasSeasons) {
                                        parseGridItemAsTvShow(el)
                                    } else {
                                        parseGridItem(el)
                                    }
                                } catch (e: Exception) {
                                    parseGridItem(el)
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
            }

            Genre(
                id = id,
                name = title,
                shows = shows
            )
        } catch (e: Exception) {
            Genre(id = id, name = "")
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val doc = service.getPage(id)

        val name = doc.selectFirst("h1")?.text()?.trim() ?: ""

        if (page > 1) {
            return People(
                id = id,
                name = name,
                filmography = emptyList()
            )
        }

        val elements = doc.select("div.listing.grid[id=dle-content] div.item.relative.mt-3")

        val filmography = coroutineScope {
            elements.map { el ->
                async {
                    val href = el.selectFirst("a.block.relative[href]")?.attr("href")?.trim()
                        ?: return@async null

                    try {
                        val itemDoc = service.getPage(href)
                        val hasSeasons = itemDoc.select("div#se-accordion").isNotEmpty()

                        if (hasSeasons) {
                            parseGridItemAsTvShow(el)
                        } else {
                            parseGridItem(el)
                        }
                    } catch (e: Exception) {
                        parseGridItem(el)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        return People(
            id = id,
            name = name,
            filmography = filmography
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val doc = service.getPage(id)

        if (videoType is Video.Type.Episode) {
            val showUrl = id.substringBefore('#')
            val episodePart = id.substringAfter('#', "")
            val seasonNum = episodePart.substringAfter('s').substringBefore('e').toIntOrNull() ?: return emptyList()
            val epNum = episodePart.substringAfter('e').toIntOrNull() ?: return emptyList()

            val imdbId = extractImdbId(doc) ?: return emptyList()
            val serialDoc = getSerialDocument(doc) ?: return emptyList()
            val token = extractDevideosrcToken(serialDoc) ?: return emptyList()
            val json = postDevideosrcApi(imdbId, "tv", token) ?: return emptyList()
            if (!json.optBoolean("ok")) return emptyList()
            val tvObj = json.optJSONObject("tv") ?: return emptyList()
            val seasonsArr = tvObj.optJSONArray("seasons") ?: return emptyList()

            for (si in 0 until seasonsArr.length()) {
                val season = seasonsArr.getJSONObject(si)
                if (season.optInt("season_number") != seasonNum) continue
                val episodes = season.optJSONArray("episodes") ?: continue
                for (ei in 0 until episodes.length()) {
                    val episode = episodes.getJSONObject(ei)
                    if (episode.optInt("episode_number") != epNum) continue
                    val sources = episode.optJSONArray("sources") ?: return emptyList()
                    val validSources = (0 until sources.length()).mapNotNull { i ->
                        val src = sources.getJSONObject(i)
                        val url = src.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val rawName = src.optString("name")
                        Pair(url, rawName)
                    }
                    return validSources.mapIndexed { index, (url, rawName) ->
                        val cleanName = rawName.substringBefore(".").trim().replaceFirstChar { it.uppercase() }
                        val serverName = if (cleanName.isNotBlank() && !cleanName.equals("Server", ignoreCase = true)) {
                            "Server ${index + 1} ($cleanName)"
                        } else {
                            "Server ${index + 1}"
                        }
                        Video.Server(id = url, name = serverName, src = url)
                    }
                }
            }
            return emptyList()
        }

        val iframe = doc.selectFirst("iframe[src*='devideosrc.co'], iframe[data-src*='devideosrc.co']")
            ?: throw Exception("Embed iframe not found")
        val iframeSrc = iframe.attr("data-src").takeIf { it.isNotBlank() } ?: iframe.attr("src")
        val embedDoc = service.getPage(iframeSrc)

        val playerMain = embedDoc.selectFirst("main[data-resolve-token][data-id]")
            ?: throw Exception("Player main element not found")
        val imdbId = playerMain.attr("data-id").takeIf { it.isNotBlank() }
            ?: throw Exception("IMDB id not found in player element")
        val token = extractDevideosrcToken(embedDoc)
            ?: throw Exception("Resolve token not found")
        val season = playerMain.attr("data-season").takeIf { it.isNotBlank() }
            ?: throw Exception("Season not found in player element")
        val episode = playerMain.attr("data-episode").takeIf { it.isNotBlank() }
            ?: throw Exception("Episode not found in player element")
        val mode = playerMain.attr("data-mode").takeIf { it.isNotBlank() }
            ?: throw Exception("Mode not found in player element")

        val json = postDevideosrcApi(imdbId, "movie", token, season, episode, mode)
            ?: throw Exception("API resolve call failed")

        if (!json.optBoolean("ok")) return emptyList()
        val sourcesArr = json.optJSONArray("sources") ?: return emptyList()

        val validSources = (0 until sourcesArr.length()).mapNotNull { i ->
            val src = sourcesArr.getJSONObject(i)
            val url = src.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawName = src.optString("name")
            Pair(url, rawName)
        }
        return validSources.mapIndexed { index, (url, rawName) ->
            val cleanName = rawName.substringBefore(".").trim().replaceFirstChar { it.uppercase() }
            val serverName = if (cleanName.isNotBlank() && !cleanName.equals("Server", ignoreCase = true)) {
                "Server ${index + 1} ($cleanName)"
            } else {
                "Server ${index + 1}"
            }
            Video.Server(id = url, name = serverName, src = url)
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src)
    }
}
