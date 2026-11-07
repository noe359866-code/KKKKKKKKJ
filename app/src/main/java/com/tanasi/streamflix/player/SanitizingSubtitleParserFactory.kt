package com.tanasi.streamflix.player

import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.tanasi.streamflix.data.model.TextSanitizer

/**
 * Arregla los subtítulos que llegan mal decodificados.
 *
 * **El problema.** Muchos `.srt`/`.vtt` de los proveedores están guardados en
 * UTF-8 pero se sirven con `charset=iso-8859-1` o sin `charset`. Media3 lee los
 * bytes con la codificación que le digan y el texto sale con cada acento
 * destrozado: `Donâ€™t` en vez de `Don’t`, `Ã±` en vez de `ñ`. En el título de
 * una ficha se puede sobrevivir; en un subtítulo tapa la imagen y no hay nada
 * que el espectador pueda hacer.
 *
 * **Por qué aquí y no en un `TextOutput`.** Un `TextOutput` recibe los cues ya
 * decodificados, pero *registrar* uno no sustituye al del `PlayerView`: son
 * observadores independientes (así lo documenta Media3 desde 2.17, «allow
 * multiple listeners»). Poner uno propio para arreglar el texto habría
 * duplicado los subtítulos en pantalla, no corregido la codificación.
 *
 * El `SubtitleParser` es donde el `String` se construye a partir de los bytes,
 * así que es el último sitio donde el arreglo llega a la pantalla. Además
 * ocurre **antes** de que el texto se convierta en `SpannableStringBuilder`
 * con los spans de estilo, así que no hay que remapear índices de spans: se
 * arregla el cue entero y Media3 sigue su curso normal.
 *
 * Se envuelve la fábrica por defecto en vez de reimplementar los parsers: el
 * día que Media3 añada un formato, esto lo soporta sin tocar nada. Que
 * [supportsFormat] delegue no es un detalle: si devolviera `false`,
 * `DefaultMediaSourceFactory` **no** se quedaría sin subtítulos, construiría
 * un `UnknownSubtitlesExtractor` que los descarta en silencio. Ese es el fallo
 * que hay que evitar aquí, y sólo se detecta mirando el valor de retorno.
 *
 * Cubre los cuatro formatos que publican nuestros proveedores (`SubtitleTrack`
 * manda `text/vtt`, `application/x-subrip`, `text/x-ssa` y
 * `application/ttml+xml`); los de imagen (PGS, VobSub, DVB) no llevan texto que
 * reparar y pasan tal cual.
 */
@UnstableApi
internal object SanitizingSubtitleParserFactory : SubtitleParser.Factory {

    private val delegate = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser =
        SanitizingParser(delegate.create(format))

    /** Envuelve un parser y repara el texto de cada cue que produce. */
    private class SanitizingParser(
        private val delegate: SubtitleParser,
    ) : SubtitleParser {

        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            delegate.parse(data, offset, length, outputOptions) { cues ->
                output.accept(cues.sanitized())
            }
        }

        /** El parser es con estado: tras un salto hay que reiniciarlo. */
        override fun reset() = delegate.reset()
    }
}

/**
 * Repara los cues de un bloque, dejando los que no cambian intactos.
 *
 * `Cue.buildUpon()` es la única forma de cambiar el texto conservando la
 * colocación y el estilo que trae el archivo (`Cue.position`, `line`, `size`).
 */
@UnstableApi
private fun CuesWithTiming.sanitized(): CuesWithTiming {
    var changed = false
    val repaired: List<Cue> = cues.map { cue ->
        val fixed = cue.sanitized()
        if (fixed !== cue) changed = true
        fixed
    }
    /* Si nada cambió se devuelve el mismo objeto: los subtítulos llegan muchas
     * veces por segundo y no hace falta crear basura para nada. */
    return if (changed) CuesWithTiming(repaired, startTimeUs, durationUs) else this
}

@UnstableApi
private fun Cue.sanitized(): Cue {
    val current = text ?: return this
    val fixed = TextSanitizer.repair(current.toString()) ?: return this
    if (fixed == current.toString()) return this
    return buildUpon().setText(fixed).build()
}
