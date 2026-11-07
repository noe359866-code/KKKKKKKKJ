package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.MediaType

/** Filtros locales: no prometen catálogos ni proveedores que no estén disponibles. */
internal enum class HomeCategory(val label: String) {
    Home("Inicio"), Movies("Películas"), Series("Series");

    fun includes(entry: MediaEntry): Boolean = when (this) {
        Home -> true
        Movies -> entry.type.equals(MediaType.Movie.wire, ignoreCase = true)
        Series -> entry.type.equals(MediaType.Series.wire, ignoreCase = true) ||
            entry.type.equals(MediaType.Anime.wire, ignoreCase = true)
    }
}

internal fun filterHomeRows(rows: List<CatalogRow>, category: HomeCategory): List<CatalogRow> =
    rows.mapNotNull { row ->
        val items = row.items.filter(category::includes)
        if (items.isEmpty()) null else row.copy(items = items)
    }
