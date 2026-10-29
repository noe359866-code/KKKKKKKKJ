package com.tanasi.streamflix.ui.details

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.core.LocalDeviceProfile
import com.tanasi.streamflix.data.model.EpisodeOption
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.enrichedBy
import com.tanasi.streamflix.data.model.StreamSource
import com.tanasi.streamflix.ui.components.BackdropImage
import com.tanasi.streamflix.ui.components.FailoverBadge
import com.tanasi.streamflix.ui.components.GlassBadge
import com.tanasi.streamflix.ui.components.LiveBadge
import com.tanasi.streamflix.ui.components.PosterImage
import com.tanasi.streamflix.ui.components.RatingBadge
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/**
 * Hoja de detalles "quick look".
 *
 * El flujo del Home es: enfocar → hero cambia → pulsar la tarjeta → **esto**.
 * Aquí se decide qué servidor usar antes de crear el `ExoPlayer`, de modo que
 * el reproductor nunca arranca con una fuente que el usuario ya sabe que le va
 * mal.
 *
 * Se implementa con `Dialog` (y no con `ModalBottomSheet`) por dos razones de
 * televisión: un `Dialog` abre su propia ventana y **su propio grafo de foco**,
 * así el D-Pad no se escapa a las tarjetas de detrás; y en un TV de 1080p una
 * bottom-sheet deja media pantalla desaprovechada.
 *
 * @param openOnSources `true` cuando el usuario llega desde "Cambiar servidor"
 *   del hero: el foco y el scroll van directos a la lista de fuentes.
 */
