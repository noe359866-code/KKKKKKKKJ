package com.tanasi.streamflix.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.data.remote.Urls
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.util.Settings

/** El modo autónomo no pide URL, claves ni instalaciones adicionales. */
@Composable
fun ServerSettingsDialog(
    settings: Settings,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var autonomous by remember { mutableStateOf(settings.autonomous) }
    var metadata by remember { mutableStateOf(settings.metadataCatalogs) }
    var value by remember { mutableStateOf(settings.baseUrl) }
    val isValid = autonomous || Urls.isValidBase(value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Funcionamiento de la app") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = autonomous, onClick = { autonomous = true })
                    Text("Autónomo · En este dispositivo")
                }
                Text(
                    text = "Los scrapers y Python vienen en el APK. La TV consulta los proveedores y resuelve los enlaces directamente, sin Node.js ni un servidor propio.",
                    color = StreamflixColors.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Necesita Internet y que las páginas de origen estén disponibles. Guarda catálogos y una caché corta de enlaces en el dispositivo; no descarga los videos.",
                    color = StreamflixColors.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (autonomous) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = metadata, onCheckedChange = { metadata = it })
                        Text("Añadir catálogos de Cinemeta (opcional)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !autonomous, onClick = { autonomous = false })
                    Text("Servidor externo · Opcional")
                }
                if (!autonomous) {
                    Text(
                        text = "Sólo para quien prefiera ejecutar el backend server/ por separado. Puedes volver al modo autónomo en cualquier momento.",
                        color = StreamflixColors.OnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        isError = value.isNotBlank() && !Urls.isValidBase(value),
                        label = { Text("URL del addon") },
                        placeholder = { Text("http://192.168.1.50:7000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    settings.baseUrl = if (value.isBlank()) "" else Urls.normalizeBase(value)
                    settings.metadataCatalogs = metadata
                    settings.autonomous = autonomous
                    onSaved()
                },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
