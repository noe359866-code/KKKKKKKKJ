package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.model.MediaEntry

/**
 * Qué se ve en el panel derecho del buscador.
 *
 * Se declara fuera de la composición a propósito: es una regla de producto
 * (resultados > historial > recomendaciones) y así se puede leer y probar en
 * JVM, sin emulador ni Compose. El diálogo sólo la pinta.
 */
internal sealed interface SearchPanelItem {
    /** Encabezado de sección; no es enfocable. */
    data class Header(val title: String, val hint: String = "") : SearchPanelItem

    /** Un título del catálogo o una recomendación de Cinemeta. */
    data class Entry(val entry: MediaEntry) : SearchPanelItem

    /** Una búsqueda anterior: al pulsarla se repite. */
    data class Recent(val query: String) : SearchPanelItem

    /** Borra el historial entero. */
    data object ForgetRecent : SearchPanelItem

    /** La consulta está en camino. */
    data object Searching : SearchPanelItem

    /** Texto suelto (mensajes). */
    data class Note(val text: String) : SearchPanelItem
}

/**
 * Prioridad del panel, de más a menos urgente:
 *
 *  1. si la búsqueda en vivo encontró algo, **resultados** (y nada más: el
 *     usuario está escribiendo, no eligiendo);
 *  2. si está buscando, "buscando" (sin carátulas a medio llegar);
 *  3. si la búsqueda no encontró nada, lo dice y ofrece lo que sí buscó antes;
 *  4. con el campo vacío, **historial** y después **recomendaciones**: en una TV
 *     escribir cuesta, así que lo primero que se ve es lo que no hay que
 *     escribir.
 */
internal fun searchPanelItems(
    query: String,
    preview: SearchPreview,
    suggestions: List<MediaEntry>,
    recentSearches: List<String>,
): List<SearchPanelItem> {
    val clean = query.trim()
    return when (preview) {
        is SearchPreview.Ready -> buildList {
            add(SearchPanelItem.Header("RESULTADOS", "OK abre la ficha · ATRÁS cierra"))
            addAll(preview.entries.map { SearchPanelItem.Entry(it) })
        }

        SearchPreview.Loading -> listOf(
            SearchPanelItem.Header("BUSCANDO", "«$clean»"),
            SearchPanelItem.Searching,
        )

        SearchPreview.Empty -> buildList {
            add(SearchPanelItem.Header("SIN RESULTADOS", "«$clean» no está en el catálogo"))
            if (recentSearches.isNotEmpty()) {
                add(SearchPanelItem.Header("PRUEBA CON", "lo que ya buscaste antes"))
                addAll(recentSearches.take(4).map { SearchPanelItem.Recent(it) })
            }
        }

        SearchPreview.Idle -> buildList {
            if (recentSearches.isNotEmpty()) {
                add(SearchPanelItem.Header("BÚSQUEDAS ANTERIORES", "OK repite la búsqueda"))
                addAll(recentSearches.map { SearchPanelItem.Recent(it) })
                add(SearchPanelItem.ForgetRecent)
            }
            if (suggestions.isNotEmpty()) {
                add(SearchPanelItem.Header("RECOMENDACIONES DE CINEMETA", "OK abre la ficha · sin escribir nada"))
                addAll(suggestions.map { SearchPanelItem.Entry(it) })
            } else if (recentSearches.isEmpty()) {
                add(SearchPanelItem.Header("RECOMENDACIONES DE CINEMETA", "todavía no hay ninguna"))
                add(SearchPanelItem.Note("Escribe un título para empezar."))
            }
        }
    }
}

/** Índice de la primera fila que el mando puede pulsar (`-1` si no hay ninguna). */
internal fun firstFocusableIndex(items: List<SearchPanelItem>): Int = items.indexOfFirst {
    it is SearchPanelItem.Entry || it is SearchPanelItem.Recent || it is SearchPanelItem.ForgetRecent
}
