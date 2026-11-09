package com.tanasi.streamflix.data.extension.cloudstream

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import com.tanasi.streamflix.data.model.ExtensionRepositoryKind
import com.tanasi.streamflix.data.remote.InstalledExtensionRecord
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Loads only user-enabled, integrity-checked Cloudstream packages. DEX code runs in-process. */
class CloudstreamExtensionLoader(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.codeCacheDir, "streamflix_cloudstream_dex").apply { check(isDirectory || mkdirs()) }
    private val packageRoot = File(appContext.filesDir, "extension_packages/cloudstream").canonicalFile.path.trimEnd(File.separatorChar) + File.separator
    private val loaded = ConcurrentHashMap<String, List<MainAPI>>()

    fun retainEnabledPackages(records: List<InstalledExtensionRecord>) = synchronized(loaded) {
        val enabledPaths = records.asSequence().filter { it.enabled }.mapNotNull { record ->
            runCatching { File(record.filePath).canonicalPath }.getOrNull()
        }.toSet()
        val sourceRoot = packageRoot.trimEnd(File.separatorChar) + File.separator
        val staleApis = synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.filter { api ->
                val source = api.sourcePlugin
                source != null && source.startsWith(sourceRoot) && source !in enabledPaths
            }
        }
        staleApis.forEach { APIHolder.removePluginMapping(it) }
        synchronized(APIHolder.allProviders) { APIHolder.allProviders.removeAll(staleApis.toSet()) }
        extractorApis.removeAll { extractor ->
            val source = extractor.sourcePlugin
            source != null && source.startsWith(sourceRoot) && source !in enabledPaths
        }
        loaded.keys.removeAll { key ->
            val source = key.substringBeforeLast(':', key)
            source.startsWith(sourceRoot) && source !in enabledPaths
        }
    }

    suspend fun load(record: InstalledExtensionRecord): List<MainAPI> = withContext(Dispatchers.IO) {
        require(record.enabled) { "La extensión Cloudstream no está activada." }
        require(record.kind == ExtensionRepositoryKind.CLOUDSTREAM) { "El paquete no es una extensión Cloudstream." }
        val packageFile = File(record.filePath).canonicalFile
        require(packageFile.path.startsWith(packageRoot)) { "El .cs3 no está en el almacenamiento privado de extensiones." }
        require(packageFile.isFile && packageFile.length() in 1..MAX_PACKAGE_BYTES) { "El archivo .cs3 no está disponible o supera el límite." }
        require(packageFile.extension.equals("cs3", ignoreCase = true)) { "El archivo no tiene extensión .cs3." }
        require(sha256(packageFile) == record.sha256.lowercase()) { "El SHA-256 del .cs3 cambió desde la instalación." }
        val cacheKey = "${packageFile.path}:${packageFile.lastModified()}:${record.sha256}"
        loaded[cacheKey]?.let { return@withContext it }
        synchronized(loaded) {
            val staleKeys = loaded.keys.filter { it.startsWith("${packageFile.path}:") && it != cacheKey }
            if (staleKeys.isNotEmpty()) {
                unregisterPackageApis(packageFile.path)
                staleKeys.forEach(loaded::remove)
            }
            loaded[cacheKey] ?: loadUncached(packageFile, cacheKey)
        }
    }

    private fun unregisterPackageApis(path: String) {
        val apis = synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.filter { it.sourcePlugin == path }
        }
        apis.forEach { APIHolder.removePluginMapping(it) }
        synchronized(APIHolder.allProviders) { APIHolder.allProviders.removeAll(apis.toSet()) }
        extractorApis.removeAll { it.sourcePlugin == path }
    }

    private fun loadUncached(packageFile: File, cacheKey: String): List<MainAPI> {
        AcraApplication.context = appContext
        CloudStreamApp.context = appContext
        val before = synchronized(APIHolder.allProviders) { APIHolder.allProviders.toSet() }
        val dexLoader = DexClassLoader(packageFile.absolutePath, cacheDir.absolutePath, null, appContext.classLoader)
        val pluginClassName = readPluginClassName(packageFile)
        val candidateNames = buildList {
            pluginClassName?.let(::add)
            addAll(readDexClassNames(packageFile).filter {
                '.' in it && '$' !in it && !it.startsWith("android.") && !it.startsWith("java.") && !it.startsWith("kotlin.")
            })
        }.distinct()

        for (className in candidateNames) {
            val clazz = try { dexLoader.loadClass(className) } catch (_: Throwable) { continue }
            if (!isPluginEntrypoint(clazz)) continue
            try {
                val instance = clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                when (instance) {
                    is Plugin -> {
                        instance.filename = packageFile.absolutePath
                        instance.load(AcraApplication.getActivity() ?: appContext)
                    }
                    is BasePlugin -> {
                        runCatching { clazz.getField("filename").set(instance, packageFile.absolutePath) }
                        val load = clazz.methods.firstOrNull { it.name == "load" && it.parameterCount == 0 }
                        load?.invoke(instance)
                    }
                    else -> {
                        val contextLoad = clazz.methods.firstOrNull {
                            it.name == "load" && it.parameterTypes.contentEquals(arrayOf(Context::class.java))
                        }
                        val activityLoad = clazz.methods.firstOrNull {
                            it.name == "load" && it.parameterTypes.contentEquals(arrayOf(android.app.Activity::class.java))
                        }
                        val noArgLoad = clazz.methods.firstOrNull { it.name == "load" && it.parameterCount == 0 }
                        when {
                            contextLoad != null -> contextLoad.invoke(instance, AcraApplication.getActivity() ?: appContext)
                            activityLoad != null -> activityLoad.invoke(instance, AcraApplication.getActivity())
                            noArgLoad != null -> noArgLoad.invoke(instance)
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Cloudstream entrypoint $className failed to initialize", error)
            }
        }

        var apis = synchronized(APIHolder.allProviders) { APIHolder.allProviders.filter { it !in before } }
        // Some older plugins package a provider directly or fail to register during load();
        // scan for concrete MainAPI classes if the entrypoint registered nothing.
        if (apis.isEmpty()) {
            for (className in candidateNames) {
                val clazz = try { dexLoader.loadClass(className) } catch (_: Throwable) { continue }
                if (!MainAPI::class.java.isAssignableFrom(clazz) || clazz.isInterface || java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) continue
                try {
                    val api = clazz.getDeclaredConstructor().newInstance() as MainAPI
                    api.sourcePlugin = packageFile.absolutePath
                    synchronized(APIHolder.allProviders) { APIHolder.allProviders.add(api) }
                    APIHolder.addPluginMapping(api)
                } catch (error: Throwable) {
                    Log.w(TAG, "Skipping Cloudstream provider class $className", error)
                }
            }
            apis = synchronized(APIHolder.allProviders) { APIHolder.allProviders.filter { it !in before } }
        }
        apis.forEach { api -> runCatching { api.init() } }
        if (apis.isEmpty()) Log.w(TAG, "No MainAPI was registered by ${packageFile.name}")
        loaded[cacheKey] = apis
        return apis
    }

    private fun isPluginEntrypoint(clazz: Class<*>): Boolean {
        if (Plugin::class.java.isAssignableFrom(clazz) || BasePlugin::class.java.isAssignableFrom(clazz)) return true
        return try { clazz.getAnnotation(CloudstreamPlugin::class.java) != null } catch (_: Throwable) { false }
    }

    private fun readPluginClassName(file: File): String? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return null
            JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                .optString("pluginClassName")
                .takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun readDexClassNames(file: File): List<String> = try {
        @Suppress("DEPRECATION")
        val dex = DexFile(file)
        try { dex.entries().asSequence().toList() } finally { dex.close() }
    } catch (error: Throwable) {
        Log.e(TAG, "Unable to enumerate classes in ${file.name}", error)
        emptyList()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "CloudstreamLoader"
        private const val MAX_PACKAGE_BYTES = 10L * 1024 * 1024
    }
}
