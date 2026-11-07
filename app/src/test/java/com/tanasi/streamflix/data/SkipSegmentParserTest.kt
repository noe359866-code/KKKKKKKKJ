package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.SkipSegment
import com.tanasi.streamflix.data.model.SkipSegmentParser
import com.tanasi.streamflix.data.model.SkipSegmentType
import com.tanasi.streamflix.data.model.SkipWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El parser tiene que entender **tres dialectos** de la misma idea: TheIntroDB
 * v4 (milisegundos, listas, `credits`), TheIntroDB v2/v3 (igual, encabezado por
 * `tmdb_id`) e IntroDB (segundos, objetos sueltos, `outro`). Los cuerpos de
 * abajo son los que publica cada documentación, recortados a lo que se usa.
 */
class SkipSegmentParserTest {

    /* ---------------------------- TheIntroDB v4 ---------------------------- */

    private val theIntroDbV4 = """
        {
          "tvdb_id": 71663,
          "type": "tv",
          "season": 1,
          "episode": 1,
          "intro":  [ { "start_ms": 30000, "end_ms": 90000 },
                      { "start_ms": 120000, "end_ms": 150000 } ],
          "recap":  [ { "start_ms": 25000, "end_ms": 134000 } ],
          "credits":[ { "start_ms": 5801777, "end_ms": 6371111 },
                      { "start_ms": 6408000, "end_ms": null } ],
          "preview":[ { "start_ms": 1680000, "end_ms": 1740000 } ]
        }
    """.trimIndent()

    @Test
    fun `theintrodb v4 en milisegundos y en listas`() {
        val segments = SkipSegmentParser.parse(theIntroDbV4, durationMs = 6_500_000L)

        val intro = segments.first { it.type == SkipSegmentType.Intro }
        assertEquals(30_000L, intro.startMs)
        assertEquals(90_000L, intro.endMs)
    }

    @Test
    fun `v4 sin start significa que el tramo empieza en cero`() {
        val body = """{"intro":[{"start_ms":null,"end_ms":23000}]}"""
        val intro = SkipSegmentParser.parse(body, durationMs = 0L).single { it.type == SkipSegmentType.Intro }

        assertEquals(0L, intro.startMs)
        assertEquals(23_000L, intro.endMs)
    }

    @Test
    fun `v4 con credits sin final se cierra con la duracion real`() {
        /* `end_ms: null` en créditos quiere decir "hasta el final del vídeo".
         * Sin duración no hay adónde saltar, así que ese tramo se cae y el
         * otro (que sí tiene final) se conserva. */
        val conDuracion = SkipSegmentParser.parse(theIntroDbV4, durationMs = 6_500_000L)
        val credits = conDuracion.filter { it.type == SkipSegmentType.Credits }
        assertEquals(2, credits.size)
        assertEquals(6_500_000L, credits.last().endMs)

        val sinDuracion = SkipSegmentParser.parse(theIntroDbV4, durationMs = 0L)
        assertEquals(1, sinDuracion.count { it.type == SkipSegmentType.Credits })
    }

    @Test
    fun `v4 ignora preview porque no es un salto`() {
        /* `preview` es el avance del próximo capítulo: saltarlo no es lo que
         * el usuario espera de un botón de "saltar". */
        val segments = SkipSegmentParser.parse(theIntroDbV4, durationMs = 6_500_000L)
        assertEquals(3, segments.map { it.type }.distinct().size)
    }

    /* ------------------------- TheIntroDB v2/v3 ---------------------------- */

    @Test
    fun `v2 y v3 se leen igual aunque el cuerpo venga con tmdb_id`() {
        val v3 = """
            {"tmdb_id":456,"type":"tv",
             "intro":[{"start_ms":null,"end_ms":23000}],
             "recap":[{"start_ms":25000,"end_ms":134000}],
             "credits":[{"start_ms":5801777,"end_ms":6371111}]}
        """.trimIndent()

        val segments = SkipSegmentParser.parse(v3, durationMs = 6_500_000L)
        assertEquals(3, segments.size)
        assertEquals(23_000L, segments.first { it.type == SkipSegmentType.Intro }.endMs)
    }

    /* ------------------------------ IntroDB -------------------------------- */

    private val introDb = """
        {
          "imdb_id": "tt0903747",
          "media_type": "tv",
          "is_movie": false,
          "season": 1,
          "episode": 1,
          "intro": { "start_sec": 2.5, "end_sec": 58,
                     "start_ms": 2500, "end_ms": 58000,
                     "confidence": 0.92, "submission_count": 14 },
          "recap": null,
          "outro": null,
          "post_credits": null,
          "no_intro": false, "no_recap": true, "no_outro": false
        }
    """.trimIndent()

