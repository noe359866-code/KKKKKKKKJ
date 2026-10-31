package com.tanasi.streamflix.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tanasi.streamflix.data.model.SearchHistory
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Historial de búsquedas del dispositivo.
 *
 * No va por perfil a propósito: en una tele el que escribe con el mando es el
 * dueño del mando, y las búsquedas ("canales", "fútbol", "la de ayer") no son
 * un dato privado como el progreso de cada uno. Guardar una sola lista también
 * evita duplicar el estado al cambiar de perfil con el buscador abierto.
 *
 * Se guarda como JSON con `kotlinx.serialization` (no `org.json`): así la misma
 * lógica se prueba en JVM, igual que la cola del reproductor.
 */
class SearchHistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())
    private val lock = Any()

    /** Lo más reciente primero. Nunca lanza: un JSON roto devuelve lista vacía. */
    fun recent(): List<String> = synchronized(lock) { read() }

    /** Anota una búsqueda y devuelve la lista ya actualizada (para pintarla). */
    fun remember(query: String): List<String> = synchronized(lock) {
        val next = SearchHistory.add(read(), query)
        write(next)
        next
    }

    /** Quita una consulta del historial. */
    fun forget(query: String): List<String> = synchronized(lock) {
        val next = SearchHistory.forget(read(), query)
        write(next)
        next
    }

    fun clear(): List<String> = synchronized(lock) {
        write(emptyList())
        emptyList()
    }

    // ------------------------------------------------------------------ //

    private fun read(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val stored = try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            return emptyList()
        }
        return SearchHistory.sanitize(stored)
    }

    private fun write(entries: List<String>) {
        prefs.edit { putString(KEY, json.encodeToString(serializer, SearchHistory.sanitize(entries))) }
    }

    private companion object {
        const val PREFS = "streamflix.search"
        const val KEY = "recent"
    }
}
