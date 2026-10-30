package com.tanasi.streamflix.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.ui.theme.Dimens
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/**
 * Tarjeta de deportes, con la anatomía del Sports de Apple TV.
 *
 * ```
 * ┌────────────────────────────────────────┐
 * │ LIGA 1                       ● EN VIVO │  ← competición + estado
 * │                                        │
 * │  ⬤C  Cienciano                         │  ← equipo local
 * │  ⬤A  Atlético Grau                     │  ← equipo visitante
 * │                                        │
 * │ Fútbol Libre            En vivo · 19:00│  ← de dónde sale + hora
 * └────────────────────────────────────────┘
 * ```
 *
 * No hay arte que enseñar: un partido no tiene póster 2:3, así que la tarjeta
 * se dibuja con tipografía y datos reales (liga, equipos, hora, estado). Los
 * canales deportivos que sí traen logotipo lo pintan a la izquierda del título.
 * Si la fila llegara sin equipos, la tarjeta cae al título del canal.
 */
@Composable
fun SportsCard(
    entry: MediaEntry,
    deviceProfile: DeviceProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (MediaEntry) -> Unit = {},
    focusUp: FocusRequester? = null,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val isTv = deviceProfile.requiresDpadFocus

    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "sportsFocus",
    )
    val scale by animateFloatAsState(
        targetValue = if (isTv && focused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "sportsScale",
    )

    val shape = RoundedCornerShape(Dimens.cardCornerRadius)
    val league = entry.league?.takeIf { it.isNotBlank() }
    val timing = entry.subtitle?.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .width(deviceProfile.landscapeWidthDp.dp)
            .then(if (focusUp != null) Modifier.focusProperties { up = focusUp } else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                val nowFocused = state.isFocused
                if (nowFocused && !focused) onFocused(entry)
                focused = nowFocused
            }
            .clickable(onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (isTv && focused) 12.dp.toPx() else 0f
                this.shape = shape
            }
            .tvFocusGlow(progress = focusProgress, cornerRadius = Dimens.cardCornerRadius)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to StreamflixColors.SurfaceVariant,
                    1f to StreamflixColors.Surface,
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .aspectRatio(16f / 9f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (league ?: "Deportes").uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                    color = StreamflixColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (entry.isLive) {
                    LiveBadge()
                } else if (timing != null) {
                    GlassBadge(text = timing)
                }
            }

            Spacer(Modifier.height(10.dp))

            if (entry.isMatch) {
                TeamLine(name = entry.homeTeam.orEmpty(), isHome = true)
                Spacer(Modifier.height(6.dp))
                TeamLine(name = entry.awayTeam.orEmpty(), isHome = false)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoThumb(entry = entry)
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = StreamflixColors.OnSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.provider.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = StreamflixColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val hour = entry.startTime?.takeIf { it.isNotBlank() }
                if (hour != null) {
                    Text(
                        text = hour,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = StreamflixColors.OnSurfaceMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Una línea de equipo: círculo con iniciales + nombre. */
@Composable
private fun TeamLine(name: String, isHome: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(Color.White.copy(alpha = if (isHome) 0.16f else 0.09f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = teamInitials(name),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = StreamflixColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Logotipo cuadrado del canal, con el mismo tamaño que el círculo de iniciales. */
@Composable
private fun LogoThumb(entry: MediaEntry) {
    val logo = entry.poster?.takeIf { it.isNotBlank() } ?: return
    AsyncImage(
        model = logo,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(34.dp)
            .padding(end = 10.dp),
    )
}

/**
 * Iniciales de un equipo, tomadas de su nombre real.
 *
 * `"Atlético Grau"` → `AG` · `"Cienciano"` → `CI` · `"Club Atlético Boca Juniors"`
 * → `AB` (se saltan los prefijos de club) · sin letras → `?`.
 */
fun teamInitials(name: String): String {
    val words = name
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
    if (words.isEmpty()) return "?"
    val meaningful = words.filterNot { it.lowercase() in CLUB_PREFIXES }.ifEmpty { words }
    if (meaningful.size == 1) {
        return meaningful.first().take(2).uppercase()
    }
    return meaningful.take(2).map { it.first().uppercaseChar() }.joinToString("")
}

private val CLUB_PREFIXES = setOf(
    "club", "fc", "cf", "cd", "sc", "ac", "afc", "de", "del", "la", "el", "los", "las", "y",
    "and", "the",
)
