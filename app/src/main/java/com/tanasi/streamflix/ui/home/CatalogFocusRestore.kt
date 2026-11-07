package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.ui.components.stableMediaKeys

/** Identidad y posición al salir del catálogo, no el índice como identidad. */
internal data class CatalogFocusAnchor(
    val rowId: String,
    val itemKey: String,
    val itemIndex: Int,
    val returnSignal: Int,
)

internal data class CatalogFocusTarget(val rowIndex: Int, val itemIndex: Int)

/** Si el catálogo cambió, conserva la fila y aproxima la posición cuando sea posible. */
internal fun resolveCatalogFocus(rows: List<CatalogRow>, anchor: CatalogFocusAnchor): CatalogFocusTarget? {
    if (rows.isEmpty()) return null
    val originalRowIndex = rows.indexOfFirst { it.id == anchor.rowId && it.items.isNotEmpty() }
    val rowIndex = if (originalRowIndex >= 0) originalRowIndex else rows.indexOfFirst { it.items.isNotEmpty() }
    if (rowIndex < 0) return null
    val items = rows[rowIndex].items
    val itemIndex = if (originalRowIndex >= 0) {
        stableMediaKeys(items).indexOf(anchor.itemKey).takeIf { it >= 0 }
            ?: anchor.itemIndex.coerceIn(0, items.lastIndex)
    } else 0
    return CatalogFocusTarget(rowIndex, itemIndex)
}

/** Una petición vieja no debe mover el foco tras navegar, cambiar pantalla o abrir búsqueda. */
internal fun mayRestoreCatalogFocus(
    requested: CatalogFocusAnchor?,
    current: CatalogFocusAnchor?,
    returnSignal: Int,
    isActive: Boolean,
    searchOpen: Boolean,
): Boolean = requested != null && requested == current && requested.returnSignal != returnSignal && isActive && !searchOpen
