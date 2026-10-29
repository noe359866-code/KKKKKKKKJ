package com.tanasi.streamflix.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs compartidos por el motor Python embebido y el addon Stremio opcional.
 * La fuente de datos decide las filas y la forma de sus tarjetas; la UI no
 * necesita saber si proceden del dispositivo o del backend.
 */

/** Respuesta de `GET /catalogs.json` (y, como respaldo, `manifest.catalogs`). */
@Serializable
data class CatalogsResponse(
    @SerialName("catalogs") val catalogs: List<CatalogDescriptor> = emptyList(),
)

/**
 * Un catálogo publicado por el addon.
 *
 * @param type tipo Stremio publicado (`movie`, `series`, `anime`, `tv`).
 * @param id id del catálogo en la URL; junto a [type] forma la clave.
 * @param posterShape `poster` (2:3) o `landscape` (16:9).
 * @param feed ruta relativa que devolvió el servidor (`/catalog/tv/live.json`).
 */
@Serializable
data class CatalogDescriptor(
    @SerialName("type") val type: String,
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = id,
    @SerialName("posterShape") val posterShape: String = "poster",
    @SerialName("order") val order: Int = 100,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("feed") val feed: String? = null,
    /**
     * `embedded` | `cinemeta` | `local` | `remote`. Las filas `remote` vienen de un addon de
     * terceros republicado por nuestro servidor (Netflix, Disney+…).
     */
    @SerialName("source") val source: String? = null,
    /** Nombre del addon de origen cuando [source] es `remote`. */
    @SerialName("provider") val provider: String? = null,
) {
    /** Forma de las tarjetas, decidida por la fuente de datos. */
    val rowAspect: RowAspect
        get() = if (posterShape.equals("landscape", ignoreCase = true)) RowAspect.Landscape else RowAspect.Poster

    val mediaType: MediaType get() = MediaType.fromWire(type) ?: MediaType.Movie

    /**
     * Id de fila estable y sin colisiones entre catálogos.
     * El hero indexa por `fila/título`, así que dos filas con el mismo id
     * romperían la etiqueta ("TV en Vivo · 3 de 9").
     */
    val rowId: String get() = "remote:$type/$id"

}

/**
 * Respuesta de `GET /catalog/{type}/{id}.json`.
 *
 * @param degraded `true` cuando el servidor no pudo hablar con Cinemeta y
 *   devolvió una lista vacía a propósito. La app lo trata como "fila vacía",
 *   no como error: el resto del Home sigue funcionando.
 */
@Serializable
data class RemoteCatalogResponse(
    @SerialName("metas") val metas: List<RemoteMeta> = emptyList(),
    @SerialName("catalog") val catalog: CatalogDescriptor? = null,
    @SerialName("degraded") val degraded: Boolean = false,
)

/** Respuesta de `GET /meta/{type}/{id}.json`. */
@Serializable
data class MetaResponse(
    @SerialName("meta") val meta: RemoteMeta? = null,
)

/**
 * Una meta Stremio ya normalizada por el servidor.
 *
 * Los campos derivados ([year], [rating]) vienen calculados porque el servidor
 * ya parsea los formatos sucios de Cinemeta (`"2019–2023"`, `"8.8"`, `"N/A"`).
 */
