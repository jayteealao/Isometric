package io.github.jayteealao.isometric

import java.lang.management.ManagementFactory
import kotlin.test.assertTrue

/**
 * Shared fail-closed access to per-thread allocation measurement for the allocation
 * regression tests.
 *
 * Every allocation test measures with
 * [com.sun.management.ThreadMXBean.getThreadAllocatedBytes] (JDK 7+ HotSpot / OpenJDK).
 * The contract is *fail closed*: when the API is unavailable, unsupported, or returns a
 * negative reading, the probe throws [AssertionError] instead of letting the test degrade
 * to a silent pass — a no-op guard would let an allocation regression slip through CI
 * unnoticed. This object is the single source of truth for that guard; tests must not
 * hand-roll their own bean acquisition.
 *
 * Two surfaces are exposed:
 * - [measurePerCallBytes] — per-call convenience for the common warm-up/measure loop.
 * - [requireAllocatingBean] + [allocatedBytes] — raw before/after capture for tests with
 *   bespoke measurement phases (e.g. FaceKeyMemoTest's first-build vs memoized split).
 */
object AllocationProbe {

    /**
     * Acquires the platform [com.sun.management.ThreadMXBean] with allocation measurement
     * supported and enabled, or throws [AssertionError] (failing closed).
     */
    fun requireAllocatingBean(): com.sun.management.ThreadMXBean {
        val threadMxBean = ManagementFactory.getThreadMXBean()
        val sunBean = threadMxBean as? com.sun.management.ThreadMXBean
            ?: throw AssertionError(
                "Thread allocation measurement unavailable: ThreadMXBean is not a " +
                    "com.sun.management.ThreadMXBean on this JVM (${threadMxBean.javaClass.name}). " +
                    "The allocation threshold cannot be enforced — failing closed.",
            )
        assertTrue(
            sunBean.isThreadAllocatedMemorySupported,
            "Thread allocation measurement unsupported: " +
                "com.sun.management.ThreadMXBean.isThreadAllocatedMemorySupported is false. " +
                "The allocation threshold cannot be enforced — failing closed.",
        )
        if (!sunBean.isThreadAllocatedMemoryEnabled) {
            sunBean.isThreadAllocatedMemoryEnabled = true
        }
        return sunBean
    }

    /**
     * Runs [block] [warmup] times (JIT warm-up), then [iterations] times between two
     * fail-closed allocation captures on the current thread, and returns the mean
     * allocated bytes per call.
     */
    inline fun measurePerCallBytes(warmup: Int, iterations: Int, block: () -> Unit): Double {
        repeat(warmup) { block() }
        val sunBean = requireAllocatingBean()
        val beforeBytes = sunBean.allocatedBytes()
        repeat(iterations) { block() }
        val afterBytes = sunBean.allocatedBytes()
        return (afterBytes - beforeBytes).toDouble() / iterations
    }
}

/**
 * Allocated-byte reading for the current thread with the fail-closed non-negative guard:
 * a negative reading means the JVM could not measure, so the test must fail rather than
 * silently pass.
 */
fun com.sun.management.ThreadMXBean.allocatedBytes(): Long {
    val bytes = getThreadAllocatedBytes(Thread.currentThread().id)
    if (bytes < 0) {
        throw AssertionError(
            "Thread allocation measurement returned a negative reading even after enabling " +
                "($bytes). The allocation threshold cannot be enforced — failing closed.",
        )
    }
    return bytes
}
