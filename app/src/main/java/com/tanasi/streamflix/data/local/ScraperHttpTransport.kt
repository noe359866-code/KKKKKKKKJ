package com.tanasi.streamflix.data.local

import android.util.Base64
import androidx.annotation.Keep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Invocado por nombre desde Python: @Keep es intencional y se limita a esta clase.
 * OkHttp impone callTimeout sobre TODO el documento, no sólo entre lecturas del
 * socket. Un origen que envía bytes muy despacio no puede ocupar un worker Python
 * indefinidamente. TLS usa la confianza de Android; nunca se desactiva su validación.
 */
@Keep
class ScraperHttpTransport {
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun request(url: String, method: String, headersJson: String, timeoutMs: Int, bodyBase64: String?): String {
        val client = http.newBuilder()
            .callTimeout(timeoutMs.coerceIn(100, 8_000).toLong(), TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(url)
        Json.parseToJsonElement(headersJson).jsonObject.forEach { (name, value) ->
            request.header(name, value.jsonPrimitive.content)
        }
        val body = bodyBase64?.let {
            Base64.decode(it, Base64.NO_WRAP).toRequestBody("application/json".toMediaType())
        }
        request.method(method, body)
        return client.newCall(request.build()).execute().use { response ->
            val bytes = ByteArrayOutputStream()
            response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(8_192)
                while (bytes.size() < MAX_BODY_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES - bytes.size()))
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                }
            }
            buildJsonObject {
                put("url", response.request.url.toString())
                put("status", response.code)
                put("headers", buildJsonObject {
                    response.headers.names().forEach { name -> put(name, response.header(name).orEmpty()) }
                })
                put("body", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
            }.toString()
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 1_500_000
    }
}