@Serializable
data class RemoteMeta(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String = "movie",
    @SerialName("name") val name: String = "",
    @SerialName("poster") val poster: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("logo") val logo: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("releaseInfo") val releaseInfo: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("rating") val rating: Double? = null,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("runtime") val runtime: String? = null,
    @SerialName("posterShape") val posterShape: String? = null,
    /**
     * Tipo real en el upstream cuando no coincide con el publicado
     * (`anime` se sirve desde `series` de Cinemeta).
     */
    @SerialName("sourceType") val sourceType: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    /**
     * Nombre del catálogo de origen cuando la meta viene de un addon de
     * terceros (`"Streaming Catalogs"`). El servidor lo añade sólo en esas
     * filas; las de Cinemeta se reconocen por el id `tt…`.
     */
    @SerialName("sourceName") val sourceName: String? = null,
    @SerialName("videos") val videos: List<RemoteVideo> = emptyList(),
) {
    val mediaType: MediaType get() = MediaType.fromWire(type) ?: MediaType.Movie

    val isLive: Boolean get() = mediaType == MediaType.Live

    /**
     * Convierte la meta en la tarjeta que pinta el Home.
     *
     * @param descriptor catálogo del que salió: presta la etiqueta de origen.
     *   Las metas traen póster remoto; si la red falla al cargarlo, la tarjeta
     *   usa el arte generado a partir del título.
     */
    fun toEntry(descriptor: CatalogDescriptor? = null): MediaEntry = MediaEntry(
        id = id,
        /* Se conserva el tipo PUBLICADO: es el que acepta `/stream/{type}/{id}`. */
        type = type,
        title = name.ifBlank { id },
        poster = normalizeArtworkUrl(poster),
        backdrop = normalizeArtworkUrl(background),
        year = year ?: releaseInfo?.let { info -> parseYear(info) },
        subtitle = releaseInfo,
        synopsis = description,
        genres = genres,
        rating = rating,
        runtime = runtime,
        live = mediaType == MediaType.Live,
        provider = providerLabel(descriptor),
    )

    /**
     * Etiqueta de origen de la ficha: el addon de terceros si lo dijo, si no
     * "Cinemeta" para los ids de IMDb. El proveedor del *stream* lo pone el
     * servidor al scrapear, esto es sólo de dónde salieron los metadatos.
     */
    private fun providerLabel(descriptor: CatalogDescriptor?): String? =
        sourceName?.takeIf { it.isNotBlank() }
            ?: descriptor?.provider?.takeIf { it.isNotBlank() }
            ?: if (id.startsWith("tt")) "Cinemeta" else null

    private companion object {
        fun parseYear(releaseInfo: String): Int? =
            Regex("(\\d{4})").find(releaseInfo)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

/**
 * Normaliza la URL de una imagen.
 *
 * Varios proveedores publican el póster como URL relativa al protocolo
 * (`//cdn.sitio/x.jpg`), que Coil no sabe resolver y deja la tarjeta sin arte.
 * Una cadena vacía se convierte en `null` para que la UI use el respaldo.
 */
internal fun normalizeArtworkUrl(url: String?): String? {
    val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (value.startsWith("//")) "https:$value" else value
}

/** Un episodio tal como lo devuelve Cinemeta a través de `/meta`. */
@Serializable
data class RemoteVideo(
    @SerialName("id") val id: String = "",
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("thumbnail") val thumbnail: String? = null,
)

/* -------------------------------------------------------------------------- */
/* Episodios                                                                  */
/* -------------------------------------------------------------------------- */

/**
 * Un episodio seleccionable en la hoja de detalles.
 *
 * El id que se pide al addon es `{imdb}:{temporada}:{episodio}`, exactamente el
 * que Cinemeta usa en `videos[].id` y el que esperan los scrapers de series.
 */
data class EpisodeOption(
    val season: Int,
    val episode: Int,
    val name: String? = null,
    val overview: String? = null,
) {
    /** "T1 · E2" */
    val label: String get() = "T$season · E$episode"

    /** "E2" para los chips compactos de la TV. */
    val shortLabel: String get() = "E$episode"

    fun idFor(baseId: String): String = "$baseId:$season:$episode"
}

/** Los episodios de una temporada, ordenados. */
data class SeasonGroup(
    val season: Int,
    val episodes: List<EpisodeOption>,
) {
    val label: String get() = if (season <= 0) "Especiales" else "T$season"
}

/**
 * Agrupa los `videos` de una meta en temporadas ordenadas.
 *
 * Descarta la temporada 0 duplicada de algunos títulos cuando está vacía y los
 * videos sin numeración (tráilers, extras), que no son episodios.
 */
fun List<RemoteVideo>.toSeasonGroups(): List<SeasonGroup> =
    filter { it.season != null && it.episode != null }
        .map {
            EpisodeOption(
                season = it.season ?: 0,
                episode = it.episode ?: 0,
                name = it.name?.takeIf { name -> name.isNotBlank() },
                overview = it.overview,
            )
        }
        .distinctBy { it.season to it.episode }
        .sortedWith(compareBy({ it.season }, { it.episode }))
        .groupBy { it.season }
        .map { (season, episodes) -> SeasonGroup(season, episodes) }

/** Enriquece la ficha sin cambiar su identificador de reproducción ni borrar datos válidos. */
fun MediaEntry.enrichedBy(meta: RemoteMeta): MediaEntry = copy(
    title = meta.name.takeIf { it.isNotBlank() } ?: title,
    poster = meta.poster ?: poster,
    backdrop = meta.background ?: backdrop,
    synopsis = meta.description?.takeIf { it.isNotBlank() } ?: synopsis,
    year = meta.year ?: meta.toEntry().year ?: year,
    rating = meta.rating ?: rating,
    genres = meta.genres.takeIf { it.isNotEmpty() } ?: genres,
    runtime = meta.runtime ?: runtime,
    provider = meta.sourceName ?: provider,
)
