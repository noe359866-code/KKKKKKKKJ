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
) {
    /** Etiqueta corta para la UI: "Proveedor · Calidad". */
    val label: String
        get() = listOfNotNull(provider, quality).joinToString(" · ").ifBlank { name }

    /** HLS (.m3u8) se beneficia de un LoadControl distinto al de un MP4. */
    val isHls: Boolean get() = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    val isDash: Boolean get() = url.substringBefore('?').endsWith(".mpd", ignoreCase = true)

    companion object {
        /** Extensiones que ExoPlayer reproduce sin extensiones extra. */
        private val playableExtensions = listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".ts")

        /**
         * Descarta lo que ExoPlayer no puede abrir (una página HTML de embed, un
         * `magnet:`…). Se aplica al normalizar la respuesta, no al reproducir.
         */
        fun looksPlayable(url: String): Boolean {
            val cleaned = url.substringBefore('#').substringBefore('?').lowercase()
            if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) return false
            return playableExtensions.any { cleaned.endsWith(it) }
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
        )
    }
}

@Serializable
data class BehaviorHints(
    @SerialName("requestHeaders") val requestHeaders: Map<String, String>? = null,
    @SerialName("notWebReady") val notWebReady: Boolean = false,
    @SerialName("isLive") val isLive: Boolean = false,
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
