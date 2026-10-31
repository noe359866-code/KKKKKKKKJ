package com.tanasi.streamflix.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Un canal que se puede zapear desde el reproductor, sin volver al Home.
 *
 * Es la unidad de la **parrilla**: lo mínimo para sintonizar (`id` + `type`) y
 * para poder enseñar el nombre en el rótulo del cambio de canal (`title`) y su
 * carátula en la guía (`poster`).
 *
 * Deliberadamente **no** lleva las fuentes: cada canal las resuelve el
 * `PlayerViewModel` al sintonizarlo. Cachear todas las fuentes de todos los
 * canales de la fila serían decenas de peticiones para un dato que caduca en
 * segundos (una señal en directo cambia de URL cuando el proveedor rota).
 */
@Serializable
data class PlaybackChannel(
    val id: String,
    val type: String = MediaType.Live.wire,
    val title: String,
    val poster: String? = null,
    /** `true` en toda la parrilla de TV; queda explícito por si hay VOD en cola. */
    val isLive: Boolean = true,
    /**
     * Programación conocida del canal (`Epg`). Vacía cuando la fuente no publica
     * horarios, que hoy es el caso de todos los canales menos los partidos de la
     * agenda deportiva: la guía lo trata como "no hay nada que contar", no como
     * un hueco que rellenar.
     */
    val epg: List<EpgEntry> = emptyList(),
)

/**
 * La parrilla de una fila del Home, lista para zapear.
 *
 * Sólo entran los canales en directo: un carrusel de películas no es una
 * parrilla y `CH+` ahí no significa nada. Se deduplica por `id` porque el mismo
 * canal puede venir repetido (varias señales del mismo proveedor se colapsan a
 * un solo id) y se recorta para que el `Intent` no crezca sin control: 60
 * canales son ~7 KB de extra, muy por debajo del límite de un `Intent`.
 */
fun List<MediaEntry>.toChannelQueue(limit: Int = MAX_CHANNEL_QUEUE): List<PlaybackChannel> =
    asSequence()
        .filter { it.isLive || MediaType.fromWire(it.type) == MediaType.Live }
        .distinctBy { it.id }
        .take(limit)
        .map { entry ->
            PlaybackChannel(
                id = entry.id,
                type = entry.type,
                title = entry.title,
                poster = entry.poster,
                isLive = true,
                epg = Epg.from(entry),
            )
        }
        .toList()

/** Tope de la parrilla que viaja en el `Intent` del reproductor. */
const val MAX_CHANNEL_QUEUE = 60

/**
 * Serializa la parrilla al extra del `Intent`.
 *
 * `org.json` queda fuera a propósito: en los tests de JVM sus clases son stubs
 * que lanzan `RuntimeException("not mocked")`, y esta es justo la pieza que se
 * quiere probar sin emulador. `kotlinx.serialization` ya está en el proyecto y
 * funciona igual en las dos JVM.
 */
object PlaybackQueueCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = ListSerializer(PlaybackChannel.serializer())

    fun encode(queue: List<PlaybackChannel>): String = runCatching {
        json.encodeToString(serializer, queue.take(MAX_CHANNEL_QUEUE))
    }.getOrDefault("[]")

    /** Ante cualquier extra corrupto devuelve lista vacía: el vídeo sigue. */
    fun decode(raw: String?): List<PlaybackChannel> {
        val text = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())
    }
}
