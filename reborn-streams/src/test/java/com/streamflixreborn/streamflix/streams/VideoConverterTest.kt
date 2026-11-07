package com.streamflixreborn.streamflix.streams

import com.streamflixreborn.streamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoConverterTest {
    @Test
    fun `rejects embed pages and non http links`() {
        assertFalse(VideoConverter.looksPlayable("https://host/embed/abc"))
        assertFalse(VideoConverter.looksPlayable("https://host/page.html"))
        assertFalse(VideoConverter.looksPlayable("magnet:?xt=1"))
        assertTrue(VideoConverter.looksPlayable("https://cdn/host/abc123"))
    }

    @Test
    fun `converts hls with headers and subtitles`() {
        val video = Video(
            source = "https://cdn.example/video.m3u8",
            type = "application/vnd.apple.mpegurl",
            headers = mapOf("Referer" to "https://provider.example/", "Empty" to ""),
            subtitles = listOf(Video.Subtitle(label = "Español", file = "https://subs.example/es.vtt")),
        )
        val stream = VideoConverter.convert("Provider", Video.Server(id = "1", name = "Server 1080p"), video)
        requireNotNull(stream)
        assertEquals("hls", stream.mediaType)
        assertEquals("1080p", stream.quality)
        assertEquals(mapOf("Referer" to "https://provider.example/"), stream.headers)
        assertEquals(1, stream.subtitles.size)
    }

    @Test
    fun `does not convert html source into stream`() {
        val video = Video(source = "https://host/watch.html")
        assertNull(VideoConverter.convert("Provider", Video.Server(id = "1", name = "Server"), video))
    }
}
