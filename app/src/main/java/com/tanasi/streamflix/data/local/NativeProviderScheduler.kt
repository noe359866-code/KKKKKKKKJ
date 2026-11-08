package com.tanasi.streamflix.data.local

import androidx.annotation.Keep
import com.chaquo.python.PyObject

/** The provider callable and its arguments are Python objects, not JSON copies. */
@Keep
class NativeProviderScheduler {
    private val coordinator = NativeFlightCoordinator<PyObject?>()

    fun submit(key: String, callable: PyObject, provider: PyObject, payload: PyObject): NativeProviderTicket =
        NativeProviderTicket(coordinator.submit(key) { callable.call(provider, payload) })

    fun shutdown() = coordinator.shutdown()
}

@Keep
class NativeProviderTicket internal constructor(private val flight: Flight<PyObject?>) {
    fun awaitReady(timeoutMs: Long): Boolean = flight.awaitReady(timeoutMs)
    fun isDone(): Boolean = flight.isDone()
    fun isCancelled(): Boolean = flight.isCancelled()
    fun cancelIfQueued(): Boolean = flight.cancelIfQueued()
    fun result(): PyObject? = flight.result()
}
