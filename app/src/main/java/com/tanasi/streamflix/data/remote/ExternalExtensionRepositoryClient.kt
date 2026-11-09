package com.tanasi.streamflix.data.remote

import com.tanasi.streamflix.data.model.ExternalExtensionEntry
import com.tanasi.streamflix.data.model.ExternalExtensionRepository
import com.tanasi.streamflix.data.model.ExtensionRepositoryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/** Reads Cloudstream and Nuvio repository indexes as metadata only. */
fun interface ExternalExtensionRepositoryFetcher {
    suspend fun fetch(url: String, kind: ExtensionRepositoryKind): ExternalExtensionRepository
}

class ExternalExtensionRepositoryClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : ExternalExtensionRepositoryFetcher {
    override suspend fun fetch(url: String, kind: ExtensionRepositoryKind): ExternalExtensionRepository = withContext(Dispatchers.IO) {
        val normalized = validateHttpsUrl(url)
        val root = json.parseToJsonElement(get(normalized))
        when (kind) {
            ExtensionRepositoryKind.NUVIO -> parseNuvioIndex(normalized, root.jsonObject)
            ExtensionRepositoryKind.CLOUDSTREAM -> parseCloudstream(normalized, root.jsonObject)
        }
    }

    internal fun parseNuvioIndex(url: String, root: JsonObject): ExternalExtensionRepository {
        val scrapers = root["scrapers"] as? JsonArray
            ?: throw IllegalArgumentException("El manifest no contiene una lista Nuvio 'scrapers'.")
        val entries = scrapers.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("id") ?: return@mapNotNull null
            val name = item.string("name") ?: id
            ExternalExtensionEntry(
                id = id,
                name = name,
                description = item.string("description"),
                version = item.string("version"),
                language = item.arrayStrings("contentLanguage").joinToString(", ").ifBlank { null },
                supportedTypes = item.arrayStrings("supportedTypes"),
                packageUrl = (item.string("filename") ?: item.string("url"))?.let { resolve(url, it) },
                packageSha256 = item.string("sha256") ?: item.string("fileHash") ?: item.string("hash"),
                packageSizeBytes = item.long("fileSize"),
            )
        }
        if (entries.isEmpty()) throw IllegalArgumentException("No se encontraron providers válidos en el manifest Nuvio.")
        return ExternalExtensionRepository(
            kind = ExtensionRepositoryKind.NUVIO,
            url = url,
            name = root.string("name") ?: hostLabel(url),
            description = "Providers JavaScript de Nuvio para instalar y ejecutar con QuickJS.",
            itemCount = entries.size,
            entries = entries,
        )
    }

    private suspend fun parseCloudstream(url: String, root: JsonObject): ExternalExtensionRepository {
        val lists = root["pluginLists"] as? JsonArray
            ?: throw IllegalArgumentException("El archivo no parece un repo Cloudstream (falta 'pluginLists').")
        val listUrls = lists.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (listUrls.isEmpty()) throw IllegalArgumentException("El repo Cloudstream no declara índices de plugins.")
        val entries = listUrls.take(MAX_CLOUDSTREAM_LISTS).flatMap { listUrl ->
            val safeListUrl = validateHttpsUrl(listUrl)
            val payload = json.parseToJsonElement(get(safeListUrl))
            val rows = payload as? JsonArray ?: return@flatMap emptyList()
            parseCloudstreamPluginList(safeListUrl, rows)
        }.distinctBy { it.id }.take(MAX_ENTRIES)
        if (entries.isEmpty()) throw IllegalArgumentException("No se encontraron extensiones Cloudstream en sus pluginLists.")
        return ExternalExtensionRepository(
            kind = ExtensionRepositoryKind.CLOUDSTREAM,
            url = url,
            name = root.string("name") ?: hostLabel(url),
            description = root.string("description"),
            itemCount = entries.size,
            entries = entries,
        )
    }

    internal fun parseCloudstreamPluginList(url: String, rows: JsonArray): List<ExternalExtensionEntry> =
        rows.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string("internalName") ?: item.string("name") ?: return@mapNotNull null
            ExternalExtensionEntry(
                id = id,
                name = item.string("name") ?: id,
                description = item.string("description"),
                version = item.string("version"),
                language = item.string("language"),
                supportedTypes = item.arrayStrings("tvTypes"),
                packageUrl = item.string("url")?.let { resolve(url, it) },
                packageSha256 = item.string("fileHash") ?: item.string("sha256") ?: item.string("hash"),
                packageSizeBytes = item.long("fileSize"),
            )
        }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalArgumentException("El repositorio respondió HTTP ${response.code}.")
            val body = response.body ?: throw IllegalArgumentException("Índice vacío.")
            if (body.contentLength() > MAX_INDEX_BYTES) throw IllegalArgumentException("Índice demasiado grande.")
            body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_INDEX_BYTES) throw IllegalArgumentException("Índice demasiado grande.")
                    output.write(buffer, 0, read)
                }
                val text = output.toString(Charsets.UTF_8.name())
                if (text.isBlank()) throw IllegalArgumentException("Índice vacío.")
                return text
            }
        }
    }

    internal fun isSafeHttpsUrl(raw: String): Boolean = runCatching { validateHttpsUrl(raw) }.isSuccess

    private fun validateHttpsUrl(raw: String): String {
        val parsed = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("URL de repositorio no válida.")
        if (!parsed.scheme.equals("https", true) || parsed.host.isNullOrBlank() || parsed.userInfo != null) {
            throw IllegalArgumentException("Usa una URL HTTPS pública, sin usuario ni contraseña.")
        }
        val host = parsed.host.lowercase()
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            throw IllegalArgumentException("No se permiten hosts locales o direcciones IP directas.")
        }
        return parsed.toASCIIString()
    }

    private fun resolve(base: String, path: String): String? = runCatching {
        val result = URI(base).resolve(path)
        if (!result.scheme.equals("https", true)) null else result.toASCIIString()
    }.getOrNull()

    private fun hostLabel(url: String) = runCatching { URI(url).host }.getOrNull() ?: "Repositorio"
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.arrayStrings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    companion object {
        private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
        private const val MAX_CLOUDSTREAM_LISTS = 8
        private const val MAX_ENTRIES = 500

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }
}
