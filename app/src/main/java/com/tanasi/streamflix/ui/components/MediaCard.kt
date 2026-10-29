package com.tanasi.streamflix.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.MediaType
import com.tanasi.streamflix.ui.theme.Dimens
import com.tanasi.streamflix.ui.theme.StreamflixColors

/**
 * Tarjeta de catálogo, compartida por TV y móvil.
 *
 * Diferencias por perfil:
 *  - **TV**: el foco del D-Pad dibuja un borde rojo, agranda la tarjeta un 8 % y
 *    eleva la sombra. Sin eso el usuario no sabe dónde está parado.
 *  - **Móvil**: `clickable` con ripple, sin escala (el dedo ya señala).
 *
 * `onFocusChanged` va **antes** de `clickable`: el modificador de foco tiene que
 * envolver al nodo enfocable para recibir los eventos.
 */
@Composable
fun MediaCard(
    entry: MediaEntry,
    deviceProfile: DeviceProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val isTv = deviceProfile.requiresDpadFocus

    val scale by animateFloatAsState(
        targetValue = if (isTv && focused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "cardScale",
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (isTv && focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "cardBorder",
    )

    val shape = RoundedCornerShape(Dimens.cardCornerRadius)

    Column(
        modifier = modifier
            .width(deviceProfile.cardWidthDp.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                /* Elevación real: la tarjeta enfocada "se separa" del fondo. */
                shadowElevation = if (isTv && focused) 16.dp.toPx() else 0f
                this.shape = shape
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(deviceProfile.cardHeightDp.dp)
                .clip(shape)
                .background(StreamflixColors.PosterPlaceholder)
                .border(
                    width = Dimens.focusBorder,
                    color = StreamflixColors.FocusRing.copy(alpha = borderAlpha),
                    shape = shape,
                ),
        ) {
            if (entry.poster.isNullOrBlank()) {
                /* Sin póster: degradado + título, nunca un hueco gris vacío. */
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(StreamflixColors.SurfaceVariant, StreamflixColors.Background),
                            ),
                        ),
                )
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = StreamflixColors.OnSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(12.dp),
                )
            } else {
                AsyncImage(
                    model = entry.poster,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (entry.mediaType == MediaType.Live) {
                LiveBadge(modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }

        Text(
            text = entry.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (isTv && focused) Color.White else StreamflixColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            text = entry.subtitle ?: entry.year?.toString().orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = StreamflixColors.OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(StreamflixColors.Live)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "EN VIVO",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

/**
 * Un carrusel del Home: título + `LazyRow` de tarjetas.
 *
 * @param firstItemFocusRequester en TV, pide el foco para la primera tarjeta de
 *   la fila (lo usa [com.tanasi.streamflix.ui.home.HomeScreen] sólo en la fila
 *   superior, para que el D-Pad tenga un punto de partida al abrir la app).
 */
@Composable
fun MediaRow(
    title: String,
    items: List<MediaEntry>,
    deviceProfile: DeviceProfile,
    onItemClick: (MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = StreamflixColors.OnSurface,
            modifier = Modifier.padding(
                start = deviceProfile.horizontalPaddingDp.dp,
                bottom = 12.dp,
            ),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = deviceProfile.horizontalPaddingDp.dp),
        ) {
            itemsIndexed(items) { index, entry ->
                MediaCard(
                    entry = entry,
                    deviceProfile = deviceProfile,
                    onClick = { onItemClick(entry) },
                    focusRequester = if (index == 0) firstItemFocusRequester else null,
                )
            }
        }
    }
}
