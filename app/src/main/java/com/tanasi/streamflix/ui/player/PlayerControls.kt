package com.tanasi.streamflix.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.player.FailoverState
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow
import com.tanasi.streamflix.util.Durations

/**
 * Controles del reproductor, pensados primero para un mando.
 *
 * Sustituyen al `PlayerView.setUseController(true)` de Media3 por tres motivos:
 *
 *  1. **D-Pad explícito.** El controlador de Media3 no documenta el salto con
 *     las direcciones; aquí `◀`/`▶` retroceden/avanzan 10 s y `OK` pausa, que
 *     es lo que espera cualquiera con un Fire TV o un mando de TV.
 *  2. **Silencio.** Todo el bloque se desvanece a los 4 s y vuelve a
 *     componerse con una tecla o un toque; nada de barras permanentes.
 *  3. **Estética única.** El foco dibuja el mismo borde+halo que las tarjetas
 *     del Home ([tvFocusGlow]), así que el salto del catálogo al vídeo no
 *     cambia de lenguaje visual.
 *
 * @param playPauseFocusRequester lo usa `PlayerScreen` para llevar el foco al
 *   botón central cuando el usuario pulsa `ABAJO` con los controles ocultos.
 */
@Composable
fun PlayerControls(
    title: String,
    isLive: Boolean,
    failover: FailoverState,
    sourcesCount: Int,
    visible: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    deviceProfile: DeviceProfile,
    playPauseFocusRequester: FocusRequester,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onShowSources: () -> Unit,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 150)),
        exit = fadeOut(animationSpec = tween(durationMillis = 220)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                /* El degradado no captura toques: sólo oscurece el vídeo para
                 * que el texto blanco se lea sobre cualquier escena. */
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.72f),
                        0.30f to Color.Black.copy(alpha = 0.10f),
                        0.70f to Color.Black.copy(alpha = 0.10f),
                        1.00f to Color.Black.copy(alpha = 0.82f),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = deviceProfile.horizontalPaddingDp.dp,
                        end = deviceProfile.horizontalPaddingDp.dp,
                        top = if (deviceProfile.requiresDpadFocus) 28.dp else 14.dp,
                        bottom = if (deviceProfile.requiresDpadFocus) 36.dp else 20.dp,
                    ),
            ) {
                TitleBar(
                    title = title,
                    isLive = isLive,
                    failover = failover,
                    sourcesCount = sourcesCount,
                )

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /* En directo no se puede retroceder: el manifiesto HLS ya
                     * no tiene el segmento y el salto sólo provoca un rebuffer. */
                    if (!isLive) {
                        ControlButton(
                            text = "−10 s",
                            deviceProfile = deviceProfile,
                            onClick = {
                                onInteraction()
                                onRewind()
                            },
                            modifier = Modifier.width(if (deviceProfile.requiresDpadFocus) 132.dp else 96.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                    }

                    ControlButton(
                        text = if (isPlaying) "Pausa" else "Reproducir",
                        deviceProfile = deviceProfile,
                        primary = true,
                        focusRequester = playPauseFocusRequester,
                        onClick = {
                            onInteraction()
                            onPlayPause()
                        },
                        modifier = Modifier.width(if (deviceProfile.requiresDpadFocus) 176.dp else 132.dp),
                    )

                    if (!isLive) {
                        Spacer(Modifier.width(12.dp))
                        ControlButton(
                            text = "+10 s",
                            deviceProfile = deviceProfile,
                            onClick = {
                                onInteraction()
                                onForward()
                            },
                            modifier = Modifier.width(if (deviceProfile.requiresDpadFocus) 132.dp else 96.dp),
                        )
                    }
                }

                Spacer(Modifier.height(if (deviceProfile.requiresDpadFocus) 20.dp else 14.dp))

                if (isLive) {
                    LiveRow()
                } else {
                    ProgressRow(positionMs = positionMs, durationMs = durationMs)
                }

                Spacer(Modifier.height(if (deviceProfile.requiresDpadFocus) 18.dp else 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlButton(
                        text = "Fuentes",
                        deviceProfile = deviceProfile,
                        onClick = {
                            onInteraction()
                            onShowSources()
                        },
                        modifier = Modifier.width(if (deviceProfile.requiresDpadFocus) 148.dp else 112.dp),
                    )
                    ControlButton(
                        text = "Volver",
                        deviceProfile = deviceProfile,
                        onClick = {
                            onInteraction()
                            onBack()
                        },
                        modifier = Modifier.width(if (deviceProfile.requiresDpadFocus) 148.dp else 112.dp),
                    )

                    Spacer(Modifier.weight(1f))

                    if (deviceProfile.requiresDpadFocus) {
                        /* Chuleta de teclas: el usuario no tiene por qué
                         * adivinar que las flechas saltan 10 s. */
                        Text(
                            text = if (isLive) "OK pausa · ATRÁS salir" else "◀ ▶ 10 s · OK pausa · ATRÁS salir",
                            style = MaterialTheme.typography.labelMedium,
                            color = StreamflixColors.OnSurfaceMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleBar(
    title: String,
    isLive: Boolean,
    failover: FailoverState,
    sourcesCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (isLive) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = "EN VIVO",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .background(StreamflixColors.Live, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        failover.current?.let { source ->
            Spacer(Modifier.width(14.dp))
            Text(
                text = "Fuente ${failover.position} de $sourcesCount · ${source.label}",
                style = MaterialTheme.typography.labelMedium,
                color = StreamflixColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Barra de progreso de VOD: sin interacción, el salto es cosa del D-Pad. */
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
            color = Color.White,
            modifier = Modifier.width(64.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.24f), RoundedCornerShape(2.dp)),
        ) {
            /* `fillMaxWidth(0f)` es un caso límite en Compose: cuando no hay
             * avance no se compone la barra rellena. */
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(StreamflixColors.Primary, RoundedCornerShape(2.dp)),
                )
            }
        }
        Text(
            text = if (durationMs > 0L) Durations.clock(durationMs) else Durations.UNKNOWN,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * En directo no hay barra de progreso: el manifiesto HLS sólo contiene la
 * ventana actual y la posición respecto al inicio de la conexión no le dice
 * nada al usuario.
 */
@Composable
private fun LiveRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .height(8.dp)
                .width(8.dp)
                .background(StreamflixColors.Live, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Señal en directo",
            style = MaterialTheme.typography.labelMedium,
            color = StreamflixColors.OnSurfaceMuted,
        )
    }
}

/**
 * Botón de los controles.
 *
 * En TV dibuja borde+halo y crece un 6 % al recibir el foco; en móvil mantiene
 * el fondo translúcido y el ripple. El `onFocusChanged` va **antes** de
 * `clickable` para envolver al nodo enfocable.
 */
@Composable
private fun ControlButton(
    text: String,
    deviceProfile: DeviceProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val isTv = deviceProfile.requiresDpadFocus

    val focusProgress by animateFloatAsState(
        targetValue = if (isTv && focused) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "controlFocus",
    )
    val scale by animateFloatAsState(
        targetValue = if (isTv && focused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "controlScale",
    )

    val shape = RoundedCornerShape(8.dp)
    val background = when {
        primary && isTv && focused -> StreamflixColors.Primary
        primary -> StreamflixColors.Primary.copy(alpha = 0.82f)
        focused -> Color.White.copy(alpha = 0.24f)
        else -> Color.White.copy(alpha = 0.14f)
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tvFocusGlow(
                progress = focusProgress,
                cornerRadius = 8.dp,
                color = StreamflixColors.FocusRing,
            )
            .background(background, shape)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            /* El botón primario es blanco: el texto tiene que ser negro. */
            color = if (primary) Color.Black else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
