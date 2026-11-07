package com.streamflixreborn.streamflix

import android.app.Activity
import android.content.Context

/** Application handle expected by vendored upstream providers/extractors. */
object StreamFlixApp {
    @Volatile
    lateinit var instance: Context
        private set

    @Volatile
    var currentActivity: Activity? = null

    fun initialize(context: Context) {
        instance = context.applicationContext
    }
}
