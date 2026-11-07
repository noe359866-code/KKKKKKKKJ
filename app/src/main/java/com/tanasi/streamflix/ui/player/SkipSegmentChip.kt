package com.tanasi.streamflix.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.data.model.SkipSegment
import com.tanasi.streamflix.ui.theme.StreamflixColors

/**
 * El botón de "Saltar intro".
 *
 * Va pegado a la esquina inferior derecha, lejos de los controles: el mando
 * llega ahí con ABAJO o DERECHA sin que el usuario tenga que abrir la barra, y
 * desaparece solo cuando el tramo termina.
 *
 * **Por qué no es un botón redondo como los del transporte**: éstos son para
 * acciones de reproducción, y este es una sugerencia temporal. Un rectángulo
 * con texto se lee a tres metros y no compite con el play central.
 *
 * El foco se pide al aparecer: en una TV, un botón que hay que ir a buscar con
 * el mando mientras pasan los primeros segundos de la cabecera llega tarde.
 * Pero no se roba el foco si el usuario ya está en otro sitio —lo decide quien
 * lo pinta—, y por eso el `focusRequester` es opcional.
 */
@Composable
internal fun SkipSegmentChip(
    segment: SkipSegment,
    deviceProfile: DeviceProfile,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember(segment) { mutableStateOf(false) }
    val alpha = if (deviceProfile.requiresDpadFocus) 1f else 0.92f

    Row(
        modifier = modifier
            .widthIn(min = 168.dp)
            .background(
                color = if (focused) Color.White.copy(alpha = alpha) else Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSkip)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = segment.type.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) Color.Black else StreamflixColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
