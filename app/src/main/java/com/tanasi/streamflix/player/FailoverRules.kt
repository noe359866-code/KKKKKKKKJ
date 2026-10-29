package com.tanasi.streamflix.player

import androidx.media3.common.PlaybackException

/**
 * Traducción de los errores de Media3 a una decisión de failover.
 *
 * Se aísla del reproductor para poder probarla con códigos enteros, sin instanciar
 * un `PlaybackException` ni arrancar un `ExoPlayer`.
 *
 * Criterio: **ante la duda, saltar**. Un usuario prefiere perder dos segundos
 * cambiando de servidor a quedarse mirando un spinner. La única excepción son
 * los errores de DRM: si la licencia falla, otra fuente del mismo título no va a
 * arreglarlo y el salto sólo produce un parpadeo.
 */
object FailoverRules {

    /**
     * @param errorCode `PlaybackException.errorCode`
     * @return `true` si merece la pena probar la siguiente fuente.
     */
    fun shouldFailover(errorCode: Int): Boolean = when (errorCode) {
        /* Sistema DRM: la lista de fuentes no cambia la licencia. */
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        -> false

        /* Todo lo demás se intenta en la siguiente fuente. */
        else -> true
    }

    /** Motivo para el historial y la UI. */
    fun reasonFor(errorCode: Int): FailoverReason = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        -> FailoverReason.HTTP_ERROR

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        -> FailoverReason.UNSUPPORTED_FORMAT

        else -> FailoverReason.PLAYER_ERROR
    }
}
