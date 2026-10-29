package com.tanasi.streamflix.player

import com.tanasi.streamflix.data.model.StreamSource

/** Por qué se abandonó una fuente (para logs, métricas y la UI). */
enum class FailoverReason {
    /** ExoPlayer reportó un error de reproducción. */
    PLAYER_ERROR,

    /** El servidor devolvió un 4xx/5xx. */
    HTTP_ERROR,

    /** Códec/container que el dispositivo no entiende. */
    UNSUPPORTED_FORMAT,

    /** Ni error ni reproducción: el buffer se quedó clavado. */
    STALL,

    /** El usuario pidió cambiar de servidor desde la lista. */
    MANUAL,
}

/** Un salto, en orden cronológico. Sirve para depurar y para la UI. */
data class FailoverEvent(
    val from: StreamSource?,
    val to: StreamSource?,
    val reason: FailoverReason,
    val message: String? = null,
)

/**
 * Decide a qué fuente saltar. **Kotlin puro, sin Android**: toda la política de
 * failover se puede probar en JVM.
 *
 * Reglas:
 *  - El orden es el que mandó el servidor (índice 0 = mayor prioridad).
 *  - Cada fuente se intenta **una vez** por sesión de reproducción: si un CDN
 *    está caído, insistir sólo alarga la espera del usuario.
 *  - Al agotar la lista no se hace wrapping (volver al primero): el llamador
 *    muestra "se agotaron las fuentes" en vez de entrar en un bucle.
 */
class FailoverPlanner(private val sources: List<StreamSource>) {

    private val mutableHistory = mutableListOf<FailoverEvent>()

    val history: List<FailoverEvent> get() = mutableHistory.toList()
    val total: Int get() = sources.size
    val isExhausted: Boolean get() = currentIndex >= sources.size

    /** Índice de la fuente en reproducción, o -1 antes de [start]. */
    var currentIndex: Int = -1
        private set

    /** Posición 1-based para etiquetas ("Fuente 2 de 5"); 0 si no empezó. */
    val progress: Int get() = (currentIndex + 1).coerceAtLeast(0)

    val current: StreamSource? get() = sources.getOrNull(currentIndex)

    /**
     * Primera fuente a reproducir, o `null` si el array llegó vacío.
     *
     * @param atIndex fuente elegida por el usuario en la hoja de detalles. El
     *   failover sigue **hacia abajo** desde ahí (nunca vuelve a las de más
     *   prioridad que ya descartó a mano).
     */
    fun start(atIndex: Int = 0): StreamSource? {
        if (sources.isEmpty()) {
            currentIndex = 0
            return null
        }
        currentIndex = atIndex.coerceIn(0, sources.lastIndex)
        return sources[currentIndex]
    }

    /**
     * Marca la fuente actual como inservible y devuelve la siguiente, o `null`
     * si ya no queda ninguna.
     */
    fun fail(reason: FailoverReason, message: String? = null): StreamSource? {
        val from = current
        currentIndex = if (currentIndex < 0) 1 else currentIndex + 1
        val to = sources.getOrNull(currentIndex)
        mutableHistory += FailoverEvent(from = from, to = to, reason = reason, message = message)
        return to
    }

    /** Salta a una fuente concreta (elección manual del usuario). */
    fun select(index: Int, reason: FailoverReason = FailoverReason.MANUAL): StreamSource? {
        if (index !in sources.indices) return null
        val from = current
        currentIndex = index
        val to = sources[currentIndex]
        mutableHistory += FailoverEvent(from = from, to = to, reason = reason)
        return to
    }

    /** `true` mientras el siguiente salto todavía puede dar algo. */
    fun hasNext(): Boolean = sources.isEmpty() || (currentIndex + 1) < sources.size
}
