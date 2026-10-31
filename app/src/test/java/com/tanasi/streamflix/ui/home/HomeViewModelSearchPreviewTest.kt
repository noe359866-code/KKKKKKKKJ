package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.repo.CatalogRepository
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * La búsqueda en vivo del buscador.
 *
 * Dos cosas se juegan aquí y las dos se notan en el sofá: que **no** salga una
 * carga de catálogo por cada letra (con el mando se teclean veinte) y que
 * escribir no rompa el Home que está detrás del teclado. El reloj es virtual —
 * `CatalogRepository` recibe el planificador de prueba—, así que la espera se
 * comprueba sin dormir el test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelSearchPreviewTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /* ---------------------------------------------------------------------- */
    /* Dobles                                                                 */
    /* ---------------------------------------------------------------------- */

    internal class FakeSettings : AddonSettings {
        override var baseUrl: String = ""
        override val autonomous: Boolean = true
        override val metadataCatalogs: Boolean = true
    }

    /**
     * Motor de mentira: una fila fija y resultados por consulta.
     *
     * Guarda cada consulta pedida para poder afirmar cuántas peticiones se
     * hicieron, que es justo lo que el retardo tiene que evitar.
     */
    internal class FakeClient(private val results: Map<String, List<String>>) : ContentClient {

        val askedSearches = mutableListOf<String?>()

        override suspend fun catalogs(baseUrl: String): List<CatalogDescriptor> = listOf(
            CatalogDescriptor(type = "movie", id = "top", name = "Películas populares", source = "cinemeta"),
        )

        override suspend fun catalog(
            baseUrl: String,
            type: String,
            id: String,
            search: String?,
            genre: String?,
            skip: Int?,
        ): RemoteCatalogResponse? {
            askedSearches += search
            val ids = results[search.orEmpty()].orEmpty()
            return RemoteCatalogResponse(
                metas = ids.map {
                    RemoteMeta(id = it, type = "movie", name = "Título $it", poster = "https://cdn/$it.jpg")
                },
            )
        }

        override suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta? = null

        override suspend fun streams(baseUrl: String, type: String, id: String) = StreamsResponse()
    }

    private fun viewModel(results: Map<String, List<String>>): Pair<HomeViewModel, FakeClient> {
        val client = FakeClient(results)
        val repository = CatalogRepository(client, FakeSettings(), ioDispatcher = dispatcher)
        return HomeViewModel(repository, client, baseUrl = { "" }) to client
    }

    /* ---------------------------------------------------------------------- */
    /* Pruebas                                                                */
    /* ---------------------------------------------------------------------- */

    @Test
    fun `una rafaga de teclas hace una sola peticion`() = runTest(dispatcher) {
        val (vm, client) = viewModel(mapOf("matrix" to listOf("tt1", "tt2")))
        advanceUntilIdle()
        client.askedSearches.clear()

        /* El usuario teclea "matrix" sin parar entre letra y letra. */
        vm.preview("ma")
        advanceTimeBy(50)
        vm.preview("mat")
        advanceTimeBy(50)
        vm.preview("matr")
        advanceTimeBy(50)
        vm.preview("matrix")
        advanceUntilIdle()

        assertEquals(listOf<String?>("matrix"), client.askedSearches)
        val ready = vm.preview.value as SearchPreview.Ready
        assertEquals(listOf("tt1", "tt2"), ready.entries.map { it.id })
    }

    @Test
    fun `escribir no toca el Home de detras`() = runTest(dispatcher) {
        val (vm, _) = viewModel(mapOf("" to listOf("tt0"), "matrix" to listOf("tt1")))
        advanceUntilIdle()
        val home = vm.state.value
        assertTrue(home is HomeUiState.Ready)

        vm.preview("matrix")
        advanceUntilIdle()

        /* El Home (y su hero) siguen como estaban: el panel derecho es aparte. */
        assertEquals(home, vm.state.value)
        assertTrue(vm.query.value.isEmpty())
    }

    @Test
    fun `una letra suelta no busca`() = runTest(dispatcher) {
        val (vm, client) = viewModel(mapOf("m" to listOf("tt1")))
        advanceUntilIdle()
        client.askedSearches.clear()

        vm.preview("m")
        advanceUntilIdle()

        assertEquals(SearchPreview.Idle, vm.preview.value)
        assertTrue(client.askedSearches.isEmpty())
    }

    @Test
    fun `sin resultados el panel queda vacio y no en error`() = runTest(dispatcher) {
        /* El Home sí carga (una fila con un título); lo que no existe es "zzz". */
        val (vm, _) = viewModel(mapOf("" to listOf("tt0")))

        vm.preview("zzz")
        advanceUntilIdle()

        /* Ni Error en el Home ni una excepción: una búsqueda que no encuentra
         * nada es una lista vacía, y el usuario sigue escribiendo. */
        assertEquals(SearchPreview.Empty, vm.preview.value)
        assertTrue(vm.state.value !is HomeUiState.Error)
    }

    @Test
    fun `cerrar el buscador cancela la busqueda pendiente`() = runTest(dispatcher) {
        val (vm, client) = viewModel(mapOf("matrix" to listOf("tt1")))
        advanceUntilIdle()
        client.askedSearches.clear()

        vm.preview("matrix")
        advanceTimeBy(100) // aún dentro del retardo
        vm.clearPreview()
        advanceUntilIdle()

        assertEquals(SearchPreview.Idle, vm.preview.value)
        assertTrue("no debe llegar la petición", client.askedSearches.isEmpty())
    }

    @Test
    fun `buscar vuelve a usar la cache de la vista previa`() = runTest(dispatcher) {
        val (vm, client) = viewModel(mapOf("matrix" to listOf("tt1")))
        advanceUntilIdle()

        vm.preview("matrix")
        advanceUntilIdle()
        client.askedSearches.clear()

        /* `BUSCAR` con la misma consulta: el repositorio ya la tiene. */
        vm.search("matrix")
        advanceUntilIdle()

        assertTrue(client.askedSearches.isEmpty())
        assertTrue(vm.state.value is HomeUiState.Ready)
    }
}

