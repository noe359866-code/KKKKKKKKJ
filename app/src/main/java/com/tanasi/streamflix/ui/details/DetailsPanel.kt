package com.tanasi.streamflix.ui.details

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.core.LocalDeviceProfile
import com.tanasi.streamflix.data.model.EpisodeOption
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.enrichedBy
import com.tanasi.streamflix.ui.components.BackdropImage
import com.tanasi.streamflix.ui.components.GlassBadge
import com.tanasi.streamflix.ui.components.LiveBadge
import com.tanasi.streamflix.ui.components.RatingBadge
import com.tanasi.streamflix.ui.theme.Dimens
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/**
 * Ficha del título, con la página de película/serie de Apple TV.
 *
 * ```
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │                     [backdrop a sangre, dos velos]                   │
 * │  SERIE · 2011 · 8,9 · 4K                                             │
 * │  Juego de tronos                                                     │
 * │  Sinopsis de tres líneas…                                            │
 * │  ( ▶ Reproducir · T1 E2 )   ( Cerrar )                               │
 * │  Buscamos la mejor fuente; si falla, saltamos a la siguiente.        │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │  Episodios                                         ( T1 )( T2 )( T3 )│
 * │  ┌────────┐ ┌────────┐ ┌────────┐                                    │
 * │  │ [img]  │ │ [img]  │ │ [img]  │  ← 16:9 con E#, título y foco      │
 * │  └────────┘ └────────┘ └────────┘                                    │
 * └──────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * **No hay lista de servidores.** Antes había que elegir fuente antes de
 * reproducir; ahora el botón blanco hace todo: el reproductor pide las fuentes
 * y salta a la siguiente si una falla. La ficha sólo decide **qué capítulo** se
 * ve.
 *
 * Se implementa con `Dialog` (y no con `ModalBottomSheet`) por dos razones de
 * televisión: un `Dialog` abre su propia ventana y **su propio grafo de foco**,
 * así el D-Pad no se escapa a las tarjetas de detrás; y en un TV de 1080p una
 * bottom-sheet deja media pantalla desaprovechada.
 */
@Composable
fun DetailsDialog(
    entry: MediaEntry,
    viewModel: DetailsViewModel,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
) {
    val deviceProfile = LocalDeviceProfile.current
    val isTv = deviceProfile.requiresDpadFocus
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
                .background(Color.Black.copy(alpha = 0.88f))
                .padding(if (isTv) 26.dp else 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = if (isTv) 1180.dp else 640.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(StreamflixColors.Surface)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)),
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val headerHeight = (maxHeight * 0.52f).coerceIn(300.dp, 460.dp)

                    DetailsHero(
                        entry = displayEntry,
                        deviceProfile = deviceProfile,
                        height = headerHeight,
                        episodeLabel = viewModel.episodeLabel(),
                        onPlay = onPlay,
                        onDismiss = onDismiss,
                    )
                }

                EpisodesPanel(
                    state = episodes,
                    deviceProfile = deviceProfile,
                    onSelectSeason = viewModel::selectSeason,
                    onSelectEpisode = viewModel::selectEpisode,
                    onManualEpisode = viewModel::selectManualEpisode,
                )
            }
        }
    }
}

/**
 * Cabecera: arte del título a sangre, metadatos encima y las dos acciones.
 *
 * El velo va en dos direcciones: vertical para fundir la foto con la ficha y
 * horizontal para que el texto se lea sobre cualquier póster claro.
 */
