package com.tanasi.streamflix.data.local

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

class NativeJsonCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(dir: File, key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(dir, "$hash.json")
    }

    @Test
    fun `ttl stale and persisted entries match engine cache`() {
        val dir = temp.newFolder("cache")
        var now = 1000.0
        val cache = NativeJsonCache(dir, 160) { now }
        cache.putJson("title:é", """{"name":"Película"}""", 10)
        assertEquals("Película", Json.parseToJsonElement(cache.getJson("title:é", 0)!!).jsonObject["name"]!!.jsonPrimitive.content)
        now = 1010.0
        assertNull(cache.getJson("title:é", 0))
        assertNotNull(cache.getJson("title:é", 20))
        now = 1030.0
        assertNull(cache.getJson("title:é", 20))
        // Python's existing cache files use this same envelope and SHA-256 name.
        file(dir, "python").writeText("""{"expires":2000.0,"value":{"from":"python"}}""")
        assertEquals("python", Json.parseToJsonElement(cache.getJson("python", 0)!!).jsonObject["from"]!!.jsonPrimitive.content)
        cache.putJson("ignored", "{}", 0)
        assertNull(cache.getJson("ignored", 0))
    }

    @Test
    fun `invalid and oversized entries are ignored and old files are pruned`() {
        val dir = temp.newFolder("bounded")
        val cache = NativeJsonCache(dir, 2) { 1000.0 }
        cache.putJson("one", "{}", 60)
        assertTrue(file(dir, "one").setLastModified(1000))
        cache.putJson("two", "[]", 60)
        assertTrue(file(dir, "two").setLastModified(2000))
        cache.putJson("three", "true", 60)
        assertNull(cache.getJson("one", 0))
        assertEquals("true", cache.getJson("three", 0))
        file(dir, "two").writeText("broken")
        assertNull(cache.getJson("two", 0))
        file(dir, "two").writeText("""{"expires":"2000","value":1}""")
        assertNull(cache.getJson("two", 0))
        file(dir, "two").writeText("""{"expires":2000,"value":garbage}""")
        assertNull(cache.getJson("two", 0))
        file(dir, "two").writeText("x".repeat(2_000_001))
        assertNull(cache.getJson("two", 0))
        cache.clear()
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".json") })
    }

    @Test
    fun `concurrent writes never expose partial json`() {
        val dir = temp.newFolder("concurrent")
        val caches = List(2) { NativeJsonCache(dir, 160) { 1000.0 } }
        val latch = CountDownLatch(4)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(4) { writer -> Thread {
            val cache = caches[writer % caches.size]
            try {
                repeat(50) { i ->
                    cache.putJson("same", """{"writer":$writer,"iteration":$i}""", 60)
                    val entry = cache.getJson("same", 0)
                    if (entry != null) Json.parseToJsonElement(entry).jsonObject
                }
            } catch (error: Throwable) { failures.add(error) } finally { latch.countDown() }
        }.start() }
        latch.await()
        assertTrue(failures.toString(), failures.isEmpty())
        assertNotNull(caches[0].getJson("same", 0))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
