package com.tanasi.streamflix.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/** A D-Pad shelf of only the platform catalogs actually declared by addons. */
@Composable
internal fun PlatformShelf(
    platforms: List<AvailableStreamingPlatform>,
    activePlatformId: String?,
    deviceProfile: DeviceProfile,
    focusRequesters: Map<String, FocusRequester>,
    contentFocusRequester: FocusRequester?,
    heroFocusRequester: FocusRequester,
    onPlatformSelected: (AvailableStreamingPlatform) -> Unit,
    onOpenNavigation: () -> Unit,
) {
    var lastSelectedPlatformId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activePlatformId, focusRequesters) {
        if (activePlatformId != null) {
            lastSelectedPlatformId = activePlatformId
        } else {
            val restore = lastSelectedPlatformId
            if (restore != null) {
                withFrameNanos { }
                withFrameNanos { }
                runCatching { focusRequesters[restore]?.requestFocus() }
                lastSelectedPlatformId = null
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = deviceProfile.horizontalPaddingDp.dp,
                    end = deviceProfile.horizontalPaddingDp.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "Plataformas",
                    style = MaterialTheme.typography.titleLarge,
                    color = StreamflixColors.OnSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (activePlatformId == null) {
                        "Explora catálogos por servicio"
                    } else {
                        "Atrás para volver a tus plataformas"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamflixColors.OnSurfaceMuted,
                )
            }
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = "${platforms.size} ${if (platforms.size == 1) "SERVICIO" else "SERVICIOS"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StreamflixColors.OnSurfaceMuted,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        LazyRow(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(
                start = deviceProfile.horizontalPaddingDp.dp,
                end = deviceProfile.horizontalPaddingDp.dp,
                top = 8.dp,
                bottom = 14.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(platforms, key = { _, item -> item.platform.id }) { index, available ->
                PlatformTile(
                    available = available,
                    isFirst = index == 0,
                    selected = available.platform.id == activePlatformId,
                    focusRequester = focusRequesters.getValue(available.platform.id),
                    focusUp = heroFocusRequester,
                    focusDown = contentFocusRequester,
                    onOpenNavigation = onOpenNavigation,
                    onClick = { onPlatformSelected(available) },
                )
            }
        }
    }
}

@Composable
private fun PlatformTile(
    available: AvailableStreamingPlatform,
    isFirst: Boolean,
    selected: Boolean,
    focusRequester: FocusRequester,
    focusUp: FocusRequester,
    focusDown: FocusRequester?,
    onOpenNavigation: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.045f else 1f,
        animationSpec = tween(140),
        label = "platformTileFocus",
    )
    val shape = RoundedCornerShape(18.dp)
    val hasMovies = available.catalogs.any { it.type.equals("movie", true) }
    val hasSeries = available.catalogs.any { it.type.equals("series", true) }
    val hasAnime = available.catalogs.any { it.type.equals("anime", true) }
    val mediaLabel = when {
        hasMovies && (hasSeries || hasAnime) -> "Películas y series"
        hasMovies -> "Películas"
        hasSeries && hasAnime -> "Series y anime"
        hasAnime -> "Anime"
        else -> "Series"
    }
    val accent = available.platform.accent

    Row(
        modifier = Modifier
            .width(204.dp)
            .height(88.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(focusRequester)
            .focusProperties {
                up = focusUp
                if (focusDown != null) down = focusDown
            }
            .tvFocusGlow(progress = if (focused) 1f else 0f, cornerRadius = 18.dp)
            .background(
                brush = Brush.horizontalGradient(
                    if (focused) {
                        listOf(Color.White, Color(0xFFF0EEF5))
                    } else {
                        listOf(accent.copy(alpha = if (selected) 0.28f else 0.16f), StreamflixColors.SurfaceVariant)
                    },
                ),
                shape = shape,
            )
            .border(
                width = 1.dp,
                color = when {
                    focused -> Color.White
                    selected -> accent.copy(alpha = 0.9f)
                    else -> accent.copy(alpha = 0.32f)
                },
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && isFirst) {
                    onOpenNavigation()
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick)
            .semantics {
                this.selected = selected
                role = Role.Button
                contentDescription = "${available.platform.label}. $mediaLabel. ${available.catalogs.size} catálogos disponibles"
            }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.12f)),
                    ),
                    shape = RoundedCornerShape(15.dp),
                )
                .border(1.dp, accent.copy(alpha = if (focused) 0.22f else 0.42f), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = available.platform.monogram,
                color = accent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = available.platform.label,
                color = if (focused) Color.Black else StreamflixColors.OnSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${available.catalogs.size} catálogos · $mediaLabel",
                color = if (focused) Color.Black.copy(alpha = 0.68f) else StreamflixColors.OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Purpose-built loading/empty card; focus stays on the selected platform tile. */
@Composable
internal fun PlatformBrowseStatus(
    platform: StreamingPlatform,
    isLoading: Boolean,
    message: String?,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .widthIn(min = 260.dp, max = 500.dp)
            .background(
                Brush.linearGradient(
                    listOf(platform.accent.copy(alpha = 0.18f), StreamflixColors.SurfaceVariant),
                ),
                shape,
            )
            .border(1.dp, platform.accent.copy(alpha = 0.42f), shape)
            .padding(horizontal = 34.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .background(platform.accent.copy(alpha = 0.15f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = platform.monogram,
                color = platform.accent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(66.dp),
                    color = platform.accent,
                    strokeWidth = 2.dp,
                )
            }
        }
        Text(
            text = if (isLoading) "Buscando en ${platform.label}" else "Sin títulos disponibles",
            style = MaterialTheme.typography.titleLarge,
            color = StreamflixColors.OnSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message ?: "Consultando los catálogos habilitados…",
            style = MaterialTheme.typography.bodyMedium,
            color = StreamflixColors.OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Pulsa Atrás para elegir otra plataforma",
            style = MaterialTheme.typography.labelMedium,
            color = platform.accent,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}