@Composable
fun DetailsDialog(
    entry: MediaEntry,
    viewModel: DetailsViewModel,
    openOnSources: Boolean,
    onDismiss: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    val deviceProfile = LocalDeviceProfile.current
    val isTv = deviceProfile.requiresDpadFocus
    val sources by viewModel.state.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val metadata by viewModel.metadata.collectAsStateWithLifecycle()
    val displayEntry = metadata?.let { entry.enrichedBy(it) } ?: entry

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = !isTv,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(if (isTv) 28.dp else 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = if (isTv) 940.dp else 620.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(StreamflixColors.Surface)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)),
            ) {
                DetailsHeader(entry = displayEntry, isTv = isTv)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (isTv) 420.dp else 460.dp),
                ) {
                    if (isTv) {
                        /* Póster a la izquierda sólo en TV: en el móvil el ancho
                         * se necesita para el texto y las fuentes. */
                        PosterImage(
                            entry = displayEntry,
                            modifier = Modifier
                                .width(150.dp)
                                .height(225.dp)
                                .padding(start = 20.dp, top = 16.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        Text(
                            text = "Sinopsis",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = StreamflixColors.OnSurfaceMuted,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = displayEntry.synopsis ?: "Sin sinopsis disponible para este título.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StreamflixColors.OnSurface.copy(alpha = 0.9f),
                        )

                        Spacer(Modifier.height(20.dp))

                        EpisodesSection(
                            state = episodes,
                            isTv = isTv,
                            onSelectSeason = viewModel::selectSeason,
                            onSelectEpisode = viewModel::selectEpisode,
                            onManualEpisode = viewModel::selectManualEpisode,
                        )

                        Spacer(Modifier.height(20.dp))

                        SourcesSection(
                            entry = displayEntry,
                            state = sources,
                            isTv = isTv,
                            openOnSources = openOnSources,
                            onSelect = viewModel::select,
                            onRetry = viewModel::load,
                        )

                        Spacer(Modifier.height(20.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val playEnabled = sources is SourcesUiState.Ready
                            PanelButton(
                                text = "Reproducir",
                                primary = true,
                                deviceProfile = deviceProfile,
                                enabled = playEnabled,
                                onClick = { onPlay(viewModel.selectedIndex()) },
                            )
                            PanelButton(
                                text = "Cerrar",
                                primary = false,
                                deviceProfile = deviceProfile,
                                onClick = onDismiss,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Cabecera: backdrop del título con los metadatos encima. */
@Composable
private fun DetailsHeader(entry: MediaEntry, isTv: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isTv) 220.dp else 160.dp),
    ) {
        BackdropImage(
            url = entry.backdrop,
            artKey = entry.art,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to StreamflixColors.Surface,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.isLive) {
                    LiveBadge()
                    Spacer(Modifier.width(8.dp))
                }
                entry.rating?.let { rating ->
                    RatingBadge(rating)
                    Spacer(Modifier.width(8.dp))
                }
                entry.quality?.let { quality ->
                    GlassBadge(text = quality)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.title,
                style = if (isTv) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.metaLine,
                style = MaterialTheme.typography.labelMedium,
                color = StreamflixColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Selector de temporada y episodio.
 *
 * Sólo aparece cuando el título es una serie y el id **no** trae capítulo. Es
 * la pieza que permite que una tarjeta de Cinemeta (`tt0944947`, la serie) se
 * convierta en una petición reproducible (`tt0944947:1:2`), porque los scrapers
 * de series buscan por temporada/episodio.
 *
 * Se oculta entera cuando el estado es [EpisodesUiState.Hidden] (películas,
 * canales en directo y entradas que ya llevan capítulo en el id): la hoja queda
 * exactamente como estaba para el 80 % de los casos.
 */
@Composable
private fun EpisodesSection(
    state: EpisodesUiState,
    isTv: Boolean,
    onSelectSeason: (Int) -> Unit,
    onSelectEpisode: (EpisodeOption) -> Unit,
    onManualEpisode: (EpisodeOption) -> Unit,
) {
    if (state is EpisodesUiState.Hidden) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Episodios",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = StreamflixColors.OnSurfaceMuted,
            )
            (state as? EpisodesUiState.Ready)?.let { ready ->
                Spacer(Modifier.width(8.dp))
                GlassBadge(text = ready.selected.label)
            }
        }
        Spacer(Modifier.height(8.dp))

        when (state) {
            is EpisodesUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = StreamflixColors.Primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.width(16.dp).height(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Cargando episodios…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamflixColors.OnSurfaceMuted,
                )
            }

            is EpisodesUiState.Unavailable -> Column {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.labelMedium,
                    color = StreamflixColors.OnSurfaceMuted,
                )
                Spacer(Modifier.height(8.dp))
                ManualEpisodePicker(selected = state.selected, onSelect = onManualEpisode)
            }

            is EpisodesUiState.Ready -> Column {
                if (state.seasons.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.seasons.forEach { season ->
                            SelectorChip(
                                text = season.label,
                                selected = season.season == state.selectedSeason,
                                onClick = { onSelectSeason(season.season) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 8.dp),
                ) {
                    items(state.episodes, key = { episode -> "${episode.season}:${episode.episode}" }) { episode ->
                        SelectorChip(
                            text = episode.shortLabel,
                            selected = episode == state.selected,
                            caption = episode.name,
                            onClick = { onSelectEpisode(episode) },
                        )
                    }
                }

                val chosen = state.selected
                if (!chosen.name.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = chosen.name.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = StreamflixColors.OnSurface.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            EpisodesUiState.Hidden -> Unit
        }
    }
}

/**
 * Chip de temporada/episodio.
 *
 * En una TV el foco tiene que verse sin ambigüedad y el D-Pad tiene que poder
 * recorrer la fila entera; por eso no se usa `FilterChip` de Material (que en
 * Android TV se queda sin anillo de foco) sino el mismo tratamiento que las
 * tarjetas del Home: anillo neón + escala mínima.
 */
@Composable
private fun SelectorChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    caption: String? = null,
    isTv: Boolean = LocalDeviceProfile.current.requiresDpadFocus,
) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "chipFocus",
    )
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = Modifier
            .tvFocusGlow(progress = focusProgress, cornerRadius = 8.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    selected -> StreamflixColors.Neon.copy(alpha = 0.16f)
                    focused -> Color.White.copy(alpha = 0.12f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
                shape,
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    StreamflixColors.Neon.copy(alpha = 0.75f)
                } else {
                    Color.White.copy(alpha = 0.08f)
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .widthIn(min = 56.dp, max = if (caption.isNullOrBlank()) 92.dp else 152.dp)
            .padding(horizontal = if (isTv) 14.dp else 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) StreamflixColors.Neon else StreamflixColors.OnSurface,
            maxLines = 1,
        )
        if (!caption.isNullOrBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = StreamflixColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Lista de servidores con el activo marcado.
 *
 * La explicación ("si falla, la app salta sola al siguiente") va escrita: es la
 * función estrella del cliente y nadie la descubre si no se cuenta.
 */
@Composable
private fun SourcesSection(
    entry: MediaEntry,
    state: SourcesUiState,
    isTv: Boolean,
    openOnSources: Boolean,
    onSelect: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    val firstSourceFocus = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }

    /* "Cambiar servidor" del hero: foco y scroll directamente aquí. */
    LaunchedEffect(openOnSources, state is SourcesUiState.Ready) {
        if (!openOnSources) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        bringIntoView.bringIntoView()
        runCatching { firstSourceFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Servidores",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = StreamflixColors.OnSurfaceMuted,
            )
            Spacer(Modifier.width(8.dp))
            GlassBadge(
                text = if (entry.isLive) "failover activo" else "failover automático",
                contentColor = StreamflixColors.Neon,
                borderColor = StreamflixColors.Neon.copy(alpha = 0.45f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Elige uno para empezar. Si falla o se queda sin datos, la app pasa sola al siguiente sin cerrar el vídeo.",
            style = MaterialTheme.typography.labelMedium,
            color = StreamflixColors.OnSurfaceMuted,
        )
        Spacer(Modifier.height(12.dp))

        when (state) {
            is SourcesUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = StreamflixColors.Primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.width(18.dp).height(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Buscando servidores…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamflixColors.OnSurfaceMuted,
                )
            }

            is SourcesUiState.Unavailable -> Column {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamflixColors.OnSurface.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Reintentar") }
            }

            is SourcesUiState.Ready -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.sources.forEachIndexed { index, source ->
                    SourceRow(
                        index = index,
                        source = source,
                        selected = index == state.selected,
                        isTv = isTv,
                        onClick = { onSelect(index) },
                        focusRequester = if (index == 0) firstSourceFocus else null,
                    )
                }
            }
        }
    }
}

/**
 * Una fuente: número de prioridad, etiqueta, formato y un chip cuando está
 * elegida. El foco dibuja el mismo anillo neón que el resto de la app.
 */
@Composable
private fun SourceRow(
    index: Int,
    source: StreamSource,
    selected: Boolean,
    isTv: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "sourceFocus",
    )
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusGlow(progress = focusProgress, cornerRadius = 10.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    selected -> StreamflixColors.Neon.copy(alpha = 0.12f)
                    focused -> Color.White.copy(alpha = 0.10f)
                    else -> Color.White.copy(alpha = 0.045f)
                },
                shape,
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    StreamflixColors.Neon.copy(alpha = 0.7f)
                } else {
                    Color.White.copy(alpha = 0.07f)
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (selected) StreamflixColors.Neon else StreamflixColors.OnSurfaceMuted,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = source.label,
                style = MaterialTheme.typography.bodyLarge,
                color = StreamflixColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(if (source.isHls) "HLS · .m3u8" else "Progresivo")
                    if (source.isLive) append(" · en directo")
                    if (index == 0) append(" · prioridad máxima")
                },
                style = MaterialTheme.typography.labelMedium,
                color = StreamflixColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            FailoverBadge(text = "elegido")
        } else if (isTv && focused) {
            GlassBadge(text = "OK")
        }
    }
}

