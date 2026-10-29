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
    var remoteCatalogs: Boolean
}

/**
 * Ajustes del cliente.
 *
 * Hoy sólo hay dos que importen: **la URL del servidor** —el backend vive en el
 * portátil del usuario o en un cajón Android TV, así que no se puede fijar en el
 * APK— y si se consultan catálogos remotos. Se guardan en `SharedPreferences`
 * con el valor de `BuildConfig` como semilla (`-PaddonBaseUrl=…`).
 */
class Settings(context: Context) : AddonSettings {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_ADDON_BASE_URL
        set(value) = prefs.edit { putString(KEY_BASE_URL, value.trim()) }

    /**
     * Catálogos servidos por el addon (`/catalogs.json` +
     * `/catalog/{type}/{id}.json`). **Encendido por defecto**: el servidor
     * publica las filas (películas, series, anime, TV en vivo y deportes) y la
     * app las pinta tal cual, así que añadir una fila es editar
     * `server/config/catalogs.json` y no reinstalar el APK.
     *
     * Apagarlo deja el Home con la cartelera curada de `assets/catalog/seed.json`
     * —útil si el servidor no responde o si se quiere una lista fija—.
     */
    override var remoteCatalogs: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_CATALOGS, true)
        set(value) = prefs.edit { putBoolean(KEY_REMOTE_CATALOGS, value) }

    /** Vuelve al valor compilado (útil cuando el usuario se equivoca de IP). */
    fun resetBaseUrl() {
        prefs.edit { remove(KEY_BASE_URL) }
    }

    private companion object {
        const val PREFS_NAME = "streamflix.settings"
        const val KEY_BASE_URL = "addon.baseUrl"
        const val KEY_REMOTE_CATALOGS = "addon.remoteCatalogs"
    }
}
