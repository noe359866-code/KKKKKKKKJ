package com.tanasi.streamflix.player

import android.os.Looper
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import com.tanasi.streamflix.data.model.TextSanitizer

/**
 * Repara los subtítulos que llegan con la codificación rota.
 *
 * **El problema.** Muchos `.srt`/`.vtt` de los proveedores están en UTF-8 pero
 * se sirven con `charset=iso-8859-1` o sin `charset`. Media3 lee los bytes con
 * la codificación que le digan y el texto sale con cada acento destrozado:
 * `Donâ€™t` en vez de `Don’t`, `Â¡` en vez de `¡`. En el título de una ficha se
 * puede sobrevivir; en un subtítulo tapa la imagen y no hay nada que el
 * espectador pueda hacer.
 *
 * **Por qué aquí.** `TextRenderer` recibe su `TextOutput` en el constructor y
 * `DefaultRenderersFactory.buildTextRenderers` es el método (protegido) que lo
 * construye. Envolviendo ese `TextOutput` se intercepta el cue **antes** de que
 * llegue a ningún `PlayerView` ni a ningún `Player.Listener`: no hay dos rutas
 * y no hay que tocar la vista.
 *
 * **Lo que no funciona** (comprobado en el código de Media3, no supuesto):
 * registrar un `TextOutput` con `Player.addTextOutput` **no** sustituye al que
 * usa el `PlayerView` —desde 2.17 se permiten varios listeners y cada uno
 * recibe los cues por su cuenta—, así que habría duplicado los subtítulos en
 * pantalla en vez de arreglarlos.
 */
@UnstableApi
internal class SanitizingRenderersFactory(
    context: android.content.Context,
) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(
        context: android.content.Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        super.buildTextRenderers(
            context,
            SanitizingTextOutput(output),
            outputLooper,
            extensionRendererMode,
            out,
        )
    }
}

/**
 * Envuelve el `TextOutput` del `TextRenderer` y delega todo menos el texto.
 *
 * Se implementan **las dos** sobrecargas de `onCues` porque `TextRenderer` llama
 * a ambas por compatibilidad hacia atrás (así está en su `onCues`): quedarse
 * con una dejaría la otra sin reparar.
 */
@UnstableApi
internal class SanitizingTextOutput(
    private val delegate: TextOutput,
) : TextOutput {

    override fun onCues(cueGroup: CueGroup) {
        delegate.onCues(cueGroup.sanitized())
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCues(cues: List<Cue>) {
        val result = cues.sanitize()
        delegate.onCues(if (result.changed) result.cues else cues)
    }
}

/**
 * Repara el texto de cada cue dejando intactos los que no cambian.
 *
 * `Cue.buildUpon()` es la única forma de cambiar el texto conservando la
 * colocación y el estilo que trae el archivo (`Cue.position`, `line`, `size`):
 * reasignar el `Cue` a mano perdería el sitio donde va.
 */
@UnstableApi
private fun Cue.sanitized(): Cue {
    val current = text ?: return this
    val repaired = TextSanitizer.repair(current.toString())
    if (repaired == null || repaired == current.toString()) return this
    return buildUpon().setText(repaired).build()
}

/**
 * Resultado de sanear una lista: los cues y si alguno cambió.
 *
 * Se devuelve un par en vez de comparar identidades porque `map` siempre crea
 * una lista nueva y `List`/`MutableList` no son el mismo tipo: comparar
 * referencias aquí sería frágil y no compilaría.
 */
@UnstableApi
private class SanitizedCues(val cues: MutableList<Cue>, val changed: Boolean)

@UnstableApi
private fun List<Cue>.sanitize(): SanitizedCues {
    var changed = false
    val mapped = map { cue ->
        val fixed = cue.sanitized()
        if (fixed !== cue) changed = true
        fixed
    }
    return SanitizedCues(mapped.toMutableList(), changed)
}

@UnstableApi
private fun CueGroup.sanitized(): CueGroup {
    val result = cues.sanitize()
    return if (result.changed) CueGroup(result.cues, presentationTimeUs) else this
}
