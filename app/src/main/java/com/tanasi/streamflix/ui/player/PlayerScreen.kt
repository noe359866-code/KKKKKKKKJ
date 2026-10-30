package com.tanasi.streamflix.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tanasi.streamflix.core.LocalDeviceProfile
import com.tanasi.streamflix.data.model.PlaybackChannel
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.data.model.SubtitleTrack
import com.tanasi.streamflix.player.ChannelZapping
import com.tanasi.streamflix.player.FailoverController
import com.tanasi.streamflix.player.FailoverPlanner
import com.tanasi.streamflix.player.MediaSources
import com.tanasi.streamflix.player.PlayerKeyAction
import com.tanasi.streamflix.player.PlayerKeyMap
import com.tanasi.streamflix.player.PlayerTracks
import com.tanasi.streamflix.player.SeekPolicy
import com.tanasi.streamflix.player.TrackOption
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow
import kotlinx.coroutines.delay
import kotlin.math.max

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
    onBack: () -> Unit,
    /**
     * Posición alcanzada, justo antes de liberar el reproductor. Es lo que
     * alimenta "Seguir viendo" **del perfil activo**: sin esto, cada perfil
     * volvía a empezar la serie desde el principio.
     */
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    /* El `Intent` sólo abre la puerta: quién se está viendo lo dice el
     * `ViewModel`, porque el zapeo cambia de canal sin volver a la `Activity`. */
    val request by viewModel.current.collectAsStateWithLifecycle()

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
                        /* Un canal caído no puede dejar al usuario encerrado:
                         * si venía de la parrilla, sigue por el siguiente. */
                        if (ChannelZapping.canZap(request.isLive, request.queue.size)) {
                            PlayerPillButton(
                                text = "Siguiente canal",
                                onClick = {
                                    val index = ChannelZapping.indexOf(request.queue, request.id)
                                    val next = ChannelZapping.step(index, 1, request.queue.size)
                                    ChannelZapping.channelAt(request.queue, next)?.let(viewModel::tune)
                                },
                            )
                        }
                        PlayerPillButton(text = "Volver", onClick = onBack)
                    }
                }
            }

            is PlayerUiState.Ready -> PlaybackSurface(
                sources = current.sources,
                subtitles = current.subtitles,
                tuning = current.tuning,
                request = request,
                onBack = onBack,
                onProgress = onProgress,
                onTune = viewModel::tune,
                onRestore = viewModel::restore,
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
    /** Pistas de texto que llegaron con las fuentes (puede ser vacío). */
    subtitles: List<SubtitleTrack>,
    /** Mientras se resuelve un canal nuevo, en pantalla sigue el anterior. */
    tuning: Boolean,
    request: PlaybackRequest,
    onBack: () -> Unit,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    /** Sintoniza otro canal de la parrilla sin salir del reproductor. */
    onTune: (PlaybackChannel) -> Unit,
    /** Deshace un zapeo volviendo al `PlaybackRequest` anterior completo. */
    onRestore: (PlaybackRequest) -> Unit,
) {
    val context = LocalContext.current
    val deviceProfile = LocalDeviceProfile.current
    val scope = rememberCoroutineScope()

    /* `remember(sources)` y no `remember(Unit)`: si el ViewModel recarga y
     * devuelve otro array, el planificador y el reproductor se recrean. */
    val player: ExoPlayer = remember(sources) { MediaSources.buildPlayer(context, request.isLive) }
    val planner = remember(sources) { FailoverPlanner(sources) }
    val controller = remember(player, planner, subtitles) {
        FailoverController(
            context = context,
            player = player,
            planner = planner,
            scope = scope,
            subtitles = subtitles,
        )
    }

    val failoverState by controller.state.collectAsStateWithLifecycle()

    var showSources by remember { mutableStateOf(false) }
    /* Panel "Audio y subtítulos" (una sola hoja, como en tvOS). */
    var showTracks by remember { mutableStateOf(false) }

    /* --- parrilla: zapeo sin salir del reproductor ------------------------ */
    val queue = request.queue
    val channelIndex = remember(request.id, queue) { ChannelZapping.indexOf(queue, request.id) }
    /* Sólo se zapea en directo y con algo a lo que saltar: en una película
     * `◀ ▶` sigue siendo el salto de 10 s. */
    val canZap = ChannelZapping.canZap(request.isLive, queue.size)

    var showGuide by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<PlaybackChannel?>(null) }
    var bannerTick by remember { mutableStateOf(0) }
    /* Canal desde el que se abrió la guía. ATRÁS vuelve a él: mirar la parrilla
     * no debe costar el canal que se estaba viendo. */
    var channelBeforeGuide by remember { mutableStateOf<PlaybackRequest?>(null) }

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

    /* Subtítulos: en VOD arrancan encendidos cuando hay pista en español
     * (la app es para contenido doblado/subtitulado); en directo, apagados,
     * porque muchas señales emiten un CC quemado o vacío. */
    var subtitlesOn by remember(subtitles) {
        mutableStateOf(SubtitleTrack.preferredIndex(subtitles, request.isLive) != null)
    }
    var textTracks by remember { mutableStateOf(emptyList<TrackOption>()) }
    var audioTracks by remember { mutableStateOf(emptyList<TrackOption>()) }

    DisposableEffect(controller) {
        /* `startPositionMs` > 0 cuando la tarjeta viene de "Seguir viendo":
         * el título retoma donde el perfil lo dejó. El failover conserva esa
         * posición al saltar de fuente. */
        controller.start(
            startPositionMs = request.startPositionMs,
            startIndex = request.startIndex,
        )
        /* La preferencia manda sobre el `DEFAULT` del manifiesto: si el usuario
         * dejó los subtítulos apagados, no se encienden solos. */
        if (!subtitlesOn) {
            runCatching {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }
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
            val tracks = player.currentTracks
            /* `TrackSelectionParameters.overrides` está indexado por
             * `TrackGroup`: si alguno de los grupos de ese tipo tiene entrada,
             * el usuario eligió una pista concreta y la fila
             * "Automático"/"Desactivado" deja de ser la activa. */
            val overrides = player.trackSelectionParameters.overrides
            fun hasOverride(type: Int): Boolean =
                tracks.groups.any { group -> group.type == type && overrides.containsKey(group.mediaTrackGroup) }

            textTracks = PlayerTracks.options(tracks, C.TRACK_TYPE_TEXT, hasOverride(C.TRACK_TYPE_TEXT))
            audioTracks = PlayerTracks.options(tracks, C.TRACK_TYPE_AUDIO, hasOverride(C.TRACK_TYPE_AUDIO))
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

    LaunchedEffect(showTracks) {
        if (!showTracks) runCatching { rootFocus.requestFocus() }
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

    /**
     * Aplica una fila del panel de pistas.
     *
     * "Desactivado" apaga el tipo de pista entero; "Automático" borra los
     * overrides de audio y deja que ExoPlayer elija la pista por defecto del
     * manifiesto (que es la que el ripeo marca como principal).
     */
    fun applyTrack(option: TrackOption) {
        runCatching {
            val builder = player.trackSelectionParameters.buildUpon()
            if (option.isAutomatic) {
                builder.setTrackTypeDisabled(option.type, option.isText)
                if (option.isAudio) builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            } else {
                val group = PlayerTracks.groupFor(player.currentTracks, option) ?: return@runCatching
                builder.setTrackTypeDisabled(option.type, false)
                builder.setOverrideForType(TrackSelectionOverride(group, option.trackIndex))
            }
            player.trackSelectionParameters = builder.build()
            if (option.isText) subtitlesOn = !option.isAutomatic
        }
        showTracks = false
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

    /**
     * Sintoniza [channel] y deja el rótulo en pantalla.
     *
     * El `ExoPlayer` anterior se libera solo: `PlaybackSurface` está memorizado
     * por `sources`, y al llegar las fuentes del canal nuevo se destruye y se
     * recrea. Es a propósito — dos decodificadores vivos a la vez en una TV box
     * acaban en "no reproduce nada".
     */
    fun tuneTo(channel: PlaybackChannel?, keepGuide: Boolean = false) {
        if (channel == null || channel.id == request.id) return
        /* Desde la guía el zapeo NO la cierra: recorrer la parrilla es el uso
         * normal, y cerrar a cada paso obligaría a reabrirla. */
        if (!keepGuide) {
            channelBeforeGuide = request
            showGuide = false
        }
        onTune(channel)
        banner = channel
        bannerTick += 1
        showSources = false
        controlsVisible = false
    }

    /** `CH+` / `CH−`: siguiente/anterior de la parrilla, con vuelta. */
    fun zap(delta: Int): Boolean {
        if (!canZap || showSources || showTracks) return false
        val target = ChannelZapping.step(channelIndex, delta, queue.size)
        val channel = ChannelZapping.channelAt(queue, target) ?: return false
        tuneTo(channel)
        return true
    }

    fun openGuide() {
        if (!canZap || showSources || showGuide || showTracks) return
        channelBeforeGuide = request
        controlsVisible = false
        showGuide = true
    }

    /* El rótulo se va solo, sin que nadie lo cierre: es información, no un
     * diálogo (Apple TV hace lo mismo al cambiar de canal). */
    LaunchedEffect(bannerTick) {
        if (bannerTick == 0) return@LaunchedEffect
        delay(CHANNEL_BANNER_MS)
        banner = null
    }

    /* Al cerrar la guía el foco se queda huérfano en tarjetas que ya no
     * existen: vuelve al vídeo. */
    LaunchedEffect(showGuide) {
        if (!showGuide) runCatching { rootFocus.requestFocus() }
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
                        if (canZap) {
                            zap(1)
                        } else {
                            showControls(focusPlayPause = false)
                            seekBy(SeekPolicy.STEP_MS)
                        }
                        return@onPreviewKeyEvent true
                    }

                    Key.MediaRewind -> {
                        if (canZap) {
                            zap(-1)
                        } else {
                            showControls(focusPlayPause = false)
                            seekBy(-SeekPolicy.STEP_MS)
                        }
                        return@onPreviewKeyEvent true
                    }

                    else -> Unit
                }

                /* Zapeo: se atiende con el foco donde sea — superficie, guía o
                 * barra de controles. Es la tecla que más se pulsa viendo un
                 * directo y no puede depender de dónde esté el foco. */
                when (PlayerKeyMap.actionFor(event.key)) {
                    PlayerKeyAction.CHANNEL_UP -> {
                        if (zap(1)) return@onPreviewKeyEvent true
                    }

                    PlayerKeyAction.CHANNEL_DOWN -> {
                        if (zap(-1)) return@onPreviewKeyEvent true
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

                    /* En directo no hay nada que retroceder (la ventana HLS
                     * no guarda el pasado): `◀ ▶` no se quedan muertos, cambian
                     * de canal. Es lo que hacen los mandos de TV y es la razón
                     * por la que las flechas laterales son las más usadas. */
                    PlayerKeyAction.SEEK_BACK -> {
                        if (canZap) {
                            zap(-1)
                        } else {
                            showControls(focusPlayPause = false)
                            seekBy(-SeekPolicy.STEP_MS)
                        }
                        true
                    }

                    PlayerKeyAction.SEEK_FORWARD -> {
                        if (canZap) {
                            zap(1)
                        } else {
                            showControls(focusPlayPause = false)
                            seekBy(SeekPolicy.STEP_MS)
                        }
                        true
                    }

                    PlayerKeyAction.OPEN_CONTROLS -> {
                        /* En directo ARRIBA abre la parrilla y ABAJO los
                         * controles: "más canales" queda a una tecla, que es
                         * justo lo que se busca en una TV. En VOD, ambas
                         * siguen sacando los controles. */
                        if (canZap && event.key == Key.DirectionUp) openGuide()
                        else showControls(focusPlayPause = true)
                        true
                    }

                    /* Ya se atendieron arriba, antes del filtro de foco. */
                    PlayerKeyAction.CHANNEL_UP,
                    PlayerKeyAction.CHANNEL_DOWN,
                    -> true

                    null -> false
                }
            }
            /* Con la guía abierta la superficie no es enfocable: si lo fuera,
             * `▶` en la última tarjeta sacaría el foco de la guía y el mando
             * dejaría de recorrer canales. Los atajos (`CH+`, `CH−`) siguen
             * llegando porque el evento sube por los ancestros. */
            .focusable(enabled = !showGuide),
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

        /* Con el panel de fuentes abierto los controles no existen: así el foco
         * no puede escaparse a los botones de detrás (tvOS también los oculta
         * al abrir la información). */
        if (!showSources && !showGuide && !showTracks) PlayerControls(
            title = request.title,
            isLive = request.isLive,
            failover = failoverState,
            sourcesCount = sources.size,
            channelPosition = ChannelZapping.positionLabel(queue, channelIndex),
            channelCount = if (canZap) queue.size else 0,
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
            onShowChannels = { openGuide() },
            onInteraction = { interactionTick++ },
            hasTracks = PlayerTracks.hasPanel(textTracks, audioTracks),
            subtitlesOn = subtitlesOn,
            onShowTracks = { showTracks = true },
        )

        if (failoverState.switching || tuning) {
            /* Por encima de los controles cuando están a la vista; pegado al
             * borde cuando no, para no tapar la barra de progreso. */
            SwitchingBadge(
                /* Zapear y saltar de fuente se parecen, pero no son lo mismo:
                 * el texto dice cuál está pasando. */
                label = if (tuning) "Sintonizando…" else "Cambiando de fuente…",
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

        /* Rótulo del zapeo: arriba, translúcido, encima de todo y sin foco. */
        banner?.let { channel ->
            ChannelBanner(
                channel = channel,
                position = ChannelZapping.positionLabel(
                    queue,
                    ChannelZapping.indexOf(queue, channel.id),
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 30.dp),
            )
        }

        /* ATRÁS cierra la guía **devolviendo el canal** desde el que se abrió:
         * así se puede cotillear la parrilla sin perder lo que se veía. */
        BackHandler(enabled = showGuide) {
            showGuide = false
            channelBeforeGuide?.let { previous ->
                channelBeforeGuide = null
                if (previous.id != request.id) onRestore(previous)
            }
        }

        if (showGuide) {
            ChannelGuideOverlay(
                channels = queue,
                currentId = request.id,
                onTune = { channel -> tuneTo(channel, keepGuide = true) },
                onClose = { showGuide = false },
            )
        }

        /* ATRÁS cierra la lista antes de salir del reproductor: si no, el
         * usuario tendría que pulsar dos veces sin saber por qué. */
        BackHandler(enabled = showSources) { showSources = false }
        BackHandler(enabled = showTracks) { showTracks = false }

        if (showTracks) {
            TracksOverlay(
                title = request.title,
                textTracks = textTracks,
                audioTracks = audioTracks,
                onDismiss = { showTracks = false },
                onSelect = { option -> applyTrack(option) },
            )
        }

        if (showSources) {
            SourcePickerOverlay(
                title = request.title,
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
private fun SwitchingBadge(
    label: String = "Cambiando de fuente…",
    modifier: Modifier = Modifier,
) {
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
            text = label,
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
 * Selector de fuentes, con el panel lateral del reproductor de tvOS.
 *
 * Antes era un `AlertDialog` centrado: en una TV tapaba el vídeo entero y las
 * filas no se desplazaban con el mando. Ahora el panel entra por la derecha
 * (donde tvOS pone "Información"), deja el vídeo visible a la izquierda y cada
 * fila es una tarjeta enfocable:
 *
 * ```
 * ┌──────────────────────────────────────────┐
 * │ FUENTES                                  │
 * │ Cienciano vs Atlético Grau               │  ← qué se está viendo
 * │ 5 disponibles · el failover sigue abajo  │
 * │ ┌──────────────────────────────────────┐ │
 * │ │ ①  PelisPlus              HLS · 1080p│ │  ← enfocada (anillo blanco)
 * │ └──────────────────────────────────────┘ │
 * │ ┌──────────────────────────────────────┐ │
 * │ │ ②  Gnula      HLS · 720p    En uso ✓ │ │  ← la que se está usando
 * │ └──────────────────────────────────────┘ │
 * │ OK elegir            ATRÁS cerrar        │
 * └──────────────────────────────────────────┘
 * ```
 *
 * El foco arranca en la fuente **en uso**, no en la primera: si el usuario sólo
 * quería ver cuál era, ATRÁS sale sin mover nada.
 */
@Composable
private fun SourcePickerOverlay(
    title: String,
    sources: List<StreamSource>,
    current: StreamSource?,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val currentIndex = remember(sources, current) {
        sources.indexOfFirst { it.url == current?.url }.takeIf { it >= 0 } ?: 0
    }
    val focusTarget = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focusTarget.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        /* Velo en degradado hacia la derecha: el vídeo sigue viéndose a la
         * izquierda y sólo se oscurece la zona del panel. */
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.28f to Color.Transparent,
                        0.60f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                )
                .clickable(onClick = onDismiss),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.38f)
                .widthIn(min = 420.dp, max = 640.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            StreamflixColors.Surface.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.95f),
                        ),
                    ),
                )
                .padding(start = 34.dp, end = 34.dp, top = 40.dp, bottom = 28.dp),
        ) {
            Text(
                text = "FUENTES",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                color = StreamflixColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${sources.size} disponibles · si una falla, se pasa a la siguiente",
                style = MaterialTheme.typography.labelMedium,
                color = StreamflixColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(22.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(sources, key = { index, source -> "$index:${source.url}" }) { index, source ->
                    SourceRow(
                        index = index,
                        source = source,
                        selected = source.url == current?.url,
                        onClick = { onSelect(index) },
                        focusRequester = if (index == currentIndex) focusTarget else null,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SourceKeyHint(key = "OK", action = "Elegir")
                SourceKeyHint(key = "ATRÁS", action = "Cerrar")
            }
        }
    }
}

/** Fila de fuente: número, proveedor, formato/calidad y marca de "en uso". */
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
    val pop by animateFloatAsState(
        targetValue = if (focused) 1.02f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "sourcePop",
    )

    val name = source.provider?.takeIf { it.isNotBlank() } ?: source.name

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pop
                scaleY = pop
            }
            .tvFocusGlow(progress = ring, cornerRadius = 10.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(
                when {
                    selected -> Color.White.copy(alpha = 0.14f)
                    focused -> StreamflixColors.GlassStrong
                    else -> Color.White.copy(alpha = 0.04f)
                },
                RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.07f),
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /* Número en cápsula: da el orden del failover de un vistazo. */
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    if (selected) Color.White else Color.White.copy(alpha = 0.10f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Color.Black else Color.White,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(source.formatLine)
                    if (selected) append(" · en uso")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Color.White.copy(alpha = 0.85f) else StreamflixColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            CheckMark(Modifier.size(18.dp))
        }
    }
}

/**
 * Marca de "esta es la que se está viendo", dibujada en `Canvas`: nada de
 * fuentes de iconos, que en algunos TV boxes salen como cajas.
 */
@Composable
private fun CheckMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = max(2f, size.width * 0.13f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(size.width * 0.16f, size.height * 0.55f)
            lineTo(size.width * 0.42f, size.height * 0.80f)
            lineTo(size.width * 0.87f, size.height * 0.22f)
        }
        drawPath(path, Color.White, style = stroke)
    }
}

