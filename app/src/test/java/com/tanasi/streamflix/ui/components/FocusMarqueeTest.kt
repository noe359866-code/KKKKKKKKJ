package com.tanasi.streamflix.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuándo se desplaza un título. La regla decide entre "se lee entero" y "se
 * corta con puntos suspensivos" en cada tarjeta del Home, así que un error de
 * un píxel se multiplica por toda la pantalla.
 */
class FocusMarqueeTest {

    @Test
    fun `un titulo que cabe no se desplaza`() {
        assertFalse(shouldMarquee(textWidthPx = 100, availableWidthPx = 300))
        assertFalse(shouldMarquee(textWidthPx = 300, availableWidthPx = 300))
    }

    @Test
    fun `un titulo que desborda se desplaza`() {
        assertTrue(shouldMarquee(textWidthPx = 302, availableWidthPx = 300))
        assertTrue(shouldMarquee(textWidthPx = 900, availableWidthPx = 300))
    }

    @Test
    fun `un pixel de mas no justifica mover el texto`() {
        /* Medir y pintar nunca coinciden exactamente. El margen evita que un
         * título justo en el límite entre y salga del desplazamiento con cada
         * recomposición. El precio es que un desborde de un solo píxel se
         * queda con puntos suspensivos: se pierde parte de un carácter, no una
         * letra. */
        assertFalse(shouldMarquee(textWidthPx = 301, availableWidthPx = 300))
    }

    @Test
    fun `sin medida todavia no se desplaza nada`() {
        /* El ancho llega en el primer `onSizeChanged`. Antes de eso no hay nada
         * que decidir, y arrancar el desplazamiento para quitarlo un fotograma
         * después se ve como un parpadeo. */
        assertFalse(shouldMarquee(textWidthPx = 900, availableWidthPx = 0))
        assertFalse(shouldMarquee(textWidthPx = 900, availableWidthPx = -5))
    }
}
