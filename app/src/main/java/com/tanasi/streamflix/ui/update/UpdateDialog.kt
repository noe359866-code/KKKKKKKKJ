package com.tanasi.streamflix.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.update.AppUpdateManager
import com.tanasi.streamflix.update.UpdateInfo
import kotlinx.coroutines.launch
import java.io.File

/** Diálogo de actualización autónoma con descarga directa del APK. */
@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember { AppUpdateManager() }

    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadLabel by remember { mutableStateOf("") }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    fun startDownload() {
        scope.launch {
            isDownloading = true
            downloadError = null
            downloadProgress = 0f
            downloadLabel = "Iniciando descarga..."

            val targetFile = File(context.cacheDir, "updates/${updateInfo.apkName}")
            try {
                updateManager.downloadApk(updateInfo.apkUrl, targetFile) { downloaded, total ->
                    if (total > 0) {
                        downloadProgress = downloaded.toFloat() / total.toFloat()
                        val downMb = downloaded / (1024 * 1024)
                        val totalMb = total / (1024 * 1024)
                        val percent = (downloadProgress * 100).toInt()
                        downloadLabel = "$downMb MB / $totalMb MB ($percent%)"
                    } else {
                        downloadProgress = 0.5f
                        downloadLabel = "${downloaded / (1024 * 1024)} MB descargados"
                    }
                }
                isDownloading = false
                downloadedFile = targetFile
                // Lanza automáticamente la pantalla del instalador
                updateManager.installApk(context, targetFile)
            } catch (e: Exception) {
                isDownloading = false
                downloadError = e.message ?: "Error al descargar la actualización"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Column {
                Text(
                    text = "Nueva versión disponible",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = StreamflixColors.OnSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${updateInfo.title} · v${updateInfo.latestVersion} (Actual: v${updateInfo.currentVersion})",
                    style = MaterialTheme.typography.labelMedium,
                    color = StreamflixColors.Primary,
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (updateInfo.changelog.isNotBlank()) {
                    Text(
                        text = "Novedades:",
                        style = MaterialTheme.typography.titleSmall,
                        color = StreamflixColors.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = updateInfo.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamflixColors.OnSurfaceMuted,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (isDownloading) {
                    Text(
                        text = "Descargando actualización autónoma...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StreamflixColors.OnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = StreamflixColors.Primary,
                        trackColor = Color.White.copy(alpha = 0.15f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = downloadLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = StreamflixColors.OnSurfaceMuted,
                    )
                }

                downloadError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF5252),
                    )
                }

                if (downloadedFile != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Descarga completa. Pulsa 'Instalar ahora' si el instalador no se abrió automáticamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF69F0AE),
                    )
                }
            }
        },
        confirmButton = {
            if (downloadedFile != null) {
                Button(onClick = { updateManager.installApk(context, downloadedFile!!) }) {
                    Text("Instalar ahora")
                }
            } else if (!isDownloading) {
                Button(onClick = { startDownload() }) {
                    Text("Actualizar ahora")
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text("Más tarde")
                }
            }
        },
    )
}
