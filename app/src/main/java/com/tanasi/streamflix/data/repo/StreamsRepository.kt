package com.tanasi.streamflix.data.repo

import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.data.model.StreamsResponse
import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.remote.AddonException
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.CancellationException

/** Resultado de pedir fuentes: siempre una lista, nunca una excepción suelta. */
sealed interface StreamsResult {
    data class Ready(val sources: List<StreamSource>, val cacheMaxAgeSeconds: Long? = null) : StreamsResult
    data object Empty : StreamsResult
    data class Failure(val message: String) : StreamsResult
}

/**
 * Puente entre la UI y `/stream/:type/:id.json`.
 *
 * Responsabilidad doble:
 *  1. pedir el JSON al addon,
 *  2. **normalizar y filtrar** el array: sólo URLs realmente reproducibles, sin
 *     duplicados y conservando el orden de prioridad que decidió el servidor.
 *
 * El orden no se toca: el servidor ya ordenó por prioridad de proveedor y el
 * failover del reproductor depende exactamente de ese orden.
 */
class StreamsRepository(
    private val client: ContentClient,
    private val settings: AddonSettings,
) {

    suspend fun streamsFor(type: String, id: String): StreamsResult {
        val baseUrl = settings.baseUrl
        if (!settings.autonomous && baseUrl.isBlank()) {
            return StreamsResult.Failure("Configura la URL del servidor en Ajustes")
        }

        return try {
            val response = client.streams(baseUrl, type, id)
            val sources = normalize(response)
            if (sources.isEmpty()) StreamsResult.Empty
            else StreamsResult.Ready(sources, response.cacheMaxAge)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AddonException) {
            StreamsResult.Failure(error.message ?: "Error de red")
        } catch (error: Exception) {
            StreamsResult.Failure(error.message ?: "Error inesperado")
        }
    }

    companion object {
        /**
         * Descarta duplicados (misma URL) y entradas no reproducibles, y numera
         * el resultado. Si el servidor repite una URL en dos proveedores distintos
         * —cosa que puede pasar con el mismo CDN— se conserva la primera, que es
         * la de mayor prioridad.
         */
        fun normalize(response: StreamsResponse): List<StreamSource> {
            val seen = HashSet<String>(response.streams.size)
            val sources = ArrayList<StreamSource>(response.streams.size)
            response.streams.forEachIndexed { index, dto ->
                val source = dto.toSource(index) ?: return@forEachIndexed
                val key = source.url.substringBefore('#')
                if (!seen.add(key)) return@forEachIndexed
                sources += source.copy(index = sources.size)
            }
            return sources
        }
    }
}
