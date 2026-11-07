package com.tanasi.streamflix.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.RowAspect
import com.tanasi.streamflix.ui.theme.StreamflixColors

/**
 * Un carrusel del Home (inspiración Netflix).
 *
 * Misma pieza para las cuatro filas: sólo cambia la proporción de las tarjetas,
 * que la decide la fila (`aspect` en el catálogo), no la pantalla. Así
 * "Continuar viendo" y "Deportes en directo" se pintan en 16:9 y el resto en
 * 2:3, sin código condicional repartido.
 *
 * Detalles de D-Pad:
 *  - el foco se desplaza lateralmente con `◀ ▶` de forma nativa en un `LazyRow`;
 *  - el sangrado lateral es el mismo que el resto de la pantalla para que la
 *    primera tarjeta quede alineada con el título del hero;
 *  - se reserva `contentPadding` vertical para que la tarjeta enfocada
 *    (escala 1.08 + halo) **no** se recorte contra el borde de la fila. El
 *    `LazyRow` no recorta a sus hijos: crecen por encima y por debajo sin
 *    empujar al vecino, y ese margen es el que deja ver el halo entero.
 *
 * @param onEntryClick recibe también los títulos de la fila, por si el
 *   consumidor necesita el contexto de la fila.
 * @param onEntryFocused se llama con la fila y el título al entrar en foco,
 *   para que el hero sepa de qué carrusel viene la tarjeta.
 * @param firstCardFocusRequester foco inicial del Home en TV.
 * @param focusUp destino al pulsar ARRIBA en la primera fila (hero).
 */
@Composable
fun MediaRow(
    row: CatalogRow,
    deviceProfile: DeviceProfile,
    onEntryClick: (MediaEntry, List<MediaEntry>, String, Int) -> Unit,
    onEntryFocused: (rowId: String, entry: MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
    firstCardFocusRequester: FocusRequester? = null,
    focusUp: FocusRequester? = null,
    restoreIndex: Int? = null,
    isRestorePending: () -> Boolean = { true },
    onFocusRestored: () -> Unit = {},
) {
    val itemKeys = remember(row.items) { stableMediaKeys(row.items) }
    val listState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    val latestRestorePending by rememberUpdatedState(isRestorePending)
    LaunchedEffect(restoreIndex, itemKeys) {
        val target = restoreIndex?.takeIf { it in row.items.indices } ?: return@LaunchedEffect
        listState.scrollToItem(target)
        repeat(4) {
            withFrameNanos { }
            if (!latestRestorePending()) return@LaunchedEffect
            val requester = if (target == 0 && firstCardFocusRequester != null) firstCardFocusRequester else restoreFocus
            runCatching { requester.requestFocus() }
        }
        // También se consume si el nodo ya tenía foco o si no pudo recuperarlo:
        // no debe robar foco en una reanudación posterior.
        if (latestRestorePending()) onFocusRestored()
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = deviceProfile.horizontalPaddingDp.dp,
                    end = deviceProfile.horizontalPaddingDp.dp,
                    bottom = if (deviceProfile.requiresDpadFocus) 10.dp else 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /* Encabezado mínimo, como en tvOS: sólo el nombre de la fila. Sin
             * barra de acento, sin contador y sin etiqueta de formato — tres
             * adornos que competían con las carátulas y no aportaban nada. */
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = StreamflixColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(deviceProfile.cardSpacingDp.dp),
            contentPadding = PaddingValues(
                start = deviceProfile.horizontalPaddingDp.dp,
                end = deviceProfile.horizontalPaddingDp.dp,
                top = 8.dp,
                bottom = 10.dp,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(row.items, key = { index, _ -> itemKeys[index] }) { index, entry ->
                val focusRequester = when {
                    index == 0 && firstCardFocusRequester != null -> firstCardFocusRequester
                    index == restoreIndex -> restoreFocus
                    else -> null
                }
                val up = focusUp
                val onFocus: (MediaEntry) -> Unit = { focused ->
                    onEntryFocused(row.id, focused)
                    if (index == restoreIndex && latestRestorePending()) onFocusRestored()
                }

                if (row.rowAspect == RowAspect.Poster) {
                    PosterCard(
                        entry = entry,
                        deviceProfile = deviceProfile,
                        onClick = { onEntryClick(entry, row.items, itemKeys[index], index) },
                        onFocused = onFocus,
                        focusUp = up,
                        focusRequester = focusRequester,
                    )
                } else {
                    LandscapeCard(
                        entry = entry,
                        deviceProfile = deviceProfile,
                        onClick = { onEntryClick(entry, row.items, itemKeys[index], index) },
                        onFocused = onFocus,
                        focusUp = up,
                        focusRequester = focusRequester,
                    )
                }
            }
        }
    }
}
