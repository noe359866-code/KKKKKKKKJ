package com.tanasi.streamflix.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.repo.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val rows: List<CatalogRow>) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

/**
 * Estado del catálogo.
 *
 * Sin `Flow` del repositorio a propósito: el catálogo se carga una vez por
 * proceso (las filas cambian poco) y se invalida explícitamente con [refresh]
 * cuando el usuario cambia de servidor o pulsa "reintentar".
 */
class HomeViewModel(
    private val repository: CatalogRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value = HomeUiState.Loading
            try {
                val rows = repository.load().filter { it.items.isNotEmpty() }
                mutableState.value = if (rows.isEmpty()) {
                    HomeUiState.Error("El catálogo está vacío")
                } else {
                    HomeUiState.Ready(rows)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = HomeUiState.Error(error.message ?: "Error desconocido")
            }
        }
    }

    fun refresh() {
        repository.invalidate()
        load()
    }
}
