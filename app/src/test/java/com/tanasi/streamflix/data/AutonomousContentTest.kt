package com.tanasi.streamflix.data

import com.tanasi.streamflix.data.local.EmbeddedContentClient
import com.tanasi.streamflix.data.local.EmbeddedRuntime
import com.tanasi.streamflix.data.model.hasEpisodeNumber
import com.tanasi.streamflix.data.repo.CatalogRepository
import com.tanasi.streamflix.data.repo.CatalogResult
import com.tanasi.streamflix.data.repo.StreamsRepository
import com.tanasi.streamflix.data.repo.StreamsResult
import com.tanasi.streamflix.util.AddonSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class AutonomousContentTest {
    private class Options(
        override var baseUrl: String = "",
        override var autonomous: Boolean = true,
        override var metadataCatalogs: Boolean = false,
    ) : AddonSettings

    private val feed = """{"catalogs":[{"type":"tv","id":"bundled_live","name":"Canales","posterShape":"landscape","source":"embedded"}]}"""
    private val cards = """{"metas":[{"id":"tv:futbolibre:fixture","type":"tv","name":"Canal de prueba"}]}"""
    private val sources = """{"streams":[{"url":"https://fixture.invalid/live.m3u8","name":"Señal","provider":"Fixture","isLive":true,
        "behaviorHints":{"requestHeaders":{"Referer":"https://embed.invalid/","Origin":"https://origin.invalid","Cookie":"fixture=1","User-Agent":"FixtureUA"}}}],"cacheMaxAge":60}"""

    private fun fixture(calls: MutableList<String>) = EmbeddedContentClient(EmbeddedRuntime { operation, _ ->
        calls += operation
        when (operation) {
            "catalogs" -> feed
            "catalog" -> cards
            "streams" -> sources
            "meta" -> """{"meta":{"id":"pelisplushd:fixture","type":"series","name":"Fixture","videos":[{"id":"pelisplushd:fixture:1:2","season":1,"episode":2}]}}"""
            else -> error("Unexpected operation")
        }
    })

    @Test
    fun `sin URL se pueden cargar catalogos y fuentes sin tocar el cliente remoto`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val options = Options()
        val forbidden = EmbeddedContentClient(EmbeddedRuntime { _, _ -> error("No debe consultar ningún backend") })
        val client = RoutingContentClient(options, fixture(calls), forbidden)
        val catalogue = CatalogRepository(client, options).load()
        assertTrue(catalogue is CatalogResult.Ready)
        val card = (catalogue as CatalogResult.Ready).catalog.rows.single().items.single()
        assertEquals("Canal de prueba", card.title)
        val result = StreamsRepository(client, options).streamsFor(card.type, card.id)
        assertTrue(result is StreamsResult.Ready)
        val source = (result as StreamsResult.Ready).sources.single()
        assertTrue(source.isLive)
        assertEquals("https://embed.invalid/", source.referer)
        assertEquals("fixture=1", source.requestHeaders["Cookie"])
        assertEquals("https://origin.invalid", source.requestHeaders["Origin"])
        assertEquals(listOf("catalogs", "catalog", "streams"), calls)
    }

    @Test
    fun `servidor remoto solo se utiliza despues de elegir ese modo explicitamente`() = runBlocking {
        val embeddedCalls = mutableListOf<String>()
        val remoteCalls = mutableListOf<String>()
        val options = Options(baseUrl = "https://optional.invalid")
        val client = RoutingContentClient(options, fixture(embeddedCalls), fixture(remoteCalls))
        client.streams(options.baseUrl, "tv", "fixture")
        assertTrue(remoteCalls.isEmpty())
        options.autonomous = false
        client.streams(options.baseUrl, "tv", "fixture")
        assertEquals(listOf("streams"), embeddedCalls)
        assertEquals(listOf("streams"), remoteCalls)
    }

    @Test
    fun `un error local no activa silenciosamente un backend alternativo`() = runBlocking {
        val options = Options(baseUrl = "https://must-not-be-used.invalid")
        val local = EmbeddedContentClient(EmbeddedRuntime { _, _ -> error("Falló el motor local") })
        val remoteCalls = mutableListOf<String>()
        val result = StreamsRepository(RoutingContentClient(options, local, fixture(remoteCalls)), options)
            .streamsFor("movie", "pelisplushd:fixture")
        assertTrue(result is StreamsResult.Failure)
        assertTrue(remoteCalls.isEmpty())
    }

    @Test
    fun `el modo remoto sin URL sigue ofreciendo configuracion`() = runBlocking {
        val options = Options(autonomous = false)
        val calls = mutableListOf<String>()
        val result = CatalogRepository(fixture(calls), options).load()
        assertTrue(result is CatalogResult.Failure)
        assertTrue((result as CatalogResult.Failure).needsSetup)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `cambiar de modo no reutiliza el catalogo del modo anterior`() = runBlocking {
        val options = Options(baseUrl = "https://optional.invalid")
        val localCalls = Collections.synchronizedList(mutableListOf<String>())
        val remoteCalls = Collections.synchronizedList(mutableListOf<String>())
        val repository = CatalogRepository(RoutingContentClient(options, fixture(localCalls), fixture(remoteCalls)), options)
        assertTrue(repository.load() is CatalogResult.Ready)
        options.autonomous = false
        assertTrue(repository.load() is CatalogResult.Ready)
        assertEquals(listOf("catalogs", "catalog"), localCalls)
        assertEquals(listOf("catalogs", "catalog"), remoteCalls)
    }

    @Test
    fun `los canales guardados se publican antes de esperar a los proveedores de Internet`() = runBlocking {
        val firstShelf = CompletableDeferred<Unit>()
        val allowNetwork = CompletableDeferred<Unit>()
        val client = EmbeddedContentClient(EmbeddedRuntime { operation, payload ->
            when (operation) {
                "catalogs" -> """{"catalogs":[
                    {"type":"tv","id":"bundled_live","name":"Canales"},
                    {"type":"movie","id":"gnula","name":"Películas"}]}"""
                "catalog" -> {
                    if (Json.parseToJsonElement(payload).jsonObject["id"]?.jsonPrimitive?.content == "gnula") {
                        allowNetwork.await()
                        """{"metas":[{"id":"gnula:fixture","type":"movie","name":"Fixture"}]}"""
                    } else cards
                }
                else -> error("Unexpected operation")
            }
        })
        val job = async {
            CatalogRepository(client, Options()).load(onPartial = { catalog ->
                if (catalog.rows.size == 1 && !firstShelf.isCompleted) firstShelf.complete(Unit)
            })
        }
        firstShelf.await()
        assertFalse("La consulta remota todavía está pendiente", job.isCompleted)
        allowNetwork.complete(Unit)
        val result = job.await() as CatalogResult.Ready
        assertEquals(2, result.catalog.rows.size)
    }

    @Test
    fun `se conservan el ID de episodio y los parametros de busqueda al cruzar el puente`() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        val client = EmbeddedContentClient(EmbeddedRuntime { operation, payload ->
            requests += operation to payload
            if (operation == "catalog") """{"metas":[]}""" else """{"streams":[]}"""
        })
        client.catalog("", "anime", "animeflv", search = "One Piece", genre = "Aventura", skip = 20)
        client.streams("", "series", "pelisplushd:fixture:2:3")
        val search = Json.parseToJsonElement(requests[0].second).jsonObject
        val stream = Json.parseToJsonElement(requests[1].second).jsonObject
        assertEquals("One Piece", search["search"]?.jsonPrimitive?.content)
        assertEquals("20", search["skip"]?.jsonPrimitive?.content)
        assertEquals("pelisplushd:fixture:2:3", stream["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `los catalogos adicionales son optativos tambien en la peticion Python`() = runBlocking {
        var payload = ""
        val options = Options()
        val client = EmbeddedContentClient(EmbeddedRuntime { _, request -> payload = request; feed }) { options.metadataCatalogs }
        client.catalogs("")
        assertEquals("false", Json.parseToJsonElement(payload).jsonObject["metadataCatalogs"]?.jsonPrimitive?.content)
        options.metadataCatalogs = true
        client.catalogs("")
        assertEquals("true", Json.parseToJsonElement(payload).jsonObject["metadataCatalogs"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cancelar una busqueda no se convierte en error ni activa otro backend`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val client = EmbeddedContentClient(EmbeddedRuntime { _, _ -> started.complete(Unit); awaitCancellation() })
        val repository = StreamsRepository(client, Options())
        val job = async { repository.streamsFor("movie", "fixture") }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `los identificadores con episodio no abren otro selector ni duplican el sufijo`() {
        assertTrue(hasEpisodeNumber("series", "pelisplushd:fixture:2:3"))
        assertTrue(hasEpisodeNumber("series", "tt1234567:1:2"))
        assertTrue(hasEpisodeNumber("anime", "animeflv:one-piece:1180"))
        assertTrue(hasEpisodeNumber("anime", "jkanime:one-piece:1:1180"))
        assertFalse(hasEpisodeNumber("anime", "animeflv:one-piece"))
        assertFalse(hasEpisodeNumber("series", "tt1234567"))
    }
}
