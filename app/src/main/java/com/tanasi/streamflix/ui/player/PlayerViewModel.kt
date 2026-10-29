package com.tanasi.streamflix.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.data.repo.StreamsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlayerUiState {
    data object Loading : PlayerUiState

    /** Hay al menos una fuente: el reproductor puede arrancar. */
    data class Ready(val sources: List<StreamSource>) : PlayerUiState

    /** Sin fuentes (todos los proveedores fallaron) o error de red. */
    data class Unavailable(val message: String) : PlayerUiState
}

/**
 * Carga las fuentes del título antes de crear el `ExoPlayer`.
 *
 * Se separa del composable para que cambiar de resolución en TV no vuelva a
 * pedir el array al servidor: el `ViewModel` sobrevive al
 * cambio de configuración.
 */
class PlayerViewModel(
    private val repository: StreamsRepository,
    private val request: PlaybackRequest,
) : ViewModel() {

    private val mutableState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value = PlayerUiState.Loading
            when (val result = repository.streamsFor(request.type, request.id)) {
                is StreamsResult.Ready -> mutableState.value = PlayerUiState.Ready(result.sources)
                StreamsResult.Empty -> mutableState.value = PlayerUiState.Unavailable(
                    "Ningún proveedor devolvió una fuente reproducible.",
                )
                is StreamsResult.Failure -> mutableState.value = PlayerUiState.Unavailable(result.message)
            }
        }
    }
}
