package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.MediaType

/** Filtros locales: no prometen catálogos ni proveedores que no estén disponibles. */
internal enum class HomeCategory(val label: String) {
    Home("Inicio"), Movies("Películas"), Series("Series"), Live("En vivo");

    fun includes(entry: MediaEntry): Boolean = when (this) {
        Home -> true
        Movies -> entry.type.equals(MediaType.Movie.wire, ignoreCase = true) && !entry.live && !entry.isLive
        Series -> (entry.type.equals(MediaType.Series.wire, ignoreCase = true) ||
            entry.type.equals(MediaType.Anime.wire, ignoreCase = true)) && !entry.live && !entry.isLive
        // Un evento deportivo programado también pertenece a TV, aunque aún no esté al aire.
        Live -> entry.type.equals(MediaType.Live.wire, ignoreCase = true) || entry.live || entry.isLive
    }
}

internal fun filterHomeRows(rows: List<CatalogRow>, category: HomeCategory): List<CatalogRow> =
    rows.mapNotNull { row ->
        val items = row.items.filter(category::includes)
        if (items.isEmpty()) null else row.copy(items = items)
    }
