package com.tanasi.streamflix.data.remote

import android.content.Context
import com.tanasi.streamflix.data.model.ExternalExtensionEntry
import com.tanasi.streamflix.data.model.ExtensionRepositoryKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Downloaded third-party package; this type does not imply that it was executed. */
data class InstalledExtensionPackage(
    val kind: ExtensionRepositoryKind,
    val entryId: String,
    val name: String,
    val filePath: String,
    val sha256: String,
    val sizeBytes: Int,
)

fun interface ExtensionPackageFetcher {
    suspend fun fetch(url: String, maxBytes: Int): ByteArray
}

fun interface ExtensionPackageStorage {
    fun save(kind: ExtensionRepositoryKind, id: String, extension: String, bytes: ByteArray): String
}

fun interface ExtensionPackageInstallOperation {
    suspend fun install(kind: ExtensionRepositoryKind, entry: ExternalExtensionEntry): Result<InstalledExtensionPackage>
}

/**
 * Downloads Nuvio JavaScript and Cloudstream .cs3 packages with strict size,
 * transport and checksum checks. It intentionally does not load or execute them.
 */
class ExternalExtensionPackageInstaller(
    private val fetcher: ExtensionPackageFetcher = HttpsExtensionPackageFetcher(),
    private val storage: ExtensionPackageStorage,
) : ExtensionPackageInstallOperation {
    override suspend fun install(
        kind: ExtensionRepositoryKind,
        entry: ExternalExtensionEntry,
    ): Result<InstalledExtensionPackage> = try {
        val url = entry.packageUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("La extensión no tiene URL de paquete.")
        val extension = packageExtension(kind, url)
        val maxBytes = when (kind) {
            ExtensionRepositoryKind.NUVIO -> MAX_NUVIO_BYTES
            ExtensionRepositoryKind.CLOUDSTREAM -> MAX_CLOUDSTREAM_BYTES
        }
        if (entry.packageSizeBytes != null && entry.packageSizeBytes > maxBytes) {
            throw IllegalArgumentException("El paquete supera el límite de tamaño permitido.")
        }
        val expected = entry.packageSha256?.let { normalizeSha256(it) }
        if (kind == ExtensionRepositoryKind.CLOUDSTREAM && expected == null) {
            throw IllegalArgumentException("Cloudstream no publicó un SHA-256 válido para esta extensión.")
        }
        val bytes = fetcher.fetch(url, maxBytes)
        if (bytes.isEmpty()) throw IllegalArgumentException("El paquete descargado está vacío.")
        if (bytes.size > maxBytes) throw IllegalArgumentException("El paquete supera el límite de tamaño permitido.")

        val digest = sha256(bytes)
        if (expected != null && !MessageDigest.isEqual(
                expected.toByteArray(Charsets.US_ASCII),
                digest.toByteArray(Charsets.US_ASCII),
            )
        ) {
            throw IllegalArgumentException("El SHA-256 del paquete no coincide con el índice.")
        }
        if (kind == ExtensionRepositoryKind.CLOUDSTREAM) validateCloudstreamPackage(bytes)

        val path = withContext(Dispatchers.IO) {
            storage.save(kind, entry.id, extension, bytes)
        }
        Result.success(
            InstalledExtensionPackage(
                kind = kind,
                entryId = entry.id,
                name = entry.name,
                filePath = path,
                sha256 = digest,
                sizeBytes = bytes.size,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    companion object {
        const val MAX_NUVIO_BYTES = 1 * 1024 * 1024
        const val MAX_CLOUDSTREAM_BYTES = 10 * 1024 * 1024

        fun normalizeSha256(value: String): String? {
            val trimmed = value.trim().lowercase()
            val hex = when {
                trimmed.startsWith("sha256-") -> trimmed.removePrefix("sha256-")
                trimmed.startsWith("sha256:") -> trimmed.removePrefix("sha256:")
                else -> trimmed
            }
            return hex.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
        }

        fun packageExtension(kind: ExtensionRepositoryKind, rawUrl: String): String {
            val path = runCatching { URI(rawUrl).path.orEmpty() }.getOrDefault("").lowercase()
            return when (kind) {
                ExtensionRepositoryKind.NUVIO -> {
                    require(path.endsWith(".js")) { "Un provider Nuvio debe apuntar a un fichero .js." }
                    "js"
                }
                ExtensionRepositoryKind.CLOUDSTREAM -> {
                    require(path.endsWith(".cs3")) { "Una extensión Cloudstream debe apuntar a un paquete .cs3." }
                    "cs3"
                }
            }
        }

        private fun validateCloudstreamPackage(bytes: ByteArray) {
            var hasDex = false
            var hasManifest = false
            var entries = 0
            var expandedBytes = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    require(entries <= MAX_ZIP_ENTRIES) { "El .cs3 contiene demasiados archivos." }
                    require(!entry.name.startsWith('/') && '\\' !in entry.name &&
                        entry.name.split('/').none { it == ".." }
                    ) { "El .cs3 contiene una ruta interna no válida." }

                    val dexHeader = ByteArray(112)
                    var dexHeaderSize = 0
                    var dexLength = 0L
                    val manifest = if (entry.name == "manifest.json") ByteArrayOutputStream() else null
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        expandedBytes += read
                        require(expandedBytes <= MAX_EXPANDED_PACKAGE_BYTES) {
                            "El contenido expandido del .cs3 supera el límite permitido."
                        }
                        if (entry.name == "classes.dex") {
                            dexLength += read
                            if (dexHeaderSize < dexHeader.size) {
                                val copy = minOf(read, dexHeader.size - dexHeaderSize)
                                buffer.copyInto(dexHeader, dexHeaderSize, 0, copy)
                                dexHeaderSize += copy
                            }
                        }
                        if (manifest != null) {
                            require(manifest.size().toLong() + read <= MAX_MANIFEST_BYTES.toLong()) {
                                "El manifest interno del .cs3 es demasiado grande."
                            }
                            manifest.write(buffer, 0, read)
                        }
                    }
                    if (entry.name == "classes.dex") {
                        val validMagic = dexHeaderSize == dexHeader.size &&
                            dexHeader[0] == 'd'.code.toByte() && dexHeader[1] == 'e'.code.toByte() &&
                            dexHeader[2] == 'x'.code.toByte() && dexHeader[3] == 10.toByte() &&
                            dexHeader[7] == 0.toByte()
                        val declaredSize = if (validMagic) readLittleEndianInt(dexHeader, 32) else -1
                        val headerSize = if (validMagic) readLittleEndianInt(dexHeader, 36) else -1
                        require(validMagic && declaredSize.toLong() == dexLength && headerSize == dexHeader.size) {
                            "El .cs3 no contiene un encabezado DEX válido."
                        }
                        hasDex = true
                    }
                    if (manifest != null) {
                        val manifestText = manifest.toByteArray().toString(Charsets.UTF_8)
                        runCatching { Json.parseToJsonElement(manifestText).jsonObject }
                            .getOrElse { throw IllegalArgumentException("El manifest interno del .cs3 no es JSON válido.") }
                        hasManifest = true
                    }
                    zip.closeEntry()
                }
            }
            require(hasDex && hasManifest) { "El .cs3 debe incluir classes.dex y manifest.json." }
        }

        private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

        private const val MAX_ZIP_ENTRIES = 512
        private const val MAX_EXPANDED_PACKAGE_BYTES = 32L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 64 * 1024

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** Private app storage; names are hashes so repository IDs cannot escape this directory. */
class FileExtensionPackageStorage(context: Context) : ExtensionPackageStorage {
    private val root = File(context.applicationContext.filesDir, "extension_packages")

    override fun save(kind: ExtensionRepositoryKind, id: String, extension: String, bytes: ByteArray): String {
        val directory = File(root, kind.name.lowercase()).apply { check(isDirectory || mkdirs()) }
        val key = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val destination = File(directory, "$key.$extension")
        val temporary = File(directory, "$key.$extension.part")
        temporary.writeBytes(bytes)
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            error("No se pudo reemplazar el paquete anterior.")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("No se pudo guardar el paquete descargado.")
        }
        if (kind == ExtensionRepositoryKind.CLOUDSTREAM && !destination.setReadOnly()) {
            destination.delete()
            error("No se pudo proteger el paquete Cloudstream descargado.")
        }
        return destination.absolutePath
    }
}

/** HTTPS-only fetcher which validates every redirect and caps streamed bytes. */
class HttpsExtensionPackageFetcher(
    private val http: OkHttpClient = defaultHttpClient(),
) : ExtensionPackageFetcher {
    override suspend fun fetch(url: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        var current = validateUrl(url)
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val request = Request.Builder()
                .url(current)
                .header("Accept", "application/javascript, application/zip, application/octet-stream, */*")
                .header("User-Agent", "Streamflix/1.1 (Extension Installer)")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code in REDIRECT_CODES) {
                    if (redirectIndex == MAX_REDIRECTS) throw IllegalArgumentException("Demasiadas redirecciones del paquete.")
                    val location = response.header("Location")
                        ?: throw IllegalArgumentException("Redirección sin destino.")
                    current = validateUrl(URI(current).resolve(location).toString())
                    return@use
                }
                if (!response.isSuccessful) {
                    throw IllegalArgumentException("El servidor del paquete respondió HTTP ${response.code}.")
                }
                val body = response.body ?: throw IllegalArgumentException("El paquete descargado está vacío.")
                if (body.contentLength() > maxBytes) throw IllegalArgumentException("El paquete supera el límite de tamaño permitido.")
                val output = ByteArrayOutputStream(minOf(maxBytes, body.contentLength().takeIf { it > 0 }?.toInt() ?: 8192))
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IllegalArgumentException("El paquete supera el límite de tamaño permitido.")
                        output.write(buffer, 0, read)
                    }
                }
                return@withContext output.toByteArray()
            }
        }
        throw IllegalArgumentException("No se pudo descargar el paquete.")
    }

    private fun validateUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("URL del paquete no válida.")
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme.equals("https", ignoreCase = true) && host.isNotBlank() && uri.userInfo == null) {
            "Los paquetes sólo se descargan desde URLs HTTPS sin credenciales."
        }
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") &&
            !host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) && ':' !in host
        ) { "No se permiten hosts locales ni direcciones IP directas." }
        return uri.toASCIIString()
    }

    companion object {
        private const val MAX_REDIRECTS = 4
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
