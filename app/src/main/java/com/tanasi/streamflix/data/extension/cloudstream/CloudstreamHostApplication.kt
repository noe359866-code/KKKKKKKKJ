package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.gson.Gson
import com.lagradost.api.setContext
import java.lang.ref.WeakReference

/** Minimal host-owned API surface used by Cloudstream plugins. */
open class AcraApplication {
    companion object {
        @JvmStatic var context: Context? = null
            set(value) {
                field = value?.applicationContext
                CloudStreamApp.context = value?.applicationContext
                value?.let { setContext(WeakReference(it)) }
            }
        private var activityRef: WeakReference<Activity>? = null
        @JvmStatic fun getActivity(): Activity? = activityRef?.get()
        @JvmStatic fun setActivity(activity: Activity?) {
            activityRef = if (activity == null) null else WeakReference(activity)
            activity?.let { setContext(WeakReference(it)) }
        }
        @JvmStatic fun <T> getKey(path: String, key: String, default: T? = null): T? =
            CloudstreamPluginSettings.get("$path/$key", default)
        @JvmStatic fun <T> getKey(key: String, default: T? = null): T? =
            CloudstreamPluginSettings.get(key, default)
        @JvmStatic fun setKey(path: String, key: String, value: Any?) =
            CloudstreamPluginSettings.put("$path/$key", value)
        @JvmStatic fun setKey(key: String, value: Any?) = CloudstreamPluginSettings.put(key, value)
        @JvmStatic fun removeKeys(prefix: String) = CloudstreamPluginSettings.removePrefix(prefix)
        @JvmStatic fun removeKey(path: String, key: String) = CloudstreamPluginSettings.remove("$path/$key")
        @JvmStatic fun removeKey(key: String) = CloudstreamPluginSettings.remove(key)
    }
}

open class CloudStreamApp {
    companion object {
        @JvmStatic var cfUserAgent: String = com.lagradost.cloudstream3.USER_AGENT
        @JvmStatic var context: Context? = null
        @JvmStatic fun Context.getActivity(): Activity? {
            var current: Context? = this
            while (current != null) {
                if (current is Activity) return current
                current = (current as? ContextWrapper)?.baseContext
            }
            return null
        }
        @JvmStatic fun <T : Any> getKey(path: String, default: T? = null): T? =
            CloudstreamPluginSettings.get(path, default)
        @JvmStatic fun <T : Any> getKey(folder: String, path: String, default: T? = null): T? =
            CloudstreamPluginSettings.get("$folder/$path", default)
        @JvmStatic fun <T : Any> getKey(folder: String, path: String): T? =
            CloudstreamPluginSettings.get("$folder/$path", null)
        @JvmStatic fun <T : Any> getKey(path: String): T? = CloudstreamPluginSettings.get(path, null)
        @JvmStatic fun <T> setKey(path: String, value: T) = CloudstreamPluginSettings.put(path, value)
        @JvmStatic fun <T> setKey(folder: String, path: String, value: T) = CloudstreamPluginSettings.put("$folder/$path", value)
        @JvmStatic fun getKeys(folder: String): List<String>? = CloudstreamPluginSettings.keys(folder)
        @JvmStatic fun removeKeys(folder: String): Int? = CloudstreamPluginSettings.removePrefix(folder)
        @JvmStatic fun removeKey(folder: String, path: String) = CloudstreamPluginSettings.remove("$folder/$path")
        @JvmStatic fun removeKey(path: String) = CloudstreamPluginSettings.remove(path)
        @JvmStatic fun openBrowser(url: String, fallbackWebView: Boolean = false) = Unit
    }
}

/** Persistent namespaced preferences for extension settings; extension code is still untrusted. */
private object CloudstreamPluginSettings {
    private const val PREFS = "cloudstream_plugin_settings"
    private const val PREFIX = "plugin/"
    private val gson = Gson()

    private fun preferences(): SharedPreferences? =
        (AcraApplication.context ?: CloudStreamApp.context)?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, default: T?): T? {
        val prefs = preferences() ?: return default
        val stored = prefs.all["$PREFIX$key"] ?: return default
        val value: Any? = when (default) {
            is String -> stored as? String
            is Int -> stored as? Int
            is Long -> stored as? Long
            is Boolean -> stored as? Boolean
            is Float -> stored as? Float
            is Double -> (stored as? String)?.toDoubleOrNull()
            is Set<*> -> stored as? Set<String>
            null -> stored
            else -> (stored as? String)?.let { runCatching { gson.fromJson(it, default.javaClass) }.getOrNull() }
        }
        return (value ?: default) as? T
    }

    fun put(key: String, value: Any?) {
        val prefs = preferences() ?: return
        val editor = prefs.edit()
        val fullKey = "$PREFIX$key"
        when (value) {
            null -> editor.remove(fullKey)
            is String -> editor.putString(fullKey, value)
            is Int -> editor.putInt(fullKey, value)
            is Long -> editor.putLong(fullKey, value)
            is Boolean -> editor.putBoolean(fullKey, value)
            is Float -> editor.putFloat(fullKey, value)
            is Double -> editor.putString(fullKey, value.toString())
            is Set<*> -> editor.putStringSet(fullKey, value.filterIsInstance<String>().toMutableSet())
            else -> editor.putString(fullKey, gson.toJson(value))
        }
        editor.apply()
    }

    fun remove(key: String) { preferences()?.edit()?.remove("$PREFIX$key")?.apply() }

    fun removePrefix(prefix: String): Int {
        val prefs = preferences() ?: return 0
        val fullPrefix = "$PREFIX$prefix"
        val keys = prefs.all.keys.filter { it.startsWith(fullPrefix) }
        if (keys.isNotEmpty()) prefs.edit().also { editor -> keys.forEach { editor.remove(it) } }.apply()
        return keys.size
    }

    fun keys(folder: String): List<String>? {
        val fullPrefix = "$PREFIX$folder/"
        return preferences()?.all?.keys?.filter { it.startsWith(fullPrefix) }?.map { it.removePrefix(fullPrefix) }
    }
}
