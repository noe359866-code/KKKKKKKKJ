package com.tanasi.streamflix.data.local

import androidx.annotation.Keep
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.serialization.json.*
import java.util.Locale

/** Adapta peticiones urllib a OkHttp y devuelve una respuesta urllib real. */
@Keep
class NativeHttpAdapter(private val transport: ScraperHttpTransport) {
    /** null señala un fallo de transporte, que el puente convierte en URLError. */
    fun open(request: PyObject, timeout: Double): PyObject? {
        return try {
            val python = Python.getInstance()
            val headers = linkedMapOf<String, String>()
            for (pair in request.callAttr("header_items").asList()) {
                val fields = pair.asList()
                headers[fields[0].toString()] = fields[1].toString()
            }
            val data = request.get("data")
            if (data != null && headers.keys.none { it.lowercase(Locale.ROOT) == "content-type" }) {
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            }
            val base64 = python.getModule("base64")
            val encoded = data?.let { base64.callAttr("b64encode", it).callAttr("decode", "ascii").toString() }
            val response = Json.parseToJsonElement(transport.request(
                request.get("full_url").toString(), request.callAttr("get_method").toString(),
                JsonObject(headers.mapValues { JsonPrimitive(it.value) }).toString(),
                (timeout * 1000).toInt().coerceIn(100, 8000), encoded,
            )).jsonObject
            val status = response["status"]!!.jsonPrimitive.int
            val resultHeaders = python.getModule("email.message").callAttr("Message")
            for ((key, value) in response["headers"]!!.jsonObject) {
                resultHeaders.callAttr("__setitem__", key, value.jsonPrimitive.content)
            }
            val body = base64.callAttr("b64decode", response["body"]!!.jsonPrimitive.content)
            val stream = python.getModule("io").callAttr("BytesIO", body)
            python.getModule("urllib.response").callAttr(
                "addinfourl", stream, resultHeaders, response["url"]!!.jsonPrimitive.content, status,
            )
        } catch (_: Exception) { null }
    }
}
