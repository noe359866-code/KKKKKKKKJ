package com.tanasi.streamflix.data.local

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class NativeFlightCoordinatorTest {
    @Test fun `identical keys share work while running and can be submitted again after completion`() {
        val pool = NativeFlightCoordinator<String>(2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val first = pool.submit("same") { calls.incrementAndGet(); started.countDown(); release.await(); "first" }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val duplicate = pool.submit("same") { calls.incrementAndGet(); "duplicate" }
            assertSame(first, duplicate)
            assertFalse(first.awaitReady(5))
            assertFalse(first.cancelIfQueued())
            release.countDown()
            assertTrue(first.awaitReady(2000))
            assertEquals("first", first.result())
            val next = pool.submit("same") { calls.incrementAndGet(); "next" }
            assertTrue(next.awaitReady(2000))
            assertEquals("next", next.result())
            assertEquals(2, calls.get())
        } finally { release.countDown(); pool.shutdown() }
    }

    @Test fun `concurrent callers for the same key share one ticket`() {
        val pool = NativeFlightCoordinator<Int>(3)
        val callers = java.util.concurrent.Executors.newFixedThreadPool(12)
        val gate = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val requests = (1..12).map {
                callers.submit<Flight<Int>> {
                    gate.await()
                    pool.submit("shared") { calls.incrementAndGet(); release.await(); 7 }
                }
            }
            gate.countDown()
            val tickets = requests.map { it.get(2, TimeUnit.SECONDS) }
            assertTrue(tickets.all { it === tickets.first() })
            release.countDown()
            assertTrue(tickets.first().awaitReady(2000))
            assertEquals(7, tickets.first().result())
            assertEquals(1, calls.get())
        } finally { release.countDown(); callers.shutdownNow(); pool.shutdown() }
    }

    @Test fun `queued work can be cancelled without interrupting active work`() {
        val pool = NativeFlightCoordinator<Int>(1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val running = pool.submit("running") { started.countDown(); release.await(); 1 }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val queued = pool.submit("queued") { calls.incrementAndGet(); 2 }
            assertTrue(queued.cancelIfQueued())
            assertTrue(queued.awaitReady(0))
            assertTrue(queued.isCancelled())
            val replacement = pool.submit("queued") { calls.incrementAndGet(); 3 }
            release.countDown()
            assertTrue(running.awaitReady(2000))
            assertTrue(replacement.awaitReady(2000))
            assertEquals(3, replacement.result())
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdown() }
    }

    @Test fun `provider exceptions finish their flight and do not poison later work`() {
        val pool = NativeFlightCoordinator<Int>(1)
        try {
            val failed = pool.submit("x") { throw IllegalArgumentException("fixture") }
            assertTrue(failed.awaitReady(2000))
            try { failed.result(); fail("expected failure") } catch (_: IllegalStateException) { }
            val next = pool.submit("x") { 4 }
            assertTrue(next.awaitReady(2000))
            assertEquals(4, next.result())
        } finally { pool.shutdown() }
    }
}
