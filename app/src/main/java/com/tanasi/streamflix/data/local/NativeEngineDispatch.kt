package com.tanasi.streamflix.data.local

import kotlinx.serialization.json.*

/** Validación y planificación de despachos; "" conserva la semántica Python para casos especiales. */
internal class NativeEngineDispatch {
    fun plan(operation: String, requestJson: String): String {
        return try {
            if (operation.any(Char::isSurrogate)) return ""
            val request = Json.parseToJsonElement(requestJson) as? JsonObject ?: return ""
            fun truthy(value: JsonElement?): Boolean = when (value) {
                null, JsonNull -> false
                is JsonArray -> value.isNotEmpty()
                is JsonObject -> value.isNotEmpty()
                is JsonPrimitive -> if (value.isString) value.content.isNotEmpty()
                    else when (value.content) {
                        "true" -> true
                        "false" -> false
                        else -> (value.content.toDoubleOrNull() ?: throw IllegalArgumentException()) != 0.0
                    }
            }
            fun textOrEmpty(key: String): String {
                val value = request[key] ?: return ""
                if (value is JsonNull) return ""
                val element = value as? JsonPrimitive ?: throw IllegalArgumentException()
                if (!element.isString || element.content.any(Char::isSurrogate)) throw IllegalArgumentException()
                return element.content
            }
            fun result(action: String) = buildJsonObject { put("action", action) }.toString()
            when (operation) {
                "catalogs" -> return buildJsonObject { put("action", "catalogs"); put("enabled", truthy(request["metadataCatalogs"])) }.toString()
                "health", "clear_cache" -> return result(operation)
            }
            // Non-string types may be unhashable in Python; let it decide.
            val rawType = request["type"]
            val mediaType = when (rawType) {
                null, JsonNull -> ""
                is JsonPrimitive -> if (rawType.isString) rawType.content else return ""
                else -> return ""
            }
            if (mediaType.any(Char::isSurrogate)) return ""
            val id = textOrEmpty("id")
            if (mediaType !in TYPES || id.isEmpty() || id.length > 1500) return result("invalid")
            when (operation) {
                "catalog" -> {
                    val search = textOrEmpty("search").take(150)
                    val genre = textOrEmpty("genre")
                    val rawSkip = request["skip"]
                    val skip = if (rawSkip == null || rawSkip is JsonNull) 0 else {
                        val primitive = rawSkip as? JsonPrimitive ?: return ""
                        if (primitive.isString || primitive.content == "true" || primitive.content == "false") return ""
                        primitive.content.toIntOrNull() ?: return ""
                    }
                    return buildJsonObject {
                        put("action", operation); put("type", mediaType); put("id", id)
                        put("search", search); put("genre", genre); put("skip", skip.coerceAtLeast(0))
                    }.toString()
                }
                "meta", "streams", "subtitles" -> return buildJsonObject {
                    put("action", operation); put("type", mediaType); put("id", id)
                }.toString()
                else -> return result("unknown")
            }
        } catch (_: IllegalArgumentException) { "" }
    }

    private companion object { val TYPES = setOf("movie", "series", "anime") }
}
