package com.tanasi.streamflix.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tanasi.streamflix.BuildConfig

/** Interfaz sin Android para probar ambos modos de los repositorios. */
interface AddonSettings {
    var baseUrl: String
    /** Los clientes remotos existentes conservan su contrato al implementar sólo baseUrl. */
    val autonomous: Boolean get() = false
    val metadataCatalogs: Boolean get() = false
}

/** Una instalación nueva (también una actualización) arranca en modo autónomo. */
class Settings(context: Context) : AddonSettings {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var autonomous: Boolean
        get() = prefs.getBoolean(KEY_AUTONOMOUS, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTONOMOUS, value) }

    /** Cinemeta es un enriquecimiento optativo; los catálogos de proveedores no lo requieren. */
    override var metadataCatalogs: Boolean
        get() = prefs.getBoolean(KEY_METADATA, false)
        set(value) = prefs.edit { putBoolean(KEY_METADATA, value) }

    override var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null) ?: BuildConfig.DEFAULT_ADDON_BASE_URL
        set(value) = prefs.edit { putString(KEY_BASE_URL, value.trim()) }

    fun resetBaseUrl() {
        prefs.edit { remove(KEY_BASE_URL) }
    }

    private companion object {
        const val PREFS_NAME = "streamflix.settings"
        const val KEY_BASE_URL = "addon.baseUrl"
        const val KEY_AUTONOMOUS = "engine.autonomous"
        const val KEY_METADATA = "engine.metadataCatalogs"
    }
}
