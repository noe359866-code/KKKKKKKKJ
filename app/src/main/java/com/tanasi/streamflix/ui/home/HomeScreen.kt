package com.tanasi.streamflix.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.core.LocalDeviceProfile
import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.PlaybackRequest
import com.tanasi.streamflix.data.model.MediaType
import com.tanasi.streamflix.ui.components.MediaRow
import com.tanasi.streamflix.ui.theme.Dimens
import com.tanasi.streamflix.ui.theme.StreamflixColors

/**
 * Home: cabecera + una fila por tipo de contenido (Películas, Series, Anime, TV
 * en vivo), tal y como las devuelve el repositorio.
 *
 * Misma composición para TV y móvil: lo único que cambia es el tamaño de las
 * tarjetas ([DeviceProfile]), los márgenes y que en TV se pide el foco inicial
 * para que el D-Pad tenga un punto de partida.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlay: (PlaybackRequest) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val deviceProfile = LocalDeviceProfile.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to StreamflixColors.Surface,
                    0.35f to StreamflixColors.Background,
                ),
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(
                deviceProfile = deviceProfile,
                onOpenSettings = onOpenSettings,
            )

            when (val current = state) {
                is HomeUiState.Loading -> CenteredMessage {
                    CircularProgressIndicator(color = StreamflixColors.Primary)
                }

                is HomeUiState.Error -> CenteredMessage {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = current.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = StreamflixColors.OnSurfaceMuted,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.load() }) {
                            Text("Reintentar")
                        }
                    }
                }

                is HomeUiState.Ready -> CatalogRows(
                    rows = current.rows,
                    deviceProfile = deviceProfile,
                    onPlay = onPlay,
                )
            }
        }
    }
}

@Composable
private fun CatalogRows(
    rows: List<CatalogRow>,
    deviceProfile: DeviceProfile,
    onPlay: (PlaybackRequest) -> Unit,
) {
    /* En TV el primer elemento debe tener el foco al abrir la app: si no, el
     * D-Pad no hace nada hasta que el usuario "adivina" que hay que pulsar. */
    val firstCardFocus = remember { FocusRequester() }
    if (deviceProfile.requiresDpadFocus) {
        LaunchedEffect(rows) {
            runCatching { firstCardFocus.requestFocus() }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.rowSpacing),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(rows, key = { it.id }) { row ->
            val isFirstRow = row.id == rows.first().id
            MediaRow(
                title = row.title,
                items = row.items,
                deviceProfile = deviceProfile,
                onItemClick = { entry ->
                    onPlay(entry.toPlaybackRequest())
                },
                firstItemFocusRequester = if (isFirstRow) firstCardFocus else null,
            )
        }
    }
}

@Composable
private fun HomeHeader(
    deviceProfile: DeviceProfile,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = deviceProfile.horizontalPaddingDp.dp,
                end = deviceProfile.horizontalPaddingDp.dp,
                top = if (deviceProfile.requiresDpadFocus) 32.dp else 20.dp,
                bottom = 20.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Streamflix",
            style = MaterialTheme.typography.headlineMedium,
            color = StreamflixColors.Primary,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = "Películas · Series · Anime · TV",
            style = MaterialTheme.typography.bodyMedium,
            color = StreamflixColors.OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onOpenSettings,
            shape = RoundedCornerShape(6.dp),
        ) {
            Text("Servidor")
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

/** Traduce una tarjeta al intent del reproductor. */
private fun MediaEntry.toPlaybackRequest() = PlaybackRequest(
    id = id,
    type = type,
    title = title,
    isLive = mediaType == MediaType.Live,
)
