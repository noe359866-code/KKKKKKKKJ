package com.tanasi.streamflix.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.tanasi.streamflix.data.model.PROFILE_COLORS

/**
 * Colores de los avatares de perfil.
 *
 * Son degradados apagados a propósito (los `systemIndigo`, `systemPink`,
 * `systemGreen`… de Apple): el avatar tiene que identificar a una persona sin
 * robarle atención al arte del catálogo, que es el que debe brillar.
 */
object ProfilePalette {

    private val gradients: List<Pair<Color, Color>> = listOf(
        Color(0xFF5E5CE6) to Color(0xFFBF5AF2), // índigo → púrpura
        Color(0xFFFF375F) to Color(0xFFFF9F0A), // rosa → naranja
        Color(0xFF30D158) to Color(0xFF64D2FF), // verde → cian
        Color(0xFFFF9F0A) to Color(0xFFFFD60A), // naranja → amarillo
        Color(0xFF64D2FF) to Color(0xFF5E5CE6), // cian → índigo
        Color(0xFFBF5AF2) to Color(0xFFFF375F), // púrpura → rosa
    )

    fun colors(colorIndex: Int): Pair<Color, Color> =
        gradients[Math.floorMod(colorIndex, PROFILE_COLORS)]

    fun brush(colorIndex: Int): Brush {
        val (start, end) = colors(colorIndex)
        return Brush.linearGradient(listOf(start, end))
    }
}
