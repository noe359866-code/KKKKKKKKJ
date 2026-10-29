package com.tanasi.streamflix.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs del **protocolo de catálogos de Stremio** tal como los sirve nuestro
 * backend (`server/src/routes/catalog.js`).
 *
 * El Home de la app ya no es una lista de filas escrita en Kotlin ni en un JSON
 * local: al arrancar se pregunta al servidor **qué catálogos publica**
 * (`GET /catalogs.json`) y cada fila se pide a `GET /catalog/{type}/{id}.json`.
 * Así, añadir una fila nueva es editar `server/config/catalogs.json` — sin
 * recompilar el APK ni reinstalarlo en la TV.
 *
 * La regla de diseño que se respeta en todo este archivo: **el backend manda**.
 * Si el servidor dice que un catálogo es `landscape`, la fila se pinta 16:9
 * aunque el contenido sea "series"; si dice `poster`, se pinta 2:3. La app no
 * adivina la forma a partir del tipo de contenido.
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
     * `cinemeta` | `local` | `remote`. Las filas `remote` vienen de un addon de
     * terceros republicado por nuestro servidor (Netflix, Disney+…).
     */
    @SerialName("source") val source: String? = null,
    /** Nombre del addon de origen cuando [source] es `remote`. */
    @SerialName("provider") val provider: String? = null,
) {
    /** Forma de las tarjetas, decidida por el **servidor**. */
    val rowAspect: RowAspect
        get() = if (posterShape.equals("landscape", ignoreCase = true)) RowAspect.Landscape else RowAspect.Poster

    val mediaType: MediaType get() = MediaType.fromWire(type) ?: MediaType.Movie

    /**
     * Id de fila estable y sin colisiones con la semilla local.
     * El hero indexa por `fila/título`, así que dos filas con el mismo id
     * romperían la etiqueta ("TV en Vivo · 3 de 9").
     */
    val rowId: String get() = "remote:$type/$id"

    /** Clave del arte local de respaldo (`movies`, `series`, `anime`, `live`). */
    val artKey: String
        get() = when (mediaType) {
            MediaType.Live -> "live"
            MediaType.Anime -> "anime"
            MediaType.Series -> "series"
            MediaType.Movie -> "movies"
        }
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
     * @param descriptor catálogo del que salió: presta la forma y la clave de
     *   arte local (las metas de Cinemeta traen póster remoto, pero si la red
     *   falla al cargarlo la tarjeta cae al `drawable` de la app).
     */
    fun toEntry(descriptor: CatalogDescriptor? = null): MediaEntry = MediaEntry(
        id = id,
        /* Se conserva el tipo PUBLICADO: es el que acepta `/stream/{type}/{id}`. */
        type = type,
        title = name.ifBlank { id },
        poster = poster,
        backdrop = background,
        art = descriptor?.artKey ?: artKeyFor(type),
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
        fun artKeyFor(type: String): String = when (MediaType.fromWire(type)) {
            MediaType.Live -> "live"
            MediaType.Anime -> "anime"
            MediaType.Series -> "series"
            else -> "movies"
        }

        fun parseYear(releaseInfo: String): Int? =
            Regex("(\\d{4})").find(releaseInfo)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
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
