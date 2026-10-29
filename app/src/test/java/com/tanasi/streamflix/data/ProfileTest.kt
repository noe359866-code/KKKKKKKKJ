package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.MAX_PROFILES
import com.tanasi.streamflix.data.model.PROFILE_COLORS
import com.tanasi.streamflix.data.model.Profile
import com.tanasi.streamflix.data.model.WatchProgress
import com.tanasi.streamflix.data.model.defaultProfileName
import com.tanasi.streamflix.data.model.nextColorIndex
import com.tanasi.streamflix.data.model.profileName
import com.tanasi.streamflix.data.model.profileInitials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato de los perfiles y de "Seguir viendo".
 *
 * Es lógica pura a propósito: el selector, el avatar y la fila del Home se
 * apoyan en estas funciones, y un error aquí se ve como dos personas
 * compartiendo historial o como una tarjeta que reaparece después de terminar
 * la serie.
 */
class ProfileTest {

    @Test
    fun `los nombres se normalizan antes de guardarse`() {
        assertEquals("Ana", profileName("  Ana  "))
        assertEquals("Ana Gómez", profileName("Ana    Gómez"))
        assertEquals("Ana Gómez", profileName("Ana\nGómez"))
        assertEquals("Espectador", profileName("   "))
        assertEquals(20, profileName("x".repeat(40)).length)
    }

    @Test
    fun `las iniciales caben en un avatar`() {
        assertEquals("A", profileInitials("Ana"))
        assertEquals("AG", profileInitials("ana gómez"))
        assertEquals("E", profileInitials(""))
        assertEquals("A", profileInitials("  "))
    }

    @Test
    fun `el color del avatar no se repite mientras haya libres`() {
        val used = listOf(Profile(id = "1", name = "Ana", colorIndex = 0))
        assertEquals(1, nextColorIndex(used))
        assertEquals(2, nextColorIndex(used + Profile(id = "2", name = "Luis", colorIndex = 1)))

        val all = (0 until PROFILE_COLORS).map { Profile(id = "p$it", name = "P$it", colorIndex = it) }
        assertTrue(nextColorIndex(all) in 0 until PROFILE_COLORS)
    }

    @Test
    fun `el perfil por defecto no repite nombres`() {
        assertEquals("Espectador", defaultProfileName(emptyList()))
        assertEquals(
            "Espectador 2",
            defaultProfileName(listOf(Profile(id = "1", name = "Espectador"))),
        )
        assertEquals(
            "Espectador 3",
            defaultProfileName(
                listOf(
                    Profile(id = "1", name = "Espectador"),
                    Profile(id = "2", name = "Espectador 2"),
                ),
            ),
        )
        assertEquals(6, MAX_PROFILES)
    }

    @Test
    fun `el progreso se queda entre 1 y 99 para que la barra se vea`() {
        assertEquals(1, progress(position = 1L, duration = 7_200_000L).percent)
        assertEquals(50, progress(position = 3_600_000L, duration = 7_200_000L).percent)
        assertEquals(99, progress(position = 7_199_000L, duration = 7_200_000L).percent)
    }

    @Test
    fun `empezar o terminar no deja rastro en seguir viendo`() {
        assertFalse(progress(position = 5_000L, duration = 7_200_000L).isTrackable())
        assertTrue(progress(position = 60_000L, duration = 7_200_000L).isTrackable())
        assertFalse(progress(position = 7_150_000L, duration = 7_200_000L).isTrackable())
        /* Sin duración (un directo mal marcado) tampoco se guarda. */
        assertFalse(progress(position = 60_000L, duration = 0L).isTrackable())
    }

    @Test
    fun `el tiempo restante se lee en castellano`() {
        assertEquals("Menos de 1 min restante", progress(100_000L, 120_000L).remainingLabel())
        assertEquals("30 min restantes", progress(3_600_000L, 5_400_000L).remainingLabel())
        assertEquals("1 h 00 min restantes", progress(1_800_000L, 5_400_000L).remainingLabel())
        assertEquals("En curso", progress(0L, 0L).remainingLabel())
    }

    @Test
    fun `la tarjeta de seguir viendo lleva el arte y el progreso`() {
        val entry = progress(position = 3_600_000L, duration = 7_200_000L).toEntry()
        assertEquals("gnula:una-pelicula", entry.id)
        assertEquals("movie", entry.type)
        assertEquals("Una película", entry.title)
        assertEquals("https://cdn.example/poster.jpg", entry.poster)
        assertEquals("https://cdn.example/backdrop.jpg", entry.backdrop)
        assertEquals(50, entry.progressPercent)
        assertEquals("1 h 00 min restantes", entry.subtitle)
    }

    private fun progress(position: Long, duration: Long) = WatchProgress(
        id = "gnula:una-pelicula",
        type = "movie",
        title = "Una película",
        poster = "https://cdn.example/poster.jpg",
        backdrop = "https://cdn.example/backdrop.jpg",
        positionMs = position,
        durationMs = duration,
        updatedAt = 1_000L,
    )
}
