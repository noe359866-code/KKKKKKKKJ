package com.streamflixreborn.streamflix.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleMatcherTest {
    @Test
    fun `normalizes accents and punctuation`() {
        assertEquals("rapido y furioso 9", TitleMatcher.normalize("Rápido & Furioso 9!"))
    }

    @Test
    fun `prefers exact title and year`() {
        val request = StreamRequest(type = "movie", id = "tt1", title = "Dune", year = 2021)
        val index = TitleMatcher.best(
            listOf("Dune" to 1984, "Dune: Part Two" to 2024, "Dune" to 2021),
            request,
        )
        assertEquals(2, index)
    }

    @Test
    fun `rejects unrelated candidates`() {
        val request = StreamRequest(type = "movie", id = "tt1", title = "Dune", year = 2021)
        assertNull(TitleMatcher.best(listOf("Star Wars" to 1977), request))
    }
}
