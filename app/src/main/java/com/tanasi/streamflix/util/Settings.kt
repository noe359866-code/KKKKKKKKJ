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
}

/**
 * Ajustes del cliente.
 *
 * Sólo hay uno que importe: **la URL del servidor**. El backend es autoalojado
 * (portátil, NAS o cajón Android TV), así que la dirección se puede fijar en
 * tiempo de compilación para el despliegue real (`-PaddonBaseUrl=…`, ver
 * `app/build.gradle`) y corregir después desde la app sin reinstalar el APK.
 *
 * Se guarda en `SharedPreferences` con el valor de `BuildConfig` como semilla;
 * un build de release sin URL configurada arranca vacío y la app pide la
 * dirección en el primer arranque.
 */
class Settings(context: Context) : AddonSettings {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_ADDON_BASE_URL
        set(value) = prefs.edit { putString(KEY_BASE_URL, value.trim()) }

    /** Vuelve al valor compilado (útil cuando el usuario se equivoca de IP). */
    fun resetBaseUrl() {
        prefs.edit { remove(KEY_BASE_URL) }
    }

    private companion object {
        const val PREFS_NAME = "streamflix.settings"
        const val KEY_BASE_URL = "addon.baseUrl"
    }
}
