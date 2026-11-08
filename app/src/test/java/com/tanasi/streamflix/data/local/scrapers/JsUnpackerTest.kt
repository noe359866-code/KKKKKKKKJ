package com.tanasi.streamflix.data.local.scrapers

import org.junit.Assert.*
import org.junit.Test

class JsUnpackerTest {
    private val unpacker = JsUnpacker()
    private fun packed(payload: String, table: String, count: Int = 3): String =
        "eval(function(p,a,c,k,e,d){return p}('$payload',$count,$count,'$table'.split('|'),0,{}))"

    @Test
    fun `unpacks numbers into a media url without executing JavaScript`() {
        val html = packed("0 1=\"2://cdn.example/v.m3u8\";", "var|file|https")
        assertEquals("var file=\"https://cdn.example/v.m3u8\";", unpacker.unpack(html, 12))
        assertEquals("", unpacker.unpack("<script>ordinary JavaScript</script>", 12))
    }

    @Test
    fun `handles escaped payloads limits and literal replacement characters`() {
        val one = packed("0=\\\"1\\\";", "source|https://cdn.example/v\$token")
        val two = packed("0='1';", "ignored|ignored")
        assertEquals("source=\"https://cdn.example/v\$token\";", unpacker.unpack(one + two, 1))
        assertEquals("", unpacker.unpack(one, 0))
        assertNull(unpacker.unpack(one, -1))
        assertNull(unpacker.unpack(packed("0", "bad\\name"), 12))
    }
}
