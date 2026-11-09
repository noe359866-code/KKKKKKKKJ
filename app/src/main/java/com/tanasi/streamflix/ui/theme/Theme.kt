package com.tanasi.streamflix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tanasi.streamflix.core.DeviceProfile
import com.tanasi.streamflix.core.LocalDeviceProfile

/**
 * Sistema visual propio de Streamflix TV: alto contraste, foco inequívoco y
 * superficies discretas para que el arte y las acciones se lean desde el sofá.
 * No depende de marcas ni de una plataforma de TV específica.
 *
 * Reglas que se siguen en todas las pantallas:
 *
 *  1. **Fondo negro neutro**, que no compite con pósteres ni vídeo.
 *  2. **Acento blanco** para la acción primaria y el foco; el arte aporta color.
 *  3. **Rojo reservado para peligro o contenido en directo**, nunca como foco.
 *  4. **Grises neutros** para información secundaria; su tamaño y contraste se
 *     deben comprobar a distancia en televisores reales.
 */
object StreamflixColors {
    /** Fondo: negro puro. */
    val Background = Color(0xFF000000)

    /** Superficie elevada (hojas, tarjetas vacías, diálogos). */
    val Surface = Color(0xFF1C1C1E)
    val SurfaceVariant = Color(0xFF2C2C2E)

    /** Acento de la interfaz: blanco. Botones primarios y barras de progreso. */
    val Primary = Color(0xFFFFFFFF)
    val PrimaryVariant = Color(0xFFE5E5EA)

    /** Texto sobre el acento (cápsula blanca → texto negro). */
    val OnPrimary = Color(0xFF000000)

    val OnSurface = Color(0xFFFFFFFF)
    val OnSurfaceMuted = Color(0xFFAAAAAF)

    /** Foco del D-Pad: anillo blanco fino, visible sobre el arte. */
    /** Rojo de peligro (borrar perfil, error). No es el rojo de un directo. */
    val Live = Color(0xFFFF453A)
    val FocusRing = Color(0xFFFFFFFF)

    /** Alias histórico (el neón anterior). Hoy es el mismo blanco del foco. */
    val Neon = Color(0xFFFFFFFF)

    /** Fondo de tarjeta mientras carga el póster. */
    val PosterPlaceholder = Color(0xFF1C1C1E)

    /** Vidrio de las insignias: negro translúcido sobre el arte. */
    val BadgeGlass = Color(0x99000000)

    /** Vidrio de los botones secundarios del hero (12 % / 22 % de blanco). */
    val Glass = Color(0x1FFFFFFF)
    val GlassStrong = Color(0x38FFFFFF)

    val Scrim = Color(0xFF000000)
}

private val StreamflixColorScheme = darkColorScheme(
    primary = StreamflixColors.Primary,
    onPrimary = StreamflixColors.OnPrimary,
    primaryContainer = StreamflixColors.PrimaryVariant,
    onPrimaryContainer = StreamflixColors.OnPrimary,
    background = StreamflixColors.Background,
    onBackground = StreamflixColors.OnSurface,
    surface = StreamflixColors.Surface,
    onSurface = StreamflixColors.OnSurface,
    surfaceVariant = StreamflixColors.SurfaceVariant,
    onSurfaceVariant = StreamflixColors.OnSurfaceMuted,
    error = Color(0xFFFF453A),
    onError = Color(0xFF000000),
)

/**
 * Tipografía de televisión.
 *
 * No se escala la de Material: se define aparte. Tres decisiones:
 *
 *  - **Tamaños grandes de verdad.** A 3 metros, 16 sp no se lee; el cuerpo base
 *    va en 17 sp y los títulos de fila en 22 sp.
 *  - **Interletraje negativo en los títulos** (`-0.4 sp`): es lo que hace que
 *    un titular se lea como "de plataforma" y no como un formulario.
 *  - **Pesos contenidos.** Los encabezados usan semibold para no competir con
 *    la imagen destacada.
 */
private fun streamflixTvTypography(): Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 64.sp,
        lineHeight = 68.sp,
        letterSpacing = (-1.6).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 52.sp,
        lineHeight = 56.sp,
        letterSpacing = (-1.2).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.0).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun StreamflixTheme(
    deviceProfile: DeviceProfile = LocalDeviceProfile.current,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDeviceProfile provides deviceProfile) {
        MaterialTheme(
            colorScheme = StreamflixColorScheme,
            typography = streamflixTvTypography(),
            content = content,
        )
    }
}

/** Medidas comunes de la UI (evita números mágicos repartidos). */
object Dimens {
    /** Aire entre filas para distinguir secciones a distancia. */
    val rowSpacing = 34.dp

    /** Radio de tarjeta de Streamflix TV: suave sin parecer una cápsula. */
    val cardCornerRadius = 10.dp

    /** Anillo de foco: fino y blanco. */
    val focusBorder = 2.dp
}
