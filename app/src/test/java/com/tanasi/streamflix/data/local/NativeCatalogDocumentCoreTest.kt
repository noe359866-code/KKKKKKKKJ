package com.tanasi.streamflix.data.local

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class NativeCatalogDocumentCoreTest {
    private fun parser() = NativeCatalogDocumentCore("https://site.invalid/page") { base, value -> URI(base).resolve(value).toString() }

    @Test fun `collects metadata heading nested anchors and lazy artwork`() {
        val document = parser()
        assertTrue(document.start("meta", """{"property":"OG:Title","content":"Fixture"}"""))
        assertTrue(document.start("h1", "{}"))
        assertTrue(document.data("Heading"))
        document.end("h1")
        assertTrue(document.start("a", """{"href":"/a"}"""))
        assertTrue(document.data("first"))
        assertTrue(document.start("img", """{"data-src":"/poster.jpg","alt":"Artwork"}"""))
        assertTrue(document.start("a", """{"href":"/b","title":"Second"}"""))
        assertTrue(document.data("ignored title"))
        document.end("a")
        val output = Json.parseToJsonElement(document.snapshot()).jsonObject
        assertEquals("Fixture", output["meta"]!!.jsonObject["og:title"]!!.jsonPrimitive.content)
        assertEquals("Heading", output["heading"]!!.jsonArray.single().jsonPrimitive.content)
        val links = output["links"]!!.jsonArray
        assertEquals(2, links.size)
        assertEquals("https://site.invalid/a", links[0].jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("Artwork", links[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("https://site.invalid/poster.jpg", links[0].jsonObject["poster"]!!.jsonPrimitive.content)
        assertEquals("Second", links[1].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test fun `text is joined by event and incomplete anchors are not finalized`() {
        val document = parser()
        document.start("a", """{"href":"/a"}""")
        document.data("first")
        document.data("second")
        document.end("a")
        document.start("a", """{"href":"/unfinished"}""")
        val links = Json.parseToJsonElement(document.snapshot()).jsonObject["links"]!!.jsonArray
        assertEquals(1, links.size)
        assertEquals("first second", links[0].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test fun `unsupported Unicode asks the caller to replay in Python`() {
        val document = parser()
        assertFalse(document.start("meta", """{"property":"İ","content":"x"}"""))
        assertFalse(document.data("😀"))
        assertFalse(document.start("a", """{"href":"/😀"}"""))
    }
}
