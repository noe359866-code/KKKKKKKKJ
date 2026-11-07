package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.data.model.MediaType
import com.tanasi.streamflix.data.model.hasEpisodeNumber

/** La acción visible debe corresponder a lo que ocurrirá al pulsar OK. */
internal enum class HeroAction(val label: String) {
    Play("▶  Reproducir"),
    Resume("▶  Continuar"),
    ChooseEpisode("Elegir episodio"),
}

internal fun heroAction(entry: MediaEntry): HeroAction {
    val type = MediaType.fromWire(entry.type)
    val needsEpisode = (type == MediaType.Series || type == MediaType.Anime) &&
        !hasEpisodeNumber(entry.type.lowercase(), entry.id)
    return when {
        needsEpisode -> HeroAction.ChooseEpisode
        entry.resumePositionMs > 0L -> HeroAction.Resume
        else -> HeroAction.Play
    }
}
