package com.tanasi.streamflix.data.repo

import android.content.Context
import com.tanasi.streamflix.data.model.CatalogDocument
import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.remote.AddonClient
import com.tanasi.streamflix.util.AddonSettings
import com.tanasi.streamflix.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * De dónde salen las filas del Home.
 *
 * El addon de Node **no publica catálogos** (`manifest.catalogs = []`): sólo
 * resuelve `/stream/:type/:id.json`. Así que el Home se alimenta de:
 *
 *  1. `assets/catalog/seed.json` — filas de arranque con ids reales de los
 *     proveedores, para que la app sea navegable desde el primer build;
 *  2. `/catalog/{type}/{id}.json` — el protocolo estándar de Stremio, que se
 *     consulta **sólo si** el addon empieza a declararlos y el usuario activa
 *     `Settings.remoteCatalogs` (por defecto desactivado, para no lanzar cuatro
 *     peticiones que hoy devolverían 404).
 *
 * Cambiar el catálogo de arranque es editar el JSON de `assets`: no hay
 * contenido incrustado en Kotlin.
 */
class CatalogRepository(
    private val context: Context,
    private val client: AddonClient,
    private val settings: AddonSettings,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    @Volatile
    private var cached: List<CatalogRow>? = null

    suspend fun load(): List<CatalogRow> = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }

        val seed = readSeed()
        val rows = if (settings.remoteCatalogs) seed.map { row -> mergeRemote(row) } else seed
        cached = rows
        rows
    }

    /** Fuerza una recarga (pull-to-refresh / reintento tras cambiar de servidor). */
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
     * Sustituye los items de una fila por los del addon **si** responde. Si no,
     * se queda la semilla: un Home vacío por un catálogo remoto que aún no
     * existe sería peor que mostrar datos de ejemplo.
     */
    private suspend fun mergeRemote(row: CatalogRow): CatalogRow {
        val type = row.items.firstOrNull()?.type ?: return row
        return try {
            val remote = client.catalog(settings.baseUrl, type, row.id) ?: return row
            if (remote.metas.isEmpty()) {
                row
            } else {
                row.copy(items = remote.metas.map { it.toEntry() })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.w("catálogo remoto %s no disponible: %s", row.id, error.message)
            row
        }
    }

    private companion object {
        const val SEED_ASSET = "catalog/seed.json"
    }
}