/**
 * Las recomendaciones del buscador, en el `ViewModel` (la selección pura ya se
 * prueba en `CinemetaSuggestionsTest`).
 *
 * Se cargan **una vez** con el Home y se quedan mientras el usuario busca: son
 * el punto de partida del buscador, no el resultado de la búsqueda. Si se
 * borraran al teclear, el panel derecho se quedaría vacío justo en el momento en
 * que hace falta un atajo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelSuggestionsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(results: Map<String, List<String>>): HomeViewModel {
        val client = HomeViewModelSearchPreviewTest.FakeClient(results)
        val repository = CatalogRepository(client, HomeViewModelSearchPreviewTest.FakeSettings(), ioDispatcher = dispatcher)
        return HomeViewModel(repository, client, baseUrl = { "" })
    }

    @Test
    fun `salen de las filas del Home sin pedir nada mas`() = runTest(dispatcher) {
        val vm = viewModel(mapOf("" to listOf("tt1", "tt2")))

        advanceUntilIdle()

        assertEquals(listOf("tt1", "tt2"), vm.suggestions.value.map { it.id })
    }

    @Test
    fun `buscar no las borra`() = runTest(dispatcher) {
        val vm = viewModel(mapOf("" to listOf("tt1"), "matrix" to listOf("tt9")))
        advanceUntilIdle()
        val before = vm.suggestions.value

        vm.search("matrix")
        advanceUntilIdle()

        assertEquals(before, vm.suggestions.value)
        assertTrue(vm.suggestions.value.isNotEmpty())
    }

    @Test
    fun `refrescar las rehace`() = runTest(dispatcher) {
        val vm = viewModel(mapOf("" to listOf("tt1")))
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf("tt1"), vm.suggestions.value.map { it.id })
    }
}
