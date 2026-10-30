package com.tanasi.streamflix.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Una fuente reproducible, ya normalizada a partir de la respuesta del addon.
 *
 * El servidor devuelve el array **ordenado por prioridad de proveedor**, así que
 * el índice en la lista es el orden de failover: 0 es la mejor fuente.
 *
 * @property referer  cabecera HTTP que exigen Streamwish/Filemoon/etc.
 * @property userAgent cabecera HTTP del proveedor (los CDN filtran por UA).
 * @property notWebReady el enlace no funciona en un navegador pero sí en un
 *   reproductor nativo: para ExoPlayer es indiferente, se conserva por si el
 *   usuario abre la URL fuera de la app.
 */
data class StreamSource(
    val url: String,
    val name: String,
    val title: String,
    val quality: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val notWebReady: Boolean = false,
    val isLive: Boolean = false,
    val provider: String? = null,
    /** Posición original en la respuesta (0 = máxima prioridad). */
    val index: Int = 0,
    /** Cabeceras completas del resolver local/remoto: también Cookie y Origin. */
    val requestHeaders: Map<String, String> = emptyMap(),
    /**
     * `hls` / `dash` / `mp4` cuando el scraper ya sabe qué es. Es la única pista
     * fiable para las URLs sin extensión (`https://cdn/v/abc123`): sin ella
     * ExoPlayer intentaría abrir un manifiesto HLS como MP4 y no reproduciría.
     */
    val mediaType: String? = null,
) {
    /** Etiqueta corta para la UI: "Proveedor · Calidad". */
    val label: String
        get() = listOfNotNull(provider, quality).joinToString(" · ").ifBlank { name }

    /** HLS (.m3u8 o `mediaType = hls`): manifiesto, no un archivo progresivo. */
    val isHls: Boolean
        get() = mediaType == MEDIA_TYPE_HLS ||
            url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    val isDash: Boolean
        get() = mediaType == MEDIA_TYPE_DASH ||
            url.substringBefore('?').endsWith(".mpd", ignoreCase = true)

    /** Línea técnica de la lista de fuentes del reproductor: "HLS · 1080p". */
    val formatLine: String
        get() = buildList {
            add(when {
                isHls -> "HLS"
                isDash -> "DASH"
                else -> "MP4"
            })
            quality?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")

    companion object {
        const val MEDIA_TYPE_HLS = "hls"
        const val MEDIA_TYPE_DASH = "dash"
        const val MEDIA_TYPE_MP4 = "mp4"

        /** Lo que el scraper puede mandar en `behaviorHints.mediaType`. */
        private val mediaTypes = setOf(MEDIA_TYPE_HLS, MEDIA_TYPE_DASH, MEDIA_TYPE_MP4)

        /** Normaliza la pista del scraper ("HLS", "m3u8", "video/mp4"…). */
        fun parseMediaType(value: String?): String? {
            val text = value?.trim()?.lowercase()?.substringBefore(';') ?: return null
            if (text.isBlank()) return null
            return when {
                text in mediaTypes -> text
                "mpegurl" in text || "m3u8" in text || text == "m3u" -> MEDIA_TYPE_HLS
                "dash" in text || text == "mpd" -> MEDIA_TYPE_DASH
                "mp4" in text || "m4v" in text -> MEDIA_TYPE_MP4
                else -> null
            }
        }

        /** Extensiones que ExoPlayer reproduce sin extensiones extra. */
        private val playableExtensions = listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".ts")

        /**
         * Lo que sí es una página web o una imagen, nunca un vídeo. Antes se
         * exigía una extensión de vídeo y se tiraban las URLs sin extensión:
         * muchos CDN sirven HLS en `https://host/live/abc123` o detrás de una
         * ruta firmada, así que la app descartaba fuentes que sí funcionaban.
         * Eso era una causa directa de "no reproduce nada".
         */
        private val rejectedExtensions = listOf(
            ".html", ".htm", ".php", ".asp", ".aspx", ".jsp", ".json", ".txt",
            ".css", ".js", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
        )

        /**
         * Descarta lo que ExoPlayer no puede abrir (una página HTML de embed, un
         * `magnet:`, una imagen…) sin exigir extensión: si el esquema es HTTP(S)
         * y no es claramente una página, se intenta y el failover decide. Se
         * aplica al normalizar la respuesta, no al reproducir.
         */
        fun looksPlayable(url: String): Boolean {
            val cleaned = url.trim().substringBefore('#').substringBefore('?').lowercase()
            if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) return false
            if (playableExtensions.any { cleaned.endsWith(it) }) return true
            return rejectedExtensions.none { cleaned.endsWith(it) }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* DTOs del protocolo Stremio                                                  */
/* -------------------------------------------------------------------------- */

@Serializable
data class StreamsResponse(
    @SerialName("streams") val streams: List<StreamDto> = emptyList(),
    /** Segundos que el cliente puede cachear; el servidor lo alinea con su TTL. */
    @SerialName("cacheMaxAge") val cacheMaxAge: Long? = null,
)

@Serializable
data class StreamDto(
    @SerialName("name") val name: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("quality") val quality: String? = null,
    @SerialName("provider") val provider: String? = null,
    @SerialName("isLive") val isLive: Boolean = false,
    @SerialName("behaviorHints") val behaviorHints: BehaviorHints? = null,
) {
    fun toSource(index: Int): StreamSource? {
        if (!StreamSource.looksPlayable(url)) return null
        val headers = behaviorHints?.requestHeaders ?: emptyMap()
        return StreamSource(
            url = url.trim(),
            name = name.ifBlank { title },
            title = title.ifBlank { name },
            quality = quality?.takeIf { it.isNotBlank() },
            referer = headers.header("Referer"),
            userAgent = headers.header("User-Agent"),
            notWebReady = behaviorHints?.notWebReady == true,
            isLive = isLive || behaviorHints?.isLive == true,
            provider = provider,
            index = index,
            requestHeaders = headers,
            mediaType = StreamSource.parseMediaType(behaviorHints?.mediaType),
        )
    }
}

@Serializable
data class BehaviorHints(
    @SerialName("requestHeaders") val requestHeaders: Map<String, String>? = null,
    @SerialName("notWebReady") val notWebReady: Boolean = false,
    @SerialName("isLive") val isLive: Boolean = false,
    /** `hls` / `dash` / `mp4`: lo manda el scraper cuando la URL no lo dice. */
    @SerialName("mediaType") val mediaType: String? = null,
)

/**
 * Los nombres de cabecera HTTP no distinguen mayúsculas, pero el servidor puede
 * mandarlas como `Referer` o `referer` según el proveedor: se busca la primera
 * coincidencia sin distinguir y se devuelve tal cual.
 */
private fun Map<String, String>.header(name: String): String? =
    entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
