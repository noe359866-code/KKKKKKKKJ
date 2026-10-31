package com.tanasi.streamflix.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanasi.streamflix.data.model.PlaybackChannel
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.data.model.SubtitleTrack
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.data.repo.StreamsResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlayerUiState {
    data object Loading : PlayerUiState

    /**
     * Hay al menos una fuente: el reproductor puede arrancar.
     *
     * @param tuning `true` mientras se resuelven las fuentes de un canal recién
     *   sintonizado y en pantalla sigue el anterior. Es lo que hace que zapear
     *   se sienta como un televisor (la imagen cambia cuando engancha la nueva
     *   señal) en vez de como una app que se queda en negro medio segundo.
     * @param subtitles pistas de texto que llegaron **con** las fuentes. Van en
     *   el mismo estado a propósito: el reproductor las necesita antes de crear
     *   el `MediaItem`, y esperar una segunda petición retrasaría el vídeo.
     */
    data class Ready(
        val sources: List<StreamSource>,
        val tuning: Boolean = false,
        val subtitles: List<SubtitleTrack> = emptyList(),
    ) : PlayerUiState

    /** Sin fuentes (todos los proveedores fallaron) o error de red. */
    data class Unavailable(val message: String) : PlayerUiState
}

/**
 * Carga las fuentes del título antes de crear el `ExoPlayer` y **cambia de
 * canal** sin salir del reproductor.
 *
 * Se separa del composable para que rotar el móvil (o cambiar de resolución en
 * TV) no vuelva a pedir el array al servidor: el `ViewModel` sobrevive al
 * cambio de configuración.
 *
 * [current] es la fuente de verdad de *qué* se está viendo: la pantalla pinta el
 * título, la parrilla y el rótulo desde ahí, no desde el `Intent`. Zapear es
 * reemplazar [current] y volver a resolver fuentes; el `ExoPlayer` se recrea
 * porque su composable está memorizado con `sources`.
 */
class PlayerViewModel(
    private val repository: StreamsRepository,
    request: PlaybackRequest,
) : ViewModel() {

    private val mutableCurrent = MutableStateFlow(request)
    val current: StateFlow<PlaybackRequest> = mutableCurrent.asStateFlow()

    private val mutableState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Se incrementa con cada carga: si el usuario zapea rápido, la respuesta de
     * un canal que ya dejó atrás no debe pisar las fuentes del canal actual.
     */
    private var loadTicket = 0

    init {
        load()
    }

    /** Reintento sobre el canal en uso (botón "Reintentar"). */
    fun load() {
        loadRequest(mutableCurrent.value, keepPicture = false)
    }

    /**
     * Sintoniza [channel] conservando el resto del estado del reproductor.
     *
     * Un directo no se "continúa": el canal nuevo arranca en la señal que el
     * proveedor dé ahora, sin posición ni fuente preferida del canal anterior.
     */
    fun tune(channel: PlaybackChannel) {
        val base = mutableCurrent.value
        if (channel.id == base.id) return
        loadRequest(
            keepPicture = true,
            request = base.copy(
                id = channel.id,
                type = channel.type,
                title = channel.title,
                isLive = channel.isLive,
                startIndex = 0,
                startPositionMs = 0L,
                /* El arte del canal nuevo, no el del anterior: es lo que se
                 * guarda si más adelante se retoma el título. */
                poster = channel.poster,
                backdrop = null,
            ),
        )
    }

    /** Vuelve a un [PlaybackRequest] completo (p. ej. deshacer un zapeo). */
    fun restore(request: PlaybackRequest) {
        loadRequest(request, keepPicture = true)
    }

    /**
     * @param keepPicture deja el vídeo anterior en pantalla mientras se
     *   resuelven las fuentes nuevas (zapeo). Con `false` la pantalla vuelve al
     *   estado de carga: es lo correcto al reintentar, porque no hay nada
     *   válido que conservar.
     */
    private fun loadRequest(request: PlaybackRequest, keepPicture: Boolean) {
        mutableCurrent.value = request
        loadJob?.cancel()
        loadTicket += 1
        val ticket = loadTicket

        val previous = mutableState.value
        if (keepPicture && previous is PlayerUiState.Ready) {
            mutableState.value = previous.copy(tuning = true)
        } else {
            mutableState.value = PlayerUiState.Loading
        }

        loadJob = viewModelScope.launch {
            val result = repository.streamsFor(request.type, request.id)
            if (ticket != loadTicket) return@launch
            mutableState.value = when (result) {
                is StreamsResult.Ready -> PlayerUiState.Ready(result.sources, subtitles = result.subtitles)
                StreamsResult.Empty -> PlayerUiState.Unavailable(
                    "Ningún proveedor devolvió una fuente reproducible.",
                )
                is StreamsResult.Failure -> PlayerUiState.Unavailable(result.message)
            }
        }
    }
}
