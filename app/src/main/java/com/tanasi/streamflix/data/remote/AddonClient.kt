package com.tanasi.streamflix.data.remote

import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.StreamsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Error de red/protocolo con un mensaje que se puede enseñar al usuario. */
class AddonException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Cliente HTTP del addon de Node.
 *
 * Sólo habla JSON: todo el scraping ocurre en el servidor, así que el APK no
 * necesita Jsoup ni Playwright. El cliente pide `/stream/{type}/{id}.json` y
 * recibe el array ya ordenado por prioridad.
 */
class AddonClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson(),
) {

    /**
     * Fuentes de un título, en el orden que devolvió el servidor (prioridad).
     *
     * @throws AddonException si el servidor no responde o devuelve algo inválido.
     */
    suspend fun streams(baseUrl: String, type: String, id: String): StreamsResponse {
        val url = Urls.stream(baseUrl, type, id)
        val body = get(url)
        return try {
            json.decodeFromString(StreamsResponse.serializer(), body)
        } catch (error: Exception) {
            throw AddonException("Respuesta inválida del addon para $type/$id", error)
        }
    }

    /** Catálogo remoto opcional (`/catalog/{type}/{id}.json`), si el addon lo expone. */
    suspend fun catalog(baseUrl: String, type: String, id: String): RemoteCatalogResponse? {
        val url = Urls.catalog(baseUrl, type, id)
        return try {
            json.decodeFromString(RemoteCatalogResponse.serializer(), get(url))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            /* El addon actual no declara catálogos: no es un error, se ignora. */
            null
        }
    }

    /** Comprobación de conectividad que usa el diálogo de ajustes. */
    suspend fun ping(baseUrl: String): Boolean = try {
        get(Urls.health(baseUrl))
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AddonException(
                        "El addon respondió ${response.code} en ${request.url.encodedPath}",
                    )
                }
                if (body.isBlank()) throw AddonException("Respuesta vacía del addon")
                body
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AddonException) {
            throw error
        } catch (error: IOException) {
            throw AddonException("No se pudo conectar con ${request.url.host}", error)
        }
    }

    companion object {
        /**
         * Timeouts distintos a los del servidor: el backend ya corta a los 8 s
         * por proveedor, así que aquí se deja margen para el failover de origen
         * (varios providers en paralelo) sin que el usuario vea un cuelgue.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** El backend puede añadir campos nuevos: no deben romper el cliente. */
        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}