@Composable
private fun DetailsHero(
    entry: MediaEntry,
    deviceProfile: DeviceProfile,
    height: androidx.compose.ui.unit.Dp,
    episodeLabel: String?,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isTv = deviceProfile.requiresDpadFocus
    val playFocus = remember { FocusRequester() }

    /* El foco arranca en Reproducir: abrir la ficha y poder darle a OK es todo
     * lo que hace el 90 % de la gente. */
    LaunchedEffect(entry.id) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }

    Box(Modifier.fillMaxWidth().height(height)) {
        BackdropImage(
            url = entry.backdrop ?: entry.poster,
            entry = entry,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.86f),
                        0.55f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Black.copy(alpha = 0.30f),
                        1f to StreamflixColors.Surface,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = if (isTv) 48.dp else 20.dp,
                    end = if (isTv) 48.dp else 20.dp,
                    bottom = if (isTv) 26.dp else 16.dp,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.isLive) {
                    LiveBadge()
                    Spacer(Modifier.width(8.dp))
                }
                GlassBadge(text = typeLabel(entry))
                entry.rating?.let { rating ->
                    Spacer(Modifier.width(8.dp))
                    RatingBadge(rating)
                }
                entry.quality?.takeIf { it.isNotBlank() }?.let { quality ->
                    Spacer(Modifier.width(8.dp))
                    GlassBadge(text = quality)
                }
            }

            Spacer(Modifier.height(if (isTv) 14.dp else 8.dp))
            Text(
                text = entry.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val meta = entry.metaLine.takeIf { it.isNotBlank() }
            if (meta != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelLarge,
                    color = StreamflixColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            entry.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamflixColors.OnSurface.copy(alpha = 0.88f),
                    maxLines = if (isTv) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(if (isTv) 22.dp else 14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayCapsule(
                    label = "Reproducir",
                    suffix = episodeLabel,
                    focusRequester = playFocus,
                    onClick = onPlay,
                )
                GhostCapsule(text = "Cerrar", onClick = onDismiss)
            }

            Spacer(Modifier.height(if (isTv) 12.dp else 8.dp))
            Text(
                text = "Buscamos la mejor fuente al arrancar y, si falla, pasamos a la siguiente sin salir del vídeo.",
                style = MaterialTheme.typography.labelSmall,
                color = StreamflixColors.OnSurfaceMuted.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Sección de episodios: temporadas en píldoras y tarjetas 16:9.
 *
 * No existe para películas ni para directos ([EpisodesUiState.Hidden]): en esos
 * casos la ficha termina en los botones y no aparece un hueco vacío.
 */
@Composable
private fun EpisodesPanel(
    state: EpisodesUiState,
    deviceProfile: DeviceProfile,
    onSelectSeason: (Int) -> Unit,
    onSelectEpisode: (EpisodeOption) -> Unit,
    onManualEpisode: (EpisodeOption) -> Unit,
) {
    if (state is EpisodesUiState.Hidden) return
    val isTv = deviceProfile.requiresDpadFocus

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isTv) 48.dp else 20.dp,
                end = if (isTv) 48.dp else 20.dp,
                top = if (isTv) 22.dp else 14.dp,
                bottom = if (isTv) 26.dp else 16.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Episodios",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = StreamflixColors.OnSurface,
            )
            Spacer(Modifier.weight(1f))
            (state as? EpisodesUiState.Ready)?.let { ready ->
                if (ready.seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ready.seasons, key = { season -> "season:${season.season}" }) { season ->
                            SeasonPill(
                                text = season.label,
                                selected = season.season == ready.selectedSeason,
                                onClick = { onSelectSeason(season.season) },
                            )
                        }
                    }
                } else {
                    Text(
                        text = ready.seasons.first().label,
                        style = MaterialTheme.typography.labelLarge,
                        color = StreamflixColors.OnSurfaceMuted,
                    )
                }
            }
        }

        Spacer(Modifier.height(if (isTv) 14.dp else 10.dp))

        when (state) {
            is EpisodesUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = StreamflixColors.Primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Buscando episodios…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamflixColors.OnSurfaceMuted,
                )
            }

            is EpisodesUiState.Unavailable -> Column {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamflixColors.OnSurfaceMuted,
                )
                Spacer(Modifier.height(10.dp))
                ManualEpisodePicker(selected = state.selected, onSelect = onManualEpisode)
            }

            is EpisodesUiState.Ready -> EpisodesRow(
                state = state,
                deviceProfile = deviceProfile,
                onSelectEpisode = onSelectEpisode,
            )

            EpisodesUiState.Hidden -> Unit
        }
    }
}

/** Fila de tarjetas de episodio; la elegida lleva borde blanco y su ✓. */
@Composable
private fun EpisodesRow(
    state: EpisodesUiState.Ready,
    deviceProfile: DeviceProfile,
    onSelectEpisode: (EpisodeOption) -> Unit,
) {
    val isTv = deviceProfile.requiresDpadFocus
    val listState = rememberLazyListState()
    val selectedIndex = remember(state.episodes, state.selected) {
        state.episodes.indexOfFirst { it == state.selected }.coerceAtLeast(0)
    }

    /* Al cambiar de temporada la fila vuelve al capítulo elegido en vez de
     * quedarse a mitad del carrusel anterior. */
    LaunchedEffect(state.selectedSeason) {
        listState.animateScrollToItem(selectedIndex.coerceAtMost((state.episodes.size - 1).coerceAtLeast(0)))
    }

    val cardWidth = if (isTv) 300.dp else 200.dp

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 10.dp),
        contentPadding = PaddingValues(end = 4.dp),
    ) {
        items(state.episodes, key = { episode -> "${episode.season}:${episode.episode}" }) { episode ->
            EpisodeCard(
                episode = episode,
                selected = episode == state.selected,
                width = cardWidth,
                isTv = isTv,
                onClick = { onSelectEpisode(episode) },
            )
        }
    }
}

