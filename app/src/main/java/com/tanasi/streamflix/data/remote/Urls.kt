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

    /**
     * Acepta lo que de verdad se escribe en un TV box: una IP
     * (`192.168.1.50:7000`), un dominio (`addon.midominio.com`), un hostname de
     * la LAN (`nas:7000`) o `localhost`. Se rechaza cualquier otro esquema.
     */
    fun isValidBase(input: String): Boolean {
        val raw = input.trim()
        if (raw.isEmpty()) return false
        /* Otro esquema no se "arregla" añadiendo http:// delante: `ftp://x`
         * acabaría en `http://ftp://x`, que no conecta a ningún sitio. */
        if (raw.contains("://") && !raw.startsWith("http://") && !raw.startsWith("https://")) {
            return false
        }
        val normalized = normalizeBase(raw)
        val authority = normalized.removePrefix("http://").removePrefix("https://")
            .substringBefore('/')
        return HOST_PORT.matches(authority)
    }

    /**
     * `host` o `host:puerto`, donde host es IPv4, un dominio o el nombre del
     * NAS en la LAN. Rechaza espacios, frases pegadas y puertos no numéricos:
     * lo que escriba el usuario en una TV tiene que convertirse en una URL que
     * OkHttp acepte.
     */
    private val HOST_PORT = Regex("^\\[?[A-Za-z0-9][A-Za-z0-9._-]*(:[0-9]{1,5})?\\]?$")

    /** `http://192.168.1.50:7000` → `192.168.1.50:7000`, para los mensajes. */
    fun hostLabel(baseUrl: String): String {
        val normalized = normalizeBase(baseUrl)
        return normalized
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .ifBlank { normalized }
    }

    /** `{base}/stream/{type}/{id}.json` — el endpoint que usa Stremio. */
    fun stream(baseUrl: String, type: String, id: String): String =
        "${normalizeBase(baseUrl)}/stream/${encodeSegment(type)}/${encodeSegment(id)}.json"

    fun manifest(baseUrl: String): String = "${normalizeBase(baseUrl)}/manifest.json"

    /** `{base}/catalogs.json` — qué filas publica el addon (extensión propia). */
    fun catalogs(baseUrl: String): String = "${normalizeBase(baseUrl)}/catalogs.json"

    /** `{base}/meta/{type}/{id}.json` — ficha con sinopsis y episodios. */
    fun meta(baseUrl: String, type: String, id: String): String =
        "${normalizeBase(baseUrl)}/meta/${encodeSegment(type)}/${encodeSegment(id)}.json"

    /**
     * `{base}/catalog/{type}/{id}.json` con los *extras* del protocolo Stremio.
     *
     * Ojo: Stremio los manda como **segmentos de path**, no como query string
     * (`/catalog/movie/top/search=matrix/skip=100.json`). Se respeta ese orden
     * porque es el que el servidor convierte en clave de caché.
     */
    fun catalog(
        baseUrl: String,
        type: String,
        id: String,
        search: String? = null,
        genre: String? = null,
        skip: Int? = null,
    ): String {
        val extras = buildList {
            if (!genre.isNullOrBlank()) add("genre=${encodeSegment(genre)}")
            if (skip != null && skip > 0) add("skip=$skip")
            if (!search.isNullOrBlank()) add("search=${encodeSegment(search)}")
        }
        val suffix = if (extras.isEmpty()) "" else "/" + extras.joinToString("/")
        return "${normalizeBase(baseUrl)}/catalog/${encodeSegment(type)}/${encodeSegment(id)}$suffix.json"
    }

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
