package com.tanasi.streamflix.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.core.LocalDeviceProfile
import com.tanasi.streamflix.di.ServiceLocator
import com.tanasi.streamflix.data.model.HomeCatalog
import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.Profile
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.stremio.StremioAddonClient
import com.tanasi.streamflix.data.model.playbackRequest
import com.tanasi.streamflix.ui.components.MediaRow
import com.tanasi.streamflix.ui.components.dpadFastVerticalScroll
import com.tanasi.streamflix.ui.components.dpadRepeatThrottle
import com.tanasi.streamflix.ui.theme.Dimens
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/*
 * Geometría del Home en TV. Son dos números distintos a propósito:
 *
 *  - `TV_BACKDROP_FRACTION` es la altura de la **caja** del hero. Llega más
 *    abajo que el texto para que su degradado inferior se funda con el fondo y
 *    las filas se deslicen por encima del backdrop sin un corte visible.
 *  - `TV_CONTENT_BASELINE` es dónde terminan los botones y arranca la lista:
 *    primero la shelf de plataformas si hay catálogos compatibles, después
 *    las filas habituales. Ahí caben badge + título + metadatos + sinopsis +
 *    botones sin tapar la zona de contenido.
 */
private const val TV_BACKDROP_FRACTION = 0.78f
private const val TV_CONTENT_BASELINE = 0.62f

