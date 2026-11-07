package com.tanasi.streamflix.core

import kotlin.math.min

/**
 * Cuánto avanza la lista mientras el usuario mantiene ARRIBA o ABAJO.
 *
 * En una TV, bajar veinte filas pulsando diez veces el mando es una tortura.
 * Con el botón sostenido la lista debería deslizarse sola, como en el lanzador
 * de la propia televisión. Esto es la aritmética de ese deslizamiento; el
 * modificador de Compose sólo la alimenta con el reloj de cada fotograma.
 *
 * Tres decisiones que no son obvias:
 *
 *  - **Velocidad en dp por segundo, no en píxeles.** La misma app corre en una
 *    TV 1080p y en una 4K; en píxeles, la de 4K se sentiría cuatro veces más
 *    rápida.
 *  - **Tope por fotograma.** Si el sistema se atasca (recolección de basura,
 *    una imagen que tarda en decodificar), entre dos fotogramas puede pasar
 *    medio segundo. Sin tope, ese fotograma movía la lista cientos de dp de
 *    golpe y el usuario perdía el sitio. Con tope, el peor caso es un salto
 *    corto y visible.
 *  - **El delta se acumula en `Double` y se entrega ya redondeado a entero.**
 *    A 60 Hz cada fotograma avanza una fracción de píxel; si se redondea en
 *    cada paso, esa fracción se pierde siempre y la lista no se mueve nunca.
 */
internal object DpadFastScroll {

    /** dp/s del deslizamiento. Deliberadamente lento: es para leer, no para huir. */
    const val VELOCITY_DP_PER_SEC = 2400f

    /** Tope de tiempo que se acepta de un solo fotograma: ~3 fotogramas a 60 Hz. */
    const val MAX_FRAME_DT_MS = 48L

    /**
     * Píxeles a desplazar en un fotograma.
     *
     * @param elapsedMs tiempo desde el fotograma anterior, en milisegundos.
     * @param density densidad de la pantalla (dp → px).
     * @param velocityDpPerSec velocidad del deslizamiento.
     * @param maxFrameDtMs tope del fotograma.
     */
    fun deltaPx(
        elapsedMs: Long,
        density: Float,
        velocityDpPerSec: Float = VELOCITY_DP_PER_SEC,
        maxFrameDtMs: Long = MAX_FRAME_DT_MS,
    ): Int {
        if (elapsedMs <= 0L || density <= 0f || velocityDpPerSec <= 0f) return 0
        val frameMs = min(elapsedMs, maxFrameDtMs.coerceAtLeast(0L))
        val dp = velocityDpPerSec * (frameMs / 1000f)
        return (dp * density).toInt()
    }
}
