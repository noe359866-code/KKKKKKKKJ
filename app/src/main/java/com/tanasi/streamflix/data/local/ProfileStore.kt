package com.tanasi.streamflix.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tanasi.streamflix.data.model.MAX_PROFILES
import com.tanasi.streamflix.data.model.Profile
import com.tanasi.streamflix.data.model.defaultProfileName
import com.tanasi.streamflix.data.model.nextColorIndex
import com.tanasi.streamflix.data.model.profileName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Perfiles guardados en el dispositivo (`SharedPreferences`, JSON).
 *
 * Sólo se serializa una lista diminuta, así que no hay base de datos ni
 * migraciones: si el JSON estuviera corrupto se descarta y se vuelve a empezar
 * con un perfil por defecto, en vez de dejar la app sin arrancar.
 *
 * Se guarda también el último perfil usado: al volver a encender la televisión
 * se entra directo a esa persona y el selector aparece sólo en **Cambiar de
 * perfil** o si se borra ese perfil.
 */
class ProfileStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(Profile.serializer())
    private val lock = Any()

    /** Todos los perfiles, en el orden en que se crearon. */
    fun profiles(): List<Profile> = synchronized(lock) { read() }

    /** Último perfil activo, si sigue existiendo. */
    fun activeProfile(): Profile? = synchronized(lock) {
        val id = prefs.getString(KEY_ACTIVE, null)
        if (id == null) null else read().firstOrNull { it.id == id }
    }

    /**
     * Garantiza que siempre hay al menos un perfil: una tele recién instalada
     * entra con "Espectador" sin obligar a nadie a escribir con el mando.
     */
    fun ensureDefault(): List<Profile> = synchronized(lock) {
        val current = read()
        if (current.isNotEmpty()) {
            current
        } else {
            val created = listOf(
                Profile(id = newId(), name = defaultProfileName(emptyList()), colorIndex = 0),
            )
            write(created)
            created
        }
    }

    fun select(id: String) {
        prefs.edit { putString(KEY_ACTIVE, id) }
    }

    fun clearActive() {
        prefs.edit { remove(KEY_ACTIVE) }
    }

    /** Crea un perfil (máximo [MAX_PROFILES]); devuelve `null` si ya no cabe. */
    fun create(name: String): Profile? = synchronized(lock) {
        val current = read()
        if (current.size >= MAX_PROFILES) {
            null
        } else {
            val profile = Profile(
                id = newId(),
                name = profileName(name),
                colorIndex = nextColorIndex(current),
            )
            write(current + profile)
            profile
        }
    }

    fun rename(id: String, name: String) = synchronized(lock) {
        write(read().map { if (it.id == id) it.copy(name = profileName(name)) else it })
    }

    fun delete(id: String) = synchronized(lock) {
        write(read().filterNot { it.id == id })
        if (prefs.getString(KEY_ACTIVE, null) == id) prefs.edit { remove(KEY_ACTIVE) }
    }

    // ------------------------------------------------------------------ //

    private fun read(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            json.decodeFromString(listSerializer, raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(profiles: List<Profile>) {
        prefs.edit { putString(KEY_PROFILES, json.encodeToString(listSerializer, profiles)) }
    }

    private fun newId(): String = UUID.randomUUID().toString().take(8)

    private companion object {
        const val PREFS = "streamflix.profiles"
        const val KEY_PROFILES = "profiles.json"
        const val KEY_ACTIVE = "profiles.active"
    }
}
