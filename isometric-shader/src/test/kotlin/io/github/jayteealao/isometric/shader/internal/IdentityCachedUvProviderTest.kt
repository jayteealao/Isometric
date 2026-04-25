package io.github.jayteealao.isometric.shader.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [IdentityCachedUvProvider].
 *
 * Verifies cache-hit semantics, cache-miss semantics, thread-safety under concurrent
 * reads, and [IdentityCachedUvProvider.clear] reset behaviour (AC5).
 */
class IdentityCachedUvProviderTest {

    // ── cache hit ──────────────────────────────────────────────────────────────

    /**
     * When the same key instance is passed twice, the builder must be invoked only
     * once and the identical [FloatArray] reference must be returned on the second call.
     */
    @Test fun `cache hit - same key returns same array without re-invoking builder`() {
        val provider = IdentityCachedUvProvider<String>()
        val callCount = AtomicInteger(0)

        val key = "key-instance"
        val builder: (String) -> FloatArray = { _ ->
            callCount.incrementAndGet()
            floatArrayOf(1f, 2f, 3f, 4f)
        }

        val first = provider.compute(key, builder)
        val second = provider.compute(key, builder)

        assertSame("Same key (===) must return the cached array reference", first, second)
        assert(callCount.get() == 1) {
            "Builder must be invoked exactly once on cache hit, but was invoked ${callCount.get()} time(s)"
        }
    }

    // ── cache miss ────────────────────────────────────────────────────────────

    /**
     * When a *different* key instance is passed, the builder must be re-invoked and a
     * new [FloatArray] returned (identity miss, even if the keys are equal by value).
     */
    @Test fun `cache miss - different key instance re-invokes builder`() {
        val provider = IdentityCachedUvProvider<String>()
        val callCount = AtomicInteger(0)

        // Two distinct String objects that are .equals() but not ===
        val key1 = StringBuilder("key").toString()
        val key2 = StringBuilder("key").toString()

        // Sanity: the two keys are equal by value but distinct instances
        assert(key1 == key2) { "Precondition: keys must be value-equal" }
        assert(key1 !== key2) { "Precondition: keys must be distinct instances" }

        val builder: (String) -> FloatArray = { _ ->
            callCount.incrementAndGet()
            floatArrayOf(callCount.get().toFloat())
        }

        val first = provider.compute(key1, builder)
        val second = provider.compute(key2, builder)

        assertNotSame("Different key instances must produce a new array reference", first, second)
        assert(callCount.get() == 2) {
            "Builder must be invoked twice for two distinct key instances, but was invoked ${callCount.get()} time(s)"
        }
    }

    // ── concurrent reads ──────────────────────────────────────────────────────

    /**
     * Under 8 concurrent threads each calling [IdentityCachedUvProvider.compute] 100
     * times with the same key, every returned array must contain the value produced by
     * the builder — no torn reads.
     *
     * Acceptable outcomes per call:
     * - The canonical array produced by the first successful builder invocation, OR
     * - A freshly-built array (race condition on first publish) with the same *value*.
     *
     * The builder always returns `floatArrayOf(42f)`, so any returned array with a
     * different value is a torn-read defect.
     */
    @Test fun `concurrent reads - no torn reads under 8-thread load`() {
        val provider = IdentityCachedUvProvider<String>()
        val key = "shared-key"
        val expectedValue = 42f
        val builder: (String) -> FloatArray = { _ -> floatArrayOf(expectedValue) }

        val threadCount = 8
        val callsPerThread = 100
        val latch = CountDownLatch(1)
        val results = Array(threadCount) { Array(callsPerThread) { floatArrayOf() } }
        val errors = mutableListOf<AssertionError>()
        val errorLock = Any()

        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            for (t in 0 until threadCount) {
                executor.submit {
                    latch.await() // all threads start simultaneously
                    for (i in 0 until callsPerThread) {
                        results[t][i] = provider.compute(key, builder)
                    }
                }
            }
            latch.countDown() // release all threads
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
                "Thread pool did not terminate in time — possible deadlock"
            }
        } finally {
            executor.shutdownNow()
        }

        // Every returned array must contain exactly the expected value.
        for (t in 0 until threadCount) {
            for (i in 0 until callsPerThread) {
                val arr = results[t][i]
                assertNotNull("Thread $t call $i returned null", arr)
                assert(arr.size == 1) {
                    "Thread $t call $i returned array of unexpected size ${arr.size}"
                }
                assert(arr[0] == expectedValue) {
                    "Thread $t call $i returned torn value ${arr[0]}, expected $expectedValue"
                }
            }
        }
    }

    // ── clear() resets ────────────────────────────────────────────────────────

    /**
     * After [IdentityCachedUvProvider.clear], the next [IdentityCachedUvProvider.compute]
     * call with the same key must invoke the builder again rather than serving the old
     * cached value.
     */
    @Test fun `clear resets cache and forces builder re-invocation`() {
        val provider = IdentityCachedUvProvider<String>()
        val callCount = AtomicInteger(0)

        val key = "key-for-clear-test"
        val builder: (String) -> FloatArray = { _ ->
            floatArrayOf(callCount.incrementAndGet().toFloat())
        }

        // Warm the cache
        val first = provider.compute(key, builder)
        assert(callCount.get() == 1) { "Builder must be invoked once before clear()" }

        // Clear and re-request
        provider.clear()
        val second = provider.compute(key, builder)

        assert(callCount.get() == 2) {
            "Builder must be invoked again after clear(), but call count is ${callCount.get()}"
        }
        assertNotSame("Array after clear() must be a new instance", first, second)
        assertArrayEquals(
            "Both arrays must have the same length",
            floatArrayOf(1f),
            floatArrayOf(first[0]),
            0f,
        )
        assertArrayEquals(
            "Post-clear array must reflect re-invoked builder value",
            floatArrayOf(2f),
            floatArrayOf(second[0]),
            0f,
        )
    }
}
