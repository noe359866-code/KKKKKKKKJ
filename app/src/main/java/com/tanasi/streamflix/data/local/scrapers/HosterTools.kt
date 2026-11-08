package com.tanasi.streamflix.data.local.scrapers

import androidx.annotation.Keep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.util.Locale

/** Clasificación y rutas comunes de servidores de vídeo durante la migración. */
@Keep
class HosterTools {
    private fun authority(url: String): String = runCatching {
        URI(url).rawAuthority.orEmpty().lowercase(Locale.ROOT)
    }.getOrDefault("")

    fun label(url: String): String {
        val host = authority(url)
        for ((hint, value) in HOSTER_LABELS) if (hint in host) return value
        if (host.isEmpty()) return "Servidor"
        return host.replace("www.", "").substringBefore('.').replaceFirstChar { it.uppercase(Locale.ROOT) }
    }

    fun isDownloadOnly(url: String): Boolean = DOWNLOAD_ONLY.any { it in authority(url) }
    fun isKnownGood(url: String): Boolean = KNOWN_GOOD.any { it in authority(url) }

    fun mediaTypeOfResponse(contentType: String): String {
        val text = contentType.lowercase(Locale.ROOT)
        return when {
            "mpegurl" in text || "m3u8" in text -> "hls"
            "dash" in text || "mpd" in text -> "dash"
            "video/" in text -> "mp4"
            "text/html" in text || "application/xhtml" in text -> "html"
            else -> ""
        }
    }

