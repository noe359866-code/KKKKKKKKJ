package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.embedded.EmbeddedProviders
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedProvidersTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ordena por prioridad y filtra por tipo`() {
        val providers = EmbeddedProviders(
            json,
            """
            {"providers":[
              {"id":"b","script":"b.py","priority":2,"kind":"vod"},
              {"id":"a","script":"a.py","priority":1,"kind":"live"},
              {"id":"c","script":"c.py","priority":3,"kind":"any"},
              {"id":"off","script":"off.py","priority":0,"kind":"vod","enabled":false}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("a", "b", "c"), providers.all.map { it.id })
        assertEquals(listOf("a", "c"), providers.forContent(true).map { it.id })
        assertEquals(listOf("b", "c"), providers.forContent(false).map { it.id })
    }

    @Test
    fun `descarta scripts con rutas`() {
        val providers = EmbeddedProviders(
            json,
            """{"providers":[{"id":"x","script":"../x.py"},{"id":"ok","script":"ok.py"}]}""",
        )
        assertEquals(listOf("ok"), providers.all.map { it.id })
        assertTrue(providers.forContent(false).isNotEmpty())
    }
}