/** Chuleta de teclas del panel: tecla en caja + acción. */
@Composable
private fun SourceKeyHint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text(
                text = key,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = action,
            style = MaterialTheme.typography.labelSmall,
            color = StreamflixColors.OnSurfaceMuted,
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Panel "Audio y subtítulos"                                                 */
/* -------------------------------------------------------------------------- */

/**
 * Hoja de pistas: **una sola** para audio y subtítulos, como en tvOS.
 *
 * ```text
 * ┌──────────────────────────────────────────┐
 * │ AUDIO Y SUBTÍTULOS                       │
 * │ Interestelar                             │
 * │                                          │
 * │ SUBTÍTULOS                               │
 * │  ● Desactivado                           │
 * │    Español · NovaHD                      │
 * │    Español (Latino) · NovaHD             │
 * │ AUDIO                                    │
 * │  ● Automático                            │
 * │    Español · AAC                         │
 * │ OK elegir            ATRÁS cerrar        │
 * └──────────────────────────────────────────┘
 * ```
 *
 * A diferencia del panel de fuentes, éste **no** cierra el vídeo: se abre sobre
 * la imagen y vuelve a los controles al elegir. Elegir una pista es una acción
 * frecuente (cambiar de idioma a mitad de película), así que el foco arranca en
 * la pista activa.
 */
@Composable
private fun TracksOverlay(
    title: String,
    textTracks: List<TrackOption>,
    audioTracks: List<TrackOption>,
    onDismiss: () -> Unit,
    onSelect: (TrackOption) -> Unit,
) {
    val focusTarget = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focusTarget.requestFocus() }
    }

    /* El foco arranca en la fila activa; si no hay ninguna, en la primera. */
    val firstSelected = remember(textTracks, audioTracks) {
        (textTracks + audioTracks).firstOrNull { it.selected }?.id
            ?: (textTracks + audioTracks).firstOrNull()?.id
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.28f to Color.Transparent,
                        0.60f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                )
                .clickable(onClick = onDismiss),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.38f)
                .widthIn(min = 420.dp, max = 640.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            StreamflixColors.Surface.copy(alpha = 0.98f),
                            Color.Black.copy(alpha = 0.95f),
                        ),
                    ),
                )
                .padding(start = 34.dp, end = 34.dp, top = 40.dp, bottom = 28.dp),
        ) {
            Text(
                text = "AUDIO Y SUBTÍTULOS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                color = StreamflixColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (textTracks.isNotEmpty()) {
                    item(key = "text-header") { TrackSectionHeader("SUBTÍTULOS") }
                    itemsIndexed(textTracks, key = { _, option -> "text:${option.id}" }) { _, option ->
                        TrackRow(
                            option = option,
                            onClick = { onSelect(option) },
                            focusRequester = if (option.id == firstSelected) focusTarget else null,
                        )
                    }
                }
                if (audioTracks.isNotEmpty()) {
                    item(key = "audio-header") { TrackSectionHeader("AUDIO") }
                    itemsIndexed(audioTracks, key = { _, option -> "audio:${option.id}" }) { _, option ->
                        TrackRow(
                            option = option,
                            onClick = { onSelect(option) },
                            focusRequester = if (option.id == firstSelected) focusTarget else null,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SourceKeyHint(key = "OK", action = "Elegir")
                SourceKeyHint(key = "ATRÁS", action = "Cerrar")
            }
        }
    }
}

@Composable
private fun TrackSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
        color = StreamflixColors.OnSurfaceMuted.copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** Fila de pista: marca de activo, nombre y (si lo hay) idioma secundario. */
@Composable
private fun TrackRow(
    option: TrackOption,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "trackFocus",
    )
    val pop by animateFloatAsState(
        targetValue = if (focused) 1.02f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "trackPop",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pop
                scaleY = pop
            }
            .tvFocusGlow(progress = ring, cornerRadius = 10.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(
                when {
                    option.selected -> Color.White.copy(alpha = 0.14f)
                    focused -> StreamflixColors.GlassStrong
                    else -> Color.White.copy(alpha = 0.04f)
                },
                RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = if (option.selected) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.07f),
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = when {
                option.isAutomatic && option.isAudio -> "La pista principal del vídeo"
                option.isAutomatic -> "Sin subtítulos"
                else -> option.language?.let { PlayerTracks.languageName(it) }.orEmpty()
            }
            if (secondary.isNotEmpty()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelMedium,
                    color = StreamflixColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
        if (option.selected) {
            Spacer(Modifier.width(10.dp))
            CheckMark(Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
