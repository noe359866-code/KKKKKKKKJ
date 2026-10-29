package com.tanasi.streamflix

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.playbackRequest
import com.tanasi.streamflix.di.ServiceLocator
import com.tanasi.streamflix.ui.details.DetailsDialog
import com.tanasi.streamflix.ui.details.DetailsViewModel
import com.tanasi.streamflix.ui.home.HomeScreen
import com.tanasi.streamflix.ui.home.HomeViewModel
import com.tanasi.streamflix.ui.player.PlayerActivity
import com.tanasi.streamflix.ui.settings.ServerSettingsDialog
import com.tanasi.streamflix.ui.theme.StreamflixTheme

/**
 * Única actividad del catálogo.
 *
 * Publica el `DeviceProfile` en la composición, abre la hoja de detalles al
 * pulsar una tarjeta y lanza [PlayerActivity] cuando el usuario decide
 * reproducir (con el servidor que haya elegido).
 *
 * Detalles de Android TV que se resuelven aquí:
 *  - en TV la pantalla nunca se apaga en el catálogo (el reproductor además
 *    lleva su propio `FLAG_KEEP_SCREEN_ON`);
 *  - `enableEdgeToEdge()` evita bandas negras cuando el launcher fuerza una
 *    resolución distinta a la del panel;
 *  - el `deviceProfile` se lee de la `Application`, que es la que recibe los
 *    cambios de modo de interfaz.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val deviceProfile = (application as StreamflixApp).deviceProfile

        setContent {
            /* `StreamflixTheme` ya publica el perfil en `LocalDeviceProfile`. */
            StreamflixTheme(deviceProfile = deviceProfile) {
                Catalogue(
                    onPlay = { request -> openPlayer(request) },
                )
            }
        }
    }

    /**
     * Catálogo + hojas.
     *
     * La hoja de detalles es estado de UI (`DetailsTarget`) y no una pantalla
     * aparte: se abre encima del Home, conserva el foco de la tarjeta que la
     * lanzó y se cierra volviendo exactamente ahí.
     */
    @Composable
    private fun Catalogue(onPlay: (PlaybackRequest) -> Unit) {
        val settings = remember { ServiceLocator.settings(applicationContext) }
        val homeFactory = remember {
            viewModelFactory {
                initializer {
                    HomeViewModel(ServiceLocator.catalogRepository(applicationContext))
                }
            }
        }
        val homeViewModel: HomeViewModel = viewModel(factory = homeFactory)

        var details by remember { mutableStateOf<DetailsTarget?>(null) }
        /* Build de release sin URL configurada: se pide el servidor en el primer
         * arranque en lugar de dejar al usuario mirando un error. */
        var showSettings by remember { mutableStateOf(settings.baseUrl.isBlank()) }

        HomeScreen(
            viewModel = homeViewModel,
            onPlay = onPlay,
            onOpenDetails = { entry, openOnSources ->
                details = DetailsTarget(entry, openOnSources)
            },
            onOpenSettings = { showSettings = true },
        )

        details?.let { target ->
            /* Un `ViewModel` por título: si el usuario abre, cierra y vuelve a
             * abrir la misma tarjeta, las fuentes ya están cargadas y la
             * elección de servidor se conserva. */
            val factory = remember(target.entry.id) {
                viewModelFactory {
                    initializer {
                        DetailsViewModel(
                            entry = target.entry,
                            repository = ServiceLocator.streamsRepository(applicationContext),
                            /* Para series: pide /meta y convierte el póster de
                             * Cinemeta (tt…) en un episodio jugable (tt…:S:E). */
                            client = ServiceLocator.addonClient(),
                            settings = ServiceLocator.settings(applicationContext),
                        )
                    }
                }
            }
            val detailsViewModel: DetailsViewModel = viewModel(
                key = "details:${target.entry.id}",
                factory = factory,
            )

            DetailsDialog(
                entry = target.entry,
                viewModel = detailsViewModel,
                openOnSources = target.openOnSources,
                onDismiss = { details = null },
                onPlay = { startIndex ->
                    details = null
                    /* ids de episodio incluidos: el ViewModel es el que sabe si
                     * se eligió un capítulo o se reproduce el título entero. */
                    onPlay(detailsViewModel.playbackRequest(startIndex))
                },
            )
        }

        if (showSettings) {
            ServerSettingsDialog(
                settings = settings,
                onDismiss = { showSettings = false },
                onSaved = {
                    showSettings = false
                    /* El catálogo curado no cambia, pero un catálogo remoto sí:
                     * se invalida para que la próxima carga use el servidor
                     * nuevo. */
                    ServiceLocator.catalogRepository(applicationContext).invalidate()
                    homeViewModel.refresh()
                },
            )
        }
    }

    private fun openPlayer(request: PlaybackRequest) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ID, request.id)
                .putExtra(PlayerActivity.EXTRA_TYPE, request.type)
                .putExtra(PlayerActivity.EXTRA_TITLE, request.title)
                .putExtra(PlayerActivity.EXTRA_IS_LIVE, request.isLive)
                .putExtra(PlayerActivity.EXTRA_START_INDEX, request.startIndex),
        )
    }

    /** Qué título tiene abierta la hoja de detalles y en qué sección entrar. */
    private data class DetailsTarget(
        val entry: MediaEntry,
        val openOnSources: Boolean,
    )
}
