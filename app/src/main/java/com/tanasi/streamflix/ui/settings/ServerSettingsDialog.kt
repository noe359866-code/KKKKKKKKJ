package com.tanasi.streamflix.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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

/**
 * Ajustes de origen de contenido.
 *
 * Por defecto la APK es autónoma: scrapers Python embebidos, catálogo y
 * reproductor en el mismo paquete. El servidor Node externo queda como opción
 * avanzada para instalaciones que ya tengan su propio addon.
 */
@Composable
fun ServerSettingsDialog(
    settings: Settings,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var embedded by remember { mutableStateOf(settings.useEmbedded) }
    var value by remember { mutableStateOf(settings.baseUrl) }
    val normalized = Urls.normalizeBase(value)
    val isValid = remember(value) { Urls.isValidBase(value) }
    val canSave = embedded || isValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Origen del contenido") },
        text = {
            Column {
                Text(
                    text = "La app puede funcionar sola: catálogo, scrapers y reproductor están integrados. Sólo necesitas internet.",
                    color = StreamflixColors.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                ModeRow(
                    selected = embedded,
                    title = "Integrado (recomendado)",
                    subtitle = "Scrapers dentro de la APK. Sin servidor externo.",
                    onClick = { embedded = true },
                )
                ModeRow(
                    selected = !embedded,
                    title = "Servidor externo",
                    subtitle = "Addon Node propio en LAN o VPS.",
                    onClick = { embedded = false },
                )
                if (!embedded) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        isError = value.isNotBlank() && !isValid,
                        placeholder = { Text("http://192.168.1.50:7000") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (normalized != value.trim() && isValid) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Se guardará como $normalized",
                            color = StreamflixColors.OnSurfaceMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    settings.useEmbedded = embedded
                    if (!embedded) settings.baseUrl = Urls.normalizeBase(value)
                    onSaved()
                },
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun ModeRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = StreamflixColors.OnSurface)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = StreamflixColors.OnSurfaceMuted)
        }
    }
}
