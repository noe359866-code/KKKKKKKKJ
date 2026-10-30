package com.tanasi.streamflix.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.core.LocalDeviceProfile
import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.toChannelQueue
import com.tanasi.streamflix.data.model.Profile
import com.tanasi.streamflix.data.model.hasEpisodeNumber
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.playbackRequest
import com.tanasi.streamflix.ui.components.MediaRow
import com.tanasi.streamflix.ui.profile.ProfileBadge
import com.tanasi.streamflix.ui.theme.Dimens
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/*
 * Geometría del Home en TV. Son dos números distintos a propósito:
 *
 *  - `TV_BACKDROP_FRACTION` es la altura de la **caja** del hero. Llega más
 *    abajo que el texto para que su degradado inferior se funda con el fondo y
 *    las filas se deslicen por encima del backdrop sin un corte visible.
 *  - `TV_CONTENT_BASELINE` es dónde terminan los botones (y donde arranca la
 *    primera fila). Ahí caben badge + título + metadatos + sinopsis + botones
 *    y aún se ve una fila 16:9 completa más el título de la siguiente: eso es
 *    lo que invita a bajar con el mando.
 */
private const val TV_BACKDROP_FRACTION = 0.78f
private const val TV_CONTENT_BASELINE = 0.62f

/**
 * Home: hero cinematográfico + carruseles.
 *
 * Una sola composición, sólo TV:
 *
 *  - El hero ocupa la parte alta y **queda fijo**; las filas se deslizan por
 *    encima de su borde inferior (Apple TV+). Con el mando, subir desde la
 *    primera fila lleva a los botones del hero, y desde ahí a la barra superior.
 *  - No hay variante táctil: la app es sólo Android TV.
 *
 * El enfocar una tarjeta no cambia el catálogo: sólo el `Spotlight` del hero
 * ([HomeViewModel.onCardFocused]).
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlay: (PlaybackRequest) -> Unit,
    /**
     * Abre la ficha. Recibe también los títulos de la misma fila porque son la
     * parrilla con la que el reproductor zapea: sin ellos, cambiar de canal
     * obligaría a volver al Home.
     */
    onOpenDetails: (MediaEntry, List<MediaEntry>) -> Unit,
    onOpenSettings: () -> Unit,
    profile: Profile? = null,
    /** Filas propias del perfil activo ("Seguir viendo"), ya resueltas. */
    continueWatching: List<MediaEntry> = emptyList(),
    onSwitchProfile: () -> Unit = {},
) {
    val deviceProfile = LocalDeviceProfile.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hero by viewModel.hero.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }

    if (showSearch) {
        CatalogSearchDialog(
            initialQuery = query,
            onSearch = { showSearch = false; viewModel.search(it) },
            onDismiss = { showSearch = false },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamflixColors.Background),
    ) {
        when (val current = state) {
            /* Esqueleto, no un spinner: el Home ya tiene su forma final y las
             * filas se van rellenando conforme llegan. */
            is HomeUiState.Loading -> HomeSkeleton(deviceProfile)

            is HomeUiState.Error -> CenteredMessage {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = "Sin catálogo",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = StreamflixColors.OnSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = StreamflixColors.OnSurfaceMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    /* Ambos botones siempre presentes: esta pantalla sustituye al
                     * Home entero, así que si aquí no se puede abrir el diálogo
                     * del servidor el usuario se queda sin salida (una TV no
                     * tiene otra forma de corregir la IP). El orden cambia según
                     * lo que probablemente arregle el problema. */
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (current.needsSetup) {
                            OutlinedButton(onClick = onOpenSettings) {
                                Text("Ajustes")
                            }
                        }
                        OutlinedButton(onClick = { viewModel.load() }) {
                            Text("Reintentar")
                        }
                        if (!current.needsSetup) {
                            OutlinedButton(onClick = onOpenSettings) {
                                Text("Ajustes")
                            }
                        }
                    }
                }
            }

            is HomeUiState.Ready -> {
                val spotlight = hero ?: current.catalog.featured

                /* "Seguir viendo" va primero y sale del perfil activo: es la
                 * fila que Apple TV pone arriba y la que hace que cambiar de
                 * perfil cambie de verdad el Home. */
                val rows = remember(current.catalog.rows, continueWatching, profile?.id) {
                    if (continueWatching.isEmpty()) {
                        current.catalog.rows
                    } else {
                        listOf(
                            CatalogRow(
                                id = "continue:${profile?.id ?: "me"}",
                                title = "Seguir viendo",
                                aspect = "landscape",
                                items = continueWatching,
                            ),
                        ) + current.catalog.rows
                    }
                }

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val isTv = deviceProfile.isTelevision
                    val heroHeight = maxHeight * TV_BACKDROP_FRACTION
                    val contentBaseline = maxHeight * TV_CONTENT_BASELINE
                    /* En TV la caja del hero es más alta que el contenido. */
                    val contentBottomPadding = if (isTv) {
                        (heroHeight - contentBaseline).coerceAtLeast(24.dp)
                    } else {
                        20.dp
                    }

                    /* Un único juego de `FocusRequester` por pantalla: el hero y
                     * las filas se los pasan entre sí para que el D-Pad sepa
                     * exactamente a dónde ir en cada dirección. */
                    val serverFocus = remember { FocusRequester() }
                    val heroPlayFocus = remember { FocusRequester() }
                    val heroDetailsFocus = remember { FocusRequester() }
                    val firstCardFocus = remember { FocusRequester() }

                    val heroBanner: @Composable (Modifier) -> Unit = { modifier ->
                        HeroBanner(
                            spotlight = spotlight,
                            deviceProfile = deviceProfile,
                            playFocusRequester = heroPlayFocus,
                            detailsFocusRequester = heroDetailsFocus,
                            upFocus = serverFocus,
                            ctaDownFocus = firstCardFocus,
                            contentBottomPadding = contentBottomPadding,
                            onPlay = {
                                /* Series sin capítulo: abre la ficha para elegir
                                 * episodio. Todo lo demás reproduce directo. */
                                spotlight?.entry?.let { entry ->
                                    if (!entry.isLive && !hasEpisodeNumber(entry.type, entry.id)) {
                                        onOpenDetails(entry, emptyList())
                                    } else {
                                        onPlay(entry.playbackRequest())
                                    }
                                }
                            },
                            onDetails = {
                                spotlight?.entry?.let { onOpenDetails(it, emptyList()) }
                            },
                            modifier = modifier,
                        )
                    }

                    val rowsContent: @Composable (Modifier) -> Unit = { modifier ->
                        CatalogList(
                            rows = rows,
                            deviceProfile = deviceProfile,
                            onEntryClick = { entry, siblings ->
                                /* Un canal en directo va derecho al reproductor:
                                 * la ficha de un canal no tiene nada que elegir
                                 * (ni episodios ni servidores) y era un peaje de
                                 * dos pulsaciones antes de ver algo. */
                                if (entry.isLive) {
                                    onPlay(entry.playbackRequest(queue = siblings.toChannelQueue()))
                                } else {
                                    onOpenDetails(entry, siblings)
                                }
                            },
                            onEntryFocused = viewModel::onCardFocused,
                            rowsTopPadding = contentBaseline,
                            firstCardFocus = firstCardFocus,
                            firstRowFocusUp = heroPlayFocus,
                            modifier = modifier,
                        )
                    }

                    /* Hero fijo detrás, filas delante que lo tapan al bajar. */
                    Box(Modifier.fillMaxSize()) {
                        heroBanner(
                            Modifier
                                .fillMaxWidth()
                                .height(heroHeight),
                        )
                        rowsContent(Modifier.fillMaxSize())
                    }

                    HomeTopBar(
                        deviceProfile = deviceProfile,
                        settingsFocus = if (deviceProfile.isTelevision) serverFocus else null,
                        settingsDownFocus = if (deviceProfile.isTelevision) heroPlayFocus else null,
                        onOpenSettings = onOpenSettings,
                        onOpenSearch = { showSearch = true },
                        onClearSearch = { viewModel.search("") },
                        searching = query.isNotBlank(),
                        profile = profile,
                        onSwitchProfile = onSwitchProfile,
                        modifier = Modifier.align(Alignment.TopStart),
                    )

                    /* Foco inicial en TV: la primera tarjeta. Así el hero arranca
                     * mostrando el primer título de "Continuar viendo" y el
                     * usuario ya puede moverse sin adivinar nada. */
                    if (deviceProfile.requiresDpadFocus) {
                        var initialFocusRequested by remember { mutableStateOf(false) }
                        LaunchedEffect(rows.firstOrNull()?.id) {
                            if (initialFocusRequested) return@LaunchedEffect
                            /* `LaunchedEffect` corre antes del layout: sin
                             * esperar un frame, `requestFocus()` lanza
                             * "FocusRequester is not initialized" y el mando
                             * se queda sin cursor. */
                            withFrameNanos { }
                            withFrameNanos { }
                            runCatching { firstCardFocus.requestFocus() }.onSuccess { initialFocusRequested = true }
                        }
                    }
                }
            }
        }
        if (state !is HomeUiState.Ready) {
            HomeTopBar(
                deviceProfile = deviceProfile,
                settingsFocus = null,
                settingsDownFocus = null,
                onOpenSettings = onOpenSettings,
                onOpenSearch = { showSearch = true },
                onClearSearch = { viewModel.search("") },
                searching = query.isNotBlank(),
                profile = profile,
                onSwitchProfile = onSwitchProfile,
            )
        }
    }
}

