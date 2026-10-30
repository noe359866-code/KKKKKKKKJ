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
import com.tanasi.streamflix.data.model.toChannelQueue
import com.tanasi.streamflix.data.model.PlaybackQueueCodec
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
     * La hoja de detalles es estado de UI (el `MediaEntry` abierto) y no una pantalla
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

        var details by remember { mutableStateOf<MediaEntry?>(null) }
        /* Títulos de la fila desde la que se abrió la ficha: la parrilla que el
         * reproductor usará para `CH+`/`CH−`. */
        var detailsQueue by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
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
            onOpenDetails = { entry, siblings ->
                detailsQueue = siblings
                details = entry
            },
            onOpenSettings = { showSettings = true },
            profile = profile,
            continueWatching = continueWatching,
            onSwitchProfile = onSwitchProfile,
        )

        details?.let { entry ->
            /* Un `ViewModel` por título: si el usuario abre, cierra y vuelve a
             * abrir la misma tarjeta, no se repiten los metadatos ni se pierde
             * el capítulo que había elegido. */
            val factory = remember(entry.id) {
                viewModelFactory {
                    initializer {
                        DetailsViewModel(
                            entry = entry,
                            /* Episodios del proveedor local o del backend opcional. */
                            client = ServiceLocator.contentClient(applicationContext),
                            settings = ServiceLocator.settings(applicationContext),
                        )
                    }
                }
            }
            val detailsViewModel: DetailsViewModel = viewModel(
                key = "details:${settings.autonomous}:${settings.baseUrl}:${entry.id}",
                factory = factory,
            )

            DetailsDialog(
                entry = entry,
                viewModel = detailsViewModel,
                onDismiss = {
                    details = null
                    detailsQueue = emptyList()
                },
                onPlay = {
                    details = null
                    /* Sin elegir servidor: el reproductor pide las fuentes y
                     * hace el failover. El id ya lleva el capítulo si tocaba. */
                    onPlay(detailsViewModel.playbackRequest(detailsQueue.toChannelQueue()))
                    detailsQueue = emptyList()
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
                .putExtra(PlayerActivity.EXTRA_START_POSITION, request.startPositionMs)
                /* Con estos tres extras el reproductor puede guardar el
                 * progreso del perfil y su arte para "Seguir viendo". */
                .putExtra(PlayerActivity.EXTRA_PROFILE_ID, profileId)
                .putExtra(PlayerActivity.EXTRA_POSTER, request.poster)
                .putExtra(PlayerActivity.EXTRA_BACKDROP, request.backdrop)
                /* Parrilla para zapear dentro del reproductor. Serializada con
                 * kotlinx: un `ArrayList<String>` de extras obligaría a
                 * reconstruir id/título/carátula a mano en la otra punta. */
                .putExtra(PlayerActivity.EXTRA_QUEUE, PlaybackQueueCodec.encode(request.queue)),
        )
    }

}
