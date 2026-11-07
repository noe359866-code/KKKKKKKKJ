package com.tanasi.streamflix.data.model

/**
 * Cuándo ofrecer el episodio siguiente y cuál es.
 *
 * Se separa de la UI a propósito: son las dos decisiones que se pueden
 * equivocar en silencio. Un "siguiente" mal calculado manda al usuario a un
 * episodio que el proveedor no conoce y acaba en "sin fuentes"; una ventana mal
 * medida tapa la escena final con una tarjeta o aparece a mitad del capítulo.
 */

/**
 * El episodio que va después de [current] en su temporada, o `null`.
 *
 * Se resuelve por **posición en la lista ordenada**, nunca con
 * `episode + 1`. Las temporadas reales tienen huecos —el E5 no existe, el
 * especial va como E0— y sumar uno produce un id inventado; ordenar por
 * `episode` y avanzar al siguiente elemento da el que de verdad existe.
 *
 * Al terminar la temporada devuelve `null`: saltar a la siguiente es una
 * decisión de producto, no una consecuencia automática.
 */
internal fun nextEpisodeAfter(
    seasons: List<SeasonGroup>,
    current: EpisodeOption,
): EpisodeOption? {
    val season = seasons.firstOrNull { it.season == current.season } ?: return null
    val ordered = season.episodes.sortedBy { it.episode }
    val index = ordered.indexOfFirst { it.episode == current.episode }
    if (index < 0) return null
    return ordered.getOrNull(index + 1)
}

/**
 * Si el episodio siguiente todavía no se ha emitido.
 *
 * Ofrecer "siguiente" para un capítulo que aún no ha salido lleva a un fallo de
 * fuentes garantizado, así que conviene no ofrecerlo. Se compara **sólo la
 * fecha** ([isoDate] espera `AAAA-MM-DD…`) para no depender de la hora ni de la
 * zona: un capítulo que se emite hoy ya se puede ofrecer.
 *
 * @param nextAirDate fecha del episodio siguiente, tal cual la publica el
 *   catálogo. `null` o ilegible significa "no se sabe", y entonces se ofrece:
 *   es mejor intentarlo y que el failover diga la verdad que esconder un
 *   episodio que sí existe.
 */
internal fun isEpisodeNotAiredYet(nextAirDate: String?, todayIsoDate: String): Boolean {
    val air = nextAirDate?.take(ISO_DATE_LENGTH)?.takeIf { it.length == ISO_DATE_LENGTH } ?: return false
    if (!air.matches(ISO_DATE_PATTERN)) return false
    val today = todayIsoDate.take(ISO_DATE_LENGTH)
    if (!today.matches(ISO_DATE_PATTERN)) return false
    /* Comparación de cadenas: en `AAAA-MM-DD` el orden lexicográfico es el
     * orden cronológico, así que no hace falta parsear ni traer java.time. */
    return air > today
}

private const val ISO_DATE_LENGTH = 10
private val ISO_DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/**
 * Ventana de aparición del botón de "siguiente episodio".
 *
 * Aparece **sólo en los últimos segundos**, no al 95 % ni con cuenta atrás
 * automática: con un mando y a tres metros, que algo cambie solo es peor que
 * pedir una pulsación.
 *
 * Se prefiere el final de los créditos cuando se conoce (el tramo `Credits` que
 * ya trae `SkipSegment`): así la tarjeta no tapa la escena final de una película
 * ni el gag post-créditos de una serie. Sin créditos conocidos se usa el último
 * [FALLBACK_WINDOW_MS], que es un compromiso razonable: cuarenta segundos dan
 * tiempo de sobra a leer y pulsar sin cubrir media escena.
 */
internal object NextEpisodeWindow {

    /** Sin datos de créditos: cuánto antes del final se ofrece. */
    const val FALLBACK_WINDOW_MS = 40_000L

    /**
     * Si toca mostrar la tarjeta.
     *
     * @param positionMs posición actual.
     * @param durationMs duración total; `0` o negativa (aún no conocida) no
     *   permite decidir y devuelve `false`.
     * @param creditsStartMs principio de los créditos, si `SkipSegment` lo
     *   conoce. Cuando llega, la tarjeta aparece al entrar en ellos.
     */
    fun shouldOffer(positionMs: Long, durationMs: Long, creditsStartMs: Long? = null): Boolean {
        if (durationMs <= 0L || positionMs <= 0L) return false
        /* Una posición por debajo de la duración no es una señal de final
         * válida: pasa al cambiar de fuente y llevaría a ofrecer el siguiente
         * episodio en el primer segundo. */
        if (positionMs > durationMs) return false

        val from = creditsStartMs
            ?.takeIf { it in 1 until durationMs }
            ?: (durationMs - FALLBACK_WINDOW_MS).coerceAtLeast(0L)
        return positionMs >= from
    }
}