/**
 * Las filas.
 *
 * En TV la lista lleva un `top` igual a la línea base del hero: la primera fila
 * aparece justo debajo de los botones, y al desplazarse las tarjetas pasan **por
 * encima** del backdrop (efecto Apple TV+). En móvil el hero es una celda de la
 * propia lista y no hay sangrado.
 */
@Composable
private fun CatalogList(
    rows: List<CatalogRow>,
    deviceProfile: DeviceProfile,
    rowsTopPadding: Dp,
    onEntryClick: (MediaEntry, List<MediaEntry>) -> Unit,
    onEntryFocused: (rowId: String, entry: MediaEntry) -> Unit,
    firstCardFocus: FocusRequester,
    firstRowFocusUp: FocusRequester,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            top = rowsTopPadding,
            bottom = 48.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (deviceProfile.requiresDpadFocus) 24.dp else 18.dp,
        ),
    ) {
        itemsIndexed(
            items = rows,
            key = { _, row -> row.id },
        ) { index, row ->
            MediaRow(
                row = row,
                deviceProfile = deviceProfile,
                onEntryClick = onEntryClick,
                onEntryFocused = onEntryFocused,
                firstCardFocusRequester = if (index == 0) firstCardFocus else null,
                focusUp = if (index == 0) firstRowFocusUp else null,
            )
        }
    }
}

