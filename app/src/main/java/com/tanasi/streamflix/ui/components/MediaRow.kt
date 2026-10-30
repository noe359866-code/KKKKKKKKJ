package com.tanasi.streamflix.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * @param onEntryFocused se llama con la fila y el título al entrar en foco,
 *   para que el hero sepa de qué carrusel viene la tarjeta.
 * @param firstCardFocusRequester foco inicial del Home en TV.
 * @param focusUp destino al pulsar ARRIBA en la primera fila (hero).
 */
@Composable
fun MediaRow(
    row: CatalogRow,
    deviceProfile: DeviceProfile,
    onEntryClick: (MediaEntry) -> Unit,
    onEntryFocused: (rowId: String, entry: MediaEntry) -> Unit,
    modifier: Modifier = Modifier,
    firstCardFocusRequester: FocusRequester? = null,
    focusUp: FocusRequester? = null,
) {
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
            horizontalArrangement = Arrangement.spacedBy(deviceProfile.cardSpacingDp.dp),
            contentPadding = PaddingValues(
                start = deviceProfile.horizontalPaddingDp.dp,
                end = deviceProfile.horizontalPaddingDp.dp,
                top = 8.dp,
                bottom = 10.dp,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(row.items, key = { index, entry -> "${row.id}:${entry.id}:$index" }) { index, entry ->
                val focusRequester = if (index == 0) firstCardFocusRequester else null
                val up = if (index == 0) focusUp else null

                /* Deportes: marcador con liga, equipos y hora. La fuente de datos
                 * lo pide con `layout: "sports"`; el resto de filas no cambia. */
                if (row.isSports) {
                    SportsCard(
                        entry = entry,
                        deviceProfile = deviceProfile,
                        onClick = { onEntryClick(entry) },
                        onFocused = { entry -> onEntryFocused(row.id, entry) },
                        focusUp = up,
                        focusRequester = focusRequester,
                    )
                } else if (row.rowAspect == RowAspect.Poster) {
                    PosterCard(
                        entry = entry,
                        deviceProfile = deviceProfile,
                        onClick = { onEntryClick(entry) },
                        onFocused = { entry -> onEntryFocused(row.id, entry) },
                        focusUp = up,
                        focusRequester = focusRequester,
                    )
                } else {
                    LandscapeCard(
                        entry = entry,
                        deviceProfile = deviceProfile,
                        onClick = { onEntryClick(entry) },
                        onFocused = { entry -> onEntryFocused(row.id, entry) },
                        focusUp = up,
                        focusRequester = focusRequester,
                    )
                }
            }
        }
    }
}
