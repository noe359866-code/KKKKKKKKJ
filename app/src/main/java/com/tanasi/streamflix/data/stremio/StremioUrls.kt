package com.tanasi.streamflix.data.stremio

import com.tanasi.streamflix.data.remote.Urls

object StremioUrls {

    /**
     * Normaliza la URL introducida por el usuario:
     * - convierte stremio:// a https://
     * - añade https:// si no tiene esquema
     * - añade /manifest.json al final si no lo tiene
     */
    fun normalizeManifestUrl(input: String): String {
        var trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("stremio://", ignoreCase = true)) {
            trimmed = "https://" + trimmed.substring("stremio://".length)
        } else if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "https://$trimmed"
        }
        if (!trimmed.endsWith("/manifest.json", ignoreCase = true)) {
            trimmed = "$trimmed/manifest.json"
        }
        return trimmed
    }

    /** Extrae la URL base quitando /manifest.json. */
    fun manifestToBaseUrl(manifestUrl: String): String {
        val normalized = normalizeManifestUrl(manifestUrl)
        return normalized.substringBeforeLast("/manifest.json").trimEnd('/')
    }

    /** Endpoint de catálogo con extras Stremio. */
    fun catalog(
        baseUrl: String,
        type: String,
        id: String,
        search: String? = null,
        genre: String? = null,
        skip: Int? = null,
    ): String = Urls.catalog(baseUrl, type, id, search = search, genre = genre, skip = skip)

    /** Endpoint de streams Stremio. */
    fun stream(baseUrl: String, type: String, id: String): String =
        Urls.stream(baseUrl, type, id)

    /** Endpoint de meta Stremio. */
    fun meta(baseUrl: String, type: String, id: String): String =
        Urls.meta(baseUrl, type, id)
}
