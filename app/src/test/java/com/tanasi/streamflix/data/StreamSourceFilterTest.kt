package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.StreamDto
import com.tanasi.streamflix.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filtro de fuentes.
 *
 * La regla anterior exigía una extensión de vídeo (`.m3u8`, `.mp4`…) y
 * descartaba en silencio las URLs sin extensión, que son la mayoría de los CDN
 * de HLS. El resultado era el peor posible: la app recibía fuentes, las
 * borraba y el usuario veía "no reproduce nada". Estas pruebas fijan el
 * contrato nuevo: **no se tira nada que pueda ser un vídeo**, sólo páginas,
 * imágenes y esquemas que ExoPlayer no entiende.
 */
class StreamSourceFilterTest {

    @Test
    fun rejectsWhatIsDefinitelyNotVideo() {
        assertFalse(StreamSource.looksPlayable(""))
        assertFalse(StreamSource.looksPlayable("magnet:?xt=urn:btih:abc"))
        assertFalse(StreamSource.looksPlayable("https://example.com/embed/pelicula.html"))
        assertFalse(StreamSource.looksPlayable("https://example.com/player.php?id=1"))
        assertFalse(StreamSource.looksPlayable("https://cdn.example.com/poster.jpg"))
        assertFalse(StreamSource.looksPlayable("ftp://example.com/video.m3u8"))
    }

    @Test
    fun keepsExplicitVideoExtensions() {
        assertTrue(StreamSource.looksPlayable("https://cdn.example.com/master.m3u8"))
        assertTrue(StreamSource.looksPlayable("https://cdn.example.com/live/index.m3u8?token=abc"))
        assertTrue(StreamSource.looksPlayable("https://cdn.example.com/video.mpd#t=10"))
        assertTrue(StreamSource.looksPlayable("https://cdn.example.com/movie.mp4"))
        assertTrue(StreamSource.looksPlayable("https://cdn.example.com/clip.mkv"))
    }

    @Test
    fun extensionlessCdnUrlsAreKept() {
        /* Un HLS servido en una ruta firmada no lleva extensión: antes se
         * descartaba y la fuente desaparecía del panel de servidores. */
        assertTrue(StreamSource.looksPlayable("https://mdstrm.com/live-stream-playlist/60b578b060947317de7b57ac"))
        assertTrue(StreamSource.looksPlayable("https://edge.example.com/hls/live/2022749/event01"))
        assertTrue(StreamSource.looksPlayable("https://cdn.example.com/v1/play/9f8e7d6c5b"))
    }

    @Test
    fun dtoBecomesAPlayableSourceWithItsHeaders() {
        val source = StreamDto(
            name = "iptv-org",
            title = "ADN 40",
            url = "https://mdstrm.com/live-stream-playlist/60b578b060947317de7b57ac.m3u8",
            isLive = true,
        ).toSource(index = 0)

        assertEquals("ADN 40", source?.title)
        assertTrue(source?.isLive == true)
        assertTrue(source?.isHls == true)
        assertEquals(0, source?.index)
    }

    @Test
    fun dtoWithoutAUrlIsDropped() {
        assertNull(StreamDto(name = "torrent", title = "Torrent", url = "").toSource(index = 0))
        assertNull(StreamDto(name = "html", title = "Embed", url = "https://example.com/x.html").toSource(index = 0))
    }

    /**
     * `behaviorHints.mediaType` es la pista que manda el scraper cuando la URL
     * no tiene extensión: sin ella ExoPlayer abriría un HLS como progresivo y el
     * resultado sería "no reproduce nada" aunque la fuente fuera buena.
     */
    @Test
    fun theScraperMediaTypeDecidesHowTheUrlIsPlayed() {
        val hints = com.tanasi.streamflix.data.model.BehaviorHints(mediaType = "hls")
        val hls = StreamDto(
            name = "PelisJuanita",
            title = "Película",
            url = "https://cdn.example.com/v/abc123",
            quality = "1080p",
            behaviorHints = hints,
        ).toSource(index = 0)

        assertEquals(StreamSource.MEDIA_TYPE_HLS, hls?.mediaType)
        assertTrue(hls?.isHls == true)
        assertEquals("HLS · 1080p", hls?.formatLine)

        val mp4 = StreamDto(
            name = "PelisJuanita",
            title = "Película",
            url = "https://cdn.example.com/v/def456",
            behaviorHints = com.tanasi.streamflix.data.model.BehaviorHints(mediaType = "video/mp4"),
        ).toSource(index = 0)

        assertEquals(StreamSource.MEDIA_TYPE_MP4, mp4?.mediaType)
        assertFalse(mp4?.isHls == true)
        assertEquals("MP4", mp4?.formatLine)
    }

    @Test
    fun anUnknownMediaTypeFallsBackToTheExtension() {
        assertNull(StreamSource.parseMediaType(null))
        assertNull(StreamSource.parseMediaType("  "))
        assertNull(StreamSource.parseMediaType("text/html"))
        assertEquals(StreamSource.MEDIA_TYPE_HLS, StreamSource.parseMediaType("application/x-mpegURL"))
        assertEquals(StreamSource.MEDIA_TYPE_DASH, StreamSource.parseMediaType("DASH"))
        assertEquals(StreamSource.MEDIA_TYPE_MP4, StreamSource.parseMediaType("mp4"))
        assertTrue(StreamSource(url = "https://cdn.example.com/master.m3u8", name = "n", title = "t").isHls)
    }

    /**
     * Línea técnica que enseña el selector de fuentes del reproductor: el
     * formato real del enlace y la calidad anunciada, nunca una calidad
     * inventada.
     */
    @Test
    fun theSourceListShowsTheFormatAndQualityItActuallyHas() {
        val hls = StreamSource(url = "https://cdn.example.com/master.m3u8", name = "n", title = "t",
                               quality = "1080p", provider = "PelisPlus")
        val dash = StreamSource(url = "https://cdn.example.com/video.mpd", name = "n", title = "t")
        val mp4 = StreamSource(url = "https://cdn.example.com/clip.mp4", name = "n", title = "t", quality = " ")

        assertEquals("HLS · 1080p", hls.formatLine)
        assertEquals("DASH", dash.formatLine)
        /* Una calidad en blanco no deja un " · " colgando. */
        assertEquals("MP4", mp4.formatLine)
        assertEquals("PelisPlus · 1080p", hls.label)
    }
}
