package com.tanasi.streamflix.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanasi.streamflix.data.model.EpisodeOption
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.MediaType
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.SeasonGroup
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.data.model.toSeasonGroups
import com.tanasi.streamflix.data.remote.AddonClient
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.data.repo.StreamsResult
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.CancellationException
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
 * Esclarece qué hacer con los episodios de una serie.
 *
 * Un póster de Cinemeta identifica la **serie** (`tt0944947`), no un capítulo, y
 * los scrapers de series necesitan `temporada:episodio` para buscar. Por eso
 * este estado existe: al abrir una serie se pide `/meta/{type}/{id}.json`, se
 * listan los episodios y las fuentes se piden para el capítulo elegido
 * (`tt0944947:1:2`). Lo que ocurre después —elegir servidor, reproducir,
 * failover— es idéntico al caso de una película.
 */
sealed interface EpisodesUiState {
    /** No aplica: película, canal en directo o un id que ya trae temporada. */
    data object Hidden : EpisodesUiState

    data object Loading : EpisodesUiState

    /**
     * @param seasons temporadas tal como las devolvió el servidor (ordenadas).
     * @param selected temporada visible en la fila de chips.
     */
    data class Ready(
        val seasons: List<SeasonGroup>,
        val selected: EpisodeOption,
    ) : EpisodesUiState {
        val selectedSeason: Int get() = selected.season

        val episodes: List<EpisodeOption>
            get() = seasons.firstOrNull { it.season == selectedSeason }?.episodes.orEmpty()
    }

    /** Es una serie, pero el servidor no devolvió episodios: se intenta igual. */
    data class Unavailable(val message: String) : EpisodesUiState
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
    private val client: AddonClient? = null,
    private val settings: AddonSettings? = null,
) : ViewModel() {

    private val mutableState = MutableStateFlow<SourcesUiState>(SourcesUiState.Loading)
    val state: StateFlow<SourcesUiState> = mutableState.asStateFlow()

    private val mutableEpisodes = MutableStateFlow<EpisodesUiState>(EpisodesUiState.Hidden)
    val episodes: StateFlow<EpisodesUiState> = mutableEpisodes.asStateFlow()

    /**
     * Id que se pide al addon: el del título, o el del episodio cuando el
     * usuario eligió uno (`tt0944947` → `tt0944947:1:2`).
     */
    @Volatile
    private var requestedId: String = entry.id

    /** Sólo se resuelven episodios cuando el id no los trae ya. */
    private val needsEpisodePicker: Boolean
        get() = entry.mediaType in setOf(MediaType.Series, MediaType.Anime) &&
            EPISODE_SUFFIX.matches(entry.id).not()

    init {
        if (needsEpisodePicker && client != null && settings != null) {
            loadEpisodes()
        } else {
            loadSources(entry.id)
        }
    }

    fun load() {
        if (mutableEpisodes.value is EpisodesUiState.Ready) {
            loadSources(requestedId)
        } else if (needsEpisodePicker && client != null && settings != null) {
            loadEpisodes()
        } else {
            loadSources(entry.id)
        }
    }

    /**
     * Pide la ficha al servidor para conocer los episodios y arranca con el
     * primero (o con el más reciente, si el servidor marcó uno).
     */
    private fun loadEpisodes() {
        val addon = client ?: return
        val addonSettings = settings ?: return

        viewModelScope.launch {
            mutableEpisodes.value = EpisodesUiState.Loading
            mutableState.value = SourcesUiState.Loading

            val meta = try {
                addon.meta(addonSettings.baseUrl, entry.type, entry.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }

            val seasons = (meta?.videos ?: emptyList()).toSeasonGroups()
            if (seasons.isEmpty()) {
                /* Sin lista de episodios no se bloquea la reproducción: se pide
                 * el id tal cual (el servidor puede resolverlo por búsqueda). */
                mutableEpisodes.value = EpisodesUiState.Unavailable(
                    "No se pudo cargar la lista de episodios. Se buscarán fuentes para la serie completa.",
                )
                loadSources(entry.id)
                return@launch
            }

            val first = seasons.first().episodes.first()
            mutableEpisodes.value = EpisodesUiState.Ready(seasons = seasons, selected = first)
            loadSources(first.idFor(entry.id))
        }
    }

    /** El usuario cambió de episodio: se recargan las fuentes de ese capítulo. */
    fun selectEpisode(episode: EpisodeOption) {
        val ready = mutableEpisodes.value as? EpisodesUiState.Ready ?: return
        if (ready.selected == episode) return

        mutableEpisodes.value = ready.copy(selected = episode)
        loadSources(episode.idFor(entry.id))
    }

    /** Cambia la temporada visible (no recarga fuentes hasta elegir capítulo). */
    fun selectSeason(season: Int) {
        val ready = mutableEpisodes.value as? EpisodesUiState.Ready ?: return
        if (ready.selectedSeason == season) return
        val first = ready.seasons.firstOrNull { it.season == season }?.episodes?.firstOrNull() ?: return
        mutableEpisodes.value = ready.copy(selected = first)
        loadSources(first.idFor(entry.id))
    }

    private fun loadSources(id: String) {
        requestedId = id
        viewModelScope.launch {
            mutableState.value = SourcesUiState.Loading
            mutableState.value = when (val result = repository.streamsFor(entry.type, id)) {
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

    /** Episodio elegido, si la hoja mostró la lista. */
    fun selectedEpisode(): EpisodeOption? = (mutableEpisodes.value as? EpisodesUiState.Ready)?.selected

    /**
     * Lo que hay que abrir en el reproductor: id (con `:temporada:episodio` si
     * es una serie) + índice de fuente elegido. El failover sigue desde ahí
     * hacia abajo, sin reordenar.
     */
    fun playbackRequest(startIndex: Int = selectedIndex()): PlaybackRequest =
        entry.playbackRequest(startIndex).copy(
            id = requestedId,
            title = selectedEpisode()?.let { episode -> "${entry.title} · ${episode.label}" } ?: entry.title,
        )

    private companion object {
        /** `tt0944947:1:2`, `gnula:x:2:5`… ya traen capítulo. */
        val EPISODE_SUFFIX = Regex(""":\d+:\d+$""")
    }
}
