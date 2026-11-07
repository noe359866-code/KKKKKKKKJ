package com.tanasi.streamflix.ui.player

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tanasi.streamflix.core.DeviceDetector
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.core.DpadKeys
import com.tanasi.streamflix.data.model.PlaybackRestore
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.WatchProgress
import com.tanasi.streamflix.di.ServiceLocator
import com.tanasi.streamflix.ui.theme.StreamflixTheme

/**
 * Contenedor del reproductor.
 *
 * Se mantiene como `Activity` separada (y no como pantalla de Compose dentro de
 * [com.tanasi.streamflix.MainActivity]) por tres motivos concretos:
 *
 *  1. el grafo de foco del D-Pad empieza limpio: al volver, el Home recupera el
 *     foco donde estaba;
 *  2. `FLAG_KEEP_SCREEN_ON` y la pantalla completa afectan sólo a esta ventana;
 *  3. el ciclo de vida del `ExoPlayer` queda atado a la actividad de vídeo.
 */
class PlayerActivity : ComponentActivity() {

    /**
     * Petición en curso y segundo en el que va, para [onSaveInstanceState].
     *
     * El `ViewModel` es la fuente de verdad (no basta con el `Intent`); aquí
     * sólo se copia lo último visto para poder reconstruir la sesión si el
     * sistema mata el proceso.
     */
    private var latestRequest: PlaybackRequest? = null
    private var latestPositionMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        /* Primero lo guardado por el sistema (muerte de proceso o vuelta de
         * segundo plano), después el `Intent` que abrió la pantalla. */
        val restored = PlaybackRestore.decode(savedInstanceState?.getString(STATE_PLAYBACK))
        val request = restored?.request ?: intent.toPlaybackRequest() ?: run {
            finish()
            return
        }
        latestRequest = request
        latestPositionMs = restored?.positionMs ?: request.startPositionMs

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val progressStore = ServiceLocator.watchProgressStore(this)

        val deviceProfile = DeviceDetector.detect(this)
        if (deviceProfile.requiresDpadFocus) {
            DpadKeys.applyImmersive(window.decorView)
        }

