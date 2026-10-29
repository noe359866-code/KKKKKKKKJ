package com.tanasi.streamflix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationsTest {

    @Test
    fun `formatea minutos y segundos con dos cifras`() {
        assertEquals("0:00", Durations.clock(0L))
        assertEquals("0:07", Durations.clock(7_000L))
        assertEquals("1:23", Durations.clock(83_000L))
        assertEquals("59:59", Durations.clock(3_599_000L))
    }

    @Test
    fun `anade las horas solo cuando hacen falta`() {
        assertEquals("1:00:00", Durations.clock(3_600_000L))
        assertEquals("1:02:03", Durations.clock(3_723_000L))
    }

    @Test
    fun `una duracion desconocida se pinta como guiones`() {
        assertEquals(Durations.UNKNOWN, Durations.clock(-1L))
        assertEquals(Durations.UNKNOWN, Durations.clock(Long.MIN_VALUE + 1))
    }
}
