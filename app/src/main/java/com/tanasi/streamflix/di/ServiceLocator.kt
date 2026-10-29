package com.tanasi.streamflix.di

import android.content.Context
import com.tanasi.streamflix.data.remote.AddonClient
import com.tanasi.streamflix.data.repo.CatalogRepository
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.util.Settings
import kotlinx.serialization.json.Json

/**
 * Inyección de dependencias manual.
 *
 * El árbol de objetos es diminuto (un cliente HTTP, dos repositorios, un
 * `SharedPreferences`), así que Hilt/Koin sólo añadirían un procesador de
 * anotaciones y tiempo de build. Es un `object` con `@Volatile` y `synchronized`
 * para que dos actividades que arrancan a la vez no creen dos `OkHttpClient`
 * (cada uno con su pool de conexiones y su caché de DNS).
 */
object ServiceLocator {

    @Volatile
    private var settingsInstance: Settings? = null

    @Volatile
    private var clientInstance: AddonClient? = null

    @Volatile
    private var streamsInstance: StreamsRepository? = null

    @Volatile
    private var catalogInstance: CatalogRepository? = null

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun settings(context: Context): Settings =
        settingsInstance ?: synchronized(this) {
            settingsInstance ?: Settings(context.applicationContext).also { settingsInstance = it }
        }

    fun addonClient(): AddonClient =
        clientInstance ?: synchronized(this) {
            clientInstance ?: AddonClient(AddonClient.defaultHttpClient(), json).also { clientInstance = it }
        }

    fun streamsRepository(context: Context): StreamsRepository =
        streamsInstance ?: synchronized(this) {
            streamsInstance ?: StreamsRepository(addonClient(), settings(context))
                .also { streamsInstance = it }
        }

    /**
     * El repositorio del Home ya no lee assets locales: sólo necesita el cliente
     * HTTP y la URL del servidor. Se mantiene el `context` en la firma para no
     * romper a quien lo llame desde una `Activity`.
     */
    fun catalogRepository(context: Context): CatalogRepository =
        catalogInstance ?: synchronized(this) {
            catalogInstance ?: CatalogRepository(addonClient(), settings(context))
                .also { catalogInstance = it }
        }

    /** Sólo para tests de instrumentación: deja el contenedor como recién creado. */
    fun reset() {
        synchronized(this) {
            streamsInstance = null
            catalogInstance = null
            clientInstance = null
            settingsInstance = null
        }
    }
}
