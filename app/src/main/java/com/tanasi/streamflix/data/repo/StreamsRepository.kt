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
 * Pide fuentes al motor elegido y normaliza/filtra URLs reproducibles. El orden
 * de prioridad se conserva para que Media3 pueda cambiar a la fuente siguiente.
 * Sólo el modo remoto necesita una URL de backend.
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
