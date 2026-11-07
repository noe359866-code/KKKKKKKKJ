package com.tanasi.streamflix.ui.details

import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.EpisodeOption
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.RemoteVideo
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelPlaybackTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = object : AddonSettings {
        override var baseUrl = ""
        override val autonomous = true
        override val metadataCatalogs = true
    }
    private val client = object : ContentClient {
        override suspend fun catalogs(baseUrl: String): List<CatalogDescriptor> = emptyList()
        override suspend fun catalog(baseUrl: String, type: String, id: String, search: String?, genre: String?, skip: Int?): RemoteCatalogResponse? = null
        override suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta = RemoteMeta(
            id = id, type = type, name = "Serie",
            videos = listOf(
                RemoteVideo(id = "tt1:1:1", season = 1, episode = 1),
                RemoteVideo(id = "tt1:1:2", season = 1, episode = 2),
            ),
        )
        override suspend fun streams(baseUrl: String, type: String, id: String) = StreamsResponse()
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `seleccionar un episodio actualiza inmediatamente la solicitud de reproduccion`() = runTest(dispatcher) {
        val vm = DetailsViewModel(MediaEntry(id = "tt1", type = "series", title = "Serie"), client, settings)
        advanceUntilIdle()
        val second = (vm.episodes.value as EpisodesUiState.Ready).episodes[1]

        vm.selectEpisode(second)

        assertEquals("tt1:1:2", vm.playbackRequest().id)
        assertEquals("T1 E2", vm.episodeLabel())
    }
    @Test fun `serie carga antes de ofrecer episodio y rechaza capitulos ajenos`() = runTest(dispatcher) {
        val vm = DetailsViewModel(MediaEntry(id = "tt1", type = "series", title = "Serie"), client, settings)
        assertTrue(vm.episodes.value is EpisodesUiState.Loading)
        advanceUntilIdle()
        assertEquals("tt1:1:1", vm.playbackRequest().id)
        vm.selectEpisode(EpisodeOption(season = 9, episode = 99))
        assertEquals("tt1:1:1", vm.playbackRequest().id)
    }

    @Test fun `desde seguir viendo se retoma el episodio guardado y su segundo`() = runTest(dispatcher) {
        /* La tarjeta de «Seguir viendo» guarda el id del capítulo
         * (`tt1:1:2`) y su posición. Sin esto la ficha ponía el foco en el E1
         * y arrancaba desde cero: el usuario veía empezar la serie otra vez. */
        val card = MediaEntry(
            id = "tt1:1:2",
            type = "series",
            title = "Serie · T1 E2",
            resumePositionMs = 1_230_000L,
        )
        val vm = DetailsViewModel(
            entry = card,
            client = client,
            settings = settings,
            resumeEpisodeId = "tt1:1:2",
        )
        advanceUntilIdle()

        val request = vm.playbackRequest()

        assertEquals("tt1:1:2", request.id)
        assertEquals("T1 E2", vm.episodeLabel())
        assertEquals(
            "la posición guardada es la del capítulo, no la del título",
            1_230_000L,
            request.startPositionMs,
        )
    }

    @Test fun `un episodio guardado que el proveedor ya no lista no rompe la ficha`() = runTest(dispatcher) {
        /* El catálogo cambió de numeración o el capítulo desapareció: hay que
         * caer al primero en vez de no seleccionar nada. */
        val vm = DetailsViewModel(
            entry = MediaEntry(id = "tt1:1:9", type = "series", title = "Serie"),
            client = client,
            settings = settings,
            resumeEpisodeId = "tt1:1:9",
        )
        advanceUntilIdle()

        assertEquals("tt1:1:1", vm.playbackRequest().id)
        assertEquals("empezar de cero es lo correcto si el capítulo ya no existe", 0L, vm.playbackRequest().startPositionMs)
    }

    @Test fun `elegir otro capitulo a mano sigue empezando desde el principio`() = runTest(dispatcher) {
        /* La excepción de la reanudación es sólo para el capítulo guardado. */
        val vm = DetailsViewModel(
            entry = MediaEntry(id = "tt1:1:2", type = "series", title = "Serie", resumePositionMs = 1_230_000L),
            client = client,
            settings = settings,
            resumeEpisodeId = "tt1:1:2",
        )
        advanceUntilIdle()
        vm.selectEpisode((vm.episodes.value as EpisodesUiState.Ready).episodes[0])

        val request = vm.playbackRequest()

        assertEquals("tt1:1:1", request.id)
        assertEquals(0L, request.startPositionMs)
    }

    @Test fun `pelicula conserva su id aunque los metadatos incluyan videos`() = runTest(dispatcher) {
        val vm = DetailsViewModel(MediaEntry(id = "tt1", type = "movie", title = "Película"), client, settings)
        assertTrue(vm.episodes.value is EpisodesUiState.Hidden)
        advanceUntilIdle()
        assertTrue(vm.episodes.value is EpisodesUiState.Hidden)
        assertEquals("tt1", vm.playbackRequest().id)
    }

    @Test fun `sin cliente se ofrece seleccion manual sin inventar episodio`() = runTest(dispatcher) {
        val vm = DetailsViewModel(MediaEntry(id = "tt1", type = "series", title = "Serie"))
        assertTrue(vm.episodes.value is EpisodesUiState.Unavailable)
        assertFalse(vm.canRetry)
        assertEquals("tt1", vm.playbackRequest().id)
        vm.selectManualEpisode(EpisodeOption(season = 3, episode = 7))
        assertEquals("tt1:3:7", vm.playbackRequest().id)
        vm.selectManualEpisode(EpisodeOption(season = -1, episode = 2))
        assertEquals("tt1:3:7", vm.playbackRequest().id)
    }

    @Test fun `id de episodio previo conserva su posicion`() = runTest(dispatcher) {
        val vm = DetailsViewModel(MediaEntry(id = "tt1:2:3", type = "series", title = "Serie", resumePositionMs = 1234), client, settings)
        assertTrue(vm.episodes.value is EpisodesUiState.Hidden)
        assertEquals("tt1:2:3", vm.playbackRequest().id)
        assertEquals(1234L, vm.playbackRequest().startPositionMs)
    }

    @Test fun `error de metadatos permite reintentar sin fabricar una lista`() = runTest(dispatcher) {
        var attempts = 0
        val retryClient = object : ContentClient by client {
            override suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta? {
                attempts++
                return if (attempts == 1) null else client.meta(baseUrl, type, id)
            }
        }
        val vm = DetailsViewModel(MediaEntry(id = "tt1", type = "series", title = "Serie"), retryClient, settings)
        advanceUntilIdle()
        assertTrue(vm.episodes.value is EpisodesUiState.Unavailable)
        assertTrue((vm.episodes.value as EpisodesUiState.Unavailable).message.contains("No se pudieron cargar"))
        vm.retryEpisodes()
        assertTrue(vm.episodes.value is EpisodesUiState.Loading)
        advanceUntilIdle()
        assertEquals("tt1:1:1", vm.playbackRequest().id)
        assertEquals(2, attempts)
    }

    @Test fun `elegir temporada selecciona un episodio sin iniciar reproduccion`() = runTest(dispatcher) {
        val seasonClient = object : ContentClient by client {
            override suspend fun meta(baseUrl: String, type: String, id: String) = RemoteMeta(
                id = id, type = type, name = "Serie", videos = listOf(
                    RemoteVideo(season = 0, episode = 1),
                    RemoteVideo(season = 1, episode = 1),
                    RemoteVideo(season = 2, episode = 1),
                ),
            )
        }
        val vm = DetailsViewModel(MediaEntry(id = "tt1", type = "series", title = "Serie"), seasonClient, settings)
        advanceUntilIdle()
        assertEquals("tt1:1:1", vm.playbackRequest().id)
        vm.selectSeason(2)
        assertEquals("tt1:2:1", vm.playbackRequest().id)
        assertEquals("T2 E1", vm.episodeLabel())
    }

}
