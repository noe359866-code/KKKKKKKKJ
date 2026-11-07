package com.tanasi.streamflix.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Lee las respuestas de las dos bases públicas de marcas de tiempo.
 *
 * Son **tres dialectos** para la misma idea, y ninguno es un superconjunto de
 * los otros:
 *
 *  - **TheIntroDB v4** (`/v4/media`): cada tipo es una **lista** de tramos, en
 *    **milisegundos**, y sus campos se llaman `start_ms`/`end_ms`. `credits`
 *    es el final; `preview` es el avance del próximo capítulo.
 *  - **TheIntroDB v2/v3**: idéntico a v4 pero con la respuesta encabezada por
 *    `tmdb_id` en vez de `tvdb_id`. Los tramos se leen igual.
 *  - **IntroDB** (`/segments`): cada tipo es **un objeto** (o `null`), en
 *    **segundos y milisegundos a la vez**, y el final se llama `outro`. Además
 *    trae banderas `no_intro` / `no_recap` / `no_outro` que dicen "la comunidad
 *    confirmó que este capítulo no tiene ese tramo".
 *
 * Se aceptan los tres con el mismo código porque los identificadores de
 * arranque del campo son inconfundibles: `start_ms` en unos, `start_sec` en
 * otro. Un tipo ausente, `null` o con las dos puntas a `null` significa "no hay
 * nada que saltar" y no llega a [SkipSegment].
 *
 * Las banderas `no_*` se ignoran a propósito: para quien reproduce, "no hay
 * intro" y "todavía nadie la ha medido" se tratan igual, es decir, no se
 * ofrece botón. Distinguirlas no cambiaría ni un píxel de la pantalla.
 */
internal object SkipSegmentParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @param raw cuerpo de la respuesta HTTP.
     * @param durationMs duración real del vídeo, para cerrar los tramos que
     *   terminan en "hasta el final" (`end_ms: null`). Con `0` esos tramos se
     *   descartan, porque sin duración no se sabe adónde saltar.
     */
    fun parse(raw: String, durationMs: Long): List<SkipSegment> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyList()
        val segments = mutableListOf<SkipSegment>()

        // Nombres de cada tipo según el dialecto. `outro` es como llama IntroDB
        // a los créditos; `credits`, como los llama TheIntroDB.
        segments += read(root, "intro", SkipSegmentType.Intro, durationMs)
        segments += read(root, "recap", SkipSegmentType.Recap, durationMs)
        segments += read(root, "outro", SkipSegmentType.Credits, durationMs)
        segments += read(root, "credits", SkipSegmentType.Credits, durationMs)

        // Un mismo tipo puede llegar por dos claves (nunca debería, pero si el
        // servidor cambiara a mitad de camino es mejor no duplicar el botón).
        return segments
            .filter { it.isUsable }
            .distinctBy { it.type }
            .sortedBy { it.startMs }
    }

    private fun read(
        root: JsonObject,
        key: String,
        type: SkipSegmentType,
        durationMs: Long,
    ): List<SkipSegment> {
        val value: JsonElement = root[key] ?: return emptyList()
        if (value is kotlinx.serialization.json.JsonNull) return emptyList()
        val items = if (value is JsonArray) value.toList() else listOf(value)
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val start = obj.millis("start_ms", "start_sec") ?: 0L
            /* `end` a null significa "hasta el final del vídeo" en los
             * créditos; en intro/recap es un dato incompleto. */
            val end = obj.millis("end_ms", "end_sec")
                ?: durationMs.takeIf { it > 0L }
                ?: return@mapNotNull null
            SkipSegment(type = type, startMs = start.coerceAtLeast(0L), endMs = end.coerceAtLeast(0L))
        }
    }

    /**
     * Lee un instante en milisegundos, prefiriendo la clave en milisegundos.
     *
     * Acepta número y también cadena: IntroDB documenta `start_sec` como
     * "number | string" en el envío, y una respuesta con `"58"` en vez de `58`
     * no debe tirar el tramo entero.
     */
    private fun JsonObject.millis(msKey: String, secKey: String): Long? {
        this[msKey]?.asDouble()?.let { return (it).toLong() }
        this[secKey]?.asDouble()?.let { return (it * 1000.0).toLong() }
        return null
    }

    private fun JsonElement.asDouble(): Double? = when (this) {
        is JsonPrimitive -> doubleOrNull
        else -> null
    }

}
