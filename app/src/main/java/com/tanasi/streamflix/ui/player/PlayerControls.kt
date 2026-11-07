package com.tanasi.streamflix.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.player.FailoverState
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow
import com.tanasi.streamflix.util.Durations

/**
 * Controles del reproductor: **sólo Android TV**.
 *
 * Se sustituye el controlador de Media3 por una capa propia porque el mando no
 * es un dedo: aquí no hay barra que arrastrar, ni botones de 32 dp, ni gestos.
 * Todo se resuelve con cuatro teclas y con el foco, que es lo único que el
 * usuario ve a tres metros.
 *
 * Lenguaje visual — el mismo de la app Apple TV:
 *
 *  - **Tres zonas**: título arriba, transporte en el centro, progreso abajo.
 *    El vídeo queda visible en medio; los velos son degradados, no cajas.
 *  - **Botones redondos**: el central es blanco (la única pieza clara) con el
 *    icono dibujado en `Canvas`, así que no depende de ninguna fuente de iconos
 *    y se ve igual en cualquier TV box.
 *  - **Foco**: anillo blanco fino + escala 1,08, idéntico al de las tarjetas.
 *  - **Sin táctil**: no hay `pointerInput`, ni `clickable` de arrastre, ni
 *    tiempos de pulsación larga. Izquierda/derecha saltan 10 s y el resto lo
 *    resuelven los botones.
 *
 * @param playPauseFocusRequester lo usa `PlayerScreen` para llevar el foco al
 *   botón central al pulsar ABAJO con los controles ocultos.
 * @param hasTracks sólo se dibuja el botón de pistas si hay algo que elegir
 *   (subtítulos en el manifiesto, pistas externas o más de un audio).
 */
