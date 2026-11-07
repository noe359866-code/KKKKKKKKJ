package com.streamflixreborn.streamflix.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** OkHttp-backed shim for the upstream Cronet helper (no Cronet AAR shipped). */
object AnimeOnlineNinjaCronetClient {
    data class Response(
        val statusCode: Int,
        val finalUrl: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        val isSuccessful: Boolean get() = statusCode in 200..299
        fun bodyAsString(): String = body.toString(Charsets.UTF_8)
    }

    fun init(context: Context) = Unit

    suspend fun get(
        context: Context,
        url: String,
        headers: Map<String, String>,
        useCache: Boolean = true,
    ): Response = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> if (value.isNotBlank()) builder.header(name, value) }
        if (!headers.keys.any { it.equals("User-Agent", true) }) {
            builder.header("User-Agent", NetworkClient.USER_AGENT)
        }
        NetworkClient.default.newCall(builder.build()).execute().use { response ->
            Response(
                statusCode = response.code,
                finalUrl = response.request.url.toString(),
                headers = response.headers.toMultimap(),
                body = response.body?.bytes() ?: ByteArray(0),
            )
        }
    }
}
