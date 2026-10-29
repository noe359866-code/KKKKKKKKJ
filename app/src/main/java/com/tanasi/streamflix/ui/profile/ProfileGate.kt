package com.tanasi.streamflix.ui.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.data.model.MAX_PROFILES
import com.tanasi.streamflix.data.model.Profile
import com.tanasi.streamflix.data.model.defaultProfileName
import com.tanasi.streamflix.ui.theme.ProfilePalette
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/**
 * Selector de perfil, "¿Quién está viendo?" al estilo de Apple TV.
 *
 * Se muestra al arrancar cuando hay más de un perfil y siempre que se elige
 * **Cambiar de perfil** en la barra superior. Tres decisiones de salón:
 *
 *  - **Avatares grandes y redondos** (128 dp) con las iniciales, no fotos: una
 *    foto de perfil no existe en una tele y pedirla con el mando sería peor.
 *  - **El foco es el anillo blanco + escala**, igual que en las tarjetas, para
 *    que el mando se sienta igual en todas las pantallas.
 *  - **Editar** muestra un "Eliminar" sobre cada avatar: no hay pulsación larga
 *    ni menús contextuales que un mando no puede invocar.
 *
 * @param onDismiss null en el arranque en frío (no se puede esquivar la
 *   elección); con valor, aparece "Cancelar" y se vuelve al perfil actual.
 */
@Composable
fun ProfileGate(
    profiles: List<Profile>,
    onSelect: (Profile) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (Profile) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }

    val canAdd = profiles.size < MAX_PROFILES

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StreamflixColors.Background),
    ) {
        /* Barra mínima: sólo aparece lo que el mando puede accionar. */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onDismiss != null) {
                GateChip(text = "Cancelar", onClick = onDismiss)
            }
            Spacer(Modifier.weight(1f))
            if (profiles.size > 1) {
                GateChip(
                    text = if (editing) "Listo" else "Editar",
                    onClick = { editing = !editing },
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "¿Quién está viendo?",
                style = MaterialTheme.typography.headlineMedium,
                color = StreamflixColors.OnSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Cada perfil guarda su propio \"Seguir viendo\".",
                style = MaterialTheme.typography.bodyMedium,
                color = StreamflixColors.OnSurfaceMuted,
            )

            Spacer(Modifier.height(44.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(items = profiles, key = { it.id }) { profile ->
                    ProfileTile(
                        profile = profile,
                        editing = editing,
                        onClick = { if (editing) onDelete(profile) else onSelect(profile) },
                    )
                }
                if (canAdd) {
                    item(key = "add") {
                        AddProfileTile(onClick = { editing = false; showCreate = true })
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
            Text(
                text = if (editing) {
                    "Elige un perfil para borrarlo."
                } else {
                    "Pulsa OK para entrar · perfil activo: ${profiles.firstOrNull()?.name ?: "—"}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = StreamflixColors.OnSurfaceMuted.copy(alpha = 0.7f),
            )
        }
    }

    if (showCreate) {
        ProfileNameDialog(
            title = "Nuevo perfil",
            initial = defaultProfileName(profiles),
            confirmLabel = "Crear",
            onConfirm = { name ->
                showCreate = false
                onCreate(name)
            },
            onDismiss = { showCreate = false },
        )
    }
}

/** Avatar circular con iniciales y degradado; es el elemento de identidad. */
@Composable
fun ProfileAvatar(
    profile: Profile,
    size: Dp,
    modifier: Modifier = Modifier,
    showInitials: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(ProfilePalette.brush(profile.colorIndex)),
        contentAlignment = Alignment.Center,
    ) {
        if (showInitials) {
            Text(
                text = profile.initials,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = if (size >= 96.dp) {
                    MaterialTheme.typography.headlineMedium
                } else {
                    MaterialTheme.typography.labelLarge
                },
            )
        }
    }
}

/** Avatar grande del selector, con nombre debajo y estado de edición. */
@Composable
private fun ProfileTile(
    profile: Profile,
    editing: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "profileRing",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "profileScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(200.dp),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .tvFocusGlow(
                    progress = ring,
                    cornerRadius = 64.dp,
                    color = if (editing) StreamflixColors.Live else StreamflixColors.FocusRing,
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(6.dp),
        ) {
            ProfileAvatar(
                profile = profile,
                size = 128.dp,
                modifier = if (focused) {
                    Modifier.border(2.dp, Color.White, CircleShape)
                } else {
                    Modifier
                },
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (editing) "Eliminar · ${profile.name}" else profile.name,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                editing -> StreamflixColors.Live
                focused -> StreamflixColors.OnSurface
                else -> StreamflixColors.OnSurfaceMuted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Última tarjeta de la fila: crear una persona nueva. */
@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "addRing",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "addScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(200.dp),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .tvFocusGlow(progress = ring, cornerRadius = 64.dp)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .background(StreamflixColors.SurfaceVariant)
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = if (focused) 0.6f else 0.22f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.displaySmall,
                    color = StreamflixColors.OnSurface,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Añadir perfil",
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) StreamflixColors.OnSurface else StreamflixColors.OnSurfaceMuted,
        )
    }
}

/** Píldora translúcida de la barra del selector (Cancelar / Editar). */
@Composable
fun GateChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "gateChip",
    )

    Box(
        modifier = modifier
            .tvFocusGlow(progress = ring, cornerRadius = 18.dp)
            .background(
                if (focused) StreamflixColors.GlassStrong else StreamflixColors.Glass,
                CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = StreamflixColors.OnSurface,
        )
    }
}

/**
 * Nombre del perfil.
 *
 * El teclado lo pone Android TV (el IME del sistema a pantalla completa), que es
 * mucho mejor que reinventarlo con una cuadrícula de letras.
 */
@Composable
private fun ProfileNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { fieldFocus.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.widthIn(min = 320.dp)) {
                Text("Escribe el nombre del perfil.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(20) },
                    singleLine = true,
                    label = { Text("Nombre") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onConfirm(value) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Círculo pequeño + nombre: la identidad activa en la barra superior. */
@Composable
fun ProfileBadge(
    profile: Profile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    focusDown: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "profileBadge",
    )

    Row(
        modifier = modifier
            .then(
                if (focusDown != null) {
                    Modifier.focusProperties { down = focusDown }
                } else {
                    Modifier
                },
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusGlow(progress = ring, cornerRadius = 20.dp)
            .background(
                if (focused) StreamflixColors.GlassStrong else StreamflixColors.Glass,
                CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(profile = profile, size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = profile.name,
            style = MaterialTheme.typography.labelLarge,
            color = StreamflixColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
