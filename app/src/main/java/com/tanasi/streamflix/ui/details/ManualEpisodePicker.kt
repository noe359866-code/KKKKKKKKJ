package com.tanasi.streamflix.ui.details

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.data.model.EpisodeOption
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/**
 * Respaldo explícito: no fabrica listas ni cambia de capítulo por su cuenta.
 *
 * Con mando no hay teclado cómodo, así que en vez de dos `TextField` de Material
 * (que en una TV abren el teclado del sistema, roban el foco y no se navegan
 * bien) hay dos contadores con **−** y **+**, y una cápsula blanca para
 * confirmar. Todo se recorre con el D-Pad.
 */
@Composable
fun ManualEpisodePicker(selected: EpisodeOption?, onSelect: (EpisodeOption) -> Unit) {
    var season by remember { mutableStateOf(selected?.season ?: 1) }
    var episode by remember { mutableStateOf(selected?.episode ?: 1) }
    val valid = season in 0..100 && episode in 1..10000

    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberStepper(
            label = "Temporada",
            value = season,
            onChange = { season = (season + it).coerceIn(0, 100) },
        )
        NumberStepper(
            label = "Episodio",
            value = episode,
            onChange = { episode = (episode + it).coerceIn(1, 10000) },
        )
        PrimaryPill(
            text = "Ver este episodio",
            enabled = valid,
            onClick = { onSelect(EpisodeOption(season = season, episode = episode)) },
        )
    }
}

/** Etiqueta + valor entre dos botones redondos. */
@Composable
private fun NumberStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = StreamflixColors.OnSurfaceMuted,
        )
        Spacer(Modifier.width(10.dp))
        RoundStepButton(symbol = "−", onClick = { onChange(-1) })
        Box(
            modifier = Modifier.width(46.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = StreamflixColors.OnSurface,
            )
        }
        RoundStepButton(symbol = "+", onClick = { onChange(+1) })
    }
}

/** Botón redondo de paso: vidrio, anillo blanco al enfocar. */
@Composable
private fun RoundStepButton(symbol: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "stepFocus",
    )

    Box(
        modifier = Modifier
            .size(34.dp)
            .tvFocusGlow(progress = focusProgress, cornerRadius = 17.dp)
            .background(
                if (focused) StreamflixColors.GlassStrong else Color.White.copy(alpha = 0.07f),
                CircleShape,
            )
            .border(1.dp, Color.White.copy(alpha = if (focused) 0.4f else 0.12f), CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/** Cápsula blanca de confirmación. */
@Composable
private fun PrimaryPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "confirmFocus",
    )
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = Modifier
            .tvFocusGlow(progress = focusProgress, cornerRadius = 24.dp)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.10f), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.Black else StreamflixColors.OnSurfaceMuted,
            maxLines = 1,
        )
    }
}
