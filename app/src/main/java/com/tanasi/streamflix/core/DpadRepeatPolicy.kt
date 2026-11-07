package com.tanasi.streamflix.core

/**
 * Cuándo dejar pasar una repetición del D-Pad.
 *
 * Mantener pulsada una dirección no genera una pulsación: el sistema emite
 * `KeyEvent`s con `repeatCount > 0` a su propio ritmo (unos 50 ms en muchas TV
 * box). Compose mueve el foco en cada uno de ellos, y en un aparato modesto eso
 * satura el hilo de UI: la lista avanza a tirones y a veces se come tarjetas.
 *
 * La regla es una compuerta de tiempo por eje, no un contador. Dos motivos:
 *
 *  - **El ritmo del sistema no es fiable.** Depende del fabricante y del mando,
 *    así que contar repeticiones daría velocidades distintas en cada TV. Una
 *    ventana en milisegundos es la misma en todas.
 *  - **Vertical y horizontal no piden lo mismo.** Bajar por una lista de filas
 *    es un gesto de barrido y admite ir más pausado; recorrer una fila de
 *    tarjetas se hace de lado y pedir el mismo ritmo lo hace sentir pesado. Por
 *    eso el eje vertical tiene una compuerta más ancha.
 *
 * La primera pulsación (sin repetición) **nunca** pasa por aquí: ésa la resuelve
 * Compose y tiene que ser inmediata, o el mando parecería no responder.
 *
 * Los umbrales se validan contra `~60 Hz`: 80 ms son unas 5 pantallas, suficiente
 * para que una pulsación sostenida no dispare más de ~12 movimientos por
 * segundo, que es el punto donde la animación de foco todavía se puede seguir
 * con la vista a tres metros.
 */
internal object DpadRepeatPolicy {

    /** Compuerta del eje horizontal: recorrer una fila se hace de lado, más rápido. */
    const val HORIZONTAL_GATE_MS = 80L

    /** Compuerta del eje vertical: bajar de fila es un barrido, más pausado. */
    const val VERTICAL_GATE_MS = 112L

    /**
     * `true` si la repetición llega demasiado pronto y hay que descartarla.
     *
     * @param elapsedMs tiempo desde la última repetición aceptada.
     * @param gateMs compuerta del eje; se acota a cero para que un valor raro
     *   del llamador no congele la navegación para siempre.
     */
    fun shouldDrop(elapsedMs: Long, gateMs: Long): Boolean = elapsedMs < gateMs.coerceAtLeast(0L)

    /** La compuerta que corresponde al eje vertical u horizontal. */
    fun gateFor(vertical: Boolean): Long = if (vertical) VERTICAL_GATE_MS else HORIZONTAL_GATE_MS
}
