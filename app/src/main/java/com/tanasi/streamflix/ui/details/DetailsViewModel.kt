package com.tanasi.streamflix.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.model.EpisodeOption
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.MediaType
import com.tanasi.streamflix.data.model.nextEpisodeAfter
import com.tanasi.streamflix.data.model.isEpisodeNotAiredYet
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.SeasonGroup
import com.tanasi.streamflix.data.model.hasEpisodeNumber
import com.tanasi.streamflix.data.model.playbackRequest
import com.tanasi.streamflix.data.model.toSeasonGroups
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    /**
     * Episodio que se estaba viendo (`serie:slug:1:3`), si la ficha se abrió
     * desde una tarjeta de «Seguir viendo».
     *
     * Sin esto se ofrecía el **primer capítulo de la serie** aunque el usuario
     * llevara seis visto: la tarjeta guarda el id del episodio, pero la ficha lo
     * descartaba y ponía el foco en el E1.
     */
    private val resumeEpisodeId: String? = null,
) : ViewModel() {

    private val mutableMetadata = MutableStateFlow<RemoteMeta?>(null)
    val metadata: StateFlow<RemoteMeta?> = mutableMetadata.asStateFlow()

    private val mediaType = MediaType.fromWire(entry.type)
    private val isSeries = mediaType == MediaType.Series || mediaType == MediaType.Anime
    private val needsEpisodeChoice = isSeries &&
        !hasEpisodeNumber(entry.type.lowercase(), entry.id)
    private val mutableEpisodes = MutableStateFlow<EpisodesUiState>(
        when {
            !needsEpisodeChoice -> EpisodesUiState.Hidden
            client != null && settings != null -> EpisodesUiState.Loading
            else -> EpisodesUiState.Unavailable(NO_METADATA_SOURCE)
        },
    )
    val episodes: StateFlow<EpisodesUiState> = mutableEpisodes.asStateFlow()

    /**
     * Id que se pide al reproductor: el del título, o el del episodio cuando el
     * usuario eligió uno (`tt0944947` → `tt0944947:1:2`).
     */
    @Volatile
    private var requestedId: String = entry.id

    /** Sólo se resuelven episodios cuando el id no los trae ya. */
    private val needsMetadata: Boolean
        get() = !hasEpisodeNumber(entry.type.lowercase(), entry.id) &&
            (isSeries || mediaType == MediaType.Movie)

    private var episodesJob: Job? = null

    init {
        if (needsMetadata && client != null && settings != null) {
            loadEpisodes()
        }
    }

    /**
     * Pide la ficha al servidor para conocer los episodios y arranca con el
     * primer capítulo de una temporada normal, si existe.
     */
    private fun loadEpisodes() {
        val addon = client ?: return
        val addonSettings = settings ?: return

        episodesJob?.cancel()
        if (needsEpisodeChoice) {
            requestedId = entry.id
            mutableEpisodes.value = EpisodesUiState.Loading
        }
        episodesJob = viewModelScope.launch {

            val meta = try {
                addon.meta(addonSettings.baseUrl, entry.type, entry.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }

            mutableMetadata.value = meta
            // Las películas pueden tener extras o tráilers numerados, pero no
            // deben convertirse en series por los videos de sus metadatos.
            if (!needsEpisodeChoice) return@launch
            val seasons = (meta?.videos ?: emptyList()).toSeasonGroups()
            if (seasons.isEmpty()) {
                mutableEpisodes.value = EpisodesUiState.Unavailable(
                    if (meta == null) "No se pudieron cargar los episodios. Indica temporada y episodio, o reproduce el título tal cual."
                    else "El proveedor no publicó la lista de episodios. Indica temporada y episodio, o reproduce el título tal cual.",
                )
                return@launch
            }

            val first = (seasons.firstOrNull { it.season > 0 } ?: seasons.first()).episodes.first()
            /* Desde «Seguir viendo» se retoma el capítulo que se estaba viendo,
             * no el primero de la serie. El id guardado ya viene completo
             * (`serie:slug:1:3`), así que se busca por `idFor` y no por número
             * de episodio: así vale también para los ids de anime, que no
             * llevan temporada. */
            val resume = resumeEpisodeId?.let { wanted ->
                seasons.asSequence()
                    .flatMap { it.episodes.asSequence() }
                    .firstOrNull { it.idFor(entry.id) == wanted }
            }
            val selected = resume ?: first
            mutableEpisodes.value = EpisodesUiState.Ready(seasons = seasons, selected = selected)
            requestedId = selected.idFor(entry.id)
        }
    }

    /** Reintenta la ficha sólo si hay una fuente configurada. */
    val canRetry: Boolean get() = needsEpisodeChoice && client != null && settings != null

    fun retryEpisodes() {
        if (canRetry && mutableEpisodes.value is EpisodesUiState.Unavailable) loadEpisodes()
    }

    /** El usuario cambió de episodio. */
    fun selectEpisode(episode: EpisodeOption) {
        val ready = mutableEpisodes.value as? EpisodesUiState.Ready ?: return
        if (episode !in ready.episodes || ready.selected == episode) return
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
        val episode = selectedEpisode()
        val next = nextEpisodeFor(episode)
        return base.copy(
            id = requestedId,
            title = episode?.let { current -> "${displayTitle()} · ${current.label}" } ?: displayTitle(),
            /* Sólo se reanuda el título que se estaba viendo: si el usuario
             * elige otro capítulo, empieza desde el principio. El caso del
             * episodio guardado es la excepción: `base.startPositionMs` **es**
             * su posición (la trae `WatchProgress.toEntry`), y compararla con
             * `entry.id` la tiraba a cero justo cuando había que usarla. */
            startPositionMs = when (requestedId) {
                entry.id -> base.startPositionMs
                resumeEpisodeId -> base.startPositionMs
                else -> 0L
            },
            /* El "siguiente" se resuelve aquí y viaja en la petición: al
             * reproductor no le queda la lista de episodios. */
            nextEpisodeId = next?.idFor(entry.id),
            nextEpisodeLabel = next?.label,
            nextEpisodeAirDate = nextEpisodeAirDate(next),
        )
    }

    /**
     * El episodio que va después del actual, si se puede ofrecer.
     *
     * Se descarta cuando aún no se ha emitido: ofrecer "siguiente" para un
     * capítulo futuro lleva a un fallo de fuentes garantizado.
     */
    private fun nextEpisodeFor(current: EpisodeOption?): EpisodeOption? {
        if (current == null) return null
        val ready = mutableEpisodes.value as? EpisodesUiState.Ready ?: return null
        val next = nextEpisodeAfter(ready.seasons, current) ?: return null
        return next.takeUnless { isEpisodeNotAiredYet(nextEpisodeAirDate(it), todayIsoDate()) }
    }

    /**
     * Fecha de emisión del episodio.
     *
     * `RemoteVideo` no la trae todavía, así que hoy siempre es `null` y el
     * filtro de "no emitido" no descarta nada. Se deja el punto de enganche
     * hecho para cuando el catálogo la publique: entonces basta rellenarlo aquí.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun nextEpisodeAirDate(episode: EpisodeOption?): String? = null

    private fun todayIsoDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun displayTitle(): String = mutableMetadata.value?.name?.takeIf { it.isNotBlank() } ?: entry.title

    private companion object {
        const val NO_METADATA_SOURCE = "No hay una fuente de metadatos disponible. Indica temporada y episodio, o reproduce el título tal cual."
    }
}
