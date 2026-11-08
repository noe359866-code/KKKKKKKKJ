package com.tanasi.streamflix.data.local

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeEngineDispatchTest {
    private val dispatch = NativeEngineDispatch()
    private fun plan(operation: String, request: String) = Json.parseToJsonElement(dispatch.plan(operation, request)).jsonObject

    @Test fun `catalog request defaults and clamps values`() {
        val result = plan("catalog", """{"type":"anime","id":"cinemeta_top","search":"title","skip":-10}""")
        assertEquals("catalog", result["action"]!!.jsonPrimitive.content)
        assertEquals("anime", result["type"]!!.jsonPrimitive.content)
        assertEquals("title", result["search"]!!.jsonPrimitive.content)
        assertEquals("", result["genre"]!!.jsonPrimitive.content)
        assertEquals("0", result["skip"]!!.jsonPrimitive.content)
    }

    @Test fun `dispatch validates content and preserves control operations`() {
        assertEquals("invalid", plan("streams", """{"type":"invalid","id":"x"}""")["action"]!!.jsonPrimitive.content)
        assertEquals("invalid", plan("meta", """{"type":"movie","id":""}""")["action"]!!.jsonPrimitive.content)
        assertEquals("unknown", plan("other", """{"type":"series","id":"show"}""")["action"]!!.jsonPrimitive.content)
        assertEquals("health", plan("health", "{}")["action"]!!.jsonPrimitive.content)
        assertTrue(plan("catalogs", """{"metadataCatalogs":{"enabled":true}}""")["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(plan("catalogs", """{"metadataCatalogs":[]}""")["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun `registry filters scripts and preserves stable priority ordering`() {
        val rows = """[{"id":"a","script":"a.py","priority":10,"enabled":true},{"id":"b","script":"b.py","priority":1,"enabled":true},{"id":"a","script":"a.py","priority":1,"enabled":true},{"id":"x","script":"other.py","priority":0,"enabled":true},{"id":"a","script":"a.py","priority":2,"enabled":false}]"""
        assertEquals("[1,2,0]", NativeEngineRegistry().selectIndices(rows, """["a","b","x"]"""))
        assertEquals("", NativeEngineRegistry().selectIndices("""[{"id":"a","priority":"1","enabled":true}]""", "[]"))
    }

    @Test fun `nonstandard python types and unicode surrogate pairs request fallback`() {
        assertEquals("", dispatch.plan("catalog", """{"type":"movie","id":"x","skip":"2"}"""))
        assertEquals("", dispatch.plan("meta", """{"type":"movie","id":5}"""))
        assertEquals("", dispatch.plan("meta", """{"type":[],"id":"x"}"""))
        assertEquals("", dispatch.plan("meta", """{"type":"movie","id":"😀"}"""))
    }
}
