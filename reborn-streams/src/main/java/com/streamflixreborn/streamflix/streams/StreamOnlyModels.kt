package com.streamflixreborn.streamflix.streams

/** Request accepted by the stream-only facade. No catalog/meta response exists. */
data class StreamRequest(
    val type: String,
    val id: String,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String? = null,
) {
    val isMovie: Boolean get() = type.equals("movie", ignoreCase = true)
    val isEpisode: Boolean get() = !isMovie && season != null && episode != null

    companion object {
        /** Parses `{base}:{season}:{episode}` ids used by Cinemeta/this app. */
        fun parseEpisode(id: String): Pair<Int, Int>? {
            val parts = id.split(':')
            if (parts.size < 3) return null
            val season = parts[parts.size - 2].toIntOrNull() ?: return null
            val episode = parts.last().toIntOrNull() ?: return null
            if (season < 0 || episode < 0) return null
            return season to episode
        }
    }
}

data class ResolvedSubtitle(
    val url: String,
    val label: String,
    val lang: String = "",
)

/** Only playable media leaves the Reborn module. */
data class ResolvedStream(
    val url: String,
    val name: String,
    val provider: String,
    val quality: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val mediaType: String? = null,
    val subtitles: List<ResolvedSubtitle> = emptyList(),
)
