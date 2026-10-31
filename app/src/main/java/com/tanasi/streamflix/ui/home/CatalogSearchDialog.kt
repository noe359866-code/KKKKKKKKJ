package com.tanasi.streamflix.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tanasi.streamflix.core.LocalDeviceProfile
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.ui.components.PosterImage
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow
import kotlinx.coroutines.delay

/**
 * Medidas del teclado.
 *
 * Un panel de 1080p informa **960 × 540 dp** en Android TV (densidad 2), no
 * 1920 × 1080: el presupuesto real de alto es de ~540 dp, no de 720. Con teclas
 * de 60 dp el teclado se salía de la pantalla y `BUSCAR` quedaba cortado, así
 * que se calcula para 540 dp de alto: 48 dp de tecla y 6 dp de separación.
 */
private val KEY_SIZE = 48.dp

private val KEY_GAP = 6.dp

/** Ancho total del teclado: lo comparten el campo de texto y el panel derecho. */
private val KEYBOARD_WIDTH = KEY_SIZE * 10 + KEY_GAP * 9

/**
 * Letras y dígitos de un **teclado físico** (los TV box con dongle suelen
 * tenerlo). Se declaran tecla a tecla en lugar de leer el carácter del evento:
 * así no depende de APIs de plataforma y el mapeo es evidente.
 */
private val PHYSICAL_KEYS: Map<Key, String> = buildMap {
    listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I,
        Key.J, Key.K, Key.L, Key.M, Key.N, Key.O, Key.P, Key.Q, Key.R,
        Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    ).forEachIndexed { index, key -> put(key, ('A' + index).toString()) }

    listOf(
        Key.Zero, Key.One, Key.Two, Key.Three, Key.Four,
        Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    ).forEachIndexed { index, key -> put(key, index.toString()) }
}

/**
 * Buscador del Home: **teclado propio** y, a la derecha, lo que hay que mirar
 * mientras se escribe.
 *
 * Antes esto era un `AlertDialog` con un `OutlinedTextField`: en una TV eso
 * significa abrir el teclado del sistema, navegarlo con el mando y tapar media
 * pantalla. Aquí el mando **es** el teclado —cada tecla es un nodo enfocable— y
 * el resto del panel se aprovecha para algo útil.
 *
 * El panel derecho tiene tres caras, en este orden de prioridad:
 *
 *  1. **resultados en vivo** de la consulta (con retardo, ver
 *     `HomeViewModel.preview`): así no hay que pulsar `BUSCAR` para saber si la
 *     búsqueda existe;
 *  2. **búsquedas anteriores** cuando el campo está vacío: repetir la del día
 *     anterior con el mando no debería costar veinte pulsaciones;
 *  3. las **recomendaciones de Cinemeta** ya cargadas, para entrar a una ficha
 *     sin escribir nada.
 *
 * Lenguaje visual (Apple TV): teclas redondeadas de vidrio que se vuelven
 * blancas al enfocarlas, campo de consulta grande con cursor, y las listas a la
 * derecha con el mismo foco de anillo que el resto de la app. Sin `TextField`,
 * sin IME, sin nada táctil.
 *
 * @param suggestions recomendaciones ya resueltas (`CinemetaSuggestions`).
 * @param onOpenDetails abre la ficha del título elegido y cierra el buscador.
 * @param preview resultado de la búsqueda en vivo para [initialQuery] y sus cambios.
 * @param onPreview se llama con cada cambio del campo (el ViewModel aplica el retardo).
 * @param recentSearches historial, lo más reciente primero.
 * @param onForgetSearches borra el historial entero desde el panel.
 */
