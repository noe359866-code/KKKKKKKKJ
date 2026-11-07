package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.model.MediaEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePresentationTest {
    @Test fun `pelicula reproduce y ofrece continuar si tiene posicion`() {
        val movie = MediaEntry(id = "tt1", type = "movie", title = "Una película")
        assertEquals(HeroAction.Play, heroAction(movie))
        assertEquals(HeroAction.Resume, heroAction(movie.copy(resumePositionMs = 5000)))
    }

    @Test fun `series sin episodio abren la seleccion`() {
        val series = MediaEntry(id = "tt1", type = "series", title = "Una serie")
        assertEquals(HeroAction.ChooseEpisode, heroAction(series))
        assertEquals(HeroAction.ChooseEpisode, heroAction(series.copy(type = "ANIME")))
        assertEquals(HeroAction.Play, heroAction(series.copy(id = "tt1:1:2")))
        assertEquals(HeroAction.Resume, heroAction(series.copy(id = "tt1:1:2", resumePositionMs = 5000)))
        assertEquals(HeroAction.ChooseEpisode, heroAction(series.copy(resumePositionMs = 5000)))
    }

    @Test fun `anime de proveedor y directo usan la accion reproducir`() {
        val anime = MediaEntry(id = "animeflv:serie:3", type = "ANIME", title = "Anime")
        assertEquals(HeroAction.Play, heroAction(anime))
        val live = MediaEntry(id = "canal", type = "tv", title = "Canal", resumePositionMs = 5000)
        assertEquals(HeroAction.Play, heroAction(live))
        assertEquals(HeroAction.Play, heroAction(live.copy(onAir = false)))
    }
}
