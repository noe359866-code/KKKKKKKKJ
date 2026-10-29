package com.tanasi.streamflix.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué errores merecen otro servidor y cuáles no. */
class FailoverRulesTest {

    @Test
    fun `los errores de red y de formato saltan a la siguiente fuente`() {
        val retriable = listOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        retriable.forEach { code ->
            assertTrue("código $code debería saltar", FailoverRules.shouldFailover(code))
        }
    }

    @Test
    fun `un fallo de DRM no salta porque otra fuente no lo arregla`() {
        val fatal = listOf(
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        )

        fatal.forEach { code ->
            assertFalse("código $code no debería saltar", FailoverRules.shouldFailover(code))
        }
    }

    @Test
    fun `el motivo clasifica HTTP, formato y el resto`() {
        assertEquals(
            FailoverReason.HTTP_ERROR,
            FailoverRules.reasonFor(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(
            FailoverReason.UNSUPPORTED_FORMAT,
            FailoverRules.reasonFor(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED),
        )
        assertEquals(
            FailoverReason.PLAYER_ERROR,
            FailoverRules.reasonFor(PlaybackException.ERROR_CODE_UNSPECIFIED),
        )
    }
}
