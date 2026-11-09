package com.tanasi.streamflix.ui.home

import androidx.compose.ui.graphics.Color
import com.tanasi.streamflix.data.model.StremioAddon
import com.tanasi.streamflix.data.model.StremioManifestCatalog

/** A local, text-based tile; no remote logo fetches or unlicensed artwork. */
internal data class StreamingPlatform(
    val id: String,
    val label: String,
    val monogram: String,
    val accent: Color,
    val catalogAliases: Set<String>,
)

internal data class PlatformCatalogRef(
    val addonId: String,
    val addonName: String,
    val catalogId: String,
    val catalogName: String,
    val type: String,
)

internal data class AvailableStreamingPlatform(
    val platform: StreamingPlatform,
    val catalogs: List<PlatformCatalogRef>,
)

/**
 * Networks are shown only when an enabled Stremio addon declares a matching
 * movie/series catalog. A tile therefore never promises a catalog the app
 * cannot request from an installed addon.
 */
internal object StreamingPlatforms {
    val all = listOf(
        StreamingPlatform("netflix", "Netflix", "N", Color(0xFFE50914), setOf("netflix", "nfx")),
        StreamingPlatform("prime-video", "Prime Video", "prime", Color(0xFF18A9D6), setOf("prime", "primevideo", "amazonprime", "pmp")),
        StreamingPlatform("disney-plus", "Disney+", "D+", Color(0xFF4B78E8), setOf("disney", "disneyplus", "dnp")),
        StreamingPlatform("max", "Max", "max", Color(0xFF8F72E8), setOf("max", "hbo", "hbm")),
        StreamingPlatform("apple-tv", "Apple TV+", "tv+", Color(0xFFE7E7EB), setOf("apple", "appletv", "atp")),
        StreamingPlatform("paramount-plus", "Paramount+", "P+", Color(0xFF5D9BE8), setOf("paramount", "paramountplus", "pcp")),
        StreamingPlatform("crunchyroll", "Crunchyroll", "CR", Color(0xFFF47521), setOf("crunchyroll", "cru")),
        StreamingPlatform("hulu", "Hulu", "hulu", Color(0xFF42C878), setOf("hulu")),
        StreamingPlatform("amc-plus", "AMC+", "AMC+", Color(0xFFE9E9E9), setOf("amc", "amcplus")),
        StreamingPlatform("mgm-plus", "MGM+", "MGM+", Color(0xFFC7A86B), setOf("mgm", "mgmplus")),
        StreamingPlatform("peacock", "Peacock", "P", Color(0xFFE7D15A), setOf("peacock")),
        StreamingPlatform("shudder", "Shudder", "S", Color(0xFFB6C1D3), setOf("shudder")),
        StreamingPlatform("starz", "STARZ", "STARZ", Color(0xFF9D9FA8), setOf("starz", "starzplay", "star", "stz")),
    )

    fun availableFrom(addons: List<StremioAddon>): List<AvailableStreamingPlatform> {
        val refs = mutableMapOf<String, MutableList<PlatformCatalogRef>>()
        addons.asSequence()
            .filter { it.enabled && it.supportsResource("catalog") }
            .forEach { addon ->
                addon.catalogs
                    .filter { catalog ->
                        (catalog.type.equals("movie", ignoreCase = true) ||
                            catalog.type.equals("series", ignoreCase = true) ||
                            catalog.type.equals("anime", ignoreCase = true)) &&
                            addon.supportsResource("catalog", type = catalog.type)
                    }
                    .forEach catalogLoop@ { catalog ->
                        val platform = platformFor(catalog.id) ?: return@catalogLoop
                        refs.getOrPut(platform.id) { mutableListOf() } += catalog.toRef(addon)
                    }
            }
        return all.mapNotNull { platform ->
            val catalogs = refs[platform.id].orEmpty()
                .distinctBy { Triple(it.addonId, it.catalogId, it.type.lowercase()) }
                .sortedWith(compareBy<PlatformCatalogRef>({ if (it.type.equals("movie", true)) 0 else 1 }, { it.addonName }))
                .take(MAX_CATALOGS_PER_PLATFORM)
            catalogs.takeIf { it.isNotEmpty() }?.let { AvailableStreamingPlatform(platform, it) }
        }
    }

    fun platformFor(catalogId: String): StreamingPlatform? {
        val normalized = catalogId.lowercase().filter(Char::isLetterOrDigit)
        return all.firstOrNull { platform ->
            platform.catalogAliases.any { alias -> normalized == alias || normalized.startsWith(alias) }
        }
    }

    private fun StremioManifestCatalog.toRef(addon: StremioAddon) = PlatformCatalogRef(
        addonId = addon.id,
        addonName = addon.name,
        catalogId = id,
        catalogName = name?.takeIf { it.isNotBlank() } ?: id,
        type = type,
    )

    private const val MAX_CATALOGS_PER_PLATFORM = 16
}
