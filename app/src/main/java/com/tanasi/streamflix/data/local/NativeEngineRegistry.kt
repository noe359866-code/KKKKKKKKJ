package com.tanasi.streamflix.data.local

import kotlinx.serialization.json.*

/** Ordena la lista blanca sin copiar los objetos de proveedor del intérprete. */
internal class NativeEngineRegistry {
    fun selectIndices(rowsJson: String, allowedJson: String): String {
        return try {
            val rows = Json.parseToJsonElement(rowsJson) as? JsonArray ?: return ""
            val allowed = (Json.parseToJsonElement(allowedJson) as? JsonArray ?: return "").map {
                val value = it as? JsonPrimitive ?: throw IllegalArgumentException()
                if (!value.isString) throw IllegalArgumentException()
                value.content
            }.toSet()
            data class Entry(val index: Int, val name: String?, val script: String?, val priority: Long, val enabled: Boolean)
            fun optionalString(value: JsonElement?): String? {
                if (value == null || value is JsonNull) return null
                val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException()
                if (!primitive.isString) throw IllegalArgumentException()
                return primitive.content
            }
            val parsed = rows.mapIndexed { index, item ->
                val obj = item as? JsonObject ?: throw IllegalArgumentException()
                val priority = (obj["priority"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: throw IllegalArgumentException()
                val enabled = (obj["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw IllegalArgumentException()
                Entry(index, optionalString(obj["id"]), optionalString(obj["script"]), priority, enabled)
            }
            JsonArray(parsed.sortedBy { it.priority }.filter {
                it.enabled && it.name != null && it.name in allowed && it.script == "${it.name}.py"
            }.map { JsonPrimitive(it.index) }).toString()
        } catch (_: IllegalArgumentException) { "" }
    }
}
