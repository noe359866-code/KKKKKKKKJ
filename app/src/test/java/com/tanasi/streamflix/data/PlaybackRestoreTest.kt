package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.PlaybackRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Restauración de la sesión de vídeo tras la muerte del proceso.
 *
 * En Android TV el sistema mata el proceso por memoria sin avisar: al volver,
 * el `Activity` se recrea con el `Intent` original. Sin este estado, una
 * película de 40 minutos volvía al segundo 0.
 */
class PlaybackRestoreTest {

    @Test
    fun `el episodio siguiente sobrevive a la restauracion`() {
        /* Al volver de la muerte del proceso, el prompt de "siguiente" tiene
         * que seguir disponible: si el campo se perdiera, el usuario vería el
         * final del capítulo sin poder avanzar. */
        val episode = PlaybackRequest(
            id = "tt0903747:1:4",
            type = "series",
            title = "Breaking Bad · T1 E4",
            nextEpisodeId = "tt0903747:1:5",
            nextEpisodeLabel = "T1 · E5",
            nextEpisodeAirDate = "2008-03-02",
        )

        val raw = PlaybackRestore.encode(episode, positionMs = 2_700_000L)
        val restored = PlaybackRestore.decode(raw)

        assertNotNull(restored)
        assertEquals("tt0903747:1:5", restored!!.request.nextEpisodeId)
        assertEquals("T1 · E5", restored.request.nextEpisodeLabel)
    }

    @Test
    fun `una sesion guardada sin los campos nuevos sigue leyendose`() {
        /* El JSON de una versión anterior no tiene los campos de "siguiente".
         * Tienen valor por defecto, así que el `Bundle` viejo no puede
         * invalidar la restauración: se recupera la posición y el episodio. */
        val legacy = """{"request":{"id":"tt0903747:1:4","type":"series","title":"Breaking Bad · T1 E4"},"positionMs":1234}"""

        val restored = PlaybackRestore.decode(legacy)

        assertNotNull(restored)
        assertEquals("tt0903747:1:4", restored!!.request.id)
        assertEquals(1234L, restored.positionMs)
        assertNull("una sesión vieja no inventa un siguiente", restored.request.nextEpisodeId)
    }

    private val movie = PlaybackRequest(
        id = "tt0133093",
        type = "movie",
        title = "Matrix",
        poster = "https://cdn/poster.jpg",
        backdrop = "https://cdn/backdrop.jpg",
        startIndex = 2,
        startPositionMs = 900_000L,
    )

    @Test
    fun `la sesion vuelve con titulo, fuente y segundo`() {
        val raw = PlaybackRestore.encode(movie, 1_845_000L)
        val snapshot = PlaybackRestore.decode(raw)

        assertNotNull(snapshot)
        val restored = requireNotNull(snapshot)
        assertEquals("tt0133093", restored.request.id)
        assertEquals("movie", restored.request.type)
        assertEquals("Matrix", restored.request.title)
        assertEquals("https://cdn/backdrop.jpg", restored.request.backdrop)
        assertEquals(2, restored.request.startIndex)
        assertEquals(1_845_000L, restored.positionMs)
        /* La posición guardada es también el punto de arranque: el reproductor
         * retoma ahí sin que nadie tenga que pasarla por separado. */
        assertEquals(1_845_000L, restored.request.startPositionMs)
    }

    @Test
    fun `sin estado guardado o con basura se arranca del intent`() {
        assertNull(PlaybackRestore.decode(null))
        assertNull(PlaybackRestore.decode(""))
        assertNull(PlaybackRestore.decode("   "))
        assertNull(PlaybackRestore.decode("{esto no es json}"))
        /* Json válido pero sin id: no hay nada que restaurar. */
        assertNull(PlaybackRestore.decode("""{"request":{"type":"movie","title":"X"},"positionMs":10}"""))
    }

    @Test
    fun `un estado de otra version no rompe la restauracion`() {
        val withExtras = """
            {
              "request": {"id": "tt1", "type": "movie", "title": "X", "campoNuevo": true},
              "positionMs": 5000,
              "guardadoPor": "0.9.9"
            }
        """.trimIndent()

        val restored = requireNotNull(PlaybackRestore.decode(withExtras))
        assertEquals("tt1", restored.request.id)
        assertEquals(5_000L, restored.request.startPositionMs)
    }

    @Test
    fun `una posicion negativa se recorta a cero`() {
        val restored = requireNotNull(PlaybackRestore.decode(PlaybackRestore.encode(movie, -5_000L)))
        assertEquals(0L, restored.positionMs)
    }
}
