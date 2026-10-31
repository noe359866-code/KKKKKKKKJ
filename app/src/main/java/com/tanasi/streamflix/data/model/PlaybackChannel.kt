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
 * un solo id).
 *
 * El recorte se hace por **bytes** en [PlaybackQueueCodec], no por número de
 * canales: 60 era un número mágico que dejaba sin zapear la mitad de una fila de
 * 120 canales, y a la vez no protegía de nada si algún día cada canal llevara su
 * programación.
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

/**
 * Tope de **entrada** de la parrilla (canales). No es el que manda: el recorte
 * real lo decide el tamaño del extra (`PlaybackQueueCodec.MAX_BYTES`), porque lo
 * que rompe un `Intent` son los bytes, no los canales.
 */
const val MAX_CHANNEL_QUEUE = 200

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

    /**
     * Presupuesto del extra: 64 KB.
     *
     * Un `Intent` admite ~500 KB por extra y las transacciones del `Binder` ~1 MB
     * entre todos, pero la misma parrilla viaja también en
     * `onSaveInstanceState` (`PlaybackRestore`), así que el presupuesto se mide
     * contra lo que aguanta el `Bundle`. Con la parrilla típica (~150 bytes por
     * canal, más programación si la hay) entran más de 300 canales.
     */
    const val MAX_BYTES = 64_000

    /**
     * La parrilla serializada, recortada por la cola hasta caber en [MAX_BYTES].
     *
     * Se recorta **por la cola**: los primeros canales son los que el usuario
     * está mirando y los que tienen que seguir ahí al zapear. Si un solo canal
     * no cupiera (imposible con estos tamaños), devuelve la lista vacía y el
     * reproductor se queda sin zapeo en vez de con un extra corrupto.
     */
    fun encode(queue: List<PlaybackChannel>): String = runCatching {
        var slice = queue.take(MAX_CHANNEL_QUEUE)
        var text = json.encodeToString(serializer, slice)
        /* Se mide en **bytes UTF-8**, no en caracteres: un título con acentos
         * ocupa dos bytes por letra y el `Bundle` cuenta bytes. Quitar de golpe
         * el 10 % evita reescribir el JSON decenas de veces. */
        while (text.encodeToByteArray().size > MAX_BYTES && slice.size > 1) {
            val keep = maxOf(1, slice.size - maxOf(1, slice.size / 10))
            slice = slice.take(keep)
            text = json.encodeToString(serializer, slice)
        }
        if (text.encodeToByteArray().size > MAX_BYTES) "[]" else text
    }.getOrDefault("[]")

    /** Ante cualquier extra corrupto devuelve lista vacía: el vídeo sigue. */
    fun decode(raw: String?): List<PlaybackChannel> {
        val text = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())
    }
}
