package com.tanasi.streamflix.ui.player

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tanasi.streamflix.core.DeviceDetector
import com.tanasi.streamflix.core.DpadKeys
import com.tanasi.streamflix.data.model.PlaybackQueueCodec
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val request = intent.toPlaybackRequest() ?: run {
            finish()
            return
        }

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val poster = intent.getStringExtra(EXTRA_POSTER)
        val backdrop = intent.getStringExtra(EXTRA_BACKDROP)
        val progressStore = ServiceLocator.watchProgressStore(this)

        val deviceProfile = DeviceDetector.detect(this)
        if (deviceProfile.requiresDpadFocus) {
            DpadKeys.applyImmersive(window.decorView)
        }

        setContent {
            StreamflixTheme(deviceProfile = deviceProfile) {
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

                PlayerScreen(
                    viewModel = playerViewModel,
                    onBack = { finish() },
                    onProgress = { positionMs, durationMs ->
                        /* Un directo no se "continúa": no hay posición que
                         * guardar. Sin perfil (lanzado desde fuera) tampoco. */
                        if (!profileId.isNullOrBlank() && !request.isLive) {
                            progressStore.save(
                                profileId,
                                WatchProgress(
                                    id = request.id,
                                    type = request.type,
                                    title = request.title,
                                    poster = poster,
                                    backdrop = backdrop,
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

    private fun Intent.toPlaybackRequest(): PlaybackRequest? {
        val id = getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: return null
        return PlaybackRequest(
            id = id,
            type = getStringExtra(EXTRA_TYPE) ?: "movie",
            title = getStringExtra(EXTRA_TITLE) ?: id,
            isLive = getBooleanExtra(EXTRA_IS_LIVE, false),
            startIndex = getIntExtra(EXTRA_START_INDEX, 0).coerceAtLeast(0),
            startPositionMs = getLongExtra(EXTRA_START_POSITION, 0L).coerceAtLeast(0L),
            /* La parrilla viaja serializada: el reproductor la usa para `CH+`,
             * `CH−` y la guía. Si el extra falta o está corrupto, la lista
             * queda vacía y todo lo demás funciona igual. */
            queue = PlaybackQueueCodec.decode(getStringExtra(EXTRA_QUEUE)),
        )
    }

    companion object {
        const val EXTRA_ID = "extra.id"
        const val EXTRA_TYPE = "extra.type"
        const val EXTRA_TITLE = "extra.title"
        const val EXTRA_IS_LIVE = "extra.isLive"
        const val EXTRA_START_INDEX = "extra.startIndex"
        const val EXTRA_START_POSITION = "extra.startPosition"
        const val EXTRA_PROFILE_ID = "extra.profileId"
        const val EXTRA_POSTER = "extra.poster"
        const val EXTRA_BACKDROP = "extra.backdrop"
        const val EXTRA_QUEUE = "extra.queue"
    }
}
