package com.streamflixreborn.streamflix.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRequestTest {
    @Test
    fun `parses cinemeta episode ids`() {
        assertEquals(2 to 5, StreamRequest.parseEpisode("tt123:2:5"))
        assertEquals(1 to 10, StreamRequest.parseEpisode("provider:slug:1:10"))
        assertNull(StreamRequest.parseEpisode("tt123"))
    }
}
