package com.tanasi.streamflix.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.data.repo.StreamsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Estado del selector de fuentes de la hoja de detalles. */
sealed interface SourcesUiState {
    data object Loading : SourcesUiState

    /**
     * Fuentes en el orden que decidió el servidor.
     *
     * @param selected índice elegido por el usuario (0 = máxima prioridad).
     */
    data class Ready(val sources: List<StreamSource>, val selected: Int = 0) : SourcesUiState {
        val current: StreamSource? get() = sources.getOrNull(selected)
    }

    data class Unavailable(val message: String) : SourcesUiState
}

/**
 * Carga las fuentes **antes** de abrir el reproductor.
 *
 * Es el otro lado del failover: el usuario ve qué servidores hay, cuál es el
 * prioritario y puede arrancar por el tercero si el primero le va mal en su
 * red. Lo que elija se pasa como índice a `PlaybackRequest.startIndex` y el
 * failover sigue bajando desde ahí.
 *
 * Vive separado de la UI para que abrir/cerrar la hoja no repita la petición ni
 * pierda la selección al rotar el móvil.
 */
class DetailsViewModel(
    val entry: MediaEntry,
    private val repository: StreamsRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow<SourcesUiState>(SourcesUiState.Loading)
    val state: StateFlow<SourcesUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value = SourcesUiState.Loading
            mutableState.value = when (val result = repository.streamsFor(entry.type, entry.id)) {
                is StreamsResult.Ready -> SourcesUiState.Ready(result.sources)
                StreamsResult.Empty -> SourcesUiState.Unavailable(
                    "Ningún proveedor devolvió una fuente para este título.",
                )

                is StreamsResult.Failure -> SourcesUiState.Unavailable(result.message)
            }
        }
    }

    fun select(index: Int) {
        val ready = mutableState.value as? SourcesUiState.Ready ?: return
        if (index in ready.sources.indices) {
            mutableState.value = ready.copy(selected = index)
        }
    }

    /** Índice con el que arrancar el reproductor. */
    fun selectedIndex(): Int = (mutableState.value as? SourcesUiState.Ready)?.selected ?: 0
}
