package com.tanasi.streamflix.core

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Medidas de Android TV y capacidad real del dispositivo.
 *
 * La interfaz ya no cambia entre móvil y TV: todas las pantallas usan tamaños
 * de televisión y navegación con D-Pad. [isAndroidTv] se utiliza para impedir
 * el uso normal si la APK se instala manualmente en un móvil o una tablet.
 * Una TV con pantalla táctil sigue siendo una TV: el táctil no la descarta.
 */
data class DeviceProfile(
    val isAndroidTv: Boolean,
    val hasTouchScreen: Boolean,
    val screenWidthDp: Int,
) {
    /** El mando es la forma principal de navegación en toda la app. */
    val requiresDpadFocus: Boolean get() = true

    /** Tarjeta 2:3; el alto se deriva de esta medida. */
    val posterWidthDp: Int get() = 220

    /** Tarjeta 16:9 para directos, deportes y continuar viendo. */
    val landscapeWidthDp: Int get() = 400

    val cardSpacingDp: Int get() = 18

    /** Margen de seguridad para la overscan de televisores. */
    val horizontalPaddingDp: Int get() = 48

    companion object {
        /** Perfil por defecto para previews, tests y primer frame en una TV. */
        val Television = DeviceProfile(
            isAndroidTv = true,
            hasTouchScreen = false,
            screenWidthDp = 960,
        )
    }
}

/**
 * Android TV y Google TV declaran Leanback; el modo televisión también cubre
 * el emulador de Android TV. No se exige ausencia de táctil porque existen
 * televisiones táctiles. FEATURE_TELEVISION por sí sola no convierte un móvil
 * con launcher de TV en un dispositivo compatible.
 */
object DeviceDetector {

    fun detect(context: Context): DeviceProfile {
        val packageManager = context.packageManager
        val uiModeTelevision = runCatching {
            val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            manager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }.getOrDefault(false)
        val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        return DeviceProfile(
            isAndroidTv = isAndroidTv(uiModeTelevision, hasLeanback),
            hasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
            screenWidthDp = context.resources.configuration.screenWidthDp,
        )
    }

    /** Regla pura, comprobada por tests sin necesidad de un emulador. */
    fun isAndroidTv(uiModeTelevision: Boolean, hasLeanback: Boolean): Boolean =
        uiModeTelevision || hasLeanback
}

/** Todas las pantallas, incluidas las previews, parten del perfil de TV. */
val LocalDeviceProfile = staticCompositionLocalOf { DeviceProfile.Television }

/** Teclas del mando y modo inmersivo del reproductor. */
object DpadKeys {
    private val confirmKeys = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
    )

    fun isConfirm(keyCode: Int): Boolean = keyCode in confirmKeys

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
