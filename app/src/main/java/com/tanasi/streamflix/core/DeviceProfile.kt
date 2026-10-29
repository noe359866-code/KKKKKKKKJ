package com.tanasi.streamflix.core

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Cómo se usa la app: TV con D-Pad o móvil táctil.
 *
 * Se calcula una sola vez al arrancar y se publica en la composición con
 * [LocalDeviceProfile], de modo que ningún composable tenga que preguntar al
 * `Context` ni duplicar la lógica.
 */
data class DeviceProfile(
    val isTelevision: Boolean,
    val hasTouchScreen: Boolean,
    val screenWidthDp: Int,
) {
    /**
     * En TV (o en cualquier dispositivo sin pantalla táctil) el foco es la
     * única forma de navegar: bordes, escala y foco inicial obligatorio.
     */
    val requiresDpadFocus: Boolean get() = isTelevision || !hasTouchScreen

    /** Tarjetas más grandes y menos columnas visibles en el salón. */
    val cardWidthDp: Int get() = if (isTelevision) 196 else 128

    val cardHeightDp: Int get() = if (isTelevision) 292 else 192

    /** En TV los carruseles caben justos; en móvil se ve casi una tarjeta y media. */
    val horizontalPaddingDp: Int get() = if (requiresDpadFocus) 48 else 16

    companion object {
        /** Valor por defecto para previews y tests: móvil táctil. */
        val Phone = DeviceProfile(isTelevision = false, hasTouchScreen = true, screenWidthDp = 411)
    }
}

/**
 * Detección de TV con las tres señales que usa Android, en orden de fiabilidad:
 *
 *  1. `UiModeManager` → `UI_MODE_TYPE_TELEVISION` (lo que informa el sistema).
 *  2. `FEATURE_LEANBACK` (Android TV) o `FEATURE_TELEVISION` (operadores).
 *  3. Ausencia de pantalla táctil, que descarta tablets con el APK de TV.
 *
 * Se exige (2) **y** la falta de táctil para los casos dudosos: un móvil puede
 * tener instalada la app de Android TV como sistema, y entonces solo (2) miente.
 */
object DeviceDetector {

    fun detect(context: Context): DeviceProfile {
        val packageManager = context.packageManager

        val uiModeTelevision = runCatching {
            val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            manager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }.getOrDefault(false)

        val hasTouch = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasTelevision = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)

        val isTelevision = uiModeTelevision || ((hasLeanback || hasTelevision) && !hasTouch)
        val widthDp = context.resources.configuration.screenWidthDp

        return DeviceProfile(
            isTelevision = isTelevision,
            hasTouchScreen = hasTouch,
            screenWidthDp = widthDp,
        )
    }
}

/** Perfil activo; [DeviceProfile.Phone] es el valor de previews y tests. */
val LocalDeviceProfile = staticCompositionLocalOf { DeviceProfile.Phone }

/**
 * Utilidades de D-Pad que no dependen del perfil: sirven para que un mando
 * también funcione en un móvil con teclado/ratón conectado.
 */
object DpadKeys {
    /** Teclas que "activan" un elemento enfocado. */
    private val confirmKeys = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
    )

    fun isConfirm(keyCode: Int): Boolean = keyCode in confirmKeys

    /** Oculta las barras del sistema (inmersivo) en el reproductor. */
    fun applyImmersive(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            view.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }
}