/**
 * Tarjeta de episodio: miniatura 16:9, número, título y estado.
 *
 * Cuando el proveedor no publica miniatura se dibuja un degradado con el número
 * grande — nunca una imagen de relleno de otro episodio.
 */
@Composable
private fun EpisodeCard(
    episode: EpisodeOption,
    selected: Boolean,
    width: androidx.compose.ui.unit.Dp,
    isTv: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 130),
        label = "episodeFocus",
    )
    val scale by animateFloatAsState(
        targetValue = if (isTv && focused) 1.04f else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "episodeScale",
    )
    val shape = RoundedCornerShape(Dimens.cardCornerRadius)
    val thumbnail = episode.thumbnail?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .width(width)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvFocusGlow(progress = focusProgress, cornerRadius = Dimens.cardCornerRadius),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(StreamflixColors.SurfaceVariant),
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(StreamflixColors.SurfaceVariant, Color(0xFF0B0B0C)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "E${episode.episode}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.22f),
                    )
                }
            }

            /* Velo inferior: el título se lee sobre cualquier miniatura. */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
            )

            GlassBadge(
                text = if (episode.season <= 0) "E${episode.episode}" else "T${episode.season} E${episode.episode}",
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )

            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckGlyph(Modifier.size(12.dp), color = Color.Black)
                }
            }

            Text(
                text = episode.name ?: "Episodio ${episode.episode}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )

            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, Color.White, shape),
                )
            }
        }

        episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Text(
                text = overview,
                style = MaterialTheme.typography.labelSmall,
                color = StreamflixColors.OnSurfaceMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

/**
 * Botón principal: cápsula blanca con el triángulo dibujado en `Canvas` y, si
 * hay capítulo elegido, su etiqueta detrás ("T1 E2").
 *
 * El triángulo se dibuja, no se escribe: los glifos `▶` salen como cajas en
 * algunos TV boxes.
 */
@Composable
private fun PlayCapsule(
    label: String,
    suffix: String?,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "playCapsuleFocus",
    )
    val shape = RoundedCornerShape(percent = 50)

    Row(
        modifier = Modifier
            .tvFocusGlow(progress = focusProgress, cornerRadius = 28.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(Color.White, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayGlyph(Modifier.size(16.dp), color = Color.Black)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            maxLines = 1,
        )
        suffix?.takeIf { it.isNotBlank() }?.let { extra ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = "· $extra",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Black.copy(alpha = 0.62f),
                maxLines = 1,
            )
        }
    }
}

/** Botón secundario: vidrio, texto blanco, mismo anillo de foco. */
@Composable
private fun GhostCapsule(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "ghostCapsuleFocus",
    )
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = Modifier
            .tvFocusGlow(progress = focusProgress, cornerRadius = 28.dp)
            .background(
                if (focused) StreamflixColors.GlassStrong else StreamflixColors.Glass,
                shape,
            )
            .border(1.dp, Color.White.copy(alpha = if (focused) 0.35f else 0.14f), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** Píldora de temporada: seleccionada en blanco, el resto en vidrio. */
@Composable
private fun SeasonPill(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "seasonPillFocus",
    )
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = Modifier
            .tvFocusGlow(progress = focusProgress, cornerRadius = 18.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    selected -> Color.White
                    focused -> StreamflixColors.GlassStrong
                    else -> Color.White.copy(alpha = 0.06f)
                },
                shape,
            )
            .border(
                width = 1.dp,
                color = when {
                    selected -> Color.Transparent
                    focused -> Color.White.copy(alpha = 0.35f)
                    else -> Color.White.copy(alpha = 0.10f)
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

/** Triángulo de play dibujado. */
@Composable
private fun PlayGlyph(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.04f)
            lineTo(size.width * 0.96f, size.height * 0.5f)
            lineTo(size.width * 0.08f, size.height * 0.96f)
            close()
        }
        drawPath(path, color)
    }
}

/** Marca de "elegido", dibujada. */
@Composable
private fun CheckGlyph(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val stroke = Stroke(
            width = (size.width * 0.16f).coerceAtLeast(1.6f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val path = Path().apply {
            moveTo(size.width * 0.14f, size.height * 0.55f)
            lineTo(size.width * 0.42f, size.height * 0.80f)
            lineTo(size.width * 0.88f, size.height * 0.20f)
        }
        drawPath(path, color, style = stroke)
    }
}

/** Etiqueta del tipo publicado: PELÍCULA · SERIE · ANIME · EN VIVO. */
private fun typeLabel(entry: MediaEntry): String = when (entry.type.lowercase()) {
    "movie" -> "Película"
    "series" -> "Serie"
    "anime" -> "Anime"
    "tv" -> "TV"
    else -> entry.type
}
