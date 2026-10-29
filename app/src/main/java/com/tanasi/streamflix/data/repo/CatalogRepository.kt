package com.tanasi.streamflix.data.repo

import android.content.Context
import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.CatalogDocument
import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.HomeCatalog
import com.tanasi.streamflix.data.remote.AddonClient
import com.tanasi.streamflix.util.AddonSettings
import com.tanasi.streamflix.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * De dónde salen las filas del Home.
 *
 * **El servidor manda.** Al abrir la app se pregunta `GET /catalogs.json` y cada
 * fila se alimenta de `GET /catalog/{type}/{id}.json` (protocolo Stremio). Eso
 * significa que la cartelera —incluida la TV en vivo y los deportes— se cambia
 * editando `server/config/catalogs.json`, sin recompilar ni reinstalar el APK.
 *
 * Las filas se piden **en paralelo** y cada una falla por su cuenta: si Cinemeta
 * se cae, las filas de películas desaparecen pero la de TV en vivo (que sale de
 * un JSON local del servidor) se sigue viendo. El Home no se queda en blanco
 * mientras haya algo que enseñar.
 *
 * Respaldo (`assets/catalog/seed.json`), usado sólo cuando el servidor no
 * responde o no publica catálogos:
 *
 *  - las filas de **"Continuar viendo"**, que son progreso local del dispositivo
 *    y por definición no viajan al servidor;
 *  - la cartelera curada completa, para que un primer arranque sin backend
 *    (o con la IP del portátil cambiada) no muestre una pantalla vacía.
 *
 * `AddonSettings.remoteCatalogs` sigue existiendo como escape: apagado, el Home
 * se queda con la semilla curada aunque el servidor publique filas.
 */
class CatalogRepository(
    private val context: Context,
    private val client: AddonClient,
    private val settings: AddonSettings,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    @Volatile
    private var cached: HomeCatalog? = null

    suspend fun load(): HomeCatalog = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }

        val seed = readSeed().filter { it.items.isNotEmpty() }
        val rows = if (settings.remoteCatalogs) {
            val remote = loadRemoteRows()
            if (remote.isEmpty()) seed else mergeLocalRows(seed, remote)
        } else {
            seed
        }

        HomeCatalog(rows).also { cached = it }
    }

    /** Fuerza una recarga (reintento tras cambiar de servidor). */
    fun invalidate() {
        cached = null
    }

    private fun readSeed(): List<CatalogRow> = try {
        val raw = context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
        json.decodeFromString(CatalogDocument.serializer(), raw).rows
    } catch (error: Exception) {
        AppLog.e("catálogo semilla ilegible", error)
        emptyList()
    }

    /**
     * Pide al addon sus catálogos y los carga todos a la vez.
     *
     * Nunca lanza: un fallo de red devuelve lista vacía y el Home cae a la
     * semilla.
     */
    private suspend fun loadRemoteRows(): List<CatalogRow> {
        val baseUrl = settings.baseUrl
        if (baseUrl.isBlank()) return emptyList()

        val descriptors = try {
            /* Descubrimiento con tope propio: si el servidor no responde, el
             * Home debe caer a la semilla en segundos, no colgarse los 20 s del
             * `callTimeout` de OkHttp. */
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { client.catalogs(baseUrl) }.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.w("no se pudieron leer los catálogos del addon: %s", error.message)
            return emptyList()
        }

        if (descriptors.isEmpty()) {
            AppLog.i("el addon no publica catálogos, se usa la semilla local")
            return emptyList()
        }

        return coroutineScope {
            descriptors
                .map { descriptor ->
                    async(Dispatchers.IO) {
                        /* Una fila lenta no puede retrasar el resto del Home:
                         * la que no llegue a tiempo simplemente no se pinta. */
                        descriptor to withTimeoutOrNull(ROW_TIMEOUT_MS) { fetchRow(descriptor) }
                    }
                }
                .awaitAll()
                .mapNotNull { (descriptor, row) ->
                    if (row == null) {
                        AppLog.w("catálogo %s/%s sin datos", descriptor.type, descriptor.id)
                    }
                    row
                }
        }
    }

    /** Una fila remota, o `null` si el catálogo no devolvió nada útil. */
    private suspend fun fetchRow(descriptor: CatalogDescriptor): CatalogRow? = try {
        val response = client.catalog(settings.baseUrl, descriptor.type, descriptor.id)
        val items = response?.metas.orEmpty()
            .map { meta -> meta.toEntry(descriptor) }
            .distinctBy { entry -> entry.id }

        if (items.isEmpty()) {
            if (response?.degraded == true) {
                AppLog.w("catálogo %s/%s degradado (upstream caído)", descriptor.type, descriptor.id)
            }
            null
        } else {
            CatalogRow(
                id = descriptor.rowId,
                title = descriptor.name,
                /* La forma la decide el SERVIDOR: 2:3 para películas/series,
                 * 16:9 para TV en vivo y deportes. */
                aspect = descriptor.posterShape,
                items = items,
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AppLog.w("catálogo %s/%s falló: %s", descriptor.type, descriptor.id, error.message)
        null
    }

    /**
     * Filas locales que sobreviven a la cartelera remota + filas remotas.
     *
     * Sólo las filas marcadas como locales (hoy: "Continuar viendo") se
     * conservan; el resto (películas, series, anime, deportes) lo sirve el
     * addon. Si una fila remota sale vacía, el hueco no se rellena con la
     * semilla: mezclar "populares de Cinemeta" con "populares curadas" en la
     * misma fila daría duplicados y etiquetas que mienten.
     */
    private fun mergeLocalRows(seed: List<CatalogRow>, remote: List<CatalogRow>): List<CatalogRow> {
        val local = seed.filter { row -> row.id in LOCAL_ROW_IDS }
        return local + remote
    }

    private companion object {
        const val SEED_ASSET = "catalog/seed.json"

        /** Filas que sólo tienen sentido en el dispositivo. */
        val LOCAL_ROW_IDS = setOf("continue")

        /** Tope del descubrimiento de catálogos (ms). */
        const val DISCOVERY_TIMEOUT_MS = 4_000L

        /** Tope por fila (ms), holgado sobre el `CINEMETA_TIMEOUT_MS` del servidor. */
        const val ROW_TIMEOUT_MS = 12_000L
    }
}
