package com.tanasi.streamflix.player

/** No confundir una duración aún desconocida con un directo ni permitir saltos sin escala. */
internal fun canSeekPlayback(isLive: Boolean, isSeekable: Boolean, durationMs: Long, tuning: Boolean): Boolean =
    !isLive && isSeekable && durationMs > 0L && !tuning

/** Una muestra durante un cambio de fuente puede contener posición y duración transitorias. */
internal fun canSavePlaybackSnapshot(isLive: Boolean, isReady: Boolean, durationMs: Long): Boolean =
    !isLive && isReady && durationMs > 0L

/** Mantiene la selección de foco por pista, no sólo por posición de grupo. */
internal fun sameTrack(a: TrackOption, b: TrackOption): Boolean =
    a.type == b.type && a.trackIndex == b.trackIndex &&
        (a.isAutomatic || a.mediaTrackGroup == b.mediaTrackGroup)

internal fun focusTrackAfterChange(previous: TrackOption?, options: List<TrackOption>): TrackOption? =
    previous?.let { old -> options.firstOrNull { sameTrack(old, it) } }
        ?: options.firstOrNull { it.selected }
        ?: options.firstOrNull()

internal data class PlaybackSnapshot(val positionMs: Long, val durationMs: Long)

/** Por sesión de reproductor: nunca sustituye una muestra válida por datos de carga. */
internal class PlaybackSnapshotCache {
    var latest: PlaybackSnapshot? = null
        private set

    fun observe(isLive: Boolean, isReady: Boolean, positionMs: Long, durationMs: Long) {
        if (canSavePlaybackSnapshot(isLive, isReady, durationMs)) {
            latest = PlaybackSnapshot(positionMs.coerceIn(0L, durationMs), durationMs)
        }
    }
}

/** Evidencia conservadora por fuente; READY por sí solo no prueba que se haya reproducido contenido. */
internal class PlaybackEvidence {
    private var renderedVideo = false
    private var playedAudioOnly = false

    fun sourceChanging() {
        renderedVideo = false
        playedAudioOnly = false
    }

    fun firstVideoFrame() { renderedVideo = true }

    fun observeAudio(isPlaying: Boolean, selectedVideo: Boolean, selectedAudio: Boolean) {
        if (isPlaying && !selectedVideo && selectedAudio) playedAudioOnly = true
    }

    fun canRecord(selectedVideo: Boolean, selectedAudio: Boolean): Boolean =
        if (selectedVideo) renderedVideo else if (selectedAudio) playedAudioOnly || renderedVideo else false
}