@Composable
fun PlayerControls(
    title: String,
    failover: FailoverState,
    sourcesCount: Int,
    visible: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    canSeek: Boolean,
    deviceProfile: DeviceProfile,
    playPauseFocusRequester: FocusRequester,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onShowSources: () -> Unit,
    onInteraction: () -> Unit,
    /** Hay audio o subtítulos que elegir: se dibuja el botón "Pistas". */
    hasTracks: Boolean = false,
    /** `true` cuando hay subtítulos activos (el botón se pinta encendido). */
    subtitlesOn: Boolean = false,
    onShowTracks: () -> Unit = {},
    sourcesFocusRequester: FocusRequester? = null,
    tracksFocusRequester: FocusRequester? = null,
) {
    val compact = deviceProfile.screenWidthDp < 850
    val controlSize = if (compact) 68.dp else 86.dp
    val primarySize = if (compact) 88.dp else 108.dp
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 150)),
        exit = fadeOut(animationSpec = tween(durationMillis = 240)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.80f),
                        0.22f to Color.Black.copy(alpha = 0.25f),
                        0.55f to Color.Transparent,
                        0.78f to Color.Black.copy(alpha = 0.35f),
                        1.00f to Color.Black.copy(alpha = 0.88f),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = deviceProfile.horizontalPaddingDp.dp + 8.dp,
                        end = deviceProfile.horizontalPaddingDp.dp + 8.dp,
                        top = maxOf(34, deviceProfile.verticalPaddingDp).dp,
                        bottom = maxOf(40, deviceProfile.verticalPaddingDp).dp,
                    ),
            ) {
                TitleBlock(
                    title = title,
                    failover = failover,
                    sourcesCount = sourcesCount,
                )

                Spacer(Modifier.weight(1f))

                /* --- transporte ------------------------------------------- */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 26.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControl(
                        label = "−10",
                        accessibilityLabel = "Retroceder diez segundos",
                        size = controlSize,
                        enabled = canSeek,
                        onClick = {
                            onInteraction()
                            onRewind()
                        },
                    )

                    RoundControl(
                        label = null,
                        size = primarySize,
                        accessibilityLabel = if (isPlaying) "Pausar" else "Reproducir",
                        primary = true,
                        focusRequester = playPauseFocusRequester,
                        onClick = {
                            onInteraction()
                            onPlayPause()
                        },
                    ) {
                        PlayPauseGlyph(playing = isPlaying, tint = Color.Black)
                    }

                    RoundControl(
                        label = "+10",
                        accessibilityLabel = "Avanzar diez segundos",
                        size = controlSize,
                        enabled = canSeek,
                        onClick = {
                            onInteraction()
                            onForward()
                        },
                    )

                    RoundControl(
                        label = "Fuentes",
                        size = controlSize,
                        focusRequester = sourcesFocusRequester,
                        onClick = {
                            onInteraction()
                            onShowSources()
                        },
                    )

                    /* Un solo botón para audio y subtítulos: el panel que abre
                     * es el mismo de tvOS ("Audio y subtítulos"), con las dos
                     * secciones. Dos botones no caben en 960 dp y el usuario
                     * no debería recordar cuál tiene los subtítulos. */
                    if (hasTracks) {
                        RoundControl(
                            label = "Pistas",
                            size = controlSize,
                            focusRequester = tracksFocusRequester,
                            accent = subtitlesOn,
                            onClick = {
                                onInteraction()
                                onShowTracks()
                            },
                        )
                    }
                }

                Spacer(Modifier.height(34.dp))

                ProgressRow(positionMs = positionMs, durationMs = durationMs)

                Spacer(Modifier.height(22.dp))

                Text(
                    text = if (canSeek) {
                        "◀ ▶ 10 s · OK pausa · Pistas audio/CC · ATRÁS salir"
                    } else {
                        "OK pausa · Pistas audio/CC · ATRÁS salir"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = StreamflixColors.OnSurfaceMuted.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Título + estado + fuente en uso.
 *
 * La fuente se muestra a la derecha porque es el dato que el failover cambia
 * por su cuenta: si el usuario ve "Fuente 2 de 5", sabe que el vídeo que está
 * viendo no es el primero que eligió y que la app resolvió sola.
 */
@Composable
private fun TitleBlock(
    title: String,
    failover: FailoverState,
    sourcesCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = failover.current?.let { source ->
                        "Fuente ${failover.position} de $sourcesCount · ${source.label}"
                    } ?: "Buscando señal…",
                    style = MaterialTheme.typography.labelMedium,
                    color = StreamflixColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Botón circular del transporte.
 *
 * `label` nulo = botón central, que pinta su contenido (el icono de
 * play/pausa). `accent` marca un botón activo (subtítulos encendidos).
 */
@Composable
private fun RoundControl(
    label: String?,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    accent: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    accessibilityLabel: String? = null,
    content: @Composable (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 130),
        label = "roundFocus",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 130),
        label = "roundScale",
    )

    val background = when {
        primary -> if (focused) Color.White else Color.White.copy(alpha = 0.94f)
        accent -> Color.White.copy(alpha = if (focused) 0.34f else 0.26f)
        focused -> StreamflixColors.GlassStrong
        else -> StreamflixColors.Glass
    }
    val contentColor = if (primary) Color.Black else Color.White

    Box(
        modifier = modifier
            .then(if (accessibilityLabel != null) Modifier.semantics { contentDescription = accessibilityLabel } else Modifier)
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .tvFocusGlow(progress = ring, cornerRadius = size / 2)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(background, CircleShape)
            .border(
                width = 1.dp,
                color = when {
                    primary -> Color.Transparent
                    accent -> Color.White.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.16f)
                },
                shape = CircleShape,
            )
            /* `onFocusChanged` antes de `clickable`: envuelve al nodo enfocable. */
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val custom = content
        if (custom != null) {
            custom()
        } else {
            val text = label ?: ""
            Text(
                text = text,
                style = if (text.length <= 3) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.labelMedium
                },
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/**
 * Icono de play/pausa dibujado a mano.
 *
 * Sin `material-icons-extended` (30 MB de fuentes para dos formas) y sin
 * glifos `▶`/`❚❚`, que algunos TV boxes renderizan como cajas: un triángulo y
 * dos barras en `Canvas` se ven igual en cualquier parte.
 */
@Composable
private fun PlayPauseGlyph(playing: Boolean, tint: Color) {
    Canvas(modifier = Modifier.size(34.dp)) {
        if (playing) {
            val barWidth = size.width * 0.24f
            val radius = CornerRadius(barWidth * 0.35f)
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.22f, size.height * 0.14f),
                size = Size(barWidth, size.height * 0.72f),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.54f, size.height * 0.14f),
                size = Size(barWidth, size.height * 0.72f),
                cornerRadius = radius,
            )
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.12f)
                lineTo(size.width * 0.86f, size.height * 0.50f)
                lineTo(size.width * 0.28f, size.height * 0.88f)
                close()
            }
            drawPath(path, color = tint)
        }
    }
}

/**
 * Barra de progreso.
 *
 * No es interactiva a propósito: en una TV se salta con las flechas, no
 * arrastrando. El punto del final de la barra indica la posición exacta sin
 * necesidad de un cursor.
 */
@Composable
private fun ProgressRow(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Durations.clock(positionMs),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.9f),
            /* Ancho fijo: con números proporcionales la barra baila cada vez
             * que cambia un dígito. */
            modifier = Modifier.width(72.dp),
        )
        Spacer(Modifier.width(16.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(3.dp)),
            )
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(Color.White, RoundedCornerShape(3.dp)),
                )
                /* Punto de posición: se desplaza con el % ya calculado. */
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .padding(end = 0.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier
                            .offset(x = 7.dp)
                            .size(14.dp)
                            .background(Color.White, CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = if (durationMs > 0L) Durations.clock(durationMs) else Durations.UNKNOWN,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.End,
            modifier = Modifier.width(72.dp),
        )
    }
}

