package com.tanasi.streamflix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La compuerta del D-Pad es lo que separa una navegación fluida de una lista que
 * avanza a tirones. Estas pruebas fijan su contrato: nunca frena la primera
 * pulsación, frena las repeticiones demasiado seguidas y el eje vertical es más
 * pausado que el horizontal.
 */
class DpadRepeatPolicyTest {

    @Test
    fun `una repeticion inmediata se descarta`() {
        assertTrue(DpadRepeatPolicy.shouldDrop(elapsedMs = 0L, gateMs = 80L))
        assertTrue(DpadRepeatPolicy.shouldDrop(elapsedMs = 79L, gateMs = 80L))
    }

    @Test
    fun `justo en la compuerta ya se deja pasar`() {
        assertFalse(DpadRepeatPolicy.shouldDrop(elapsedMs = 80L, gateMs = 80L))
        assertFalse(DpadRepeatPolicy.shouldDrop(elapsedMs = 500L, gateMs = 80L))
    }

    @Test
    fun `el eje vertical es mas pausado que el horizontal`() {
        val horizontal = DpadRepeatPolicy.gateFor(vertical = false)
        val vertical = DpadRepeatPolicy.gateFor(vertical = true)

        assertEquals(DpadRepeatPolicy.HORIZONTAL_GATE_MS, horizontal)
        assertEquals(DpadRepeatPolicy.VERTICAL_GATE_MS, vertical)
        assertTrue("bajar de fila debe pedir más tiempo que recorrer una", vertical > horizontal)
    }

    @Test
    fun `una compuerta negativa no congela la navegacion`() {
        /* Un 0 o un valor raro del llamador no puede bloquear el mando para
         * siempre: la compuerta se acota a cero y todo pasa. */
        assertFalse(DpadRepeatPolicy.shouldDrop(elapsedMs = 0L, gateMs = -100L))
    }

    @Test
    fun `el ritmo real de un mando sostenido cabe bajo el limite`() {
        /* Muchos TV box repiten cada ~50 ms. Con la compuerta horizontal de
         * 80 ms, uno de cada dos eventos se descarta: la lista avanza a ~12
         * pasos por segundo en vez de 20. */
        var accepted = 0
        var lastAccepted = 0L
        for (elapsed in 0L..1000L step 50L) {
            if (!DpadRepeatPolicy.shouldDrop(elapsed - lastAccepted, DpadRepeatPolicy.HORIZONTAL_GATE_MS)) {
                accepted++
                lastAccepted = elapsed
            }
        }
        assertTrue("no debe aceptar los 21 eventos del sistema", accepted < 21)
        /* 10 repeticiones aceptadas en el primer segundo: la lista avanza a la
         * mitad de la velocidad del mando, que es justo el punto donde todavía
         * se puede seguir cada tarjeta con la vista. */
        assertEquals(10, accepted)
    }
}