/**
 * Barra superior: marca + acceso al servidor.
 *
 * Va flotando sobre el hero con un degradado transparente detrás, para que el
 * backdrop llegue hasta arriba sin una banda opaca que rompa la foto.
 */
@Composable
private fun HomeTopBar(
    deviceProfile: DeviceProfile,
    settingsFocus: FocusRequester?,
    settingsDownFocus: FocusRequester?,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onClearSearch: () -> Unit,
    searching: Boolean,
    profile: Profile?,
    onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Transparent,
                ),
            )
            .padding(
                start = deviceProfile.horizontalPaddingDp.dp,
                end = deviceProfile.horizontalPaddingDp.dp,
                top = if (deviceProfile.requiresDpadFocus) 18.dp else 10.dp,
                bottom = 18.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /* Marca sobria: en tvOS el logotipo no compite con el hero, y el
         * acento blanco ya está reservado para los botones. */
        Text(
            text = "Streamflix",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = StreamflixColors.OnSurface,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "TV",
            style = MaterialTheme.typography.labelSmall,
            color = StreamflixColors.OnSurfaceMuted,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (searching) {
            TopBarChip(
                text = "Inicio",
                focusDown = settingsDownFocus,
                onClick = onClearSearch,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        TopBarChip(
            text = "Buscar",
            focusDown = settingsDownFocus,
            onClick = onOpenSearch,
            modifier = Modifier.padding(end = 8.dp),
        )
        TopBarChip(
            text = "Ajustes",
            onClick = onOpenSettings,
            focusDown = settingsDownFocus,
            focusRequester = settingsFocus,
            modifier = Modifier.padding(end = if (profile != null) 8.dp else 0.dp),
        )
        if (profile != null) {
            ProfileBadge(
                profile = profile,
                onClick = onSwitchProfile,
                focusDown = settingsDownFocus,
            )
        }
    }
}

/**
 * Píldora de la barra superior.
 *
 * Las acciones (Inicio, Buscar, Ajustes) comparten forma para que la barra se
 * lea como un menú y no como texto suelto; el foco del mando añade el anillo
 * blanco y sube el brillo del vidrio.
 */
@Composable
private fun TopBarChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusDown: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val glow by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 120),
        label = "chipFocus",
    )

    Box(
        modifier = modifier
            .then(if (focusDown != null) Modifier.focusProperties { down = focusDown } else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            /* Cápsula translúcida: mismo lenguaje que los botones del hero y
             * que el avatar del perfil. */
            .tvFocusGlow(progress = glow, cornerRadius = 20.dp)
            .background(
                if (focused) StreamflixColors.GlassStrong else StreamflixColors.Glass,
                RoundedCornerShape(percent = 50),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = StreamflixColors.OnSurface,
            maxLines = 1,
        )
    }
}

