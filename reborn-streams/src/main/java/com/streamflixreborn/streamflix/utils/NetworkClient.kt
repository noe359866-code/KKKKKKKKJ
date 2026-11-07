package com.streamflixreborn.streamflix.utils

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkClient {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val cookieJar = MyCookieJar()

    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .dns(DnsResolver.doh)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Upstream name kept for source compatibility. It deliberately does NOT
     * disable TLS validation; callers get the normal validating client.
     */
    val trustAll: OkHttpClient get() = default
}
