package com.tanasi.streamflix.data.local

import androidx.annotation.Keep
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Caché acotada del motor local. Los valores son JSON, nunca código ni vídeo. */
@Keep
class NativeJsonCache internal constructor(
    private val directory: File,
    private val maxEntries: Int,
    private val clock: () -> Double,
) {
    constructor(directory: String) : this(File(directory), 160, { System.currentTimeMillis() / 1000.0 })

    // Dos instancias pueden compartir cacheDir; serializar también entre ellas
    // evita que ambas escriban el mismo archivo temporal a la vez.
    private val lock = LOCKS.getOrPut(directory.absoluteFile.normalize().path) { Any() }

    init { directory.mkdirs() }

    private fun path(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$hash.json")
    }

    /** null indica que no hay entrada válida; "null" es un valor JSON válido. */
    fun getJson(key: String, staleSeconds: Long): String? = synchronized(lock) {
        try {
            val file = path(key)
            if (!file.isFile || file.length() > MAX_READ_BYTES) return@synchronized null
            val entry = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject ?: return@synchronized null
            // kotlinx.serialization admite algunos valores sin comillas incluso
            // en modo estricto. No devolver JSON corrupto al motor Python.
            if (!validJson(entry)) return@synchronized null
            val expiryValue = entry["expires"] as? JsonPrimitive ?: return@synchronized null
            if (expiryValue.isString) return@synchronized null
            val expires = expiryValue.doubleOrNull ?: return@synchronized null
            if (!expires.isFinite() || expires + staleSeconds <= clock()) return@synchronized null
            (entry["value"] ?: return@synchronized null).toString()
        } catch (_: Exception) { null }
        catch (_: StackOverflowError) { null }
    }

    fun putJson(key: String, valueJson: String, ttl: Long) {
        if (ttl <= 0) return
        synchronized(lock) {
            val file = path(key)
            val temp = File(directory, file.nameWithoutExtension + ".tmp")
            try {
                val value = Json.parseToJsonElement(valueJson)
                val content = buildJsonObject { put("expires", clock() + ttl); put("value", value) }.toString()
                temp.writeText(content, Charsets.UTF_8)
                // En Android, renameTo reemplaza el destino de forma atómica en el mismo directorio.
                if (!temp.renameTo(file)) throw java.io.IOException("Cache rename failed")
                val files = directory.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".json") }
                    ?.sortedBy { it.lastModified() }.orEmpty()
                files.take((files.size - maxEntries).coerceAtLeast(0)).forEach { it.delete() }
            } catch (_: Exception) { temp.delete() }
        }
    }

    private fun validJson(value: JsonElement, depth: Int = 0): Boolean {
        if (depth > 256) return false
        return when (value) {
            is JsonObject -> value.values.all { validJson(it, depth + 1) }
            is JsonArray -> value.all { validJson(it, depth + 1) }
            is JsonNull -> true
            is JsonPrimitive -> value.isString || value.content in setOf("true", "false") || NUMBER.matches(value.content)
        }
    }

    fun clear() = synchronized(lock) {
        directory.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".json") }
            ?.forEach { it.delete() }
    }

    private companion object {
        const val MAX_READ_BYTES = 2_000_000L
        val LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()
        val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
