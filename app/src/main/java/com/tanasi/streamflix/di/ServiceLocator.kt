package com.tanasi.streamflix.di

import android.content.Context
import com.tanasi.streamflix.data.embedded.EmbeddedContentClient
import com.tanasi.streamflix.data.remote.AddonClient
import com.tanasi.streamflix.data.remote.ContentClient
import com.tanasi.streamflix.data.remote.RoutingContentClient
import com.tanasi.streamflix.data.repo.CatalogRepository
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.util.Settings
import kotlinx.serialization.json.Json

/**
 * Inyección de dependencias manual.
 *
 * La APK es autónoma por defecto: el cliente de contenido enruta a los scrapers
 * Python embebidos. El addon Node externo sigue disponible como opción avanzada
 * y comparte el mismo contrato [ContentClient].
 */
object ServiceLocator {

    @Volatile
    private var settingsInstance: Settings? = null

    @Volatile
    private var remoteClientInstance: AddonClient? = null

    @Volatile
    private var contentClientInstance: ContentClient? = null

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
        remoteClientInstance ?: synchronized(this) {
            remoteClientInstance ?: AddonClient(AddonClient.defaultHttpClient(), json).also { remoteClientInstance = it }
        }

    fun contentClient(context: Context): ContentClient =
        contentClientInstance ?: synchronized(this) {
            contentClientInstance ?: RoutingContentClient(
                settings = settings(context),
                remote = addonClient(),
                embedded = lazy { EmbeddedContentClient(context.applicationContext, json) },
            ).also { contentClientInstance = it }
        }

    fun streamsRepository(context: Context): StreamsRepository =
        streamsInstance ?: synchronized(this) {
            streamsInstance ?: StreamsRepository(contentClient(context), settings(context))
                .also { streamsInstance = it }
        }

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
            contentClientInstance = null
            remoteClientInstance = null
            settingsInstance = null
        }
    }
}