/**
 * Esqueleto del Home mientras llegan los catálogos.
 *
 * Un `CircularProgressIndicator` sobre negro se lee como "la app está
 * colgada"; este esqueleto respeta la geometría real (hero + dos filas de
 * pósters) y late suavemente, así que la espera se percibe como "cargando
 * contenido" y el paso al catálogo no mueve nada de sitio.
 */
@Composable
private fun HomeSkeleton(deviceProfile: DeviceProfile) {
    val shimmer by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.28f,
        targetValue = 0.68f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (deviceProfile.isTelevision) 330.dp else 240.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            StreamflixColors.SurfaceVariant.copy(alpha = shimmer * 0.55f),
                            StreamflixColors.Background,
                        ),
                    ),
                ),
        )
        Spacer(Modifier.height(26.dp))
        repeat(2) { row ->
            Box(
                modifier = Modifier
                    .padding(start = deviceProfile.horizontalPaddingDp.dp)
                    .width(if (deviceProfile.isTelevision) 190.dp else 130.dp)
                    .height(if (deviceProfile.isTelevision) 18.dp else 14.dp)
                    .background(
                        StreamflixColors.SurfaceVariant.copy(alpha = shimmer),
                        RoundedCornerShape(4.dp),
                    ),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = deviceProfile.horizontalPaddingDp.dp),
                horizontalArrangement = Arrangement.spacedBy(deviceProfile.cardSpacingDp.dp),
            ) {
                repeat(if (deviceProfile.isTelevision) 6 else 3) {
                    Box(
                        modifier = Modifier
                            .width(deviceProfile.posterWidthDp.dp)
                            .aspectRatio(2f / 3f)
                            .background(
                                StreamflixColors.SurfaceVariant.copy(alpha = shimmer * 0.85f),
                                RoundedCornerShape(Dimens.cardCornerRadius),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(if (row == 0) 30.dp else 0.dp))
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
