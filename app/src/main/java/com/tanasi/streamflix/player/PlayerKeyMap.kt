package com.tanasi.streamflix.player

import androidx.compose.ui.input.key.Key

/** Acciones que el mando puede disparar sobre el reproductor. */
enum class PlayerKeyAction {
    /** `DPAD_CENTER` / `ENTER` / botón A del gamepad. */
    TOGGLE_PLAY_PAUSE,

    /** `DPAD_LEFT`: retroceder un paso. */
    SEEK_BACK,

    /** `DPAD_RIGHT`: avanzar un paso. */
    SEEK_FORWARD,

    /** `DPAD_DOWN` / `DPAD_UP`: sacar los controles y llevar el foco a ellos. */
    OPEN_CONTROLS,

    /** `CH+`: sintonizar el canal siguiente de la parrilla, sin salir. */
    CHANNEL_UP,

    /** `CH−`: sintonizar el canal anterior de la parrilla, sin salir. */
    CHANNEL_DOWN,
}

/**
 * Traducción tecla → acción del reproductor.
 *
 * Se aísla de la UI porque es la tabla que define la sensación de "mando de
 * salón": si `OK` no pausa o `IZQUIERDA` no retrocede, el usuario cree que la
 * app está colgada. Al ser una función pura se prueba en JVM, sin emulador ni
 * `ExoPlayer`.
 *
 * Teclas multimedia (`KEYCODE_MEDIA_PLAY_PAUSE`, que emiten muchos mandos y
 * televisores) se tratan igual que el botón central; `MEDIA_NEXT`/`MEDIA_PREVIOUS`
 * hacen de `CH+`/`CH−` porque es donde acaban esos botones en la mitad de los
 * mandos baratos.
 *
 * `null` = no es asunto del reproductor y el evento sigue su curso (navegación
 * o `Activity`).
 */
object PlayerKeyMap {

    fun actionFor(key: Key): PlayerKeyAction? = when (key) {
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        Key.Spacebar,
        Key.ButtonA,
        Key.MediaPlayPause,
        Key.MediaPlay,
        Key.MediaPause,
        -> PlayerKeyAction.TOGGLE_PLAY_PAUSE

        Key.DirectionLeft,
        Key.MediaRewind,
        -> PlayerKeyAction.SEEK_BACK

        Key.DirectionRight,
        Key.MediaFastForward,
        -> PlayerKeyAction.SEEK_FORWARD

        Key.DirectionDown,
        Key.DirectionUp,
        -> PlayerKeyAction.OPEN_CONTROLS

        /* `CH+`/`CH−` del mando de TV. Muchos mandos de TV box no tienen esas
         * teclas y emiten `MEDIA_NEXT`/`MEDIA_PREVIOUS` con los mismos botones
         * (o la barra de sonido los manda así): se aceptan ambos. */
        Key.ChannelUp,
        Key.MediaNext,
        -> PlayerKeyAction.CHANNEL_UP

        Key.ChannelDown,
        Key.MediaPrevious,
        -> PlayerKeyAction.CHANNEL_DOWN

        else -> null
    }
}
