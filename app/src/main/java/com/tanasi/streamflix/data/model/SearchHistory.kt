package com.tanasi.streamflix.data.model

/**
 * Historial de búsquedas: las últimas consultas, sin repeticiones.
 *
 * Es una lista ordenada (lo más reciente primero) y una regla pura, separada
 * del almacenamiento para poder probarla en JVM. La pantalla sólo pregunta
 * "¿qué se buscó antes?" cuando el campo está vacío: en una TV escribir con el
 * mando cuesta, y repetir la misma búsqueda de ayer no debería costar otra vez
 * veinte pulsaciones.
 *
 * Reglas:
 *  - se normaliza el texto (espacios de más fuera) y se descartan las consultas
 *    de menos de [MIN_LENGTH] caracteres: "a" no es una búsqueda, es un tecleo;
 *  - la misma consulta **sube al principio** en vez de duplicarse, sin
 *    distinguir mayúsculas (`Matrix` y `matrix` son la misma);
 *  - tope de [MAX_ENTRIES]: el buscador no puede crecer sin fin.
 */
object SearchHistory {

    /** Cuántas consultas se recuerdan. */
    const val MAX_ENTRIES = 8

    /** Menos de dos caracteres es un tecleo, no una búsqueda. */
    const val MIN_LENGTH = 2

    /** Coloca [query] al principio de [existing]. Devuelve la lista sin tocar si no vale. */
    fun add(existing: List<String>, query: String, limit: Int = MAX_ENTRIES): List<String> {
        val clean = query.trim().replace(Regex("\\s+"), " ")
        if (clean.length < MIN_LENGTH) return existing
        val others = existing.filterNot { it.equals(clean, ignoreCase = true) }
        return (listOf(clean) + others).take(limit.coerceAtLeast(1))
    }

    /** Quita una consulta concreta (el usuario la borró del historial). */
    fun forget(existing: List<String>, query: String): List<String> =
        existing.filterNot { it.equals(query.trim(), ignoreCase = true) }

    /** Sanea lo que venga del almacenamiento: sin vacíos, sin duplicados, con tope. */
    fun sanitize(raw: List<String>, limit: Int = MAX_ENTRIES): List<String> =
        raw.asSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length >= MIN_LENGTH }
            .distinctBy { it.lowercase() }
            .take(limit.coerceAtLeast(1))
            .toList()

    /** Lo que se enseña en el panel: la consulta de hoy no se repite como sugerencia. */
    fun forQuery(existing: List<String>, query: String): List<String> =
        if (query.isBlank()) existing else existing.filterNot { it.equals(query.trim(), ignoreCase = true) }
}
