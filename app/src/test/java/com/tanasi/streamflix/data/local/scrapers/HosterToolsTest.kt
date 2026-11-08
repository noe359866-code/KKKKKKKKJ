package com.tanasi.streamflix.data.local.scrapers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class HosterToolsTest {
    private val tools = HosterTools()
    private fun parse(value: String) = Json.parseToJsonElement(value).jsonArray.map { it.jsonPrimitive.content }
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `host classification and content types`() {
        assertEquals("Streamwish", tools.label("https://www.dhcplay.example/e/1"))
        assertEquals("Example", tools.label("https://www.example.org/embed/1"))
        assertEquals("Servidor", tools.label("bad"))
        assertTrue(tools.isDownloadOnly("https://mega.nz/file/one"))
        assertFalse(tools.isDownloadOnly("https://streamwish.example/e/1"))
        assertTrue(tools.isKnownGood("https://streamwish.example/e/1"))
        assertEquals("hls", tools.mediaTypeOfResponse("application/x-mpegURL"))
        assertEquals("dash", tools.mediaTypeOfResponse("application/dash+xml"))
        assertEquals("mp4", tools.mediaTypeOfResponse("video/webm"))
        assertEquals("html", tools.mediaTypeOfResponse("text/html; charset=utf-8"))
    }

    @Test
    fun `probe recognizes video signatures and rejects html`() {
        assertEquals("hls", tools.classifyProbe(hex("\uFEFF \n#EXTM3U".toByteArray())))
        assertEquals("dash", tools.classifyProbe(hex("<?xml version='1.0'?><MPD>".toByteArray())))
        assertEquals("mp4", tools.classifyProbe(hex(byteArrayOf(0, 0, 0, 24) + "ftyp".toByteArray())))
        assertEquals("html", tools.classifyProbe(hex("<!DOCTYPE html>".toByteArray())))
        assertEquals("", tools.classifyProbe("ff0"))
    }

    @Test
    fun `extract streamtape markers and token in document order`() {
        val html = """<div id='ideoooolink'> //streamtape.test/get_video?id=1 </div>
            <div ID="ideoooolink">other.test/get_video?id=2</div><script>token='abc'</script>"""
        assertEquals(listOf("https://streamtape.test/get_video?id=1&token=abc&dl=1s", "https://other.test/get_video?id=2&token=abc&dl=1s"), parse(tools.streamtapeJson(html)))
        assertEquals(listOf("https://example.test/a"), parse(tools.streamtapeJson("<div id='ideoooolink'>example.test/a</div>")))
        assertTrue(parse(tools.streamtapeJson("no player")).isEmpty())
    }

    @Test
    fun `extract okru hls urls from nested encoded metadata`() {
        val html = """<div data-module='OKVideo' data-options='{&quot;flashvars&quot;:{&quot;metadata&quot;:&quot;{\&quot;hlsMasterPlaylistUrl\&quot;:\&quot;https://cdn.test/master\&quot;,\&quot;hlsManifestUrl\&quot;:\&quot;http://cdn.test/hls\&quot;}&quot;}}'>"""
        assertEquals(listOf("https://cdn.test/master", "http://cdn.test/hls"), parse(tools.okruJson(html)))
        assertEquals("", tools.okruJson("<div data-module='OKVideo' data-options='broken'>"))
        assertEquals("", tools.okruJson("""<div data-module='OKVideo' data-options='{&quot;flashvars&quot;:{&quot;metadata&quot;:&quot;{\&quot;hlsManifestUrl\&quot;:\&quot;https://cdn.test/hls\&quot;,\&quot;other\&quot;:NaN}&quot;}}'>"""))
        assertEquals("", tools.okruJson("""<div data-module='OKVideo' data-options='{&quot;flashvars&quot;:{&quot;metadata&quot;:&quot;[]&quot;}}'>"""))
    }

    @Test
    fun `variants keep signatures and distinguish manifests from embeds`() {
        val url = "https://host.test/play/abc-123?sig=a%2Fb#fragment"
        val manifests = parse(tools.manifestVariantsJson(url))
        val embeds = parse(tools.embedVariantsJson(url))
        assertEquals(8, manifests.size)
        assertEquals("https://host.test/m3u8/abc-123?sig=a%2Fb#fragment", manifests.first())
        assertEquals("https://host.test/hls/e-abc-123?sig=a%2Fb#fragment", manifests[3])
        assertEquals(12, embeds.size)
        assertEquals("https://host.test/e/abc-123?sig=a%2Fb#fragment", embeds.first())
        assertTrue(parse(tools.manifestVariantsJson("https://host.test/e/abc")).isEmpty())
        assertEquals("https://host.test/m3u8/abc;other?sig=x", parse(tools.manifestVariantsJson("https://host.test/play/abc;other?sig=x")).first())
    }
}
