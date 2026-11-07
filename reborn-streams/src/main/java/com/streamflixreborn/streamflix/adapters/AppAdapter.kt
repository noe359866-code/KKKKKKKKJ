package com.streamflixreborn.streamflix.adapters

/**
 * Minimal compile-time stand-in for the upstream RecyclerView adapter.
 * The stream-only port never inflates rows; models only need the marker type.
 */
class AppAdapter {
    interface Item {
        var itemType: Type
    }

    enum class Type { UNKNOWN }
}
