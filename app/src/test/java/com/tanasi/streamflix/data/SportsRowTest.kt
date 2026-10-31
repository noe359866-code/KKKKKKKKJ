package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.ui.components.teamInitials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Área de deportes: el contrato entre la agenda del motor y la tarjeta de
 * marcador del Home.
 *
 * Lo importante que fijan estas pruebas:
 *  - la fila llega con `layout: "sports"` y es la única que se pinta así;
 *  - un partido programado **no** es un directo aunque su tipo sea `tv`;
 *  - liga, equipos y hora se conservan hasta la tarjeta;
 *  - las iniciales de los equipos se derivan del nombre real, sin inventar arte.
 */
class SportsRowTest {

    private val descriptor = CatalogDescriptor(
        type = "tv",
        id = "deportes",
        name = "Deportes en vivo",
        posterShape = "landscape",
        layout = "sports",
    )

    private fun match(
        name: String = "Yankees vs Red Sox",
        home: String = "Yankees",
        away: String = "Red Sox",
        league: String = "MLB",
        time: String = "21:00",
        onAir: Boolean = false,
        releaseInfo: String = "Hoy · 21:00",
    ) = RemoteMeta(
        id = "tv:futbolibre:evento:${home.lowercase()}-vs-${away.lowercase().replace(" ", "-")}",
        type = "tv",
        name = name,
        home = home,
        away = away,
        league = league,
        startTime = time,
        onAir = onAir,
        releaseInfo = releaseInfo,
    )

    @Test
    fun `la fila de deportes se publica con layout propio`() {
        assertEquals("sports", descriptor.layout)
        val row = CatalogRow(
            id = descriptor.rowId,
            title = descriptor.name,
            aspect = descriptor.posterShape,
            layout = descriptor.layout ?: CatalogRow.LAYOUT_CARDS,
        )

        assertTrue(row.isSports)
        /* Sin layout explícito, la fila se pinta con las tarjetas de siempre. */
        assertFalse(CatalogRow(id = "remote:movie/top", title = "Películas").isSports)
    }

    @Test
    fun `un partido programado no es directo aunque su tipo sea tv`() {
        val upcoming = match().toEntry(descriptor)
        val onAir = match(onAir = true, releaseInfo = "En vivo · 21:00").toEntry(descriptor)

        assertFalse(upcoming.isLive)
        assertTrue(onAir.isLive)
        /* El resto de filas `tv` sigue deduciendo el directo del tipo. */
        val channel = RemoteMeta(id = "iptvorg:abc", type = "tv", name = "Canal").toEntry(descriptor)
        assertTrue(channel.isLive)
    }

    @Test
    fun `la tarjeta de marcador conserva liga equipos y hora`() {
        val entry = match().toEntry(descriptor)

        assertTrue(entry.isMatch)
        assertEquals("MLB", entry.league)
        assertEquals("Yankees", entry.homeTeam)
        assertEquals("Red Sox", entry.awayTeam)
        assertEquals("21:00", entry.startTime)
        assertEquals("Hoy · 21:00", entry.subtitle)
    }

    @Test
    fun `un canal deportivo sin equipos cae al titulo del canal`() {
        val channel = RemoteMeta(
            id = "iptvorg:espn",
            type = "tv",
            name = "ESPN 2",
            releaseInfo = "En vivo",
        ).toEntry(descriptor)

        assertFalse(channel.isMatch)
        assertEquals("ESPN 2", channel.title)
        assertTrue(channel.isLive)
    }

    @Test
    fun `las iniciales salen del nombre real del equipo`() {
        assertEquals("AG", teamInitials("Atlético Grau"))
        assertEquals("CI", teamInitials("Cienciano"))
        assertEquals("RS", teamInitials("Red Sox"))
        /* Los prefijos de club no cuentan como nombre propio. */
        assertEquals("AB", teamInitials("Club Atlético Boca Juniors"))
        assertEquals("?", teamInitials("   "))
    }

    @Test
    fun `un titulo sin equipos no se convierte en marcador`() {
        val entry = MediaEntry(id = "x", type = "tv", title = "MLB Network")

        assertFalse(entry.isMatch)
        assertEquals("MN", teamInitials(entry.title))
    }
}
