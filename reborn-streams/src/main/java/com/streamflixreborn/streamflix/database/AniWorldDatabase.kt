package com.streamflixreborn.streamflix.database

import android.content.Context
import com.streamflixreborn.streamflix.database.dao.TvShowDao

class AniWorldDatabase private constructor() {
    private val dao = TvShowDao()
    fun tvShowDao(): TvShowDao = dao

    companion object {
        @Volatile private var instance: AniWorldDatabase? = null
        fun getInstance(context: Context): AniWorldDatabase =
            instance ?: synchronized(this) { instance ?: AniWorldDatabase().also { instance = it } }
    }
}
