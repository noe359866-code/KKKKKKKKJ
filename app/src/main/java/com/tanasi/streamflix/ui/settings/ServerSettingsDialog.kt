package com.tanasi.streamflix.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import com.tanasi.streamflix.BuildConfig
import com.tanasi.streamflix.core.LocalDeviceProfile
import com.tanasi.streamflix.data.model.StremioAddon
import com.tanasi.streamflix.data.remote.Urls
import com.tanasi.streamflix.data.stremio.StremioAddonManager
import com.tanasi.streamflix.di.ServiceLocator
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow
import com.tanasi.streamflix.update.AppUpdateManager
import com.tanasi.streamflix.update.UpdateInfo
import com.tanasi.streamflix.util.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Diálogo de configuración: modo de conexión, addons Stremio y actualizaciones autónomas. */
@Composable
fun ServerSettingsDialog(
    settings: Settings,
    addonManager: StremioAddonManager? = null,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onAddonsChanged: () -> Unit = {},
    onUpdateFound: ((UpdateInfo) -> Unit)? = null,
) {
    val context = LocalContext.current
    val manager = addonManager ?: remember { ServiceLocator.stremioAddonManager(context) }
    val scope = rememberCoroutineScope()
    val updateManager = remember { AppUpdateManager() }
    val deviceProfile = LocalDeviceProfile.current

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Conexión, 1: Addons, 2: Actualizaciones
    val firstTabFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val keepEditingFocus = remember { FocusRequester() }
    val cancelDeleteFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { firstTabFocus.requestFocus() }
    }

    var autonomous by remember { mutableStateOf(settings.autonomous) }
    var metadata by remember { mutableStateOf(settings.metadataCatalogs) }
    var value by remember { mutableStateOf(settings.baseUrl) }
    var autoUpdate by remember { mutableStateOf(settings.autoCheckUpdates) }
    val savedDraft = remember(settings) {
        SettingsDraft(settings.autonomous, settings.metadataCatalogs, settings.baseUrl, settings.autoCheckUpdates)
    }
    val draft = SettingsDraft(autonomous, metadata, value, autoUpdate)
    val isValid = draft.isValid
    var addonInputUrl by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }
    val hasChanges = draft.hasChangesFrom(savedDraft) || addonInputUrl.isNotBlank()
    var pendingDelete by remember { mutableStateOf<StremioAddon?>(null) }
    var pendingAction by remember { mutableStateOf<SettingsExit?>(null) }
    var foundUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    fun finish(action: SettingsExit) {
        when (action) {
            SettingsExit.Close -> onDismiss()
            SettingsExit.Update -> foundUpdate?.let { onUpdateFound?.invoke(it) }
        }
    }

    fun requestExit(action: SettingsExit) {
        if (hasChanges || isInstalling) pendingAction = action else finish(action)
    }

    val installedAddons by manager.addons.collectAsState()

    var installError by remember { mutableStateOf<String?>(null) }
    var installSuccess by remember { mutableStateOf<String?>(null) }

    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updateCheckMessage by remember { mutableStateOf<String?>(null) }

    fun tryInstall(url: String) {
        if (url.isBlank() || isInstalling) return
        isInstalling = true
        scope.launch {
            installError = null
            installSuccess = null
            manager.installAddon(url).fold(
                onSuccess = { added ->
                    isInstalling = false
                    addonInputUrl = ""
                    installSuccess = "Addon '${added.name}' instalado correctamente"
                    onAddonsChanged()
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    isInstalling = false
                    installError = error.message ?: "No se pudo conectar o parsear el manifest"
                },
            )
        }
    }

    fun checkUpdatesNow() {
        if (isCheckingUpdates) return
        isCheckingUpdates = true
        foundUpdate = null
        scope.launch {
            updateCheckMessage = null
            try {
                val update = updateManager.checkForUpdate()
                isCheckingUpdates = false
                if (update != null) {
                    foundUpdate = update
                    updateCheckMessage = "Versión disponible: ${update.latestVersion}."
                } else {
                    // El comprobador devuelve null tanto para fallos como para
                    // publicaciones sin APK o sin versión más reciente.
                    updateCheckMessage = "No se encontró una actualización descargable o no se pudo consultar GitHub."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isCheckingUpdates = false
                updateCheckMessage = "Error al consultar releases: ${e.message}"
            }
        }
    }

    Dialog(
        onDismissRequest = { requestExit(SettingsExit.Close) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(StreamflixColors.Background)
                .padding(
                    horizontal = deviceProfile.horizontalPaddingDp.dp,
                    vertical = deviceProfile.verticalPaddingDp.dp,
                ),
        ) {
            // En pantallas pequeñas se conserva una zona de contenido útil.
            val sidebarWidth = (maxWidth * 0.28f).coerceIn(150.dp, 235.dp)
            Column(Modifier.fillMaxSize()) {
                Text("Ajustes", style = MaterialTheme.typography.headlineSmall, color = StreamflixColors.OnSurface)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(
                        modifier = Modifier.width(sidebarWidth).fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("Conexión", "Addons (${installedAddons.size})", "Actualizaciones").forEachIndexed { index, title ->
                            TabButton(
                                text = title,
                                selected = selectedTab == index,
                                focusRequester = if (index == 0) firstTabFocus else null,
                                rightFocus = contentFocus,
                                onFocus = { selectedTab = index },
                                onClick = { selectedTab = index },
                            )
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text(
                            text = when (selectedTab) { 0 -> "Conexión"; 1 -> "Addons"; else -> "Actualizaciones" },
                            style = MaterialTheme.typography.titleLarge,
                            color = StreamflixColors.OnSurface,
                        )
                        Spacer(Modifier.height(12.dp))
                        key(selectedTab) {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                when (selectedTab) {
                                    0 -> {
                                        SettingsChoice(
                                            title = "En este dispositivo",
                                            description = "Modo autónomo",
                                            selected = autonomous,
                                            focusRequester = contentFocus,
                                            onClick = { autonomous = true },
                                        )
                                        Text(
                                            text = "El contenido se consulta desde este dispositivo. No necesitas configurar un servidor propio.",
                                            color = StreamflixColors.OnSurfaceMuted,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "Los catálogos y la caché se guardan en el dispositivo.",
                                            color = StreamflixColors.OnSurfaceMuted,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        if (autonomous) {
                                            SettingsToggle(
                                                title = "Catálogos de Cinemeta",
                                                description = "Usar catálogos de metadatos en modo autónomo",
                                                checked = metadata,
                                                onCheckedChange = { metadata = it },
                                            )
                                        }
                                        Spacer(Modifier.height(12.dp))
                                        SettingsChoice(
                                            title = "Servidor externo",
                                            description = "Conectar al backend opcional",
                                            selected = !autonomous,
                                            onClick = { autonomous = false },
                                        )
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
                                            modifier = Modifier.fillMaxWidth().focusRequester(contentFocus),
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
                                                        onToggle = { enabled ->
                                                            manager.setAddonEnabled(addon.id, enabled)
                                                            onAddonsChanged()
                                                        },
                                                        onDelete = { pendingDelete = addon },
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
                                                    isInstalling = isInstalling,
                                                    onInstall = { tryInstall(preset.manifestUrl) },
                                                )
                                            }
                                        }
                                    }
                                    2 -> {
                                        Text(
                                            text = "Consulta las versiones publicadas en GitHub Releases. La instalación requiere confirmación de Android.",
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

                                        SettingsToggle(
                                            title = "Buscar actualizaciones al iniciar",
                                            description = "Comprueba GitHub al abrir la app; no instala automáticamente",
                                            checked = autoUpdate,
                                            focusRequester = contentFocus,
                                            onCheckedChange = { autoUpdate = it },
                                        )

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

                                        foundUpdate?.let { update ->
                                            if (onUpdateFound != null) {
                                                Spacer(Modifier.height(8.dp))
                                                OutlinedButton(onClick = { requestExit(SettingsExit.Update) }) {
                                                    Text("Ver actualización ${update.latestVersion}")
                                                }
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
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when {
                                !isValid -> "Introduce una URL válida para el servidor."
                                addonInputUrl.isNotBlank() -> "Instala el addon o borra su URL antes de guardar."
                                isInstalling -> "Espera a que termine la instalación antes de guardar."
                                hasChanges -> "Hay cambios sin guardar. Los addons se aplican al instante."
                                else -> "Los addons se aplican al instante."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isValid) StreamflixColors.OnSurfaceMuted else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                enabled = draft.canSave(addonInputUrl, isInstalling),
                                onClick = {
                                    settings.baseUrl = draft.normalizedBaseUrl()
                                    settings.metadataCatalogs = metadata
                                    settings.autonomous = autonomous
                                    settings.autoCheckUpdates = autoUpdate
                                    onSaved()
                                },
                            ) { Text("Guardar") }
                            OutlinedButton(onClick = { requestExit(SettingsExit.Close) }) { Text("Cerrar") }
                        }
                    }
                }
            }
        }
    }

    pendingAction?.let { action ->
        LaunchedEffect(action) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { keepEditingFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("¿Descartar cambios?") },
            text = { Text("Los cambios de conexión y actualización y la URL de addon sin instalar se descartarán. Los cambios en addons ya se aplicaron. Si hay una instalación en curso se intentará cancelar.") },
            confirmButton = {
                TextButton(onClick = { pendingAction = null; finish(action) }) { Text("Descartar") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }, modifier = Modifier.focusRequester(keepEditingFocus)) { Text("Seguir editando") } },
        )
    }
    pendingDelete?.let { addon ->
        LaunchedEffect(addon.id) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { cancelDeleteFocus.requestFocus() }
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("¿Eliminar addon?") },
            text = { Text("Se eliminará ${addon.name} de este dispositivo.") },
            confirmButton = {
                TextButton(onClick = {
                    manager.removeAddon(addon.id)
                    pendingDelete = null
                    onAddonsChanged()
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }, modifier = Modifier.focusRequester(cancelDeleteFocus)) { Text("Cancelar") } },
        )
    }
}

