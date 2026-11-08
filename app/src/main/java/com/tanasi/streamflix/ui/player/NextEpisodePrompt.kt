package com.tanasi.streamflix.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.ui.theme.StreamflixColors

/**
 * "¿Ver el siguiente?" cuando el episodio se acaba.
 *
 * Tres decisiones que no son obvias:
 *
 *  - **Sin cuenta atrás.** Muchos reproductores avanzan solos a los diez
 *    segundos. Con un mando y a tres metros, que algo cambie sin que lo pidas
 *    es peor que pedir una pulsación: si te has levantado a por agua, vuelves y
 *    ya estás en otro capítulo sin saber por qué.
 *  - **El foco arranca en "Siguiente"**, no en "Ahora no": es lo que se quiere
 *    el 90 % de las veces. Pero se pide tras unos fotogramas, porque en el
 *    instante de componer el árbol de foco todavía no existe y la petición se
 *    perdería en silencio.
 *  - **El velo cubre la pantalla entera**, no una tarjeta flotante: al terminar,
 *    lo que hay debajo es el último fotograma congelado o negro, y una caja
 *    pequeña encima se lee mal desde el sofá.
 *
 * El velo se pinta con alfa 0,88 y no opaco del todo para que se siga viendo
 * qué había debajo, igual que el resto de superposiciones del reproductor.
 */
@Composable
internal fun NextEpisodePrompt(
    label: String?,
    deviceProfile: DeviceProfile,
    onPlayNext: () -> Unit,
    onStay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playFocus = remember { FocusRequester() }
    val stayFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        /* Se limpia y se espera: si el foco estaba en un botón de los controles
         * que acaba de ocultarse, la petición directa se queda sin destino. */
        focusManager.clearFocus(force = true)
        repeat(3) { withFrameNanos { } }
        runCatching { playFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .focusGroup()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Fin del episodio",
                style = MaterialTheme.typography.labelLarge,
                color = StreamflixColors.OnSurfaceMuted,
            )
            if (!label.isNullOrBlank()) {
                Text(
                    text = "Siguiente · $label",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = StreamflixColors.OnSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PromptButton(
                    text = "Ver el siguiente",
                    primary = true,
                    focusRequester = playFocus,
                    onClick = onPlayNext,
                )
                PromptButton(
                    text = "Ahora no",
                    primary = false,
                    focusRequester = stayFocus,
                    onClick = onStay,
                )
            }
        }
    }
}

/**
 * Botón del prompt.
 *
 * El mismo par visual que `SkipSegmentChip`: relleno blanco con foco, negro con
 * velo sin él. Se mantiene el anillo de foco del tema en lugar de dibujar uno
 * propio, para que el mando se sienta igual aquí que en el resto de la app.
 *
 * Es `internal` y no privado porque el aviso de «¿sigues ahí?» usa el mismo
 * botón: dos copias del mismo control divergen al primer retoque de estilo.
 */
@Composable
internal fun PromptButton(
    text: String,
    primary: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val base = if (primary) Color.White else Color.White.copy(alpha = 0.16f)

    Row(
        modifier = Modifier
            .focusRequester(focusRequester)
            .background(
                color = if (focused) Color.White else base,
                shape = RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (focused) Color.Black else StreamflixColors.OnSurface,
            maxLines = 1,
        )
    }
}
