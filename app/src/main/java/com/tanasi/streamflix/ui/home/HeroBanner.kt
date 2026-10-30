package com.tanasi.streamflix.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.Spotlight
import com.tanasi.streamflix.ui.components.HeroBackdropImage
import com.tanasi.streamflix.ui.components.GlassBadge
import com.tanasi.streamflix.ui.components.LiveBadge
import com.tanasi.streamflix.ui.components.RatingBadge
import com.tanasi.streamflix.ui.components.TrendingBadge
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/**
 * Hero cinematográfico (inspiración Apple TV+).
 *
 * Tres cosas lo hacen parecer "de plataforma":
 *
 *  1. **Backdrop que sigue al foco.** No hay carrusel automático: la imagen es
 *     la de la tarjeta que el usuario está mirando. Con un mando eso se siente
 *     como si la app leyera la intención; con el dedo se queda en el destacado
 *     inicial hasta que se toca otra tarjeta.
 *  2. **Ken Burns eterno.** El fondo se mueve a un 6 % de escala durante 20 s,
 *     en una única animación infinita sobre `graphicsLayer` (no recompone).
 *  3. **Doble velo.** Un degradado horizontal protege el texto a la izquierda y
 *     uno vertical funde el hero con las filas, así no hay un corte duro entre
 *     la foto y el catálogo.
 *
 * @param onPlay reproduce directamente: el reproductor busca las fuentes y
 *   hace el failover, así que aquí no se elige servidor.
 * @param onDetails abre la ficha con sinopsis y episodios.
 * @param upFocus destino del D-Pad al pulsar ARRIBA (la barra superior).
 * @param ctaDownFocus destino al pulsar ABAJO desde cualquier botón: la primera
 *   tarjeta de la primera fila, que es donde el usuario quiere volver.
 * @param contentBottomPadding aire entre los botones y el borde inferior de la
 *   caja del hero. En TV el backdrop se prolonga por debajo (las filas se
 *   deslizan encima) y el texto tiene que quedar más arriba, a la altura a la
 *   que arranca la primera fila.
 */
