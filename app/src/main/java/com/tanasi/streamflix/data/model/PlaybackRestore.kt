package com.tanasi.streamflix.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lo mínimo para devolver al usuario **a lo que estaba viendo** si el proceso
 * muere.
 *
 * En una TV box Android el `Activity` del reproductor se recrea con el `Intent`
 * original cuando el sistema mata el proceso por memoria: sin esto, el usuario
 * que llevaba 40 minutos de película volvía al segundo 0 (o al punto donde
 * abrió la ficha), y el que había zapeado a otro canal volvía al primero.
 *
 * Se guarda en `onSaveInstanceState`, así que:
 *  - sobrevive a la muerte del proceso (el `Bundle` lo conserva el sistema);
 *  - no toca disco: es estado de una sesión, no un historial (eso es
 *    `WatchProgressStore`);
 *  - si el `Bundle` viene corrupto o de una versión anterior, `decode` devuelve
 *    `null` y la app arranca del `Intent` como siempre.
 */
object PlaybackRestore {

    /** Petición en curso + segundo en el que iba. */
    data class Snapshot(val request: PlaybackRequest, val positionMs: Long)

    @Serializable
    private data class Dto(val request: PlaybackRequest, val positionMs: Long = 0L)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Serializa la petición **con la posición actual**.
     *
     * Un directo no se "continúa": guardar su segundo haría que al volver se
     * pidiera un segmento del pasado —o uno que ya no está en la ventana del
     * vivo— y la señal tardaría en arrancar. El canal sí se recuerda.
     */
    fun encode(request: PlaybackRequest, positionMs: Long): String {
        val safePosition = if (request.isLive) 0L else positionMs.coerceAtLeast(0L)
        return runCatching {
            json.encodeToString(Dto.serializer(), Dto(request.copy(startPositionMs = safePosition), safePosition))
        }.getOrDefault("")
    }

    /** `null` si no hay nada guardado, si el JSON no se puede leer o si el id viene vacío. */
    fun decode(raw: String?): Snapshot? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val dto = json.decodeFromString(Dto.serializer(), text)
            if (dto.request.id.isBlank()) return@runCatching null
            val position = if (dto.request.isLive) 0L else dto.positionMs.coerceAtLeast(0L)
            Snapshot(dto.request.copy(startPositionMs = position), position)
        }.getOrNull()
    }
}
