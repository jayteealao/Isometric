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
 * The public surface is a single primitive: [measureBytes] returns the fail-closed total
 * bytes allocated by a block. The raw bean plumbing is private — there is no way to
 * measure allocation on the current thread without going through [measureBytes].
 */
object AllocationProbe {

    /**
     * Acquires the platform [com.sun.management.ThreadMXBean] with allocation measurement
     * supported and enabled, or throws [AssertionError] (failing closed).
     */
    private fun requireAllocatingBean(): com.sun.management.ThreadMXBean {
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
     * Allocated-byte reading for the current thread with the fail-closed non-negative
     * guard: a negative reading means the JVM could not measure, so the test must fail
     * rather than silently pass.
     */
    private fun com.sun.management.ThreadMXBean.allocatedBytes(): Long {
        val bytes = getThreadAllocatedBytes(Thread.currentThread().id)
        if (bytes < 0) {
            throw AssertionError(
                "Thread allocation measurement returned a negative reading even after " +
                    "enabling ($bytes). The allocation threshold cannot be enforced — " +
                    "failing closed.",
            )
        }
        return bytes
    }

    /**
     * Fail-closed total bytes allocated by [block] on the current thread.
     */
    fun measureBytes(block: () -> Unit): Long {
        val sunBean = requireAllocatingBean()
        val beforeBytes = sunBean.allocatedBytes()
        block()
        val afterBytes = sunBean.allocatedBytes()
        return afterBytes - beforeBytes
    }
}
