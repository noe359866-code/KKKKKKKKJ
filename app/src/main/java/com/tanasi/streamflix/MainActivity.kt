package com.tanasi.streamflix

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.Profile
import com.tanasi.streamflix.data.model.playbackRequest
import com.tanasi.streamflix.di.ServiceLocator
import com.tanasi.streamflix.ui.details.DetailsDialog
import com.tanasi.streamflix.ui.details.DetailsViewModel
import com.tanasi.streamflix.ui.home.HomeScreen
import com.tanasi.streamflix.ui.home.HomeViewModel
import com.tanasi.streamflix.ui.player.PlayerActivity
import com.tanasi.streamflix.ui.profile.ProfileGate
import com.tanasi.streamflix.ui.settings.ServerSettingsDialog
import com.tanasi.streamflix.ui.theme.StreamflixTheme
import com.tanasi.streamflix.ui.update.UpdateDialog
import com.tanasi.streamflix.update.AppUpdateManager
import com.tanasi.streamflix.update.UpdateInfo

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
                Profiles(
                    onPlay = { request, profileId -> openPlayer(request, profileId) },
                )
            }
        }
    }

    /**
     * Perfiles ("¿Quién está viendo?").
     *
     * Al arrancar se entra directo al último perfil usado; el selector aparece
     * la primera vez (con "Espectador" ya creado) y siempre que se pulse el
     * avatar de la barra superior. Cada perfil tiene su propio "Seguir viendo",
     * que es lo que hace que la tele se comporte como si fueran dos personas.
     */
    @Composable
    private fun Profiles(onPlay: (PlaybackRequest, String) -> Unit) {
        val store = remember { ServiceLocator.profileStore(applicationContext) }
        var profiles by remember { mutableStateOf(store.ensureDefault()) }
        var active by remember { mutableStateOf(store.activeProfile()) }
        var pickerOpen by remember { mutableStateOf(false) }

        val current = active
        if (current == null || pickerOpen) {
            ProfileGate(
                profiles = profiles,
                onSelect = { profile ->
                    store.select(profile.id)
                    active = profile
                    pickerOpen = false
                    profiles = store.profiles()
                },
                onCreate = { name ->
                    store.create(name)?.let { created ->
                        store.select(created.id)
                        active = created
                        pickerOpen = false
                    }
                    profiles = store.profiles()
                },
                onDelete = { profile ->
                    store.delete(profile.id)
                    if (active?.id == profile.id) active = null
                    profiles = store.profiles()
                },
                /* Salir del selector sólo tiene sentido si ya había un perfil:
                 * en el primer arranque hay que elegir uno. */
                onDismiss = if (pickerOpen && current != null) {
                    { pickerOpen = false }
                } else {
                    null
                },
            )
            return
        }

        Catalogue(
            profile = current,
            onPlay = { request -> onPlay(request, current.id) },
            onSwitchProfile = { pickerOpen = true },
        )
    }

    /**
     * Catálogo + hojas del perfil activo.
     *
     * La hoja de detalles es estado de UI (`DetailsTarget`) y no una pantalla
     * aparte: se abre encima del Home, conserva el foco de la tarjeta que la
     * lanzó y se cierra volviendo exactamente ahí.
     */
    @Composable
    private fun Catalogue(
        profile: Profile,
        onPlay: (PlaybackRequest) -> Unit,
        onSwitchProfile: () -> Unit,
    ) {
        val settings = remember { ServiceLocator.settings(applicationContext) }
        val homeFactory = remember {
            viewModelFactory {
                initializer {
                    HomeViewModel(
                        repository = ServiceLocator.catalogRepository(applicationContext),
                        /* El hero pide la ficha del título enfocado para tener
                         * backdrop 16:9 y sinopsis aunque el catálogo no las traiga. */
                        client = ServiceLocator.contentClient(applicationContext),
                        baseUrl = { ServiceLocator.settings(applicationContext).baseUrl },
                    )
                }
            }
        }
        val homeViewModel: HomeViewModel = viewModel(factory = homeFactory)

        /* "Seguir viendo" del perfil activo. Se relee al volver del reproductor
         * (ON_RESUME), que es cuando el progreso acaba de guardarse. */
        val progressStore = remember { ServiceLocator.watchProgressStore(applicationContext) }
        var continueWatching by remember(profile.id) {
            mutableStateOf(progressStore.entriesFor(profile.id))
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            continueWatching = progressStore.entriesFor(profile.id)
        }

        var details by remember { mutableStateOf<DetailsTarget?>(null) }
        /* Primer arranque autónomo: no pide IP, dominio ni un backend. */
        var showSettings by remember { mutableStateOf(false) }
        var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

        /*
         * Primer arranque: se instalan los addons recomendados (Streaming
         * Catalogs) en segundo plano. Sin ellos faltan las filas por plataforma
         * (Netflix, Disney+, HBO Max…) y el Home se queda sólo con los
         * proveedores. Cinemeta no se instala: ya viene en el motor local.
         */
        LaunchedEffect(Unit) {
            val manager = ServiceLocator.stremioAddonManager(applicationContext)
            if (manager.seedDefaultAddons()) {
                ServiceLocator.catalogRepository(applicationContext).invalidate()
                homeViewModel.refreshInBackground()
            }
        }

        // Comprobación automática de actualización al iniciar la app (100% autónoma)
        LaunchedEffect(Unit) {
            if (settings.autoCheckUpdates) {
                val now = System.currentTimeMillis()
                // Throttle: como mucho una comprobación automática cada 4 horas
                if (now - settings.lastUpdateCheckTime > 4 * 3600 * 1000L) {
                    try {
                        val update = AppUpdateManager().checkForUpdate()
                        settings.lastUpdateCheckTime = now
                        if (update != null) {
                            pendingUpdate = update
                        }
                    } catch (_: Exception) {
                        // En caso de fallo de red, continuar sin interrumpir al usuario
                    }
                }
            }
        }

        HomeScreen(
            viewModel = homeViewModel,
            onPlay = onPlay,
            onOpenDetails = { entry, openOnSources ->
                details = DetailsTarget(entry, openOnSources)
            },
            onOpenSettings = { showSettings = true },
            profile = profile,
            continueWatching = continueWatching,
            onSwitchProfile = onSwitchProfile,
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
                            /* Episodios del proveedor local o del backend opcional. */
                            client = ServiceLocator.contentClient(applicationContext),
                            settings = ServiceLocator.settings(applicationContext),
                        )
                    }
                }
            }
            val detailsViewModel: DetailsViewModel = viewModel(
                key = "details:${settings.autonomous}:${settings.baseUrl}:${target.entry.id}",
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
                    /* Descarta fichas y fuentes del modo anterior. */
                    details = null
                    ServiceLocator.catalogRepository(applicationContext).invalidate()
                    homeViewModel.refresh()
                },
                onUpdateFound = { update ->
                    showSettings = false
                    pendingUpdate = update
                },
            )
        }

        pendingUpdate?.let { update ->
            UpdateDialog(
                updateInfo = update,
                onDismiss = { pendingUpdate = null },
            )
        }
    }

    private fun openPlayer(request: PlaybackRequest, profileId: String) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ID, request.id)
                .putExtra(PlayerActivity.EXTRA_TYPE, request.type)
                .putExtra(PlayerActivity.EXTRA_TITLE, request.title)
                .putExtra(PlayerActivity.EXTRA_IS_LIVE, request.isLive)
                .putExtra(PlayerActivity.EXTRA_START_INDEX, request.startIndex)
                /* Con estos tres extras el reproductor puede guardar el
                 * progreso del perfil y su arte para "Seguir viendo". */
                .putExtra(PlayerActivity.EXTRA_PROFILE_ID, profileId)
                .putExtra(PlayerActivity.EXTRA_POSTER, request.poster)
                .putExtra(PlayerActivity.EXTRA_BACKDROP, request.backdrop),
        )
    }

    /** Qué título tiene abierta la hoja de detalles y en qué sección entrar. */
    private data class DetailsTarget(
        val entry: MediaEntry,
        val openOnSources: Boolean,
    )
}