    @Test
    fun `introdb trae objetos sueltos en segundos y milisegundos`() {
        val segments = SkipSegmentParser.parse(introDb, durationMs = 0L)

        val intro = segments.single()
        assertEquals(SkipSegmentType.Intro, intro.type)
        assertEquals(2_500L, intro.startMs)
        assertEquals(58_000L, intro.endMs)
    }

    @Test
    fun `introdb llama outro a los creditos`() {
        val body = """{"outro":{"start_sec":1000,"end_sec":1100}}"""
        val credits = SkipSegmentParser.parse(body, durationMs = 0L).single()

        assertEquals(SkipSegmentType.Credits, credits.type)
        assertEquals(1_000_000L, credits.startMs)
    }

    @Test
    fun `introdb con segundos en texto no pierde el tramo`() {
        /* El envío documenta `number | string`; una respuesta con cadenas no
         * debe tirar el tramo entero. */
        val body = """{"intro":{"start_sec":"2.5","end_sec":"58"}}"""
        val intro = SkipSegmentParser.parse(body, durationMs = 0L).single()

        assertEquals(2_500L, intro.startMs)
        assertEquals(58_000L, intro.endMs)
    }

    /* ------------------------------- Bordes -------------------------------- */

    @Test
    fun `los tramos demasiado cortos no se ofrecen`() {
        /* Menos de cinco segundos no es un tramo, es un dato mal formado: el
         * mínimo que publican las dos bases es justo ése. */
        val body = """{"intro":{"start_ms":10000,"end_ms":12000}}"""
        assertTrue(SkipSegmentParser.parse(body, durationMs = 0L).isEmpty())
    }

    @Test
    fun `un tipo repetido en dos claves no duplica el boton`() {
        /* `credits` y `outro` son el mismo tipo en dialectos distintos. */
        val body = """
            {"credits":[{"start_ms":100000,"end_ms":200000}],
             "outro":{"start_sec":100,"end_sec":200}}
        """.trimIndent()
        assertEquals(1, SkipSegmentParser.parse(body, durationMs = 0L).count { it.type == SkipSegmentType.Credits })
    }

    @Test
    fun `un cuerpo roto o vacio no lanza`() {
        assertTrue(SkipSegmentParser.parse("", durationMs = 0L).isEmpty())
        assertTrue(SkipSegmentParser.parse("no es json", durationMs = 0L).isEmpty())
        assertTrue(SkipSegmentParser.parse("{}", durationMs = 0L).isEmpty())
        assertTrue(SkipSegmentParser.parse("[]", durationMs = 0L).isEmpty())
        assertTrue(SkipSegmentParser.parse("""{"error":"no data"}""", durationMs = 0L).isEmpty())
    }

    @Test
    fun `los tramos salen ordenados por inicio`() {
        val segments = SkipSegmentParser.parse(theIntroDbV4, durationMs = 6_500_000L)
        assertEquals(segments.sortedBy { it.startMs }, segments)
    }

    @Test
    fun `un tramo con final anterior al inicio se descarta`() {
        val body = """{"intro":{"start_ms":90000,"end_ms":30000}}"""
        assertTrue(SkipSegmentParser.parse(body, durationMs = 0L).isEmpty())
    }

    /* ---------------------------- La ventana ------------------------------- */

    @Test
    fun `dentro del tramo el boton salta al final`() {
        val intro = SkipSegment(SkipSegmentType.Intro, startMs = 30_000L, endMs = 90_000L)
        val window = SkipWindow.at(listOf(intro), 45_000L)

        assertEquals(intro, window.active)
        assertEquals(null, window.upcoming)
    }

    @Test
    fun `justo al final del tramo ya no se ofrece`() {
        val intro = SkipSegment(SkipSegmentType.Intro, startMs = 30_000L, endMs = 90_000L)
        assertEquals(null, SkipWindow.at(listOf(intro), 90_000L).active)
    }

    @Test
    fun `cinco segundos antes ya se puede avisar`() {
        val intro = SkipSegment(SkipSegmentType.Intro, startMs = 30_000L, endMs = 90_000L)
        val pronto = SkipWindow.at(listOf(intro), 26_000L)
        val tarde = SkipWindow.at(listOf(intro), 24_000L)

        assertEquals(intro, pronto.upcoming)
        assertEquals(null, tarde.upcoming)
    }

    @Test
    fun `dentro de un tramo no se anuncia el siguiente`() {
        /* Estar en la intro y avisar del resumen a la vez sería dos botones en
         * pantalla; primero se sale de donde se está. */
        val intro = SkipSegment(SkipSegmentType.Intro, startMs = 30_000L, endMs = 90_000L)
        val recap = SkipSegment(SkipSegmentType.Recap, startMs = 92_000L, endMs = 200_000L)
        val window = SkipWindow.at(listOf(intro, recap), 88_000L)

        assertEquals(intro, window.active)
        assertEquals(null, window.upcoming)
    }
}
