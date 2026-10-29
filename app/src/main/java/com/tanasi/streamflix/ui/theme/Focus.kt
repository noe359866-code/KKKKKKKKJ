package com.tanasi.streamflix.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * "Selector de foco" de Android TV, en Compose.
 *
 * En una app basada en `View` esto serían dos `res/drawable` gemelos
 * (`background.xml` y `background_focused.xml`) metidos en
 * `res/color/selector.xml`, más `android:focusable="true"` en cada tarjeta.
 * Compose no tiene estados XML: el foco es un dato (`onFocusChanged`) y el
 * aspecto se deriva de él, así que el equivalente vive aquí, en un único
 * modificador reutilizable:
 *
 * ```
 * android:background="@color/card_focus_selector"   →  .tvFocusGlow(progress)
 * android:focusable="true"                          →  .clickable { … }   (ya enfocable)
 * ```
 *
 * @param progress 0 = sin foco, 1 = foco pleno. Se anima en el llamador con
 *   `animateFloatAsState` para que el borde no aparezca de golpe.
 */
fun Modifier.tvFocusGlow(
    progress: Float,
    cornerRadius: Dp = Dimens.cardCornerRadius,
    ringWidth: Dp = Dimens.focusBorder,
    color: Color = StreamflixColors.FocusRing,
): Modifier {
    if (progress <= 0.01f) return this
    val alpha = progress.coerceIn(0f, 1f)

    return drawBehind {
        val radius = cornerRadius.toPx()
        val stroke = ringWidth.toPx()

        /* Halo: tres trazos concéntricos, cada vez más anchos y más tenues.
         * Es el "brillo" que en TV delata dónde está el cursor a 3 metros. */
        repeat(3) { layer ->
            val spread = stroke * (layer + 1) * 1.4f
            drawRoundRect(
                color = color.copy(alpha = 0.20f * alpha / (layer + 1)),
                topLeft = Offset(-spread / 2f, -spread / 2f),
                size = Size(size.width + spread, size.height + spread),
                cornerRadius = CornerRadius(radius + spread / 2f),
                style = Stroke(width = stroke),
            )
        }

        /* Anillo nítido pegado al borde del componente. */
        drawRoundRect(
            color = color.copy(alpha = alpha),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = stroke),
        )
    }
}
