package com.tanasi.streamflix.ui.settings

import com.tanasi.streamflix.data.remote.Urls

/** Cambios pendientes de conexión y actualizaciones; los addons se aplican aparte. */
internal data class SettingsDraft(
    val autonomous: Boolean,
    val metadataCatalogs: Boolean,
    val baseUrl: String,
    val autoCheckUpdates: Boolean,
) {
    val isValid: Boolean get() = autonomous || Urls.isValidBase(baseUrl)

    fun hasChangesFrom(saved: SettingsDraft): Boolean = this != saved

    fun canSave(addonInput: String, installing: Boolean): Boolean =
        isValid && addonInput.isBlank() && !installing

    fun normalizedBaseUrl(): String = if (baseUrl.isBlank()) "" else Urls.normalizeBase(baseUrl)
}
