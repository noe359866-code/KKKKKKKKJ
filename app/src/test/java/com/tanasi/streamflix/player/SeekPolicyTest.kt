package com.tanasi.streamflix.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bordes del salto con las flechas: son las cuentas que, mal hechas, mandan al
 * usuario al segundo 0 en mitad de una película o le sacan de la reproducción.
 */
class SeekPolicyTest {

    @Test
    fun `avanza el paso completo hacia delante`() {
        assertEquals(30_000L, SeekPolicy.target(20_000L, SeekPolicy.STEP_MS, 600_000L))
    }

    @Test
    fun `retrocede el paso completo`() {
        assertEquals(50_000L, SeekPolicy.target(60_000L, -SeekPolicy.STEP_MS, 600_000L))
    }

    @Test
    fun `no retrocede por debajo de cero`() {
        assertEquals(0L, SeekPolicy.target(3_000L, -SeekPolicy.STEP_MS, 600_000L))
    }

    @Test
    fun `no se pasa del final conocido`() {
        assertEquals(600_000L, SeekPolicy.target(595_000L, SeekPolicy.STEP_MS, 600_000L))
    }

    @Test
    fun `sin duracion conocida no se limita por arriba`() {
        /* C.TIME_UNSET es Long.MIN_VALUE + 1; un directo devuelve un valor <= 0. */
        val target = SeekPolicy.target(1_000L, SeekPolicy.STEP_MS, C_TIME_UNSET)

        assertEquals(11_000L, target)
    }

    @Test
    fun `una posicion negativa se trata como cero`() {
        assertEquals(0L, SeekPolicy.target(-500L, 0L, 600_000L))
    }

    private companion object {
        const val C_TIME_UNSET = Long.MIN_VALUE + 1
    }
}
