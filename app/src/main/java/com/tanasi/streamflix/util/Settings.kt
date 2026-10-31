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

    init {
        /*
         * Migración: las versiones anteriores traían Cinemeta desactivado, así
         * que quien hubiese pulsado "Guardar" en Ajustes tiene un `false`
         * persistido. Se activa una vez (el usuario puede volver a apagarlo) o
         * el Home seguiría sin los pósters y backdrops de Cinemeta.
         */
        if (!prefs.getBoolean(KEY_METADATA_MIGRATED, false)) {
            prefs.edit {
                putBoolean(KEY_METADATA, true)
                putBoolean(KEY_METADATA_MIGRATED, true)
            }
        }
    }

    override var autonomous: Boolean
        get() = prefs.getBoolean(KEY_AUTONOMOUS, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTONOMOUS, value) }

    /**
     * Cinemeta viene activado de fábrica: es la única fuente que garantiza
     * póster 2:3 y backdrop 16:9 de calidad para cada título, así que sin él el
     * Home se queda con los pósters pequeños (o ausentes) de las webs de
     * origen. Se puede desactivar en Ajustes → Conexión.
     */
    override var metadataCatalogs: Boolean
        get() = prefs.getBoolean(KEY_METADATA, true)
        set(value) = prefs.edit { putBoolean(KEY_METADATA, value) }

    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_UPDATE, value) }

    var lastUpdateCheckTime: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_UPDATE_CHECK, value) }

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
        const val KEY_METADATA_MIGRATED = "engine.metadataCatalogsMigrated"
        const val KEY_AUTO_UPDATE = "app.autoUpdate"
        const val KEY_LAST_UPDATE_CHECK = "app.lastUpdateCheck"
    }
}
