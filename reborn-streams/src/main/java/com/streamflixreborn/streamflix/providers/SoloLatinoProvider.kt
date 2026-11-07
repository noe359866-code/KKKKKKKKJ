package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.models.sololatino.Item
import com.streamflixreborn.streamflix.utils.ArtworkRequestHeaders
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SoloLatinoProvider : Provider {

    override val name = "SoloLatino"
    override val baseUrl = "https://sololatino.net"
    override val logo: String
        get() = artworkUrl("$baseUrl/images/logo.png")
            ?: "$baseUrl/images/logo.png"
    override val language = "es"

    private const val TAG = "SoloLatinoProvider"
    private val USER_AGENT: String get() = NetworkClient.USER_AGENT

    private val providerMutex = Mutex()
    private var webViewResolver: WebViewResolver? = null
    @Volatile
    private var clearanceCookieHeader: String? = null

    fun init(context: Context) {
        webViewResolver = WebViewResolver(context)
        syncClearanceCookieState()
    }

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    private class ChallengeRequiredException(
        message: String,
        val rejectedClearance: String?,
    ) : IllegalStateException(message)

    private fun clearanceToken(cookieHeader: String?): String? {
        return cookieHeader
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("cf_clearance=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf(String::isNotBlank)
    }

    private fun clearStaleClearanceCookie() {
        val cookieManager = CookieManager.getInstance()
        val host = "sololatino.net"
        cookieManager.setCookie("https://$host/", "cf_clearance=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=.$host; Max-Age=0")
        cookieManager.setCookie("https://$host/", "cf_clearance=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Max-Age=0")
        cookieManager.flush()
        clearanceCookieHeader = null
    }

    private fun syncClearanceCookieState(): String? {
        val cookieManager = CookieManager.getInstance()
        val host = "sololatino.net"
        val candidates = listOf(
            "https://$host",
            "https://$host/",
            "https://.$host",
            "https://.$host/"
        )

        val liveCookieHeaders = candidates.mapNotNull { candidate ->
            cookieManager.getCookie(candidate)?.trim()?.takeIf(String::isNotBlank)
        }.distinct()

        val liveWithClearance = liveCookieHeaders.firstOrNull { header ->
            !clearanceToken(header).isNullOrBlank()
        }

        if (liveWithClearance != null) {
            clearanceCookieHeader = liveWithClearance
        }

        val token = clearanceToken(clearanceCookieHeader)
        if (!token.isNullOrBlank()) {
            cookieManager.setCookie("https://$host/", "cf_clearance=$token; Path=/; Domain=.$host")
            cookieManager.setCookie("https://$host/", "cf_clearance=$token; Path=/")
            cookieManager.flush()
        }

        val currentLive = cookieManager.getCookie("https://$host/")?.trim()
        val resultCookie = when {
            !clearanceToken(currentLive).isNullOrBlank() -> currentLive
            !token.isNullOrBlank() && !currentLive.isNullOrBlank() -> "$currentLive; cf_clearance=$token"
            !token.isNullOrBlank() -> "cf_clearance=$token"
            else -> currentLive ?: clearanceCookieHeader
        }
        return resultCookie
    }

    private fun currentClearanceCookie(): String? = syncClearanceCookieState()

    fun hasCurrentClearanceCookie(): Boolean {
        return !clearanceToken(syncClearanceCookieState()).isNullOrBlank()
    }

    private fun promoteClearanceCookieHeader(cookieHeader: String?) {
        val normalized = cookieHeader?.trim()?.takeIf(String::isNotBlank) ?: return
        if (normalized == clearanceCookieHeader) return
        clearanceCookieHeader = normalized
    }

    private fun hasUsableSiteContent(html: String): Boolean {
        if (html.length < 500) return false
        return html.contains("main-header", ignoreCase = true) ||
                html.contains("movies-grid", ignoreCase = true) ||
                html.contains("class=\"card\"", ignoreCase = true) ||
                html.contains("class='card'", ignoreCase = true) ||
                html.contains("card__poster", ignoreCase = true) ||
                html.contains("ep-card", ignoreCase = true) ||
                html.contains("hero", ignoreCase = true) ||
                html.contains("server-btn", ignoreCase = true) ||
                html.contains("fbar-form", ignoreCase = true)
    }

    private fun isChallengePage(html: String, currentUrl: String): Boolean {
        if (currentUrl.contains("/cdn-cgi/challenge-platform", ignoreCase = true)) return true
        val titleIsChallenge = html.contains("<title>Just a moment...</title>", ignoreCase = true) ||
                (html.contains("Just a moment...", ignoreCase = true) && !hasUsableSiteContent(html))
        val bodyIsChallenge = html.contains("cf-browser-verification", ignoreCase = true) ||
                html.contains("challenge-running", ignoreCase = true) ||
                (html.contains("window._cf_chl_opt", ignoreCase = true) && !hasUsableSiteContent(html))
        return titleIsChallenge || bodyIsChallenge
    }

    private fun requiresClearance(html: String, code: Int = 200, finalUrl: String = ""): Boolean {
        if (finalUrl.contains("/cdn-cgi/challenge-platform", ignoreCase = true)) return true
        if (code in listOf(403, 503)) {
            return !hasUsableSiteContent(html)
        }
        return isChallengePage(html, finalUrl)
    }

    private fun pageHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        "Referer" to referer
    )

    private fun artworkUrl(url: String?): String? {
        val target = url?.takeIf { it.isNotBlank() } ?: return null
        return ArtworkRequestHeaders.withHeaders(
            url = target,
            referer = "$baseUrl/",
            origin = baseUrl,
            userAgent = NetworkClient.USER_AGENT,
            accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            cookie = currentClearanceCookie()
        )
    }

    private suspend fun solveCloudflareChallenge(
        url: String,
        referer: String,
        rejectedClearance: String?,
    ): String? {
        val result = providerMutex.withLock {
            val currentClearance = clearanceToken(currentClearanceCookie())
            if (!currentClearance.isNullOrBlank() && currentClearance != rejectedClearance) {
                Log.d(TAG, "Another request already renewed Cloudflare clearance; skipping WebView")
                return@withLock null
            }

            clearStaleClearanceCookie()
            val challengeUrl = url
            Log.d(TAG, "Solving confirmed Cloudflare challenge in WebView -> url=$challengeUrl")

            getResolver().getResult(
                url = challengeUrl,
                headers = pageHeaders(referer),
                completion = { currentUrl, html, cookies ->
                    val cookieManager = CookieManager.getInstance()
                    val liveCookies = listOf(
                        cookies,
                        cookieManager.getCookie(currentUrl).orEmpty(),
                        cookieManager.getCookie("https://sololatino.net/").orEmpty(),
                        cookieManager.getCookie("https://sololatino.net").orEmpty()
                    ).filter { it.isNotBlank() }.joinToString("; ")

                    val newClearance = clearanceToken(liveCookies)
                    val isNewClearance = !newClearance.isNullOrBlank() && newClearance != rejectedClearance
                    val hasContent = hasUsableSiteContent(html)
                    val isChallenge = isChallengePage(html, currentUrl)

                    if (isNewClearance) {
                        promoteClearanceCookieHeader(liveCookies)
                    }
                    Log.d(
                        TAG,
                        "WebView challenge poll -> url=$currentUrl isNewClearance=$isNewClearance " +
                                "content=$hasContent challenge=$isChallenge clearance=$newClearance"
                    )
                    !isChallenge && (hasContent || isNewClearance)
                },
                shouldAllowNavigation = { targetUrl, isMainFrame ->
                    runCatching {
                        val target = URL(targetUrl)
                        val targetHost = target.host.lowercase()
                        val isAllowed = targetHost.contains("sololatino.net") ||
                                target.path.contains("/cdn-cgi/", ignoreCase = true) ||
                                targetHost.contains("cloudflare") ||
                                (isMainFrame && !targetHost.contains("google") && !targetHost.contains("doubleclick"))
                        isAllowed
                    }.getOrDefault(true)
                },
            )
        }

        if (result != null) {
            val tokenFromManager = clearanceToken(CookieManager.getInstance().getCookie("https://sololatino.net/"))
            val tokenFromResult = clearanceToken(result.cookies)
            val token = tokenFromResult ?: tokenFromManager
            if (!token.isNullOrBlank()) {
                promoteClearanceCookieHeader("cf_clearance=$token")
            }
            CookieManager.getInstance().flush()
            syncClearanceCookieState()
            if (result.html.isNotBlank() && hasUsableSiteContent(result.html)) {
                return result.html
            }
        }

        val resolvedClearance = clearanceToken(currentClearanceCookie())
        if (resolvedClearance.isNullOrBlank() || resolvedClearance == rejectedClearance) {
            throw ChallengeRequiredException(
                message = "Cloudflare clearance was not obtained for $url",
                rejectedClearance = rejectedClearance,
            )
        }
        return null
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .cookieJar(NetworkClient.cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val client get() = httpClient

    private fun fetchDirect(rawUrl: String, referer: String? = null): String {
        val url = rawUrl
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")

        val effectiveReferer = referer ?: "$baseUrl/"
        reqBuilder.header("Referer", effectiveReferer)
        syncClearanceCookieState()?.takeIf { it.isNotBlank() }?.let { cookie ->
            reqBuilder.header("Cookie", cookie)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val finalUrl = resp.request.url.toString()

            if (resp.isSuccessful && !requiresClearance(body, resp.code, finalUrl)) {
                return body
            }

            if (requiresClearance(body, resp.code, finalUrl) || resp.code in listOf(403, 503)) {
                Log.w(TAG, "Cloudflare challenge detected -> code=${resp.code} url=$url finalUrl=$finalUrl")
                throw ChallengeRequiredException(
                    message = "SoloLatino Cloudflare challenge detected for $url",
                    rejectedClearance = clearanceToken(currentClearanceCookie()),
                )
            }

            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code} for $url")
            }

            return body
        }
    }

    private suspend fun httpGet(url: String, referer: String? = null): String {
        syncClearanceCookieState()
        val target = if (url.startsWith("http")) url else "$baseUrl$url"
        return try {
            fetchDirect(target, referer)
        } catch (challenge: ChallengeRequiredException) {
            val resolvedHtml = solveCloudflareChallenge(target, referer ?: "$baseUrl/", challenge.rejectedClearance)
            resolvedHtml ?: fetchDirect(target, referer)
        }
    }

    private suspend fun getPage(url: String): Document {
        return Jsoup.parse(httpGet(url), baseUrl)
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()

        try {
            val mainDoc = getPage(baseUrl)
            
            // 1. Featured
            val bannerShows = parseBannerShows(mainDoc).take(12)
            if (bannerShows.isNotEmpty()) {
                categories.add(Category(Category.FEATURED, bannerShows))
            }
            
            // 2. Sections from the home page
            val sections = mainDoc.select("section")
            for (section in sections) {
                val title = section.selectFirst(".section-title")?.text() ?: continue
                val shows = parseMixed(section)
                if (shows.isNotEmpty()) {
                    categories.add(Category(title, shows.take(12)))
                }
            }
        } catch (e: Exception) { /* Ignore */ }

        categories
    }

    private fun parseMixed(element: Element): List<Show> {
        val cards = if (element is Document) element.select("div.card") else element.select("div.card")
        return cards.mapNotNull { card ->
            val linkElement = card.selectFirst("a") ?: card.parents().firstOrNull { it.tagName() == "a" }
            val href = linkElement?.attr("href") ?: return@mapNotNull null
            val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"
            
            val imgElement = card.selectFirst("img.card__poster")
            val poster = imgElement?.attr("src") ?: ""
            
            val titleElement = card.selectFirst(".card__title")
            val title = titleElement?.text() ?: ""
            
            val year = card.selectFirst(".card__year")?.text()
            val isMovie = card.selectFirst(".badge-movie") != null || absoluteUrl.contains("/pelicula/")

            if (isMovie) {
                Movie(
                    id = absoluteUrl,
                    title = title,
                    released = year,
                    poster = poster
                )
            } else {
                TvShow(
                    id = absoluteUrl,
                    title = title,
                    released = year,
                    poster = poster
                )
            }
        }
    }

    private fun parseBannerShows(document: Document): List<Show> {
        return document.select(".hero__slide").mapNotNull { slide ->
            val linkElement = slide.selectFirst("a.btn-accent") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"
            
            val posterElement = slide.selectFirst(".hero__bg")
            val style = posterElement?.attr("style") ?: ""
            val bannerUrl = if (style.contains("url('")) {
                style.substringAfter("url('").substringBefore("')")
            } else ""
            
            val title = slide.selectFirst(".hero__logo-img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: slide.selectFirst(".hero__content p.font-display")?.text()?.trim() 
                ?: ""
            
            val year = slide.selectFirst("div.flex.items-center span")?.text()?.takeIf { it.matches(Regex("""\d{4}""")) }
            
            val overview = slide.selectFirst(".text-sm.leading-relaxed.line-clamp-4")?.text()?.trim()

            if (absoluteUrl.contains("/pelicula/")) {
                Movie(
                    id = absoluteUrl,
                    title = title,
                    banner = bannerUrl,
                    overview = overview,
                    released = year
                )
            } else {
                TvShow(
                    id = absoluteUrl,
                    title = title,
                    banner = bannerUrl,
                    overview = overview,
                    released = year
                )
            }
        }
    }

    private fun parseMovies(document: Document): List<Movie> {
        return parseMixed(document).filterIsInstance<Movie>()
    }

    private fun parseTvShows(document: Document): List<TvShow> {
        return parseMixed(document).filterIsInstance<TvShow>()
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre("accion", "Acción"),
                Genre("action-adventure", "Action & Adventure"),
                Genre("animacion", "Animación"),
                Genre("aventura", "Aventura"),
                Genre("belica", "Bélica"),
                Genre("ciencia-ficcion", "Ciencia Ficción"),
                Genre("comedia", "Comedia"),
                Genre("crimen", "Crimen"),
                Genre("disney", "Disney"),
                Genre("documental", "Documental"),
                Genre("drama", "Drama"),
                Genre("familia", "Familia"),
                Genre("fantasia", "Fantasía"),
                Genre("hbo", "HBO"),
                Genre("historia", "Historia"),
                Genre("kids", "Kids"),
                Genre("misterio", "Misterio"),
                Genre("musica", "Música"),
                Genre("romance", "Romance"),
                Genre("sci-fi-fantasy", "Sci-Fi & Fantasy"),
                Genre("soap", "Soap"),
                Genre("suspense", "Suspense"),
                Genre("talk", "Talk"),
                Genre("terror", "Terror"),
                Genre("war-politics", "War & Politics"),
                Genre("western", "Western"),
            )
        }

        return try {
            val url = if (page > 1) "$baseUrl/buscar?q=$query&page=$page" else "$baseUrl/buscar?q=$query"
            val document = getPage(url)
            parseMixed(document)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val url = if (page > 1) "$baseUrl/peliculas/page/$page" else "$baseUrl/peliculas"
            val document = getPage(url)
            parseMovies(document)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val url = if (page > 1) "$baseUrl/series/page/$page" else "$baseUrl/series"
            val document = getPage(url)
            parseTvShows(document)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val url = if (page > 1) "$baseUrl/genero/$id/page/$page" else "$baseUrl/genero/$id"
            val document = getPage(url)
            val shows = parseMixed(document)
            Genre(
                id = id,
                name = id.replace("-", " ").replaceFirstChar { it.uppercase() },
                shows = shows
            )
        } catch (e: Exception) {
            Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = emptyList())
        }
    }

    override suspend fun getMovie(id: String): Movie {
        return try {
            val document = getPage(id)
            val title = document.selectFirst("h1")?.text() 
                ?: document.select("nav[aria-label=breadcrumb] span:last-child").text()
            
            val posterElement = document.selectFirst("div.-mt-28 img, div.flex-shrink-0.mx-auto img, img.card__poster")
            val posterUrl = posterElement?.attr("src") ?: ""
            
            val overview = document.selectFirst(".description")?.text() 
                ?: document.selectFirst(".storyline")?.text()
                ?: document.selectFirst("p.leading-relaxed")?.text()
                ?: document.selectFirst("p.text-sm")?.text() ?: ""

            val banner = document.selectFirst(".hero__bg, .detail-hero__bg")?.attr("style")
                ?.substringAfter("url('")?.substringBefore("')")
            val rating = document.selectFirst(".rating-badge--tmdb")?.text()?.let { 
                Regex("""(\d+\.?\d*)""").find(it)?.value?.toDoubleOrNull()
            }

            val runtimeStr = document.select("div.flex.flex-wrap.items-center.gap-4.text-sm.mb-5 span")
                .map { it.text() }
                .firstOrNull { it.contains("h") || (it.contains("m") && it.any { c -> c.isDigit() }) }
            
            val runtime = runtimeStr?.let { str ->
                val hours = Regex("""(\d+)h""").find(str)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val minutes = Regex("""(\d+)m""").find(str)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (hours == 0 && minutes == 0) null else (hours * 60) + minutes
            }
            val released = document.selectFirst("span.date")?.text() ?: document.selectFirst(".card__year")?.text()
            val trailer = document.selectFirst("button[data-trailer]")?.attr("data-trailer")?.let { 
                "https://www.youtube.com/watch?v=$it" 
            }

            val genres = document.select("div.flex-1.min-w-0 a[href*=/genero/]").map {
                Genre(id = "", name = it.text())
            }

            val cast = document.select("div.cast-card").map {
                People(
                    id = it.selectFirst("a")?.attr("href") ?: "",
                    name = it.selectFirst("p.font-semibold")?.text() ?: "",
                    image = it.selectFirst("img")?.attr("src")
                )
            }

            val recommendations = document.select("div.card, article.card").mapNotNull { article ->
                val linkElement = article.selectFirst("a") ?: return@mapNotNull null
                val imgElement = article.selectFirst("img") ?: return@mapNotNull null
                val href = linkElement.attr("href")
                val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val recPoster = imgElement.attr("src") ?: imgElement.attr("data-srcset")

                Movie(
                    id = absoluteUrl,
                    title = imgElement.attr("alt"),
                    poster = if (recPoster.startsWith("http")) recPoster else "$baseUrl$recPoster"
                )
            }

            Movie(
                id = id,
                title = title,
                poster = if (posterUrl.startsWith("http")) posterUrl else "$baseUrl$posterUrl",
                banner = banner,
                genres = genres,
                overview = overview,
                rating = rating,
                runtime = runtime,
                released = released,
                trailer = trailer,
                cast = cast,
                recommendations = recommendations,
            )
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        return try {
            val document = getPage(id)
            val title = document.selectFirst("h1")?.text()
                ?: document.select("nav[aria-label=breadcrumb] span:last-child").text()
            
            val posterElement = document.selectFirst("div.-mt-28 img, div.flex-shrink-0.mx-auto img, img.card__poster")
            val posterUrl = posterElement?.attr("src") ?: ""

            val overview = document.selectFirst(".description")?.text() 
                ?: document.selectFirst(".storyline")?.text()
                ?: document.selectFirst("p.leading-relaxed")?.text()
                ?: document.selectFirst("p.text-sm")?.text() ?: ""

            val seasons = document.select("div[data-season-panel]").map { seasonElement ->
                val seasonNumber = seasonElement.attr("data-season-panel").toIntOrNull() ?: 0
                Season(id = "$id@$seasonNumber", number = seasonNumber, title = "Temporada $seasonNumber")
            }.filter { it.number != 0 }

            val banner = document.selectFirst(".hero__bg, .detail-hero__bg")?.attr("style")
                ?.substringAfter("url('")?.substringBefore("')")
                
            val rating = document.selectFirst(".rating-badge--tmdb")?.text()?.let { 
                Regex("""(\d+\.?\d*)""").find(it)?.value?.toDoubleOrNull()
            }
            val runtime: Int? = null
            
            val yearMatch = Regex("""\d{4}""").find(document.text())
            val released = yearMatch?.value
            
            val trailer = document.selectFirst("button[data-trailer]")?.attr("data-trailer")?.let { 
                "https://www.youtube.com/watch?v=$it" 
            }

            val genres = document.select("div.flex-1.min-w-0 a[href*=/genero/]").map {
                Genre(id = "", name = it.text())
            }

            val cast = document.select("div.cast-card, div.sbox.srepart div.person").map {
                People(
                    id = it.selectFirst("a")?.attr("href") ?: "",
                    name = it.selectFirst("p.font-semibold, .name a")?.text() ?: "",
                    image = it.selectFirst("img")?.attr("src")
                )
            }

            val recommendations = document.select("div.card, article.card").mapNotNull { article ->
                val linkElement = article.selectFirst("a") ?: return@mapNotNull null
                val imgElement = article.selectFirst("img") ?: return@mapNotNull null
                val href = linkElement.attr("href")
                val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val recPoster = imgElement.attr("src") ?: imgElement.attr("data-srcset")

                TvShow(
                    id = absoluteUrl,
                    title = imgElement.attr("alt"),
                    poster = if (recPoster.startsWith("http")) recPoster else "$baseUrl$recPoster"
                )
            }

            TvShow(
                id = id,
                title = title,
                poster = if (posterUrl.startsWith("http")) posterUrl else "$baseUrl$posterUrl",
                banner = banner,
                genres = genres,
                overview = overview,
                seasons = seasons.sortedBy { it.number },
                rating = rating,
                runtime = runtime,
                released = released,
                trailer = trailer,
                cast = cast,
                recommendations = recommendations,
            )
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return try {
            val showId = seasonId.substringBefore("@")
            val seasonNumber = seasonId.substringAfter("@")
            val document = getPage(showId)
            val seasonElement = document.select("div[data-season-panel=$seasonNumber]").firstOrNull() ?: return emptyList()
            seasonElement.select("a.ep-item").map { episodeElement ->
                val epNumText = episodeElement.selectFirst("p.ep-num")?.text() ?: "E0"
                val episodeNum = epNumText.filter { it.isDigit() }.toIntOrNull() ?: 0
                val episodeTitle = episodeElement.selectFirst("p.leading-tight")?.text()
                    ?: "Episodio $episodeNum"
                val episodeOverview = episodeElement.selectFirst("p.text-xs.line-clamp-2")?.text() 
                    ?: episodeElement.selectFirst("p[style*='color:#5050a0']")?.text()

                Episode(
                    id = episodeElement.attr("href"),
                    number = episodeNum,
                    title = episodeTitle.trim(),
                    overview = episodeOverview?.trim(),
                    poster = episodeElement.selectFirst("img.ep-thumb")?.attr("src")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val doc = getPage(id)
            val allServers = mutableListOf<Video.Server>()
            
            val serverBtns = doc.select("button.server-btn")
            for (btn in serverBtns) {
                val serverUrl = btn.attr("data-server-url")
                val playerId = btn.attr("data-player-id")
                val playerModel = btn.attr("data-player-model")
                val playerToken = btn.attr("data-player-token")
                
                if (playerToken.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val payload = JSONObject().put("t", playerToken)
                            val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
                            
                            var requestBuilder = Request.Builder()
                                .url("$baseUrl/api/player-url")
                                .post(requestBody)
                                .header("Referer", id)
                                .header("X-Requested-With", "XMLHttpRequest")
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json")
                            
                            fun addHeaders(builder: Request.Builder) {
                                val cookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
                                val xsrfCookie = cookies.firstOrNull { it.name == "XSRF-TOKEN" }?.value
                                if (xsrfCookie != null) {
                                    val decoded = java.net.URLDecoder.decode(xsrfCookie, "UTF-8")
                                    builder.header("X-XSRF-TOKEN", decoded)
                                }
                                syncClearanceCookieState()?.takeIf { it.isNotBlank() }?.let {
                                    builder.header("Cookie", it)
                                }
                            }
                            
                            addHeaders(requestBuilder)
                            var response = client.newCall(requestBuilder.build()).execute()
                            
                            if (response.code == 419 || response.code == 403) {
                                response.close()
                                // Fetch CSRF cookie
                                val csrfReq = Request.Builder()
                                    .url("$baseUrl/sanctum/csrf-cookie")
                                    .build()
                                client.newCall(csrfReq).execute().close()
                                
                                // Rebuild request and add new XSRF header
                                requestBuilder = Request.Builder()
                                    .url("$baseUrl/api/player-url")
                                    .post(requestBody)
                                    .header("Referer", id)
                                    .header("X-Requested-With", "XMLHttpRequest")
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json")
                                addHeaders(requestBuilder)
                                response = client.newCall(requestBuilder.build()).execute()
                            }
                            
                            val jsonText = response.body?.string() ?: ""
                            response.close()
                            
                            val jsonObject = JSONObject(jsonText)
                            val resolvedUrl = jsonObject.optString("url")
                            if (resolvedUrl.isNotEmpty()) {
                                val nested = processIframe(resolvedUrl, id)
                                allServers.addAll(nested)
                            }
                        } catch (e: Exception) {
                            // Ignore single network errors
                        }
                    }
                } else if (serverUrl.isNotEmpty()) {
                    val nested = processIframe(serverUrl, id)
                    allServers.addAll(nested)
                } else if (playerId.isNotEmpty() && playerModel.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val request = Request.Builder()
                                .url("$baseUrl/api/player-url/$playerModel/$playerId")
                                .header("Referer", id)
                                .header("X-Requested-With", "XMLHttpRequest")
                                .build()
                            val response = client.newCall(request).execute()
                            val jsonText = response.body?.string() ?: ""
                            val jsonObject = JSONObject(jsonText)
                            val resolvedUrl = jsonObject.optString("url")
                            if (resolvedUrl.isNotEmpty()) {
                                val nested = processIframe(resolvedUrl, id)
                                allServers.addAll(nested)
                            }
                        } catch (e: Exception) {
                            // Ignore single network errors
                        }
                    }
                }
            }
            
            allServers.distinctBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun solvePoW(challenge: String, difficulty: Int, salt: String): ByteArray {
        val prefix = "0".repeat(difficulty)
        var nonce = 0
        val md = MessageDigest.getInstance("SHA-256")
        while (true) {
            val input = challenge + nonce
            val hashBytes = md.digest(input.toByteArray(Charsets.UTF_8))
            val hashStr = hashBytes.joinToString("") { "%02x".format(it) }
            if (hashStr.startsWith(prefix)) {
                val keyMaterial = challenge + nonce + salt
                return md.digest(keyMaterial.toByteArray(Charsets.UTF_8))
            }
            nonce++
        }
    }

    private fun decryptAES(encrypted: String, aesKey: ByteArray): String? {
        return try {
            val decoded = Base64.decode(encrypted, Base64.DEFAULT)
            val iv = decoded.copyOfRange(0, 16)
            val cipherText = decoded.copyOfRange(16, decoded.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun processIframe(iframeUrl: String, referer: String): List<Video.Server> {
        return try {
            val iframeDoc = getPage(iframeUrl)
            val iframeHtml = iframeDoc.html()
            val servers = mutableListOf<Video.Server>()

            // Try to resolve PoW parameters
            var aesKey: ByteArray? = null
            try {
                val challenge = Regex("""const\s+POW_CHALLENGE\s*=\s*'([^']+)';""").find(iframeHtml)?.groupValues?.get(1)
                val difficulty = Regex("""const\s+POW_DIFFICULTY\s*=\s*(\d+);""").find(iframeHtml)?.groupValues?.get(1)?.toIntOrNull()
                val salt = Regex("""const\s+POW_SALT\s*=\s*'([^']+)';""").find(iframeHtml)?.groupValues?.get(1)
                
                if (challenge != null && difficulty != null && salt != null) {
                    aesKey = solvePoW(challenge, difficulty, salt)
                }
            } catch (e: Exception) {
                // PoW solving error
            }

            // 1. DataLink case
            try {
                val dataLinkMatch = Regex("""dataLink = (\[.+?\]);""").find(iframeHtml)
                if (dataLinkMatch != null) {
                    val items = json.decodeFromString<List<Item>>(dataLinkMatch.groupValues[1])
                    for (item in items) {
                        val lang = when(item.video_language) {
                            "LAT" -> "[LAT]"
                            "ESP" -> "[CAST]"
                            "SUB" -> "[SUB]"
                            "JAP" -> "[JAP]"
                            else -> ""
                        }
                        for (embed in item.sortedEmbeds) {
                            if (embed.servername.equals("download", ignoreCase = true)) continue
                            
                            val decryptedLink = if (embed.link.contains(".") && embed.link.split(".").size == 3) {
                                decodeBase64Link(embed.link)
                            } else if (aesKey != null) {
                                decryptAES(embed.link, aesKey)
                            } else {
                                null
                            }
                            
                            if (decryptedLink != null) {
                                servers.add(Video.Server(id = decryptedLink, name = "${embed.servername} $lang".trim()))
                            }
                        }
                    }
                }
            } catch (e: Exception) { /* JSON error - continue */ }

            // 2. DOM-base
            try {
                val domItems = iframeDoc.select(".ODDIV .OD_1 li[onclick]")
                for (dom in domItems) {
                    val onclick = dom.attr("onclick")
                    val m = Regex("""go_to_playerVast\(\s*'([^']+)'""").find(onclick)
                    val finalUrl = m?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    if (finalUrl.isBlank()) continue
                    val serverName = dom.selectFirst("span")?.text()?.trim().orEmpty()
                    if (serverName.equals("1fichier", ignoreCase = true) || serverName.equals("download", ignoreCase = true)) continue
                    if (servers.none { it.id == finalUrl }) {
                        servers.add(Video.Server(id = finalUrl, name = serverName))
                    }
                }
            } catch (e: Exception) { /* DOM error - continue */ }

            // 3. Direct Iframe
            iframeDoc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotEmpty() }?.let { src ->
                val name = src.substringAfter("//").substringBefore("/").replace("www.", "").substringBefore(".").replaceFirstChar { it.uppercase() }
                if (servers.none { it.id == src }) {
                    servers.add(Video.Server(id = src, name = name))
                }
            }

            servers
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeBase64Link(encryptedLink: String): String? {
        return try {
            // Encrypted link has format: header.payload.signature
            val parts = encryptedLink.split(".")
            if (parts.size != 3) return null
            
            // Decode the payload (middle part) from base64
            var payloadB64 = parts[1]
            
            // Add padding if necessary
            val missingPadding = payloadB64.length % 4
            if (missingPadding != 0) {
                payloadB64 += "=".repeat(4 - missingPadding)
            }
            
            // Decode base64 payload
            val payloadJson = String(Base64.decode(payloadB64, Base64.DEFAULT))
            
            // Manual parsing for robustness
            val linkStart = payloadJson.indexOf("\"link\":\"")
            if (linkStart == -1) return null
            val valueStart = linkStart + 8
            val valueEnd = payloadJson.indexOf("\"", valueStart)
            if (valueEnd == -1) return null
            payloadJson.substring(valueStart, valueEnd)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.id, server)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) {
            return People(id = id, name = "")
        }
        return try {
            val document = getPage(id)
            val name = document.selectFirst(".data h1")?.text() ?: ""
            val poster = document.selectFirst(".poster img")?.attr("src")
            val filmography = parseMixed(document)
            People(
                id = id,
                name = name,
                image = poster?.let { if (it.startsWith("http")) it else "$baseUrl$it" },
                filmography = filmography
            )
        } catch (e: Exception) {
            throw e
        }
    }
}