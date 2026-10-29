package com.tanasi.streamflix.data.model

import kotlinx.serialization.Serializable

/**
 * Un perfil de la app, al estilo de los usuarios de Apple TV.
 *
 * La app es de salón: en la misma televisión ven cosas distintas un adulto
 * (series largas, deportes) y un niño (anime, dibujos). Sin perfiles, "Seguir
 * viendo" mezclaba las dos cosas en la misma fila. Un perfil sólo guarda
 * identidad y color; el progreso vive en `WatchProgressStore` **por perfil**.
 *
 * No depende de Android ni de Compose a propósito: así se puede probar en la
 * JVM y la UI decide los colores reales a partir de [colorIndex].
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** Índice en la paleta de avatares de la UI (0..[PROFILE_COLORS] - 1). */
    val colorIndex: Int = 0,
    /** Perfil infantil: la UI puede filtrar catálogos para niños. */
    val kids: Boolean = false,
) {
    val initials: String get() = profileInitials(name)
}

/** Cuántos perfiles caben en una fila de avatares sin hacer scroll. */
const val MAX_PROFILES = 6

/** Tamaño de la paleta de avatares (ver `ui/theme/ProfilePalette`). */
const val PROFILE_COLORS = 6

/**
 * Nombre utilizable: sin espacios de más, sin saltos de línea y acotado.
 *
 * Escribir con el mando es lento y el teclado de Android TV deja pasar
 * cualquier cosa; aquí se normaliza una sola vez para que el avatar, el
 * `SharedPreferences` y la fila del Home digan lo mismo.
 */
fun profileName(raw: String): String {
    val cleaned = raw.replace(Regex("\\s+"), " ").trim()
    return when {
        cleaned.isEmpty() -> "Espectador"
        cleaned.length <= 20 -> cleaned
        else -> cleaned.take(20).trim()
    }
}

/**
 * Iniciales para el avatar circular: una letra si el nombre es una palabra
 * ("Ana" → "A"), dos si son dos o más ("Ana Gómez" → "AG").
 */
fun profileInitials(name: String): String {
    val words = profileName(name).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return "?"
    val first = words.first().first().uppercaseChar()
    val second = words.getOrNull(1)?.firstOrNull()?.uppercaseChar()
    return if (second == null) "$first" else "$first$second"
}

/** Color libre siguiente, para que dos perfiles nuevos no salgan iguales. */
fun nextColorIndex(existing: List<Profile>): Int {
    val used = existing.map { it.colorIndex }.toSet()
    return (0 until PROFILE_COLORS).firstOrNull { it !in used }
        ?: (existing.size % PROFILE_COLORS)
}

/** `Espectador`, `Espectador 2`, `Espectador 3`… sin repetir nombres. */
fun defaultProfileName(existing: List<Profile>): String {
    val taken = existing.map { it.name }.toSet()
    if ("Espectador" !in taken) return "Espectador"
    var index = 2
    while ("Espectador $index" in taken) index++
    return "Espectador $index"
}
