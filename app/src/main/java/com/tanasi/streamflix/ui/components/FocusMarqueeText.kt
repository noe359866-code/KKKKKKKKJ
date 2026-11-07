package com.tanasi.streamflix.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Si un título necesita desplazarse para leerse entero.
 *
 * En una TV el título de la tarjeta va a un renglón y con puntos suspensivos: un
 * nombre largo, o un episodio con su temporada, se queda en "La casa de papel…"
 * justo cuando el usuario lo está mirando con el mando. Desplazarlo resuelve el
 * caso, pero sólo mientras la tarjeta tiene el foco: si se moviera siempre,
 * diez tarjetas moviéndose a la vez convierten la fila en ruido.
 *
 * La comparación es estricta (`>`) y con un margen de 1 px. Medir y pintar no
 * dan exactamente el mismo número y el error va en ambos sentidos; sin margen,
 * un título justo en el límite entraría y salir del desplazamiento con cada
 * recomposición.
 *
 * @param textWidthPx ancho medido del texto.
 * @param availableWidthPx ancho del hueco donde se pinta.
 */
internal fun shouldMarquee(textWidthPx: Int, availableWidthPx: Int): Boolean =
    availableWidthPx > 0 && textWidthPx > availableWidthPx + 1

/**
 * Un renglón de texto que se desplaza **sólo si está enfocado y no cabe**.
 *
 * Es el mismo contrato que el `Text` de siempre —quien lo llama decide el
 * estilo— para que sustituirlo en una tarjeta no cambie el diseño: sin foco, o
 * si el texto cabe, se comporta exactamente igual (recorte con puntos
 * suspensivos).
 *
 * Se mide dos veces y por motivos distintos:
 *  - el texto, con el `TextMeasurer` y el ancho disponible, para saber si
 *    desborda;
 *  - el hueco, con `onSizeChanged`, porque el ancho depende de la tarjeta y del
 *    perfil de pantalla, no sólo del texto.
 *
 * Hasta que llega la primera medida no se desplaza nada: arrancar el marquee y
 * quitarlo un fotograma después se ve como un parpadeo.
 */
@Composable
internal fun FocusMarqueeText(
    text: String,
    focused: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var availableWidthPx by remember(text) { mutableStateOf(0) }
    val overflowing = remember(text, availableWidthPx, style) {
        if (availableWidthPx <= 0) {
            false
        } else {
            val measured = measurer.measure(
                text = text,
                style = style,
                maxLines = 1,
                softWrap = false,
            )
            shouldMarquee(measured.size.width, availableWidthPx)
        }
    }
    val marquee = focused && overflowing

    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                val widthPx = with(density) { size.width }
                if (widthPx != availableWidthPx) availableWidthPx = widthPx
            }
            .then(
                if (marquee) {
                    Modifier.basicMarquee(
                        iterations = MARQUEE_ITERATIONS,
                        velocity = MARQUEE_VELOCITY,
                    )
                } else {
                    Modifier
                },
            ),
    )
}

/**
 * Tres vueltas y se detiene: suficiente para leer un título largo sin dejar la
 * fila en movimiento indefinidamente mientras el usuario decide.
 */
private const val MARQUEE_ITERATIONS = 3

/**
 * 40 dp/s. Más rápido se lee mal a tres metros; el valor es deliberadamente
 * menor que el de una cinta de noticias.
 */
private val MARQUEE_VELOCITY = 40.dp
