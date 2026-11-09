package com.tanasi.streamflix.data.phone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PhoneSetupKind {
    @SerialName("stremio") STREMIO_ADDON,
    @SerialName("nuvio") NUVIO_REPOSITORY,
    @SerialName("cloudstream") CLOUDSTREAM_REPOSITORY,
}

@Serializable
data class PhoneSetupEntry(
    val kind: PhoneSetupKind,
    val url: String,
    val name: String = "",
)

@Serializable
data class PhoneSetupPreferences(
    val autonomous: Boolean,
    val metadataCatalogs: Boolean,
    val autoCheckUpdates: Boolean,
    val baseUrl: String,
)

data class PhoneSetupSnapshot(
    val entries: List<PhoneSetupEntry>,
    val preferences: PhoneSetupPreferences,
)

@Serializable
internal data class PhoneSetupRequest(
    val entries: List<PhoneSetupEntry>,
    val preferences: PhoneSetupPreferences,
)

@Serializable
internal data class PhoneSetupStateResponse(
    val entries: List<PhoneSetupEntry>,
    val preferences: PhoneSetupPreferences,
)

@Serializable
internal data class PhoneSetupProposalResponse(
    val status: String,
    val id: String? = null,
    val error: String? = null,
)

@Serializable
internal data class PhoneSetupStatusResponse(
    val status: String,
    val message: String? = null,
)

data class PhoneSetupChange(
    val id: String,
    val added: List<PhoneSetupEntry>,
    val removed: List<PhoneSetupEntry>,
    val preferences: PhoneSetupPreferences? = null,
)

data class PhoneSetupSessionState(
    val isStarting: Boolean = false,
    val isActive: Boolean = false,
    val serverUrl: String? = null,
    val qrBitmap: android.graphics.Bitmap? = null,
    val pendingChange: PhoneSetupChange? = null,
    val lastResult: String? = null,
    val error: String? = null,
)