private enum class SettingsExit { Close, Update }

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    rightFocus: FocusRequester,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(120),
        label = "settingsTabFocus",
    )
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { right = rightFocus }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics { this.selected = selected; role = Role.Tab }
            .tvFocusGlow(progress = focusProgress, cornerRadius = 12.dp)
            .background(
                when {
                    focused -> Color.White
                    selected -> StreamflixColors.GlassStrong
                    else -> Color.White.copy(alpha = 0.04f)
                },
                shape,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else if (selected) Color.White else StreamflixColors.OnSurfaceMuted,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Una fila seleccionable, con foco y selección tratados como estados distintos. */
@Composable
private fun SettingsChoice(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusGlow(if (focused) 1f else 0f, cornerRadius = 12.dp)
            .background(if (focused) StreamflixColors.GlassStrong else StreamflixColors.Glass, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = StreamflixColors.OnSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = StreamflixColors.OnSurfaceMuted)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

/** La fila completa recibe foco; el interruptor refleja el valor actual. */
@Composable
private fun SettingsToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusGlow(if (focused) 1f else 0f, cornerRadius = 12.dp)
            .background(if (focused) StreamflixColors.GlassStrong else StreamflixColors.Glass, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = StreamflixColors.OnSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = StreamflixColors.OnSurfaceMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
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
    isInstalling: Boolean,
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
                OutlinedButton(enabled = !isInstalling, onClick = onInstall) {
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
