package com.streamflixreborn.streamflix.database.dao

import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory DAO shim; the stream-only port does not persist catalog data. */
class TvShowDao {
    private val shows = MutableStateFlow<Map<String, TvShow>>(emptyMap())

    fun getById(id: String): TvShow? = shows.value[id]

    fun getAll(): Flow<List<TvShow>> = shows.map { it.values.toList() }

    suspend fun getAllIds(): List<String> = shows.value.keys.toList()

    suspend fun searchTvShows(query: String, limit: Int, offset: Int): List<TvShow> =
        shows.value.values
            .filter { it.title.lowercase().contains(query.lowercase()) }
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceAtLeast(0))

    fun insertAll(tvShows: List<TvShow>) {
        shows.value = shows.value + tvShows.associateBy { it.id }
    }

    fun update(tvShow: TvShow) {
        shows.value = shows.value + (tvShow.id to tvShow)
    }

    suspend fun getAllWithNullPoster(): List<TvShow> =
        shows.value.values.filter { it.poster.isNullOrBlank() }
}
