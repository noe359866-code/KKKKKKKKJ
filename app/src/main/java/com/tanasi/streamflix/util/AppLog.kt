package com.tanasi.streamflix.util

import android.util.Log
import com.tanasi.streamflix.BuildConfig

/**
 * Log con etiqueta corta y silenciado en release.
 *
 * Media3 y ExoPlayer son muy habladores con `Log` directo; centralizarlo evita
 * filtrar URLs firmadas en builds de producción (el servidor hace lo mismo con
 * su propio logger).
 */
object AppLog {
    private const val TAG = "Streamflix"

    fun d(message: String, vararg args: Any?) {
        if (BuildConfig.DEBUG) Log.d(TAG, format(message, args))
    }

    fun i(message: String, vararg args: Any?) {
        Log.i(TAG, format(message, args))
    }

    fun w(message: String, vararg args: Any?) {
        Log.w(TAG, format(message, args))
    }

    fun e(message: String, error: Throwable? = null, vararg args: Any?) {
        Log.e(TAG, format(message, args), error)
    }

    /** Nunca loguear la query de una URL: los CDN firman con tokens. */
    fun redact(url: String): String = url.substringBefore('?').take(160)

    private fun format(message: String, args: Array<out Any?>): String =
        if (args.isEmpty()) message else message.format(*args)
}
