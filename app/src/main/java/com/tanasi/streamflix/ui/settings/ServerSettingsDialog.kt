package com.tanasi.streamflix.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.BuildConfig
import com.tanasi.streamflix.data.model.StremioAddon
import com.tanasi.streamflix.data.remote.Urls
import com.tanasi.streamflix.data.stremio.StremioAddonManager
import com.tanasi.streamflix.di.ServiceLocator
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.update.AppUpdateManager
import com.tanasi.streamflix.update.UpdateInfo
import com.tanasi.streamflix.util.Settings
import kotlinx.coroutines.launch

/** Diálogo de configuración: modo de conexión, addons Stremio y actualizaciones autónomas. */
@Composable
fun ServerSettingsDialog(
    settings: Settings,
    addonManager: StremioAddonManager? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onUpdateFound: ((UpdateInfo) -> Unit)? = null,
) {
    val context = LocalContext.current
    val manager = addonManager ?: remember { ServiceLocator.stremioAddonManager(context) }
    val scope = rememberCoroutineScope()
    val updateManager = remember { AppUpdateManager() }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Conexión, 1: Addons, 2: Actualizaciones

    var autonomous by remember { mutableStateOf(settings.autonomous) }
    var metadata by remember { mutableStateOf(settings.metadataCatalogs) }
    var value by remember { mutableStateOf(settings.baseUrl) }
    var autoUpdate by remember { mutableStateOf(settings.autoCheckUpdates) }
    val isValid = autonomous || Urls.isValidBase(value)

    val installedAddons by manager.addons.collectAsState()

    var addonInputUrl by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var installSuccess by remember { mutableStateOf<String?>(null) }

    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updateCheckMessage by remember { mutableStateOf<String?>(null) }

    fun tryInstall(url: String) {
        if (url.isBlank()) return
        scope.launch {
            isInstalling = true
            installError = null
            installSuccess = null
            manager.installAddon(url).fold(
                onSuccess = { added ->
                    isInstalling = false
                    addonInputUrl = ""
                    installSuccess = "Addon '${added.name}' instalado correctamente"
                },
                onFailure = { error ->
                    isInstalling = false
                    installError = error.message ?: "No se pudo conectar o parsear el manifest"
                },
            )
        }
    }

    fun checkUpdatesNow() {
        scope.launch {
            isCheckingUpdates = true
            updateCheckMessage = null
            try {
                val update = updateManager.checkForUpdate()
                isCheckingUpdates = false
                if (update != null) {
                    onUpdateFound?.invoke(update)
                } else {
                    updateCheckMessage = "Tu aplicación está actualizada a la última versión (v${BuildConfig.VERSION_NAME})."
                }
            } catch (e: Exception) {
                isCheckingUpdates = false
                updateCheckMessage = "Error al consultar releases: ${e.message}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "Ajustes de Streamflix", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabButton(
                        text = "Conexión",
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                    )
                    TabButton(
                        text = "Addons (${installedAddons.size})",
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                    )
                    TabButton(
                        text = "Actualizaciones",
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (selectedTab) {
                    0 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = autonomous, onClick = { autonomous = true })
                            Text("100% Autónomo · En este dispositivo")
                        }
                        Text(
                            text = "Los 12 scrapers y Python vienen en el APK. El dispositivo consulta los sitios y resuelve los enlaces directamente, sin Node.js, sin servidores propios ni intermediarios.",
                            color = StreamflixColors.OnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Guarda catálogos y caché en el dispositivo; los videos se reproducen en streaming directo mediante Media3 / ExoPlayer.",
                            color = StreamflixColors.OnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (autonomous) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = metadata, onCheckedChange = { metadata = it })
                                Text("Catálogos de Cinemeta (recomendado)", style = MaterialTheme.typography.bodyMedium)
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
                                label = { Text("URL del backend") },
                                placeholder = { Text("http://192.168.1.50:7000") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    1 -> {
                        Text(
                            text = "Añade addons compatibles con Stremio v3 mediante su URL de manifest. Sus catálogos y streams se integrarán directamente en la app.",
                            color = StreamflixColors.OnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = addonInputUrl,
                            onValueChange = { addonInputUrl = it },
                            singleLine = true,
                            label = { Text("URL de manifest del addon") },
                            placeholder = { Text("https://.../manifest.json o stremio://...") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { tryInstall(addonInputUrl) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isInstalling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = StreamflixColors.Primary,
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Button(
                                enabled = addonInputUrl.isNotBlank() && !isInstalling,
                                onClick = { tryInstall(addonInputUrl) },
                            ) {
                                Text("Instalar addon")
                            }
                        }

                        installError?.let { err ->
                            Spacer(Modifier.height(6.dp))
                            Text(text = err, color = Color(0xFFFF5252), style = MaterialTheme.typography.bodySmall)
                        }
                        installSuccess?.let { msg ->
                            Spacer(Modifier.height(6.dp))
                            Text(text = msg, color = Color(0xFF69F0AE), style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Addons instalados",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StreamflixColors.OnSurface,
                        )
                        Spacer(Modifier.height(8.dp))

                        if (installedAddons.isEmpty()) {
                            Text(
                                text = "No tienes addons de Stremio instalados.",
                                color = StreamflixColors.OnSurfaceMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                installedAddons.forEach { addon ->
                                    AddonItemCard(
                                        addon = addon,
                                        onToggle = { enabled -> manager.setAddonEnabled(addon.id, enabled) },
                                        onDelete = { manager.removeAddon(addon.id) },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Addons sugeridos (1 clic)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StreamflixColors.OnSurface,
                        )
                        Spacer(Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            manager.presets().forEach { preset ->
                                val alreadyInstalled = installedAddons.any {
                                    it.manifestUrl == preset.manifestUrl || it.id.equals(preset.name, ignoreCase = true)
                                }
                                PresetItemCard(
                                    preset = preset,
                                    isInstalled = alreadyInstalled,
                                    onInstall = { tryInstall(preset.manifestUrl) },
                                )
                            }
                        }
                    }
                    2 -> {
                        Text(
                            text = "Streamflix se actualiza de manera 100% autónoma descargando los APKs firmados directamente desde GitHub Releases.",
                            color = StreamflixColors.OnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                        ) {
                            Column {
                                Text(
                                    text = "Versión instalada: v${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StreamflixColors.OnSurface,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Código de versión: ${BuildConfig.VERSION_CODE} · Canal: GitHub Releases",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StreamflixColors.OnSurfaceMuted,
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Actualizar automáticamente",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StreamflixColors.OnSurface,
                                )
                                Text(
                                    text = "Busca nuevas versiones al abrir la app",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StreamflixColors.OnSurfaceMuted,
                                )
                            }
                            Switch(
                                checked = autoUpdate,
                                onCheckedChange = { autoUpdate = it },
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                enabled = !isCheckingUpdates,
                                onClick = { checkUpdatesNow() },
                            ) {
                                Text("Buscar actualizaciones ahora")
                            }
                            if (isCheckingUpdates) {
                                Spacer(Modifier.width(12.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = StreamflixColors.Primary,
                                )
                            }
                        }

                        updateCheckMessage?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (msg.startsWith("Error")) Color(0xFFFF5252) else Color(0xFF69F0AE),
                            )
                        }
                    }
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
                    settings.autoCheckUpdates = autoUpdate
                    onSaved()
                },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                color = if (selected) StreamflixColors.Primary.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = if (selected) StreamflixColors.Primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = if (selected) StreamflixColors.Primary else StreamflixColors.OnSurfaceMuted,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun AddonItemCard(
    addon: StremioAddon,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = addon.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = StreamflixColors.OnSurface,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "v${addon.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StreamflixColors.OnSurfaceMuted,
                        )
                    }
                    addon.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamflixColors.OnSurfaceMuted,
                            maxLines = 2,
                        )
                    }
                }
                Switch(
                    checked = addon.enabled,
                    onCheckedChange = onToggle,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (addon.catalogs.isNotEmpty()) {
                        AddonBadge(text = "Catálogos: ${addon.catalogs.size}")
                    }
                    if (addon.supportsResource("stream")) {
                        AddonBadge(text = "Streams")
                    }
                    if (addon.supportsResource("meta")) {
                        AddonBadge(text = "Metas")
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(text = "Eliminar", color = Color(0xFFFF6E6E), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PresetItemCard(
    preset: com.tanasi.streamflix.data.model.StremioPreset,
    isInstalled: Boolean,
    onInstall: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = StreamflixColors.OnSurface,
                )
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = StreamflixColors.OnSurfaceMuted,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isInstalled || preset.bundled) {
                /* Cinemeta ya está dentro del motor local: se ofrece por si el
                 * usuario prefiere gestionarlo como addon, pero no hace falta
                 * instalarlo para ver sus catálogos. */
                Text(
                    text = if (isInstalled) "Instalado" else "Incluido",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF69F0AE),
                )
            } else {
                OutlinedButton(onClick = onInstall) {
                    Text("Instalar")
                }
            }
        }
    }
}

@Composable
private fun AddonBadge(text: String) {
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = StreamflixColors.OnSurfaceMuted,
        )
    }
}
