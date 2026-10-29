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
 * Deja dos cosas listas antes de la primera pantalla:
 *  1. el [DeviceProfile] (TV vs móvil), que se detecta una sola vez;
 *  2. el árbol de dependencias ([ServiceLocator]).
 *
 * Android TV informa el modo de interfaz en la `Application` (`onConfigurationChanged`
 * llega antes que el `Activity`), así que aquí se guarda el perfil y la UI lo lee.
 */
class StreamflixApp : Application() {

    var deviceProfile: DeviceProfile = DeviceProfile.Phone
        private set

    override fun onCreate() {
        super.onCreate()
        deviceProfile = DeviceDetector.detect(this)
        /* Toca el contenedor para que el cliente HTTP se cree con la app ya
         * arrancada y no en el primer frame del Home. */
        ServiceLocator.addonClient()

        AppLog.i(
            "arranque: tv=%s táctil=%s ancho=%ddp",
            deviceProfile.isTelevision,
            deviceProfile.hasTouchScreen,
            deviceProfile.screenWidthDp,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        /* Una TV puede cambiar de resolución en caliente (1080p → 4K); el perfil
         * se recalcula pero sólo cambia si el modo de interfaz cambió de verdad. */
        val refreshed = DeviceDetector.detect(this)
        if (refreshed != deviceProfile) deviceProfile = refreshed
    }
}
