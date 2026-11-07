package com.tanasi.streamflix.data.model

/**
 * Un título "destacado" en el hero.
 *
 * No es un tipo nuevo de contenido: es una vista de un [MediaEntry] que añade
 * de dónde salió (fila y posición). El hero cambia cuando el usuario enfoca una
 * tarjeta, así que necesita saber a qué fila pertenece para pintar
 * "Películas populares · 3 de 6" y para devolver el foco al sitio correcto.
 */
data class Spotlight(
    val entry: MediaEntry,
    val rowId: String,
    val rowTitle: String,
    /** Posición 1-based dentro de la fila. */
    val position: Int,
    val total: Int,
    /**
     * Títulos de la misma fila, en su orden. Va aquí y no se busca después
     * porque el hero también reproduce y puede necesitar el contexto de la
     * fila sin volver a recorrerla.
     */
    val siblings: List<MediaEntry> = emptyList(),
)

/**
 * El catálogo del Home ya resuelto: filas + índice para el hero.
 *
 * El índice se construye una vez (`byId`) para que enfocar una tarjeta no
 * recorra cinco carruseles en cada evento de foco, que en una TV llega a decenas
 * por segundo al mantener la flecha pulsada.
 */
class HomeCatalog(val rows: List<CatalogRow>) {

    /**
     * Índice por **(fila, id, tipo)**.
     *
     * Un mismo título puede estar en "Continuar viendo" y en "Películas
     * populares": si la clave fuese sólo el id, enfocar la tarjeta de películas
     * mostraría en el hero la etiqueta de "Continuar viendo" y una posición que
     * no corresponde a la fila que el usuario está mirando.
     */
    private val byKey: Map<Triple<String, String, String>, Spotlight> = buildMap {
        rows.forEach { row ->
            row.items.forEachIndexed { index, entry ->
                put(
                    key(row.id, entry.id, entry.type),
                    Spotlight(
                        entry = entry,
                        rowId = row.id,
                        rowTitle = row.title,
                        position = index + 1,
                        total = row.items.size,
                        siblings = row.items,
                    ),
                )
            }
        }
    }

    private fun key(rowId: String, entryId: String, type: String) = Triple(rowId, entryId, type)

    val isEmpty: Boolean get() = rows.isEmpty() || rows.all { it.items.isEmpty() }

    /** Qué se ve en el hero antes de que el usuario enfoque nada. */
    val featured: Spotlight?
        get() = rows.firstOrNull { it.items.isNotEmpty() }?.let { row ->
            byKey[key(row.id, row.items.first().id, row.items.first().type)]
        }

    fun spotlightFor(rowId: String, entryId: String, type: String): Spotlight? = byKey[key(rowId, entryId, type)]
}
