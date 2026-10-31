package com.tanasi.streamflix.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.WatchProgress
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * "Seguir viendo" **por perfil**.
 *
 * Cada perfil tiene su propia lista (clave `progress.<id>`), así que el niño no
 * ve los capítulos del adulto ni al revés. Se guarda lo mínimo para pintar la
 * tarjeta (id, título, arte, posición) y se ordena por `updatedAt`, con tope de
 * [MAX_ENTRIES]: el Home no debe crecer sin fin.
 *
 * La decisión de qué entra y qué no está en [WatchProgress.isTrackable] — por
 * debajo de 15 s no hay nada que continuar y a falta de 90 s ya está vista.
 */
class WatchProgressStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(WatchProgress.serializer())
    private val lock = Any()

    /** Lo guardado, de lo más reciente a lo más antiguo. */
    fun entries(profileId: String): List<WatchProgress> = synchronized(lock) { read(profileId) }

    /** Tarjetas listas para una fila de 16:9 del Home. */
    fun entriesFor(profileId: String): List<MediaEntry> =
        entries(profileId).map { it.toEntry() }

    /**
     * Guarda (o actualiza) una posición. Si el título ya no se puede continuar
     * —terminado o apenas empezado— se borra en vez de dejarlo a medias.
     */
    fun save(profileId: String, progress: WatchProgress) = synchronized(lock) {
        val current = read(profileId).filterNot { it.id == progress.id }
        val updated = if (progress.isTrackable()) {
            (listOf(progress) + current)
                .sortedByDescending { it.updatedAt }
                .take(MAX_ENTRIES)
        } else {
            current
        }
        write(profileId, updated)
    }

    fun remove(profileId: String, contentId: String) = synchronized(lock) {
        write(profileId, read(profileId).filterNot { it.id == contentId })
    }

    /**
     * Quita un título **y sus episodios** ("Dejar de seguir viendo").
     *
     * `remove` borra una entrada exacta; desde la ficha de una serie el usuario
     * espera que desaparezca la serie entera, no sólo el capítulo que dejó a
     * medias.
     */
    fun removeTitle(profileId: String, contentId: String) = synchronized(lock) {
        write(profileId, read(profileId).filterNot { it.belongsTo(contentId) })
    }

    fun clear(profileId: String) = synchronized(lock) { write(profileId, emptyList()) }

    // ------------------------------------------------------------------ //

    private fun read(profileId: String): List<WatchProgress> {
        val raw = prefs.getString(key(profileId), null) ?: return emptyList()
        return try {
            json.decodeFromString(listSerializer, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(profileId: String, entries: List<WatchProgress>) {
        prefs.edit {
            if (entries.isEmpty()) remove(key(profileId))
            else putString(key(profileId), json.encodeToString(listSerializer, entries))
        }
    }

    private fun key(profileId: String) = "$KEY_PREFIX$profileId"

    private companion object {
        const val PREFS = "streamflix.profiles"
        const val KEY_PREFIX = "progress."
        const val MAX_ENTRIES = 20
    }
}
