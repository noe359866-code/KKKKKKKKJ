@file:JvmName("CloudflareKillerKt")

package com.lagradost.cloudstream3.network

import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Response

/** Host compatibility shim: it does not bypass Cloudflare challenges or manipulate TLS. */
class CloudflareKiller : Interceptor {
    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())

    fun getCookieHeaders(url: String): Headers {
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        return getHeaders(emptyMap(), savedCookies[host].orEmpty())
    }

    companion object {
        const val TAG = "CloudflareKiller"
        @JvmStatic fun parseCookieMap(cookie: String): Map<String, String> = cookie.split(';')
            .mapNotNull { part ->
                val split = part.trim().split('=', limit = 2)
                split.getOrNull(1)?.let { split[0].trim() to it.trim() }
            }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .toMap()
    }
}

fun getHeaders(headers: Map<String, String>, cookie: Map<String, String>): Headers {
    val cookieHeader = cookie.takeIf { it.isNotEmpty() }?.entries
        ?.joinToString("; ") { "${it.key}=${it.value}" }
    return (headers + listOfNotNull(cookieHeader?.let { "Cookie" to it }).toMap()).toHeaders()
}
