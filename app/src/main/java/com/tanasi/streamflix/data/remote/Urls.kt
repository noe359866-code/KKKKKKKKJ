package com.tanasi.streamflix.data.remote

import java.net.URLEncoder

/**
 * Construcción de URLs del addon. Es Kotlin puro (nada de `android.net.Uri`)
 * para poder testearlo en JVM sin emulador.
 */
object Urls {

    /**
     * Normaliza lo que el usuario escribe en el campo de servidor:
     * `192.168.1.50:7000` → `http://192.168.1.50:7000`, y quita la barra final.
     */
    fun normalizeBase(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    fun isValidBase(input: String): Boolean {
        val normalized = normalizeBase(input)
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
        val host = normalized.removePrefix("http://").removePrefix("https://").substringBefore('/')
        return host.isNotBlank() && (host.contains('.') || host.startsWith("localhost"))
    }

    /** `{base}/stream/{type}/{id}.json` — el endpoint que usa Stremio. */
    fun stream(baseUrl: String, type: String, id: String): String =
        "${normalizeBase(baseUrl)}/stream/${encodeSegment(type)}/${encodeSegment(id)}.json"

    fun manifest(baseUrl: String): String = "${normalizeBase(baseUrl)}/manifest.json"

    fun catalog(baseUrl: String, type: String, id: String): String =
        "${normalizeBase(baseUrl)}/catalog/${encodeSegment(type)}/${encodeSegment(id)}.json"

    fun health(baseUrl: String): String = "${normalizeBase(baseUrl)}/health"

    /**
     * Los ids llevan `:` (temporada/episodio) y guiones; no hace falta
     * tocarlos. Sólo se codifica lo que rompería el path.
     */
    fun encodeSegment(value: String): String {
        if (value.all { it.isLetterOrDigit() || it in "-_.~:" }) return value
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
