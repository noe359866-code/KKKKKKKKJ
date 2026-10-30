package com.tanasi.streamflix.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.PlaybackChannel
import com.tanasi.streamflix.player.ChannelZapping
import com.tanasi.streamflix.ui.components.BackdropImage
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow
import kotlinx.coroutines.delay
import kotlin.math.max

/** Lo que el rótulo del zapeo se queda en pantalla. */
internal const val CHANNEL_BANNER_MS = 2_600L

/**
 * Retardo antes de sintonizar la tarjeta enfocada en la guía.
 *
 * Mantener `◀ ▶` pulsado recorre la parrilla a toda velocidad; sin este
 * respiro se pedirían las fuentes de los diez canales por los que se ha pasado
 * y el último tardaría en llegar.
 */
private const val GUIDE_TUNE_DEBOUNCE_MS = 260L

/**
 * Rótulo del cambio de canal.
 *
 * Es la pieza que hace que zapear *se sienta* como un televisor: aparece arriba,
 * translúcido, con el nombre del canal y su posición en la parrilla, y se va
 * solo. No es un diálogo: no roba el foco ni detiene el vídeo — el canal ya está
 * cargando detrás mientras se lee.
 *
 * @param position `"3 de 12"`, o `null` si el canal no está en la parrilla.
 */
@Composable
internal fun ChannelBanner(
    channel: PlaybackChannel,
    position: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(max = 760.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        StreamflixColors.Surface.copy(alpha = 0.96f),
                        StreamflixColors.Surface.copy(alpha = 0.80f),
                    ),
                ),
                RoundedCornerShape(18.dp),
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(horizontal = 26.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(StreamflixColors.Live, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CH",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (position != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "CANAL $position",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.4.sp,
                    color = StreamflixColors.OnSurfaceMuted,
                )
            }
        }
    }
}

/**
 * Guía de canales: la parrilla de la fila, dentro del reproductor.
 *
 * Se abre con `▲` o con el botón **Canales** de los controles y ocupa la franja
 * inferior, dejando el vídeo a la vista arriba (Apple TV: la guía nunca tapa la
 * emisión). Al recorrerla con `◀ ▶` el canal **cambia al instante** — no hay
 * "elegir y confirmar": en TV, moverse por la parrilla es sintonizar.
 *
 * `OK` cierra la guía y deja el canal enfocado; `ATRÁS` cierra y devuelve el
 * canal desde el que se abrió (lo resuelve `PlayerScreen`), para poder echar un
 * vistazo sin perder lo que se estaba viendo.
 */
@Composable
internal fun ChannelGuideOverlay(
    channels: List<PlaybackChannel>,
    currentId: String,
    onTune: (PlaybackChannel) -> Unit,
    onClose: () -> Unit,
) {
    val startIndex = remember(channels, currentId) {
        channels.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = max(0, startIndex - 1),
    )
    val focusTarget = remember { FocusRequester() }
    var focusedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focusTarget.requestFocus() }
    }

    /* Sólo sintoniza cuando el foco se **queda** en una tarjeta. */
    LaunchedEffect(focusedId) {
        val id = focusedId ?: return@LaunchedEffect
        if (id == currentId) return@LaunchedEffect
        delay(GUIDE_TUNE_DEBOUNCE_MS)
        channels.firstOrNull { it.id == id }?.let(onTune)
    }

    Box(Modifier.fillMaxSize()) {
        /* Velo sólo en la mitad inferior: arriba se sigue viendo el canal. */
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.42f to Color.Transparent,
                        0.72f to Color.Black.copy(alpha = 0.62f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 56.dp, end = 56.dp, bottom = 40.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "CANALES",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = Color.White.copy(alpha = 0.82f),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = ChannelZapping.positionLabel(channels, startIndex)
                        ?: "${channels.size} canales",
                    style = MaterialTheme.typography.labelMedium,
                    color = StreamflixColors.OnSurfaceMuted,
                )
            }

            Spacer(Modifier.height(16.dp))

            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(channels, key = { _, channel -> channel.id }) { _, channel ->
                    GuideCard(
                        channel = channel,
                        selected = channel.id == currentId,
                        onClick = onClose,
                        onFocused = { focusedId = channel.id },
                        focusRequester = if (channel.id == currentId) focusTarget else null,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                GuideHint(key = "◀ ▶", action = "Recorrer")
                GuideHint(key = "OK", action = "Ver")
                GuideHint(key = "ATRÁS", action = "Volver")
            }
        }
    }
}

/** Tarjeta 16:9 de la guía: arte del canal + nombre + marca de "en emisión". */
@Composable
private fun GuideCard(
    channel: PlaybackChannel,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "guideRing",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "guideScale",
    )

    Column(
        modifier = Modifier
            .width(236.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvFocusGlow(progress = ring, cornerRadius = 14.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(236.dp)
                .height(133.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(StreamflixColors.SurfaceVariant)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
            BackdropImage(
                url = channel.poster,
                entry = MediaEntry(
                    id = channel.id,
                    type = channel.type,
                    title = channel.title,
                ),
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(StreamflixColors.Live, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(Color.White, CircleShape),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "EN VIVO",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            /* El canal que se está viendo no necesita estado de foco para
             * reconocerse: lleva su nombre abajo, siempre. */
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GuideHint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.9f),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = action,
            style = MaterialTheme.typography.labelSmall,
            color = StreamflixColors.OnSurfaceMuted,
        )
    }
}
