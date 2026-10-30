package com.tanasi.streamflix.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.model.EpisodeOption
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.MediaType
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.SeasonGroup
import com.tanasi.streamflix.data.model.hasEpisodeNumber
import com.tanasi.streamflix.data.model.playbackRequest
import com.tanasi.streamflix.data.model.toSeasonGroups
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Esclarece qué hacer con los episodios de una serie.
 *
 * Un póster de Cinemeta identifica la **serie** (`tt0944947`), no un capítulo, y
 * los scrapers de series necesitan `temporada:episodio` para buscar. Por eso
 * este estado existe: al abrir una serie se pide `/meta/{type}/{id}.json`, se
 * listan los episodios y el reproductor pide el capítulo elegido
 * (`tt0944947:1:2`).
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
    data class Unavailable(val message: String, val selected: EpisodeOption? = null) : EpisodesUiState
}

/**
 * Estado de la ficha de un título: metadatos del proveedor y episodios.
 *
 * **No busca fuentes.** La lista de servidores ya no se enseña antes de
 * reproducir: se pulsa *Reproducir* y el reproductor (`PlayerViewModel`) pide
 * las fuentes y hace el failover por su cuenta. Aquí sólo se decide **qué id**
 * se reproduce (`tt0944947:1:2` cuando el usuario eligió capítulo).
 *
 * También es el sitio donde vive el texto del episodio elegido: la ficha lo
 * pinta en el botón ("Reproducir · T1 E2") sin pedir nada más.
 */
class DetailsViewModel(
    val entry: MediaEntry,
    private val client: ContentClient? = null,
    private val settings: AddonSettings? = null,
) : ViewModel() {

    private val mutableMetadata = MutableStateFlow<RemoteMeta?>(null)
    val metadata: StateFlow<RemoteMeta?> = mutableMetadata.asStateFlow()

    private val mutableEpisodes = MutableStateFlow<EpisodesUiState>(EpisodesUiState.Hidden)
    val episodes: StateFlow<EpisodesUiState> = mutableEpisodes.asStateFlow()

    /**
     * Id que se pide al reproductor: el del título, o el del episodio cuando el
     * usuario eligió uno (`tt0944947` → `tt0944947:1:2`).
     */
    @Volatile
    private var requestedId: String = entry.id

    /** Sólo se resuelven episodios cuando el id no los trae ya. */
    private val needsMetadata: Boolean
        get() = !entry.isLive && !hasEpisodeNumber(entry.type, entry.id)

    private var episodesJob: Job? = null

    init {
        if (needsMetadata && client != null && settings != null) {
            loadEpisodes()
        }
    }

    /**
     * Pide la ficha al servidor para conocer los episodios y arranca con el
     * primero (o con el más reciente, si el servidor marcó uno).
     */
    private fun loadEpisodes() {
        val addon = client ?: return
        val addonSettings = settings ?: return

        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            mutableEpisodes.value = EpisodesUiState.Loading

            val meta = try {
                addon.meta(addonSettings.baseUrl, entry.type, entry.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }

            mutableMetadata.value = meta
            val seasons = (meta?.videos ?: emptyList()).toSeasonGroups()
            if (seasons.isEmpty()) {
                if (entry.mediaType == MediaType.Movie) {
                    mutableEpisodes.value = EpisodesUiState.Hidden
                } else {
                    /* No inventar capítulos ni reproducir en silencio el
                     * episodio 1: se ofrece indicarlos a mano. */
                    mutableEpisodes.value = EpisodesUiState.Unavailable(
                        "El proveedor no publicó la lista de episodios. Indica temporada y episodio, o reproduce el título tal cual.",
                    )
                }
                return@launch
            }

            val first = seasons.first().episodes.first()
            mutableEpisodes.value = EpisodesUiState.Ready(seasons = seasons, selected = first)
            requestedId = first.idFor(entry.id)
        }
    }

    /** El usuario cambió de episodio. */
    fun selectEpisode(episode: EpisodeOption) {
        val ready = mutableEpisodes.value as? EpisodesUiState.Ready ?: return
        if (ready.selected == episode) return
        mutableEpisodes.value = ready.copy(selected = episode)
        requestedId = episode.idFor(entry.id)
    }

    fun selectManualEpisode(episode: EpisodeOption) {
        val unavailable = mutableEpisodes.value as? EpisodesUiState.Unavailable ?: return
        if (episode.season !in 0..100 || episode.episode !in 1..10000) return
        mutableEpisodes.value = unavailable.copy(selected = episode)
        requestedId = episode.idFor(entry.id)
    }

    /** Cambia la temporada visible y deja elegido su primer capítulo. */
    fun selectSeason(season: Int) {
        val ready = mutableEpisodes.value as? EpisodesUiState.Ready ?: return
        if (ready.selectedSeason == season) return
        val first = ready.seasons.firstOrNull { it.season == season }?.episodes?.firstOrNull() ?: return
        mutableEpisodes.value = ready.copy(selected = first)
        requestedId = first.idFor(entry.id)
    }

    /** Episodio elegido, si la ficha mostró la lista. */
    fun selectedEpisode(): EpisodeOption? = when (val current = mutableEpisodes.value) {
        is EpisodesUiState.Ready -> current.selected
        is EpisodesUiState.Unavailable -> current.selected
        else -> null
    }

    /** Texto del botón principal: "T1 E2" cuando hay capítulo elegido. */
    fun episodeLabel(): String? = selectedEpisode()?.playLabel

    /**
     * Lo que hay que abrir en el reproductor: el id (con `:temporada:episodio`
     * si es una serie). El índice es siempre 0: el reproductor pide las fuentes
     * y empieza por la de mayor prioridad, sin preguntar.
     */
    fun playbackRequest(): PlaybackRequest {
        val base = entry.playbackRequest(0)
        return base.copy(
            id = requestedId,
            title = selectedEpisode()?.let { episode -> "${displayTitle()} · ${episode.label}" } ?: displayTitle(),
            /* Sólo se reanuda el título que se estaba viendo: si el usuario
             * elige otro capítulo, empieza desde el principio. */
            startPositionMs = if (requestedId == entry.id) base.startPositionMs else 0L,
        )
    }

    private fun displayTitle(): String = mutableMetadata.value?.name?.takeIf { it.isNotBlank() } ?: entry.title
}
