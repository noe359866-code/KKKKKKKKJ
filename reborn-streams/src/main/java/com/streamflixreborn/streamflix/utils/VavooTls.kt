package com.streamflixreborn.streamflix.utils

import okhttp3.OkHttpClient

/** Secure no-op: never disable certificate/hostname validation in this app. */
object VavooTls {
    fun applyTo(builder: OkHttpClient.Builder, targetHost: String) = Unit
}
