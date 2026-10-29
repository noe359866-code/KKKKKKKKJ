package com.tanasi.streamflix.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanasi.streamflix.data.model.HomeCatalog
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.Spotlight
import com.tanasi.streamflix.data.repo.CatalogRepository
import com.tanasi.streamflix.data.repo.CatalogResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val catalog: HomeCatalog) : HomeUiState

    /**
     * @param needsSetup el problema se resuelve configurando la URL del addon;
     *   la UI ofrece el botón de ajustes además de reintentar.
     */
    data class Error(val message: String, val needsSetup: Boolean = false) : HomeUiState
}

/**
 * Estado del Home: filas + el título que ocupa el hero.
 *
 * El hero se mantiene **fuera** de [HomeUiState] a propósito. En una TV, mover
 * la flecha sobre una fila emite un evento de foco por tarjeta; si eso cambiase
 * el estado del catálogo, la `LazyColumn` se recompondría entera decenas de veces
 * por segundo. Aquí sólo cambia un `StateFlow` pequeño y el hero se recompone
 * solo.
 */
class HomeViewModel(
    private val repository: CatalogRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    private val mutableHero = MutableStateFlow<Spotlight?>(null)
    val hero: StateFlow<Spotlight?> = mutableHero.asStateFlow()

    private var loadJob: Job? = null
    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery.asStateFlow()

    init {
        load()
    }

    fun search(value: String) {
        mutableQuery.value = value.trim().take(150)
        load()
    }

    private fun showCatalog(catalog: HomeCatalog) {
        val previous = mutableHero.value
        mutableHero.value = previous?.let { catalog.spotlightFor(it.rowId, it.entry.id) } ?: catalog.featured
        mutableState.value = HomeUiState.Ready(catalog)
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.value = HomeUiState.Loading
            try {
                when (val result = repository.load(mutableQuery.value.takeIf { it.isNotBlank() }) { partial ->
                    if (isActive) showCatalog(partial)
                }) {
                    is CatalogResult.Ready -> {
                        if (result.catalog.isEmpty) {
                            mutableState.value = HomeUiState.Error(
                                "No hay títulos disponibles. Revisa Internet y los proveedores.",
                            )
                            return@launch
                        }
                        showCatalog(result.catalog)
                    }

                    is CatalogResult.Failure -> {
                        mutableState.value = HomeUiState.Error(result.message, result.needsSetup)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = HomeUiState.Error(error.message ?: "Error desconocido")
            }
        }
    }

    /**
     * El usuario enfocó una tarjeta: el hero pasa a mostrarla.
     *
     * Se compara **(fila, id)** antes de emitir, por dos motivos:
     *  - mantener la flecha pulsada sobre la misma tarjeta no debe regenerar el
     *    `StateFlow` en cada frame (en una TV el foco llega decenas de veces por
     *    segundo);
     *  - con catálogos del servidor, el mismo título aparece en varias filas
     *    (`movie/top` y `movie/imdbRating`), así que comparar sólo el id dejaría
     *    el hero con la etiqueta de la fila equivocada.
     */
    fun onCardFocused(rowId: String, entry: MediaEntry) {
        val catalog = (mutableState.value as? HomeUiState.Ready)?.catalog ?: return
        val spotlight = catalog.spotlightFor(rowId, entry.id) ?: return
        val current = mutableHero.value
        val changed = current == null ||
            current.rowId != spotlight.rowId ||
            current.entry.id != spotlight.entry.id
        if (changed) mutableHero.value = spotlight
    }

    /** Vuelve al destacado por defecto (p. ej. al abrir los ajustes). */
    fun resetHero() {
        val catalog = (mutableState.value as? HomeUiState.Ready)?.catalog ?: return
        mutableHero.value = catalog.featured
    }

    fun refresh() {
        repository.invalidate()
        load()
    }
}
