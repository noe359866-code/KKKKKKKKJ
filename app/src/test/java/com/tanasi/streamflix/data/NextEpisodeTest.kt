package com.tanasi.streamflix.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El "siguiente episodio" se equivoca en silencio: un id inventado acaba en
 * "sin fuentes" y el usuario cree que la app está rota. Estas pruebas fijan los
 * casos que de verdad aparecen en las temporadas.
 */
class NextEpisodeTest {

    private fun episode(season: Int, episode: Int, name: String? = null) =
        EpisodeOption(season = season, episode = episode, name = name)

    private fun season(number: Int, vararg episodes: Int) =
        SeasonGroup(season = number, episodes = episodes.map { episode(number, it) })

    @Test
    fun `el siguiente es el de la lista, no el numero mas uno`() {
        /* Hueco en la numeración: el E2 no existe. Sumar uno daría el E2, que
         * el proveedor no conoce; el siguiente de verdad es el E3. */
        val seasons = listOf(season(1, 1, 3, 4))

        val next = nextEpisodeAfter(seasons, episode(1, 1))

        assertEquals(3, next?.episode)
    }

    @Test
    fun `el ultimo de la temporada no tiene siguiente`() {
        val seasons = listOf(season(1, 1, 2, 3))

        assertNull("saltar de temporada no es automático", nextEpisodeAfter(seasons, episode(1, 3)))
    }

    @Test
    fun `no se salta a otra temporada`() {
        val seasons = listOf(season(1, 1), season(2, 1))

        assertNull(nextEpisodeAfter(seasons, episode(1, 1)))
    }

    @Test
    fun `un episodio que no esta en la lista no tiene siguiente`() {
        /* Pasa cuando el catálogo y el reproductor no coinciden de versión. */
        val seasons = listOf(season(1, 1, 2))

        assertNull(nextEpisodeAfter(seasons, episode(1, 9)))
        assertNull(nextEpisodeAfter(emptyList(), episode(1, 1)))
        assertNull(nextEpisodeAfter(listOf(season(3, 1, 2)), episode(1, 1)))
    }

    @Test
    fun `la lista desordenada se ordena antes de avanzar`() {
        val seasons = listOf(SeasonGroup(season = 1, episodes = listOf(episode(1, 3), episode(1, 1), episode(1, 2))))

        assertEquals(2, nextEpisodeAfter(seasons, episode(1, 1))?.episode)
        assertEquals(3, nextEpisodeAfter(seasons, episode(1, 2))?.episode)
    }

    @Test
    fun `el especial de temporada cero funciona igual`() {
        val seasons = listOf(SeasonGroup(season = 0, episodes = listOf(episode(0, 1), episode(0, 2))))

        assertEquals(2, nextEpisodeAfter(seasons, episode(0, 1))?.episode)
    }

    @Test
    fun `un episodio futuro no se ofrece`() {
        assertTrue(isEpisodeNotAiredYet("2026-11-20", todayIsoDate = "2026-11-07"))
    }

    @Test
    fun `un episodio que se emite hoy si se ofrece`() {
        /* Se compara sólo la fecha: el capítulo que sale hoy ya se puede ver. */
        assertFalse(isEpisodeNotAiredYet("2026-11-07", todayIsoDate = "2026-11-07"))
        assertFalse(isEpisodeNotAiredYet("2026-11-07T23:00:00Z", todayIsoDate = "2026-11-07"))
    }

    @Test
    fun `sin fecha o con fecha ilegible se ofrece`() {
        /* "No se sabe" no es "no ha salido": esconder un episodio que existe es
         * peor que intentarlo y dejar que el failover diga la verdad. */
        assertFalse(isEpisodeNotAiredYet(null, "2026-11-07"))
        assertFalse(isEpisodeNotAiredYet("", "2026-11-07"))
        assertFalse(isEpisodeNotAiredYet("proximamente", "2026-11-07"))
        assertFalse(isEpisodeNotAiredYet("2026-11", "2026-11-07"))
    }

    @Test
    fun `la ventana aparece solo al final`() {
        val duration = 2_700_000L // 45 min

        assertFalse(NextEpisodeWindow.shouldOffer(positionMs = 60_000L, durationMs = duration))
        assertFalse(NextEpisodeWindow.shouldOffer(positionMs = duration / 2, durationMs = duration))
        assertTrue(NextEpisodeWindow.shouldOffer(positionMs = duration - 30_000L, durationMs = duration))
        assertTrue(NextEpisodeWindow.shouldOffer(positionMs = duration, durationMs = duration))
    }

    @Test
    fun `con creditos conocidos la ventana empieza donde empiezan`() {
        val duration = 2_700_000L
        val creditsAt = duration - 180_000L // 3 min de créditos

        assertFalse("no antes de los créditos", NextEpisodeWindow.shouldOffer(positionMs = creditsAt - 1, durationMs = duration, creditsStartMs = creditsAt))
        assertTrue(NextEpisodeWindow.shouldOffer(positionMs = creditsAt, durationMs = duration, creditsStartMs = creditsAt))
    }

    @Test
    fun `una duracion desconocida o una posicion imposible no ofrecen nada`() {
        /* Éstos son los dos falsos positivos que hay que evitar: al cambiar de
         * fuente la duración llega como 0 y la posición puede quedar por encima
         * de la duración un instante. */
        assertFalse(NextEpisodeWindow.shouldOffer(positionMs = 30_000L, durationMs = 0L))
        assertFalse(NextEpisodeWindow.shouldOffer(positionMs = 30_000L, durationMs = -1L))
        assertFalse(NextEpisodeWindow.shouldOffer(positionMs = 0L, durationMs = 2_700_000L))
        assertFalse(NextEpisodeWindow.shouldOffer(positionMs = 3_000_000L, durationMs = 2_700_000L))
    }

    @Test
    fun `unos creditos en una posicion absurda caen en la ventana de respaldo`() {
        val duration = 2_700_000L

        /* `creditsStartMs` igual a la duración (o fuera de rango) no sirve como
         * punto de partida: se usa el respaldo. */
        assertTrue(
            NextEpisodeWindow.shouldOffer(
                positionMs = duration - 10_000L,
                durationMs = duration,
                creditsStartMs = duration,
            ),
        )
        assertFalse(
            NextEpisodeWindow.shouldOffer(
                positionMs = 60_000L,
                durationMs = duration,
                creditsStartMs = -5L,
            ),
        )
    }
}
