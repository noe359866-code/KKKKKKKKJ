package com.tanasi.streamflix.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
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
import androidx.media3.common.C
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
import com.tanasi.streamflix.ui.theme.tvFocusGlow
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

            /* Sin fuentes no hay vídeo, pero tampoco una pantalla vacía: mismo
             * panel de vidrio que el aviso de fallo, con el foco en Reintentar. */
            is PlayerUiState.Unavailable -> CenterBox {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .background(StreamflixColors.Surface, RoundedCornerShape(20.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 40.dp, vertical = 34.dp),
                ) {
                    Text(
                        text = "Nada que reproducir",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = current.message,
                        color = StreamflixColors.OnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(26.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlayerPillButton(text = "Reintentar", primary = true, onClick = { viewModel.load() })
                        PlayerPillButton(text = "Volver", onClick = onBack)
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

    /* Subtítulos: sólo se ofrece el botón si el manifiesto trae alguna pista.
     * Arrancan apagados porque muchas señales emiten un CC quemado o vacío. */
    var hasSubtitles by remember { mutableStateOf(false) }
    var subtitlesOn by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        /* `startPositionMs` > 0 cuando la tarjeta viene de "Seguir viendo":
         * el título retoma donde el perfil lo dejó. El failover conserva esa
         * posición al saltar de fuente. */
        controller.start(
            startPositionMs = request.startPositionMs,
            startIndex = request.startIndex,
        )
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
            hasSubtitles = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
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

    fun toggleSubtitles() {
        val next = !subtitlesOn
        subtitlesOn = next
        runCatching {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !next)
                .build()
        }
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

    /* Con el aviso de fallo en pantalla, ATRÁS sale del reproductor: es lo que
     * espera cualquiera que vea "No se pudo reproducir" y quiera volver. */
    val playbackFailed = failoverState.fatalMessage != null || failoverState.exhausted
    BackHandler {
        when {
            playbackFailed -> onBack()
            controlsVisible -> hideControls()
            else -> onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { rootSelfFocused = it.isFocused }
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                /* Teclas multimedia: funcionan SIEMPRE, aunque el foco esté en
                 * un botón o en la lista de fuentes. Muchos mandos de TV y
                 * barras de sonido sólo emiten estas, y antes se perdían. */
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        togglePlayPause()
                        return@onPreviewKeyEvent true
                    }

                    Key.MediaFastForward -> {
                        showControls(focusPlayPause = false)
                        seekBy(SeekPolicy.STEP_MS)
                        return@onPreviewKeyEvent true
                    }

                    Key.MediaRewind -> {
                        showControls(focusPlayPause = false)
                        seekBy(-SeekPolicy.STEP_MS)
                        return@onPreviewKeyEvent true
                    }

                    else -> Unit
                }

                /* `onPreviewKeyEvent` en un ancestro del nodo enfocado se
                 * ejecuta antes que el botón: si el foco está en un control,
                 * el evento NO se roba (IZQUIERDA debe moverse entre botones). */
                if (!rootSelfFocused) {
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
            onInteraction = { interactionTick++ },
            hasSubtitles = hasSubtitles,
            subtitlesOn = subtitlesOn,
            onToggleSubtitles = { toggleSubtitles() },
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

        /* ATRÁS cierra la lista antes de salir del reproductor: si no, el
         * usuario tendría que pulsar dos veces sin saber por qué. */
        BackHandler(enabled = showSources) { showSources = false }

        if (showSources) {
            SourcePickerOverlay(
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
            .background(StreamflixColors.Surface.copy(alpha = 0.92f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Cambiando de fuente…",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Aviso cuando no hay nada que reproducir.
 *
 * Tres salidas, en el orden en que se necesitan: reintentar desde la primera
 * fuente (foco inicial, así que basta pulsar OK), abrir la lista de fuentes
 * para elegir otra a mano, o volver al catálogo. El panel es el mismo vidrio
 * oscuro del resto de la app; nada de diálogos con botones pequeños, que en
 * una TV no se aciertan.
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
            .background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 760.dp)
                .background(StreamflixColors.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                .padding(horizontal = 44.dp, vertical = 38.dp),
        ) {
            Text(
                text = "No se pudo reproducir",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = StreamflixColors.OnSurfaceMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = StreamflixColors.OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PlayerPillButton(
                    text = "Reintentar",
                    primary = true,
                    onClick = onRetry,
                    focusRequester = retryFocus,
                )
                PlayerPillButton(text = "Elegir fuente", onClick = onPickSource)
                PlayerPillButton(text = "Volver", onClick = onBack)
            }
        }
    }
}

/** Cápsula del reproductor: la misma forma que los botones del hero. */
@Composable
private fun PlayerPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 130),
        label = "pillFocus",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "pillScale",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvFocusGlow(progress = ring, cornerRadius = 24.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(
                when {
                    primary -> Color.White
                    focused -> StreamflixColors.GlassStrong
                    else -> StreamflixColors.Glass
                },
                RoundedCornerShape(percent = 50),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

/**
 * Lista de fuentes: panel centrado con filas grandes.
 *
 * Sustituye al `AlertDialog` de Material, que en una TV deja el foco en un
 * botón de 40 dp y una lista que no se desplaza al navegar. Aquí cada fila es
 * enfocable, la `LazyColumn` lleva el foco a la vista y ATRÁS cierra.
 */
@Composable
private fun SourcePickerOverlay(
    sources: List<StreamSource>,
    current: StreamSource?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .widthIn(min = 520.dp, max = 900.dp)
                .background(StreamflixColors.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                .padding(horizontal = 32.dp, vertical = 28.dp),
        ) {
            Text(
                text = "Fuentes",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${sources.size} disponibles · el failover sigue hacia abajo · ATRÁS para volver",
                style = MaterialTheme.typography.labelMedium,
                color = StreamflixColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(20.dp))

            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(sources, key = { index, source -> "$index:${source.url}" }) { index, source ->
                    SourceRow(
                        index = index,
                        source = source,
                        selected = source.url == current?.url,
                        onClick = { onSelect(index) },
                        focusRequester = if (index == 0) firstFocus else null,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "OK elige la fuente marcada · ATRÁS vuelve al vídeo",
                style = MaterialTheme.typography.labelSmall,
                color = StreamflixColors.OnSurfaceMuted.copy(alpha = 0.8f),
            )
        }
    }
}

/** Fila de fuente: número, proveedor, formato y marca de "en uso". */
@Composable
private fun SourceRow(
    index: Int,
    source: StreamSource,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "sourceFocus",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusGlow(progress = ring, cornerRadius = 12.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(
                when {
                    selected -> Color.White.copy(alpha = 0.12f)
                    focused -> StreamflixColors.Glass
                    else -> Color.Transparent
                },
                RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.titleMedium,
            color = StreamflixColors.OnSurfaceMuted,
            modifier = Modifier.width(38.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = source.label,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(if (source.isHls) "HLS" else if (source.isDash) "DASH" else "Progresivo")
                    source.quality?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelMedium,
                color = StreamflixColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "EN USO",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Black,
                )
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
