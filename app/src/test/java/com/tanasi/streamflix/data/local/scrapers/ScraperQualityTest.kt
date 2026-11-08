package com.tanasi.streamflix.data.local.scrapers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScraperQualityTest {
    @Test
    fun `strong hints take precedence over weaker labels across all inputs`() {
        assertEquals("1080p", ScraperQuality.guess(listOf("Latino HD", "https://cdn.example/1080/master.m3u8")))
        assertEquals("4K", ScraperQuality.guess(listOf("UHD 2160p")))
        assertEquals("1080p", ScraperQuality.guess(listOf("FHD")))
        assertEquals("720p", ScraperQuality.guess(listOf("Latino HD")))
        assertEquals("SD", ScraperQuality.guess(listOf("SD")))
        assertEquals("CAM", ScraperQuality.guess(listOf("CAM")))
    }

    @Test
    fun `hostnames and unrelated words do not invent quality`() {
        assertNull(ScraperQuality.guess(listOf("https://hd.tubeload.example/e/1")))
        assertNull(ScraperQuality.guess(listOf("https://streamtape.example/v/ts/1")))
        assertNull(ScraperQuality.guess(listOf("Streamwish")))
        assertEquals("", ScraperQuality().guessJson("""["Streamwish",""]"""))
        assertEquals("1080p", ScraperQuality().guessJson("""["FullHD"]"""))
    }
}
