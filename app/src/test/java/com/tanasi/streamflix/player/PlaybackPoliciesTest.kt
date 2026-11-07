package com.tanasi.streamflix.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPoliciesTest {
    @Test fun `watchdog cambia fuente solo si se intenta reproducir durante buffering`() {
        assertTrue(shouldAdvanceStalled(playWhenReady = true, buffering = true))
        assertFalse(shouldAdvanceStalled(playWhenReady = false, buffering = true))
        assertFalse(shouldAdvanceStalled(playWhenReady = true, buffering = false))
        assertFalse(shouldAdvanceStalled(playWhenReady = false, buffering = false))
    }

    @Test fun `regreso conserva la intencion de reproduccion`() {
        val policy = PlaybackLifecyclePolicy()
        assertFalse(policy.onStart())
        policy.onStop(true)
        policy.onStop(false) // Un segundo stop no debe borrar la intención inicial.
        assertTrue(policy.onStart())
        assertFalse(policy.onStart())
        policy.onStop(false)
        assertFalse(policy.onStart())
    }
}
