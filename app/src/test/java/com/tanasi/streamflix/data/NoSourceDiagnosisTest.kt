package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.model.NoSourceDiagnosis
import com.tanasi.streamflix.data.model.ProviderStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Por qué se quedó sin fuentes un título.
 *
 * Lo que se comprueba no es el texto exacto —eso se puede retocar— sino la
 * **distinción**: un título que no está en ningún catálogo y una red caída
 * piden cosas distintas del usuario, y el mensaje tiene que delatarlo.
 */
class NoSourceDiagnosisTest {

    private fun provider(id: String, status: String, streams: Int = 0) =
        ProviderStatus(id = id, label = id.replaceFirstChar { it.uppercase() }, status = status, streams = streams)

    @Test
    fun `si alguien respondio con cero fuentes el problema es el titulo`() {
        val message = NoSourceDiagnosis.explain(
            listOf(
                provider("cuevana", "empty"),
                provider("gnula", "empty"),
                provider("repelis", "error"),
            ),
        )

        /* Clave: NO se sugiere reintentar, porque reintentar no va a cambiar
         * nada: el catálogo ya dijo que no lo tiene. */
        assertTrue(message, message.contains("tiene fuentes para este título"))
        assertFalse("no debe invitar a reintentar cuando el título no está", message.contains("Reintenta"))
    }

    @Test
    fun `si todos fallaron sugiere reintentar y nombra a los culpables`() {
        val message = NoSourceDiagnosis.explain(
            listOf(
                provider("cuevana", "error"),
                provider("gnula", "blocked"),
                provider("repelis", "error"),
            ),
        )

        assertTrue(message, message.contains("Ninguno de los 3 proveedores respondió"))
        assertTrue(message, message.contains("Cuevana"))
        assertTrue(message, message.contains("Reintenta en un momento"))
    }

    @Test
    fun `distingue el caso de tiempo agotado`() {
        val message = NoSourceDiagnosis.explain(
            listOf(provider("cuevana", "timeout"), provider("gnula", "timeout")),
        )

        assertTrue(message, message.contains("todos agotaron el tiempo de espera"))
    }

    @Test
    fun `no nombra mas de tres proveedores`() {
        val many = (1..7).map { provider("p$it", "error") }

        val message = NoSourceDiagnosis.explain(many)

        assertTrue(message, message.contains("y 4 más"))
        /* Siete nombres en una pantalla de 1080p con letra grande no cabe. */
        assertFalse(message, message.contains("P7"))
    }

    @Test
    fun `sin datos no se inventa un diagnostico`() {
        /* Un addon de terceros no manda `providers`: aquí no se puede saber si
         * el problema es el título o la red, así que se dicen las dos. */
        val message = NoSourceDiagnosis.explain(emptyList())

        assertTrue(message, message.contains("Puede ser el título o la conexión"))
    }

    @Test
    fun `un estado desconocido del servidor no rompe nada`() {
        /* `status` es texto libre a propósito: un valor nuevo en el servidor no
         * debe reventar el cliente. Se trata como un fallo, que es lo prudente. */
        val message = NoSourceDiagnosis.explain(listOf(provider("cuevana", "cloudflare_2026")))

        /* Singular: «ninguno de los 1 proveedores» es texto de relleno. */
        assertTrue(message, message.contains("El único proveedor no respondió"))
    }

    @Test
    fun `el nombre cae al id cuando no hay etiqueta`() {
        val message = NoSourceDiagnosis.explain(
            listOf(ProviderStatus(id = "gnula", label = "", status = "error")),
        )

        assertTrue(message, message.contains("Gnula"))
    }
}