/**
 * Home: hero cinematográfico + carruseles.
 *
 * Una sola composición, sólo TV:
 *
 *  - El hero ocupa la parte alta y **queda fijo**; las filas se deslizan por
 *    encima de su borde inferior. Con el mando, subir desde la primera fila
 *    lleva a los botones del hero, y desde ahí a la barra superior.
 *  - No hay variante táctil: la app es sólo Android TV.
 *
 * El enfocar una tarjeta no cambia el catálogo: sólo el `Spotlight` del hero
 * ([HomeViewModel.onCardFocused]).
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlay: (PlaybackRequest) -> Unit,
    /** Abre la ficha del título. */
    onOpenDetails: (MediaEntry) -> Unit,
    onOpenSettings: () -> Unit,
    profile: Profile? = null,
    /** Filas propias del perfil activo ("Seguir viendo"), ya resueltas. */
    continueWatching: List<MediaEntry> = emptyList(),
    onSwitchProfile: () -> Unit = {},
    returnSignal: Int = 0,
    canRestoreCatalogFocus: Boolean = true,
) {
    val deviceProfile = LocalDeviceProfile.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hero by viewModel.hero.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val searchPreview by viewModel.preview.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var showSidebar by remember { mutableStateOf(false) }
    var restoreMenuFocus by remember { mutableStateOf(false) }
    val menuButtonFocus = remember { FocusRequester() }

    LaunchedEffect(showSidebar, restoreMenuFocus) {
        if (!showSidebar && restoreMenuFocus) {
            withFrameNanos { }
            runCatching { menuButtonFocus.requestFocus() }
            restoreMenuFocus = false
        }
    }
    var category by remember { mutableStateOf(HomeCategory.Home) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    var returnAnchor by remember(profile?.id, category, query) { mutableStateOf<CatalogFocusAnchor?>(null) }
    val context = LocalContext.current
    val stremioAddonManager = remember(context) { ServiceLocator.stremioAddonManager(context) }
    val installedStremioAddons by stremioAddonManager.addons.collectAsStateWithLifecycle()
    val availablePlatforms = remember(installedStremioAddons) {
        StreamingPlatforms.availableFrom(installedStremioAddons)
    }
    val platformBrowse by viewModel.platformBrowse.collectAsStateWithLifecycle()

    /* El historial vive en el dispositivo, no en el ViewModel: se lee al abrir
     * el buscador y se escribe al buscar, así que siempre está al día sin
     * arrastrar un `SharedPreferences` dentro del ViewModel. */
    var recentSearches by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(showSearch) {
        if (showSearch) recentSearches = ServiceLocator.searchHistory(context).recent()
    }
    if (showSearch) {
        CatalogSearchDialog(
            initialQuery = query,
            suggestions = suggestions,
            preview = searchPreview,
            onPreview = viewModel::preview,
            onClearPreview = viewModel::clearPreview,
            recentSearches = recentSearches,
            onForgetSearches = {
                recentSearches = ServiceLocator.searchHistory(context).clear()
            },
            onForgetSearch = { query ->
                recentSearches = ServiceLocator.searchHistory(context).forget(query)
            },
            onSearch = {
                recentSearches = ServiceLocator.searchHistory(context).remember(it)
                showSearch = false
                category = HomeCategory.Home
                viewModel.search(it)
            },
            /* Una recomendación lleva a su ficha: es lo que el usuario quiere
             * hacer con lo que le sugieren, sin pasar por la búsqueda. */
            onOpenDetails = { entry ->
                returnAnchor = null
                showSearch = false
                onOpenDetails(entry)
            },
            onDismiss = { showSearch = false },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back && !showSidebar && !showSearch) {
                    if (platformBrowse.platform != null) viewModel.closePlatformBrowse() else showSidebar = true
                    true
                } else {
                    // Una acción nueva del mando prevalece sobre una restauración
                    // pendiente, incluso si la fila aún se está componiendo.
                    if (event.type == KeyEventType.KeyDown &&
                        mayRestoreCatalogFocus(returnAnchor, returnAnchor, returnSignal, canRestoreCatalogFocus, showSearch) &&
                        event.key in setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)
                    ) returnAnchor = null
                    false
                }
            }
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

                /* "Seguir viendo" va primero y sale del perfil activo: cambiar
                 * de perfil cambia realmente el contenido principal del Home. */
                val allRows = remember(current.catalog.rows, continueWatching, profile?.id, query) {
                    if (continueWatching.isEmpty() || query.isNotBlank()) {
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

                val platformShelfVisible = category == HomeCategory.Home && query.isBlank() && availablePlatforms.isNotEmpty()
                val platformCatalogRowIds = remember(availablePlatforms) {
                    availablePlatforms.flatMap { available ->
                        available.catalogs.map { ref ->
                            "remote:${ref.type}/${StremioAddonClient.encodeCatalogId(ref.addonId, ref.catalogId)}"
                        }
                    }.toSet()
                }
                val rows = remember(allRows, category, platformCatalogRowIds, platformBrowse) {
                    when {
                        platformBrowse.platform != null -> platformBrowse.rows
                        category == HomeCategory.Home -> filterHomeRows(
                            allRows.filterNot { it.id in platformCatalogRowIds },
                            category,
                        )
                        else -> filterHomeRows(allRows, category)
                    }
                }
                val visibleCatalog = remember(rows) { HomeCatalog(rows) }
                // No mostrar un destacado de otra sección al cambiar de pestaña.
                val spotlight = hero?.let { visibleCatalog.spotlightFor(it.rowId, it.entry.id, it.entry.type)?.copy(entry = it.entry) }
                    ?: visibleCatalog.featured

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
                    val heroPlayFocus = remember { FocusRequester() }
                    val heroDetailsFocus = remember { FocusRequester() }
                    val firstCardFocus = remember { FocusRequester() }
                    val firstPlatformFocus = remember { FocusRequester() }
                    val platformFocusRequesters = remember(availablePlatforms, firstPlatformFocus) {
                        availablePlatforms.mapIndexed { index, available ->
                            available.platform.id to if (index == 0) firstPlatformFocus else FocusRequester()
                        }.toMap()
                    }
                    val activePlatformFocus = platformBrowse.platform?.platform?.id
                        ?.let { platformFocusRequesters[it] } ?: firstPlatformFocus

                    val heroBanner: @Composable (Modifier) -> Unit = { modifier ->
                        HeroBanner(
                            spotlight = spotlight,
                            deviceProfile = deviceProfile,
                            playFocusRequester = heroPlayFocus,
                            detailsFocusRequester = heroDetailsFocus,
                            upFocus = menuButtonFocus,
                            ctaDownFocus = when {
                                platformShelfVisible -> firstPlatformFocus
                                rows.isNotEmpty() -> firstCardFocus
                                else -> null
                            },
                            contentBottomPadding = contentBottomPadding,
                            onPlay = {
                                /* Series sin capítulo: abre la ficha para elegir
                                 * episodio. Todo lo demás reproduce directo. */
                                returnAnchor = null
                                spotlight?.let { focused ->
                                    val entry = focused.entry
                                    if (heroAction(entry) == HeroAction.ChooseEpisode) {
                                        onOpenDetails(entry)
                                    } else {
                                        onPlay(entry.playbackRequest())
                                    }
                                }
                            },
                            onDetails = {
                                returnAnchor = null
                                spotlight?.let { onOpenDetails(it.entry) }
                            },
                            modifier = modifier,
                        )
                    }

                    /* Al buscar, las filas dejan de ser el catálogo y pasan a
                     * ser resultados: sin una cabecera, el Home parece el mismo
                     * de siempre con títulos cambiados. */
                    val resultHeader: (@Composable () -> Unit)? = if (query.isNotBlank()) {
                        {
                            SearchResultHeader(
                                query = query,
                                count = rows.sumOf { it.items.size },
                                deviceProfile = deviceProfile,
                            )
                        }
                    } else if (category != HomeCategory.Home) {
                        {
                            Text(
                                text = "${category.label} · catálogo cargado",
                                style = MaterialTheme.typography.labelLarge,
                                color = StreamflixColors.OnSurfaceMuted,
                                modifier = Modifier.padding(
                                    start = deviceProfile.horizontalPaddingDp.dp,
                                    end = deviceProfile.horizontalPaddingDp.dp,
                                ),
                            )
                        }
                    } else {
                        null
                    }
                    val catalogHeader: (@Composable () -> Unit)? = if (platformShelfVisible) {
                        {
                            PlatformShelf(
                                platforms = availablePlatforms,
                                activePlatformId = platformBrowse.platform?.platform?.id,
                                deviceProfile = deviceProfile,
                                focusRequesters = platformFocusRequesters,
                                contentFocusRequester = firstCardFocus.takeIf { rows.isNotEmpty() },
                                heroFocusRequester = heroPlayFocus,
                                onPlatformSelected = viewModel::browsePlatform,
                                onOpenNavigation = { showSidebar = true },
                            )
                        }
                    } else {
                        resultHeader
                    }

                    val rowsContent: @Composable (Modifier) -> Unit = { modifier ->
                        val anchorToRestore = returnAnchor?.takeIf {
                            mayRestoreCatalogFocus(it, returnAnchor, returnSignal, canRestoreCatalogFocus, showSearch)
                        }
                        CatalogList(
                            rows = rows,
                            deviceProfile = deviceProfile,
                            header = catalogHeader,
                            onEntryClick = { row, entry, siblings, itemKey, itemIndex ->
                                returnAnchor = CatalogFocusAnchor(row.id, itemKey, itemIndex, returnSignal)
                                onOpenDetails(entry)
                            },
                            onEntryFocused = { row, entry -> viewModel.onCardFocused(row, entry) },
                            onOpenNavigation = { showSidebar = true },
                            rowsTopPadding = contentBaseline,
                            firstCardFocus = firstCardFocus,
                            firstRowFocusUp = if (platformShelfVisible) activePlatformFocus else heroPlayFocus,
                            restoreTarget = anchorToRestore?.let { resolveCatalogFocus(rows, it) },
                            isRestorePending = { mayRestoreCatalogFocus(anchorToRestore, returnAnchor, returnSignal, canRestoreCatalogFocus, showSearch) },
                            onFocusRestored = { if (returnAnchor == anchorToRestore) returnAnchor = null },
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
                        if (rows.isNotEmpty() || platformShelfVisible) {
                            key(category) { rowsContent(Modifier.fillMaxSize()) }
                        }
                        if (rows.isEmpty()) {
                            val selectedPlatform = platformBrowse.platform
                            if (selectedPlatform != null) {
                                Box(
                                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                                ) {
                                    PlatformBrowseStatus(
                                        platform = selectedPlatform.platform,
                                        isLoading = platformBrowse.isLoading,
                                        message = platformBrowse.error,
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier.align(Alignment.Center).padding(48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        if (query.isNotBlank()) "Sin resultados en esta sección" else "No hay títulos en esta sección",
                                        style = MaterialTheme.typography.titleLarge,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        if (query.isNotBlank()) "Prueba otra sección o cambia la búsqueda."
                                        else "Este filtro utiliza el catálogo cargado. Prueba otra sección o busca un título.",
                                        color = StreamflixColors.OnSurfaceMuted,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }

                    HomeTopBar(
                        deviceProfile = deviceProfile,
                        menuFocusRequester = menuButtonFocus,
                        downFocus = if (spotlight != null) heroPlayFocus else null,
                        activeLabel = if (query.isNotBlank()) "Resultados de búsqueda" else category.label,
                        onOpenSidebar = { showSidebar = true },
                        modifier = Modifier.align(Alignment.TopStart),
                    )

                    /* En TV, enfocar primero la shelf cuando esté disponible;
                     * sin ella, conservar el foco inicial en la primera tarjeta. */
                    if (deviceProfile.requiresDpadFocus && rows.isNotEmpty()) {
                        LaunchedEffect(rows.firstOrNull()?.id, platformShelfVisible) {
                            if (initialFocusRequested) return@LaunchedEffect
                            /* `LaunchedEffect` corre antes del layout: sin
                             * esperar un frame, `requestFocus()` lanza
                             * "FocusRequester is not initialized" y el mando
                             * se queda sin cursor. */
                            withFrameNanos { }
                            withFrameNanos { }
                            runCatching {
                                if (platformShelfVisible) firstPlatformFocus.requestFocus() else firstCardFocus.requestFocus()
                            }.onSuccess { initialFocusRequested = true }
                        }
                    }
                }
            }
        }
        if (state !is HomeUiState.Ready) {
            HomeTopBar(
                deviceProfile = deviceProfile,
                menuFocusRequester = menuButtonFocus,
                downFocus = null,
                activeLabel = if (query.isNotBlank()) "Resultados de búsqueda" else category.label,
                onOpenSidebar = { showSidebar = true },
            )
        }
    }

    if (showSidebar) {
        HomeSidebar(
            category = category,
            searching = query.isNotBlank(),
            profile = profile,
            horizontalSafeInset = deviceProfile.horizontalPaddingDp.dp,
            verticalSafeInset = deviceProfile.verticalPaddingDp.dp,
            compactHeight = deviceProfile.screenHeightDp < 520,
            onCategorySelected = { selected ->
                viewModel.closePlatformBrowse()
                category = selected
                if (selected == HomeCategory.Home && query.isNotBlank()) viewModel.search("")
                initialFocusRequested = true
            },
            onSearch = { viewModel.closePlatformBrowse(); showSearch = true },
            onSettings = { viewModel.closePlatformBrowse(); onOpenSettings() },
            onSwitchProfile = { viewModel.closePlatformBrowse(); onSwitchProfile() },
            onDismiss = { restoreHomeFocus ->
                restoreMenuFocus = restoreHomeFocus
                showSidebar = false
            },
        )
    }
}

/**
 * Cabecera de resultados de búsqueda: **qué** se buscó y **cuánto** salió.
 *
 * Sin ella, tras buscar el Home era indistinguible del catálogo normal (mismas
 * filas, mismos títulos de fila): el encabezado explicita la consulta y el
 * número de resultados sin depender del estado del menú lateral.
 */
@Composable
private fun SearchResultHeader(
    query: String,
    count: Int,
    deviceProfile: DeviceProfile,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = deviceProfile.horizontalPaddingDp.dp,
                end = deviceProfile.horizontalPaddingDp.dp,
                bottom = 14.dp,
            ),
    ) {
        Text(
            text = "RESULTADOS PARA",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            color = StreamflixColors.OnSurfaceMuted,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "«$query»",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = StreamflixColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = if (count == 1) "1 resultado" else "$count resultados",
                style = MaterialTheme.typography.labelMedium,
                color = StreamflixColors.OnSurfaceMuted,
            )
        }
    }
}

/**
 * Las filas.
 *
 * La lista lleva un `top` igual a la línea base del hero: la primera fila
 * aparece justo debajo de los botones y, al desplazarse, las tarjetas pasan
 * **por encima** del backdrop.
 */
@Composable
private fun CatalogList(
    rows: List<CatalogRow>,
    deviceProfile: DeviceProfile,
    rowsTopPadding: Dp,
    onEntryClick: (CatalogRow, MediaEntry, List<MediaEntry>, String, Int) -> Unit,
    onEntryFocused: (row: CatalogRow, entry: MediaEntry) -> Unit,
    onOpenNavigation: () -> Unit,
    firstCardFocus: FocusRequester,
    firstRowFocusUp: FocusRequester,
    restoreTarget: CatalogFocusTarget?,
    isRestorePending: () -> Boolean,
    onFocusRestored: () -> Unit,
    modifier: Modifier = Modifier,
    /** Cabecera opcional («Resultados para …»); va dentro de la lista para que
     *  se desplace con las filas y no tape el hero. */
    header: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val latestRestorePending by rememberUpdatedState(isRestorePending)
    var readyTarget by remember { mutableStateOf<CatalogFocusTarget?>(null) }
    LaunchedEffect(restoreTarget, header != null) {
        readyTarget = null
        if (restoreTarget != null) {
            listState.scrollToItem(restoreTarget.rowIndex + if (header != null) 1 else 0)
            withFrameNanos { }
            if (latestRestorePending()) readyTarget = restoreTarget
        }
    }
    LazyColumn(
        state = listState,
        /* Pantalla con D-Pad: las repeticiones del mando se frenan a un ritmo
         * conocido y ARRIBA/ABAJO sostenido desliza la lista. En táctil no se
         * instala nada. */
        modifier = modifier
            .dpadRepeatThrottle()
            .dpadFastVerticalScroll(
                scrollableState = listState,
                enabled = deviceProfile.requiresDpadFocus,
            ),
        contentPadding = PaddingValues(
            top = rowsTopPadding,
            bottom = 48.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (deviceProfile.requiresDpadFocus) 24.dp else 18.dp,
        ),
    ) {
        if (header != null) {
            item(key = "list-header") { header() }
        }
        itemsIndexed(
            items = rows,
            key = { _, row -> "catalog:${row.id}" },
        ) { index, row ->
            MediaRow(
                row = row,
                deviceProfile = deviceProfile,
                onEntryClick = { entry, siblings, itemKey, itemIndex -> onEntryClick(row, entry, siblings, itemKey, itemIndex) },
                onEntryFocused = { _, entry -> onEntryFocused(row, entry) },
                onOpenNavigation = onOpenNavigation,
                firstCardFocusRequester = if (index == 0) firstCardFocus else null,
                focusUp = if (index == 0) firstRowFocusUp else null,
                restoreIndex = readyTarget?.takeIf { it.rowIndex == index }?.itemIndex,
                isRestorePending = isRestorePending,
                onFocusRestored = onFocusRestored,
            )
        }
    }
}

/**
 * Barra mínima sobre el hero: abre el panel de navegación del mando y mantiene
 * visible la sección actual sin competir con el artwork.
 */
@Composable
private fun HomeTopBar(
    deviceProfile: DeviceProfile,
    menuFocusRequester: FocusRequester,
    downFocus: FocusRequester?,
    activeLabel: String,
    onOpenSidebar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val glow by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 140),
        label = "menuFocus",
    )

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
                top = deviceProfile.verticalPaddingDp.dp,
                bottom = 18.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .then(if (downFocus != null) Modifier.focusProperties { down = downFocus } else Modifier)
                .focusRequester(menuFocusRequester)
                .tvFocusGlow(progress = glow, cornerRadius = 18.dp)
                .background(
                    if (focused) Color.White else Color.White.copy(alpha = 0.10f),
                    RoundedCornerShape(18.dp),
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onOpenSidebar)
                .semantics { contentDescription = "Abrir menú de navegación. Sección $activeLabel" }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(width = 22.dp, height = 20.dp)) {
                val stroke = 1.8.dp.toPx()
                val left = 2.dp.toPx()
                val right = size.width - 2.dp.toPx()
                val spacing = size.height / 4f
                for (line in 1..3) {
                    val y = spacing * line
                    drawLine(
                        color = if (focused) Color.Black else Color.White,
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = stroke,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = activeLabel,
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) Color.Black else Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