@Composable
fun CatalogSearchDialog(
    initialQuery: String,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    suggestions: List<MediaEntry> = emptyList(),
    onOpenDetails: (MediaEntry) -> Unit = {},
    preview: SearchPreview = SearchPreview.Idle,
    onPreview: (String) -> Unit = {},
    onClearPreview: () -> Unit = {},
    recentSearches: List<String> = emptyList(),
    onForgetSearches: () -> Unit = {},
) {
    var value by remember { mutableStateOf(initialQuery.take(TvKeyboardLayout.MAX_QUERY)) }
    var page by remember { mutableStateOf(TvKeyboardLayout.SymbolPage.DIGITS) }
    val deviceProfile = LocalDeviceProfile.current
    val firstKeyFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val canSearch = value.isNotBlank()

    fun submit(text: String = value) {
        if (text.isNotBlank()) onSearch(TvKeyboardLayout.normalize(text))
    }

    /* Cada cambio del campo se anuncia al ViewModel, que espera a que el
     * usuario deje de teclear. Sin esto habría una carga de catálogo por letra
     * (el repositorio cachea por consulta, no por prefijo). */
    LaunchedEffect(value) { onPreview(value) }

    /* Al cerrar, la vista previa no sirve para nada: si se quedara viva, el
     * siguiente `BUSCAR` vacío pintaría resultados de una consulta anterior. */
    DisposableEffect(Unit) { onDispose { onClearPreview() } }

    /* El foco tiene que empezar en una tecla: si el diálogo abre sin foco, el
     * primer `▼` del usuario no hace nada y parece colgado. */
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { firstKeyFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0C0C0E), Color(0xFF000000)),
                    ),
                )
                /* Un teclado físico (muchos TV box lo tienen) escribe sin pasar
                 * por las teclas: es gratis y evita tener que recorrer 27 nodos. */
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key == Key.Backspace) {
                        value = TvKeyboardLayout.backspace(value)
                        return@onPreviewKeyEvent true
                    }
                    /* Enter en un teclado físico busca sin bajar hasta `BUSCAR`. */
                    if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                        submit()
                        return@onPreviewKeyEvent true
                    }
                    val typed = PHYSICAL_KEYS[event.key] ?: return@onPreviewKeyEvent false
                    value = TvKeyboardLayout.type(value, typed)
                    true
                }
                .focusable(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = deviceProfile.horizontalPaddingDp.dp,
                        end = deviceProfile.horizontalPaddingDp.dp,
                        top = 28.dp,
                        bottom = 24.dp,
                    ),
            ) {
                Text(
                    text = "BUSCAR",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = StreamflixColors.OnSurfaceMuted,
                )

                Spacer(Modifier.height(14.dp))

                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    /* --- teclado ------------------------------------------- */
                    Column(
                        modifier = Modifier.width(KEYBOARD_WIDTH),
                        verticalArrangement = Arrangement.spacedBy(KEY_GAP),
                    ) {
                        QueryField(value = value)

                        TvKeyboardLayout.rows.forEachIndexed { rowIndex, keys ->
                            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                                keys.forEachIndexed { keyIndex, key ->
                                    KeyCap(
                                        label = key,
                                        width = KEY_SIZE,
                                        /* Sólo el borde derecho de cada fila salta
                                         * al panel: si todas las teclas apuntaran
                                         * allí, `▶` nunca movería dentro del teclado. */
                                        rightFocus = if (keyIndex == keys.lastIndex && rowIndex != TvKeyboardLayout.rows.lastIndex) {
                                            panelFocus
                                        } else {
                                            null
                                        },
                                        focusRequester = if (rowIndex == 0 && keyIndex == 0) firstKeyFocus else null,
                                        onClick = { value = TvKeyboardLayout.type(value, key) },
                                    )
                                }
                                /* La última fila es más corta: borrar vive ahí,
                                 * donde el pulgar de la mirada ya está. */
                                if (rowIndex == TvKeyboardLayout.rows.lastIndex) {
                                    KeyCap(
                                        label = "BORRAR",
                                        width = 150.dp,
                                        rightFocus = panelFocus,
                                        onClick = { value = TvKeyboardLayout.backspace(value) },
                                    )
                                }
                            }
                        }

                        /* Símbolos: `123` y `ÁÉÍ` comparten fila. Los acentos no
                         * cabían como fila extra (540 dp de alto), pero un título
                         * escrito con tilde tiene que poder buscarse desde el
                         * mando. La tecla que conmuta es un interruptor: dice a
                         * dónde lleva, no dónde está. */
                        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                            val symbols = TvKeyboardLayout.symbolRow(page)
                            symbols.forEachIndexed { index, key ->
                                KeyCap(
                                    label = key,
                                    width = KEY_SIZE,
                                    rightFocus = if (index == symbols.lastIndex) panelFocus else null,
                                    onClick = { value = TvKeyboardLayout.type(value, key) },
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                            KeyCap(
                                label = "ESPACIO",
                                width = 210.dp,
                                onClick = { value = TvKeyboardLayout.type(value, " ") },
                            )
                            /* 210 + 6 + 108 + 6 + 150 = 480 dp: la misma fila,
                             * sin mover nada de sitio. */
                            KeyCap(
                                label = TvKeyboardLayout.toggleLabel(page),
                                width = 108.dp,
                                onClick = { page = TvKeyboardLayout.toggle(page) },
                            )
                            PrimaryKey(
                                label = "BUSCAR",
                                width = 150.dp,
                                enabled = canSearch,
                                rightFocus = panelFocus,
                                onClick = { submit() },
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = if (canSearch) {
                                "OK escribe · BUSCAR busca «${TvKeyboardLayout.normalize(value)}» · ATRÁS cierra"
                            } else {
                                "${TvKeyboardLayout.toggleLabel(page)} cambia los símbolos · ATRÁS cierra"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = StreamflixColors.OnSurfaceMuted.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(KEYBOARD_WIDTH),
                        )
                    }

                    Spacer(Modifier.width(40.dp))

                    /* --- resultados / historial / recomendaciones ---------- */
                    ResultsPanel(
                        query = value,
                        preview = preview,
                        suggestions = suggestions,
                        recentSearches = recentSearches,
                        focusRequester = panelFocus,
                        onOpenDetails = onOpenDetails,
                        onUseRecent = { query -> value = query; submit(query) },
                        onForgetSearches = onForgetSearches,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------- //
// Panel derecho
// ------------------------------------------------------------------------- //

@Composable
private fun ResultsPanel(
    query: String,
    preview: SearchPreview,
    suggestions: List<MediaEntry>,
    recentSearches: List<String>,
    focusRequester: FocusRequester,
    onOpenDetails: (MediaEntry) -> Unit,
    onUseRecent: (String) -> Unit,
    onForgetSearches: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(query, preview, suggestions, recentSearches) {
        searchPanelItems(query, preview, suggestions, recentSearches)
    }
    /* El foco tiene que caer en la primera fila **pulsable**; los encabezados
     * no son enfocables y el `▶` desde el teclado no debe quedarse en el aire. */
    val firstFocusable = firstFocusableIndex(items)

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(
                items,
                key = { index, item ->
                    when (item) {
                        is SearchPanelItem.Entry -> "$index:${item.entry.type}:${item.entry.id}"
                        is SearchPanelItem.Recent -> "$index:recent:${item.query}"
                        is SearchPanelItem.Header -> "$index:header:${item.title}"
                        SearchPanelItem.ForgetRecent -> "$index:forget"
                        SearchPanelItem.Searching -> "$index:searching"
                        is SearchPanelItem.Note -> "$index:note"
                    }
                },
            ) { index, item ->
                val getsFocus = index == firstFocusable
                when (item) {
                    is SearchPanelItem.Header -> SectionHeader(item.title, item.hint)
                    is SearchPanelItem.Entry -> SuggestionRow(
                        entry = item.entry,
                        focusRequester = if (getsFocus) focusRequester else null,
                        onClick = { onOpenDetails(item.entry) },
                    )
                    is SearchPanelItem.Recent -> RecentRow(
                        query = item.query,
                        focusRequester = if (getsFocus) focusRequester else null,
                        onClick = { onUseRecent(item.query) },
                    )
                    SearchPanelItem.ForgetRecent -> ForgetRow(
                        focusRequester = if (getsFocus) focusRequester else null,
                        onClick = onForgetSearches,
                    )
                    SearchPanelItem.Searching -> NoteRow("Buscando en el catálogo…")
                    is SearchPanelItem.Note -> NoteRow(item.text)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, hint: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            color = StreamflixColors.OnSurfaceMuted,
        )
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = StreamflixColors.OnSurfaceMuted.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoteRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = StreamflixColors.OnSurfaceMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/**
 * Una búsqueda anterior.
 *
 * Es una fila entera y no una "chip" porque el mando no tiene puntero: la zona
 * enfocable tiene que ser grande. Se distingue de un título por el glifo y por
 * el color —no hay carátula que enseñar—, y la flecha dice que la pulsación
 * repite la búsqueda.
 */
@Composable
private fun RecentRow(
    query: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val appearance = rememberRowAppearance(focusedRingLabel = "recentRing", focusedScaleLabel = "recentScale")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = appearance.scale
                scaleY = appearance.scale
            }
            .tvFocusGlow(progress = appearance.ring, cornerRadius = 14.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(
                if (appearance.focused) StreamflixColors.GlassStrong else StreamflixColors.Glass,
                RoundedCornerShape(14.dp),
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (appearance.focused) 0.22f else 0.08f),
                shape = RoundedCornerShape(14.dp),
            )
            .onFocusChanged(appearance.onFocusChanged)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "↺",
            style = MaterialTheme.typography.titleMedium,
            color = StreamflixColors.OnSurfaceMuted,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = query,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = if (appearance.focused) Color.White else StreamflixColors.OnSurfaceMuted.copy(alpha = 0.6f),
        )
    }
}

/** "Borrar historial": la única acción destructiva, y pequeña a propósito. */
@Composable
private fun ForgetRow(
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "forgetRing",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusGlow(progress = ring, cornerRadius = 12.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(
                if (focused) StreamflixColors.GlassStrong else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Borrar historial",
            style = MaterialTheme.typography.labelSmall,
            color = StreamflixColors.OnSurfaceMuted,
        )
    }
}

/** Estado visual compartido por las filas del panel (anillo + escala + foco). */
private class RowAppearance(
    val focused: Boolean,
    val ring: Float,
    val scale: Float,
    val onFocusChanged: (androidx.compose.ui.focus.FocusState) -> Unit,
)

@Composable
private fun rememberRowAppearance(
    focusedRingLabel: String,
    focusedScaleLabel: String,
): RowAppearance {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = focusedRingLabel,
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.02f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = focusedScaleLabel,
    )
    return RowAppearance(
        focused = focused,
        ring = ring,
        scale = scale,
        onFocusChanged = { focused = it.isFocused },
    )
}

// ------------------------------------------------------------------------- //
// Teclado
// ------------------------------------------------------------------------- //

/**
 * Campo de consulta: texto grande sobre vidrio y un cursor que parpadea.
 *
 * No es editable con el IME a propósito: el texto sólo lo escriben las teclas de
 * abajo (o un teclado físico, que se atiende en el `onPreviewKeyEvent` del
 * diálogo).
 */
@Composable
private fun QueryField(value: String) {
    var caretOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530L)
            caretOn = !caretOn
        }
    }

    Row(
        modifier = Modifier
            .width(KEYBOARD_WIDTH)
            .height(60.dp)
            .background(StreamflixColors.Glass, RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value.ifEmpty { "Título o canal" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (value.isEmpty()) {
                StreamflixColors.OnSurfaceMuted.copy(alpha = 0.7f)
            } else {
                Color.White
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .width(2.dp)
                .height(30.dp)
                .background(
                    Color.White.copy(alpha = if (caretOn) 0.9f else 0f),
                    RoundedCornerShape(1.dp),
                ),
        )
    }
}

/**
 * Tecla de letra.
 *
 * Enfocada se pinta **blanca con la letra negra**: en un mando no hay cursor de
 * texto, así que la tecla que se va a pulsar tiene que ser evidente desde el
 * sofá. Es el mismo truco del botón play del reproductor.
 */
@Composable
private fun KeyCap(
    label: String,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rightFocus: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "keyRing",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "keyScale",
    )

    Box(
        modifier = modifier
            .width(width)
            .height(KEY_SIZE)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvFocusGlow(progress = ring, cornerRadius = 12.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            /* Salir del teclado hacia la derecha lleva al panel, que es la única
             * otra zona del buscador. */
            .then(if (rightFocus != null) Modifier.focusProperties { right = rightFocus } else Modifier)
            .background(
                if (focused) Color.White else StreamflixColors.Glass,
                RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = if (focused) Color.Transparent else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (label.length <= 2) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.labelMedium
            },
            fontWeight = FontWeight.SemiBold,
            color = if (focused) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

/** Tecla de acción (BUSCAR): blanca siempre, para que se vea que es la salida. */
@Composable
private fun PrimaryKey(
    label: String,
    width: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    rightFocus: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "primaryKeyRing",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "primaryKeyScale",
    )

    Box(
        modifier = Modifier
            .width(width)
            .height(KEY_SIZE)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvFocusGlow(progress = ring, cornerRadius = 12.dp)
            .then(if (rightFocus != null) Modifier.focusProperties { right = rightFocus } else Modifier)
            .background(
                when {
                    !enabled -> StreamflixColors.Glass
                    focused -> Color.White
                    else -> Color.White.copy(alpha = 0.92f)
                },
                RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.Black else StreamflixColors.OnSurfaceMuted,
            maxLines = 1,
        )
    }
}

/**
 * Un título del panel (recomendación o resultado): carátula, título y línea de
 * metadatos.
 *
 * Es una fila y no una tarjeta en rejilla porque el foco vertical es el que el
 * mando resuelve sin ambigüedad, y porque así caben más títulos con su año y su
 * nota, que es lo que ayuda a decidir qué abrir.
 */
@Composable
private fun SuggestionRow(
    entry: MediaEntry,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val appearance = rememberRowAppearance(focusedRingLabel = "suggestionRing", focusedScaleLabel = "suggestionScale")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = appearance.scale
                scaleY = appearance.scale
            }
            .tvFocusGlow(progress = appearance.ring, cornerRadius = 14.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(
                if (appearance.focused) StreamflixColors.GlassStrong else StreamflixColors.Glass,
                RoundedCornerShape(14.dp),
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (appearance.focused) 0.22f else 0.08f),
                shape = RoundedCornerShape(14.dp),
            )
            .onFocusChanged(appearance.onFocusChanged)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterImage(
            entry = entry,
            modifier = Modifier
                .width(48.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(8.dp)),
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.metaLine.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = entry.metaLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = StreamflixColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = if (appearance.focused) Color.White else StreamflixColors.OnSurfaceMuted.copy(alpha = 0.6f),
        )
    }
}
