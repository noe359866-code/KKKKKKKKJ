package com.tanasi.streamflix.data.embedded

/** Id de Stremio partido en base + temporada/episodio, como `lib/ids.js`. */
data class MediaId(
    val raw: String,
    val base: String,
    val season: Int?,
    val episode: Int?,
    val isLive: Boolean,
    val isSports: Boolean,
    val imdbId: String?,
)

object EmbeddedIds {
    fun normalize(id: String): String = id.trim().removeSuffix(".json")

    fun parse(rawId: String, type: String = ""): MediaId {
        val raw = normalize(rawId)
        val segments = raw.split(':').filter { it.isNotBlank() }
        val base = segments.firstOrNull().orEmpty()
        var season: Int? = null
        var episode: Int? = null
        if (segments.size >= 3) {
            val s = segments[segments.size - 2].toIntOrNull()
            val e = segments[segments.size - 1].toIntOrNull()
            if (s != null && e != null) {
                season = s
                episode = e
            }
        }
        val lower = base.lowercase()
        val isSports = lower in setOf("sports", "sport", "deportes") || raw.contains(":sport:", ignoreCase = true)
        val isLive = type.equals("tv", ignoreCase = true) || isSports || lower == "tv" || raw.startsWith("tv:", ignoreCase = true)
        return MediaId(
            raw = raw,
            base = base,
            season = season,
            episode = episode,
            isLive = isLive,
            isSports = isSports,
            imdbId = base.takeIf { it.startsWith("tt") },
        )
    }
}
