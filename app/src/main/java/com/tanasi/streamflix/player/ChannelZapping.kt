package com.tanasi.streamflix.player

import com.tanasi.streamflix.data.model.PlaybackChannel

/**
 * Aritmética del zapping, aislada de Compose y de `ExoPlayer`.
 *
 * Un `CH+` en el último canal tiene que dar la vuelta al primero: la parrilla de
 * una TV no tiene fin y, si el mando "se queda pegado" en el borde, el usuario
 * cree que la app se colgó. Esa regla —y el caso de un canal que ya no está en
 * la parrilla— se prueban en JVM ([ChannelZappingTest]).
 */
object ChannelZapping {

    /**
     * Índice del canal a [delta] posiciones de [currentIndex], dando la vuelta.
     *
     * @param currentIndex índice actual; `-1` (el canal se abrió desde fuera de
     *   la parrilla) se interpreta como "antes del primero", así que `+1`
     *   arranca en el primero y `-1` en el último.
     * @return índice válido dentro de `0 until size`, o `-1` si no hay parrilla.
     */
    fun step(currentIndex: Int, delta: Int, size: Int): Int {
        if (size <= 0) return -1
        if (size == 1) return 0
        val base = when {
            currentIndex in 0 until size -> currentIndex
            delta >= 0 -> -1
            else -> size
        }
        return ((base + delta) % size + size) % size
    }

    /** Zapear sólo tiene sentido en directo y con algo a lo que saltar. */
    fun canZap(isLive: Boolean, size: Int): Boolean = isLive && size > 1

    /** Índice del canal en uso, o `-1` si este título no está en la parrilla. */
    fun indexOf(queue: List<PlaybackChannel>, id: String): Int =
        queue.indexOfFirst { it.id == id }

    fun channelAt(queue: List<PlaybackChannel>, index: Int): PlaybackChannel? =
        queue.getOrNull(index)

    /**
     * Rótulo del cambio de canal: `"3 de 12"`.
     *
     * Devuelve `null` cuando no hay parrilla o el canal no está en ella, para
     * que la UI no invente un número que no existe.
     */
    fun positionLabel(queue: List<PlaybackChannel>, index: Int): String? =
        if (index in queue.indices) "${index + 1} de ${queue.size}" else null
}
