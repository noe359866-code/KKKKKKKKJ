package com.tanasi.streamflix.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Qué le pasó a un proveedor en el scrape del que salieron estas fuentes.
 *
 * El servidor ya calculaba esto para sus propios logs y lo tiraba. Con él, la
 * app puede dejar de decir «ningún proveedor devolvió una fuente» —que no
 * distingue entre «la web está caída», «nos bloqueó» y «esa película no está
 * en el catálogo»— y decir cuál de las tres es.
 *
 * `status` es texto libre: el servidor manda `ok`, `empty`, `error`, `timeout`
 * o el motivo que reporte el propio scraper. No se convierte en un enum porque
 * un valor nuevo en el servidor no debe reventar el `kotlinx.serialization` del
 * cliente: un enum desconocido lanza, un `String` no.
 */
@Serializable
data class ProviderStatus(
    @SerialName("id") val id: String = "",
    @SerialName("label") val label: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("streams") val streams: Int = 0,
    /** `null` cuando el scraper se agotó por tiempo: el servidor no lo midió. */
    @SerialName("ms") val ms: Long? = null,
) {
    /**
     * Nombre para el usuario: la etiqueta legible o, si no vino, el id.
     *
     * El id se capitaliza porque es un identificador (`gnula`), no un nombre
     * de marca: «Fallaron: gnula» en pantalla se lee como un descuido.
     */
    val name: String
        get() = when {
            label.isNotBlank() -> label
            id.isNotBlank() -> id.replaceFirstChar { char -> char.uppercase() }
            else -> "Proveedor"
        }
}

/**
 * Explica por qué un título se quedó sin fuentes.
 *
 * **El problema que resuelve.** Hoy el reproductor muestra «Ningún proveedor
 * devolvió una fuente reproducible» tanto si las nueve webs están caídas como
 * si el título simplemente no existe en ellas. Las dos situaciones piden cosas
 * distintas del usuario: en la primera conviene reintentar en un rato; en la
 * segunda, rendirse o buscar otro título. Sin esta distinción el mensaje es
 * ruido y el usuario reintenta en vano.
 *
 * **La regla, en una frase:** si algún proveedor respondió (aunque fuera con
 * cero fuentes) el problema es del título; si **todos** fallaron, el problema
 * es de la red o de los proveedores.
 */
object NoSourceDiagnosis {

    /**
     * Estados que significan «este proveedor hizo su trabajo y no tenía nada».
     *
     * `empty` es el caso normal y el más importante: el proveedor respondió
     * bien y la película no está en su catálogo.
     */
    private val answeredStatuses = setOf("ok", "empty")

    /** Tope de proveedores que se nombran: más de tres satura la pantalla. */
    const val MAX_NAMED = 3

    /**
     * @param statuses lo que mandó el servidor; vacío si el backend es un
     *   addon de terceros que no publica este campo.
     * @param providerCount cuántos proveedores intentaron el scrape, cuando el
     *   servidor lo dice pero la lista de estados no llegó.
     */
    fun explain(statuses: List<ProviderStatus>): String {
        if (statuses.isEmpty()) {
            /* Sin datos no se inventa un diagnóstico: se dice lo único cierto
             * y se sugiere lo único útil. */
            return "Ningún proveedor devolvió una fuente reproducible. " +
                "Puede ser el título o la conexión; reintenta en un momento."
        }

        val answered = statuses.filter { it.status.lowercase() in answeredStatuses }
        val failed = statuses.filterNot { it.status.lowercase() in answeredStatuses }

        /* Un solo proveedor se nombra en singular: «ninguno de los 1
         * proveedores» es la clase de texto que hace parecer descuidada una
         * app que por lo demás está pulida. */

        /* Algún proveedor contestó que no tiene nada: el título no está. */
        if (answered.isNotEmpty()) {
            return if (statuses.size == 1) "El único proveedor no tiene fuentes para este título."
                else "Ninguno de los ${statuses.size} proveedores tiene fuentes para este título."
        }

        val timedOut = failed.count { it.status.lowercase() == "timeout" }
        return buildString {
            append(if (statuses.size == 1) "El único proveedor no respondió" else "Ninguno de los ${statuses.size} proveedores respondió")
            if (timedOut == failed.size) {
                /* Todos por tiempo: nombrarlos añade poco y el consejo es el
                 * mismo para todos. */
                append(": todos agotaron el tiempo de espera. Reintenta en un momento.")
                return@buildString
            }
            if (timedOut > 0) append(" ($timedOut por tiempo de espera)")
            append(".")
            val named = failed.take(MAX_NAMED).joinToString(", ") { it.name }
            if (named.isNotBlank()) {
                append(" Fallaron: $named")
                val rest = failed.size - MAX_NAMED
                if (rest > 0) append(" y $rest más")
                append(".")
            }
            append(" Reintenta en un momento.")
        }
    }
}
