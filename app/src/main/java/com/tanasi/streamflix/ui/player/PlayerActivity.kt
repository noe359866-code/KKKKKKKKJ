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
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.di.ServiceLocator
import com.tanasi.streamflix.ui.components.AndroidTvOnlyScreen
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

        val deviceProfile = DeviceDetector.detect(this)
        if (!deviceProfile.isAndroidTv) {
            setContent {
                StreamflixTheme(deviceProfile = deviceProfile) {
                    AndroidTvOnlyScreen(onExit = { finish() })
                }
            }
            return
        }

        val request = intent.toPlaybackRequest() ?: run {
            finish()
            return
        }

        DpadKeys.applyImmersive(window.decorView)

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
                    request = request,
                    onBack = { finish() },
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
        )
    }

    companion object {
        const val EXTRA_ID = "extra.id"
        const val EXTRA_TYPE = "extra.type"
        const val EXTRA_TITLE = "extra.title"
        const val EXTRA_IS_LIVE = "extra.isLive"
        const val EXTRA_START_INDEX = "extra.startIndex"
    }
}
