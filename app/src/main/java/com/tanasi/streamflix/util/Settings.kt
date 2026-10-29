package com.tanasi.streamflix.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tanasi.streamflix.BuildConfig

/**
 * Lo que la capa de datos necesita de los ajustes.
 *
 * Existe para poder probar los repositorios en JVM (sin `Context` ni
 * `SharedPreferences`): en producción lo implementa [Settings], en los tests una
 * clase de tres líneas.
 */
interface AddonSettings {
    var baseUrl: String

    /** `true` = scrapers embebidos en la APK; `false` = addon Node externo. */
    val useEmbedded: Boolean get() = false
}

/**
 * Ajustes del cliente.
 *
 * La APK arranca en modo autónomo: catálogo, scrapers y reproductor viven
 * dentro de la app. El servidor Node externo queda como opción avanzada para
 * quien ya tenga uno en su LAN o VPS.
 */
class Settings(context: Context) : AddonSettings {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_ADDON_BASE_URL
        set(value) = prefs.edit { putString(KEY_BASE_URL, value.trim()) }

    override var useEmbedded: Boolean
        get() = prefs.getBoolean(KEY_USE_EMBEDDED, BuildConfig.EMBEDDED_SCRAPERS)
        set(value) = prefs.edit { putBoolean(KEY_USE_EMBEDDED, value) }

    /** Vuelve al valor compilado (útil cuando el usuario se equivoca de IP). */
    fun resetBaseUrl() {
        prefs.edit { remove(KEY_BASE_URL) }
    }

    private companion object {
        const val PREFS_NAME = "streamflix.settings"
        const val KEY_BASE_URL = "addon.baseUrl"
        const val KEY_USE_EMBEDDED = "addon.useEmbedded"
    }
}
