package com.streamflixreborn.streamflix.database

import android.content.Context
import com.streamflixreborn.streamflix.database.dao.TvShowDao

class SerienStreamDatabase private constructor() {
    private val dao = TvShowDao()
    fun tvShowDao(): TvShowDao = dao

    companion object {
        @Volatile private var instance: SerienStreamDatabase? = null
        fun getInstance(context: Context): SerienStreamDatabase =
            instance ?: synchronized(this) { instance ?: SerienStreamDatabase().also { instance = it } }
    }
}
