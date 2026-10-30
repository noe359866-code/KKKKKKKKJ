package com.tanasi.streamflix.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.model.CinemetaSuggestions
import com.tanasi.streamflix.data.model.HomeCatalog
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.Spotlight
import com.tanasi.streamflix.data.repo.CatalogRepository
import com.tanasi.streamflix.data.repo.CatalogResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /**
     * Cliente de metadatos para "adornar" el hero. Es opcional: sin él la app
     * funciona igual, sólo que el fondo se queda con lo que trajo el catálogo.
     */
    private val client: ContentClient? = null,
    private val baseUrl: () -> String = { "" },
) : ViewModel() {

    private val mutableState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    private val mutableHero = MutableStateFlow<Spotlight?>(null)
    val hero: StateFlow<Spotlight?> = mutableHero.asStateFlow()

    private var loadJob: Job? = null
    private var heroJob: Job? = null

    /** Título cuya ficha ya está en camino: evita reiniciar el debounce. */
    private var heroJobKey: String? = null

    /** Fichas ya pedidas, por `tipo:id`: volver a una tarjeta no repite la petición. */
    private val heroDetails = mutableMapOf<String, MediaEntry>()

    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery.asStateFlow()

    /**
     * Recomendaciones de Cinemeta para el buscador.
     *
     * Se calculan **una vez** con el Home ya cargado y se quedan aunque el
     * usuario busque: son el punto de partida del buscador, no el resultado de
     * la búsqueda. Se rehacen al refrescar el catálogo.
     */
    private val mutableSuggestions = MutableStateFlow<List<MediaEntry>>(emptyList())
    val suggestions: StateFlow<List<MediaEntry>> = mutableSuggestions.asStateFlow()

    init {
        load()
    }

    fun search(value: String) {
        mutableQuery.value = value.trim().take(150)
        load()
    }

    private fun showCatalog(catalog: HomeCatalog) {
        /* Sin búsqueda activa las filas son el catálogo, no resultados. Se
         * queda la lista más completa que se haya visto: el Home llega por
         * partes y la primera puede traer sólo los canales guardados. */
        if (mutableQuery.value.isBlank()) {
            val next = CinemetaSuggestions.from(catalog.rows)
            if (next.size > mutableSuggestions.value.size) mutableSuggestions.value = next
        }
        val previous = mutableHero.value
        mutableHero.value = previous?.let { catalog.spotlightFor(it.rowId, it.entry.id) } ?: catalog.featured
        mutableState.value = HomeUiState.Ready(catalog)
        enrichHero(mutableHero.value)
    }

    /**
     * Pide `/meta` del título que ocupa el hero para completar lo que el
     * catálogo no trae: backdrop 16:9, sinopsis, nota y géneros.
     *
     * La fila de Cinemeta ya trae póster y backdrop; los canales en vivo no
     * tienen ficha. Se espera [HERO_DEBOUNCE_MS] porque en una TV el foco salta
     * de tarjeta en tarjeta y no tiene sentido pedir una ficha por cada salto.
     */
    private fun enrichHero(spotlight: Spotlight?) {
        val client = client ?: return
        val entry = spotlight?.entry ?: return
        if (entry.isLive) return
        if (!entry.backdrop.isNullOrBlank() && !entry.synopsis.isNullOrBlank()) return

        val key = "${entry.type}:${entry.id}"
        val merged = heroDetails[key]
        val current = mutableHero.value ?: return
        if (current.entry.id != entry.id) return
        if (merged != null) {
            mutableHero.value = current.copy(entry = merged)
            return
        }

        if (heroJobKey == key && heroJob?.isActive == true) return
        heroJobKey = key
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            delay(HERO_DEBOUNCE_MS)
            val meta = try {
                client.meta(baseUrl(), entry.type, entry.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@launch

            /* Sólo se rellenan huecos: el título, el id y el tipo del catálogo
             * mandan, porque son los que ya vio el usuario. */
            val enriched = entry.copy(
                poster = entry.poster ?: meta.poster,
                backdrop = entry.backdrop ?: meta.background,
                synopsis = entry.synopsis ?: meta.description?.takeIf { it.isNotBlank() },
                rating = entry.rating ?: meta.rating,
                genres = entry.genres.ifEmpty { meta.genres },
                runtime = entry.runtime ?: meta.runtime,
            )
            if (heroDetails.size < HERO_CACHE_LIMIT) heroDetails[key] = enriched

            val latest = mutableHero.value ?: return@launch
            if (latest.entry.id == entry.id) mutableHero.value = latest.copy(entry = enriched)
        }
    }

    fun load() = load(showLoading = true)

    /**
     * Recarga sin pasar por el estado de carga.
     *
     * Se usa cuando algo se instala en segundo plano (los addons recomendados
     * del primer arranque): vaciar la pantalla para volver a pintarla haría
     * parpadear un Home que el usuario ya está mirando.
     */
    fun refreshInBackground() = load(showLoading = false)

    private fun load(showLoading: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showLoading) mutableState.value = HomeUiState.Loading
            try {
                when (val result = repository.load(mutableQuery.value.takeIf { it.isNotBlank() }) { partial ->
                    if (isActive) showCatalog(partial)
                }) {
                    is CatalogResult.Ready -> {
                        if (result.catalog.isEmpty) {
                            mutableState.value = HomeUiState.Error(
                                "No hay títulos disponibles. Revisa la conexión e intenta de nuevo.",
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
        if (changed) {
            mutableHero.value = spotlight
            enrichHero(spotlight)
        }
    }

    /** Vuelve al destacado por defecto (p. ej. al abrir los ajustes). */
    fun resetHero() {
        val catalog = (mutableState.value as? HomeUiState.Ready)?.catalog ?: return
        mutableHero.value = catalog.featured
        enrichHero(mutableHero.value)
    }

    fun refresh() {
        repository.invalidate()
        heroDetails.clear()
        mutableSuggestions.value = emptyList()
        load()
    }

    private companion object {
        /** Espera antes de pedir la ficha del hero (ms). */
        const val HERO_DEBOUNCE_MS = 280L

        /** Tope del mapa de fichas ya pedidas: el Home no crece sin límite. */
        const val HERO_CACHE_LIMIT = 80
    }
}
