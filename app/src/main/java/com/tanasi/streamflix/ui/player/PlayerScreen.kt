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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.core.LocalDeviceProfile
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.player.FailoverController
import com.tanasi.streamflix.player.FailoverPlanner
import com.tanasi.streamflix.player.FailoverState
import com.tanasi.streamflix.player.MediaSources
import com.tanasi.streamflix.ui.theme.Dimens
import com.tanasi.streamflix.ui.theme.StreamflixColors

/**
 * Pantalla de reproducción.
 *
 * Tres estados: cargando fuentes, reproduciendo con failover, o "sin fuentes".
 * El reproductor sólo se crea cuando hay algo que reproducir, y se libera en
 * `onDispose` — un `ExoPlayer` filtrado mantiene el decodificador de hardware
 * ocupado y la app acaba en negro.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    request: PlaybackRequest,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val current = state) {
            is PlayerUiState.Loading -> CenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = StreamflixColors.Primary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Buscando fuentes…",
                        color = StreamflixColors.OnSurfaceMuted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            is PlayerUiState.Unavailable -> CenterBox {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        text = current.message,
                        color = StreamflixColors.OnSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onBack) { Text("Volver") }
                        Button(onClick = { viewModel.load() }) { Text("Reintentar") }
                    }
                }
            }

            is PlayerUiState.Ready -> PlaybackSurface(
                sources = current.sources,
                request = request,
                onBack = onBack,
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Reproductor + failover                                                     */
/* -------------------------------------------------------------------------- */

@Composable
private fun PlaybackSurface(
    sources: List<StreamSource>,
    request: PlaybackRequest,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val deviceProfile = LocalDeviceProfile.current
    val scope = rememberCoroutineScope()

    /* `remember(sources)` y no `remember(Unit)`: si el ViewModel recarga y
     * devuelve otro array, el planificador y el reproductor se recrean. */
    val player: ExoPlayer = remember(sources) { MediaSources.buildPlayer(context, request.isLive) }
    val planner = remember(sources) { FailoverPlanner(sources) }
    val controller = remember(player, planner) {
        FailoverController(
            context = context,
            player = player,
            planner = planner,
            scope = scope,
        )
    }

    val failoverState by controller.state.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        controller.start()
        onDispose {
            controller.release()
            player.release()
        }
    }

    /* En VOD se reanuda donde se quedó el usuario tras un salto de servidor; en
     * directo se prefiere saltar al borde del vivo (comportamiento de ExoPlayer). */
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view -> view.player = player },
        )

        PlayerOverlay(
            title = request.title,
            isLive = request.isLive,
            failover = failoverState,
            deviceProfile = deviceProfile,
            onBack = onBack,
            onShowSources = { showSources = true },
            sourcesCount = sources.size,
        )

        if (failoverState.switching) {
            SwitchingBadge(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
            )
        }

        if (showSources) {
            SourcePickerDialog(
                sources = sources,
                current = failoverState.current,
                onDismiss = { showSources = false },
                onSelect = { index ->
                    showSources = false
                    controller.selectSource(index)
                },
            )
        }
    }
}

@Composable
private fun PlayerOverlay(
    title: String,
    isLive: Boolean,
    failover: FailoverState,
    deviceProfile: DeviceProfile,
    onBack: () -> Unit,
    onShowSources: () -> Unit,
    sourcesCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = deviceProfile.horizontalPaddingDp.dp,
                end = deviceProfile.horizontalPaddingDp.dp,
                top = if (deviceProfile.requiresDpadFocus) 28.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isLive) {
            Text(
                text = "EN VIVO",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .background(StreamflixColors.Live, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        failover.current?.let { source ->
            Text(
                text = "Fuente ${failover.position} de $sourcesCount · ${source.label}",
                style = MaterialTheme.typography.labelMedium,
                color = StreamflixColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
        }
        OverlayChip(
            text = "Fuentes",
            deviceProfile = deviceProfile,
            onClick = onShowSources,
        )
        Spacer(Modifier.width(8.dp))
        OverlayChip(
            text = "Volver",
            deviceProfile = deviceProfile,
            onClick = onBack,
        )
    }
}

/**
 * Botón del overlay del reproductor.
 *
 * En TV no hay ripple que delate el foco (no hay dedo): el chip dibuja el borde
 * rojo y crece un 6 % cuando el D-Pad lo alcanza. En móvil se queda en el fondo
 * translúcido de siempre.
 */
@Composable
private fun OverlayChip(
    text: String,
    deviceProfile: DeviceProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val isTv = deviceProfile.requiresDpadFocus

    val scale by animateFloatAsState(
        targetValue = if (isTv && focused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "chipScale",
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (isTv && focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "chipBorder",
    )
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(Color.White.copy(alpha = if (focused) 0.22f else 0.14f), shape)
            .border(
                width = Dimens.focusBorder,
                color = StreamflixColors.FocusRing.copy(alpha = borderAlpha),
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/**
 * Aviso discreto del salto: aparece abajo, sin modal ni diálogo, para que la
 * experiencia no se interrumpa. Dura lo que dura el `BUFFERING`.
 */
@Composable
private fun SwitchingBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = StreamflixColors.Primary,
            strokeWidth = 2.dp,
            modifier = Modifier.width(16.dp).height(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Cambiando de servidor…",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Lista de fuentes ordenadas por prioridad, elegible con D-Pad o dedo. */
@Composable
private fun SourcePickerDialog(
    sources: List<StreamSource>,
    current: StreamSource?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fuentes disponibles") },
        text = {
            Column {
                sources.forEachIndexed { index, source ->
                    val selected = source.url == current?.url
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}.",
                            color = if (selected) StreamflixColors.Primary else StreamflixColors.OnSurfaceMuted,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.width(28.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = source.label,
                                color = if (selected) StreamflixColors.Primary else StreamflixColors.OnSurface,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (source.isHls) "HLS · .m3u8" else "Progresivo",
                                color = StreamflixColors.OnSurfaceMuted,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
    )
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
