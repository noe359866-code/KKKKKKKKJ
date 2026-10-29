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
 * Un título del catálogo: lo que necesita una tarjeta, el hero y la hoja de
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
    /** Póster 2:3 (TMDB o el proveedor). Si falta o falla, se usa [art]. */
    val poster: String? = null,
    /** Backdrop 16:9 para el hero. Si falta, se usa [art]. */
    val backdrop: String? = null,
    /**
     * Clave del arte local de respaldo: `movies`, `series`, `anime` o `live`.
     * Se resuelve en `ui/components/Artwork.kt`, porque un JSON no puede
     * contener `R.drawable.*`.
     */
    val art: String? = null,
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
    /** 0-100 para las tarjetas de "Continuar viendo". */
    @SerialName("progressPercent") val progressPercent: Int? = null,
) {
    val mediaType: MediaType get() = MediaType.fromWire(type) ?: MediaType.Movie

    val isLive: Boolean get() = live || mediaType == MediaType.Live

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

/** Una fila del Home: Continuar viendo, Películas populares, Anime… */
@Serializable
data class CatalogRow(
    val id: String,
    val title: String,
    /** `poster` (2:3) o `landscape` (16:9). */
    val aspect: String = "poster",
    val items: List<MediaEntry> = emptyList(),
) {
    val rowAspect: RowAspect
        get() = if (aspect.equals("landscape", ignoreCase = true)) RowAspect.Landscape else RowAspect.Poster
}

/** Documento completo del catálogo semilla / remoto. */
@Serializable
data class CatalogDocument(
    val version: Int = 1,
    val rows: List<CatalogRow> = emptyList(),
)

/**
 * Catálogo remoto al estilo Stremio (`/catalog/{type}/{id}.json`), por si el
 * addon empieza a exponer catalogs en el manifest.
 */
@Serializable
data class RemoteCatalogResponse(
    @SerialName("metas") val metas: List<RemoteMeta> = emptyList(),
)

@Serializable
data class RemoteMeta(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("name") val name: String,
    @SerialName("poster") val poster: String? = null,
    @SerialName("releaseInfo") val releaseInfo: String? = null,
) {
    fun toEntry(): MediaEntry = MediaEntry(
        id = id,
        type = type,
        title = name,
        poster = poster,
        year = releaseInfo?.take(4)?.toIntOrNull(),
    )
}

/**
 * Traduce una tarjeta al intent del reproductor.
 *
 * @param startIndex fuente elegida en la hoja de detalles (0 = la de mayor
 *   prioridad). El failover continúa desde ahí hacia abajo.
 */
fun MediaEntry.playbackRequest(startIndex: Int = 0) = PlaybackRequest(
    id = id,
    type = type,
    title = title,
    isLive = isLive,
    startIndex = startIndex,
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
)
