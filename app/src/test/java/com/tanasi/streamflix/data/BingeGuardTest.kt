package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.BingeGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regla del aviso «¿sigues ahí?».
 *
 * El caso que importa es el **límite**: una maratón de tres capítulos no debe
 * interrumpirse, y la cuarta apertura automática sí pregunta. Un `>=` mal
 * puesto aquí se traduce en un aviso molesto cada tres episodios.
 */
class BingeGuardTest {

    @Test
    fun `con menos de cuatro capitulos seguidos no pregunta`() {
        assertFalse(BingeGuard.shouldAsk(0))
        assertFalse(BingeGuard.shouldAsk(1))
        assertFalse(BingeGuard.shouldAsk(2))
        assertFalse(BingeGuard.shouldAsk(3))
    }

    @Test
    fun `al cuarto capitulo automatico si pregunta`() {
        assertTrue(BingeGuard.shouldAsk(4))
        assertTrue(BingeGuard.shouldAsk(5))
        assertTrue(BingeGuard.shouldAsk(12))
    }

    @Test
    fun `el umbral es el que se documenta`() {
        /* Si alguien cambia la constante, este test obliga a cambiar el
         * comentario que explica por qué es cuatro. */
        assertTrue(BingeGuard.EPISODES_BEFORE_PROMPT == 4)
    }

    @Test
    fun `desactivado nunca pregunta`() {
        /* Punto de enganche para un ajuste futuro: la regla acepta `enabled`
         * desde el primer día para que apagarlo no exija tocar el conteo. */
        assertFalse(BingeGuard.shouldAsk(10, enabled = false))
        assertFalse(BingeGuard.shouldAsk(4, enabled = false))
    }

    @Test
    fun `un contador negativo no pregunta`() {
        /* No debería ocurrir, pero un `int` de un `Bundle` puede venir con
         * cualquier cosa: la regla tiene que ser total. */
        assertFalse(BingeGuard.shouldAsk(-1))
    }
}
