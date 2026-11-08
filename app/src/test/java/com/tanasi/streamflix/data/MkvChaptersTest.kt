package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.MkvChapters
import com.tanasi.streamflix.data.model.SkipSegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lectura de los capítulos que lleva dentro un `.mkv`.
 *
 * Los bytes de prueba son un Matroska sintético construido a mano (cabecera
 * EBML + segmento + tres capítulos) y verificado byte a byte contra la spec.
 * Antes de escribir el parser en Kotlin se portó a Python y se ejecutó contra
 * estos mismos archivos: dos de los tres fallos que aparecieron estaban en el
 * fichero de prueba, no en el parser, y por eso quedan fijados aquí.
 */
class MkvChaptersTest {

    @Test
    fun `lee los capitulos de un mkv con su elemento Chapters`() {
        val chapters = MkvChapters.parse(mkvWithChapters)

        assertEquals(3, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals("Opening Credits", chapters[0].title)
        assertEquals(90_000L, chapters[1].startMs)          // 90 s
        assertEquals("Part One", chapters[1].title)
        assertEquals(2_400_000L, chapters[2].startMs)       // 40 min
    }

    @Test
    fun `encuentra los capitulos aunque falte el elemento Chapters`() {
        /* Muchos archivos reales no envuelven los capítulos en `Chapters` o
         * ponen tamaño desconocido. El respaldo los busca desde la raíz. */
        val chapters = MkvChapters.parse(mkvWithoutChapters)

        assertEquals(3, chapters.size)
        assertEquals("Opening Credits", chapters[0].title)
        assertEquals("Recap", chapters[1].title)
        assertEquals("End Credits", chapters[2].title)
    }

    @Test
    fun `un archivo que no es mkv no rompe nada`() {
        /* La información es opcional: si esto lanzara, el usuario se quedaría
         * sin reproductor por un archivo raro. */
        assertTrue(MkvChapters.parse("no soy un mkv".toByteArray()).isEmpty())
        assertTrue(MkvChapters.parse(ByteArray(0)).isEmpty())
    }

    @Test
    fun `un archivo truncado a la mitad devuelve vacio sin lanzar`() {
        val truncated = mkvWithChapters.copyOf(mkvWithChapters.size / 2)

        assertTrue(MkvChapters.parse(truncated).isEmpty())
    }

    @Test
    fun `el final de un tramo es el principio del capitulo siguiente`() {
        val chapters = MkvChapters.parse(mkvWithChapters)

        val segments = MkvChapters.segmentsFrom(chapters, durationMs = 0L)

        /* Un tramo empieza donde el capítulo y acaba donde empieza el
         * siguiente: el contenedor no dice dónde termina la intro, sólo dónde
         * empieza el resto. */
        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(90_000L, segments[0].endMs)
    }

    @Test
    fun `Opening Credits son creditos, no una intro`() {
        /* Casa con los dos patrones («opening» y «credits»). Ofrecer «Saltar
         * intro» al empezar la película es lo contrario de lo que se quiere. */
        val segments = MkvChapters.segmentsFrom(
            chapters = listOf(
                MkvChapters.Chapter(0L, "Opening Credits"),
                MkvChapters.Chapter(120_000L, "Part One"),
            ),
            durationMs = 600_000L,
        )

        assertEquals(1, segments.size)
        assertEquals(SkipSegmentType.Credits, segments[0].type)
    }

    @Test
    fun `los capitulos sin nombre identificable no generan tramo`() {
        val chapters = MkvChapters.parse(mkvWithChapters)

        val segments = MkvChapters.segmentsFrom(chapters, durationMs = 3_000_000L)

        /* "Part One" y "Part Two" son capítulos normales: no son un salto. */
        assertTrue(segments.all { it.type != SkipSegmentType.Recap })
        assertTrue(segments.all { it.type != SkipSegmentType.Intro })
        assertEquals(1, segments.size)
        assertEquals(
            "«Opening Credits» empieza en 0 y acaba en el siguiente capítulo",
            0L,
            segments[0].startMs,
        )
    }

    @Test
    fun `los creditos se reconocen y llegan hasta el final con la duracion`() {
        val chapters = MkvChapters.parse(mkvWithoutChapters)

        val segments = MkvChapters.segmentsFrom(chapters, durationMs = 2_700_000L)

        /* `last` y no `first`: «Opening Credits» también es un tramo de
         * créditos, y está en el segundo cero. El que interesa aquí es el del
         * final del archivo. */
        val credits = segments.last { it.type == SkipSegmentType.Credits }
        assertEquals(2_400_000L, credits.startMs)
        assertEquals("sin capítulo después, los créditos van hasta el final", 2_700_000L, credits.endMs)
        val recap = segments.first { it.type == SkipSegmentType.Recap }
        assertEquals(90_000L, recap.startMs)
    }

    @Test
    fun `sin duracion no se ofrece el ultimo tramo`() {
        val chapters = MkvChapters.parse(mkvWithoutChapters)

        val segments = MkvChapters.segmentsFrom(chapters, durationMs = 0L)

        /* Los créditos del **final** son el último capítulo y sin duración no
         * se sabe dónde acaban: un tramo sin final no se puede ofrecer como
         * salto. Los del principio ("Opening Credits") sí tienen final, así
         * que siguen ofreciéndose. */
        assertTrue(
            "no debe haber tramo que empiece en el último capítulo",
            segments.none { it.startMs == 2_400_000L },
        )
    }

    @Test
    fun `los capitulos desordenados se ordenan antes de calcular los tramos`() {
        val chapters = listOf(
            MkvChapters.Chapter(startMs = 90_000L, title = "Part One"),
            MkvChapters.Chapter(startMs = 0L, title = "Intro"),
        )

        val segments = MkvChapters.segmentsFrom(chapters, durationMs = 600_000L)

        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(90_000L, segments[0].endMs)
    }

    @Test
    fun `un capitulo llamado Introduction no es una intro`() {
        /* Se exige palabra completa: "Introduction to Physics" es el nombre de
         * un capítulo, no una cabecera que haya que saltar. */
        val chapters = listOf(
            MkvChapters.Chapter(startMs = 0L, title = "Introduction to Physics"),
            MkvChapters.Chapter(startMs = 300_000L, title = "The End of the World"),
        )

        assertTrue(MkvChapters.segmentsFrom(chapters, durationMs = 900_000L).isEmpty())
    }

    companion object {
        /** MKV sintético: cabecera EBML + segmento + `Chapters` con tres capítulos. */
        private val mkvWithChapters = byteArrayOf(
            0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x83.toByte(), 0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x18.toByte(), 0x53.toByte(), 0x80.toByte(), 0x67.toByte(),
            0xCB.toByte(), 0x10.toByte(), 0x43.toByte(), 0xA7.toByte(), 0x70.toByte(), 0xC6.toByte(), 0x45.toByte(), 0xB9.toByte(), 0xC3.toByte(), 0xB6.toByte(), 0x96.toByte(), 0x91.toByte(),
            0x81.toByte(), 0x00.toByte(), 0x80.toByte(), 0x91.toByte(), 0x85.toByte(), 0x8F.toByte(), 0x4F.toByte(), 0x70.toByte(), 0x65.toByte(), 0x6E.toByte(), 0x69.toByte(), 0x6E.toByte(),
            0x67.toByte(), 0x20.toByte(), 0x43.toByte(), 0x72.toByte(), 0x65.toByte(), 0x64.toByte(), 0x69.toByte(), 0x74.toByte(), 0x73.toByte(), 0xB6.toByte(), 0x93.toByte(), 0x91.toByte(),
            0x85.toByte(), 0x14.toByte(), 0xF4.toByte(), 0x6B.toByte(), 0x04.toByte(), 0x00.toByte(), 0x80.toByte(), 0x8A.toByte(), 0x85.toByte(), 0x88.toByte(), 0x50.toByte(), 0x61.toByte(),
            0x72.toByte(), 0x74.toByte(), 0x20.toByte(), 0x4F.toByte(), 0x6E.toByte(), 0x65.toByte(), 0xB6.toByte(), 0x94.toByte(), 0x91.toByte(), 0x86.toByte(), 0x02.toByte(), 0x2E.toByte(),
            0xCB.toByte(), 0x25.toByte(), 0xC0.toByte(), 0x00.toByte(), 0x80.toByte(), 0x8A.toByte(), 0x85.toByte(), 0x88.toByte(), 0x50.toByte(), 0x61.toByte(), 0x72.toByte(), 0x74.toByte(),
            0x20.toByte(), 0x54.toByte(), 0x77.toByte(), 0x6F.toByte(),
        )

        /** El mismo archivo sin el elemento `Chapters`: sólo `EditionEntry`. */
        private val mkvWithoutChapters = byteArrayOf(
            0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(), 0x83.toByte(), 0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x18.toByte(), 0x53.toByte(), 0x80.toByte(), 0x67.toByte(),
            0xC6.toByte(), 0x45.toByte(), 0xB9.toByte(), 0xC3.toByte(), 0xB6.toByte(), 0x96.toByte(), 0x91.toByte(), 0x81.toByte(), 0x00.toByte(), 0x80.toByte(), 0x91.toByte(), 0x85.toByte(),
            0x8F.toByte(), 0x4F.toByte(), 0x70.toByte(), 0x65.toByte(), 0x6E.toByte(), 0x69.toByte(), 0x6E.toByte(), 0x67.toByte(), 0x20.toByte(), 0x43.toByte(), 0x72.toByte(), 0x65.toByte(),
            0x64.toByte(), 0x69.toByte(), 0x74.toByte(), 0x73.toByte(), 0xB6.toByte(), 0x90.toByte(), 0x91.toByte(), 0x85.toByte(), 0x14.toByte(), 0xF4.toByte(), 0x6B.toByte(), 0x04.toByte(),
            0x00.toByte(), 0x80.toByte(), 0x87.toByte(), 0x85.toByte(), 0x85.toByte(), 0x52.toByte(), 0x65.toByte(), 0x63.toByte(), 0x61.toByte(), 0x70.toByte(), 0xB6.toByte(), 0x97.toByte(),
            0x91.toByte(), 0x86.toByte(), 0x02.toByte(), 0x2E.toByte(), 0xCB.toByte(), 0x25.toByte(), 0xC0.toByte(), 0x00.toByte(), 0x80.toByte(), 0x8D.toByte(), 0x85.toByte(), 0x8B.toByte(),
            0x45.toByte(), 0x6E.toByte(), 0x64.toByte(), 0x20.toByte(), 0x43.toByte(), 0x72.toByte(), 0x65.toByte(), 0x64.toByte(), 0x69.toByte(), 0x74.toByte(), 0x73.toByte(),
        )
    }
}
