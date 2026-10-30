package com.tanasi.streamflix.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks

/**
 * Una fila del panel "Audio y subtítulos".
 *
 * @property type `C.TRACK_TYPE_TEXT` o `C.TRACK_TYPE_AUDIO`.
 * @property groupIndex posición del grupo en `Tracks.groups` (lo que necesita
 *   `TrackSelectionOverride`).
 * @property trackIndex pista dentro del grupo; `-1` en las filas de "sin
 *   elegir" ("Desactivado" / "Automático").
 * @property selected estado actual, para pintar la marca.
 */
data class TrackOption(
    val type: Int,
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
) {
    val isAutomatic: Boolean get() = trackIndex < 0
    val id: String get() = "$type:$groupIndex:$trackIndex"
    val isText: Boolean get() = type == C.TRACK_TYPE_TEXT
    val isAudio: Boolean get() = type == C.TRACK_TYPE_AUDIO
}

/**
 * Lectura de las pistas que ofrece el manifiesto en curso.
 *
 * Se separa del composable a propósito: es la única parte del panel que tiene
 * reglas (qué se enseña, en qué orden, qué se marca como activo) y así se puede
 * probar en la JVM sin `ExoPlayer` ni pantalla.
 *
 * Reglas:
 *  - **Audio**: "Automático" es la primera fila. ExoPlayer ya elige la pista
 *    por defecto del manifiesto; forzar un override sin necesidad es lo que
 *    deja sin sonido a los ripeos con una sola pista mal etiquetada.
 *  - **Subtítulos**: "Desactivado" es la primera fila y la activa por defecto
 *    (salvo que la pista en español viniera marcada `DEFAULT`).
 *  - Los grupos de tipo `C.TRACK_TYPE_TEXT` incluyen tanto los subtítulos del
 *    manifiesto (HLS con `SUBTITLES`) como los externos que añade la app.
 */
object PlayerTracks {

    /** Nombre legible de la pista: `label` del manifiesto, idioma o un índice. */
    fun formatLabel(format: Format?, fallback: String): String {
        val label = format?.label?.trim().orEmpty()
        if (label.isNotEmpty()) return label
        val language = languageName(format?.language)
        if (language.isNotEmpty()) return language
        return fallback
    }

    /** `es` → `Español`, `es-419` → `Español (Latino)`, `en` → `Inglés`. */
    fun languageName(language: String?): String {
        val clean = language?.trim()?.lowercase().orEmpty()
        if (clean.isEmpty() || clean == "und") return ""
        val base = clean.substringBefore('-')
        val name = when (base) {
            "es" -> "Español"
            "en" -> "Inglés"
            "pt" -> "Portugués"
            "fr" -> "Francés"
            "de" -> "Alemán"
            "it" -> "Italiano"
            "ja" -> "Japonés"
            "ko" -> "Coreano"
            "zh" -> "Chino"
            "ru" -> "Ruso"
            else -> base.uppercase()
        }
        val region = clean.substringAfter('-', "")
        return when {
            region.isEmpty() -> name
            base == "es" && region == "419" -> "$name (Latino)"
            base == "pt" && region == "br" -> "$name (Brasil)"
            else -> "$name (${region.uppercase()})"
        }
    }

    /** Fila "sin elegir": `null` cuando no hay nada que ofrecer. */
    fun offOption(type: Int, selected: Boolean): TrackOption = TrackOption(
        type = type,
        groupIndex = -1,
        trackIndex = -1,
        label = if (type == C.TRACK_TYPE_AUDIO) "Automático" else "Desactivado",
        selected = selected,
    )

    /**
     * Pistas de un tipo.
     *
     * @param hasOverride `true` si el usuario ya eligió una pista concreta
     *   (`trackSelectionParameters.overrides`). Sin override, la fila
     *   "Automático"/"Desactivado" es la activa.
     */
    fun options(tracks: Tracks?, type: Int, hasOverride: Boolean = false): List<TrackOption> {
        if (tracks == null) return emptyList()
        val groups = tracks.groups
        val rows = ArrayList<TrackOption>()
        var anySelected = false

        for (groupIndex in 0 until groups.size) {
            val group = groups[groupIndex]
            if (group.type != type) continue
            for (trackIndex in 0 until group.length) {
                val selected = group.isTrackSelected(trackIndex)
                if (selected) anySelected = true
                rows += TrackOption(
                    type = type,
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    label = formatLabel(group.getTrackFormat(trackIndex), "Pista ${rows.size + 1}"),
                    language = group.getTrackFormat(trackIndex).language,
                    selected = selected,
                )
            }
        }

        if (rows.isEmpty()) return emptyList()
        val automatic = when (type) {
            C.TRACK_TYPE_AUDIO -> !hasOverride
            else -> !anySelected || !hasOverride
        }
        return listOf(offOption(type, automatic)) + rows
    }

    /** Grupo de una fila, o `null` si la fila es "sin elegir". */
    fun groupFor(tracks: Tracks?, option: TrackOption): TrackGroup? {
        if (option.groupIndex < 0) return null
        return tracks?.groups?.getOrNull(option.groupIndex)?.mediaTrackGroup
    }

    /** ¿Hay algo que enseñar en el panel? (audio siempre, texto sólo si existe) */
    fun hasPanel(text: List<TrackOption>, audio: List<TrackOption>): Boolean =
        text.size > 1 || audio.size > 1
}
