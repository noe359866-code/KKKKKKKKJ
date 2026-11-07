package com.streamflixreborn.streamflix.utils

/**
 * The upstream Uprot API requires private native keys that are not part of this
 * port. Keep already-resolved MaxStream links working and fail closed otherwise.
 */
object UprotResolver {
    const val MAXSTREAM_EMBED_BASE = "https://maxstream.video/emisvjoe/"

    fun resolve(urlOrId: String, isStayOnline: Boolean = false): String? {
        val clean = urlOrId.trim()
        if (clean.contains("maxstream.video", ignoreCase = true)) return clean
        return null
    }
}
