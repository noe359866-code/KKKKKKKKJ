package com.tanasi.streamflix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.core.LocalDeviceProfile

/**
 * Paleta de la app.
 *
 * En TV no hay "modo claro": el salón está a oscuras y un fondo blanco deslumbra.
 * En móvil se sigue igualmente el tema oscuro para que el paso del catálogo al
 * reproductor (siempre negro) no dé un flash.
 */
object StreamflixColors {
    val Background = Color(0xFF0B0B12)
    val Surface = Color(0xFF15151F)
    val SurfaceVariant = Color(0xFF1F1F2C)
    val Primary = Color(0xFFE53935)
    val PrimaryVariant = Color(0xFFB71C1C)
    val OnSurface = Color(0xFFF2F2F7)
    val OnSurfaceMuted = Color(0xFF9E9EAE)

    /**
     * Foco del D-Pad: neón cian.
     *
     * El rojo ya significa "en vivo" y "marca"; si además fuese el color del
     * foco, en una fila de deportes no se distinguiría qué está seleccionado de
     * qué está emitiendo. El cian no aparece en ningún otro sitio de la
     * interfaz, así que el ojo lo lee como "el cursor está aquí".
     */
    val FocusRing = Color(0xFF3DDCFF)
    val Neon = Color(0xFF3DDCFF)

    /** Rojo de emisión (badge EN VIVO, punto de directo). */
    val Live = Color(0xFFFF1744)

    /** Fondo de tarjeta mientras carga el póster. */
    val PosterPlaceholder = Color(0xFF22222F)

    /** Vidrio de las insignias (Prime): negro translúcido sobre el arte. */
    val BadgeGlass = Color(0xCC101018)
    val Scrim = Color(0xFF000000)
}

private val StreamflixColorScheme = darkColorScheme(
    primary = StreamflixColors.Primary,
    onPrimary = Color.White,
    primaryContainer = StreamflixColors.PrimaryVariant,
    onPrimaryContainer = Color.White,
    background = StreamflixColors.Background,
    onBackground = StreamflixColors.OnSurface,
    surface = StreamflixColors.Surface,
    onSurface = StreamflixColors.OnSurface,
    surfaceVariant = StreamflixColors.SurfaceVariant,
    onSurfaceVariant = StreamflixColors.OnSurfaceMuted,
    error = Color(0xFFFF6E6E),
)

/**
 * Tipografía adaptativa: en TV todo se ve a 3 metros del sofá, así que títulos y
 * etiquetas crecen ~25 %. Se construye aquí y no en cada pantalla para que el
 * cambio sea consistente en toda la app.
 */
@Composable
private fun typographyFor(isTelevision: Boolean): Typography {
    val base = Typography()
    if (!isTelevision) return base

    fun TextStyle.tv(factor: Float) = copy(
        fontSize = fontSize * factor,
        lineHeight = lineHeight * factor,
    )

    return Typography(
        displaySmall = base.displaySmall.tv(1.2f),
        headlineMedium = base.headlineMedium.tv(1.2f),
        headlineSmall = base.headlineSmall.tv(1.25f),
        titleLarge = base.titleLarge.tv(1.25f).copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.tv(1.2f),
        bodyLarge = base.bodyLarge.tv(1.15f),
        bodyMedium = base.bodyMedium.tv(1.15f),
        labelLarge = base.labelLarge.tv(1.15f),
        /* Texto pequeño de las tarjetas: 12 sp en TV es ilegible. */
        labelMedium = base.labelMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
    )
}

@Composable
fun StreamflixTheme(
    deviceProfile: DeviceProfile = LocalDeviceProfile.current,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDeviceProfile provides deviceProfile) {
        MaterialTheme(
            colorScheme = StreamflixColorScheme,
            typography = typographyFor(deviceProfile.isTelevision),
            content = content,
        )
    }
}

/** Medidas comunes de la UI (evita números mágicos repartidos). */
object Dimens {
    val rowSpacing = 28.dp
    val cardCornerRadius = 12.dp
    val focusBorder = 3.dp
}
