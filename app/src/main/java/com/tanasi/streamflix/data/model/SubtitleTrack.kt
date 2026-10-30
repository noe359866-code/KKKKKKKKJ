package com.tanasi.streamflix.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Una pista de subtítulos externa, ya normalizada.
 *
 * El servidor (y el motor embebido) entregan la lista en el protocolo Stremio:
 * `{ id, url, lang, label }`. Aquí se le añade lo que Media3 necesita para
 * cargarla como pista de texto: el MIME.
 *
 * Los subtítulos viajan **en la misma respuesta que las fuentes** (campo extra
 * `subtitles`), así que abrir un título sigue siendo una sola petición y las
 * pistas están listas antes de crear el `MediaItem`. Cargarlas después
 * obligaría a reconstruir la fuente y el vídeo volvería a empezar.
 *
 * @property url  `.vtt`, `.srt`, `.ass` o `.ttml` por HTTP(S).
 * @property lang BCP-47 (`es`, `es-419`, `en`); vacío si el proveedor no lo dice.
 * @property label nombre legible ("Español", "Español (Latino)", "Inglés").
 * @property provider proveedor del que salió la pista (se muestra en el panel).
 */
data class SubtitleTrack(
    val url: String,
    val lang: String = "",
    val label: String = "",
    val provider: String? = null,
    val format: String? = null,
) {
    /** Clave estable para listas y para recordar la elección. */
    val id: String get() = url

    /** `"Español · NovaHD"` en el panel de pistas. */
    val displayLabel: String
        get() = buildList {
            add(label.ifBlank { languageLabel(lang).ifBlank { "Subtítulo" } })
            provider?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")

    /** MIME que espera `MediaItem.SubtitleConfiguration`. */
    val mimeType: String get() = mimeFor(url, format)

    /** ¿Es la pista que la app elige sola cuando hay varias? */
    val isSpanish: Boolean get() = lang.startsWith("es", ignoreCase = true)

    companion object {
        /* Los mismos literales que `androidx.media3.common.MimeTypes`. Se
         * escriben a mano para que el modelo no dependa de Media3 (y se pueda
         * probar en la JVM sin Robolectric). */
        const val MIME_VTT = "text/vtt"
        const val MIME_SUBRIP = "application/x-subrip"
        const val MIME_SSA = "text/x-ssa"
        const val MIME_TTML = "application/ttml+xml"

        /** Formato por extensión; si no se reconoce, el `label` del proveedor. */
        fun formatFor(url: String): String? {
            val clean = url.substringBefore('#').substringBefore('?').lowercase()
            val extension = clean.substringAfterLast('.', "")
            return when (extension) {
                "vtt", "webvtt" -> "vtt"
                "srt", "sub" -> "srt"
                "ass", "ssa" -> "ass"
                "ttml", "dfxp", "xml" -> "ttml"
                else -> null
            }
        }

        fun mimeFor(url: String, format: String? = null): String {
            val kind = (format?.lowercase() ?: formatFor(url))
            return when (kind) {
                "vtt" -> MIME_VTT
                "ass" -> MIME_SSA
                "ttml" -> MIME_TTML
                /* SRT es el caso por defecto: Media3 lo convierte a VTT al
                 * vuelo con `SubtitleDecoderFactory`, y es el formato que más
                 * publican los proveedores en español. */
                else -> MIME_SUBRIP
            }
        }

        /** `es-419` → `Español (Latino)`. Sin código, cadena vacía. */
        fun languageLabel(lang: String): String {
            val clean = lang.trim().lowercase()
            if (clean.isBlank()) return ""
            val base = clean.substringBefore('-')
            val name = when (base) {
                "es" -> "Español"
                "en" -> "Inglés"
                "pt" -> "Portugués"
                "fr" -> "Francés"
                "de" -> "Alemán"
                "it" -> "Italiano"
                "ja" -> "Japonés"
                "ko" -> "Coreano"
                "zh" -> "Chino"
                "ru" -> "Ruso"
                else -> return clean.uppercase()
            }
            val region = clean.substringAfter('-', "")
            return when {
                region.isEmpty() -> name
                base == "es" && region == "419" -> "$name (Latino)"
                base == "pt" && region == "br" -> "$name (Brasil)"
                else -> "$name (${region.uppercase()})"
            }
        }

        /**
         * Índice de la pista que conviene activar sola, o `null` si no hay
         * ninguna en español.
         *
         * En el directo nunca: muchas señales emiten un CC quemado (o vacío) y
         * encenderlo por sorpresa es peor que no ofrecerlo.
         */
        fun preferredIndex(tracks: List<SubtitleTrack>, isLive: Boolean): Int? {
            if (isLive) return null
            return tracks.indexOfFirst { it.isSpanish }.takeIf { it >= 0 }
        }
    }
}

/**
 * DTO del protocolo Stremio. `label` es opcional en el estándar (algunos
 * addons sólo mandan `lang`), por eso se cae al nombre del idioma.
 */
@Serializable
data class SubtitleDto(
    @SerialName("id") val id: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("lang") val lang: String = "",
    @SerialName("label") val label: String = "",
    @SerialName("provider") val provider: String? = null,
    @SerialName("format") val format: String? = null,
) {
    fun toTrack(): SubtitleTrack? {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return null
        val code = normalizeLang(lang)
        return SubtitleTrack(
            url = clean,
            lang = code,
            label = label.trim().ifBlank { SubtitleTrack.languageLabel(code) }.ifBlank { "Subtítulo" },
            provider = provider?.takeIf { it.isNotBlank() },
            format = format?.takeIf { it.isNotBlank() } ?: SubtitleTrack.formatFor(clean),
        )
    }

    companion object {
        /** El servidor ya normaliza, pero un addon de terceros puede no hacerlo. */
        private val aliases = mapOf(
            "spa" to "es", "esp" to "es", "spanish" to "es", "espanol" to "es", "español" to "es",
            "eng" to "en", "english" to "en", "por" to "pt", "portuguese" to "pt",
            "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de",
            "ita" to "it", "jpn" to "ja", "kor" to "ko", "zho" to "zh", "chi" to "zh", "rus" to "ru",
        )

        fun normalizeLang(value: String): String {
            val text = value.trim().lowercase().replace('_', '-')
            if (text.isBlank()) return ""
            val base = aliases[text.substringBefore('-')] ?: text.substringBefore('-')
            val region = text.substringAfter('-', "")
            val validBase = base.length in 2..3 && base.all { it.isLetter() }
            if (!validBase) return ""
            val validRegion = region.length == 2 && region.all { it.isLetter() } ||
                (region.length == 3 && region.all { it.isDigit() })
            return if (validRegion) "$base-$region" else base
        }
    }
}
