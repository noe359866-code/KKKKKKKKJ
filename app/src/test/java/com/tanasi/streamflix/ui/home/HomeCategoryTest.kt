package com.tanasi.streamflix.ui.home

import com.tanasi.streamflix.data.model.CatalogRow
import com.tanasi.streamflix.data.model.MediaEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCategoryTest {
    private val movie = MediaEntry(id = "m", type = "movie", title = "Película")
    private val series = MediaEntry(id = "s", type = "series", title = "Serie")
    private val anime = MediaEntry(id = "a", type = "anime", title = "Anime")
    private val rows = listOf(
        CatalogRow(id = "mixed", title = "Destacados", items = listOf(movie, series, anime)),
        CatalogRow(id = "empty", title = "Vacía"),
    )

    @Test fun filtersItemsWithoutChangingRowMetadataOrOrder() {
        val filtered = filterHomeRows(rows, HomeCategory.Series)
        assertEquals(listOf("mixed"), filtered.map { it.id })
        assertEquals("Destacados", filtered.single().title)
        assertEquals(listOf(series, anime), filtered.single().items)
        assertEquals(listOf(movie), filterHomeRows(rows, HomeCategory.Movies).single().items)
    }

    @Test fun emptyCategoriesHaveNoEmptyRows() {
        assertTrue(filterHomeRows(listOf(rows.last()), HomeCategory.Home).isEmpty())
    }
    @Test fun animeCountsAsSeries() {
        assertEquals(listOf(series, anime), filterHomeRows(rows, HomeCategory.Series).single().items)
    }
}