@Composable
fun HeroBanner(
    spotlight: Spotlight?,
    deviceProfile: DeviceProfile,
    playFocusRequester: FocusRequester,
    detailsFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    upFocus: FocusRequester? = null,
    ctaDownFocus: FocusRequester? = null,
    contentBottomPadding: Dp? = null,
) {
    if (spotlight == null) {
        Box(modifier.fillMaxSize().background(StreamflixColors.Background))
        return
    }

    val isTv = deviceProfile.requiresDpadFocus

    Box(modifier = modifier.fillMaxSize()) {
        /* --- fondo ------------------------------------------------------- */
        Crossfade(
            targetState = spotlight,
            animationSpec = tween(durationMillis = 420),
            label = "heroBackdrop",
        ) { current ->
            KenBurnsBackdrop(
                entry = current.entry,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.00f to Color.Black.copy(alpha = 0.92f),
                        0.42f to Color.Black.copy(alpha = 0.55f),
                        0.72f to Color.Black.copy(alpha = 0.10f),
                        1.00f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.55f),
                        0.30f to Color.Transparent,
                        0.72f to StreamflixColors.Background.copy(alpha = 0.35f),
                        1.00f to StreamflixColors.Background,
                    ),
                ),
        )

        /* --- contenido --------------------------------------------------- */
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .widthIn(max = if (isTv) 780.dp else 560.dp)
                .padding(
                    start = deviceProfile.horizontalPaddingDp.dp,
                    end = deviceProfile.horizontalPaddingDp.dp,
                    bottom = contentBottomPadding
                        ?: if (isTv) 40.dp else 20.dp,
                ),
        ) {
            val entry = spotlight.entry

            /* tvOS no llena el hero de etiquetas: como mucho el estado (en
             * directo / tendencia) y, debajo del título, los metadatos. */
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (spotlight.isLive) {
                    LiveBadge()
                    Spacer(Modifier.width(10.dp))
                } else if (entry.trending) {
                    TrendingBadge()
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = spotlight.rowTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = StreamflixColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(if (isTv) 12.dp else 8.dp))

            Text(
                text = entry.title,
                style = if (isTv) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(if (isTv) 10.dp else 6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.rating?.let { rating ->
                    RatingBadge(rating)
                    Spacer(Modifier.width(8.dp))
                }
                entry.quality?.let { quality ->
                    GlassBadge(text = quality)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = entry.metaLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = StreamflixColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            entry.synopsis?.let { synopsis ->
                Spacer(Modifier.height(if (isTv) 12.dp else 8.dp))
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StreamflixColors.OnSurface.copy(alpha = 0.86f),
                    maxLines = if (isTv) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(if (isTv) 22.dp else 14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroButton(
                    text = "▶  Reproducir",
                    deviceProfile = deviceProfile,
                    primary = true,
                    focusRequester = playFocusRequester,
                    upFocus = upFocus,
                    downFocus = ctaDownFocus,
                    onClick = onPlay,
                )
                HeroButton(
                    text = if (isTv) "Más información" else "Info",
                    deviceProfile = deviceProfile,
                    focusRequester = detailsFocusRequester,
                    upFocus = upFocus,
                    downFocus = ctaDownFocus,
                    onClick = onDetails,
                )
            }
        }
    }
}

/**
 * Fondo con movimiento lento.
 *
 * `rememberInfiniteTransition` + `graphicsLayer`: la animación corre en el hilo
 * de render y no invalida la composición, así que un TV box barato no sufre.
 */
@Composable
private fun KenBurnsBackdrop(
    entry: MediaEntry,
    modifier: Modifier = Modifier,
) {
    val motion by rememberInfiniteTransition(label = "kenBurns").animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "kenBurnsScale",
    )

    /* backdrop 16:9 → póster desenfocado → arte local. Así el hero nunca
     * aparece como un bloque de color liso. */
    HeroBackdropImage(
        entry = entry,
        modifier = modifier.graphicsLayer {
            scaleX = motion
            scaleY = motion
        },
    )
}

/**
 * Botón del hero.
 *
 * `primary` (Netflix): fondo blanco y texto negro — el único elemento claro de
 * la pantalla, así que el ojo va solo. Los secundarios van en vidrio sobre el
 * backdrop. Al recibir foco todos ganan el anillo neón y crecen un 4 %.
 */
@Composable
private fun HeroButton(
    text: String,
    deviceProfile: DeviceProfile,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    primary: Boolean = false,
    upFocus: FocusRequester? = null,
    downFocus: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }

    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 130),
        label = "heroFocus",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "heroScale",
    )

    /* Cápsula, como los botones de tvOS. `RoundedCornerShape(percent = 50)`
     * siempre es un óvalo perfecto, sin depender de la altura. */
    val shape = RoundedCornerShape(percent = 50)
    val background = when {
        primary && focused -> Color.White
        primary -> Color.White.copy(alpha = 0.92f)
        focused -> StreamflixColors.GlassStrong
        else -> StreamflixColors.Glass
    }
    val contentColor = if (primary) Color.Black else Color.White

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvFocusGlow(progress = focusProgress, cornerRadius = 28.dp)
            .focusProperties {
                /* Se asigna el destino del D-Pad sólo si existe: dejar el
                 * `FocusRequester.Default` es lo correcto cuando no lo hay. */
                upFocus?.let { requester -> up = requester }
                downFocus?.let { requester -> down = requester }
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(background, shape)
            .border(
                width = 1.dp,
                color = when {
                    /* En la cápsula blanca el borde sólo aporta al enfocar. */
                    primary -> Color.Transparent
                    focused -> Color.White.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.14f)
                },
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(
                horizontal = 26.dp,
                vertical = 14.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
