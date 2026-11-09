package com.tanasi.streamflix.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanasi.streamflix.data.model.NoSourceDiagnosis
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
     * @param subtitles pistas de texto que llegaron **con** las fuentes. Van en
     *   el mismo estado a propósito: el reproductor las necesita antes de crear
     *   el `MediaItem`, y esperar una segunda petición retrasaría el vídeo.
     */
    data class Ready(
        val sources: List<StreamSource>,
        val subtitles: List<SubtitleTrack> = emptyList(),
        /** Cada carga exitosa identifica la sesión incluso si las fuentes son iguales. */
        val generation: Int = 0,
    ) : PlayerUiState

    /** Sin fuentes (todos los proveedores fallaron) o error de red. */
    data class Unavailable(val message: String) : PlayerUiState
}

/**
 * Carga las fuentes del título antes de crear el `ExoPlayer`.
 *
 * Se separa del composable para que un cambio de resolución o densidad en la
 * TV no vuelva a pedir las fuentes al servidor: el `ViewModel` sobrevive al
 * cambio de configuración.
 *
 * [current] es la fuente de verdad de *qué* se está viendo, no el `Intent`.
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
     * Se incrementa con cada carga: si dos peticiones se solapan, la respuesta
     * vieja no debe pisar las fuentes de la actual.
     */
    private var loadTicket = 0

    init {
        load()
    }

    /** Carga (o reintenta) las fuentes del título actual. */
    fun load() {
        loadJob?.cancel()
        loadTicket += 1
        val ticket = loadTicket
        val request = mutableCurrent.value
        mutableState.value = PlayerUiState.Loading

        loadJob = viewModelScope.launch {
            val result = repository.streamsFor(request.type, request.id)
            if (ticket != loadTicket) return@launch
            mutableState.value = when (result) {
                is StreamsResult.Ready -> PlayerUiState.Ready(result.sources, subtitles = result.subtitles, generation = ticket)
                is StreamsResult.Empty -> PlayerUiState.Unavailable(
                    NoSourceDiagnosis.explain(result.providers),
                )
                is StreamsResult.Failure -> PlayerUiState.Unavailable(result.message)
            }
        }
    }
}
