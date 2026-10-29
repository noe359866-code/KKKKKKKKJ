package com.tanasi.streamflix.player

import com.tanasi.streamflix.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El failover es la parte de la app donde un error cuesta una película: estas
 * pruebas fijan el contrato (orden, un intento por fuente, sin bucles).
 */
class FailoverPlannerTest {

    private fun source(index: Int, url: String = "https://cdn$index.example/index.m3u8") =
        StreamSource(
            url = url,
            name = "Servidor $index",
            title = "Servidor $index",
            quality = "1080p",
            provider = "Proveedor $index",
            index = index,
        )

    private val threeSources = listOf(source(0), source(1), source(2))

    @Test
    fun `start devuelve la fuente de mayor prioridad`() {
        val planner = FailoverPlanner(threeSources)

        val first = planner.start()

        assertEquals("https://cdn0.example/index.m3u8", first?.url)
        assertEquals(1, planner.progress)
        assertEquals(3, planner.total)
        assertFalse(planner.isExhausted)
    }

    @Test
    fun `fail avanza en orden y conserva el total`() {
        val planner = FailoverPlanner(threeSources)
        planner.start()

        val second = planner.fail(FailoverReason.HTTP_ERROR)
        val third = planner.fail(FailoverReason.STALL)

        assertEquals("https://cdn1.example/index.m3u8", second?.url)
        assertEquals("https://cdn2.example/index.m3u8", third?.url)
        assertEquals(3, planner.progress)
    }

    @Test
    fun `al agotar la lista devuelve null y no vuelve a empezar`() {
        val planner = FailoverPlanner(threeSources)
        planner.start()

        repeat(3) { planner.fail(FailoverReason.PLAYER_ERROR) }
        val afterEnd = planner.fail(FailoverReason.PLAYER_ERROR)

        assertNull("no debe reiniciar el ciclo", afterEnd)
        assertTrue(planner.isExhausted)
        assertNull(planner.current)
    }

    @Test
    fun `sin fuentes no hay nada que reproducir`() {
        val planner = FailoverPlanner(emptyList())

        assertNull(planner.start())
        assertTrue(planner.isExhausted)
        assertEquals(0, planner.progress)
    }

    @Test
    fun `el historial registra cada salto con su motivo`() {
        val planner = FailoverPlanner(threeSources)
        planner.start()

        planner.fail(FailoverReason.HTTP_ERROR, "404")
        planner.fail(FailoverReason.STALL, "12s sin datos")

        assertEquals(2, planner.history.size)
        assertEquals(FailoverReason.HTTP_ERROR, planner.history[0].reason)
        assertEquals("404", planner.history[0].message)
        assertEquals("https://cdn0.example/index.m3u8", planner.history[0].from?.url)
        assertEquals("https://cdn1.example/index.m3u8", planner.history[0].to?.url)
        assertEquals(FailoverReason.STALL, planner.history[1].reason)
    }

    @Test
    fun `select permite elegir una fuente concreta`() {
        val planner = FailoverPlanner(threeSources)
        planner.start()

        val chosen = planner.select(2)

        assertEquals("https://cdn2.example/index.m3u8", chosen?.url)
        assertEquals(2, planner.currentIndex)
        assertEquals(FailoverReason.MANUAL, planner.history.last().reason)
    }

    @Test
    fun `select con un indice fuera de rango no rompe`() {
        val planner = FailoverPlanner(threeSources)
        planner.start()

        assertNull(planner.select(99))
        assertEquals(0, planner.currentIndex)
    }

    @Test
    fun `hasNext refleja si quedan servidores`() {
        val planner = FailoverPlanner(listOf(source(0)))
        planner.start()

        assertFalse(planner.hasNext())
        planner.fail(FailoverReason.PLAYER_ERROR)
        planner.fail(FailoverReason.PLAYER_ERROR)
        assertNull(planner.current)
    }

    @Test
    fun `start puede arrancar en la fuente elegida por el usuario`() {
        val planner = FailoverPlanner(threeSources)

        val chosen = planner.start(atIndex = 2)

        assertEquals("https://cdn2.example/index.m3u8", chosen?.url)
        assertEquals(3, planner.progress)
        /* Desde la última fuente ya no hay plan B: el planificador lo dice en
         * vez de envolver al principio de la lista. */
        assertNull(planner.fail(FailoverReason.MANUAL))
        assertTrue(planner.isExhausted)
    }

    @Test
    fun `un indice fuera de rango se acota a la lista`() {
        assertEquals(
            "https://cdn0.example/index.m3u8",
            FailoverPlanner(threeSources).start(atIndex = -5)?.url,
        )
        assertEquals(
            "https://cdn2.example/index.m3u8",
            FailoverPlanner(threeSources).start(atIndex = 99)?.url,
        )
    }
}
