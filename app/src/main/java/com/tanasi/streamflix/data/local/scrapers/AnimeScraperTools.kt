package com.tanasi.streamflix.data.local.scrapers

import androidx.annotation.Keep
import kotlinx.serialization.json.*
import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** Parsing común de AnimeFLV, JkAnime y Monoschinos. Nunca ejecuta JavaScript. */
@Keep
class AnimeScraperTools {
    fun buildStreamsJson(provider: String, display: String, signals: String, userAgent: String): String =
        StreamBuilder.build(provider, display, signals, userAgent)

    /** Extrae iframes ya desescapados; formatos de URL infrecuentes se dejan a Python. */
    fun iframeServersJson(html: String, pageUrl: String, skipHostsJson: String): String {
        val skip = try { Json.parseToJsonElement(skipHostsJson).jsonArray.map { it.jsonPrimitive.content } }
            catch (_: Exception) { return "" }
        val base = try { URI(pageUrl) } catch (_: Exception) { return "" }
        val scheme = base.scheme?.lowercase(Locale.ROOT) ?: return ""
        if (scheme !in setOf("http", "https") || base.scheme != scheme || base.rawAuthority.isNullOrEmpty()) return ""
        val servers = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        for (match in IFRAME.findAll(html)) {
            val raw = match.groupValues[1].trim { it.isWhitespace() || it == '\u0085' || it in '\u001c'..'\u001f' }
            val url = when {
                raw.startsWith("https://") || raw.startsWith("http://") -> raw
                raw.startsWith("//") -> "$scheme:$raw"
                raw.isNotEmpty() && !raw.startsWith("?") && !raw.startsWith("#") -> {
                    // URI.resolve y urljoin difieren con rutas irregulares,
                    // parámetros vacíos y referencias que parecen un esquema.
                    val path = raw.substringBefore('?').substringBefore('#')
                    val basePath = base.rawPath.orEmpty()
                    if (path.split('/').any { it == "." || it == ".." } || "//" in path || ':' in path.substringBefore('/') ||
                        "//" in basePath || ';' in basePath || basePath.split('/').any { it == "." || it == ".." }) return ""
                    try { base.resolve(URI(raw)).toString() } catch (_: Exception) { return "" }
                }
                else -> return ""
            }
            // El URL se mantiene intacto (incluyendo query firmada), pero se
            // comprueba que URI pueda tratarlo y que el nombre sea ASCII.
            val parsed = try { URI(url) } catch (_: Exception) { return "" }
            if (parsed.rawQuery == "" || parsed.rawFragment == "") return ""
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            if (skip.any { it in url } || url in seen) continue
            val host = url.substringAfterLast("//").substringBefore('/')
            if (host.any { it.code > 127 }) return ""
            val words = host.split(Regex("(?U)[-_\\s\\x1c-\\x1f]+"))
                .filter { it.isNotEmpty() }.map { word ->
                    when {
                        word.all { it in '0'..'9' } -> word
                        word.lowercase(Locale.ROOT) in ACRONYMS -> word.uppercase(Locale.ROOT)
                        else -> word.substring(0, 1).uppercase(Locale.ROOT) + word.substring(1)
                    }
                }
            val name = words.joinToString(" ").ifEmpty { host }.ifEmpty { "Servidor" }
            seen.add(url)
            servers += buildJsonObject { put("name", name); put("url", url) }
        }
        return JsonArray(servers).toString()
    }