    /** Clasifica los primeros bytes; nunca descarga el vídeo. Entrada hex para el puente JNI. */
    fun classifyProbe(hex: String): String {
        if (hex.length % 2 != 0) return ""
        val bytes = try { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
                    catch (_: IllegalArgumentException) { return "" }
        val text = String(bytes, Charsets.ISO_8859_1)
        val stripped = text.trimStart { it in "\u00ef\u00bb\u00bf \t\r\n" }
        val lower = text.take(512).lowercase(Locale.ROOT)
        return when {
            stripped.take(7).uppercase(Locale.ROOT) == "#EXTM3U" -> "hls"
            "<MPD" in text || (stripped.take(64).contains("<?xml") && "MPD" in text.take(512)) -> "dash"
            bytes.size >= 8 && text.substring(4, 8) == "ftyp" -> "mp4"
            "<!doctype" in lower || "<html" in lower -> "html"
            else -> ""
        }
    }

    /** Extrae las URL construidas por Streamtape a partir de su marcador y token. */
    fun streamtapeJson(html: String): String {
        val token = STREAMTAPE_TOKEN.find(html)?.groupValues?.get(1)
        val urls = STREAMTAPE_ID.findAll(html).mapNotNull { match ->
            // Python aplica strip() y después strip de comillas, en ese orden.
            val fragment = match.groupValues[1].trim().trim('\'', '"')
            if (fragment.isEmpty()) null else {
                val url = if (fragment.startsWith("//")) "https:$fragment" else "https://$fragment"
                if (token != null) "$url&token=$token&dl=1s" else url
            }
        }.toList()
        return JsonArray(urls.map(::JsonPrimitive)).toString()
    }

    /** Devuelve una cadena vacía si la estructura requiere el comportamiento Python. */
    fun okruJson(html: String): String {
        val urls = mutableListOf<String>()
        for (match in OKRU_OPTIONS.findAll(html)) {
            val rawOptions = match.groupValues[1].replace("&quot;", "\"")
            // Python admite NaN y otros casos particulares. Si no es JSON
            // estricto, usar el parser anterior en lugar de perder señales.
            if (!AnimeScraperTools.StrictJson(rawOptions).valid()) return ""
            val options = try { Json.parseToJsonElement(rawOptions) }
                catch (_: IllegalArgumentException) { return "" }
            val optionObject = options as? JsonObject ?: continue
            val flashvars = optionObject["flashvars"] ?: JsonObject(emptyMap())
            val flashObject = flashvars as? JsonObject ?: continue
            val rawMetadata = flashObject["metadata"] ?: JsonPrimitive("{}")
            val metadataString = (rawMetadata as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (!AnimeScraperTools.StrictJson(metadataString).valid()) return ""
            val metadata = try { Json.parseToJsonElement(metadataString) }
                catch (_: IllegalArgumentException) { return "" }
            // El parser anterior lanza si metadata no es un objeto: dejarlo decidir.
            val objectMetadata = metadata as? JsonObject ?: return ""
            for (key in listOf("hlsMasterPlaylistUrl", "hlsManifestUrl", "ondemandHls", "rtmpUrl")) {
                val value = objectMetadata[key] as? JsonPrimitive ?: continue
                if (value.isString && value.content.startsWith("http")) urls.add(value.content)
            }
        }
        return JsonArray(urls.map(::JsonPrimitive)).toString()
    }

    fun manifestVariantsJson(url: String): String = variants(url, listOf("/m3u8/", "/hls/"))
    fun embedVariantsJson(url: String): String = variants(url, listOf("/e/", "/embed/", "/v/"))

    private fun variants(url: String, prefixes: List<String>): String {
        // urllib acepta algunos caracteres que URI rechaza. El puente Python
        // conserva su parser para esos casos en lugar de perder servidores.
        val uri = try { URI(url) } catch (_: Exception) { return "" }
        val path = uri.rawPath.orEmpty()
        // urllib separa los parámetros de la última parte de la ruta.
        val last = path.substringAfterLast('/')
        val params = if (';' in last) ";" + last.substringAfter(';') else ""
        val pathWithoutParams = if (params.isNotEmpty()) path.dropLast(params.length) else path
        val identifier = Regex("/play/([A-Za-z0-9._-]+)$").find(pathWithoutParams)?.groupValues?.get(1)
            ?: return "[]"
        val root = buildString {
            if (uri.scheme != null) append(uri.scheme.lowercase(Locale.ROOT)).append(':')
            if (uri.rawAuthority != null) append("//").append(uri.rawAuthority)
        }
        val suffix = params + (uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "") +
            (uri.rawFragment?.takeIf { it.isNotEmpty() }?.let { "#$it" } ?: "")
        val result = listOf("", "e-", "embed-", "v-").flatMap { idPrefix ->
            prefixes.map { prefix -> "$root$prefix$idPrefix$identifier$suffix" }
        }.filter { it != url }.distinct()
        return JsonArray(result.map { JsonPrimitive(it) }).toString()
    }

    private companion object {
        val STREAMTAPE_ID = Regex("""id\s*=\s*["']ideoooolink["'][^>]*>([^<]+)<""", RegexOption.IGNORE_CASE)
        val STREAMTAPE_TOKEN = Regex("""token\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val OKRU_OPTIONS = Regex("""data-module\s*=\s*["']OKVideo["'][^>]*data-options\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val HOSTER_LABELS = listOf(
            "streamwish" to "Streamwish", "dhcplay" to "Streamwish", "filelions" to "Filelions",
            "filemoon" to "Filemoon", "bysesukior" to "Filemoon", "vidhide" to "Vidhide",
            "movearnpre" to "Vidhide", "voe" to "VOE", "luluvdo" to "Lulustream",
            "lulustream" to "Lulustream", "mixdrop" to "Mixdrop", "miixdrop" to "Mixdrop",
            "streamtape" to "Streamtape", "strcloud" to "Streamtape", "mp4upload" to "Mp4upload",
            "yourupload" to "YourUpload", "uqload" to "Uqload", "okru" to "Okru",
            "dood" to "Doodstream", "ds2play" to "Doodstream", "hqq" to "HQQ", "pdrain" to "PDrain",
            "hls" to "HLS", "mega.nz" to "Mega", "mediafire" to "Mediafire", "1fichier" to "1fichier",
            "burstcloud" to "Burstcloud", "hydrax" to "Hydrax", "obeywish" to "Obeywish",
        )
        val DOWNLOAD_ONLY = listOf("mega.nz", "mediafire", "1fichier", "zippyshare", "drive.google", "youtube.com", "youtu.be", "facebook.com", "instagram.com", "jkplayers")
        val KNOWN_GOOD = listOf("streamwish", "filemoon", "vidhide", "voe", "streamtape", "mixdrop", "mp4upload", "okru", "filelions", "lulustream")
    }
}
