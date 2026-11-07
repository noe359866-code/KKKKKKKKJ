package com.tanasi.streamflix.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTracksOptionsTest {
    /*
     * Un `TrackGroup` de Media3 sólo agrupa pistas del **mismo contenido y el
     * mismo idioma**: `TrackGroup.verifyCorrectness()` lo comprueba y, si ve
     * idiomas distintos, escribe un `Log.e` de diagnóstico. En la JVM de los
     * tests `android.util.Log` no existe y `returnDefaultValues = true` hace
     * que `Log.getStackTraceString()` devuelva `null`; el propio logger lanza
     * entonces `NullPointerException` dentro del constructor y el test muere
     * antes del assert (así fallaba `:app:testDebugUnitTest` en CI). Las dos
     * pistas se distinguen por `label`, que es lo que el panel pinta.
     */
    private fun audioGroup(id: String) = TrackGroup(
        id,
        Format.Builder()
            .setSampleMimeType("audio/mp4a-latm").setLanguage("es").setLabel("Español")
            .build(),
        Format.Builder()
            .setSampleMimeType("audio/mp4a-latm").setLanguage("es").setLabel("Español (alt)")
            .build(),
    )

    private fun tracks(group: TrackGroup, support: IntArray = intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED)) =
        Tracks(listOf(Tracks.Group(group, false, support, booleanArrayOf(true, false))))

    @Test fun `automatico y pista fisica no aparecen ambos seleccionados`() {
        val options = PlayerTracks.options(tracks(audioGroup("uno")), C.TRACK_TYPE_AUDIO)
        assertTrue(options.first().selected)
        assertFalse(options[1].selected)
        val manual = PlayerTracks.options(tracks(audioGroup("uno")), C.TRACK_TYPE_AUDIO, hasOverride = true)
        assertFalse(manual.first().selected)
        assertTrue(manual[1].selected)
    }

    @Test fun `no ofrece pistas no admitidas y rechaza selecciones obsoletas`() {
        val original = tracks(audioGroup("uno"), intArrayOf(C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_TYPE))
        val options = PlayerTracks.options(original, C.TRACK_TYPE_AUDIO)
        assertEquals(2, options.size)
        assertEquals(original.groups[0].mediaTrackGroup, PlayerTracks.groupFor(original, options[1]))
        assertNull(PlayerTracks.groupFor(tracks(audioGroup("otro")), options[1]))
        assertNull(PlayerTracks.groupFor(original, options[1].copy(trackIndex = 1)))
    }
}
