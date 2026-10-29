package com.tanasi.streamflix

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.di.ServiceLocator
import com.tanasi.streamflix.ui.home.HomeScreen
import com.tanasi.streamflix.ui.home.HomeViewModel
import com.tanasi.streamflix.ui.player.PlayerActivity
import com.tanasi.streamflix.ui.settings.ServerSettingsDialog
import com.tanasi.streamflix.ui.theme.StreamflixTheme

/**
 * Única actividad del catálogo.
 *
 * Detecta el perfil (TV o móvil), publica el `DeviceProfile` en la composición y
 * lanza [PlayerActivity] al elegir un título.
 *
 * Detalles de Android TV que se resuelven aquí:
 *  - en TV la pantalla nunca se apaga en el catálogo (playback además lleva su
 *    propio `FLAG_KEEP_SCREEN_ON`);
 *  - `enableEdgeToEdge()` en TV evita bandas negras cuando el launcher fuerza
 *    una resolución distinta a la del panel;
 *  - el `deviceProfile` se recalcula desde la `Application`, que es la que
 *    recibe los cambios de modo de interfaz.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val deviceProfile = (application as StreamflixApp).deviceProfile

        setContent {
            /* `StreamflixTheme` ya publica el perfil en `LocalDeviceProfile`. */
            StreamflixTheme(deviceProfile = deviceProfile) {
                val factory = remember {
                    viewModelFactory {
                        initializer {
                            HomeViewModel(ServiceLocator.catalogRepository(applicationContext))
                        }
                    }
                }
                val homeViewModel: HomeViewModel = viewModel(factory = factory)
                var showSettings by remember { mutableStateOf(false) }

                HomeScreen(
                    viewModel = homeViewModel,
                    onPlay = { request -> openPlayer(request) },
                    onOpenSettings = { showSettings = true },
                )

                if (showSettings) {
                    ServerSettingsDialog(
                        settings = ServiceLocator.settings(applicationContext),
                        onDismiss = { showSettings = false },
                        onSaved = {
                            showSettings = false
                            /* El catálogo semilla no cambia, pero un catálogo
                             * remoto sí: se invalida para que el próximo
                             * arranque use el servidor nuevo. */
                            ServiceLocator.catalogRepository(applicationContext).invalidate()
                            homeViewModel.refresh()
                        },
                    )
                }
            }
        }
    }

    private fun openPlayer(request: PlaybackRequest) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ID, request.id)
                .putExtra(PlayerActivity.EXTRA_TYPE, request.type)
                .putExtra(PlayerActivity.EXTRA_TITLE, request.title)
                .putExtra(PlayerActivity.EXTRA_IS_LIVE, request.isLive),
        )
    }
}
