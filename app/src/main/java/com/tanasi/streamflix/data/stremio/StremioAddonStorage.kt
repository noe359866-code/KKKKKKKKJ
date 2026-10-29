package com.tanasi.streamflix.data.stremio

import android.content.Context
import androidx.core.content.edit

/** Contrato de persistencia desacoplado de Android para tests JVM. */
interface AddonStorage {
    fun loadAddonsJson(): String?
    fun saveAddonsJson(json: String)
}

class SharedPreferencesAddonStorage(context: Context) : AddonStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadAddonsJson(): String? = prefs.getString(KEY_ADDONS, null)

    override fun saveAddonsJson(json: String) {
        prefs.edit { putString(KEY_ADDONS, json) }
    }

    private companion object {
        const val PREFS_NAME = "streamflix.stremio_addons"
        const val KEY_ADDONS = "installed_addons"
    }
}

class InMemoryAddonStorage(initialJson: String? = null) : AddonStorage {
    private var data: String? = initialJson
    override fun loadAddonsJson(): String? = data
    override fun saveAddonsJson(json: String) {
        data = json
    }
}
