package com.tanasi.streamflix.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tanasi.streamflix.core.LocalDeviceProfile
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.player.FailoverController
import com.tanasi.streamflix.player.FailoverPlanner
import com.tanasi.streamflix.player.MediaSources
import com.tanasi.streamflix.player.PlayerKeyAction
import com.tanasi.streamflix.player.PlayerKeyMap
import com.tanasi.streamflix.player.SeekPolicy
import com.tanasi.streamflix.ui.theme.StreamflixColors
import kotlinx.coroutines.delay

/** Tiempo que los controles se quedan en pantalla sin que nadie toque el mando. */
private const val CONTROLS_AUTO_HIDE_MS = 4_000L

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
    /**
     * Posición alcanzada, justo antes de liberar el reproductor. Es lo que
     * alimenta "Seguir viendo" **del perfil activo**: sin esto, cada perfil
     * volvía a empezar la serie desde el principio.
     */
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
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
                onProgress = onProgress,
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Reproductor + failover + controles                                         */
/* -------------------------------------------------------------------------- */

@Composable
private fun PlaybackSurface(
    sources: List<StreamSource>,
    request: PlaybackRequest,
    onBack: () -> Unit,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
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

    /* --- estado de los controles (D-Pad y táctil) ------------------------- */
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    var rootSelfFocused by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableStateOf(0) }
    var pendingFocus by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    DisposableEffect(controller) {
        controller.start(startIndex = request.startIndex)
        onDispose {
            /* Antes de soltar el decodificador: después, `currentPosition` ya
             * es 0 y el progreso se perdería. */
            runCatching { onProgress(player.currentPosition.coerceAtLeast(0L), player.duration) }
            controller.release()
            player.release()
        }
    }

    /* El foco del D-Pad empieza en la superficie de vídeo: si no, el mando no
     * tiene dónde apuntar y las teclas se pierden. Se esperan dos frames porque
     * `LaunchedEffect` corre antes del layout y `requestFocus()` lanzaría
     * "FocusRequester is not initialized". */
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { rootFocus.requestFocus() }
    }

    /* `ABAJO` con los controles ocultos: se muestran y el foco salta al botón
     * central en cuanto existe en la composición. */
    LaunchedEffect(controlsVisible, pendingFocus) {
        if (pendingFocus && controlsVisible) {
            pendingFocus = false
            runCatching { playPauseFocus.requestFocus() }
        }
    }

    /* Sondeo del estado del reproductor: más fino con los controles a la vista
     * (la barra avanza suave), más barato cuando están ocultos. */
    LaunchedEffect(player, controlsVisible) {
        while (true) {
            isPlaying = player.isPlaying
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0L } ?: 0L
            delay(if (controlsVisible) 250L else 1_000L)
        }
    }

    /* Auto-ocultado: sólo si se está reproduciendo y el foco sigue en la
     * superficie. Si el usuario está navegando los botones, se quedan. */
    LaunchedEffect(controlsVisible, isPlaying, rootSelfFocused, interactionTick) {
        if (!controlsVisible || !isPlaying || !rootSelfFocused) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        runCatching { rootFocus.requestFocus() }
        controlsVisible = false
    }

    /* Al cerrar el diálogo de fuentes el foco se queda huérfano: vuelve al vídeo. */
    LaunchedEffect(showSources) {
        if (!showSources) runCatching { rootFocus.requestFocus() }
    }

    fun togglePlayPause() {
        /* `playWhenReady` y no `play()/pause()`: durante un buffer el usuario
         * también quiere poder cancelar la espera. */
        player.playWhenReady = !player.playWhenReady
        isPlaying = player.playWhenReady
        interactionTick++
    }

    fun seekBy(deltaMs: Long) {
        if (request.isLive) return
        val target = SeekPolicy.target(player.currentPosition, deltaMs, player.duration)
        player.seekTo(target)
        positionMs = target
        interactionTick++
    }

    fun showControls(focusPlayPause: Boolean) {
        interactionTick++
        controlsVisible = true
        if (focusPlayPause) pendingFocus = true else runCatching { rootFocus.requestFocus() }
    }

    fun hideControls() {
        runCatching { rootFocus.requestFocus() }
        controlsVisible = false
    }

    BackHandler {
        if (controlsVisible) hideControls() else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { rootSelfFocused = it.isFocused }
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                /* `onPreviewKeyEvent` en un ancestro del nodo enfocado se
                 * ejecuta antes que el botón: si el foco está en un control,
                 * el evento NO se roba (LEFT debe moverse entre botones). */
                if (event.type != KeyEventType.KeyDown || !rootSelfFocused) {
                    return@onPreviewKeyEvent false
                }
                when (PlayerKeyMap.actionFor(event.key)) {
                    PlayerKeyAction.TOGGLE_PLAY_PAUSE -> {
                        togglePlayPause()
                        true
                    }

                    PlayerKeyAction.SEEK_BACK -> {
                        showControls(focusPlayPause = false)
                        seekBy(-SeekPolicy.STEP_MS)
                        true
                    }

                    PlayerKeyAction.SEEK_FORWARD -> {
                        showControls(focusPlayPause = false)
                        seekBy(SeekPolicy.STEP_MS)
                        true
                    }

                    PlayerKeyAction.OPEN_CONTROLS -> {
                        showControls(focusPlayPause = true)
                        true
                    }

                    null -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    /* Los controles son de Compose ([PlayerControls]): el
                     * controlador de Media3 se apaga y la vista deja de ser
                     * enfocable, para que el D-Pad no se pierda dentro de un
                     * `FrameLayout` ajeno al grafo de Compose. */
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    isClickable = false
                }
            },
            update = { view -> view.player = player },
        )

        PlayerControls(
            title = request.title,
            isLive = request.isLive,
            failover = failoverState,
            sourcesCount = sources.size,
            visible = controlsVisible,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            deviceProfile = deviceProfile,
            playPauseFocusRequester = playPauseFocus,
            onPlayPause = { togglePlayPause() },
            onRewind = { seekBy(-SeekPolicy.STEP_MS) },
            onForward = { seekBy(SeekPolicy.STEP_MS) },
            onShowSources = { showSources = true },
            onBack = onBack,
            onInteraction = { interactionTick++ },
        )

        if (failoverState.switching) {
            /* Por encima de los controles cuando están a la vista; pegado al
             * borde cuando no, para no tapar la barra de progreso. */
            SwitchingBadge(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (controlsVisible) 196.dp else 24.dp),
            )
        }

        /* Sin este aviso, cuando todas las fuentes fallaban la pantalla se
         * quedaba en negro: parecía que la app "no reproduce nada" y no había
         * forma de saber por qué ni de reintentar. */
        val failure = when {
            failoverState.fatalMessage != null ->
                "El reproductor rechazó el vídeo (${failoverState.fatalMessage})."
            failoverState.exhausted ->
                "Ninguna de las ${sources.size} fuentes respondió." +
                    (failoverState.lastError?.let { " Último error: $it." } ?: "")
            else -> null
        }
        if (failure != null) {
            PlaybackFailureOverlay(
                title = request.title,
                message = failure,
                onRetry = { controller.selectSource(0) },
                onPickSource = { showSources = true },
                onBack = onBack,
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

/**
 * Aviso a pantalla completa cuando no hay nada que reproducir.
 *
 * Tres salidas: reintentar desde la primera fuente, abrir la lista de fuentes
 * (para elegir otra a mano) o volver al catálogo. La primera opción recibe el
 * foco, así que con el mando basta pulsar OK para reintentar.
 */
@Composable
private fun PlaybackFailureOverlay(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onPickSource: () -> Unit,
    onBack: () -> Unit,
) {
    val retryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { retryFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(40.dp),
        ) {
            Text(
                text = "No se pudo reproducir",
                style = MaterialTheme.typography.headlineSmall,
                color = StreamflixColors.OnSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = StreamflixColors.OnSurfaceMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = StreamflixColors.OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(retryFocus),
                ) { Text("Reintentar") }
                OutlinedButton(onClick = onPickSource) { Text("Elegir fuente") }
                OutlinedButton(onClick = onBack) { Text("Volver") }
            }
        }
    }
}

/** Lista de fuentes ordenadas por prioridad, elegible con el D-Pad. */
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
