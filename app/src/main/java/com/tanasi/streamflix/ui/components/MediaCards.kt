package com.tanasi.streamflix.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.ui.theme.Dimens
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/**
 * Tarjeta 2:3 (películas, series, anime).
 *
 * Anatomía:
 * ```
 * ┌──────────────────┐
 * │ EN VIVO     4K   │  ← insignias flotantes
 * │                  │
 * │      póster      │
 * │                  │
 * │ ▓▓▓▓▓░░░░░░░░░░░ │  ← progreso (sólo "Continuar viendo")
 * │ PelisPlus        │  ← proveedor / servidor previsto
 * └──────────────────┘
 *   Título            ← aparece al enfocar con el D-Pad
 *   T1 E3 · 62 %
 * ```
 *
 * La tarjeta enfocada escala **1.08x**, se eleva y dibuja el anillo neón para
 * que el cursor del mando se lea desde el sofá.
 *
 * @param onFocused se llama al entrar en foco; el Home lo usa para cambiar el
 *   backdrop del hero (Apple TV+). Sólo se dispara al *entrar*, no en cada
 *   recomposición.
 * @param focusUp petición de foco al pulsar ARRIBA: la usa la primera fila para
 *   saltar a los botones del hero.
 */
@Composable
fun PosterCard(
    entry: MediaEntry,
    deviceProfile: DeviceProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (MediaEntry) -> Unit = {},
    focusUp: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
) {
    ContentCard(
        entry = entry,
        deviceProfile = deviceProfile,
        onClick = onClick,
        modifier = modifier,
        onFocused = onFocused,
        focusUp = focusUp,
        focusRequester = focusRequester,
        widthDp = deviceProfile.posterWidthDp,
        aspectRatio = 2f / 3f,
    ) {
        PosterImage(entry = entry, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Tarjeta 16:9 (TV en vivo, deportes, continuar viendo).
 *
 * Mismo esqueleto que [PosterCard]; sólo cambian la proporción y la imagen de
 * fondo, que aquí puede venir del catálogo o del arte local.
 */
@Composable
fun LandscapeCard(
    entry: MediaEntry,
    deviceProfile: DeviceProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (MediaEntry) -> Unit = {},
    focusUp: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
) {
    ContentCard(
        entry = entry,
        deviceProfile = deviceProfile,
        onClick = onClick,
        modifier = modifier,
        onFocused = onFocused,
        focusUp = focusUp,
        focusRequester = focusRequester,
        widthDp = deviceProfile.landscapeWidthDp,
        aspectRatio = 16f / 9f,
    ) {
        BackdropImage(
            url = entry.backdrop ?: entry.poster,
            artKey = entry.art,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Esqueleto compartido. Recibe el arte como contenido para que las dos
 * proporciones no dupliquen insignias, foco, progreso y título.
 */
@Composable
private fun ContentCard(
    entry: MediaEntry,
    deviceProfile: DeviceProfile,
    onClick: () -> Unit,
    modifier: Modifier,
    onFocused: (MediaEntry) -> Unit,
    focusUp: FocusRequester?,
    focusRequester: FocusRequester?,
    widthDp: Int,
    aspectRatio: Float,
    artwork: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val isTv = deviceProfile.requiresDpadFocus

    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "cardFocus",
    )
    val scale by animateFloatAsState(
        targetValue = if (isTv && focused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "cardScale",
    )

    val shape = RoundedCornerShape(Dimens.cardCornerRadius)

    Column(
        modifier = modifier
            .width(widthDp.dp)
            /* El foco se pide antes que el clic para envolver al nodo enfocable. */
            .then(if (focusUp != null) Modifier.focusProperties { up = focusUp } else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (nowFocused && !focused) onFocused(entry)
                focused = nowFocused
            }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    /* Elevación real: la tarjeta enfocada "flota" sobre la fila. */
                    shadowElevation = if (isTv && focused) 20.dp.toPx() else 0f
                    this.shape = shape
                }
                .tvFocusGlow(progress = focusProgress, cornerRadius = Dimens.cardCornerRadius)
                .clip(shape)
                .background(StreamflixColors.PosterPlaceholder),
        ) {
            artwork()

            /* Velo inferior: garantiza contraste de las insignias sobre
             * cualquier póster (muchos son claros en la zona baja). */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
            )

            EntryTopStartBadge(
                entry = entry,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )
            EntryTopEndBadge(
                entry = entry,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )

            entry.provider?.let { provider ->
                GlassBadge(
                    text = provider,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                )
            }

            entry.progressPercent?.let { progress ->
                ProgressUnderline(
                    percent = progress,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        /* Título bajo la tarjeta: aparece con el foco del D-Pad (Netflix). */
        Text(
            text = entry.title,
            style = MaterialTheme.typography.labelLarge,
            color = StreamflixColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 8.dp, start = 2.dp, end = 2.dp)
                .graphicsLayer { alpha = if (isTv) focusProgress else 1f },
        )
        Text(
            text = entry.subtitle ?: entry.year?.toString().orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = StreamflixColors.OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 2.dp, end = 2.dp)
                .graphicsLayer { alpha = if (isTv) focusProgress * 0.9f else 1f },
        )
    }
}

/** Barra de progreso pegada al borde inferior de la tarjeta. */
@Composable
private fun ProgressUnderline(percent: Int, modifier: Modifier = Modifier) {
    val fraction = percent.coerceIn(0, 100) / 100f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.55f)),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(StreamflixColors.Primary),
            )
        }
    }
}
