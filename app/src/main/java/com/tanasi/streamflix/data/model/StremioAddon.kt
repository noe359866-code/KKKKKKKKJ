package com.tanasi.streamflix.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Recursos declarados en el manifest de Stremio.
 * Puede ser un string simple ("stream", "catalog", "meta") o un objeto
 * con filtros de tipos y prefijos de id:
 * `{"name": "stream", "types": ["movie", "series"], "idPrefixes": ["tt"]}`
 */
@Serializable(with = StremioResourceSerializer::class)
data class StremioResource(
    val name: String,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
)

object StremioResourceSerializer : KSerializer<StremioResource> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("StremioResource")

    override fun deserialize(decoder: Decoder): StremioResource {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("StremioResource solo puede deserializarse desde JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                StremioResource(name = element.content)
            }
            is JsonObject -> {
                val name = element["name"]?.jsonPrimitive?.content.orEmpty()
                val types = element["types"]?.let { typesEl ->
                    if (typesEl is JsonArray) {
                        typesEl.mapNotNull { (it as? JsonPrimitive)?.content }
                    } else emptyList()
                } ?: emptyList()
                val prefixes = element["idPrefixes"]?.let { prefixEl ->
                    if (prefixEl is JsonArray) {
                        prefixEl.mapNotNull { (it as? JsonPrimitive)?.content }
                    } else emptyList()
                } ?: emptyList()
                StremioResource(name = name, types = types, idPrefixes = prefixes)
            }
            else -> StremioResource(name = "")
        }
    }

    override fun serialize(encoder: Encoder, value: StremioResource) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("StremioResource solo puede serializarse a JSON")
        val obj = buildJsonObject {
            put("name", JsonPrimitive(value.name))
            if (value.types.isNotEmpty()) {
                put("types", buildJsonArray { value.types.forEach { add(JsonPrimitive(it)) } })
            }
            if (value.idPrefixes.isNotEmpty()) {
                put("idPrefixes", buildJsonArray { value.idPrefixes.forEach { add(JsonPrimitive(it)) } })
            }
        }
        jsonEncoder.encodeJsonElement(obj)
    }
}

/** Extra opcional u obligatorio soportado por un catálogo de Stremio (search, genre, skip). */
@Serializable
data class StremioCatalogExtra(
    @SerialName("name") val name: String,
    @SerialName("isRequired") val isRequired: Boolean = false,
    @SerialName("options") val options: List<String> = emptyList(),
)

/** Catálogo declarado en el manifest de un addon de Stremio. */
@Serializable
data class StremioManifestCatalog(
    @SerialName("type") val type: String,
    @SerialName("id") val id: String,
    @SerialName("name") val name: String? = null,
    @SerialName("pageSize") val pageSize: Int? = null,
    @SerialName("extra") val extra: List<StremioCatalogExtra> = emptyList(),
)

/** Manifest de un addon de Stremio v3. */
@Serializable
data class StremioManifest(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("version") val version: String = "1.0.0",
    @SerialName("description") val description: String? = null,
    @SerialName("logo") val logo: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("types") val types: List<String> = emptyList(),
    @SerialName("resources") val resources: List<StremioResource> = emptyList(),
    @SerialName("catalogs") val catalogs: List<StremioManifestCatalog> = emptyList(),
)

/** Addon instalado y gestionable en la aplicación. */
@Serializable
data class StremioAddon(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("version") val version: String = "1.0.0",
    @SerialName("description") val description: String? = null,
    @SerialName("icon") val icon: String? = null,
    @SerialName("manifestUrl") val manifestUrl: String,
    @SerialName("baseUrl") val baseUrl: String,
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("resources") val resources: List<StremioResource> = emptyList(),
    @SerialName("types") val types: List<String> = emptyList(),
    @SerialName("catalogs") val catalogs: List<StremioManifestCatalog> = emptyList(),
) {
    fun supportsResource(resourceName: String, type: String? = null, id: String? = null): Boolean {
        if (!enabled) return false
        val effective = if (resources.isEmpty()) {
            listOf(
                StremioResource("catalog", types = types),
                StremioResource("meta", types = types),
                StremioResource("stream", types = types),
            )
        } else {
            resources
        }
        val matching = effective.filter { it.name.equals(resourceName, ignoreCase = true) }
        if (matching.isEmpty()) return false
        return matching.any { res ->
            val typeOk = type == null || res.types.isEmpty() || res.types.any { it.equals(type, ignoreCase = true) }
            val prefixOk = id == null || res.idPrefixes.isEmpty() || res.idPrefixes.any { id.startsWith(it) }
            typeOk && prefixOk
        }
    }

    fun supportsSearch(type: String, catalogId: String): Boolean {
        val cat = catalogs.firstOrNull { it.type.equals(type, ignoreCase = true) && it.id == catalogId }
        return cat?.extra?.any { it.name.equals("search", ignoreCase = true) } == true
    }
}

/** Preconfiguración sugerida para instalar con 1 clic. */
data class StremioPreset(
    val name: String,
    val description: String,
    val manifestUrl: String,
    /**
     * `true` cuando la app ya publica esos catálogos por su cuenta (Cinemeta a
     * través del motor local). Se sigue ofreciendo en Ajustes, pero no se
     * instala solo para no duplicar filas ni peticiones.
     */
    val bundled: Boolean = false,
)
