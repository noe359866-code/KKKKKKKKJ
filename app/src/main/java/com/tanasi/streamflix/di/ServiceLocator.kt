package com.tanasi.streamflix.di

import android.content.Context
import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.RoutingContentClient
import com.tanasi.streamflix.data.local.ChaquopyRuntime
import com.tanasi.streamflix.data.local.EmbeddedContentClient
import com.tanasi.streamflix.data.remote.AddonClient
import com.tanasi.streamflix.data.repo.CatalogRepository
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.data.stremio.SharedPreferencesAddonStorage
import com.tanasi.streamflix.data.stremio.StremioAddonClient
import com.tanasi.streamflix.data.stremio.StremioAddonManager
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
    private var stremioClientInstance: StremioAddonClient? = null

    @Volatile
    private var addonManagerInstance: StremioAddonManager? = null

    @Volatile
    private var contentInstance: ContentClient? = null

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

    fun stremioAddonClient(): StremioAddonClient =
        stremioClientInstance ?: synchronized(this) {
            stremioClientInstance ?: StremioAddonClient(AddonClient.defaultHttpClient(), json)
                .also { stremioClientInstance = it }
        }

    fun stremioAddonManager(context: Context): StremioAddonManager =
        addonManagerInstance ?: synchronized(this) {
            addonManagerInstance ?: StremioAddonManager(
                storage = SharedPreferencesAddonStorage(context),
                client = stremioAddonClient(),
                json = json,
            ).also { addonManagerInstance = it }
        }

    fun contentClient(context: Context): ContentClient =
        contentInstance ?: synchronized(this) {
            contentInstance ?: RoutingContentClient(
                settings = settings(context),
                embedded = EmbeddedContentClient(ChaquopyRuntime(context)) { settings(context).metadataCatalogs },
                remote = addonClient(),
                addonManager = stremioAddonManager(context),
                stremioClient = stremioAddonClient(),
            ).also { contentInstance = it }
        }

    fun streamsRepository(context: Context): StreamsRepository =
        streamsInstance ?: synchronized(this) {
            streamsInstance ?: StreamsRepository(contentClient(context), settings(context))
                .also { streamsInstance = it }
        }

    /** El mismo repositorio trabaja con ambos motores y los addons de Stremio. */
    fun catalogRepository(context: Context): CatalogRepository =
        catalogInstance ?: synchronized(this) {
            catalogInstance ?: CatalogRepository(contentClient(context), settings(context))
                .also { catalogInstance = it }
        }

    /** Sólo para tests de instrumentación: deja el contenedor como recién creado. */
    fun reset() {
        synchronized(this) {
            streamsInstance = null
            catalogInstance = null
            clientInstance = null
            contentInstance = null
            settingsInstance = null
            addonManagerInstance = null
            stremioClientInstance = null
        }
    }
}
