package com.tanasi.streamflix.util

import java.util.Locale

/**
 * Formato de tiempos del reproductor.
 *
 * Kotlin puro (sin `android.text.format`) para poder probarlo en JVM y porque
 * `DateUtils` depende del idioma del sistema: aquí siempre queremos `1:23` y no
 * "1 min 23 s" ocupando media barra de progreso.
 */
object Durations {

    /** Texto para una posición desconocida (`C.TIME_UNSET` o duración ilegible). */
    const val UNKNOWN: String = "--:--"

    /**
     * `83_000` → `"1:23"`; `3_723_000` → `"1:02:03"`; negativo → [UNKNOWN].
     *
     * Se usa `Locale.ROOT` a propósito: `String.format` con el idioma del
     * sistema cambia los dígitos en locales como `ar` o `fa` y desalinea la
     * barra de progreso.
     */
    fun clock(ms: Long): String {
        if (ms < 0L) return UNKNOWN
        val totalSeconds = ms / 1_000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3_600L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}