        setContent {
            val configuration = LocalConfiguration.current
            val currentDeviceProfile = DeviceProfile.fromDimensions(
                configuration.screenWidthDp, configuration.screenHeightDp,
            )
            StreamflixTheme(deviceProfile = currentDeviceProfile) {
                val factory = remember(request) {
                    viewModelFactory {
                        initializer {
                            PlayerViewModel(
                                repository = ServiceLocator.streamsRepository(applicationContext),
                                request = request,
                            )
                        }
                    }
                }
                val playerViewModel: PlayerViewModel = viewModel(factory = factory)

                /* La petición vive en el `ViewModel`: se sigue aquí para
                 * guardar la sesión correcta y no la del `Intent`. */
                val currentRequest by playerViewModel.current.collectAsStateWithLifecycle()
                LaunchedEffect(currentRequest) {
                    if (latestRequest?.id != currentRequest.id || latestRequest?.type != currentRequest.type) {
                        latestPositionMs = currentRequest.startPositionMs
                    }
                    latestRequest = currentRequest
                }

                PlayerScreen(
                    viewModel = playerViewModel,
                    onBack = { finish() },
                    onPlayNext = { nextId -> openNextEpisode(nextId, profileId) },
                    onProgress = { current, positionMs, durationMs ->
                        if (latestRequest?.id == current.id && latestRequest?.type == current.type) {
                            latestPositionMs = positionMs
                        }
                        /* Sin perfil (lanzado desde fuera) no hay dónde
                         * guardar el avance. */
                        if (!profileId.isNullOrBlank()) {
                            progressStore.save(
                                profileId,
                                WatchProgress(
                                    id = current.id,
                                    type = current.type,
                                    title = current.title,
                                    poster = current.poster,
                                    backdrop = current.backdrop,
                                    positionMs = positionMs,
                                    durationMs = durationMs,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    /**
     * Abre el episodio siguiente en un reproductor nuevo.
     *
     * Se cierra la actual después de lanzar la nueva: mantener dos
     * `PlayerActivity` vivas dejaría dos `ExoPlayer` con audio, y al volver con
     * ATRÁS el usuario aterrizaría en el episodio que acababa de terminar.
     *
     * El perfil viaja en el `Intent` para que el avance del capítulo nuevo se
     * guarde en el sitio correcto.
     */
    private fun openNextEpisode(nextId: String, profileId: String?) {
        val next = intentForEpisode(nextId, profileId)
        startActivity(next)
        finish()
    }

    private fun intentForEpisode(nextId: String, profileId: String?): Intent {
        /* El título de la serie no se puede reconstruir de un id como
         * `tt0903747:1:5`: el `Intent` actual lo lleva con la etiqueta del
         * capítulo, así que se reutiliza el nombre de la serie quitándole el
         * sufijo " · T1 E4" antes de añadir el nuevo. */
        val seriesTitle = (intent.getStringExtra(EXTRA_TITLE) ?: nextId).substringBefore(" · ")
        val request = PlaybackRequest(
            id = nextId,
            type = intent.getStringExtra(EXTRA_TYPE) ?: "series",
            title = seriesTitle,
            poster = intent.getStringExtra(EXTRA_POSTER),
            backdrop = intent.getStringExtra(EXTRA_BACKDROP),
        )
        return Intent(this, PlayerActivity::class.java).apply {
            putExtra(EXTRA_ID, request.id)
            putExtra(EXTRA_TYPE, request.type)
            putExtra(EXTRA_TITLE, request.title)
            putExtra(EXTRA_POSTER, request.poster)
            putExtra(EXTRA_BACKDROP, request.backdrop)
            /* El episodio nuevo empieza por el principio y con la fuente de
             * mayor prioridad: el failover del anterior no aplica. */
            putExtra(EXTRA_START_INDEX, 0)
            putExtra(EXTRA_START_POSITION, 0L)
            profileId?.let { putExtra(EXTRA_PROFILE_ID, it) }
        }
    }

    /**
     * Guarda la sesión para poder volver a ella.
     *
     * Se llama también al pasar a segundo plano (no sólo al morir el proceso):
     * así el regreso es instantáneo y en el mismo segundo, sin depender de que
     * el sistema llegue a matar la actividad.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val request = latestRequest ?: return
        outState.putString(STATE_PLAYBACK, PlaybackRestore.encode(request, latestPositionMs))
    }

    private fun Intent.toPlaybackRequest(): PlaybackRequest? {
        val id = getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: return null
        return PlaybackRequest(
            id = id,
            type = getStringExtra(EXTRA_TYPE) ?: "movie",
            title = getStringExtra(EXTRA_TITLE) ?: id,
            startIndex = getIntExtra(EXTRA_START_INDEX, 0).coerceAtLeast(0),
            startPositionMs = getLongExtra(EXTRA_START_POSITION, 0L).coerceAtLeast(0L),
            poster = getStringExtra(EXTRA_POSTER),
            backdrop = getStringExtra(EXTRA_BACKDROP),
            nextEpisodeId = getStringExtra(EXTRA_NEXT_EPISODE_ID),
            nextEpisodeLabel = getStringExtra(EXTRA_NEXT_EPISODE_LABEL),
            nextEpisodeAirDate = getStringExtra(EXTRA_NEXT_EPISODE_AIR_DATE),
        )
    }

    companion object {
        const val EXTRA_ID = "extra.id"
        const val EXTRA_TYPE = "extra.type"
        const val EXTRA_TITLE = "extra.title"
        const val EXTRA_START_INDEX = "extra.startIndex"
        const val EXTRA_START_POSITION = "extra.startPosition"
        const val EXTRA_PROFILE_ID = "extra.profileId"
        const val EXTRA_POSTER = "extra.poster"
        const val EXTRA_BACKDROP = "extra.backdrop"
        const val EXTRA_NEXT_EPISODE_ID = "extra.nextEpisodeId"
        const val EXTRA_NEXT_EPISODE_LABEL = "extra.nextEpisodeLabel"
        const val EXTRA_NEXT_EPISODE_AIR_DATE = "extra.nextEpisodeAirDate"

        /** Estado de sesión (no es un extra del `Intent`). */
        private const val STATE_PLAYBACK = "state.playback"
    }
}
