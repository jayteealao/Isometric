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
 *
 * A fourth failure mode exists that this probe deliberately does **not** guard: a JVM that
 * reports allocation measurement as supported yet returns a stuck/constant non-negative
 * reading, so `after - before` is `0`. The negative-reading guard above does not catch this —
 * a stuck-at-zero delta is non-negative and passes it vacuously. The check cannot live here
 * either, because "how large a delta counts as real" depends on what each caller's block
 * allocates, not on the probe. Every caller must therefore assert its own lower bound
 * (`measured > floor`, sized well below the block's documented baseline and well above
 * zero/noise) alongside any upper-bound threshold — an upper-bound-only assertion
 * (`measured < threshold`) passes vacuously when `measured` is `0`. See
 * `DepthSorterAllocationTest`, `PathAllocationTest`, `NoSortEdgeEquationAllocationTest`, and
 * `FaceKeyMemoTest` for the pattern.
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
     *
     * This only guards the negative-reading failure mode; it performs no check for a
     * stuck/constant reading (`after - before == 0`) — see the object KDoc above. Callers must
     * assert their own lower bound (`measured > floor`) below the block's documented baseline
     * to catch that case; an upper-bound-only assertion passes vacuously when `measured` is `0`.
     */
    fun measureBytes(block: () -> Unit): Long {
        val sunBean = requireAllocatingBean()
        val beforeBytes = sunBean.allocatedBytes()
        block()
        val afterBytes = sunBean.allocatedBytes()
        return afterBytes - beforeBytes
    }
}
