package com.tanasi.streamflix.data.local

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Python se inicia una vez, en segundo plano. Los scripts se importan y se llama
 * a scrape(payload) directamente: no hay proceso Node, shell ni servidor HTTP.
 *
 * Pool y cola acotados para no crear hilos sin límite si se cambia rápidamente
 * de episodio. Cancelar una corutina descarta el trabajo en cola y su resultado;
 * una llamada Python ya iniciada termina con los deadlines de los scrapers.
 * No intentamos matar el intérprete compartido ni interrumpir JNI a la fuerza.
 */
class ChaquopyRuntime(context: Context) : EmbeddedRuntime {
    private val app = context.applicationContext
    // Una búsqueda lenta de portada no debe poner la reproducción detrás de sus filas.
    private val catalogWorkers = pool(4, 32, "streamflix-catalog")
    private val playbackWorkers = pool(2, 16, "streamflix-playback")

    private fun pool(size: Int, capacity: Int, name: String) = ThreadPoolExecutor(
        size, size, 30, TimeUnit.SECONDS, ArrayBlockingQueue(capacity),
        { task -> Thread(task, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val module: PyObject by lazy {
        synchronized(PYTHON_START_LOCK) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        }
        Python.getInstance().getModule("embedded_engine").also { engine ->
            val cache = File(app.cacheDir, "embedded-engine").apply { mkdirs() }
            engine.callAttr(
                "configure", cache.absolutePath,
                app.assets.open("providers.json").bufferedReader().use { it.readText() },
                app.assets.open("live-catalog.json").bufferedReader().use { it.readText() },
                ScraperHttpTransport(),
            )
        }
    }

    override suspend fun call(operation: String, payload: String): String = suspendCancellableCoroutine { continuation ->
        val workers = if (operation == "catalog") catalogWorkers else playbackWorkers
        try {
            val task = workers.submit {
                if (continuation.isActive) {
                    try {
                        val response = module.callAttr("dispatch", operation, payload).toString()
                        continuation.resume(response)
                    } catch (error: Exception) {
                        // El detalle Python queda fuera del mensaje de UI (puede contener URLs firmadas).
                        continuation.resumeWithException(IOException("El motor local no pudo completar la consulta", error))
                    }
                }
            }
            continuation.invokeOnCancellation {
                task.cancel(false)
                workers.purge()
            }
        } catch (error: Exception) {
            continuation.resumeWithException(IOException("El motor local está ocupado. Vuelve a intentarlo.", error))
        }
    }

    private companion object {
        val PYTHON_START_LOCK = Any()
    }
}
