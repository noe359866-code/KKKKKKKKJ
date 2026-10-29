package com.tanasi.streamflix

import android.app.Application
import android.content.res.Configuration
import com.tanasi.streamflix.core.DeviceDetector
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.di.ServiceLocator
import com.tanasi.streamflix.util.AppLog

/**
 * Punto de entrada del proceso.
 *
 * Mantiene el perfil de Android TV y el árbol de dependencias. La interfaz es
 * siempre de TV; la detección sirve para bloquear instalaciones manuales en
 * móviles/tablets y para adaptar las medidas al ancho real de la televisión.
 */
class StreamflixApp : Application() {

    var deviceProfile: DeviceProfile = DeviceProfile.Television
        private set

    override fun onCreate() {
        super.onCreate()
        deviceProfile = DeviceDetector.detect(this)
        /* Inicializa HTTP antes del primer frame del catálogo. */
        ServiceLocator.addonClient()

        AppLog.i(
            "arranque: android_tv=%s táctil=%s ancho=%ddp",
            deviceProfile.isAndroidTv,
            deviceProfile.hasTouchScreen,
            deviceProfile.screenWidthDp,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        /* Una TV puede cambiar de resolución en caliente (1080p → 4K). */
        val refreshed = DeviceDetector.detect(this)
        if (refreshed != deviceProfile) deviceProfile = refreshed
    }
}
