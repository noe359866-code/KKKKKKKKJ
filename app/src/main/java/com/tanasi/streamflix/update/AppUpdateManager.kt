package com.tanasi.streamflix.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.tanasi.streamflix.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    @SerialName("size") val size: Long = 0L,
    @SerialName("content_type") val contentType: String? = null,
)

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val title: String,
    val changelog: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
)

object VersionComparator {
    /** Devuelve true si [remote] es estrictamente más reciente que [current]. */
    fun isNewer(remote: String, current: String): Boolean {
        val rParts = parseVersion(remote)
        val cParts = parseVersion(current)
        if (rParts.isEmpty()) return false
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun parseVersion(version: String): List<Int> {
        val clean = version.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        return clean.split('.').mapNotNull { it.toIntOrNull() }
    }
}

/**
 * Gestor autónomo de actualizaciones directas desde GitHub Releases.
 * Sin depender de tiendas de aplicaciones ni servidores intermedios.
 */
class AppUpdateManager(
    private val http: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson(),
) {

    /**
     * Comprueba si hay una versión más reciente publicada en el repositorio.
     * Devuelve [UpdateInfo] si hay una actualización descargable, o null si ya está al día.
     */
    suspend fun checkForUpdate(
        currentVersion: String = BuildConfig.VERSION_NAME,
        repo: String = DEFAULT_REPO,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Streamflix-Updater/1.1")
            .get()
            .build()

        val body = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string().orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext null
        }

        if (body.isBlank()) return@withContext null

        val release = try {
            json.decodeFromString(GitHubRelease.serializer(), body)
        } catch (_: Exception) {
            return@withContext null
        }

        val remoteVersion = release.tagName.trim().removePrefix("v").removePrefix("V")
        if (!VersionComparator.isNewer(remoteVersion, currentVersion)) {
            return@withContext null
        }

        // Busca el asset de la APK
        val apkAsset = release.assets.firstOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType?.contains("package-archive", ignoreCase = true) == true
        } ?: return@withContext null

        UpdateInfo(
            currentVersion = currentVersion,
            latestVersion = remoteVersion,
            title = release.name?.takeIf { it.isNotBlank() } ?: "Versión $remoteVersion",
            changelog = release.body.orEmpty(),
            apkUrl = apkAsset.downloadUrl,
            apkName = apkAsset.name,
            apkSize = apkAsset.size,
        )
    }

    /**
     * Descarga el APK con seguimiento de progreso en bytes.
     */
    suspend fun downloadApk(
        apkUrl: String,
        destinationFile: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        destinationFile.parentFile?.mkdirs()
        if (destinationFile.exists()) destinationFile.delete()

        val request = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", "Streamflix-Updater/1.1")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Error descargando APK: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Cuerpo de respuesta vacío")
            val totalBytes = body.contentLength()

            destinationFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(downloaded, totalBytes)
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * Lanza el instalador del paquete de Android para aplicar la actualización.
     */
    fun installApk(context: Context, apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    companion object {
        const val DEFAULT_REPO = "noe359866-code/KKKKKKKKJ"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
