package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.EpisodeOption
import com.tanasi.streamflix.data.model.RemoteMeta
import com.tanasi.streamflix.data.model.RemoteVideo
import com.tanasi.streamflix.data.model.RowAspect
import com.tanasi.streamflix.data.model.toSeasonGroups
import com.tanasi.streamflix.data.remote.Urls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato entre el catálogo del backend y las tarjetas del Home.
 *
 * Estas pruebas fijan las dos decisiones que más fácil se rompen al tocar el
 * servidor: que **la forma de la tarjeta la decide el backend** (`posterShape`)
 * y que las metas de Cinemeta se traducen a algo que `/stream` entiende sin
 * perder el id de IMDb.
 */
class CatalogFeedTest {

    private fun meta(
        id: String = "tt0944947",
        type: String = "series",
        name: String = "Game of Thrones",
        releaseInfo: String? = "2011–2019",
        rating: Double? = null,
        posterShape: String? = null,
        videos: List<RemoteVideo> = emptyList(),
    ) = RemoteMeta(
        id = id,
        type = type,
        name = name,
        poster = "https://images.metahub.space/poster/medium/$id/img",
        background = "https://images.metahub.space/background/medium/$id/img",
        description = "Sinopsis",
        releaseInfo = releaseInfo,
        rating = rating,
        posterShape = posterShape,
        sourceType = if (type == "anime") "series" else null,
        videos = videos,
    )

    @Test
    fun `la meta conserva el id de IMDb para pedir streams`() {
        val entry = meta().toEntry()

        assertEquals("tt0944947", entry.id)
        assertEquals("series", entry.type)
        assertEquals("Game of Thrones", entry.title)
    }

    @Test
    fun `el anio se extrae del rango de releaseInfo`() {
        assertEquals(2011, meta(releaseInfo = "2011–2019").toEntry().year)
        assertEquals(1999, meta(type = "movie", releaseInfo = "1999").toEntry().year)
        assertNull(meta(releaseInfo = null).toEntry().year)
    }

    @Test
    fun `un anime conserva su tipo publicado aunque Cinemeta lo sirviera como serie`() {
        val entry = meta(type = "anime").toEntry()

        /* El endpoint de streams es /stream/anime/tt…: el tipo publicado manda. */
        assertEquals("anime", entry.type)
        assertEquals("anime", entry.art)
    }

    @Test
    fun `la TV en vivo se marca como directo y hereda arte landscape`() {
        val descriptor = CatalogDescriptor(type = "tv", id = "live", name = "TV", posterShape = "landscape")
        val entry = meta(id = "tv:demo:espn-1", type = "tv", releaseInfo = "En directo").toEntry(descriptor)

        assertTrue(entry.isLive)
        assertEquals("live", entry.art)
        assertEquals(RowAspect.Landscape, descriptor.rowAspect)
    }

    @Test
    fun `la forma de la fila la decide el servidor, no el tipo de contenido`() {
        val live = CatalogDescriptor(type = "tv", id = "live", posterShape = "landscape")
        val vod = CatalogDescriptor(type = "series", id = "top", posterShape = "poster")
        val oddMovie = CatalogDescriptor(type = "movie", id = "featured", posterShape = "landscape")

        assertEquals(RowAspect.Landscape, live.rowAspect)
        assertEquals(RowAspect.Poster, vod.rowAspect)
        assertEquals(RowAspect.Landscape, oddMovie.rowAspect)
        /* Ids de fila distintos por tipo+id: el hero indexa por fila/título. */
        assertEquals("remote:tv/live", live.rowId)
        assertEquals("remote:movie/featured", oddMovie.rowId)
    }

    @Test
    fun `los identificadores tt se etiquetan como Cinemeta`() {
        assertEquals("Cinemeta", meta().toEntry().provider)
        assertNull(meta(id = "tv:demo:espn-1", type = "tv").toEntry().provider)
    }

    @Test
    fun `los videos se agrupan en temporadas ordenadas y sin duplicados`() {
        val groups = listOf(
            RemoteVideo(id = "tt:1:2", season = 1, episode = 2, name = "Segundo"),
            RemoteVideo(id = "tt:1:1", season = 1, episode = 1, name = "Primero"),
            RemoteVideo(id = "tt:2:5", season = 2, episode = 5, name = "Quinto"),
            RemoteVideo(id = "tt:1:1", season = 1, episode = 1, name = "Duplicado"),
            RemoteVideo(id = "tt:trailer", season = null, episode = null, name = "Tráiler"),
        ).toSeasonGroups()

        assertEquals(listOf(1, 2), groups.map { it.season })
        assertEquals(listOf(1, 2), groups[0].episodes.map { it.episode })
        assertEquals(listOf("Primero", "Segundo"), groups[0].episodes.map { it.name })
        assertEquals("T2", groups[1].label)
    }

    @Test
    fun `el id del episodio es el que espera el addon`() {
        val episode = EpisodeOption(season = 1, episode = 2, name = "The Kingsroad")

        assertEquals("tt0944947:1:2", episode.idFor("tt0944947"))
        assertEquals("T1 · E2", episode.label)
        assertEquals("E2", episode.shortLabel)
    }

    @Test
    fun `Urls escribe los extras como segmentos de path, en el orden del protocolo`() {
        assertEquals(
            "http://host:7000/catalog/movie/top.json",
            Urls.catalog("http://host:7000/", "movie", "top"),
        )
        assertEquals(
            "http://host:7000/catalog/movie/top/genre=Sci-Fi/skip=100/search=the%20matrix.json",
            Urls.catalog("host:7000", "movie", "top", search = "the matrix", genre = "Sci-Fi", skip = 100),
        )
        assertEquals(
            "http://host:7000/catalog/tv/live/search=canal%2013.json",
            Urls.catalog("http://host:7000", "tv", "live", search = "canal 13"),
        )
    }

    @Test
    fun `Urls no toca los dos puntos de los ids de serie`() {
        assertEquals(
            "http://host:7000/meta/series/tt0944947:1:2.json",
            Urls.meta("http://host:7000", "series", "tt0944947:1:2"),
        )
        assertEquals("http://host:7000/catalogs.json", Urls.catalogs("http://host:7000"))
    }

    @Test
    fun `el id de una pelicula no lleva temporada`() {
        val entry = meta(id = "tt0133093", type = "movie", releaseInfo = "1999").toEntry()
        assertFalse(entry.id.contains(":"))
        assertFalse(entry.isLive)
    }
}
