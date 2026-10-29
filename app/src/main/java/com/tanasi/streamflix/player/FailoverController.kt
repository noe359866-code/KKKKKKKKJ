package com.tanasi.streamflix.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que la UI necesita pintar del failover. */
data class FailoverState(
    val current: StreamSource? = null,
    val position: Int = 0,
    val total: Int = 0,
    /** `true` durante el salto: se muestra "cambiando de servidor" sin bloquear. */
    val switching: Boolean = false,
    val exhausted: Boolean = false,
    val fatalMessage: String? = null,
    /**
     * Último error del reproductor (`ERROR_CODE_…`). Se enseña al usuario
     * cuando se agotan las fuentes: sin esto la pantalla quedaba en negro sin
     * ninguna pista de por qué no reproducía.
     */
    val lastError: String? = null,
) {
    val hasPrevious: Boolean get() = position > 1
}

/**
 * Failover automático sobre un único `ExoPlayer`.
 *
 * Por qué no se recrea el reproductor al saltar: recrear el `ExoPlayer` cuesta
 * ~150-300 ms y provoca un parpadeo negro. Aquí se cambia sólo el
 * `MediaSource` (`player.setMediaSource`), que reutiliza el decodificador y el
 * render de vídeo, así que el salto es un `BUFFERING` breve y silencioso.
 *
 * Se conserva la posición del salto: si el usuario llevaba 12:31 de película, el
 * segundo servidor arranca en 12:31 en vez de empezar de cero.
 *
 * **Hilo**: todos los métodos deben llamarse desde el hilo principal (el
 * `CoroutineScope` que se pasa es el de Compose, que ya lo es). ExoPlayer exige
 * su propio looper.
 */
class FailoverController(
    private val context: Context,
    private val player: ExoPlayer,
    private val planner: FailoverPlanner,
    private val scope: CoroutineScope,
    private val stallTimeoutMs: Long = 12_000,
) : Player.Listener {

    private val mutableState = MutableStateFlow(FailoverState(total = planner.total))
    val state: StateFlow<FailoverState> = mutableState.asStateFlow()

    private var watchdog: Job? = null
    private var released = false

    /**
     * Añade el listener y arranca.
     *
     * @param startIndex fuente elegida en la hoja de detalles (0 = máxima
     *   prioridad). El resto de la lista queda como plan de failover.
     */
    fun start(startPositionMs: Long = 0L, startIndex: Int = 0) {
        player.addListener(this)
        play(planner.start(startIndex), startPositionMs, wasPlaying = true)
    }

    /* ---------------------------------------------------------------------- */
    /* Player.Listener                                                        */
    /* ---------------------------------------------------------------------- */

    override fun onPlayerError(error: PlaybackException) {
        AppLog.w(
            "playback error %s (%s) en %s",
            error.errorCodeName,
            error.errorCode,
            planner.current?.let { AppLog.redact(it.url) } ?: "-",
        )

        if (!FailoverRules.shouldFailover(error.errorCode)) {
            /* DRM roto: cambiar de CDN no arregla nada, se avisa al usuario. */
            mutableState.update {
                it.copy(switching = false, fatalMessage = error.errorCodeName, lastError = error.errorCodeName)
            }
            return
        }

        mutableState.update { it.copy(lastError = error.errorCodeName) }
        advance(FailoverRules.reasonFor(error.errorCode), error.errorCodeName)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                cancelWatchdog()
                mutableState.update { it.copy(switching = false) }
            }

            Player.STATE_BUFFERING -> if (player.playWhenReady) armWatchdog()

            /* STATE_IDLE llega también tras un error: no es un cuelgue. */
            else -> Unit
        }
    }

    /**
     * Una pausa del usuario **no** es un cuelgue: el watchdog sólo corre cuando
     * la reproducción es intencionada (`playWhenReady`) y el reproductor sigue
     * sin llegar a `STATE_READY`. Sin esta condición, pausar 12 s en mitad de
     * una película cambiaría de servidor por la espalda.
     */
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            cancelWatchdog()
        } else if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
            armWatchdog()
        }
    }

    /* ---------------------------------------------------------------------- */
    /* API para la UI                                                         */
    /* ---------------------------------------------------------------------- */

    /** Salta a la siguiente fuente conservando posición y estado de reproducción. */
    fun advance(reason: FailoverReason, message: String? = null) {
        if (released) return
        cancelWatchdog()

        val resumePosition = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        val next = planner.fail(reason, message)

        if (next == null) {
            AppLog.w("sin fuentes: %d intentos agotados", planner.history.size)
            mutableState.update {
                it.copy(switching = false, exhausted = true, position = planner.progress)
            }
            return
        }

        AppLog.i(
            "failover %s → fuente %d/%d (%s)",
            reason.name,
            planner.progress,
            planner.total,
            next.label,
        )
        mutableState.update { it.copy(switching = true) }
        play(next, resumePosition, wasPlaying)
    }

    /** Elección manual desde la lista de fuentes. */
    fun selectSource(index: Int) {
        if (released) return
        val resumePosition = player.currentPosition.coerceAtLeast(0L)
        val source = planner.select(index) ?: return
        cancelWatchdog()
        mutableState.update { it.copy(switching = true, exhausted = false) }
        play(source, resumePosition, wasPlaying = true)
    }

    /** Libera listener y watchdog; el `ExoPlayer` lo libera su dueño. */
    fun release() {
        released = true
        cancelWatchdog()
        player.removeListener(this)
    }

    /* ---------------------------------------------------------------------- */
    /* Interno                                                                */
    /* ---------------------------------------------------------------------- */

    private fun play(source: StreamSource?, positionMs: Long, wasPlaying: Boolean) {
        if (source == null) {
            mutableState.update { it.copy(exhausted = true, switching = false) }
            return
        }

        player.setMediaSource(MediaSources.mediaSource(context, source))
        player.prepare()
        if (positionMs > 0L) player.seekTo(positionMs)
        player.playWhenReady = wasPlaying

        mutableState.update {
            it.copy(
                current = source,
                position = planner.progress,
                total = planner.total,
                switching = false,
                exhausted = false,
                fatalMessage = null,
            )
        }
        if (wasPlaying) armWatchdog()
    }

    /**
     * Vigila los cuelgues silenciosos: si el reproductor lleva
     * [stallTimeoutMs] sin llegar a `STATE_READY` (CDN que acepta la conexión y
     * no manda bytes), se salta igual que ante un error. ExoPlayer no siempre
     * emite `onPlayerError` en ese caso, por eso hace falta el temporizador.
     */
    private fun armWatchdog() {
        cancelWatchdog()
        watchdog = scope.launch {
            delay(stallTimeoutMs)
            if (released) return@launch
            if (player.playbackState != Player.STATE_READY) {
                AppLog.w("stall de %d ms, saltando de fuente", stallTimeoutMs)
                mutableState.update { it.copy(lastError = "sin datos en ${stallTimeoutMs}ms") }
                advance(FailoverReason.STALL, "sin datos en ${stallTimeoutMs}ms")
            }
        }
    }

    private fun cancelWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }
}