    /** El resultado vacío indica que el parser legado debe encargarse del URL inválido. */
    fun parseIdJson(contentId: String, provider: String): String {
        val raw = contentId.trim()
        var slug = ""
        var url = ""
        var season: Int? = null
        var episode: Int? = null
        var explicit = false
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val path = try { URI(raw).rawPath.orEmpty().trim('/') } catch (_: Exception) { return "" }
            url = raw
            var candidate = path.substringAfterLast('/')
            candidate = Regex("-episodio-\\d+$", RegexOption.IGNORE_CASE).replace(candidate, "")
            candidate = EPISODE.replace(candidate, "")
            candidate = Regex("-\\d{1,5}$").replace(candidate.trim('-'), "")
            slug = slugify(candidate)
            val match = EPISODE.find(path) ?: TRAILING_NUMBER.find(path)
            if (match != null) { episode = match.groupValues[1].toInt(); explicit = true }
        } else if (raw.isNotEmpty()) {
            var text = raw
            for (prefix in listOf("anime:", "tv:", "stremio:")) {
                if (text.startsWith(prefix, ignoreCase = true)) text = text.substring(prefix.length)
            }
            if (text.startsWith("$provider:", ignoreCase = true)) text = text.substring(provider.length + 1)
            else if (listOf("animeflv:", "jkanime:", "monoschinos:").any { text.startsWith(it, ignoreCase = true) }) text = text.substringAfter(':')
            val parts = text.split(':').filter { it.isNotEmpty() }.toMutableList()
            if (parts.isNotEmpty()) {
                val numbers = mutableListOf<Int>()
                while (parts.size > 1 && parts.last().matches(Regex("\\d{1,5}")) && numbers.size < 2) {
                    numbers.add(0, parts.removeAt(parts.lastIndex).toInt())
                }
                slug = slugify(parts.joinToString(":"))
                if (numbers.size == 1) { episode = numbers[0]; explicit = true }
                if (numbers.size == 2) { season = numbers[0]; episode = numbers[1]; explicit = true }
                if (slug.isEmpty()) slug = slugify(text)
            }
        }
        return buildJsonObject {
            put("raw", raw); put("slug", slug); put("url", url); put("explicit_episode", explicit)
            put("season", season?.let { JsonPrimitive(it) } ?: JsonNull)
            put("episode", episode?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()
    }

    /** Completa temporada y episodio sólo si el ID no contenía un episodio. */
    fun applyEpisodeJson(refJson: String, payloadJson: String): String {
        val ref = Json.parseToJsonElement(refJson).jsonObject
        val payload = Json.parseToJsonElement(payloadJson).jsonObject
        if ((ref["explicit_episode"] as? JsonPrimitive)?.booleanOrNull == true) return ref.toString()
        // Python admite enteros arbitrarios y formatos adicionales como 1_000;
        // dejar esos casos al parser anterior para no truncar episodios.
        for (key in listOf("episode", "season")) {
            val item = payload[key] as? JsonPrimitive ?: continue
            val text = item.content.trim()
            if ('_' in text || text.any { it.code > 127 } || text.count { it in '0'..'9' } > 18) return ""
            if (!item.isString && ('.' in text || text.contains('e', true))) {
                val number = text.toDoubleOrNull()
                if (number != null && (!number.isFinite() || number > Long.MAX_VALUE || number < Long.MIN_VALUE)) return ""
            }
        }
        val result = ref.toMutableMap()
        try {
            val episode = payload["episode"]
            if (episode != null && episode !is JsonNull && episode.jsonPrimitive.content.trim().isNotEmpty()) {
                result["episode"] = JsonPrimitive(pythonInt(episode.jsonPrimitive))
                result["explicit_episode"] = JsonPrimitive(true)
            }
            val season = payload["season"]
            if (season != null && season !is JsonNull && season.jsonPrimitive.content.trim().isNotEmpty()) {
                result["season"] = JsonPrimitive(pythonInt(season.jsonPrimitive))
            }
        } catch (_: IllegalArgumentException) {
            // Coincide con la conversión parcial del parser anterior.
        }
        return JsonObject(result).toString()
    }

    private fun pythonInt(value: JsonPrimitive): Long {
        val text = value.content.trim()
        if (!value.isString) {
            if (text == "true") return 1
            if (text == "false") return 0
            if (text.contains('.') || text.contains('e', true)) {
                val number = text.toDouble()
                require(number.isFinite() && number >= Long.MIN_VALUE && number <= Long.MAX_VALUE)
                return number.toLong()
            }
        }
        return text.toLong()
    }

    /** Decodifica embeds base64 sin utilizar APIs Android ausentes en API 24. */
    fun decodeMaybeBase64(value: String): String? {
        val text = value.trim()
        fun isUrl(s: String) = s.startsWith("https://") || s.startsWith("http://") || s.startsWith("//")
        if (isUrl(text)) return text
        // Para formatos no canónicos se mantiene el comportamiento tolerante
        // de base64.b64decode de Python, en lugar de rechazar un servidor válido.
        if (!text.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val padded = text + "=".repeat((4 - text.length % 4) % 4)
        val data = padded.takeWhile { it != '=' }
        val remainder = data.length % 4
        if (remainder == 1 || (remainder > 0 && padded.length - data.length < 4 - remainder)) return null
        val result = ArrayList<Byte>()
        var bits = 0; var accumulator = 0
        for (c in data) {
            accumulator = (accumulator shl 6) or alphabet.indexOf(c)
            bits += 6
            if (bits >= 8) { bits -= 8; result.add((accumulator shr bits).toByte()); accumulator = accumulator and ((1 shl bits) - 1) }
        }
        val decoded = result.toByteArray().toString(Charsets.UTF_8).trim()
        return if (isUrl(decoded)) decoded else text
    }

    /** Devuelve JSON; la cadena vacía pide usar el parser anterior. No evalúa código. */
    fun literalJson(html: String, name: String): String {
        if (html.isEmpty() || name.isEmpty()) return ""
        val pattern = Regex("(?:var\\s+|let\\s+|const\\s+|window\\.)?\\b${Regex.escape(name)}\\s*=\\s*", RegexOption.IGNORE_CASE)
        for (match in pattern.findAll(html)) {
            val startAt = match.range.last + 1
            val array = html.indexOf('[', startAt)
            val obj = html.indexOf('{', startAt)
            val start = listOf(array, obj).filter { it >= 0 }.minOrNull() ?: continue
            val open = html[start]
            val raw = balanced(html, start, open, if (open == '[') ']' else '}') ?: continue
            // Si un literal no es JSON reconocible, dejar que el parser
            // anterior compruebe esta asignación antes de pasar a la siguiente.
            val parsed = parseLiteral(raw) ?: return ""
            if (parsed !is JsonNull) return parsed.toString()
        }
        return ""
    }

    private fun balanced(text: String, start: Int, open: Char, close: Char): String? {
        var depth = 0; var quote: Char? = null; var index = start
        while (index < text.length) {
            val c = text[index]
            if (quote != null) {
                if (c == '\\') { index += 2; continue }
                if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                open -> depth++
                close -> { depth--; if (depth == 0) return text.substring(start, index + 1) }
            }
            index++
        }
        return null
    }

    private fun quoteKeys(text: String): String? {
        val out = StringBuilder(); var index = 0; var quote: Char? = null
        while (index < text.length) {
            val c = text[index]
            if (quote != null) {
                if (c == '\\') {
                    out.append(text.substring(index, minOf(index + 2, text.length)))
                    index += 2; continue
                }
                if (c == quote) { out.append('"'); quote = null; index++; continue }
                when (c) { '"' -> out.append("\\\""); '\n' -> out.append("\\n"); '\r' -> out.append("\\r"); '\t' -> out.append("\\t"); else -> out.append(c) }
                index++; continue
            }
            if (c == '\'' || c == '"') { quote = c; out.append('"'); index++; continue }
            if (c.isLetter() || c == '_' || c == '$') {
                var end = index
                while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$')) end++
                var probe = end
                while (probe < text.length && text[probe].isWhitespace()) probe++
                val word = text.substring(index, end)
                if (probe < text.length && text[probe] == ':' && (probe + 1 >= text.length || text[probe + 1] != ':')) {
                    out.append('"').append(word).append('"')
                } else out.append(word)
                index = end; continue
            }
            out.append(c); index++
        }
        return if (quote == null) out.toString() else null
    }

    private fun parseLiteral(raw: String): JsonElement? {
        val attempts = mutableListOf(raw)
        quoteKeys(raw)?.takeIf { it.isNotEmpty() }?.let { attempts.add(it) }
        attempts.addAll(attempts.toList().map { Regex(",(\\s*[}\\]])").replace(it, "$1") })
        for (candidate in attempts) {
            // kotlinx.serialization acepta algunos valores sin comillas aun en
            // modo no lenient; json.loads no. Validar antes de consumirlos.
            if (!StrictJson(candidate).valid()) continue
            val parsed = runCatching { Json.parseToJsonElement(candidate) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /** Reconoce sólo JSON, sin evaluar expresiones ni aceptar palabras sueltas. */
    internal class StrictJson(private val input: String) {
        private var pos = 0
        fun valid(): Boolean = value(0) && whitespace().let { pos == input.length }
        private fun whitespace() { while (pos < input.length && input[pos] in " \t\n\r") pos++ }
        private fun consume(c: Char): Boolean {
            whitespace()
            if (pos < input.length && input[pos] == c) { pos++; return true }
            return false
        }
        private fun value(depth: Int): Boolean {
            if (depth > 256) return false
            whitespace()
            if (pos >= input.length) return false
            return when (input[pos]) {
                '"' -> string()
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                't' -> keyword("true")
                'f' -> keyword("false")
                'n' -> keyword("null")
                '-', in '0'..'9' -> number()
                else -> false
            }
        }
        private fun keyword(word: String): Boolean {
            if (!input.startsWith(word, pos)) return false
            pos += word.length
            return true
        }
        private fun string(): Boolean {
            if (!consume('"')) return false
            while (pos < input.length) {
                val c = input[pos++]
                if (c == '"') return true
                if (c.code < 0x20) return false
                if (c != '\\') continue
                if (pos >= input.length) return false
                val escape = input[pos++]
                if (escape == 'u') {
                    if (pos + 4 > input.length || input.substring(pos, pos + 4).any { it !in "0123456789abcdefABCDEF" }) return false
                    pos += 4
                } else if (escape !in "\"\\/bfnrt") return false
            }
            return false
        }
        private fun number(): Boolean {
            if (input[pos] == '-') pos++
            if (pos >= input.length) return false
            if (input[pos] == '0') pos++
            else {
                if (input[pos] !in '1'..'9') return false
                while (pos < input.length && input[pos] in '0'..'9') pos++
            }
            if (pos < input.length && input[pos] == '.') {
                pos++
                val begin = pos
                while (pos < input.length && input[pos] in '0'..'9') pos++
                if (begin == pos) return false
            }
            if (pos < input.length && input[pos] in "eE") {
                pos++
                if (pos < input.length && input[pos] in "+-") pos++
                val begin = pos
                while (pos < input.length && input[pos] in '0'..'9') pos++
                if (begin == pos) return false
            }
            return true
        }
        private fun arrayValue(depth: Int): Boolean {
            if (!consume('[')) return false
            if (consume(']')) return true
            do { if (!value(depth)) return false; if (consume(']')) return true } while (consume(','))
            return false
        }
        private fun objectValue(depth: Int): Boolean {
            if (!consume('{')) return false
            if (consume('}')) return true
            do { if (!string() || !consume(':') || !value(depth)) return false; if (consume('}')) return true } while (consume(','))
            return false
        }
    }

    /** Comparador de resultados usado tanto por anime como por cine. */
    fun matchTokensJson(value: String): String = JsonArray(matchTokens(value).map { JsonPrimitive(it) }).toString()

    fun matchScore(query: String, title: String, slug: String): Int {
        val wanted = matchTokens(query)
        if (wanted.isEmpty()) return 0
        val compact = wanted.joinToString("")
        var best = 0
        for (tokens in listOf(matchTokens(title), matchTokens(slug))) {
            if (tokens.isEmpty()) continue
            if (tokens == wanted) return 100
            if (tokens.joinToString("") == compact) { best = maxOf(best, 98); continue }
            if (tokens.size >= wanted.size && tokens.take(wanted.size) == wanted) {
                best = maxOf(best, if (tokens.size == wanted.size) 84 else 80); continue
            }
            val ratio = wanted.toSet().intersect(tokens.toSet()).size.toDouble() / wanted.toSet().size
            if (tokens.first() == wanted.first() && ratio >= 0.5) {
                best = maxOf(best, 55 + (20 * ratio).toInt()); continue
            }
            if (compact.length >= 5 && compact in tokens.joinToString("")) best = maxOf(best, 50)
        }
        return best
    }

    fun bestMatchJson(query: String, hitsJson: String): String {
        val hits = Json.parseToJsonElement(hitsJson).jsonArray
        var bestScore = 0; var bestExtra = Int.MAX_VALUE; var bestIndex = -1
        val wantedSize = matchTokens(query).size
        hits.forEachIndexed { index, item ->
            val hit = item.jsonObject
            val title = (hit["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val slug = (hit["slug"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val score = matchScore(query, title, slug)
            val extra = maxOf(0, matchTokens(title).size - wantedSize)
            if (score > 0 && (score > bestScore || (score == bestScore && extra < bestExtra))) {
                bestScore = score; bestExtra = extra; bestIndex = index
            }
        }
        return buildJsonObject { put("score", bestScore); put("index", bestIndex) }.toString()
    }

    private fun matchTokens(value: String): List<String> {
        val tokens = slugify(value).split('-').filter { it.isNotEmpty() }
        val filtered = tokens.filter { it !in MATCH_NOISE && !it.matches(Regex("(?:19|20)\\d{2}")) }
        return filtered.ifEmpty { tokens }
    }

    private fun slugify(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private companion object {
        val IFRAME = Regex("""(?U)<iframe[^>]+?src[\s\x1c-\x1f]*=[\s\x1c-\x1f]*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val ACRONYMS = setOf("espn", "tudn", "tyc", "tnt", "fox", "dazn", "mls", "nba", "nfl", "ufc", "wwe", "usa", "uk", "hd", "tv", "nhl", "mlb", "dsports", "vtv", "dsp", "canal", "premiere", "op", "opt", "sd", "fhd")
        val MATCH_NOISE = setOf("latino", "lat", "castellano", "esp", "espanol", "español", "subtitulado",
            "sub", "subs", "subbed", "dubbed", "dual", "audio", "hd", "fhd", "uhd", "4k", "1080p",
            "720p", "480p", "360p", "cam", "ts", "online", "gratis", "completa", "completo", "ver",
            "pelicula", "peliculas", "movie", "film", "mega", "torrent", "descargar", "bluray", "bdrip",
            "webrip", "hdtv")
        val EPISODE = Regex("(?:episodio|episode|cap(?:itulo|ítulo)?|ep)[-_. /]*(\\d{1,5})(?!\\d)", RegexOption.IGNORE_CASE)
        val TRAILING_NUMBER = Regex("[-_/](\\d{1,5})/?$")
    }
}
