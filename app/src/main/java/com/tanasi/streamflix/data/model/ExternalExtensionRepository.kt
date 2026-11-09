package com.tanasi.streamflix.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Third-party package index. These formats describe executable code, not Stremio addons. */
@Serializable
enum class ExtensionRepositoryKind {
    @SerialName("cloudstream") CLOUDSTREAM,
    @SerialName("nuvio") NUVIO,
}

@Serializable
data class ExternalExtensionRepository(
    val kind: ExtensionRepositoryKind,
    val url: String,
    val name: String,
    val description: String? = null,
    val itemCount: Int,
    /** Cached index metadata; downloaded packages are managed separately. */
    val entries: List<ExternalExtensionEntry> = emptyList(),
)

@Serializable
data class ExternalExtensionEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: String? = null,
    val language: String? = null,
    val supportedTypes: List<String> = emptyList(),
    val packageUrl: String? = null,
    /** Cloudstream uses the `sha256-<hex>` `fileHash` field for package integrity. */
    val packageSha256: String? = null,
    val packageSizeBytes: Long? = null,
)
