package com.tanasi.streamflix.ui.components

import com.tanasi.streamflix.data.model.MediaEntry

/** Claves guardables y estables si una fila se reordena o recibe más títulos. */
internal fun stableMediaKeys(entries: List<MediaEntry>): List<String> {
    val occurrences = mutableMapOf<Pair<String, String>, Int>()
    return entries.map { entry ->
        val identity = entry.type to entry.id
        val occurrence = occurrences.getOrDefault(identity, 0)
        occurrences[identity] = occurrence + 1
        // Las longitudes evitan colisiones aunque un id contenga dos puntos.
        "${entry.type.length}:${entry.type}${entry.id.length}:${entry.id}:$occurrence"
    }
}
