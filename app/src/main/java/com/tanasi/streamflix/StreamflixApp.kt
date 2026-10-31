package com.tanasi.streamflix

import android.app.Application
import android.content.res.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tanasi.streamflix.core.DeviceDetector
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.di.ServiceLocator
import com.tanasi.streamflix.util.AppLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Punto de entrada del proceso.
 *
 * Deja tres cosas listas antes de la primera pantalla:
 *  1. el [DeviceProfile] (TV vs móvil), que se detecta una sola vez;
 *  2. el árbol de dependencias ([ServiceLocator]);
 *  3. el cargador de imágenes de Coil, con las cabeceras que necesitan los
 *     pósters (ver [artworkClient]).
 *
 * Android TV informa el modo de interfaz en la `Application` (`onConfigurationChanged`
 * llega antes que el `Activity`), así que aquí se guarda el perfil y la UI lo lee.
 */
class StreamflixApp : Application(), ImageLoaderFactory {

    var deviceProfile: DeviceProfile = DeviceProfile.Tv
        private set

    override fun onCreate() {
        super.onCreate()
        deviceProfile = DeviceDetector.detect(this)
        /* Toca el contenedor para que el cliente HTTP se cree con la app ya
         * arrancada y no en el primer frame del Home. */
        ServiceLocator.addonClient()

        AppLog.i("arranque: Android TV, ancho=%ddp", deviceProfile.screenWidthDp)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        /* Una TV puede cambiar de resolución en caliente (1080p → 4K); el perfil
         * se recalcula pero sólo cambia si el modo de interfaz cambió de verdad. */
        val refreshed = DeviceDetector.detect(this)
        if (refreshed != deviceProfile) deviceProfile = refreshed
    }

    /**
     * Loader de Coil para todo el arte de la app.
     *
     * Sin esto, Coil usa su cliente por defecto (sin `Referer` ni un
     * `User-Agent` de navegador) y muchos CDN de pósters responden 403: la
     * tarjeta se queda en el respaldo y el Home parece no tener imágenes.
     * Aquí cada petición sale como la haría el navegador del propio sitio, con
     * caché de disco para no volver a pedir el mismo póster al hacer scroll.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(artworkClient())
        .respectCacheHeaders(false)
        .crossfade(220)
        .build()

    private fun artworkClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val origin = "${request.url.scheme}://${request.url.host}/"
            chain.proceed(
                request.newBuilder()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    /* Los CDN de los proveedores comprueban el origen; el
                     * propio host del póster es el referer válido más seguro. */
                    .header("Referer", origin)
                    .header(
                        "Accept",
                        "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
                    )
                    .build(),
            )
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private companion object {
        /** UA de navegador de salón: los CDN sirven WebP y no bloquean la IP. */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; SmartTV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
