package com.tanasi.streamflix.data.remote

import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.CatalogsResponse
import com.tanasi.streamflix.data.model.MetaResponse
import com.tanasi.streamflix.data.model.RemoteCatalogResponse
import com.tanasi.streamflix.data.model.RemoteMeta
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
 * Sólo habla JSON: todo el scraping y toda la agregación de metadatos ocurre en
 * el servidor, así que el APK no necesita Jsoup, Playwright ni una clave de
 * TMDB. El cliente pide:
 *
 *  - `/catalogs.json` → qué filas publica el addon (extensión propia, pensada
 *    para esta app: es más pequeña y estable que el manifest completo);
 *  - `/catalog/{type}/{id}.json` → las metas de una fila, ya normalizadas;
 *  - `/meta/{type}/{id}.json` → ficha completa, con episodios si es serie;
 *  - `/stream/{type}/{id}.json` → fuentes ya ordenadas por prioridad.
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

    /**
     * Catálogos que publica el addon.
     *
     * Se pregunta primero por `/catalogs.json`; si el servidor es una versión
     * anterior que no lo expone, se cae al `manifest.json` (donde el bloque
     * `catalogs` es el estándar de Stremio). Devuelve una lista vacía —no una
     * excepción— cuando el addon no publica ninguno: la app tiene semilla local
     * para ese caso.
     */
    suspend fun catalogs(baseUrl: String): List<CatalogDescriptor> {
        val fromFeed = runCatching {
            json.decodeFromString(CatalogsResponse.serializer(), get(Urls.catalogs(baseUrl))).catalogs
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        }

        if (!fromFeed.isNullOrEmpty()) return fromFeed

        return runCatching {
            json.decodeFromString(CatalogsResponse.serializer(), get(Urls.manifest(baseUrl))).catalogs
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptyList()
        }
    }

    /**
     * Metas de un catálogo.
     *
     * @return `null` si la fila falla (404, red, JSON inválido): el repositorio
     *   simplemente no la pinta, en lugar de romper el Home entero.
     */
    suspend fun catalog(
        baseUrl: String,
        type: String,
        id: String,
        search: String? = null,
        genre: String? = null,
        skip: Int? = null,
    ): RemoteCatalogResponse? = try {
        val body = get(Urls.catalog(baseUrl, type, id, search = search, genre = genre, skip = skip))
        json.decodeFromString(RemoteCatalogResponse.serializer(), body)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /**
     * Ficha de un título. `null` cuando el servidor no la tiene (404), que es un
     * caso normal: un canal local puede no existir ya, o Cinemeta puede no
     * conocer un id.
     */
    suspend fun meta(baseUrl: String, type: String, id: String): RemoteMeta? = try {
        val body = get(Urls.meta(baseUrl, type, id))
        json.decodeFromString(MetaResponse.serializer(), body).meta
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
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
