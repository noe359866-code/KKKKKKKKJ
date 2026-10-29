package com.tanasi.streamflix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import com.tanasi.streamflix.ui.theme.StreamflixColors

/**
 * Salida accesible si la APK se instala por ADB en un dispositivo no TV.
 * Los filtros de Google Play no se aplican a las instalaciones manuales.
 * No carga catálogo, configuración ni reproducción en ese dispositivo.
 */
@Composable
fun AndroidTvOnlyScreen(onExit: () -> Unit) {
    val exitFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { exitFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamflixColors.Background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Solo para Android TV",
            style = MaterialTheme.typography.headlineMedium,
            color = StreamflixColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Instala Streamflix en un televisor con Android TV o Google TV. La app está diseñada para navegar con el mando.",
            modifier = Modifier.widthIn(max = 560.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = StreamflixColors.OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onExit,
            modifier = Modifier.focusRequester(exitFocus),
        ) {
            Text("Salir")
        }
    }
}
