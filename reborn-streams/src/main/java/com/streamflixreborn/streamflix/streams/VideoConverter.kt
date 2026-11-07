package com.streamflixreborn.streamflix.streams

import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video

/** Converts upstream extractor output to playable streams; rejects pages/embeds. */
object VideoConverter {
    private val rejectedExtensions = listOf(
        ".html", ".htm", ".php", ".asp", ".aspx", ".jsp", ".json", ".txt",
        ".css", ".js", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
    )

    fun looksPlayable(url: String): Boolean {
        val cleaned = url.trim().substringBefore('#').substringBefore('?').lowercase()
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) return false
        if (cleaned.contains("/embed/") || cleaned.contains("/e/")) return false
        return rejectedExtensions.none { cleaned.endsWith(it) }
    }

    fun mediaType(video: Video): String? {
        val type = video.type?.lowercase().orEmpty()
        val url = video.source.substringBefore('?').lowercase()
        return when {
            type.contains("mpegurl") || type.contains("m3u8") || url.endsWith(".m3u8") -> "hls"
            type.contains("dash") || url.endsWith(".mpd") -> "dash"
            type.contains("mp4") || url.endsWith(".mp4") || url.endsWith(".m4v") -> "mp4"
            type == MimeTypes.APPLICATION_M3U8 -> "hls"
            type == MimeTypes.APPLICATION_MPD -> "dash"
            else -> null
        }
    }

    fun quality(serverName: String, url: String): String? {
        val match = Regex("(2160|1440|1080|720|480|360)[pi]", RegexOption.IGNORE_CASE)
            .find(serverName) ?: Regex("(2160|1440|1080|720|480|360)[pi]", RegexOption.IGNORE_CASE).find(url)
        return match?.value?.lowercase()
    }

    fun convert(providerName: String, server: Video.Server, video: Video): ResolvedStream? {
        val url = video.source.trim()
        if (!looksPlayable(url)) return null
        val serverName = server.name.ifBlank { "Stream" }
        return ResolvedStream(
            url = url,
            name = "$providerName · $serverName",
            provider = providerName,
            quality = quality(serverName, url),
            headers = video.headers.orEmpty().filterKeys { it.isNotBlank() }.filterValues { it.isNotBlank() },
            mediaType = mediaType(video),
            subtitles = video.subtitles.mapNotNull { subtitle ->
                val file = subtitle.file.trim()
                if (!file.startsWith("http://") && !file.startsWith("https://")) null
                else ResolvedSubtitle(url = file, label = subtitle.label.ifBlank { "Subtitle" }, lang = subtitle.label)
            },
        )
    }
}