/**
 * Botón del pie de la hoja.
 *
 * En TV no hay `Button` de Material que se sienta nativo con mando (ripple
 * incluido), así que se compone a mano con el mismo anillo de foco.
 */
@Composable
private fun PanelButton(
    text: String,
    primary: Boolean,
    deviceProfile: DeviceProfile,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "panelButtonFocus",
    )
    val shape = RoundedCornerShape(8.dp)
    val background = when {
        !enabled -> Color.White.copy(alpha = 0.06f)
        primary && focused -> Color.White
        primary -> Color.White.copy(alpha = 0.92f)
        focused -> Color.White.copy(alpha = 0.2f)
        else -> Color.White.copy(alpha = 0.1f)
    }
    val contentColor = when {
        !enabled -> StreamflixColors.OnSurfaceMuted.copy(alpha = 0.6f)
        primary -> Color.Black
        else -> Color.White
    }

    Box(
        modifier = Modifier
            .tvFocusGlow(progress = focusProgress, cornerRadius = 8.dp)
            .background(background, shape)
            .border(
                width = 1.dp,
                color = if (focused) StreamflixColors.Neon.copy(alpha = 0.9f) else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = if (deviceProfile.requiresDpadFocus) 24.dp else 18.dp,
                vertical = if (deviceProfile.requiresDpadFocus) 13.dp else 11.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
        )
    }
}
