package com.tanasi.streamflix.data

import androidx.media3.common.C
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.model.SubtitleDto
import com.tanasi.streamflix.data.model.SubtitleTrack
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.data.repo.StreamsResult
import com.tanasi.streamflix.player.PlayerTracks
import com.tanasi.streamflix.player.TrackOption
import com.tanasi.streamflix.util.AddonSettings
import com.tanasi.streamflix.data.remote.AddonClient
import com.tanasi.streamflix.data.remote.Urls
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Subtítulos: del JSON del addon a la pista que se puede cargar en Media3.
 *
 * Lo que se prueba aquí es lo que puede romper sin avisar: el formato (MIME),
 * la etiqueta que ve el usuario y cuál se activa sola. La carga real la hace
 * `MediaSources` y sólo se puede ver en un dispositivo.
 */
class SubtitlesTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class FakeSettings(override var baseUrl: String) : AddonSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /* ---------------------------------------------------------------------- */
    /* Modelo                                                                 */
    /* ---------------------------------------------------------------------- */

    @Test
    fun `el formato sale de la extension y el MIME de Media3 va acorde`() {
        assertEquals("vtt", SubtitleTrack.formatFor("https://cdn/a.es.vtt?token=1"))
        assertEquals("srt", SubtitleTrack.formatFor("https://cdn/a.es.SRT#t=10"))
        assertEquals("ass", SubtitleTrack.formatFor("https://cdn/a.ass"))
        assertEquals("ttml", SubtitleTrack.formatFor("https://cdn/a.ttml"))
        assertNull(SubtitleTrack.formatFor("https://cdn/subs/12345"))

        assertEquals(SubtitleTrack.MIME_VTT, SubtitleTrack.mimeFor("https://cdn/a.vtt"))
        assertEquals(SubtitleTrack.MIME_SUBRIP, SubtitleTrack.mimeFor("https://cdn/a.srt"))
        assertEquals(SubtitleTrack.MIME_SSA, SubtitleTrack.mimeFor("https://cdn/a.ass"))
        assertEquals(SubtitleTrack.MIME_TTML, SubtitleTrack.mimeFor("https://cdn/a.ttml"))
        /* Sin extensión, SRT: es lo que más publican los proveedores y Media3
         * lo convierte a VTT al vuelo. */
        assertEquals(SubtitleTrack.MIME_SUBRIP, SubtitleTrack.mimeFor("https://cdn/subs/9"))
    }

    @Test
    fun `la etiqueta visible une idioma y proveedor`() {
        val track = SubtitleTrack(url = "https://cdn/a.srt", lang = "es-419", label = "Español (Latino)", provider = "NovaHD")
        assertEquals("Español (Latino) · NovaHD", track.displayLabel)
        assertTrue(track.isSpanish)

        val sinIdioma = SubtitleTrack(url = "https://cdn/b.srt", label = "", provider = null)
        assertEquals("Subtítulo", sinIdioma.displayLabel)

        assertEquals("Español (Latino)", SubtitleTrack.languageLabel("es-419"))
        assertEquals("Portugués (Brasil)", SubtitleTrack.languageLabel("pt-BR"))
        assertEquals("", SubtitleTrack.languageLabel(""))
        assertEquals("Japonés", SubtitleTrack.languageLabel("ja"))
    }

    @Test
    fun `solo se activa sola una pista en espanol y nunca en directo`() {
        val tracks = listOf(
            SubtitleTrack(url = "https://cdn/a.en.vtt", lang = "en", label = "Inglés"),
            SubtitleTrack(url = "https://cdn/b.es.srt", lang = "es", label = "Español"),
        )
        assertEquals(1, SubtitleTrack.preferredIndex(tracks, isLive = false))
        assertNull(SubtitleTrack.preferredIndex(tracks, isLive = true))
        assertNull(SubtitleTrack.preferredIndex(listOf(tracks.first()), isLive = false))
        assertNull(SubtitleTrack.preferredIndex(emptyList(), isLive = false))
    }

    @Test
    fun `un DTO del protocolo se convierte o se descarta`() {
        val track = SubtitleDto(url = "https://cdn/a.es.srt", lang = "SPA", label = "").toTrack()
        assertEquals("https://cdn/a.es.srt", track?.url)
        assertEquals("es", track?.lang)
        assertEquals("Español", track?.label)
        assertEquals("srt", track?.format)

        /* Sin etiqueta ni idioma conocido: al menos una fila legible. */
        assertEquals("Subtítulo", SubtitleDto(url = "https://cdn/x.vtt").toTrack()?.label)

        /* Lo que no es HTTP(S) no es una pista. */
        assertNull(SubtitleDto(url = "/subs/a.srt", lang = "es").toTrack())
        assertNull(SubtitleDto(url = "", lang = "es").toTrack())

        assertEquals("es-419", SubtitleDto.normalizeLang("ES_419"))
        assertEquals("en", SubtitleDto.normalizeLang("english"))
        assertEquals("", SubtitleDto.normalizeLang("subtitulado"))
    }

    /* ---------------------------------------------------------------------- */
    /* Repositorio + respuesta HTTP                                           */
    /* ---------------------------------------------------------------------- */

    @Test
    fun `los subtitulos llegan con las fuentes y sin duplicados`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "streams": [{"url": "https://cdn/video.m3u8", "name": "NovaHD"}],
                  "cacheMaxAge": 300,
                  "subtitles": [
                    {"url": "https://cdn/a.en.vtt", "lang": "en", "label": "Inglés"},
                    {"url": "https://cdn/b.es.srt", "lang": "es", "label": "Español"},
                    {"url": "https://cdn/b.es.srt", "lang": "es", "label": "Español"},
                    {"url": "/relativo.srt", "lang": "es", "label": "Roto"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val repository = StreamsRepository(AddonClient(), FakeSettings(Urls.normalizeBase(server.url("/").toString())))
        val result = repository.streamsFor("movie", "tt1234567")

        assertTrue(result is StreamsResult.Ready)
        val ready = result as StreamsResult.Ready
        assertEquals(1, ready.sources.size)
        assertEquals(listOf("Inglés", "Español"), ready.subtitles.map { it.label })
        assertEquals(listOf("en", "es"), ready.subtitles.map { it.lang })
        assertEquals(300L, ready.cacheMaxAgeSeconds)
        assertEquals("/stream/movie/tt1234567.json", server.takeRequest().path)
    }

    @Test
    fun `una respuesta sin subtitulos deja la lista vacia`() = runTest {
        server.enqueue(MockResponse().setBody("""{"streams":[{"url":"https://cdn/video.m3u8","name":"X"}]}"""))
        val repository = StreamsRepository(AddonClient(), FakeSettings(server.url("/").toString()))
        val ready = repository.streamsFor("movie", "tt1") as StreamsResult.Ready
        assertTrue(ready.subtitles.isEmpty())
    }

    @Test
    fun `normalizeSubtitles tolera un addon sin el campo`() {
        assertTrue(StreamsRepository.normalizeSubtitles(StreamsResponse()).isEmpty())
        val decoded = json.decodeFromString(
            StreamsResponse.serializer(),
            """{"streams":[],"subtitles":[{"url":"https://cdn/a.vtt","lang":"EN"}]}""",
        )
        assertEquals("en", StreamsRepository.normalizeSubtitles(decoded).single().lang)
    }

    /* ---------------------------------------------------------------------- */
    /* Panel de pistas                                                        */
    /* ---------------------------------------------------------------------- */

    @Test
    fun `el panel marca la fila activa y no inventa filas`() {
        val automatico = PlayerTracks.offOption(C.TRACK_TYPE_AUDIO, selected = true)
        assertEquals("Automático", automatico.label)
        assertTrue(automatico.isAutomatic)
        assertFalse(automatico.isText)

        val apagado = PlayerTracks.offOption(C.TRACK_TYPE_TEXT, selected = false)
        assertEquals("Desactivado", apagado.label)
        assertTrue(apagado.isText)

        /* Sin reproductor no hay pistas: el panel no se dibuja. */
        assertTrue(PlayerTracks.options(null, C.TRACK_TYPE_TEXT).isEmpty())
        assertFalse(PlayerTracks.hasPanel(emptyList(), emptyList()))
        assertFalse(PlayerTracks.hasPanel(listOf(apagado), listOf(automatico)))
        assertTrue(PlayerTracks.hasPanel(listOf(apagado), listOf(automatico, automatico.copy(trackIndex = 0))))
    }

    @Test
    fun `los nombres de idioma del panel son legibles`() {
        assertEquals("Español", PlayerTracks.languageName("es"))
        assertEquals("Español (Latino)", PlayerTracks.languageName("es-419"))
        assertEquals("Inglés", PlayerTracks.languageName("en"))
        assertEquals("", PlayerTracks.languageName(null))
        assertEquals("", PlayerTracks.languageName("und"))
        assertNull(PlayerTracks.groupFor(null, TrackOption(C.TRACK_TYPE_AUDIO, -1, -1, "Automático")))
    }
}
