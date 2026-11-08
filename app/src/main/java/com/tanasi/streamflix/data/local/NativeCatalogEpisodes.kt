package com.tanasi.streamflix.data.local

import kotlinx.serialization.json.*

/** Extrae temporada/episodio de enlaces ya leídos por el parser de catálogos. */
internal class NativeCatalogEpisodes {
    fun linkIndices(linksJson: String, refsJson: String, slug: String, anime: Boolean): String {
        return try {
            val links = Json.parseToJsonElement(linksJson) as? JsonArray ?: return ""
            val refs = Json.parseToJsonElement(refsJson) as? JsonArray ?: return ""
            if (anime && refs.size != links.size) return ""
            if (slug.any { it.code > 127 || it == '\r' || it == '\n' }) return ""
            val out = mutableListOf<JsonElement>()
            val patterns = if (anime) emptyList() else listOf(
                Regex("(?:temporada|season)[/-]([0-9]+)/(?:capitulo|episode)[/-]([0-9]+)", RegexOption.IGNORE_CASE),
                Regex("/${Regex.escape(slug)}/([0-9]+)/([0-9]+)(?:/|$)", RegexOption.IGNORE_CASE),
                Regex("([0-9]{1,2})x([0-9]{1,4})", RegexOption.IGNORE_CASE),
            )
            for ((index, entry) in links.withIndex()) {
                val link = entry as? JsonObject ?: return ""
                val url = link.string("url") ?: return ""
                val title = link.string("title") ?: return ""
                val attrs = link["attrs"] as? JsonObject ?: return ""
                var season: Int? = null
                var episode: Int? = null
                if (anime) {
                    val ref = refs[index]
                    if (ref !is JsonNull) {
                        val obj = ref as? JsonObject ?: return ""
                        if (obj.string("slug") == slug && obj["episode"] !is JsonNull) {
                            val ep = obj["episode"] as? JsonPrimitive ?: return ""
                            if (ep.isString) return ""
                            episode = ep.content.toIntOrNull() ?: return ""
                            season = 1
                        }
                    }
                } else {
                    val rawSeason = attrs.string("data-s").orEmpty().ifEmpty { attrs.string("data-season").orEmpty() }
                    val rawEpisode = attrs.string("data-e").orEmpty().ifEmpty { attrs.string("data-episode").orEmpty() }
                    if ((rawSeason + rawEpisode + url + title).any { it.code > 127 || it == '\r' || it == '\n' }) return ""
                    fun numeric(value: String) = value.isNotEmpty() && value.all { it in '0'..'9' }
                    if (numeric(rawSeason) && numeric(rawEpisode)) {
                        season = rawSeason.toIntOrNull() ?: return ""
                        episode = rawEpisode.toIntOrNull() ?: return ""
                    } else if (slug in url) {
                        val source = "$url $title"
                        for (pattern in patterns) {
                            val match = pattern.find(source) ?: continue
                            season = match.groupValues[1].toIntOrNull() ?: return ""
                            episode = match.groupValues[2].toIntOrNull() ?: return ""
                            break
                        }
                    }
                }
                if (season != null && episode != null && season in 0..100 && episode in 0..10000) {
                    out.add(buildJsonArray { add(season); add(episode); add(index) })
                }
            }
            JsonArray(out).toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    fun renderVideos(entriesJson: String, baseId: String): String {
        return try {
            if (baseId.any { it.isSurrogate() }) return ""
            val rows = Json.parseToJsonElement(entriesJson) as? JsonArray ?: return ""
            val parsed = rows.map { entry ->
                val item = entry as? JsonArray ?: throw IllegalArgumentException()
                if (item.size != 3) throw IllegalArgumentException()
                val season = (item[0] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw IllegalArgumentException()
                val episode = (item[1] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw IllegalArgumentException()
                if (season !in 0..100 || episode !in 0..10000) throw IllegalArgumentException()
                val name = (item[2] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException()
                if (name.any { it.isSurrogate() }) throw IllegalArgumentException()
                Triple(season, episode, name)
            }.sortedWith(compareBy({ it.first }, { it.second }))
            buildJsonArray {
                for ((season, episode, name) in parsed) add(buildJsonObject {
                    put("id", "$baseId:$season:$episode"); put("season", season)
                    put("episode", episode); put("name", name)
                })
            }.toString()
        } catch (_: IllegalArgumentException) { "" }
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}
