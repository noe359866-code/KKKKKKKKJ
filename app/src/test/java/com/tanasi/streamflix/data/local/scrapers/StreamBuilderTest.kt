package com.tanasi.streamflix.data.local.scrapers

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StreamBuilderTest {
    @Test
    fun `resolved signals become ordered playable streams`() {
        val signals = """[{"url":"  https://cdn.invalid/video  ","server":"Latino HD","referer":"https://embed.invalid/","media_type":"HLS"},{"url":"https://cdn.invalid/1080p.mp4","label":"Mirror"}]"""
        val streams = Json.parseToJsonElement(StreamBuilder.build("Sitio", "Título", signals, "UA")).jsonArray
        assertEquals(2, streams.size)
        val first = streams[0].jsonObject
        assertEquals("https://cdn.invalid/video", first["url"]!!.jsonPrimitive.content)
        assertEquals("Sitio · Título · Latino HD", first["title"]!!.jsonPrimitive.content)
        assertEquals("720p", first["quality"]!!.jsonPrimitive.content)
        assertEquals("hls", first["behaviorHints"]!!.jsonObject["mediaType"]!!.jsonPrimitive.content)
        assertEquals("https://embed.invalid/", first["referer"]!!.jsonPrimitive.content)
        assertEquals("1080p", streams[1].jsonObject["quality"]!!.jsonPrimitive.content)
        assertNull(streams[1].jsonObject["referer"])
    }

    @Test
    fun `unsupported dynamic values are left to python`() {
        assertEquals("", StreamBuilder.build("site", "title", """[{"url":5}]""", ""))
        assertEquals("", StreamBuilder.build("site", "title", """[{"url":"a","media_type":5}]""", ""))
        assertEquals("[]", StreamBuilder.build("site", "title", "[]", ""))
    }
}
