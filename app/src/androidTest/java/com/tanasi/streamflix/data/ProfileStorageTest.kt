package com.tanasi.streamflix.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tanasi.streamflix.data.local.ProfileStore
import com.tanasi.streamflix.data.local.WatchProgressStore
import com.tanasi.streamflix.data.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Persistencia real de los perfiles en el dispositivo.
 *
 * Se instancian los almacenes **dos veces**: la segunda instancia lee sólo de
 * `SharedPreferences`, así que esto demuestra lo que de verdad importa —
 * apagar la tele y volver a encenderla no borra ni el perfil activo ni la
 * posición de "Seguir viendo", y el progreso de un perfil no se filtra al otro.
 */
@RunWith(AndroidJUnit4::class)
class ProfileStorageTest {

    @Test
    fun profilesAndProgressSurviveARestartAndStayPerProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val store = ProfileStore(context)
        val ana = store.create("Ana") ?: error("No se pudo crear el perfil")
        store.select(ana.id)

        /* "Reinicio": una instancia nueva lee de disco. */
        val reopened = ProfileStore(context)
        assertEquals(ana.id, reopened.activeProfile()?.id)
        assertTrue(reopened.profiles().any { it.name == "Ana" })

        val progress = WatchProgressStore(context)
        progress.save(
            ana.id,
            WatchProgress(
                id = "gnula:una-pelicula",
                type = "movie",
                title = "Una película",
                poster = "https://cdn.example/p.jpg",
                positionMs = 60_000L,
                durationMs = 7_200_000L,
                updatedAt = 1L,
            ),
        )

        val entries = WatchProgressStore(context).entriesFor(ana.id)
        assertEquals(1, entries.size)
        assertEquals("Una película", entries.first().title)
        assertNotNull(entries.first().progressPercent)

        /* Aislamiento: otro perfil no ve lo del primero. */
        val luis = store.create("Luis") ?: error("No se pudo crear el segundo perfil")
        assertTrue(WatchProgressStore(context).entriesFor(luis.id).isEmpty())

        /* Terminar la película la saca de "Seguir viendo". */
        progress.save(
            ana.id,
            WatchProgress(
                id = "gnula:una-pelicula",
                type = "movie",
                title = "Una película",
                positionMs = 7_200_000L,
                durationMs = 7_200_000L,
                updatedAt = 2L,
            ),
        )
        assertTrue(WatchProgressStore(context).entriesFor(ana.id).isEmpty())

        store.delete(ana.id)
        store.delete(luis.id)
        progress.clear(ana.id)
        progress.clear(luis.id)
        store.clearActive()
        assertFalse(ProfileStore(context).profiles().any { it.id == ana.id })
    }
}
