package com.tanasi.streamflix.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.ui.theme.StreamflixColors

/*
 * Insignias flotantes (estilo Prime Video).
 *
 * Reglas de composición que se repiten en todas las tarjetas:
 *   - esquina superior izquierda: estado (EN VIVO / TOP 10);
 *   - esquina superior derecha: calidad (4K / HD / CAM);
 *   - esquina inferior izquierda: proveedor, o sea el servidor que servirá el
 *     stream y desde el que arrancará el failover;
 *   - borde inferior: barra de progreso en "Continuar viendo".
 *
 * Nunca se apilan más de dos en el mismo sitio: una tarjeta de 128 dp con cinco
 * etiquetas deja de leerse como póster.
 */

/** Pieza base: vidrio oscuro, borde fino, texto en mayúsculas pequeñas. */
@Composable
fun GlassBadge(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = StreamflixColors.BadgeGlass,
    contentColor: Color = Color.White,
    borderColor: Color = Color.White.copy(alpha = 0.16f),
) {
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(4.dp))
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** EN VIVO con punto que late: el único elemento animado en reposo de la app. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "livePulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "livePulseAlpha",
    )

    Row(
        modifier = modifier
            .background(StreamflixColors.Live, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer { alpha = pulse }
                .background(Color.White, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "EN VIVO",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** Calidad anunciada. `4K` va en neón para que destaque sobre el póster. */
@Composable
fun QualityBadge(quality: String, modifier: Modifier = Modifier) {
    val isUltra = quality.contains("4K", ignoreCase = true)
    GlassBadge(
        text = quality,
        modifier = modifier,
        contentColor = if (isUltra) StreamflixColors.Neon else Color.White,
        borderColor = if (isUltra) StreamflixColors.Neon.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.16f),
    )
}

/** Nota tipo Prime: "★ 8.2". */
@Composable
fun RatingBadge(rating: Double, modifier: Modifier = Modifier) {
    GlassBadge(
        text = "★ ${String.format(java.util.Locale.ROOT, "%.1f", rating)}",
        modifier = modifier,
    )
}

/** Insignia de tendencia: "TOP 10" sobre el arte. */
@Composable
fun TrendingBadge(modifier: Modifier = Modifier) {
    GlassBadge(
        text = "TOP 10",
        modifier = modifier,
        /* Blanco, no rojo: el rojo está reservado para "en directo". */
        background = Color.White.copy(alpha = 0.94f),
        contentColor = Color.Black,
        borderColor = Color.White.copy(alpha = 0.24f),
    )
}

/**
 * Servidor activo del failover: "S2 · Gnula".
 *
 * Es la insignia que pide el enunciado ("el servidor activo que está usando el
 * sistema de failover"). Vive sobre todo en el reproductor, porque es el único
 * sitio donde el dato existe de verdad: antes de reproducir sólo se puede
 * mostrar el proveedor previsto, no el que acabó sirviendo el vídeo.
 */
@Composable
fun FailoverBadge(text: String, modifier: Modifier = Modifier) {
    GlassBadge(
        text = text,
        modifier = modifier,
        contentColor = StreamflixColors.Neon,
        borderColor = StreamflixColors.Neon.copy(alpha = 0.55f),
    )
}

/** Piezas por estado de la tarjeta, para no repetir condiciones en cada fila. */
@Composable
internal fun EntryTopStartBadge(entry: MediaEntry, modifier: Modifier = Modifier) {
    when {
        entry.isLive -> LiveBadge(modifier)
        entry.trending -> TrendingBadge(modifier)
        entry.quality != null -> QualityBadge(entry.quality!!, modifier)
        else -> Unit
    }
}

@Composable
internal fun EntryTopEndBadge(entry: MediaEntry, modifier: Modifier = Modifier) {
    /* La nota es el dato que más se mira al elegir: si existe, ocupa la
     * derecha. La calidad sólo se repite aquí cuando el estado de la izquierda
     * no la muestra ya (directos y tendencias). */
    val rating = entry.rating
    val quality = entry.quality
    when {
        rating != null -> RatingBadge(rating, modifier)
        quality != null && (entry.isLive || entry.trending) -> QualityBadge(quality, modifier)
        else -> Unit
    }
}
