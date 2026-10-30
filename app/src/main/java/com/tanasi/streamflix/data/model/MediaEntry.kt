package com.tanasi.streamflix.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tipos que entiende el addon (`config.addon.types` en el servidor).
 * El valor textual es el que viaja en la URL: `/stream/{type}/{id}.json`.
 */
enum class MediaType(val wire: String) {
    Movie("movie"),
    Series("series"),
    Anime("anime"),
    Live("tv");

    companion object {
        fun fromWire(value: String): MediaType? =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/** Proporción de las tarjetas de una fila. */
enum class RowAspect {
    /** 2:3 — pósters de películas, series y anime. */
    Poster,

    /** 16:9 — canales en directo, deportes y "continuar viendo". */
    Landscape,
}

/**
 * Un título del catálogo local/remoto: lo que necesita una tarjeta, el hero y la hoja de
 * detalles.
 *
 * [id] es el identificador completo que espera el backend, con el proveedor
 * incluido (`gnula:street-fighter`, `monoschinos:one-piece:1`,
 * `tv:futbolibre:espn-1`) y, en series, la temporada y el episodio cuando toca
 * (`pelisplushd:x:1:2`).
 *
 * El modelo es deliberadamente "gordo": todos los campos de presentación
 * (sinopsis, géneros, calidad, progreso) viajan en el mismo JSON del catálogo,
 * así que la UI no necesita una segunda petición para pintar la hoja de
 * detalles.
 */
@Serializable
data class MediaEntry(
    val id: String,
    val type: String,
    val title: String,
    /** Póster 2:3 (Cinemeta o el proveedor). Si falta o falla, se genera uno. */
    val poster: String? = null,
    /** Backdrop 16:9 para el hero. Si falta, se usa el póster desenfocado. */
    val backdrop: String? = null,
    val year: Int? = null,
    val subtitle: String? = null,
    /** Sólo informativo: de qué proveedor salió (lo pinta la tarjeta). */
    val provider: String? = null,
    val synopsis: String? = null,
    val genres: List<String> = emptyList(),
    /** Nota de 0 a 10 (TMDB o la propia web). */
    val rating: Double? = null,
    /** Calidad anunciada: `4K`, `1080p`, `720p`, `CAM`… */
    val quality: String? = null,
    val runtime: String? = null,
    /** Marca de "tendencia": pinta la insignia de top 10. */
    @SerialName("trending") val trending: Boolean = false,
    /** Canal en emisión continua. */
    @SerialName("live") val live: Boolean = false,
    /**
     * Estado explícito del directo cuando el tipo no basta (fila `deportes`):
     * un partido de hoy que aún no ha empezado es `tv` pero no está al aire.
     * `null` = dedúcelo del tipo, como el resto de las filas.
     */
    @SerialName("onAir") val onAir: Boolean? = null,
    /** Equipos del partido (sólo deportes). El título ya es "A vs B". */
    @SerialName("home") val homeTeam: String? = null,
    @SerialName("away") val awayTeam: String? = null,
    /** Competición: "Liga 1", "MLB", "NBA"… Sólo deportes. */
    @SerialName("league") val league: String? = null,
    /** Hora de comienzo del partido ("19:00"). Sólo deportes. */
    @SerialName("startTime") val startTime: String? = null,
    /** 0-100 para las tarjetas de "Continuar viendo". */
    @SerialName("progressPercent") val progressPercent: Int? = null,
    /**
     * Milisegundo en el que se quedó este título. Sólo lo rellena "Seguir
     * viendo" (`WatchProgress.toEntry`): al pulsar esa tarjeta el reproductor
     * arranca ahí en vez de desde el principio. No viaja en el JSON.
     */
    @kotlinx.serialization.Transient val resumePositionMs: Long = 0L,
) {
    val mediaType: MediaType get() = MediaType.fromWire(type) ?: MediaType.Movie

    val isLive: Boolean get() = onAir ?: (live || mediaType == MediaType.Live)

    /** Fila de deportes con dos equipos: la tarjeta se pinta como marcador. */
    val isMatch: Boolean get() = !homeTeam.isNullOrBlank() && !awayTeam.isNullOrBlank()

    /** "Acción · Aventura · 2026 · 2 h 08 min" (sin huecos). */
    val metaLine: String
        get() = buildList {
            addAll(genres)
            year?.let { add(it.toString()) }
            runtime?.let { add(it) }
            if (isEmpty()) subtitle?.let { add(it) }
        }.joinToString(" · ")

    val ratingText: String? get() = rating?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }
}

/**
 * Una fila del Home, ya resuelta: Películas populares, Netflix, Anime…
 *
 * No se serializa: CatalogRepository la construye con los mismos DTOs para el
 * motor embebido y el backend opcional.
 */
data class CatalogRow(
    val id: String,
    val title: String,
    /** `poster` (2:3) o `landscape` (16:9). */
    val aspect: String = "poster",
    val items: List<MediaEntry> = emptyList(),
    /**
     * Cómo se pinta la fila. `cards` (por defecto) usa las tarjetas de arte;
     * `sports` pinta marcadores: liga, equipos, hora y EN VIVO.
     */
    val layout: String = LAYOUT_CARDS,
) {
    val rowAspect: RowAspect
        get() = if (aspect.equals("landscape", ignoreCase = true)) RowAspect.Landscape else RowAspect.Poster

    val isSports: Boolean get() = layout.equals(LAYOUT_SPORTS, ignoreCase = true)

    companion object {
        const val LAYOUT_CARDS = "cards"
        const val LAYOUT_SPORTS = "sports"
    }
}

/**
 * Traduce una tarjeta al intent del reproductor.
 *
 * @param startIndex fuente elegida en la hoja de detalles (0 = la de mayor
 *   prioridad). El failover continúa desde ahí hacia abajo.
 */
fun MediaEntry.playbackRequest(
    startIndex: Int = 0,
    /**
     * Parrilla de la fila de la que salió la tarjeta (ver
     * [toChannelQueue]). Sólo se usa para zapear con `CH+`/`CH−` dentro del
     * reproductor; en películas y series va vacía.
     */
    queue: List<PlaybackChannel> = emptyList(),
) = PlaybackRequest(
    id = id,
    type = type,
    title = title,
    isLive = isLive,
    startIndex = startIndex,
    poster = poster,
    backdrop = backdrop,
    startPositionMs = resumePositionMs,
    queue = queue,
)

/** Lo que el reproductor necesita saber para abrir un título. */
data class PlaybackRequest(
    val id: String,
    val type: String,
    val title: String,
    val isLive: Boolean = false,
    /**
     * Fuente con la que arrancar, elegida en la hoja de detalles
     * (`0` = la de mayor prioridad; el failover sigue desde ahí hacia abajo).
     */
    val startIndex: Int = 0,
    /**
     * Arte del título. No hace falta para reproducir; viaja para poder pintar
     * la tarjeta de "Seguir viendo" sin volver a pedir el catálogo.
     */
    val poster: String? = null,
    val backdrop: String? = null,
    /** Posición de arranque: 0 salvo que venga de "Seguir viendo". */
    val startPositionMs: Long = 0L,
    /**
     * Parrilla de canales de la que salió este título, en el orden en que el
     * usuario la vio. Es lo que permite `CH+`/`CH−` y la guía sin volver al
     * Home; vacía en películas, series y cuando se abre desde fuera.
     *
     * El canal **actual** se localiza aquí por `id`, no por posición: si el
     * catálogo se reordena entre abrir y zapear, seguimos apuntando al mismo
     * canal.
     */
    val queue: List<PlaybackChannel> = emptyList(),
)

/** Tanto :temporada:episodio como los ids de anime proveedor:slug:episodio. */
fun hasEpisodeNumber(type: String, id: String): Boolean =
    Regex(":\\d+:\\d+$").containsMatchIn(id) ||
        (type == "anime" && Regex("^(animeflv|jkanime|monoschinos):[^:]+:\\d+$").matches(id))
