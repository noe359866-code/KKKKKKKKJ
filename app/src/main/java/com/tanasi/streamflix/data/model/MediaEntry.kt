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

/**
 * Un título del catálogo: lo mínimo para pintar una tarjeta y pedir sus fuentes.
 *
 * [id] es el identificador completo que espera el backend, con el proveedor
 * incluido (`gnula:street-fighter`, `monoschinos:one-piece:1`, `tv:futbolibre:espn-1`)
 * y, en series, la temporada y el episodio cuando toca (`pelisplushd:x:1:2`).
 */
@Serializable
data class MediaEntry(
    val id: String,
    val type: String,
    val title: String,
    val poster: String? = null,
    val year: Int? = null,
    val subtitle: String? = null,
    /** Sólo informativo: de qué proveedor salió (lo pinta la tarjeta en TV). */
    val provider: String? = null,
) {
    val mediaType: MediaType get() = MediaType.fromWire(type) ?: MediaType.Movie
}

/** Una fila del Home: Películas, Series, Anime o TV en vivo. */
@Serializable
data class CatalogRow(
    val id: String,
    val title: String,
    val items: List<MediaEntry> = emptyList(),
)

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

/** Lo que el reproductor necesita saber para abrir un título. */
data class PlaybackRequest(
    val id: String,
    val type: String,
    val title: String,
    val isLive: Boolean = false,
)
