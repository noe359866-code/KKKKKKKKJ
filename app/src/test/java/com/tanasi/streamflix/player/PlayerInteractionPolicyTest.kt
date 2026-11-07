package com.tanasi.streamflix.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInteractionPolicyTest {
    @Test fun `seek exige duracion y capacidad y no ofrece DVR en directo`() {
        assertTrue(canSeekPlayback(false, true, 60_000L, false))
        assertFalse(canSeekPlayback(true, true, 60_000L, false))
        assertFalse(canSeekPlayback(false, false, 60_000L, false))
        assertFalse(canSeekPlayback(false, true, 0L, false))
        assertFalse(canSeekPlayback(false, true, Long.MIN_VALUE + 1, false))
        assertFalse(canSeekPlayback(false, true, 60_000L, true))
    }

    @Test fun `muestras transitorias no borran el progreso confirmado`() {
        val snapshots = PlaybackSnapshotCache()
        snapshots.observe(false, false, 0L, 0L)
        assertNull(snapshots.latest)
        snapshots.observe(false, true, 45_000L, 100_000L)
        snapshots.observe(false, false, 0L, 100_000L)
        snapshots.observe(true, true, 10L, 100_000L)
        assertEquals(PlaybackSnapshot(45_000L, 100_000L), snapshots.latest)
        snapshots.observe(false, true, 110_000L, 100_000L)
        assertEquals(PlaybackSnapshot(100_000L, 100_000L), snapshots.latest)
    }

    @Test fun `foco conserva pista al mover grupo y recupera seleccion al desaparecer`() {
        fun group(id: String) = TrackGroup(id, Format.Builder().setSampleMimeType("audio/mp4a-latm").build())
        val old = TrackOption(C.TRACK_TYPE_AUDIO, 0, 0, "Español", mediaTrackGroup = group("a"))
        val moved = old.copy(groupIndex = 3)
        val replacement = old.copy(mediaTrackGroup = group("b"), selected = true)
        assertTrue(sameTrack(old, moved))
        assertFalse(sameTrack(old, replacement))
        assertEquals(moved, focusTrackAfterChange(old, listOf(replacement, moved)))
        assertEquals(replacement, focusTrackAfterChange(old, listOf(replacement)))
        assertNull(focusTrackAfterChange(old, emptyList()))
    }
    @Test fun `video necesita primer fotograma y cada fuente nueva vuelve a esperar`() {
        val evidence = PlaybackEvidence()
        assertFalse(evidence.canRecord(selectedVideo = true, selectedAudio = true))
        evidence.observeAudio(isPlaying = true, selectedVideo = true, selectedAudio = true)
        assertFalse(evidence.canRecord(selectedVideo = true, selectedAudio = true))
        evidence.firstVideoFrame()
        assertTrue(evidence.canRecord(selectedVideo = true, selectedAudio = true))
        evidence.sourceChanging()
        assertFalse(evidence.canRecord(selectedVideo = true, selectedAudio = true))
    }

    @Test fun `audio sin video se confirma solo cuando esta reproduciendose`() {
        val evidence = PlaybackEvidence()
        evidence.observeAudio(isPlaying = false, selectedVideo = false, selectedAudio = true)
        assertFalse(evidence.canRecord(selectedVideo = false, selectedAudio = true))
        evidence.observeAudio(isPlaying = true, selectedVideo = false, selectedAudio = true)
        assertTrue(evidence.canRecord(selectedVideo = false, selectedAudio = true))
        assertFalse(evidence.canRecord(selectedVideo = true, selectedAudio = true))
        evidence.sourceChanging()
        assertFalse(evidence.canRecord(selectedVideo = false, selectedAudio = true))
        assertFalse(evidence.canRecord(selectedVideo = false, selectedAudio = false))
    }

}
