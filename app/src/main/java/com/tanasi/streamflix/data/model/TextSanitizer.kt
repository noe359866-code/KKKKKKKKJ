package com.tanasi.streamflix.data.model

/**
 * Arregla el texto que llegó mal codificado desde un proveedor.
 *
 * Los scrapers leen HTML y JSON de sitios que no siempre declaran bien su
 * `charset`. Cuando un UTF-8 se interpreta como Windows-1252, cada carácter no
 * ASCII se convierte en dos o tres caracteres basura: `â€™` en vez de `’`,
 * `Â¡` en vez de `¡`, `Â¿` en vez de `¿`. En una ficha eso se ve como
 * «Â¡No te lo pierdas!» y en un subtítulo como `Donâ€™t`, que es peor porque el
 * espectador no puede hacer nada.
 *
 * La regla es **reparar sólo cuando hay señal de daño**: si el texto no
 * contiene ninguno de los caracteres que delatan la conversión (`â`, `Â` o el
 * diamante `\uFFFD`), se devuelve tal cual. Sin esa guarda, una frase legítima
 * como «CAFÉ Â» o un nombre propio con acento circunflejo se corrompería al
 * arreglarla.
 *
 * No se intenta adivinar la codificación de origen: eso exige re-decodificar
 * los bytes y aquí ya es un `String` inmutable. Se corrigen las secuencias
 * conocidas, que son las que producen los proveedores en la práctica.
 */
object TextSanitizer {

    /**
     * Pares «secuencia rota → carácter». El orden importa: las secuencias
     * largas van antes que sus prefijos, o `â€“` (guion) se comería el prefijo
     * de `â€"+ ` antes de llegar a la regla buena.
     */
    private val replacements: List<Pair<String, String>> = listOf(
        "â™ª" to "\u266A",        // ♪
        "â™«" to "\u266B",        // ♫
        "â€™" to "\u2019",        // ’
        "â€˜" to "\u2018",        // ‘
        "â€œ" to "\u201C",        // “
        "â€\u009D" to "\u201D",   // ”
        "â€\u009C" to "\u201C",   // “
        "â€\u0098" to "\u2018",   // ‘
        "â€\u0099" to "\u2019",   // ’
        "â€“" to "\u2013",        // –
        "â€”" to "\u2014",        // —
        "â€¦" to "\u2026",        // …
        "\u00C2\u00A0" to " ",    // espacio duro roto
        "Â¿" to "¿",
        "Â¡" to "¡",
        "Â«" to "«",
        "Â»" to "»",
        "Â·" to "·",
        "Â°" to "°",
        "Â´" to "´",
        "Â " to " ",
        "â™" to "\u266A",         // resto de ♪ partido
        "â€" to "\u201D",         // último recurso
        "\uFFFD" to "",           // el carácter de reemplazo no aporta nada
    )

    /** Sólo se recorre el texto si contiene alguna de las señales. */
    fun needsRepair(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        for (index in text.indices) {
            when (text[index]) {
                'â', 'Â', '\uFFFD' -> return true
            }
        }
        return false
    }

    /**
     * Devuelve el texto reparado, o **el mismo objeto** si no había nada que
     * arreglar. Devolver la misma instancia importa en las listas de Compose:
     * un `String` nuevo con el mismo contenido invalida el `remember` que
     * compara por identidad.
     */
    fun repair(text: String?): String? {
        if (text == null || !needsRepair(text)) return text
        var result = text
        for ((broken, fixed) in replacements) {
            if (result.contains(broken)) result = result.replace(broken, fixed)
        }
        return result
    }

    /** Igual que [repair] pero devolviendo cadena vacía en vez de `null`. */
    fun repairOrEmpty(text: String?): String = repair(text) ?: ""

    /** Reparado y sin espacios sobrantes; `null` si queda en blanco. */
    fun repairOrNull(text: String?): String? = repair(text)?.takeIf { it.isNotBlank() }
}
