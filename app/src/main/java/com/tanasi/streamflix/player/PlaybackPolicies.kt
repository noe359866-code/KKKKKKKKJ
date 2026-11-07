package com.tanasi.streamflix.player

/** Una pausa o un estado terminado/inactivo no justifican cambiar de fuente. */
internal fun shouldAdvanceStalled(playWhenReady: Boolean, buffering: Boolean): Boolean =
    playWhenReady && buffering

/** Recuerda sólo la intención que existía antes de pasar a segundo plano. */
internal class PlaybackLifecyclePolicy {
    private var backgrounded = false
    private var resumeOnStart = false

    fun onStop(playWhenReady: Boolean) {
        if (!backgrounded) resumeOnStart = playWhenReady
        backgrounded = true
    }

    fun onStart(): Boolean {
        if (!backgrounded) return false
        backgrounded = false
        return resumeOnStart.also { resumeOnStart = false }
    }
}
