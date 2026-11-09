package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import android.content.res.Resources

/** Host adapter around Cloudstream's library BasePlugin for app-only plugin ABI compatibility. */
open class Plugin : BasePlugin() {
    var openSettings: ((Context) -> Unit)? = null
    var resources: Resources? = null

    override fun load() = Unit

    open fun load(activity: Activity?) { load() }

    open fun load(context: Context) { load(context as? Activity) }
}

object PluginManager {
    data class PluginData(val internalName: String, val url: String?, val isOnline: Boolean, val filePath: String, val version: Int)
    @JvmStatic fun getPluginsOnline(): Array<PluginData> = emptyArray()
    @JvmStatic fun unloadPlugin(filePath: String) = Unit
}
