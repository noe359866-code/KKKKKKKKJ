package com.tanasi.streamflix.data.local.scrapers

import androidx.annotation.Keep
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** Identificadores y slugs compartidos por los proveedores de cine y series. */
@Keep
class MovieScraperTools {
    fun buildStreamsJson(provider: String, display: String, signals: String, userAgent: String): String =
        StreamBuilder.build(provider, display, signals, userAgent)

    /** Una cadena vacía solicita al puente que use el parser anterior. */
    fun parseRefJson(contentId: String, provider: String): String {
        val raw = contentId.trim().replace(Regex("\\.json$", RegexOption.IGNORE_CASE), "").trim().trimEnd('/')
        var slug = ""; var url = ""; var titleHint = ""; var imdb: String? = null
        var season: BigInteger? = null; var episode: BigInteger? = null
        var explicitEpisode = false; var explicitUrl = false
        // Python acepta más formas de dígitos Unicode que Java. Para éstas y
        // números excesivos se mantiene exactamente el parser anterior.
        if (raw.any { it.isDigit() && it !in '0'..'9' } || Regex("[0-9]{101}").containsMatchIn(raw)) return ""
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            val path = try { URI(raw).rawPath.orEmpty().trimEnd('/') } catch (_: Exception) { return "" }
            slug = if (path.isNotEmpty()) slugify(path.substringAfterLast('/')) else ""
            url = raw; explicitUrl = true; titleHint = titleFromSlug(slug)
        } else if (raw.isNotEmpty()) {
            val parts = raw.split(':').filter { it.isNotBlank() }.toMutableList()
            while (parts.isNotEmpty() && parts.first().trim().lowercase(Locale.ROOT) in TYPE_TOKENS) parts.removeAt(0)
            if (parts.isNotEmpty() && parts.first().trim().lowercase(Locale.ROOT) in (PROVIDERS + provider.trim().lowercase(Locale.ROOT))) parts.removeAt(0)
            if (parts.isNotEmpty()) {
                fun digits(value: String) = value.trim().matches(Regex("[0-9]+"))
                if (parts.size >= 3 && digits(parts[parts.lastIndex]) && digits(parts[parts.lastIndex - 1])) {
                    season = parts[parts.lastIndex - 1].trim().toBigInteger(); episode = parts.last().trim().toBigInteger()
                    parts.removeAt(parts.lastIndex); parts.removeAt(parts.lastIndex)
                } else if (parts.size >= 2 && digits(parts.last())) {
                    episode = parts.removeAt(parts.lastIndex).trim().toBigInteger()
                }
                val tail = parts.joinToString(":").trim()
                if (IMDB.matches(tail)) {
                    // El parser anterior devuelve el IMDb antes de aplicar la numeración.
                    imdb = tail.lowercase(Locale.ROOT); season = null; episode = null
                } else {
                    slug = slugify(tail)
                    if (slug.isNotEmpty()) {
                        val match = SEASON_EPISODE.find(slug)
                        if (match != null) {
                            if (season == null) season = match.groupValues[1].toBigInteger()
                            if (episode == null) episode = match.groupValues[2].toBigInteger()
                            slug = SEASON_EPISODE.replace(slug, "-").trim('-')
                        }
                        titleHint = titleFromSlug(slug)
                        explicitEpisode = season != null && episode != null
                    } else { season = null; episode = null }
                }
            }
        }
        return buildJsonObject {
            put("slug", slug); put("season", season?.let(::JsonPrimitive) ?: JsonNull)
            put("episode", episode?.let(::JsonPrimitive) ?: JsonNull); put("imdb", imdb?.let(::JsonPrimitive) ?: JsonNull)
            put("url", url); put("title_hint", titleHint); put("title", ""); put("year", "")
            put("slug_candidates", JsonArray(emptyList())); put("explicit_episode", explicitEpisode); put("explicit_url", explicitUrl)
        }.toString()
    }

    fun titleFromSlug(slug: String): String = slug.split('-').filter { it.isNotEmpty() }.mapIndexed { index, word ->
        when {
            word.all { it.isDigit() } -> word
            index > 0 && word.lowercase(Locale.ROOT) in SMALL_WORDS -> word.lowercase(Locale.ROOT)
            else -> word.substring(0, 1).uppercase(Locale.ROOT) + word.substring(1)
        }
    }.joinToString(" ")

    fun slugVariantsJson(title: String, yearValue: String): String {
        if (yearValue.any { it.code > 127 }) return ""
        val base = slugify(title)
        if (base.isEmpty()) return "[]"
        val year = yearValue.take(4)
        val withYear = year.isNotEmpty() && year.all { it.isDigit() }
        val candidates = mutableListOf(if (withYear) "$base-$year" else "", base)
        val stripped = Regex("-?(19|20)\\d{2}$").replace(base, "")
        if (stripped.isNotEmpty() && stripped != base) {
            candidates += if (withYear) "$stripped-$year" else ""
            candidates += stripped
        }
        for (suffix in listOf("-pelicula", "-online", "-hd", "-latino", "-castellano")) {
            if (base.endsWith(suffix)) candidates += base.dropLast(suffix.length)
        }
        return JsonArray(candidates.filter { it.isNotEmpty() }.distinct().map { JsonPrimitive(it) }).toString()
    }

    /** Rellena las plantillas habituales; otras sintaxis se dejan al formateador Python. */
    fun fillTemplate(template: String, valuesJson: String): String {
        val values = try { Json.parseToJsonElement(valuesJson).jsonObject }
            catch (_: IllegalArgumentException) { return "" }
        fun text(key: String): String {
            val value = values[key] ?: return ""
            if (value is JsonNull) return "None"
            val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException()
            if (!primitive.isString && !primitive.content.matches(Regex("-?[0-9]+"))) throw IllegalArgumentException()
            return primitive.content
        }
        return try {
            val slug = text("slug")
            val season = text("season")
            val episode = text("episode")
            val query = text("query")
            if ((season + episode).any { it.code > 127 }) return ""
            fun padded(key: String, value: String): String {
                val actual = if (values[key] is JsonNull) "" else value
                return if (actual.isNotEmpty() && actual.all { it in '0'..'9' }) actual.padStart(2, '0') else actual
            }
            val replacements = mapOf(
                "slug" to slug, "season" to season, "episode" to episode,
                "season2" to padded("season", season), "episode2" to padded("episode", episode),
                "query" to URLEncoder.encode(query, "UTF-8").replace("%7E", "~").replace("*", "%2A"),
            )
            val out = StringBuilder()
            var index = 0
            while (index < template.length) {
                when (template[index]) {
                    '{' -> {
                        if (index + 1 < template.length && template[index + 1] == '{') { out.append('{'); index += 2 }
                        else {
                            val end = template.indexOf('}', index + 1)
                            if (end < 0) return ""
                            val value = replacements[template.substring(index + 1, end)] ?: return ""
                            out.append(value); index = end + 1
                        }
                    }
                    '}' -> {
                        if (index + 1 >= template.length || template[index + 1] != '}') return ""
                        out.append('}'); index += 2
                    }
                    else -> out.append(template[index++])
                }
            }
            out.toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    /** Consultas más cortas para los buscadores que no indexan años o paréntesis. */
    fun fallbackQueriesJson(query: String, limit: Int): String {
        fun words(value: String) = value.split(PYTHON_WHITESPACE).filter { it.isNotEmpty() }
        val original = words(query).joinToString(" ")
        if (original.isEmpty()) return "[]"
        val cleaned = words(YEAR_NOISE.replace(original, " "))
        val candidates = mutableListOf(original)
        if (cleaned.isNotEmpty()) {
            candidates.add(cleaned.joinToString(" "))
            candidates.add(cleaned.take(4).joinToString(" "))
        }
        val seen = mutableSetOf<String>()
        return JsonArray(candidates.filter { it.isNotEmpty() && seen.add(it.lowercase(Locale.ROOT)) }
            .take(maxOf(1, limit)).map(::JsonPrimitive)).toString()
    }

    /** Planifica las siete pruebas directas, priorizando episodios y fichas. */
    fun directPagePlanJson(slugsJson: String, templatesJson: String, fichaPathsJson: String): String {
        fun strings(input: String): List<String> = Json.parseToJsonElement(input).jsonArray.map {
            val value = it as? JsonPrimitive ?: throw IllegalArgumentException()
            require(value.isString)
            value.content
        }
        return try {
            val slugs = strings(slugsJson).filter { it.isNotEmpty() }.take(3)
            val templates = strings(templatesJson)
            val ordered = (templates.take(3) + templates.takeLast(1) + strings(fichaPathsJson) + templates.drop(3)).distinct()
            val plan = mutableListOf<JsonElement>()
            loop@ for (slug in slugs) for (template in ordered) {
                if (plan.size == 7) break@loop
                plan.add(JsonArray(listOf(JsonPrimitive(slug), JsonPrimitive(template))))
            }
            JsonArray(plan).toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    /** Enlaces del episodio en una ficha, conservando el orden y sin repetir URLs. */
    fun candidateEpisodeLinksJson(html: String, season: String, episode: String): String {
        // Los argumentos vienen de enteros Python. Números no canónicos o muy
        // grandes se dejan al scraper anterior, sin truncar la numeración.
        if (!season.matches(Regex("-?[0-9]{1,100}")) || !episode.matches(Regex("-?[0-9]{1,100}"))) return ""
        val padded = if (episode.length < 2) episode.padStart(2, '0') else episode
        val variants = listOf(
            "${season}x$episode", "${season}x$padded", "temporada/$season/capitulo/$episode",
            "temporada-$season/capitulo-$episode", "/$season/$episode", "/$season/$padded",
            "capitulo-$episode", "capitulo-$padded", "capitulo/$episode", "episodio-$episode",
            "episodio-$padded", "episodio/$episode", "episode-$episode", "temporada-$season-capitulo-$episode",
        )
        val textVariants = listOf("t$season e$episode", "t${season}e$episode", "${season}x$episode", "${season}x$padded")
        val tail = Regex("(?U)(?:[-_/])(0*${Regex.escape(episode)})(?!\\d)")
        val links = linkedSetOf<String>()
        for (match in EPISODE_LINK.findAll(html)) {
            // Python strip incluye también NEL y los separadores U+001C–U+001F.
            val href = match.groupValues[1].trim { it.isWhitespace() || it == '\u0085' || it in '\u001c'..'\u001f' }
            val label = VISIBLE_TAG.replace(match.groupValues[2], " ").split(PYTHON_WHITESPACE)
                .filter { it.isNotEmpty() }.joinToString(" ").lowercase(Locale.ROOT)
            val lowerHref = href.lowercase(Locale.ROOT)
            val haystack = "$lowerHref $label"
            val compact = haystack.replace(" ", "")
            if (variants.any { it in compact || it in haystack } ||
                textVariants.any { it in haystack } || tail.containsMatchIn(lowerHref)) links.add(href)
        }
        return JsonArray(links.map(::JsonPrimitive)).toString()
    }

    private fun slugify(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private companion object {
        val EPISODE_LINK = Regex("""(?U)<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val VISIBLE_TAG = Regex("<[^>]+>")
        val PYTHON_WHITESPACE = Regex("(?U)[\\s\\x1c-\\x1f]+")
        val YEAR_NOISE = Regex("(?U)\\([^)]*\\)|\\b(?:19|20)\\d{2}\\b")
        val TYPE_TOKENS = setOf("movie", "movies", "film", "films", "pelicula", "peliculas", "película", "películas", "series", "serie", "show", "shows", "vod", "tv", "anime", "content")
        val PROVIDERS = setOf("gnula", "cuevana", "pelisplushd", "pelisplus", "repelis", "peliculasflix", "pelisjuanita", "comodin", "animeflv", "jkanime", "monoschinos", "tvenvivo", "deportelibre", "futbolibre", "livelive24", "damitv", "ntv", "streamcorner", "novahd")
        val SMALL_WORDS = setOf("de", "del", "la", "las", "el", "los", "y", "o", "a", "en", "al", "con", "por", "para", "un", "una", "the", "of", "and")
        val IMDB = Regex("^tt\\d{6,9}$", RegexOption.IGNORE_CASE)
        val SEASON_EPISODE = Regex("(?:^|[-_ ])(\\d{1,2})x(\\d{1,4})(?:$|[-_ ])", RegexOption.IGNORE_CASE)
    }
}
