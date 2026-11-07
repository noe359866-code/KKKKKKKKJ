package com.tanasi.streamflix.data.model

/**
 * Un tramo del título que se puede saltar: la cabecera, el resumen del capítulo
 * anterior o los créditos.
 *
 * Las dos bases públicas —IntroDB e TheIntroDB— publican los mismos tres o
 * cuatro tipos con nombres distintos y en unidades distintas (segundos,
 * milisegundos, `null` para "empieza en 0" o para "hasta el final"). Se
 * normaliza todo a milisegundos y a este enum para que el reproductor no tenga
 * que saber de dónde vino el dato.
 */
enum class SkipSegmentType(val label: String) {
    /** Cabecera del capítulo. */
    Intro("Saltar intro"),

    /** "En el capítulo anterior…". */
    Recap("Saltar resumen"),

    /** Créditos finales. */
    Credits("Saltar créditos"),
}

/**
 * Un tramo concreto, en milisegundos y siempre con principio y final.
 *
 * Los finales abiertos (`null` = "hasta el final del vídeo") se resuelven antes
 * de llegar aquí, con la duración real: un tramo sin final no se puede ofrecer
 * como salto sin saber adónde ir.
 */
data class SkipSegment(
    val type: SkipSegmentType,
    val startMs: Long,
    val endMs: Long,
) {
    /** Milisegundos que dura el tramo. */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** `true` si el tramo es tan corto que saltarlo no aporta nada. */
    val isUsable: Boolean get() = durationMs >= MIN_USABLE_MS

    companion object {
        /**
         * Cinco segundos es el mínimo que publican ambas bases para cualquier
         * tipo. Por debajo no es un tramo: es un dato mal formado.
         */
        const val MIN_USABLE_MS = 5_000L
    }
}

/**
 * Dónde está el usuario respecto a los tramos de un título.
 *
 * - `null` → no hay nada que ofrecer en este instante.
 * - [active] es el tramo en el que está metido ahora mismo (el botón salta a su
 *   final).
 * - [upcoming] es el siguiente que empieza pronto, para poder avisar antes de
 *   entrar (los reproductores de TV suelen enseñar el botón unos segundos
 *   antes).
 */
data class SkipWindow(
    val active: SkipSegment?,
    val upcoming: SkipSegment?,
) {
    val hasAnything: Boolean get() = active != null || upcoming != null

    companion object {
        /**
         * Cuánto antes de que empiece un tramo se puede ofrecer.
         *
         * Cinco segundos: suficiente para que dé tiempo a leer el botón y
         * pulsarlo con el mando, y poco como para que no aparezca durante la
         * escena anterior.
         */
        const val UPCOMING_LEAD_MS = 5_000L

        val Empty = SkipWindow(active = null, upcoming = null)

        /**
         * Calcula la ventana para una posición.
         *
         * Un tramo está activo desde su principio hasta su final (final
         * excluido, para que al saltar justo al final no vuelva a ofrecerse) y
         * "próximo" cuando faltan [UPCOMING_LEAD_MS] o menos para que empiece.
         * Se prefiere el activo sobre el próximo: si el usuario está dentro de
         * la cabecera, lo que quiere es salir de ella.
         */
        fun at(segments: List<SkipSegment>, positionMs: Long): SkipWindow {
            if (segments.isEmpty()) return Empty
            val usable = segments.filter { it.isUsable }
            if (usable.isEmpty()) return Empty

            val active = usable.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
            val upcoming = usable
                .filter { it.startMs > positionMs && it.startMs - positionMs <= UPCOMING_LEAD_MS }
                .minByOrNull { it.startMs }
            return SkipWindow(active = active, upcoming = if (active != null) null else upcoming)
        }
    }
}
