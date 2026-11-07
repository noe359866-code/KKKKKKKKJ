package com.tanasi.streamflix.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.ui.theme.StreamflixColors

/**
 * «¿Sigues ahí?» cuando la app lleva varios episodios seguidos sin que nadie
 * toque el mando.
 *
 * **Sin cuenta atrás, igual que el aviso de siguiente episodio.** NuvioTV usa
 * 60 s y luego cierra el reproductor; aquí el aviso se queda quieto hasta que
 * alguien decida. Dos razones: en Android TV el mando puede estar en otra
 * habitación durante un rato largo, y sacar sola de la reproducción a alguien
 * que sí estaba mirando —sin haber entendido por qué— es peor que dejar la
 * pantalla quieta. Quieta no gasta datos, que es justo lo que el aviso
 * pretendía evitar.
 *
 * El botón con foco por defecto es **Sí, sigo**, no el de salir: si alguien
 * despierta y pulsa A de memoria, lo que quiere es seguir viendo.
 */
@Composable
internal fun StillWatchingPrompt(
    /** Cuántos episodios van seguidos, para que el texto no sea genérico. */
    episodes: Int,
    onContinue: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val continueFocus = remember { FocusRequester() }
    val stopFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        /* Mismo patrón que `NextEpisodePrompt`: el foco anterior acaba de
         * desaparecer con los controles y una petición inmediata se pierde. */
        focusManager.clearFocus(force = true)
        repeat(3) { withFrameNanos { } }
        runCatching { continueFocus.requestFocus() }
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
                text = "¿Sigues ahí?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = StreamflixColors.OnSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Van $episodes episodios seguidos sin tocar el mando.",
                style = MaterialTheme.typography.labelLarge,
                color = StreamflixColors.OnSurfaceMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PromptButton(
                    text = "Sí, sigo",
                    primary = true,
                    focusRequester = continueFocus,
                    onClick = onContinue,
                )
                PromptButton(
                    text = "Parar",
                    primary = false,
                    focusRequester = stopFocus,
                    onClick = onStop,
                )
            }
        }
    }
}
