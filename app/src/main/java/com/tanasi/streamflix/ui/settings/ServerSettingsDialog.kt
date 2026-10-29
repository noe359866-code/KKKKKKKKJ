package com.tanasi.streamflix.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.data.remote.Urls
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.util.Settings

/**
 * Editor de la URL del addon.
 *
 * Existe porque el backend corre en la red local y cada casa tiene otra IP: es
 * lo primero que hay que tocar al instalar el APK en un TV box.
 *
 * Acepta `192.168.1.50:7000` y añade el esquema, pero rechaza lo que no sea
 * http(s) para no dejar el cliente apuntando a `file://` o a basura.
 */
@Composable
fun ServerSettingsDialog(
    settings: Settings,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var value by remember { mutableStateOf(settings.baseUrl) }
    val normalized = Urls.normalizeBase(value)
    val isValid = remember(value) { Urls.isValidBase(value) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servidor del addon") },
        text = {
            Column {
                Text(
                    text = "URL de la instancia Node (server/). En el emulador, 10.0.2.2 apunta a tu equipo.",
                    color = StreamflixColors.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    settings.baseUrl = Urls.normalizeBase(value)
                    onSaved()
                },
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
