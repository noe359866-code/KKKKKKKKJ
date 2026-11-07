package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.providers.Provider
import java.util.concurrent.ConcurrentHashMap

/** Lightweight, stream-only replacement for the upstream settings store. */
object UserPreferences {
    const val PROVIDER_URL = "provider_url"
    const val PROVIDER_LOGO = "provider_logo"
    const val PROVIDER_PORTAL_URL = "provider_portal_url"
    const val PROVIDER_AUTOUPDATE = "provider_autoupdate"
    const val PROVIDER_NEW_INTERFACE = "provider_new_interface"
    const val PROVIDER_PREFERRED_SERVER = "provider_preferred_server"

    private val cache = ConcurrentHashMap<String, String>()
    private val customDomains = ConcurrentHashMap<String, String>()

    private val currentProviderLocal = ThreadLocal<Provider?>()
    var currentProvider: Provider?
        get() = currentProviderLocal.get()
        set(value) { currentProviderLocal.set(value) }
    @Volatile var providerLanguage: String? = null
    @Volatile var serverAutoSubtitlesDisabled: Boolean = false
    @Volatile var cuevanaDomain: String = "cuevana.eu"
    @Volatile var moflixDomain: String = "moflix-stream.link"
    @Volatile var poseidonDomain: String = "poseidonhd2.com"
    @Volatile var serienstreamDomain: String = "serienstream.to"
    @Volatile var streamingcommunityDomain: String = "streamingcommunity.credit"
    @Volatile var tmdbApiKey: String = BuildConfig.TMDB_API_KEY
    @Volatile var tmdbCategories: Set<String> = emptySet()

    val enableTmdb: Boolean
        get() = tmdbApiKey.isNotBlank()

    fun getProviderCache(provider: Provider, key: String): String =
        getProviderCache(provider.name, key)

    fun getProviderCache(providerName: String, key: String): String =
        cache["$providerName::$key"].orEmpty()

    fun setProviderCache(provider: Provider?, key: String, value: String) {
        setProviderCache(provider?.name ?: return, key, value)
    }

    fun setProviderCache(providerName: String, key: String, value: String) {
        if (value.isBlank()) cache.remove("$providerName::$key")
        else cache["$providerName::$key"] = value
    }

    fun clearProviderCache(providerName: String) {
        cache.keys.removeIf { it.startsWith("$providerName::") }
    }

    fun setProviderCustomDomain(providerName: String, value: String) {
        val clean = value.trim().trimEnd('/')
        if (clean.isBlank()) customDomains.remove(providerName)
        else customDomains[providerName] = clean
    }

    fun resolveProviderBaseUrl(providerName: String, defaultBaseUrl: String): String {
        val custom = customDomains[providerName]?.takeIf { it.isNotBlank() } ?: return defaultBaseUrl
        return if (custom.startsWith("http://") || custom.startsWith("https://")) custom
        else "https://$custom"
    }
}
