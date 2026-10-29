package com.tanasi.streamflix.data.embedded

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.tanasi.streamflix.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ejecuta los scrapers Python dentro de la APK (Chaquopy).
 *
 * No usa Node ni procesos externos: el módulo `streamflix_bridge` importa el
 * scraper pedido y llama a `scrape(payload)`. El semáforo protege televisiones
 * con poca RAM de lanzar demasiados scrapers a la vez.
 */
class PythonScraperBridge(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
    maxConcurrency: Int = 4,
) {
    private val semaphore = Semaphore(maxConcurrency)

    init {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }

    suspend fun run(provider: ProviderConfig, payload: JsonObject): ScraperResult =
        semaphore.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val module = Python.getInstance().getModule("streamflix_bridge")
                    val response = module.callAttr("run", provider.script, payload.toString()).toString()
                    val obj = json.parseToJsonElement(response) as? JsonObject
                        ?: buildJsonObject { put("ok", false) }
                    EmbeddedStreamAggregator.parseBridgeResult(provider, obj)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLog.w("scraper %s falló: %s", provider.id, error.message)
                    ScraperResult(provider, ok = false, streams = emptyList())
                }
            }
        }
}
