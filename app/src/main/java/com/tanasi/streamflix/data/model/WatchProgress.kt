package com.tanasi.streamflix.data.model

import kotlinx.serialization.Serializable

/**
 * Lo que se estaba viendo, por perfil.
 *
 * Se guarda al salir del reproductor y alimenta la fila **Seguir viendo** del
 * Home. Es lo que hace que dos perfiles se comporten como dos personas: la
 * misma televisión enseña series distintas según quién entre.
 *
 * La lógica (cuándo cuenta como visto, cuánto queda, cuándo desaparece) es
 * Kotlin puro para poder probarla sin emulador.
 */
@Serializable
data class WatchProgress(
    val id: String,
    val type: String,
    val title: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 0..100, nunca 0 ni 100: la barra de la tarjeta debe verse. */
    val percent: Int
        get() {
            if (durationMs <= 0L) return 1
            val raw = (positionMs.toDouble() / durationMs.toDouble() * 100).toInt()
            return raw.coerceIn(1, 99)
        }

    /**
     * Empezar los primeros segundos o terminar los últimos no es "seguir
     * viendo": lo primero no deja rastro y lo segundo ya está visto.
     */
    fun isTrackable(): Boolean = when {
        durationMs <= 0L -> false
        positionMs < MIN_POSITION_MS -> false
        positionMs >= durationMs - END_MARGIN_MS -> false
        else -> true
    }

    /**
     * ¿Esta entrada es del título [contentId]?
     *
     * Un episodio se guarda con el id completo (`serie:slug:1:3`): borrar
     * "la serie" tiene que borrar también sus capítulos, y por eso se compara
     * el prefijo con dos puntos de separación, no con un `startsWith` a secas
     * (`serie:slug` no debe arrastrar `serie:slug-2`).
     */
    fun belongsTo(contentId: String): Boolean = sameTitle(id, contentId)

    /** "2 h 05 min restantes" (o "12 min restantes"). */
    fun remainingLabel(): String {
        val leftMs = (durationMs - positionMs).coerceAtLeast(0L)
        val minutes = (leftMs / 60_000L).toInt()
        return when {
            durationMs <= 0L -> "En curso"
            minutes < 1 -> "Menos de 1 min restante"
            minutes < 60 -> "$minutes min restantes"
            else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min restantes"
        }
    }

    /**
     * Tarjeta para el Home: mismo `MediaEntry` que pinta una fila normal, con
     * la barra de progreso y el tiempo restante ya resueltos.
     */
    fun toEntry(): MediaEntry = MediaEntry(
        id = id,
        type = type,
        title = title,
        poster = poster,
        backdrop = backdrop,
        progressPercent = percent,
        subtitle = remainingLabel(),
        resumePositionMs = positionMs,
    )

    companion object {
        /** Menos de 15 s no deja huella: el usuario sólo estaba mirando. */
        const val MIN_POSITION_MS = 15_000L

        /** A falta de 90 s se considera terminado. */
        const val END_MARGIN_MS = 90_000L

        /**
         * ¿[entryId] es el título [contentId] o uno de sus episodios?
         *
         * La misma regla la usan el borrado (`WatchProgressStore.removeTitle`)
         * y la ficha (`MainActivity`), para que el botón aparezca exactamente
         * cuando hay algo que borrar.
         */
        fun sameTitle(entryId: String, contentId: String): Boolean =
            contentId.isNotBlank() && (entryId == contentId || entryId.startsWith("$contentId:"))
    }
}
