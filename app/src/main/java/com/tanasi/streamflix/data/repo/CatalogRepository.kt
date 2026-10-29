package com.tanasi.streamflix.data.repo

import com.tanasi.streamflix.data.model.CatalogDescriptor
import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.HomeCatalog
import com.tanasi.streamflix.data.remote.AddonClient
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

/**
 * Resultado de cargar el Home.
 *
 * No hay datos locales de respaldo: la cartelera **sólo** la publica el servidor
 * del addon. Por eso el fallo es un estado explícito y no una lista vacía: la UI
 * necesita distinguir "falta configurar el servidor" (abrir ajustes) de "el
 * servidor no responde" (reintentar) de "el servidor no publica filas".
 */
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
 * De dónde salen las filas del Home: **del servidor**, siempre.
 *
 * Al abrir la app se pregunta `GET /catalogs.json` y cada fila se alimenta de
 * `GET /catalog/{type}/{id}.json` (protocolo Stremio). La cartelera —incluida la
 * TV en vivo, los deportes y las filas de Netflix/Disney+— se cambia editando
 * `server/config/catalogs.json`, sin recompilar ni reinstalar el APK.
 *
 * Las filas se piden **en paralelo** y cada una falla por su cuenta: si Cinemeta
 * se cae, las filas de películas desaparecen pero la de TV en vivo (que sale de
 * un JSON local del servidor) se sigue viendo. Una fila lenta no retrasa al
 * resto: tiene su propio tope.
 *
 * El APK no lleva catálogo embebido. Un build de producción sin servidor
 * configurado muestra el diálogo de ajustes en el primer arranque; no una lista
 * de prueba que el usuario confunda con contenido real.
 */
class CatalogRepository(
    private val client: AddonClient,
    private val settings: AddonSettings,
) {

    @Volatile
    private var cached: HomeCatalog? = null

    suspend fun load(): CatalogResult = withContext(Dispatchers.IO) {
        cached?.let { return@withContext CatalogResult.Ready(it) }

        val baseUrl = settings.baseUrl.trim()
        if (baseUrl.isEmpty()) {
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
                "No se pudo conectar con ${Urls.hostLabel(baseUrl)}. Revisa que el servidor esté encendido y la dirección sea correcta.",
                needsSetup = true,
            )
        }

        if (descriptors.isEmpty()) {
            return@withContext CatalogResult.Failure(
                "El servidor respondió pero no publica catálogos. Revisa config/catalogs.json en el backend.",
                needsSetup = false,
            )
        }

        val rows = loadRows(baseUrl, descriptors)
        if (rows.isEmpty()) {
            return@withContext CatalogResult.Failure(
                "Ninguna fila del catálogo respondió. Se reintentará al volver a esta pantalla.",
                needsSetup = false,
            )
        }

        HomeCatalog(rows).also { cached = it }.let(CatalogResult::Ready)
    }

    /** Fuerza una recarga (reintento manual o cambio de servidor). */
    fun invalidate() {
        cached = null
    }

    /** Carga todas las filas a la vez; una que falle simplemente no se pinta. */
    private suspend fun loadRows(baseUrl: String, descriptors: List<CatalogDescriptor>): List<CatalogRow> =
        coroutineScope {
            descriptors
                .map { descriptor ->
                    async(Dispatchers.IO) {
                        descriptor to withTimeoutOrNull(ROW_TIMEOUT_MS) { fetchRow(baseUrl, descriptor) }
                    }
                }
                .awaitAll()
                .mapNotNull { (descriptor, row) ->
                    if (row == null) AppLog.w("catálogo %s/%s sin datos", descriptor.type, descriptor.id)
                    row
                }
        }

    /** Una fila remota, o `null` si el catálogo no devolvió nada útil. */
    private suspend fun fetchRow(baseUrl: String, descriptor: CatalogDescriptor): CatalogRow? = try {
        val response = client.catalog(baseUrl, descriptor.type, descriptor.id)
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

    private companion object {
        /** Tope del descubrimiento de catálogos (ms). */
        const val DISCOVERY_TIMEOUT_MS = 4_000L

        /** Tope por fila (ms), holgado sobre el `CINEMETA_TIMEOUT_MS` del servidor. */
        const val ROW_TIMEOUT_MS = 12_000L
    }
}
