package com.tanasi.streamflix.ui.components

import androidx.annotation.DrawableRes
import com.tanasi.streamflix.R

/**
 * Arte local de respaldo.
 *
 * El catálogo del servidor trae URLs de TMDB y de cada proveedor, pero:
 *  - un TV box sin salida a `image.tmdb.org` se quedaría con tarjetas grises;
 *  - el hero necesita un backdrop de 16:9 que las webs de origen no publican.
 *
 * Cada título lleva en el JSON una clave (`art`: `movies`, `series`, `anime`,
 * `live`) y aquí se traduce a un `@DrawableRes`. Es la única forma de resolver
 * recursos desde datos: `R.drawable.*` no existe en un JSON.
 */
object Artwork {

    private const val KEY_MOVIES = "movies"
    private const val KEY_SERIES = "series"
    private const val KEY_ANIME = "anime"
    private const val KEY_LIVE = "live"

    /** Backdrop 16:9 (1280×720, ~150 KB cada uno). */
    @DrawableRes
    fun backdrop(key: String?): Int = when (key?.lowercase()) {
        KEY_SERIES -> R.drawable.art_series
        KEY_ANIME -> R.drawable.art_anime
        KEY_LIVE -> R.drawable.art_live
        else -> R.drawable.art_movies
    }

    /**
     * Respaldo de póster. Se reutiliza el backdrop y se recorta a 2:3 con
     * `ContentScale.Crop`: mejor un trozo de arte coherente que un hueco.
     */
    @DrawableRes
    fun posterFallback(key: String?): Int = backdrop(key)
}
