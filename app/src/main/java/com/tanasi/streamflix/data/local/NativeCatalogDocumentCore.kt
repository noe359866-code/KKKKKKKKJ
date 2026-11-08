package com.tanasi.streamflix.data.local

import kotlinx.serialization.json.*
import java.util.Locale

/** Procesa eventos del HTMLParser compartido; no ejecuta HTML ni JavaScript. */
internal class NativeCatalogDocumentCore(private val page: String, private val resolve: (String, String) -> String) {
    private val links = mutableListOf<JsonObject>()
    private val meta = linkedMapOf<String, JsonElement>()
    private val heading = mutableListOf<String>()
    private var h1 = false
    private var anchor: MutableMap<String, JsonElement>? = null
    private val text = mutableListOf<String>()

    fun start(tag: String, attrsJson: String): Boolean {
        val attrs = try { Json.parseToJsonElement(attrsJson) as? JsonObject ?: return false }
                    catch (_: IllegalArgumentException) { return false }
        for ((key, value) in attrs) {
            val field = value as? JsonPrimitive ?: return false
            if (!field.isString || key.any(Char::isSurrogate) || field.content.any(Char::isSurrogate)) return false
        }
        fun attr(key: String) = (attrs[key] as? JsonPrimitive)?.content.orEmpty()
        when (tag) {
            "meta" -> {
                val key = attr("property").ifEmpty { attr("name") }
                // Python and Java have different Unicode case-folding tables.
                if (key.any { it.code > 127 }) return false
                if (key.isNotEmpty()) meta[key.lowercase(Locale.ROOT)] = JsonPrimitive(attr("content"))
            }
            "h1" -> h1 = true
            "a" -> {
                if (anchor != null) end("a")
                val url = resolve(page, attr("href"))
                if (url.any(Char::isSurrogate)) return false
                anchor = linkedMapOf("url" to JsonPrimitive(url), "attrs" to attrs,
                    "title" to JsonPrimitive(attr("title")), "poster" to JsonPrimitive(""))
                text.clear()
            }
            "img" -> if (anchor != null) {
                for (key in listOf("data-src", "data-original", "data-lazy-src", "src")) {
                    val image = resolve(page, attr(key))
                    if (image.any(Char::isSurrogate)) return false
                    if (attr(key).isNotEmpty() && (image.startsWith("http://") || image.startsWith("https://"))) {
                        anchor!!["poster"] = JsonPrimitive(image)
                        break
                    }
                }
                if (anchor!!["title"]!!.jsonPrimitive.content.isEmpty()) anchor!!["title"] = JsonPrimitive(attr("alt"))
            }
        }
        return true
    }

    fun data(value: String): Boolean {
        if (value.any(Char::isSurrogate)) return false
        if (anchor != null) text.add(value)
        if (h1) heading.add(value)
        return true
    }

    fun end(tag: String) {
        if (tag == "a" && anchor != null) {
            val current = anchor!!
            if (current["title"]!!.jsonPrimitive.content.isEmpty()) current["title"] = JsonPrimitive(text.joinToString(" "))
            links.add(JsonObject(current.toMap()))
            anchor = null
            text.clear()
        } else if (tag == "h1") h1 = false
    }

    fun snapshot(): String = buildJsonObject {
        put("links", JsonArray(links)); put("meta", JsonObject(meta));
        put("heading", JsonArray(heading.map(::JsonPrimitive)))
    }.toString()
}
