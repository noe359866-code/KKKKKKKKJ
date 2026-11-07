package com.tanasi.streamflix.core

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Geometría y modo de uso de la app.
 *
 * **La app es sólo para Android TV.** No hay perfil de móvil: el mando (D-Pad)
 * es la única entrada, la pantalla se mira a tres metros y las tarjetas, los
 * márgenes y la tipografía se calculan para eso. El `screenWidthDp` permite
 * afinar el tamaño si el panel informa un ancho distinto (720p vs 1080p).
 */
data class DeviceProfile(
    val screenWidthDp: Int = DEFAULT_WIDTH_DP,
    val screenHeightDp: Int = DEFAULT_HEIGHT_DP,
) {
    /** Siempre TV: el manifiesto exige `android.software.leanback`. */
    val isTelevision: Boolean get() = true

    /** No hay táctil: todo se maneja con el mando. */
    val hasTouchScreen: Boolean get() = false

    /** El foco es la única forma de navegar: bordes, escala y foco inicial. */
    val requiresDpadFocus: Boolean get() = true

    /**
     * Ancho de una tarjeta 2:3 (pósters). El alto se deriva de la proporción,
     * no se escribe a mano: si mañana cambia el ancho, la tarjeta sigue siendo
     * 2:3 exacta en vez de deformarse.
     */
    val posterWidthDp: Int get() = if (screenWidthDp >= 1280) 200 else 168

    /** Ancho de una tarjeta 16:9 (continuar viendo y filas de arte ancho). */
    val landscapeWidthDp: Int get() = if (screenWidthDp >= 1280) 360 else 300

    /** Separación entre tarjetas: en el salón se agradece más aire. */
    val cardSpacingDp: Int get() = 18

    /** Zona segura de partida: 5 % de cada dimensión lógica, no de pulgadas. */
    val horizontalPaddingDp: Int get() = safeInsetDp(screenWidthDp, DEFAULT_WIDTH_DP)
    val verticalPaddingDp: Int get() = safeInsetDp(screenHeightDp, DEFAULT_HEIGHT_DP)

    companion object {
        const val DEFAULT_WIDTH_DP = 960
        const val DEFAULT_HEIGHT_DP = 540

        private fun safeInsetDp(value: Int, fallback: Int): Int {
            val dimension = value.takeIf { it > 0 } ?: fallback
            return dimension / 20 + if (dimension % 20 == 0) 0 else 1
        }

        /** Normaliza los tamaños lógicos que comunica Android. */
        fun fromDimensions(widthDp: Int, heightDp: Int) = DeviceProfile(
            screenWidthDp = widthDp.takeIf { it > 0 } ?: DEFAULT_WIDTH_DP,
            screenHeightDp = heightDp.takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP,
        )

        /** Valor por defecto para previews y tests. */
        val Tv = DeviceProfile()
    }
}

/**
 * Detección del panel.
 *
 * No se pregunta si es TV: el APK declara `android.software.leanback` como
 * requisito, así que sólo se instala en Android TV / Google TV. Lo único que
 * interesa aquí es el ancho, para no pintar tarjetas de 200 dp en un panel de
 * 720p.
 */
object DeviceDetector {

    fun detect(context: Context): DeviceProfile {
        val configuration = context.resources.configuration
        return DeviceProfile.fromDimensions(configuration.screenWidthDp, configuration.screenHeightDp)
    }
}

/** Perfil activo; [DeviceProfile.Tv] sirve para previews y tests. */
val LocalDeviceProfile = staticCompositionLocalOf { DeviceProfile.Tv }

/**
 * Utilidades del mando.
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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
