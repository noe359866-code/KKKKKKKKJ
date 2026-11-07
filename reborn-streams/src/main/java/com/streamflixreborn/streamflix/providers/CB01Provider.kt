package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TmdbUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Field
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import com.streamflixreborn.streamflix.utils.Keys
import com.streamflixreborn.streamflix.utils.UprotResolver
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object CB01Provider : Provider {

    override val name = "CB01"
    override val baseUrl = "https://cb01official.uno"
    override val logo: String get() = "$baseUrl/apple-icon-180x180px.png"
    override val language = "it"

    private const val USER_AGENT = "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private interface CB01Service {
        @Headers(USER_AGENT)
        @GET(".")
        suspend fun getHome(): Document

        @Headers(USER_AGENT)
        @GET("page/{page}/")
        suspend fun getMovies(@Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET("serietv/")
        suspend fun getTvShows(): Document

        @Headers(USER_AGENT)
        @GET("serietv/page/{page}/")
        suspend fun getTvShows(@Path("page") page: Int): Document

        @Headers(USER_AGENT)
        @GET(".")
        suspend fun searchMovies(@Query("s") query: String): Document

        @Headers(USER_AGENT)
        @GET("page/{page}/")
        suspend fun searchMovies(@Path("page") page: Int, @Query("s") query: String): Document

        @Headers(USER_AGENT)
        @GET("serietv/")
        suspend fun searchTvShows(@Query("s") query: String): Document

        @Headers(USER_AGENT)
        @GET("serietv/page/{page}/")
        suspend fun searchTvShows(@Path("page") page: Int, @Query("s") query: String): Document

        @Headers(USER_AGENT)
        @GET
        suspend fun getPage(@Url url: String): Document

        companion object {
            fun build(baseUrl: String): CB01Service {
                val clientBuilder = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)

                val client = clientBuilder.dns(DnsResolver.doh).build()

                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                return retrofit.create(CB01Service::class.java)
            }
        }
    }

    private val service = CB01Service.build(baseUrl)

    private interface StayService {
        @FormUrlEncoded
        @POST
        suspend fun postAjax(
            @Url url: String,
            @Field("id") id: String,
            @Field("ref") ref: String = "",
            @Header("User-Agent") userAgent: String,
            @Header("Referer") referer: String
        ): Response<ResponseBody>

        companion object {
            fun build(): StayService {
                val clientBuilder = OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)

                val client = clientBuilder.dns(DnsResolver.doh).build()

                val retrofit = Retrofit.Builder()
                    .baseUrl("https://stayonline.pro/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                return retrofit.create(StayService::class.java)
            }
        }
    }

    private val stayService = StayService.build()

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private fun cleanTitle(raw: String): String {
        val withoutYearHd = raw
            .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
            .replace(Regex("\\s*\\[\\s*hd\\s*(?:/3d)?\\s*\\]", RegexOption.IGNORE_CASE), "")

        val episodeLangRegex = Regex(
            pattern = "\\s*[–-]\\s*\\d+[x×]\\d+(?:[./]\\d+)*\\s*[–-]\\s*([A-Za-z][A-Za-z -]{1,})\\s*$",
            option = RegexOption.IGNORE_CASE
        )
        val cleanedEpisode = episodeLangRegex.replace(withoutYearHd) { mr ->
            val lang = mr.groupValues.getOrNull(1)?.trim().orEmpty()
            if (lang.startsWith("sub", ignoreCase = true)) " - $lang" else ""
        }
        val completaRegex = Regex("\\s*[–-]\\s*Stagione\\s+\\d+\\s*[–-]\\s*COMPLETA\\s*$", RegexOption.IGNORE_CASE)
        val cleaned = completaRegex.replace(cleanedEpisode, "")

        return cleaned.trim()
    }

    private fun parseGenresText(raw: String, hasDurationMarker: Boolean): List<Genre> {
        val withoutDuration = if (hasDurationMarker) {
            raw.split(Regex("\\s*[–-]\\s*DURATA", RegexOption.IGNORE_CASE)).firstOrNull()
                ?: raw
        } else raw

        val withoutYears = withoutDuration.replace(Regex("\\s*\\(\\d{4}[^)]*\\)\\s*$"), "").trim()

        return withoutYears.split(Regex("\\s*/\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { name ->
                val normalized = name.lowercase().replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase() else c.toString()
                }
                Genre(id = normalized, name = normalized)
            }
    }

    private fun parseHomeMovie(el: Element): Movie? {
        val titleAnchor = el.selectFirst("h3.card-title a[href]") ?: return null
        val href = titleAnchor.attr("href").trim()
        val rawTitle = titleAnchor.text().trim()
        val title = cleanTitle(rawTitle)
        val img = el.selectFirst(".card-image img[src]")?.attr("src").orEmpty()
        val poster = img
        val quality = if (rawTitle.contains("[HD]", ignoreCase = true) || rawTitle.contains("[HD/3D]", ignoreCase = true)) "HD" else null
        if (href.isBlank() || title.isBlank()) return null
        return Movie(
            id = href,
            title = title,
            poster = poster,
            quality = quality,
        )
    }

    private fun parseHomeTvShow(el: Element): TvShow? {
        val titleAnchor = el.selectFirst("h3.card-title a[href]") ?: return null
        val href = titleAnchor.attr("href").trim()
        val rawTitle = titleAnchor.text().trim()
        val title = cleanTitle(rawTitle)
        val img = el.selectFirst(".card-image img[src]")?.attr("src").orEmpty()
        val poster = img
        val quality = if (rawTitle.contains("[HD]", ignoreCase = true) || rawTitle.contains("[HD/3D]", ignoreCase = true)) "HD" else null
        if (href.isBlank() || title.isBlank()) return null
        return TvShow(
            id = href,
            title = title,
            poster = poster,
            quality = quality,
        )
    }

    private fun parseLatestMovie(el: Element): Movie? {
        val titleAnchor = el.selectFirst("h3.rpwe-title a[href]") ?: return null
        val href = titleAnchor.attr("href").trim()
        val rawTitle = titleAnchor.text().trim()
        val title = cleanTitle(rawTitle)
        val poster = el.selectFirst("img.rpwe-thumb")?.attr("src").orEmpty().replace("-60x90", "")
        val quality = if (rawTitle.contains("[HD]", ignoreCase = true) || rawTitle.contains("[HD/3D]", ignoreCase = true)) "HD" else null

        if (href.isBlank() || title.isBlank()) return null
        return Movie(
            id = href,
            title = title,
            poster = poster,
            quality = quality,
        )
    }

    override suspend fun getHome(): List<Category> {
        val doc = service.getHome()

        val categories = mutableListOf<Category>()

        val movies = doc.select("div.card.mp-post.horizontal").mapNotNull { parseHomeMovie(it) }
        if (movies.isNotEmpty()) {
            categories.add(Category(name = "Film", list = movies))
        }

        val latestMovies = doc.select("#rpwe_widget-2 ul.rpwe-ul li.rpwe-li").mapNotNull { parseLatestMovie(it) }
        if (latestMovies.isNotEmpty()) {
            categories.add(Category(name = "Ultimi Film Aggiunti", list = latestMovies))
        }

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            val home = service.getHome()
            val hdItems = home.select("#mega-menu-sequex-main-menu li:has(> a:matchesOwn(^Film HD Streaming$)) .mega-sub-menu a.mega-menu-link")
                .mapNotNull { a ->
                    val href = a.attr("href").trim()
                    val nameRaw = a.text().trim()
                    if (href.isBlank() || nameRaw.isBlank()) return@mapNotNull null
                    if (!href.contains("/category/film-hd-streaming/", true)) return@mapNotNull null
                    val normName = nameRaw.lowercase()
                        .replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
                        .replace(Regex("\\bhd\\b", RegexOption.IGNORE_CASE), "Hd")
                    Genre(id = if (href.startsWith("http")) href else baseUrl + href, name = normName)
                }

            val genreItems = home.select("#mega-menu-sequex-main-menu li:has(> a:matchesOwn(^Film per Genere$)) .mega-sub-menu a.mega-menu-link")
                .mapNotNull { a ->
                    val href = a.attr("href").trim()
                    val nameRaw = a.text().trim()
                    if (href.isBlank() || nameRaw.isBlank()) return@mapNotNull null
                    if (!href.contains("/category/", true)) return@mapNotNull null
                    val normName = nameRaw.lowercase().replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
                    Genre(id = if (href.startsWith("http")) href else baseUrl + href, name = normName)
                }

            return (hdItems + genreItems).sortedBy { it.name }
        }

        val movieResults = try {
            val movieDoc = if (page <= 1) service.searchMovies(query) else service.searchMovies(page, query)
            movieDoc.select("div.card.mp-post.horizontal").mapNotNull { parseHomeMovie(it) }
        } catch (_: Exception) {
            emptyList()
        }
        
        val tvResults = try {
            val tvDoc = if (page <= 1) service.searchTvShows(query) else service.searchTvShows(page, query)
            tvDoc.select("div.card.mp-post.horizontal").mapNotNull { parseHomeTvShow(it) }
        } catch (_: Exception) {
            emptyList()
        }
        
        return movieResults + tvResults
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val doc = if (page <= 1) service.getHome() else service.getMovies(page)
            doc.select("div.card.mp-post.horizontal").mapNotNull { parseHomeMovie(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        return try {
            val doc = if (page <= 1) service.getTvShows() else service.getTvShows(page)
            doc.select("div.card.mp-post.horizontal").mapNotNull { parseHomeTvShow(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val doc = service.getPage(id)

        val rawTitle = doc.selectFirst("span.breadcrumb_last")?.text()?.trim()
            ?: doc.selectFirst("h1, h2")?.text()?.trim()
            ?: ""
        val title = cleanTitle(rawTitle)
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.getOrNull(1)
        val runtime = doc.selectFirst("div.ignore-css > p > strong")?.text()?.let { Regex("DURATA\\s+(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val tmdbMovie = TmdbUtils.getMovie(title, language = language)

        val poster = tmdbMovie?.poster ?: doc.selectFirst("div.sequex-featured-img.s-post img[src]")?.attr("src")

        return Movie(
            id = id,
            title = title,
            poster = poster,
            overview = tmdbMovie?.overview ?: doc.select("div.ignore-css > p").firstOrNull { !it.text().contains("DURATA", true) }?.text()?.replace(Regex("\\s*\\+?Info\\s*»\\s*$", RegexOption.IGNORE_CASE), "")?.trim(),
            genres = tmdbMovie?.genres ?: (doc.selectFirst("div.ignore-css > p > strong")?.text()?.trim()?.let { parseGenresText(it, hasDurationMarker = true) } ?: emptyList()),
            trailer = tmdbMovie?.trailer ?: doc.selectFirst("table.cbtable:has(font:matchesOwn(^Guarda il Trailer:$)) + p iframe[data-src*='youtube.com/embed/']")?.attr("data-src")?.replace("/embed/", "/watch?v="),
            quality = if (rawTitle.contains("[HD]", ignoreCase = true) || rawTitle.contains("[HD/3D]", ignoreCase = true)) "HD" else null,
            rating = tmdbMovie?.rating ?: doc.selectFirst("div.imdb_r [itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull(),
            released = tmdbMovie?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" } ?: year,
            runtime = tmdbMovie?.runtime ?: runtime,
            banner = tmdbMovie?.banner,
            imdbId = tmdbMovie?.imdbId,
            cast = tmdbMovie?.cast ?: emptyList()
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = service.getPage(id)

        val rawTitle = doc.selectFirst("span.breadcrumb_last")?.text()?.trim()
            ?: doc.selectFirst("h1, h2")?.text()?.trim()
            ?: ""
        val title = cleanTitle(rawTitle)

        val year = doc.selectFirst("div.ignore-css > p > strong")?.text()?.trim()?.let { Regex("\\((\\d{4})\\)").findAll(it).lastOrNull()?.groupValues?.getOrNull(1) }
        val tmdbTvShow = TmdbUtils.getTvShow(title, language = language)

        val poster = tmdbTvShow?.poster ?: doc.selectFirst("div.sequex-featured-img.s-post img[src]")?.attr("src")
        val seasons = mutableListOf<Season>()
        doc.select("div.sp-wrap").forEachIndexed { index, wrap ->
            val head = wrap.selectFirst("div.sp-head")?.text()?.trim() ?: return@forEachIndexed
            if (head.contains("TUTTE LE STAGIONI", ignoreCase = true)) return@forEachIndexed

            val body = wrap.selectFirst("div.sp-body")
            if (body != null) {
                val hasLinks = body.select("a[href]").any { a ->
                    val href = a.attr("href").lowercase()
                    val text = a.text().lowercase()
                    text.contains("mixdrop") || href.contains("stayonline.pro") || href.contains("uprot.net") || href.contains("maxstream")
                }
                if (!hasLinks) return@forEachIndexed
            }

            val rangeMatch = Regex("STAGIONE\\s+(\\d+)\\s+(?:A|-)\\s+(\\d+)", RegexOption.IGNORE_CASE).find(head)

            val isCompleteSeries = body?.text()?.contains("TUTTA LA SERIE", ignoreCase = true) == true ||
                                   head.contains("COMPLETA", ignoreCase = true)

            // Only expand using TMDB if the page doesn't already explicitly list multiple seasons (e.g. via a range in the title)
            if (isCompleteSeries && rangeMatch == null && tmdbTvShow != null && tmdbTvShow.seasons.isNotEmpty()) {
                val tmdbSeasons = tmdbTvShow.seasons.filter { it.number > 0 }
                if (tmdbSeasons.size > 1) { // Only expand if TMDB has more than 1 season
                    tmdbSeasons.forEach { tmdbSeason ->
                        if (!seasons.any { it.number == tmdbSeason.number }) {
                            seasons.add(
                                Season(
                                    id = "$id#s${tmdbSeason.number}#w$index",
                                    number = tmdbSeason.number,
                                    title = null, // fallback to TMDB translation
                                    poster = tmdbSeason.poster ?: tmdbTvShow.poster
                                )
                            )
                        }
                    }
                    return@forEachIndexed
                }
            }

            val cleanedHead = head
                .replace(Regex("\\[\\s*hd\\s*(?:/3d)?\\s*\\]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*-\\s*(?:ITA|SUB ITA|HD|HD/3D)\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()

            val formattedTitle = if (cleanedHead.equals(cleanedHead.uppercase(), ignoreCase = false) && cleanedHead.contains(Regex("[A-Z]"))) {
                cleanedHead.lowercase().split(" ").joinToString(" ") { word ->
                    word.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
                }
            } else {
                cleanedHead
            }

            if (rangeMatch != null) {
                val start = rangeMatch.groupValues[1].toIntOrNull() ?: 1
                val end = rangeMatch.groupValues[2].toIntOrNull() ?: start
                for (s in start..end) {
                    var candidate = s
                    while (seasons.any { it.number == candidate }) {
                        candidate++
                    }
                    seasons.add(
                        Season(
                            id = "$id#s$candidate#w$index",
                            number = candidate,
                            title = if (candidate != s) "$formattedTitle (Stagione $s)" else null,
                            poster = tmdbTvShow?.seasons?.find { it.number == s }?.poster ?: tmdbTvShow?.poster
                        )
                    )
                }
            } else {
                val parsedNumber = Regex("(?:STAGIONE|SEASON)\\s+(\\d+)", RegexOption.IGNORE_CASE)
                    .find(head)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (seasons.size + 1)
                var candidate = parsedNumber
                while (seasons.any { it.number == candidate }) {
                    candidate++
                }

                val cleanSectionName = cleanedHead
                    .replace(Regex("(?:STAGIONE|SEASON)\\s+\\d+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("COMPLETA", RegexOption.IGNORE_CASE), "")
                    .trim()

                val spinOffPoster = if (cleanSectionName.isNotBlank() && !cleanSectionName.equals("SERIE", ignoreCase = true)) {
                    val spinOffShow = TmdbUtils.getTvShow("$title $cleanSectionName", language = language)
                    spinOffShow?.poster ?: spinOffShow?.seasons?.find { it.number == parsedNumber }?.poster
                } else null

                val seasonPoster = spinOffPoster
                    ?: tmdbTvShow?.seasons?.find { it.number == parsedNumber }?.poster
                    ?: tmdbTvShow?.seasons?.find { it.number == candidate }?.poster
                    ?: tmdbTvShow?.poster

                val isStandardSeasonTitle = formattedTitle.matches(Regex("^Stagione\\s+\\d+$", RegexOption.IGNORE_CASE))

                seasons.add(
                    Season(
                        id = "$id#s$candidate#w$index",
                        number = candidate,
                        title = if (!isStandardSeasonTitle) formattedTitle else null,
                        poster = seasonPoster
                    )
                )
            }
        }

        return TvShow(
            id = id,
            title = title,
            poster = poster,
            trailer = tmdbTvShow?.trailer ?: doc.selectFirst("table.cbtable:has(font:matchesOwn(^Guarda il Trailer:$)) + p iframe")?.attr("data-src")?.replace("/embed/", "/watch?v="),
            overview = tmdbTvShow?.overview ?: doc.select("div.ignore-css > p").firstOrNull { !it.text().contains("DURATA", true) }?.clone()?.apply { select("strong, b").remove() }?.text()?.replace(Regex("\\s*\\+?Info\\s*»\\s*$", RegexOption.IGNORE_CASE), "")?.trim(),
            genres = tmdbTvShow?.genres ?: (doc.selectFirst("div.ignore-css > p > strong")?.text()?.trim()?.let { parseGenresText(it, hasDurationMarker = false) } ?: emptyList()),
            seasons = seasons,
            rating = tmdbTvShow?.rating ?: doc.selectFirst("div.imdb_r [itemprop=ratingValue]")?.text()?.trim()?.toDoubleOrNull(),
            released = tmdbTvShow?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" } ?: year,
            runtime = tmdbTvShow?.runtime,
            banner = tmdbTvShow?.banner,
            imdbId = tmdbTvShow?.imdbId,
            cast = tmdbTvShow?.cast ?: emptyList()
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore("#s")
        val part = seasonId.substringAfter("#s")
        val candidateSeasonNum = part.substringBefore("#w").substringBefore("-e").toIntOrNull() ?: 1
        val wrapIndex = if (part.contains("#w")) part.substringAfter("#w").toIntOrNull() else null

        val doc = service.getPage(showId)
        val rawShowTitle = doc.selectFirst("h1")?.text() ?: ""
        val mainTitle = cleanTitle(rawShowTitle)

        val wraps = doc.select("div.sp-wrap")
        val wrap = if (wrapIndex != null && wrapIndex in wraps.indices) {
            wraps[wrapIndex]
        } else {
            wraps.firstOrNull { w ->
                val head = w.selectFirst("div.sp-head")?.text()?.trim().orEmpty()
                head.contains(Regex("STAGIONE\\s+$candidateSeasonNum", RegexOption.IGNORE_CASE))
            } ?: wraps.firstOrNull()
        } ?: return emptyList()

        val headText = wrap.selectFirst("div.sp-head")?.text()?.trim().orEmpty()
        val parsedSeasonNum = Regex("STAGIONE\\s+(\\d+)", RegexOption.IGNORE_CASE)
            .find(headText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: candidateSeasonNum

        val cleanSectionTitle = headText
            .replace(Regex("\\s*-\\s*(?:ITA|SUB ITA|HD|HD/3D)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("STAGIONE\\s+\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("COMPLETA", RegexOption.IGNORE_CASE), "")
            .trim()

        val spinOffTitle = if (cleanSectionTitle.isNotBlank() && !cleanSectionTitle.equals("SERIE", ignoreCase = true) && !cleanSectionTitle.equals("TUTTE LE STAGIONI", ignoreCase = true)) {
            "$mainTitle $cleanSectionTitle"
        } else null

        val spinOffTvShow = spinOffTitle?.let { TmdbUtils.getTvShow(it, language = language) }
        val mainTvShow = TmdbUtils.getTvShow(mainTitle, language = language)

        val tmdbTvShow = spinOffTvShow ?: mainTvShow
        val targetSeasonNum = if (spinOffTvShow != null) parsedSeasonNum else (parsedSeasonNum.takeIf { it > 0 } ?: candidateSeasonNum)

        val tmdbEpisodes = if (tmdbTvShow != null) {
            TmdbUtils.getEpisodesBySeason(tmdbTvShow.id, targetSeasonNum, language = language)
        } else emptyList()

        val seasonNum = candidateSeasonNum
        val body = wrap.selectFirst("div.sp-body") ?: return emptyList()


        // Check if there is a uprot folder link (msfld)
        val folderLink = body.select("a[href*=/msfld/]").firstOrNull()?.attr("href")?.trim()

        if (!folderLink.isNullOrBlank()) {
            return try {
                val folderDoc = service.getPage(folderLink)
                val rows = folderDoc.select("table tr")

                val seasonsInFolder = rows.mapNotNull { tr ->
                    val fileName = tr.selectFirst("td")?.text()?.trim().orEmpty()
                    val match = Regex("""S(\d{1,2})E""", RegexOption.IGNORE_CASE).find(fileName)
                        ?: Regex("""(?:^|\b|\.)(\d{1,2})x\d""", RegexOption.IGNORE_CASE).find(fileName)
                    match?.groupValues?.getOrNull(1)?.toIntOrNull()
                }.distinct()

                val hasMultipleSeasonsInFolder = seasonsInFolder.size > 1

                rows.mapIndexedNotNull { idx, tr ->
                    val fileName = tr.selectFirst("td")?.text()?.trim().orEmpty()
                    val watchUrl = tr.selectFirst("a[href*=/msfi/]")?.attr("href")?.trim()
                        ?: tr.selectFirst("a[href]")?.attr("href")?.trim()
                        ?: return@mapIndexedNotNull null
                    val sAndEMatch = Regex("""S(\d{1,2})E(\d{1,3})""", RegexOption.IGNORE_CASE).find(fileName)
                        ?: Regex("""(?:^|\b|\.)(\d{1,2})x(\d{1,3})""", RegexOption.IGNORE_CASE).find(fileName)
                    val epNum: Int
                    if (sAndEMatch != null) {
                        val fileSeason = sAndEMatch.groupValues[1].toIntOrNull()
                        val fileEp = sAndEMatch.groupValues[2].toIntOrNull()
                        if (hasMultipleSeasonsInFolder && fileSeason != null && fileSeason != seasonNum) {
                            return@mapIndexedNotNull null
                        }
                        epNum = fileEp ?: (idx + 1)
                    } else {
                        epNum = Regex("""(?:\.|\b|E)(\d{1,4})(?:\.|\b|ITA|WEB|SUB)""", RegexOption.IGNORE_CASE)
                            .find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: Regex("""(\d{1,4})""").find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: (idx + 1)
                    }

                    val tmdbEp = tmdbEpisodes.find { it.number == epNum }

                    Episode(
                        id = "$showId#epUrl=$watchUrl",
                        number = epNum,
                        title = tmdbEp?.title ?: fileName.ifBlank { "Episodio $epNum" },
                        poster = tmdbEp?.poster,
                        overview = tmdbEp?.overview
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }


        return body.select("p").mapNotNull { p ->
            val text = p.text().trim()
            val epNum = Regex("(\\d+)[x×](\\d+)").find(text)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: return@mapNotNull null

            val tmdbEp = tmdbEpisodes.find { it.number == epNum }

            Episode(
                id = "$showId#s${seasonNum}-e${epNum}",
                number = epNum,
                title = tmdbEp?.title,
                poster = tmdbEp?.poster,
                overview = tmdbEp?.overview
            )
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = "", filmography = emptyList())
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page <= 1) id else id.trimEnd('/') + "/page/$page/"
        return try {
            val doc = service.getPage(url)
            val movies = doc.select("div.card.mp-post.horizontal").mapNotNull { parseHomeMovie(it) }
            Genre(id = id, name = id.substringAfterLast('/').replace('-', ' ').uppercase(), shows = movies)
        } catch (_: Exception) {
            Genre(id = id, name = id.substringAfterLast('/'), shows = emptyList())
        }
    }

    private data class CandidateLink(
        val href: String,
        val baseName: String,
        val inHdSection: Boolean,
        val in3dSection: Boolean
    )

    private suspend fun resolveServerLink(href: String, baseName: String): String? {
        val lowerName = baseName.lowercase()
        return if (href.contains("stayonline.pro", ignoreCase = true)) {
            val stayId = Regex("/l/([A-Za-z0-9]+)/?").find(href)?.groupValues?.getOrNull(1)
            if (!stayId.isNullOrBlank()) {
                try {
                    val resp = stayService.postAjax(
                        url = "https://stayonline.pro/ajax/linkEmbedView.php",
                        id = stayId,
                        ref = "",
                        userAgent = DEFAULT_USER_AGENT,
                        referer = "https://stayonline.pro/"
                    )
                    val body = resp.body()?.string().orEmpty()
                    val targetUrl = try {
                        val json = JSONObject(body)
                        if (json.optString("status").equals("success", ignoreCase = true)) {
                            json.optJSONObject("data")?.optString("value")?.takeIf { it.isNotBlank() }
                        } else null
                    } catch (_: Exception) { null }

                    if (!targetUrl.isNullOrBlank()) {
                        if (lowerName.contains("maxstream")) {
                            UprotResolver.resolve(targetUrl, isStayOnline = true)
                        } else {
                            targetUrl
                        }
                    } else null
                } catch (_: Exception) { null }
            } else null
        } else if (lowerName.contains("maxstream") || href.contains("uprot.net", ignoreCase = true)) {
            UprotResolver.resolve(href)
        } else {
            href
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return when (videoType) {
            is Video.Type.Movie -> {
                val doc = service.getPage(id)

                val servers3D = mutableListOf<Video.Server>()
                val hdServers = mutableListOf<Video.Server>()
                val sdServers = mutableListOf<Video.Server>()
                var includeLinks = false
                var inHdSection = false
                var in3dSection = false

                val candidates = mutableListOf<CandidateLink>()

                for (tbl in doc.select("table.tableinside, table.cbtable")) {
                    val heading = tbl.selectFirst("u > strong")?.text()?.trim()?.lowercase()
                    if (heading != null) {
                        includeLinks = heading.startsWith("streaming")
                        inHdSection = heading.startsWith("streaming hd")
                        in3dSection = heading.startsWith("streaming 3d")
                        if (heading.startsWith("download")) includeLinks = false
                        continue
                    }

                    if (!includeLinks || !tbl.hasClass("tableinside")) continue

                    for (a in tbl.select("a[href]")) {
                        val href = a.attr("href").trim()
                        if (href.isBlank()) continue
                        val baseName = a.text().trim().ifBlank { "Server" }
                        val lowerName = baseName.lowercase()
                        if (!lowerName.contains("mixdrop") && !lowerName.contains("maxstream")) continue
                        if (lowerName.contains("maxstream") && !href.contains("uprot.net", ignoreCase = true) && !href.contains("stayonline.pro", ignoreCase = true)) continue

                        candidates.add(CandidateLink(href, baseName, inHdSection, in3dSection))
                    }
                }

                val resolvedList = coroutineScope {
                    candidates.map { candidate ->
                        async(Dispatchers.IO) {
                            val resolvedUrl = resolveServerLink(candidate.href, candidate.baseName)
                            if (!resolvedUrl.isNullOrBlank()) {
                                val labeled = when {
                                    candidate.in3dSection && !candidate.baseName.endsWith("3D", true) -> "${candidate.baseName} 3D"
                                    candidate.inHdSection && !candidate.baseName.endsWith("HD", true) -> "${candidate.baseName} HD"
                                    else -> candidate.baseName
                                }
                                Triple(
                                    Video.Server(id = resolvedUrl, name = labeled, src = resolvedUrl),
                                    candidate.inHdSection,
                                    candidate.in3dSection
                                )
                            } else null
                        }
                    }.awaitAll().filterNotNull()
                }

                for ((server, isHd, is3d) in resolvedList) {
                    when {
                        is3d -> servers3D.add(server)
                        isHd -> hdServers.add(server)
                        else -> sdServers.add(server)
                    }
                }

                hdServers + sdServers + servers3D
            }
            is Video.Type.Episode -> {
                if (id.contains("#epUrl=")) {
                    val watchUrl = id.substringAfter("#epUrl=")
                    val servers = mutableListOf<Video.Server>()
                    val finalMaxstream = UprotResolver.resolve(watchUrl, isStayOnline = watchUrl.contains("stayonline.pro"))
                    if (!finalMaxstream.isNullOrBlank()) {
                        servers.add(Video.Server(id = finalMaxstream, name = "Maxstream", src = finalMaxstream))
                    }
                    return servers
                }

                val showId = id.substringBefore("#s")
                val part = id.substringAfter("#s")
                val seasonNum = part.substringBefore("-e").toIntOrNull() ?: return emptyList()
                val episodeNum = part.substringAfter("-e").toIntOrNull() ?: return emptyList()

                val doc = service.getPage(showId)
                val wrap = doc.select("div.sp-wrap").firstOrNull { w ->
                    val head = w.selectFirst("div.sp-head")?.text()?.trim().orEmpty()
                    head.contains(Regex("STAGIONE\\s+$seasonNum", RegexOption.IGNORE_CASE))
                } ?: return emptyList()
                val body = wrap.selectFirst("div.sp-body") ?: return emptyList()

                val line = body.select("p").firstOrNull { para ->
                    val text = para.text().trim()
                    Regex("(\\d+)[x×](\\d+)").find(text)?.groupValues?.getOrNull(2)?.toIntOrNull() == episodeNum
                } ?: return emptyList()

                val rawLinks = line.select("a[href]").mapNotNull { a ->
                    val href = a.attr("href").trim()
                    val name = a.text().trim()
                    val lowerName = name.lowercase()
                    if (href.isBlank() || (!lowerName.contains("mixdrop") && !lowerName.contains("maxstream"))) null
                    else Pair(href, name)
                }

                val servers = coroutineScope {
                    rawLinks.map { (href, name) ->
                        async(Dispatchers.IO) {
                            val resolvedUrl = resolveServerLink(href, name)
                            if (!resolvedUrl.isNullOrBlank()) {
                                val finalName = when {
                                    name.lowercase().contains("mixdrop") -> "Mixdrop"
                                    name.lowercase().contains("maxstream") -> "Maxstream"
                                    else -> name
                                }
                                Video.Server(id = resolvedUrl, name = finalName, src = resolvedUrl)
                            } else null
                        }
                    }.awaitAll().filterNotNull()
                }

                servers
            }
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src, server)
    }
}
