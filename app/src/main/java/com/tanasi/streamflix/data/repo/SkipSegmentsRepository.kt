package com.tanasi.streamflix.data.repo

import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.SkipSegment
import com.tanasi.streamflix.data.model.SkipSegmentParser
import com.tanasi.streamflix.util.AppLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * De dónde salen las marcas de tiempo de "saltar intro".
 *
 * Hay dos bases públicas que publican lo mismo y se complementan:
 *
 *  - **TheIntroDB** (`api.theintrodb.org/v4`) es la más completa: identifica por
 *    TVDB, TMDB o IMDb, y su `duration_ms` permite elegir la versión del
 *    estreno (teatral, extendida…). Es la primera.
 *  - **IntroDB** (`api.introdb.app`) sólo entiende **IMDb**, pero es la que
 *    responde para muchas series que la otra no tiene.
 *
 * Se consultan en ese orden y se para en la primera que devuelva algo. Hacerlo
 * al revés o en paralelo multiplicaría el tráfico a servicios gratuitos sin
 * ganar nada: cuando la primera tiene el dato, la segunda no aporta.
 */
class SkipSegmentsRepository(
    private val http: (String) -> String? = ::getOrNull,
) {

    /**
     * Pide los tramos de una reproducción.
     *
     * @param durationMs duración conocida del vídeo. Con `0` se sigue
     *   consultando igual, pero los tramos que terminan en "hasta el final" se
     *   descartan (no se puede saltar a un sitio que no se conoce).
     * @return lista vacía si no hay dato, si el título no se puede identificar
     *   o si las dos bases fallan. Nunca lanza: el reproductor no debe romperse
     *   porque un servicio de terceros esté caído.
     */
    fun segmentsFor(request: PlaybackRequest, durationMs: Long): List<SkipSegment> {
        val imdbId = imdbIdOf(request.id) ?: return emptyList()
        val coordinates = Coordinates.of(imdbId, request.id)
        if (coordinates == null) return emptyList()

        cache[coordinates.cacheKey]?.let { return it }

        val result = fetch(coordinates.toTheIntroDbUrl(durationMs), durationMs)
            ?.takeIf { it.isNotEmpty() }
            ?: fetch(coordinates.toIntroDbUrl(), durationMs)
            ?: emptyList()

        /* Se cachea también el vacío: una serie sin marcas se pediría en cada
         * capítulo y en cada cambio de fuente. */
        cache[coordinates.cacheKey] = result
        return result
    }

    private fun fetch(url: String, durationMs: Long): List<SkipSegment>? {
        val body = http(url) ?: return null
        /* La duración se pasa al parser porque los créditos llegan con
         * `end_ms: null` ("hasta el final") y sin ella ese tramo no se puede
         * ofrecer. Si todavía no se conoce, el tramo se cae y el resto se
         * conserva. */
        val segments = SkipSegmentParser.parse(body, durationMs)
        AppLog.d("skipsegments", "tramos leídos: ${segments.size}")
        return segments
    }

    /** La clave del caché incluye temporada y episodio: cada capítulo tiene las suyas. */
    private data class Coordinates(
        val imdbId: String,
        val season: Int?,
        val episode: Int?,
    ) {
        val cacheKey: String
            get() = if (season == null) "movie:$imdbId" else "tv:$imdbId:$season:$episode"

        fun toTheIntroDbUrl(durationMs: Long): String {
            val params = buildString {
                append("imdb_id=$imdbId")
                if (season != null && episode != null) append("&season=$season&episode=$episode")
                if (durationMs > 0L) append("&duration_ms=$durationMs")
            }
            return "https://api.theintrodb.org/v4/media?$params"
        }

        fun toIntroDbUrl(): String {
            val params = buildString {
                append("imdb_id=$imdbId")
                if (season != null && episode != null) {
                    append("&season=$season&episode=$episode")
                } else {
                    append("&is_movie=true")
                }
            }
            return "https://api.introdb.app/segments?$params"
        }

        companion object {
            /**
             * De los ids del reproductor a coordenadas.
             *
             * El addon usa dos formas: `tt0111161` para película y
             * `tt0903747:1:1` para episodio. Cualquier otra cosa (un id de
             * proveedor como `gnula:...`) no se puede consultar en ninguna de
             * las dos bases, así que se descarta antes de gastar una petición.
             */
            fun of(imdbId: String, requestId: String): Coordinates? {
                val parts = requestId.split(':')
                return when {
                    parts.size == 1 -> Coordinates(imdbId, null, null)
                    parts.size == 3 -> Coordinates(
                        imdbId,
                        parts[1].toIntOrNull() ?: return null,
                        parts[2].toIntOrNull() ?: return null,
                    )
                    else -> null
                }
            }
        }
    }

    companion object {
        /**
         * Caché por proceso. Las marcas de un capítulo no cambian durante una
         * sesión, así que no hace falta caducarlas; el `ConcurrentHashMap`
         * evita que dos reproducciones simultáneas pidan lo mismo dos veces.
         */
        private val cache = ConcurrentHashMap<String, List<SkipSegment>>()

        /** Sólo para pruebas: deja el caché limpio entre casos. */
        internal fun clearCache() = cache.clear()

        /**
         * Extrae el IMDb id de un id del reproductor.
         *
         * Acepta `tt1234567` y `tt1234567:1:2`. Se valida la forma (`tt` + 7 u
         * 8 dígitos) porque es lo único que ambas bases aceptan; un id que no
         * la cumpla devolvería 400 y gastaría cuota.
         */
        fun imdbIdOf(requestId: String): String? {
            val head = requestId.substringBefore(':')
            return head.takeIf { it.matches(IMDB_PATTERN) }
        }

        private val IMDB_PATTERN = Regex("^tt\\d{7,8}$")
    }
}

/**
 * GET sencillo con tiempo máximo.
 *
 * `HttpURLConnection` y no OkHttp a propósito: el proyecto ya usa
 * `HttpURLConnection` en el cliente de addons y en el transporte de los
 * scrapers, y añadir una dependencia por dos peticiones de texto no se paga.
 *
 * Devuelve `null` en cualquier fallo (red, timeout, 404, 429, cuerpo vacío):
 * quien llama prefiere "no hay marcas" a una excepción en mitad de la
 * reproducción.
 */
private fun getOrNull(url: String): String? = runCatching {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "Streamflix/1.1 (+Android TV)")
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

private const val TIMEOUT_MS = 4_000
