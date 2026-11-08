package com.tanasi.streamflix.data.local

import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixed pool with keyed single-flight and cancellation only before a job starts. */
internal class NativeFlightCoordinator<T>(workers: Int = 3) {
    private val lock = Any()
    private val inflight = mutableMapOf<String, Flight<T>>()
    private val pool = Executors.newFixedThreadPool(workers) { task ->
        Thread(task, "embedded-provider").apply { isDaemon = true }
    }

    fun submit(key: String, work: () -> T): Flight<T> = synchronized(lock) {
        inflight[key]?.takeUnless { it.isDone() }?.let { return it }
        lateinit var flight: Flight<T>
        flight = Flight {
            synchronized(lock) { if (inflight[key] === flight) inflight.remove(key) }
        }
        inflight[key] = flight
        try {
            pool.execute { flight.run(work) }
        } catch (e: RuntimeException) {
            inflight.remove(key)
            throw e
        }
        flight
    }

    fun shutdown() {
        // Do not interrupt running Python calls, matching ThreadPoolExecutor's
        // shutdown(wait=True, cancel_futures=True) in the desktop engine.
        synchronized(lock) { inflight.values.toList().forEach { it.cancelIfQueued() } }
        pool.shutdown()
        while (!pool.awaitTermination(1, TimeUnit.DAYS)) { /* wait for running providers */ }
    }
}

internal class Flight<T>(private val finished: () -> Unit) {
    private val monitor = java.lang.Object()
    private var started = false
    private var done = false
    private var cancelled = false
    private var value: T? = null
    private var error: Throwable? = null

    fun run(work: () -> T) {
        synchronized(monitor) {
            if (done) return
            started = true
        }
        try {
            val result = work()
            synchronized(monitor) { value = result }
        } catch (failure: Throwable) {
            synchronized(monitor) { error = failure }
        } finally {
            synchronized(monitor) { done = true; monitor.notifyAll() }
            finished()
        }
    }

    fun awaitReady(timeoutMs: Long): Boolean = synchronized(monitor) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        while (!done) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
        }
        done
    }

    fun isDone(): Boolean = synchronized(monitor) { done }
    fun isCancelled(): Boolean = synchronized(monitor) { cancelled }

    fun cancelIfQueued(): Boolean {
        synchronized(monitor) {
            if (started || done) return false
            cancelled = true
            done = true
            monitor.notifyAll()
        }
        finished()
        return true
    }

    @Suppress("UNCHECKED_CAST")
    fun result(): T = synchronized(monitor) {
        check(done) { "Provider result is not ready" }
        if (cancelled) throw CancellationException()
        error?.let { throw IllegalStateException("Provider failed", it) }
        value as T
    }
}
