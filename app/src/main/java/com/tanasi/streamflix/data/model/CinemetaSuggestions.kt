package com.tanasi.streamflix.data.model

/**
 * Las recomendaciones que el buscador enseña junto al teclado.
 *
 * Salen de las filas de **Cinemeta** que ya están cargadas en el Home
 * (`Películas populares`, `Mejor valoradas`, `Series populares`…), no de una
 * petición nueva: el buscador abre con la parrilla de sugerencias ya llena, sin
 * esperar a la red, y sin gastar una llamada por cada vez que alguien prueba a
 * buscar algo.
 *
 * Se descartan los canales en directo y los títulos sin arte: una
 * "recomendación" sin carátula en una TV no se reconoce, y un canal no es una
 * recomendación.
 */
object CinemetaSuggestions {

    /** Tope de sugerencias: llena la columna sin convertirla en otro catálogo. */
    const val DEFAULT_LIMIT = 28

    /**
     * `true` si la fila la sirve Cinemeta.
     *
     * Se acepta el campo `source` que publica el addon y, si falta (servidores
     * antiguos), la convención de ids de Cinemeta (`cinemeta_movie_top`) y los
     * ids canónicos del addon oficial (`top`, `imdbRating`).
     */
    fun isCinemeta(row: CatalogRow): Boolean {
        val source = row.source?.trim()?.lowercase()
        if (source == "cinemeta") return true
        if (source != null && source.isNotEmpty()) return false
        val id = row.id.substringAfterLast('/').lowercase()
        return id.startsWith("cinemeta_") || id == "top" || id == "imdbrating"
    }

    /**
     * Sugerencias deducidas de las filas del Home, en el orden en que el usuario
     * las ve (populares primero), sin repetir títulos.
     */
    fun from(rows: List<CatalogRow>, limit: Int = DEFAULT_LIMIT): List<MediaEntry> =
        rows.asSequence()
            .filter(::isCinemeta)
            .flatMap { it.items.asSequence() }
            .filter { !it.isLive }
            .filter { !it.poster.isNullOrBlank() || !it.backdrop.isNullOrBlank() }
            .distinctBy { "${it.type}:${it.id}" }
            .take(limit)
            .toList()
}
