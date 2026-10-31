package com.tanasi.streamflix.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.data.model.SubtitleTrack

/**
 * Construcción de `ExoPlayer` y de las fuentes de medios.
 *
 * El punto crítico son las **cabeceras HTTP**: Streamwish, Filemoon, VOE o
 * Mp4upload rechazan el `.m3u8` si falta el `Referer` del sitio que lo publica
 * (y a veces un `User-Agent` concreto). Ambos motores los devuelven en
 * `behaviorHints.requestHeaders`; aquí se inyectan en el `DataSource`.
 */
object MediaSources {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000

    /** UA de respaldo (el servidor normalmente manda uno por proveedor). */
    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * `DataSource` con las cabeceras de **una** fuente concreta.
     *
     * Es por fuente, no por reproductor: al hacer failover el `Referer` cambia
     * (cada CDN exige el suyo), así que cada `MediaSource` lleva el suyo.
     */
    fun dataSourceFactory(source: StreamSource): DataSource.Factory {
        val headers = linkedMapOf<String, String>().apply { putAll(source.requestHeaders) }
        fun contains(name: String) = headers.keys.any { it.equals(name, ignoreCase = true) }
        source.referer?.takeIf { it.isNotBlank() }?.let { referer ->
            if (!contains("Referer")) headers["Referer"] = referer
            if (!contains("Origin")) originOf(referer)?.let { headers["Origin"] = it }
        }
        source.userAgent?.takeIf { it.isNotBlank() }?.let { if (!contains("User-Agent")) headers["User-Agent"] = it }

        return DefaultHttpDataSource.Factory()
            .setUserAgent(source.userAgent ?: FALLBACK_USER_AGENT)
            .setDefaultRequestProperties(headers)
            /* Muchos CDN redirigen http → https (o a otro subdominio): sin esto
             * ExoPlayer rechaza el salto por seguridad de protocolo. */
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
    }

    /**
     * `MediaSource` de una fuente, con su tipo MIME cuando se puede deducir.
     *
     * `DefaultMediaSourceFactory` elige `HlsMediaSource` para `.m3u8` (gracias al
     * módulo `media3-exoplayer-hls`), `DashMediaSource` para `.mpd` y
     * `ProgressiveMediaSource` para `.mp4`. El MIME explícito sólo se usa como
     * ayuda cuando la URL no tiene extensión (algunos CDN sirven `/play/<id>`).
     */
    fun mediaSource(
        context: Context,
        source: StreamSource,
        subtitles: List<SubtitleTrack> = emptyList(),
    ): MediaSource {
        val mimeType = when {
            /* La pista del scraper manda: es lo único que distingue un HLS de un
             * MP4 cuando la URL no tiene extensión (`https://cdn/v/abc123`). */
            source.mediaType == StreamSource.MEDIA_TYPE_HLS -> MimeTypes.APPLICATION_M3U8
            source.mediaType == StreamSource.MEDIA_TYPE_DASH -> MimeTypes.APPLICATION_MPD
            source.mediaType == StreamSource.MEDIA_TYPE_MP4 -> MimeTypes.VIDEO_MP4
            source.isHls -> MimeTypes.APPLICATION_M3U8
            source.isDash -> MimeTypes.APPLICATION_MPD
            source.url.substringBefore('?').endsWith(".mp4", true) -> MimeTypes.VIDEO_MP4
            /* Canales en directo sin extensión (`/live/espn-1`): el 100 % de la
             * TV en vivo del addon sale por HLS, así que se fuerza el manifiesto
             * en vez de dejar que ExoPlayer intente abrirlo como progresivo. */
            source.isLive -> MimeTypes.APPLICATION_M3U8
            else -> null
        }

        val mediaItem = MediaItem.Builder()
            .setUri(source.url)
            .apply { mimeType?.let { setMimeType(it) } }
            /* Los subtítulos van en el `MediaItem`, no en el `ExoPlayer`: si se
             * añadieran después (con `addTextOutput` o cambiando la fuente) el
             * vídeo volvería a empezar. Aquí llegan antes de `prepare()`. */
            .apply {
                val configurations = subtitleConfigurations(subtitles, source)
                if (configurations.isNotEmpty()) setSubtitleConfigurations(configurations)
            }
            .build()

        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory(source))
            .createMediaSource(mediaItem)
    }

    /**
     * Subtítulos → `MediaItem.SubtitleConfiguration`.
     *
     * La pista en español (si la hay) se marca `DEFAULT`, que es lo que hace
     * que Media3 la seleccione sola en cuanto el vídeo está listo: el usuario
     * ve la película subtitulada sin tener que entrar al panel de pistas. En
     * directo no se marca nada (muchas señales ya traen CC quemado).
     */
    fun subtitleConfigurations(
        subtitles: List<SubtitleTrack>,
        source: StreamSource,
    ): List<MediaItem.SubtitleConfiguration> {
        if (subtitles.isEmpty()) return emptyList()
        val preferred = SubtitleTrack.preferredIndex(subtitles, source.isLive)
        return subtitles.mapIndexed { index, track ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setMimeType(track.mimeType)
                .apply { if (track.lang.isNotBlank()) setLanguage(track.lang) }
                .setLabel(track.displayLabel)
                .apply {
                    if (index == preferred) {
                        /* DEFAULT: se activa sola. SELECTION_FLAG_FORCED no: no
                         * queremos que un subtítulo "forzado" sustituya al
                         * diálogo original. */
                        setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    }
                }
                .build()
        }
    }

    /**
     * Reproductor base.
     *
     * - `setWakeMode(WAKE_MODE_NETWORK)`: en Android TV el dispositivo no debe
     *   suspender la red a mitad de un partido.
     * - `setHandleAudioBecomingNoisy(true)`: pausa al desconectar los auriculares.
     * - `LoadControl` por defecto de Media3: el buffer de fábrica ya aguanta un
     *   cambio de CDN; tocarlo hacia arriba sólo retrasa el primer frame.
     * - incrementos de salto de ±10 s **sólo en VOD** (en directo el salto al
     *   borde del vivo lo resuelve HlsMediaSource).
     */
    fun buildPlayer(context: Context, isLive: Boolean = false): ExoPlayer =
        ExoPlayer.Builder(context)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .apply {
                /* En VOD, ±10 s por pulsación del mando: los CDN entregan pocos
                 * keyframes y el incremento por defecto (5 s) deja el salto a
                 * medias entre dos imágenes. En directo se queda el de fábrica,
                 * que es el que usa "saltar al borde del vivo". */
                if (!isLive) {
                    setSeekBackIncrementMs(10_000)
                    setSeekForwardIncrementMs(10_000)
                }
            }
            .build()

    /** `https://host/path` → `https://host` (lo que espera la cabecera `Origin`). */
    fun originOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd)
        val rest = url.substring(schemeEnd + 3)
        val host = rest.substringBefore('/')
        if (host.isBlank()) return null
        return "$scheme://$host"
    }
}
