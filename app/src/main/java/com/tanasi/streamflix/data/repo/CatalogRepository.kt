package com.tanasi.streamflix.data.repo

import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.HomeCatalog
import com.tanasi.streamflix.data.ContentClient
import com.tanasi.streamflix.data.remote.Urls
import com.tanasi.streamflix.util.AddonSettings
import com.tanasi.streamflix.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Resultado de cargar el Home desde el motor local o el servidor opcional. */
sealed interface CatalogResult {
    data class Ready(val catalog: HomeCatalog) : CatalogResult

    /**
     * @param needsSetup `true` cuando el problema se arregla escribiendo bien la
     *   URL del addon (sin configurar, host inalcanzable); la UI enseña el
     *   botón de ajustes además de "Reintentar".
     */
    data class Failure(val message: String, val needsSetup: Boolean = false) : CatalogResult
}

/**
 * Las filas se resuelven en paralelo. En modo autónomo salen de Cinemeta (opt-in),
 * los canales en vivo y los addons; los scrapers sólo resuelven streams. Sólo el
 * modo remoto necesita baseUrl. Una fila caída no tumba las demás.
 */
class CatalogRepository(
    private val client: ContentClient,
    private val settings: AddonSettings,
) {

    @Volatile
    private var cached: Pair<String, HomeCatalog>? = null

    suspend fun load(search: String? = null, onPartial: ((HomeCatalog) -> Unit)? = null): CatalogResult = withContext(Dispatchers.IO) {
        val key = "${settings.autonomous}:${settings.metadataCatalogs}:${settings.baseUrl}:${search.orEmpty()}"
        cached?.takeIf { it.first == key }?.let { return@withContext CatalogResult.Ready(it.second) }

        val baseUrl = settings.baseUrl.trim()
        if (!settings.autonomous && baseUrl.isEmpty()) {
            return@withContext CatalogResult.Failure(
                "Falta configurar la dirección del servidor.",
                needsSetup = true,
            )
        }

        val descriptors = try {
            /* El descubrimiento tiene su propio tope: si el servidor no responde,
             * el Home debe decir algo en segundos, no colgarse el `callTimeout`. */
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { client.catalogs(baseUrl) }.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.w("no se pudieron leer los catálogos del addon: %s", error.message)
            return@withContext CatalogResult.Failure(
                if (settings.autonomous) "No se pudo iniciar el motor local. Vuelve a intentarlo."
                else "No se pudo conectar con ${Urls.hostLabel(baseUrl)}. Revisa que el servidor esté encendido y la dirección sea correcta.",
                needsSetup = !settings.autonomous,
            )
        }

        if (descriptors.isEmpty()) {
            return@withContext CatalogResult.Failure(
                if (settings.autonomous) "El motor local no publicó catálogos. Revisa la instalación del APK."
                else "El servidor respondió pero no publica catálogos. Revisa config/catalogs.json en el backend.",
                needsSetup = false,
            )
        }

        val rows = loadRows(baseUrl, descriptors, search, onPartial)
        if (rows.isEmpty()) {
            return@withContext CatalogResult.Failure(
                if (!search.isNullOrBlank()) "No se encontraron títulos para esa búsqueda."
                else "No se pudo cargar ninguna fila. Revisa la conexión y vuelve a intentarlo.",
                needsSetup = false,
            )
        }

        HomeCatalog(rows).also { cached = key to it }.let(CatalogResult::Ready)
    }

    /** Fuerza una recarga (reintento manual o cambio de servidor). */
    fun invalidate() {
        cached = null
    }

    /** Carga todas las filas a la vez; una que falle simplemente no se pinta. */
    private suspend fun loadRows(baseUrl: String, descriptors: List<CatalogDescriptor>, search: String?, onPartial: ((HomeCatalog) -> Unit)?): List<CatalogRow> =
        coroutineScope {
            val completed = linkedMapOf<String, CatalogRow>()
            // Arranca con los canales empaquetados antes de ocupar el pool con peticiones de Internet.
            val saved = descriptors.firstOrNull { settings.autonomous && it.id == "bundled_live" && it.type == "tv" }
            if (saved != null) {
                fetchRow(baseUrl, saved, search)?.let { row ->
                    completed[saved.rowId] = row
                    onPartial?.invoke(HomeCatalog(listOf(row)))
                }
            }
            descriptors.filterNot { it == saved }
                .map { descriptor ->
                    async(Dispatchers.IO) {
                        val row = withTimeoutOrNull(ROW_TIMEOUT_MS) { fetchRow(baseUrl, descriptor, search) }
                        if (row != null) synchronized(completed) {
                            completed[descriptor.rowId] = row
                            // Publica primero los canales guardados; las filas de Internet aparecen al llegar.
                            onPartial?.invoke(HomeCatalog(descriptors.mapNotNull { completed[it.rowId] }))
                        }
                        descriptor to row
                    }
                }
                .awaitAll()
                .forEach { (descriptor, row) ->
                    if (row == null) AppLog.w("catálogo %s/%s sin datos", descriptor.type, descriptor.id)
                }
            descriptors.mapNotNull { completed[it.rowId] }
        }

    /** Una fila remota, o `null` si el catálogo no devolvió nada útil. */
    private suspend fun fetchRow(baseUrl: String, descriptor: CatalogDescriptor, search: String?): CatalogRow? = try {
        val response = client.catalog(baseUrl, descriptor.type, descriptor.id, search = search)
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
                /* La forma y el dibujo los decide la fuente de datos. */
                aspect = descriptor.posterShape,
                items = items,
                layout = descriptor.layout ?: CatalogRow.LAYOUT_CARDS,
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AppLog.w("catálogo %s/%s falló: %s", descriptor.type, descriptor.id, error.message)
        null
    }

    private companion object {
        /** Tope del descubrimiento de catálogos (ms). */
        const val DISCOVERY_TIMEOUT_MS = 15_000L // Incluye el primer arranque de Python.

        /** Tope por fila (ms), holgado sobre el `CINEMETA_TIMEOUT_MS` del servidor. */
        const val ROW_TIMEOUT_MS = 35_000L // Consultas acotadas, hasta 4 filas locales simultáneas.
    }
}
