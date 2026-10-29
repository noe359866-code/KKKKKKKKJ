package com.tanasi.streamflix.player

/**
 * Aritmética de los saltos del D-Pad.
 *
 * Vive aparte del reproductor (y sin Android) porque es justo el tipo de cuenta
 * que se rompe en los bordes: pulsar "atrás" en el segundo 3 no debe llevar a
 * `-7 s`, y pulsar "adelante" en los últimos segundos no debe salirse del final
 * (ExoPlayer acaba en un `STATE_ENDED` que el usuario no pidió).
 *
 * El paso coincide con los incrementos configurados en
 * [MediaSources.buildPlayer] (10 s en VOD) para que el salto que pinta el mando
 * sea el mismo que aplica el reproductor con `seekBack()`/`seekForward()`.
 */
object SeekPolicy {

    /** ±10 s por pulsación, igual que `setSeekBackIncrementMs` en MediaSources. */
    const val STEP_MS: Long = 10_000L

    /**
     * Destino del salto, siempre dentro de `[0, duración]`.
     *
     * @param currentMs posición actual (negativa se trata como 0).
     * @param deltaMs incremento; negativo para retroceder.
     * @param durationMs duración conocida; si es `<= 0` (directo, o todavía sin
     *   metadatos) no se limita por arriba.
     */
    fun target(currentMs: Long, deltaMs: Long, durationMs: Long): Long {
        val current = currentMs.coerceAtLeast(0L)
        val upperBound = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        /* `c + d` no puede desbordar con valores reales (ms), pero se protege
         * igualmente porque un CDN puede devolver una duración absurda. */
        val raw = if (deltaMs > 0 && current > Long.MAX_VALUE - deltaMs) {
            Long.MAX_VALUE
        } else {
            current + deltaMs
        }
        return raw.coerceIn(0L, upperBound)
    }
}
