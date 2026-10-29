package com.tanasi.streamflix.data.embedded

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProviderConfig(
    val id: String,
    val label: String = id,
    val script: String,
    val priority: Int = 100,
    val kind: String = "any",
    val enabled: Boolean = true,
    val referer: String = "",
    val userAgent: String = DEFAULT_USER_AGENT,
    val options: JsonObject? = null,
) {
    fun supports(isLive: Boolean): Boolean =
        enabled && (kind == "any" || kind == if (isLive) "live" else "vod")
}

@Serializable
private data class ProvidersFile(val providers: List<ProviderConfig> = emptyList())

/** Registro de scrapers embebidos, ordenado por prioridad de failover. */
class EmbeddedProviders(json: Json, raw: String) {
    val all: List<ProviderConfig> = json.decodeFromString(ProvidersFile.serializer(), raw).providers
        .filter { it.id.isNotBlank() && it.script.isNotBlank() && !it.script.contains('/') && !it.script.contains('\\') }
        .sortedBy { it.priority }

    fun forContent(isLive: Boolean): List<ProviderConfig> = all.filter { it.supports(isLive) }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
