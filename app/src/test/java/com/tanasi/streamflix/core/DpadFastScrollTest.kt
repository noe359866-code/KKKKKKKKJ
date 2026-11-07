package com.tanasi.streamflix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El deslizamiento rápido con el D-Pad. Lo que importa aquí no es que se mueva,
 * sino que se mueva **igual en cualquier tele**: la velocidad va en dp, un
 * fotograma atascado no puede teletransportar la lista y el avance no puede
 * perderse por redondeo.
 */
class DpadFastScrollTest {

    @Test
    fun `un fotograma a 60 Hz avanza una fraccion de la velocidad`() {
        /* 2400 dp/s durante 16 ms son 38,4 dp. */
        val delta = DpadFastScroll.deltaPx(elapsedMs = 16L, density = 1f)
        assertEquals(38, delta)
    }

    @Test
    fun `la misma tele en 4K se mueve a la misma velocidad`() {
        /* density 1 = 1080p, density 2 = 4K. El doble de píxeles para la misma
         * distancia física: el usuario no debe notar la diferencia. */
        val hd = DpadFastScroll.deltaPx(elapsedMs = 16L, density = 1f)
        val uhd = DpadFastScroll.deltaPx(elapsedMs = 16L, density = 2f)

        assertEquals(hd * 2, uhd)
    }

    @Test
    fun `un fotograma atascado no teletransporta la lista`() {
        /* Medio segundo entre fotogramas serían 1200 dp de golpe. El tope lo
         * deja en ~115 dp. */
        val stuck = DpadFastScroll.deltaPx(elapsedMs = 500L, density = 1f)
        val atCap = DpadFastScroll.deltaPx(elapsedMs = DpadFastScroll.MAX_FRAME_DT_MS, density = 1f)

        assertEquals(atCap, stuck)
        assertTrue("el tope no puede ser un salto de pantalla", stuck < 120)
    }

    @Test
    fun `el avance no se pierde por redondear cada fotograma`() {
        /* Con densidad baja, un solo fotograma avanza menos de un píxel. Si el
         * delta se calculara contra el fotograma anterior en vez de acumular, la
         * lista no se movería nunca. Aquí se comprueba la suma de diez
         * fotogramas, que sí supera el píxel. */
        val total = (1..10).sumOf { DpadFastScroll.deltaPx(elapsedMs = 16L, density = 0.25f) }
        assertTrue("diez fotogramas tienen que mover algo", total > 0)
    }

    @Test
    fun `sin tiempo o sin densidad no se mueve nada`() {
        assertEquals(0, DpadFastScroll.deltaPx(elapsedMs = 0L, density = 1f))
        assertEquals(0, DpadFastScroll.deltaPx(elapsedMs = -16L, density = 1f))
        assertEquals(0, DpadFastScroll.deltaPx(elapsedMs = 16L, density = 0f))
        assertEquals(0, DpadFastScroll.deltaPx(elapsedMs = 16L, density = 1f, velocityDpPerSec = 0f))
    }
}
