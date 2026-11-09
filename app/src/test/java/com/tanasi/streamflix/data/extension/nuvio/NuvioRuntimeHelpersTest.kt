package com.tanasi.streamflix.data.extension.nuvio

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Test

class NuvioRuntimeHelpersTest {
    @Test fun responseHeadersUseLowerCaseAndPreserveRepeatedValues() {
        val headers = Headers.Builder().add("X-Test", "first").add("x-test", "second").build()
        assertEquals(mapOf("x-test" to "first, second"), headers.toPluginResponseHeaders())
    }

    @Test fun truncationDoesNotSplitSurrogatePairs() {
        val text = "a\uD83D\uDE00b"
        assertEquals("a", truncatePluginBody(text, 2))
        assertEquals("a\uD83D\uDE00", truncatePluginBody(text, 3))
        assertEquals("", truncatePluginBody(text, 0))
        assertEquals(text, truncatePluginBody(text, 10))
    }
}
