package com.tanasi.streamflix.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.asImageBitmap
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
import com.tanasi.streamflix.data.model.ExtensionRepositoryKind
import com.tanasi.streamflix.data.model.ExternalExtensionEntry
import com.tanasi.streamflix.data.remote.InstalledExtensionRecord
import com.tanasi.streamflix.data.phone.PhoneSetupChange
import com.tanasi.streamflix.data.phone.PhoneSetupEntry
import com.tanasi.streamflix.data.phone.PhoneSetupKind
import com.tanasi.streamflix.data.phone.PhoneSetupPreferences
import com.tanasi.streamflix.data.phone.PhoneSetupSession
import com.tanasi.streamflix.data.phone.PhoneSetupSnapshot
import com.tanasi.streamflix.data.phone.PhoneSetupSessionState
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

/** Ajustes TV-first: conexión, fuentes de catálogo, índices informativos y mantenimiento. */
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

    var selectedTab by remember { mutableIntStateOf(0) } // Conexión → Stremio → Índices → Actualizaciones
    val repositoryManager = remember { ServiceLocator.extensionRepositoryManager(context) }
    val packageManager = remember { ServiceLocator.extensionPackageManager(context) }
    val externalRepositories by repositoryManager.repositories.collectAsState()
    val installedPackages by packageManager.installed.collectAsState()
    val phoneSetupSession = remember(manager, repositoryManager) {
        PhoneSetupSession(context) {
            val entries = manager.getAddons().map { addon ->
                PhoneSetupEntry(PhoneSetupKind.STREMIO_ADDON, addon.manifestUrl, addon.name)
            } + repositoryManager.repositories.value.map { repository ->
                val kind = when (repository.kind) {
                    ExtensionRepositoryKind.NUVIO -> PhoneSetupKind.NUVIO_REPOSITORY
                    ExtensionRepositoryKind.CLOUDSTREAM -> PhoneSetupKind.CLOUDSTREAM_REPOSITORY
                }
                PhoneSetupEntry(kind, repository.url, repository.name)
            }
            PhoneSetupSnapshot(
                entries = entries,
                preferences = PhoneSetupPreferences(
                    autonomous = settings.autonomous,
                    metadataCatalogs = settings.metadataCatalogs,
                    autoCheckUpdates = settings.autoCheckUpdates,
                    baseUrl = settings.baseUrl,
                ),
            )
        }
    }
    val phoneSetupState by phoneSetupSession.state.collectAsState()
    var showPhoneSetup by remember { mutableStateOf(false) }
    var isApplyingPhoneChange by remember { mutableStateOf(false) }
    DisposableEffect(phoneSetupSession) {
        onDispose { phoneSetupSession.stop() }
    }
    var extensionRepositoryUrl by remember { mutableStateOf("") }
    var selectedRepositoryKind by remember { mutableStateOf(ExtensionRepositoryKind.NUVIO) }
    var isRepositoryLoading by remember { mutableStateOf(false) }
    var repositoryMessage by remember { mutableStateOf<String?>(null) }
    var isPackageLoading by remember { mutableStateOf(false) }
    var installingPackageKey by remember { mutableStateOf<String?>(null) }
    var packageMessage by remember { mutableStateOf<String?>(null) }
    var pendingExtensionEnable by remember { mutableStateOf<InstalledExtensionRecord?>(null) }
    val firstTabFocus = remember { FocusRequester() }
    val phoneSetupFocus = remember { FocusRequester() }
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
    var savedDraft by remember(settings) {
        mutableStateOf(SettingsDraft(settings.autonomous, settings.metadataCatalogs, settings.baseUrl, settings.autoCheckUpdates))
    }
    val draft = SettingsDraft(autonomous, metadata, value, autoUpdate)
    val isValid = draft.isValid
    var addonInputUrl by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }
    val hasChanges = draft.hasChangesFrom(savedDraft) || addonInputUrl.isNotBlank() || extensionRepositoryUrl.isNotBlank()
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
        if (hasChanges || isInstalling || isRepositoryLoading || isPackageLoading) pendingAction = action else finish(action)
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

    fun tryAddExtensionRepository() {
        if (extensionRepositoryUrl.isBlank() || isRepositoryLoading) return
        isRepositoryLoading = true
        repositoryMessage = null
        scope.launch {
            repositoryManager.addOrRefresh(extensionRepositoryUrl.trim(), selectedRepositoryKind).fold(
                onSuccess = { repository ->
                    extensionRepositoryUrl = ""
                    repositoryMessage = "Índice cargado: ${repository.name} (${repository.itemCount} extensiones)."
                    isRepositoryLoading = false
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    repositoryMessage = error.message ?: "No se pudo leer el índice."
                    isRepositoryLoading = false
                },
            )
        }
    }

    fun tryInstallExtension(kind: ExtensionRepositoryKind, entry: ExternalExtensionEntry) {
        if (isPackageLoading) return
        isPackageLoading = true
        installingPackageKey = "${kind.name}:${entry.id}"
        packageMessage = null
        scope.launch {
            packageManager.install(kind, entry).fold(
                onSuccess = { installed ->
                    isPackageLoading = false
                    installingPackageKey = null
                    packageMessage = if (kind == ExtensionRepositoryKind.CLOUDSTREAM) {
                        "${installed.entry.name} descargado; ZIP, DEX y SHA-256 verificados. Queda desactivado hasta que lo habilites."
                    } else {
                        "${installed.entry.name} descargado por HTTPS; su índice no publica checksum. Queda desactivado hasta que lo habilites."
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    isPackageLoading = false
                    installingPackageKey = null
                    packageMessage = error.message ?: "No se pudo instalar el paquete."
                },
            )
        }
    }

    fun changeExtensionEnabled(record: InstalledExtensionRecord, enabled: Boolean) {
        if (enabled) pendingExtensionEnable = record
        else packageManager.setEnabled(record.kind, record.entry.id, false)
    }

    fun openPhoneSetup() {
        showPhoneSetup = true
        scope.launch { phoneSetupSession.start() }
    }

    fun closePhoneSetup() {
        showPhoneSetup = false
        phoneSetupSession.stop()
    }

    fun applyPhoneChange(change: PhoneSetupChange) {
        if (isApplyingPhoneChange) return
        isApplyingPhoneChange = true
        scope.launch {
            var applied = 0
            var catalogChanged = false
            val failures = mutableListOf<String>()
            for (entry in change.added) {
                try {
                    when (entry.kind) {
                        PhoneSetupKind.STREMIO_ADDON -> {
                            manager.installAddon(entry.url).getOrThrow()
                            catalogChanged = true
                        }
                        PhoneSetupKind.NUVIO_REPOSITORY, PhoneSetupKind.CLOUDSTREAM_REPOSITORY -> {
                            val kind = if (entry.kind == PhoneSetupKind.NUVIO_REPOSITORY) {
                                ExtensionRepositoryKind.NUVIO
                            } else {
                                ExtensionRepositoryKind.CLOUDSTREAM
                            }
                            repositoryManager.addOrRefresh(entry.url, kind).getOrThrow()
                        }
                    }
                    applied++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures += "${entry.url}: ${error.message ?: "no se pudo añadir"}"
                }
            }
            for (entry in change.removed) {
                try {
                    when (entry.kind) {
                        PhoneSetupKind.STREMIO_ADDON -> {
                            val matches = manager.getAddons().filter { it.manifestUrl == entry.url }
                            matches.forEach { manager.removeAddon(it.id) }
                            if (matches.isNotEmpty()) catalogChanged = true
                        }
                        PhoneSetupKind.NUVIO_REPOSITORY -> repositoryManager.remove(ExtensionRepositoryKind.NUVIO, entry.url)
                        PhoneSetupKind.CLOUDSTREAM_REPOSITORY -> repositoryManager.remove(ExtensionRepositoryKind.CLOUDSTREAM, entry.url)
                    }
                    applied++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures += "${entry.url}: ${error.message ?: "no se pudo quitar"}"
                }
            }
            change.preferences?.let { preferences ->
                val normalizedBaseUrl = if (preferences.baseUrl.isBlank()) "" else Urls.normalizeBase(preferences.baseUrl)
                settings.autonomous = preferences.autonomous
                settings.metadataCatalogs = preferences.metadataCatalogs
                settings.autoCheckUpdates = preferences.autoCheckUpdates
                settings.baseUrl = normalizedBaseUrl
                autonomous = preferences.autonomous
                metadata = preferences.metadataCatalogs
                autoUpdate = preferences.autoCheckUpdates
                value = normalizedBaseUrl
                savedDraft = SettingsDraft(preferences.autonomous, preferences.metadataCatalogs, normalizedBaseUrl, preferences.autoCheckUpdates)
                catalogChanged = true
                applied++
            }
            if (catalogChanged) onAddonsChanged()
            isApplyingPhoneChange = false
            val message = if (failures.isEmpty()) {
                "$applied cambio${if (applied == 1) "" else "s"} aplicado${if (applied == 1) "" else "s"} en la TV."
            } else {
                "Aplicación parcial ($applied cambios). " + failures.take(2).joinToString("; ")
            }
            phoneSetupSession.confirmChange(change.id, message)
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
            // En pantallas bajas se reduce el cromado y se reserva altura al contenido desplazable.
            val compactSettings = maxHeight < 400.dp
            val panelPadding = if (compactSettings) 12.dp else 18.dp
            val sidebarWidth = (maxWidth * 0.28f).coerceIn(150.dp, 235.dp)
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Ajustes",
                            style = if (compactSettings) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                            color = StreamflixColors.OnSurface,
                        )
                        if (!compactSettings) {
                            Text(
                                "Fuentes, integraciones y mantenimiento",
                                style = MaterialTheme.typography.bodySmall,
                                color = StreamflixColors.OnSurfaceMuted,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { openPhoneSetup() },
                        modifier = Modifier
                            .focusRequester(phoneSetupFocus)
                            .focusProperties { down = firstTabFocus },
                    ) { Text(if (compactSettings) "Vincular · QR" else "Vincular teléfono · QR") }
                }
                Spacer(Modifier.height(if (compactSettings) 10.dp else 18.dp))
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        modifier = Modifier.width(sidebarWidth).fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "SECCIONES",
                            style = MaterialTheme.typography.labelSmall,
                            color = StreamflixColors.OnSurfaceMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                        )
                        val sections = listOf(
                            Triple("Conexión", "Motor local o servidor", null),
                            Triple("Stremio", "Addons y catálogos", installedAddons.size.toString()),
                            Triple("Índices", "Nuvio y Cloudstream", externalRepositories.size.toString()),
                            Triple("Actualizaciones", "Versión de la app", null),
                        )
                        sections.forEachIndexed { index, (title, subtitle, count) ->
                            TabButton(
                                text = title,
                                subtitle = subtitle,
                                badge = count,
                                selected = selectedTab == index,
                                focusRequester = if (index == 0) firstTabFocus else null,
                                upFocus = if (index == 0) phoneSetupFocus else null,
                                rightFocus = contentFocus,
                                onFocus = { selectedTab = index },
                                onClick = { selectedTab = index },
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.025f), RoundedCornerShape(20.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
                            .padding(panelPadding),
                    ) {
                        Text(
                            text = when (selectedTab) { 0 -> "Conexión"; 1 -> "Addons de Stremio"; 2 -> "Índices externos"; else -> "Actualizaciones" },
                            style = if (compactSettings) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            color = StreamflixColors.OnSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when (selectedTab) {
                                0 -> "Elige dónde se procesa el catálogo de Streamflix."
                                1 -> "Añade fuentes Stremio y controla cuáles están activas."
                                2 -> "Instala y administra providers Nuvio y extensiones Cloudstream."
                                else -> "Revisa la versión instalada y las actualizaciones disponibles."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamflixColors.OnSurfaceMuted,
                            maxLines = if (compactSettings) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(if (compactSettings) 8.dp else 14.dp))
                        key(selectedTab) {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                when (selectedTab) {
                                    0 -> {
                                        SettingsSectionHeading("Fuente de catálogos", "Elige dónde se consultan las películas, series y sus metadatos.")
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
                                            description = "Catálogos y metadatos del backend",
                                            selected = !autonomous,
                                            onClick = { autonomous = false },
                                        )
                                        if (!autonomous) {
                                            Text(
                                                text = "Este backend aporta catálogos y fichas, no fuentes de reproducción. Para reproducir, añade addons Stremio en la pestaña Stremio. Puedes volver al modo local en cualquier momento.",
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
                                        SettingsSectionHeading("Añadir un addon", "Conecta un manifest Stremio para sumar catálogos y fuentes de reproducción.")
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
                                            text = "Instalados · ${installedAddons.size}",
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
                                            text = "Sugeridos para empezar",
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
                                        Box(
                                            modifier = Modifier.fillMaxWidth()
                                                .background(Color(0xFFFFC857).copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                                                .border(1.dp, Color(0xFFFFC857).copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                                                .padding(14.dp),
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    "Extensiones de terceros",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFFFD779),
                                                )
                                                Text(
                                                    "Nuvio ejecuta JavaScript en QuickJS; Cloudstream carga .cs3 DEX mediante su API v4.7.0. Cloudstream no ofrece sandbox: al activarlo, el código se ejecuta dentro del proceso y con los permisos de Streamflix. Activa sólo paquetes de repositorios en los que confíes.",
                                                    color = StreamflixColors.OnSurfaceMuted,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(16.dp))
                                        SettingsSectionHeading("Añadir un índice", "Carga un repositorio Nuvio o Cloudstream para explorar e instalar extensiones.")
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SettingsChoice(
                                                title = "Nuvio",
                                                description = "manifest.json · JavaScript",
                                                selected = selectedRepositoryKind == ExtensionRepositoryKind.NUVIO,
                                                focusRequester = contentFocus,
                                                onClick = { selectedRepositoryKind = ExtensionRepositoryKind.NUVIO },
                                            )
                                            SettingsChoice(
                                                title = "Cloudstream",
                                                description = "repo.json · paquetes .cs3",
                                                selected = selectedRepositoryKind == ExtensionRepositoryKind.CLOUDSTREAM,
                                                onClick = { selectedRepositoryKind = ExtensionRepositoryKind.CLOUDSTREAM },
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = extensionRepositoryUrl,
                                            onValueChange = { extensionRepositoryUrl = it },
                                            singleLine = true,
                                            label = { Text("URL HTTPS del índice") },
                                            placeholder = { Text(if (selectedRepositoryKind == ExtensionRepositoryKind.NUVIO) "https://.../manifest.json" else "https://.../repo.json") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                            keyboardActions = KeyboardActions(onDone = { tryAddExtensionRepository() }),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(
                                            enabled = extensionRepositoryUrl.isNotBlank() && !isRepositoryLoading,
                                            onClick = { tryAddExtensionRepository() },
                                        ) {
                                            if (isRepositoryLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                            else Text("Cargar índice")
                                        }
                                        repositoryMessage?.let { message ->
                                            Spacer(Modifier.height(6.dp))
                                            Text(message, color = if (message.startsWith("Índice")) Color(0xFF69F0AE) else Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                                        }
                                        val packagesWithoutCurrentIndex = installedPackages.filter { packageRecord ->
                                            externalRepositories.none { repository ->
                                                repository.kind == packageRecord.kind && repository.entries.any { it.id == packageRecord.entry.id }
                                            }
                                        }
                                        if (packagesWithoutCurrentIndex.isNotEmpty()) {
                                            Spacer(Modifier.height(18.dp))
                                            SettingsSectionHeading("Paquetes instalados sin índice · ${packagesWithoutCurrentIndex.size}")
                                            packagesWithoutCurrentIndex.forEach { record ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text("${record.entry.name} · ${record.kind.name}", color = StreamflixColors.OnSurface, style = MaterialTheme.typography.bodyMedium)
                                                        Text(
                                                            if (record.kind == ExtensionRepositoryKind.CLOUDSTREAM) "API Cloudstream v4.7.0 · SHA-256 verificado" else "Provider Nuvio instalado",
                                                            color = StreamflixColors.OnSurfaceMuted,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    }
                                                    Switch(checked = record.enabled, onCheckedChange = { changeExtensionEnabled(record, it) })
                                                    TextButton(onClick = { packageManager.remove(record.kind, record.entry.id) }) { Text("Quitar") }
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(18.dp))
                                        SettingsSectionHeading("Índices guardados · ${externalRepositories.size}")
                                        if (externalRepositories.isEmpty()) {
                                            Text(
                                                "Aún no has añadido ningún índice. Cuando cargues uno, aparecerá aquí.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = StreamflixColors.OnSurfaceMuted,
                                                modifier = Modifier.padding(vertical = 8.dp),
                                            )
                                        } else externalRepositories.forEach { repository ->
                                            Column(
                                                Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
                                                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                                                    .padding(14.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Text("${repository.name} · ${repository.kind.name} · ${repository.itemCount} elementos", color = StreamflixColors.OnSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                                Text(repository.url, color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                repository.entries.forEach { extension ->
                                                    val installed = installedPackages.firstOrNull {
                                                        it.kind == repository.kind && it.entry.id == extension.id
                                                    }
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        Column(Modifier.weight(1f)) {
                                                            Text(
                                                                "${extension.name}${extension.version?.let { " v$it" }.orEmpty()}${extension.language?.let { " · $it" }.orEmpty()}",
                                                                color = StreamflixColors.OnSurfaceMuted,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                            if (installed != null) {
                                                                Text(
                                                                    if (repository.kind == ExtensionRepositoryKind.CLOUDSTREAM) "${if (installed.enabled) "Activo" else "Validado · desactivado"} · SHA-256 verificado" else "SHA-256 ${installed.sha256.take(12)}…",
                                                                    color = StreamflixColors.OnSurfaceMuted,
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                )
                                                            }
                                                        }
                                                        if (installed == null) {
                                                            val key = "${repository.kind.name}:${extension.id}"
                                                            OutlinedButton(
                                                                enabled = !isPackageLoading && extension.packageUrl != null,
                                                                onClick = { tryInstallExtension(repository.kind, extension) },
                                                            ) {
                                                                if (isPackageLoading && installingPackageKey == key) {
                                                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                                                } else Text("Descargar")
                                                            }
                                                        } else {
                                                            Switch(
                                                                checked = installed.enabled,
                                                                onCheckedChange = { changeExtensionEnabled(installed, it) },
                                                            )
                                                            TextButton(onClick = { packageManager.remove(installed.kind, installed.entry.id) }) {
                                                                Text("Quitar")
                                                            }
                                                        }
                                                    }
                                                }
                                                packageMessage?.let { Text(it, color = if (it.contains("verificado")) Color(0xFF69F0AE) else Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
                                                TextButton(onClick = { repositoryManager.remove(repository.kind, repository.url) }) { Text("Quitar índice") }
                                            }
                                        }
                                    }
                                    3 -> {
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
                        Spacer(Modifier.height(if (compactSettings) 6.dp else 12.dp))
                        Box(
                            Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)),
                        )
                        Spacer(Modifier.height(if (compactSettings) 6.dp else 10.dp))
                        Text(
                            text = when {
                                !isValid -> "Introduce una URL válida para el servidor."
                                addonInputUrl.isNotBlank() -> "Instala el addon o borra su URL antes de guardar."
                                isInstalling -> "Espera a que termine la instalación antes de guardar."
                                hasChanges -> "Cambios pendientes. Los addons e índices se aplican al instante."
                                else -> "Los addons e índices se aplican al instante."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!isValid || addonInputUrl.isNotBlank() || isInstalling) {
                                MaterialTheme.colorScheme.error
                            } else {
                                StreamflixColors.OnSurfaceMuted
                            },
                            maxLines = if (compactSettings) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(if (compactSettings) 4.dp else 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                enabled = draft.canSave(addonInputUrl, isInstalling),
                                onClick = {
                                    settings.baseUrl = draft.normalizedBaseUrl()
                                    settings.metadataCatalogs = metadata
                                    settings.autonomous = autonomous
                                    settings.autoCheckUpdates = autoUpdate
                                    onSaved()
                                },
                            ) { Text("Guardar cambios") }
                            OutlinedButton(onClick = { requestExit(SettingsExit.Close) }) { Text("Cerrar") }
                        }
                    }
                }
            }
        }
    }

    pendingExtensionEnable?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingExtensionEnable = null },
            title = { Text(if (record.kind == ExtensionRepositoryKind.CLOUDSTREAM) "Activar extensión Cloudstream" else "Activar provider Nuvio") },
            text = {
                Text(
                    if (record.kind == ExtensionRepositoryKind.CLOUDSTREAM) {
                        "Un paquete .cs3 contiene código DEX de terceros. Se ejecutará dentro del proceso de Streamflix con los permisos de la app; Android no proporciona un sandbox para este tipo de plugin. Sólo continúa si confías en su autor y repositorio."
                    } else {
                        "Este JavaScript se ejecutará en QuickJS sin acceso directo a clases Java, pero el host le permite hacer peticiones de red y analizar HTML. Sólo continúa si confías en el autor y repositorio."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    packageManager.setEnabled(record.kind, record.entry.id, true)
                    pendingExtensionEnable = null
                }) { Text("Entiendo y activar") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExtensionEnable = null }) { Text("Cancelar") }
            },
        )
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
    if (showPhoneSetup) {
        PhoneSetupDialog(
            state = phoneSetupState,
            isApplying = isApplyingPhoneChange,
            onDismiss = { closePhoneSetup() },
            onConfirm = { change -> applyPhoneChange(change) },
            onReject = { change -> phoneSetupSession.rejectChange(change.id) },
        )
    }
}

@Composable
private fun PhoneSetupDialog(
    state: PhoneSetupSessionState,
    isApplying: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PhoneSetupChange) -> Unit,
    onReject: (PhoneSetupChange) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(StreamflixColors.Background.copy(alpha = 0.98f)).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val qrSize = (maxHeight * 0.44f).coerceIn(160.dp, 270.dp)
            Column(
                Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.94f)
                    .verticalScroll(rememberScrollState())
                    .background(StreamflixColors.Surface, RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Text("Gestionar desde el teléfono", style = MaterialTheme.typography.headlineSmall, color = StreamflixColors.OnSurface)
                Spacer(Modifier.height(14.dp))
                when {
                    state.isStarting -> {
                        CircularProgressIndicator(color = StreamflixColors.Primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Preparando la conexión local…", color = StreamflixColors.OnSurfaceMuted)
                    }
                    state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                    state.qrBitmap != null -> {
                        Image(
                            bitmap = state.qrBitmap.asImageBitmap(),
                            contentDescription = "Código QR para abrir Streamflix en el teléfono",
                            modifier = Modifier.size(qrSize).background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(state.serverUrl.orEmpty(), color = StreamflixColors.OnSurface, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Escanea el QR desde un teléfono conectado a la misma red. Los cambios se aplican sólo después de confirmarlos aquí.",
                            color = StreamflixColors.OnSurfaceMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("La sesión se cierra automáticamente en 10 minutos.", color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
                        state.lastResult?.let { result ->
                            Spacer(Modifier.height(8.dp))
                            Text(result, color = Color(0xFF69F0AE), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                OutlinedButton(onClick = onDismiss) { Text("Cerrar sesión QR") }
            }
        }
    }

    state.pendingChange?.let { change ->
        AlertDialog(
            onDismissRequest = { if (!isApplying) onReject(change) },
            title = { Text("¿Aprobar cambios del teléfono?") },
            text = {
                Column(Modifier.height(220.dp).verticalScroll(rememberScrollState())) {
                    Text("Revisa la propuesta. La TV no modifica la configuración hasta que la apruebes.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    change.preferences?.let { preferences ->
                        Text("Configuración general", color = StreamflixColors.OnSurface, fontWeight = FontWeight.Bold)
                        Text("Conexión: ${if (preferences.autonomous) "en este dispositivo" else "servidor externo"}", color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        if (!preferences.autonomous) Text("Backend: ${preferences.baseUrl}", color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        Text("Catálogos de Cinemeta: ${if (preferences.metadataCatalogs) "activados" else "desactivados"}", color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        Text("Buscar actualizaciones al iniciar: ${if (preferences.autoCheckUpdates) "activado" else "desactivado"}", color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                    }
                    change.added.forEach { entry ->
                        Text("Añadir · ${phoneSetupLabel(entry.kind)}", color = Color(0xFF69F0AE), fontWeight = FontWeight.SemiBold)
                        Text(entry.name.ifBlank { entry.url }, color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        if (entry.name.isNotBlank()) Text(entry.url, color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(7.dp))
                    }
                    change.removed.forEach { entry ->
                        Text("Quitar · ${phoneSetupLabel(entry.kind)}", color = Color(0xFFFF8A80), fontWeight = FontWeight.SemiBold)
                        Text(entry.name.ifBlank { entry.url }, color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        if (entry.name.isNotBlank()) Text(entry.url, color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(7.dp))
                    }
                    if (change.added.any { it.kind != PhoneSetupKind.STREMIO_ADDON }) {
                        Text("Los providers Nuvio se ejecutan en QuickJS y las extensiones Cloudstream .cs3 se ejecutan con la API v4.7.0. Cloudstream no está aislado del proceso de Streamflix.", color = StreamflixColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(enabled = !isApplying, onClick = { onConfirm(change) }) {
                    if (isApplying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Aprobar y aplicar")
                }
            },
            dismissButton = { TextButton(enabled = !isApplying, onClick = { onReject(change) }) { Text("Rechazar") } },
        )
    }
}

private fun phoneSetupLabel(kind: PhoneSetupKind): String = when (kind) {
    PhoneSetupKind.STREMIO_ADDON -> "addon Stremio"
    PhoneSetupKind.NUVIO_REPOSITORY -> "índice Nuvio y providers JavaScript"
    PhoneSetupKind.CLOUDSTREAM_REPOSITORY -> "repositorio Cloudstream"
}

private enum class SettingsExit { Close, Update }

@Composable
private fun SettingsSectionHeading(text: String, description: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier.width(3.dp).height(19.dp)
                    .background(StreamflixColors.Primary, RoundedCornerShape(50)),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = StreamflixColors.OnSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        }
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = StreamflixColors.OnSurfaceMuted,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    subtitle: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    rightFocus: FocusRequester,
    upFocus: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(120),
        label = "settingsTabFocus",
    )
    val shape = RoundedCornerShape(14.dp)
    val foreground = if (focused) Color.Black else StreamflixColors.OnSurface
    val muted = if (focused) Color.Black.copy(alpha = 0.65f) else StreamflixColors.OnSurfaceMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties {
                right = rightFocus
                if (upFocus != null) up = upFocus
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics { this.selected = selected; role = Role.Tab }
            .tvFocusGlow(progress = focusProgress, cornerRadius = 14.dp)
            .background(
                when {
                    focused -> Color.White
                    selected -> StreamflixColors.GlassStrong
                    else -> Color.White.copy(alpha = 0.035f)
                },
                shape,
            )
            .border(
                width = 1.dp,
                color = when {
                    focused -> Color.White
                    selected -> StreamflixColors.Primary.copy(alpha = 0.56f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
                shape = shape,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .background(
                    if (focused || selected) StreamflixColors.Primary else Color.Transparent,
                    RoundedCornerShape(50),
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = text,
                color = foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(
                        if (focused) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.09f),
                        RoundedCornerShape(50),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = badge,
                    color = foreground,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
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
            .tvFocusGlow(if (focused) 1f else 0f, cornerRadius = 14.dp)
            .background(
                when {
                    focused -> Color.White
                    selected -> StreamflixColors.GlassStrong
                    else -> StreamflixColors.Glass
                },
                RoundedCornerShape(14.dp),
            )
            .border(
                1.dp,
                when {
                    focused -> Color.White
                    selected -> StreamflixColors.Primary.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.07f)
                },
                RoundedCornerShape(14.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (focused) Color.Black else StreamflixColors.OnSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = if (focused) Color.Black.copy(alpha = 0.68f) else StreamflixColors.OnSurfaceMuted)
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
            .tvFocusGlow(if (focused) 1f else 0f, cornerRadius = 14.dp)
            .background(
                if (focused) Color.White else StreamflixColors.Glass,
                RoundedCornerShape(14.dp),
            )
            .border(
                1.dp,
                if (focused) Color.White else Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(14.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (focused) Color.Black else StreamflixColors.OnSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = if (focused) Color.Black.copy(alpha = 0.68f) else StreamflixColors.OnSurfaceMuted)
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
            .background(Color.White.copy(alpha = 0.055f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .padding(16.dp),
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
            .background(Color.White.copy(alpha = 0.035f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(14.dp),
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
