package com.tanasi.streamflix.ui.player

import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamDto
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.model.SubtitleDto
import com.tanasi.streamflix.data.remote.AddonException
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * El `ViewModel` del reproductor: cargar y reintentar.
 *
 * Lo que se prueba es que el estado sobrevive a la actividad y que reintentar
 * vuelve a pedir las fuentes del título en curso.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /* ---------------------------------------------------------------------- */
    /* Dobles                                                                 */
    /* ---------------------------------------------------------------------- */

    private class FakeSettings(override var baseUrl: String = "http://addon.test") : AddonSettings

    /**
     * Cliente de mentira: responde por id, sin red. `gates` permite dejar una
     * petición colgada para observar el estado intermedio.
     */
    private class FakeClient(
        private val responses: Map<String, StreamsResponse> = emptyMap(),
        private val failures: Map<String, String> = emptyMap(),
        private val gates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
    ) : ContentClient {

        val asked = mutableListOf<String>()

        override suspend fun catalogs(baseUrl: String): List<CatalogDescriptor> = emptyList()

        override suspend fun catalog(
            baseUrl: String,
            type: String,
            id: String,
            search: String?,
            genre: String?,
            skip: Int?,
        ): RemoteCatalogResponse? = null

        override suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta? = null

        override suspend fun streams(baseUrl: String, type: String, id: String): StreamsResponse {
            asked += id
            gates[id]?.await()
            failures[id]?.let { throw AddonException(it) }
            return responses[id] ?: StreamsResponse()
        }
    }

    private fun viewModel(client: ContentClient, request: PlaybackRequest) =
        PlayerViewModel(StreamsRepository(client, FakeSettings()), request)

    private fun movie(id: String = "tt0133093") = PlaybackRequest(id = id, type = "movie", title = "Matrix")

    private fun response(vararg urls: String, subtitles: List<SubtitleDto> = emptyList()) = StreamsResponse(
        streams = urls.map { url -> StreamDto(url = url, name = "NovaHD", title = "NovaHD · 1080p") },
        cacheMaxAge = 300,
        subtitles = subtitles,
    )

    private fun ready(viewModel: PlayerViewModel) = viewModel.state.value as PlayerUiState.Ready

    /* ---------------------------------------------------------------------- */
    /* Carga                                                                  */
    /* ---------------------------------------------------------------------- */

    @Test
    fun `arranca con las fuentes y los subtitulos del mismo pedido`() {
        val client = FakeClient(
            responses = mapOf(
                "tt0133093" to response(
                    "https://cdn/a.m3u8",
                    "https://cdn/b.m3u8",
                    subtitles = listOf(SubtitleDto(url = "https://cdn/a.es.srt", lang = "es", label = "Español")),
                ),
            ),
        )

        val viewModel = viewModel(client, movie())
        dispatcher.scheduler.advanceUntilIdle()

        val state = ready(viewModel)
        assertEquals(listOf("https://cdn/a.m3u8", "https://cdn/b.m3u8"), state.sources.map { it.url })
        assertEquals(1, state.subtitles.size)
        assertEquals("es", state.subtitles.single().lang)
        assertEquals(listOf("tt0133093"), client.asked)
    }

    @Test
    fun `sin fuentes el mensaje dice que ningun proveedor respondio`() {
        val viewModel = viewModel(FakeClient(responses = mapOf("tt1" to StreamsResponse())), movie("tt1"))
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as PlayerUiState.Unavailable
        assertTrue(state.message.contains("Ningún proveedor"))
    }

    @Test
    fun `un fallo de red se cuenta tal cual`() {
        val client = FakeClient(failures = mapOf("tt1" to "El addon no responde"))
        val viewModel = viewModel(client, movie("tt1"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("El addon no responde", (viewModel.state.value as PlayerUiState.Unavailable).message)
    }

    @Test
    fun `reintentar vuelve a pedir las fuentes del titulo en curso`() {
        val client = FakeClient(responses = mapOf("tt1" to response("https://cdn/1.m3u8")))
        val viewModel = viewModel(client, movie("tt1"))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("tt1", "tt1"), client.asked)
        assertEquals(listOf("https://cdn/1.m3u8"), ready(viewModel).sources.map { it.url })
    }
    @Test
    fun `reintentar con las mismas fuentes recibe nueva generacion`() {
        val client = FakeClient(responses = mapOf("tt1" to response("https://cdn/shared.m3u8")))
        val vm = viewModel(client, movie("tt1"))
        dispatcher.scheduler.advanceUntilIdle()
        val before = ready(vm).generation

        vm.load()
        dispatcher.scheduler.advanceUntilIdle()

        /* El `ExoPlayer` se memoriza por generación: sin número nuevo, el
         * reintento reutilizaría el reproductor que ya falló. */
        assertTrue(ready(vm).generation > before)
    }

}
