package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.SkipSegmentType
import com.tanasi.streamflix.data.repo.SkipSegmentsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Prioridad entre las dos bases, construcción de las URLs y qué pasa cuando una
 * responde con algo inútil. Todo con un HTTP inyectado: estas pruebas no tocan
 * la red.
 */
class SkipSegmentsRepositoryTest {

    private val theIntroDbBody = """{"intro":[{"start_ms":30000,"end_ms":90000}]}"""
    private val introDbBody = """{"intro":{"start_ms":30000,"end_ms":90000}}"""

    private fun movie(id: String = "tt0111161") =
        PlaybackRequest(id = id, type = "movie", title = "Cadena perpetua")

    private fun episode(id: String = "tt0903747:1:1") =
        PlaybackRequest(id = id, type = "series", title = "Piloto")

    @Before
    fun clean() = SkipSegmentsRepository.clearCache()

    @Test
    fun `una pelicula se pregunta a TheIntroDB por imdb`() {
        var asked: String? = null
        val repo = SkipSegmentsRepository { url -> asked = url; theIntroDbBody }

        val segments = repo.segmentsFor(movie(), durationMs = 7_200_000L)

        assertEquals(1, segments.size)
        assertEquals(SkipSegmentType.Intro, segments.single().type)
        assertTrue(asked!!.startsWith("https://api.theintrodb.org/v4/media?imdb_id=tt0111161"))
        assertTrue("una película no manda temporada", !asked!!.contains("season="))
        assertTrue("la duración permite elegir versión", asked!!.contains("duration_ms=7200000"))
    }

    @Test
    fun `un episodio manda temporada y episodio`() {
        var asked: String? = null
        val repo = SkipSegmentsRepository { url -> asked = url; theIntroDbBody }

        repo.segmentsFor(episode(), durationMs = 0L)

        assertTrue(asked!!.contains("season=1"))
        assertTrue(asked!!.contains("episode=1"))
    }

    @Test
    fun `si TheIntroDB no tiene nada se pregunta a IntroDB`() {
        val asked = mutableListOf<String>()
        val repo = SkipSegmentsRepository { url ->
            asked += url
            when {
                url.contains("theintrodb") -> "{}"          // 200 sin tramos
                else -> introDbBody
            }
        }

        val segments = repo.segmentsFor(movie(), durationMs = 0L)

        assertEquals(1, segments.size)
        assertEquals(2, asked.size)
        assertTrue(asked[1].startsWith("https://api.introdb.app/segments?"))
        assertTrue("IntroDB sólo entiende IMDb", asked[1].contains("imdb_id=tt0111161"))
        assertTrue("las películas se marcan explícitamente", asked[1].contains("is_movie=true"))
    }

    @Test
    fun `si la primera falla con null tambien se prueba la segunda`() {
        val repo = SkipSegmentsRepository { url ->
            if (url.contains("theintrodb")) null else introDbBody
        }
        assertEquals(1, repo.segmentsFor(movie(), durationMs = 0L).size)
    }

    @Test
    fun `si las dos fallan no hay tramos y no se lanza`() {
        val repo = SkipSegmentsRepository { null }
        assertTrue(repo.segmentsFor(movie(), durationMs = 0L).isEmpty())
    }

    @Test
    fun `un id sin imdb no gasta ninguna peticion`() {
        var called = false
        val repo = SkipSegmentsRepository { called = true; theIntroDbBody }

        val segments = repo.segmentsFor(movie(id = "gnula:street-fighter"), durationMs = 0L)

        assertTrue(segments.isEmpty())
        assertTrue("no se debe consultar por un id que las bases no aceptan", !called)
    }

    @Test
    fun `el segundo pedido sale del cache`() {
        var calls = 0
        val repo = SkipSegmentsRepository { calls++; theIntroDbBody }

        repo.segmentsFor(episode(), durationMs = 0L)
        repo.segmentsFor(episode(), durationMs = 0L)

        assertEquals("el capítulo ya se había preguntado", 1, calls)
    }

    @Test
    fun `dos episodios distintos no comparten cache`() {
        var calls = 0
        val repo = SkipSegmentsRepository { calls++; theIntroDbBody }

        repo.segmentsFor(episode("tt0903747:1:1"), durationMs = 0L)
        repo.segmentsFor(episode("tt0903747:1:2"), durationMs = 0L)

        assertEquals(2, calls)
    }

    @Test
    fun `un episodio sin marcas no se vuelve a pedir`() {
        /* El vacío también se cachea: si no, una serie sin datos se pediría en
         * cada cambio de fuente. */
        var calls = 0
        val repo = SkipSegmentsRepository { calls++; "{}" }

        repo.segmentsFor(episode(), durationMs = 0L)
        repo.segmentsFor(episode(), durationMs = 0L)

        assertEquals(2, calls) // TheIntroDB + IntroDB la primera vez, ninguna la segunda
    }

    /* ------------------------ Lectura del identificador --------------------- */

    @Test
    fun `extrae el imdb de los dos formatos del addon`() {
        assertEquals("tt0111161", SkipSegmentsRepository.imdbIdOf("tt0111161"))
        assertEquals("tt0903747", SkipSegmentsRepository.imdbIdOf("tt0903747:1:1"))
    }

    @Test
    fun `rechaza ids que no son imdb validos`() {
        /* Los dos servicios validan `tt` + 7 u 8 dígitos; cualquier otra cosa
         * gastaría una petición para recibir un 400. */
        assertNull(SkipSegmentsRepository.imdbIdOf("gnula:street-fighter"))
        assertNull(SkipSegmentsRepository.imdbIdOf("tt123"))
        assertNull(SkipSegmentsRepository.imdbIdOf("tt123456789"))
        assertNull(SkipSegmentsRepository.imdbIdOf("nm1234567"))
        assertNull(SkipSegmentsRepository.imdbIdOf(""))
    }

    @Test
    fun `un id con temporada no numerica no se consulta`() {
        var called = false
        val repo = SkipSegmentsRepository { called = true; theIntroDbBody }
        assertTrue(repo.segmentsFor(episode("tt0903747:x:1"), 0L).isEmpty())
        assertTrue(!called)
    }
}
