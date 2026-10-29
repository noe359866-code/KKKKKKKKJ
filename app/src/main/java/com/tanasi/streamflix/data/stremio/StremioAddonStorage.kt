package com.tanasi.streamflix.data.stremio

import android.content.Context
import androidx.core.content.edit

/** Contrato de persistencia desacoplado de Android para tests JVM. */
interface AddonStorage {
    fun loadAddonsJson(): String?
    fun saveAddonsJson(json: String)

    /**
     * Marca de "ya se instalaron los addons recomendados".
     *
     * Sin ella, borrar a propósito el addon sugerido haría que volviese a
     * aparecer en el siguiente arranque. Por defecto no hay persistencia: los
     * dobles de prueba empiezan siempre sin sembrar.
     */
    fun isSeeded(): Boolean = false
    fun markSeeded() = Unit
}

class SharedPreferencesAddonStorage(context: Context) : AddonStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadAddonsJson(): String? = prefs.getString(KEY_ADDONS, null)

    override fun saveAddonsJson(json: String) {
        prefs.edit { putString(KEY_ADDONS, json) }
    }

    override fun isSeeded(): Boolean = prefs.getBoolean(KEY_SEEDED, false)

    override fun markSeeded() {
        prefs.edit { putBoolean(KEY_SEEDED, true) }
    }

    private companion object {
        const val PREFS_NAME = "streamflix.stremio_addons"
        const val KEY_ADDONS = "installed_addons"
        const val KEY_SEEDED = "seeded_defaults"
    }
}

class InMemoryAddonStorage(initialJson: String? = null) : AddonStorage {
    private var data: String? = initialJson
    private var seeded = false
    override fun loadAddonsJson(): String? = data
    override fun saveAddonsJson(json: String) {
        data = json
    }

    override fun isSeeded(): Boolean = seeded
    override fun markSeeded() {
        seeded = true
    }
}
