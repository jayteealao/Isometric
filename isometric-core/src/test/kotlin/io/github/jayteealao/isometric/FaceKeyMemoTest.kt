@file:OptIn(ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.Prism
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Correctness and regression tests for the per-[Path] faceKey memo.
 *
 * Each [Path] computes its [Path.faceKey] and [Path.faceKeyBumped] lazily and exactly
 * once per instance (via `by lazy`).  Because the key is a pure world-space function of
 * the Path's points, the memo lives for the Path's own GC lifetime — there is no
 * engine-side [IdentityHashMap] holding strong references to every Path until `clear()`.
 *
 * These tests cover:
 *
 * 1. A single Path instance computes each key at most once — repeated access is flat
 *    (near-zero allocation) versus the first-access build cost.
 * 2. Coincident interior faces are still culled correctly (grouping/dedup unchanged).
 * 3. The engine holds no external identity map of Paths (the retention leak is gone),
 *    and churning many distinct Paths does not accumulate engine-side state.
 */
class FaceKeyMemoTest {

    private fun quad(i: Int): Path {
        // Distinct geometry per i so no two Paths share a faceKey — each must build its own.
        val d = i.toDouble()
        return Path(
            Point(d, 0.0, 0.0),
            Point(d + 1.0, 0.0, 0.0),
            Point(d + 1.0, 1.0, 0.0),
            Point(d, 1.0, 0.0),
        )
    }

    // ---------------------------------------------------------------------------
    // Test 1 — faceKey is computed at most once per Path instance
    // ---------------------------------------------------------------------------

    @Test
    fun `faceKey computed at most once per Path instance`() {
        val n = 24 // same face count as a 2x2 Prism grid

        // Allocation measurement — fail closed via the shared probe: the threshold must
        // be enforced, never silently skipped, even on a JVM that cannot measure. The
        // two-phase (first-build vs memoized) capture uses two AllocationProbe.measureBytes
        // calls, one per phase.
        var sink = 0

        // Warm-up: let the JIT compile the lazy-build and access paths using throwaway
        // Paths so the measured batch below is not polluted by first-compile allocation.
        repeat(5) {
            for (j in 0 until n) {
                val p = quad(1000 + it * n + j)
                sink += p.faceKey.hashCode()
                sink += p.faceKeyBumped.hashCode()
                sink += p.faceKey.hashCode() // second access — memoized
            }
        }

        // Fresh Paths whose keys have NOT been accessed yet.
        val paths = (0 until n).map { quad(it) }

        // First access — each Path builds its two keys exactly once (unmemoized cost).
        val firstBuildBytes = AllocationProbe.measureBytes {
            for (p in paths) {
                sink += p.faceKey.hashCode()
                sink += p.faceKeyBumped.hashCode()
            }
        }

        // Second access — every key resolves from the per-Path memo (should be ~0 bytes).
        val memoizedBytes = AllocationProbe.measureBytes {
            for (p in paths) {
                sink += p.faceKey.hashCode()
                sink += p.faceKeyBumped.hashCode()
            }
        }

        // Keep the JIT from dead-code-eliminating the key reads.
        assertTrue(sink != Int.MIN_VALUE, "sink guard")

        // Threshold rationale (Finding #4 — sit between memoized max and unmemoized min):
        //   First access builds n×2 FaceKeys (ArrayList + 4 QuantizedPoint + sorted-copy +
        //   wrapper per key); measured ≈ 33,000 B for the 24-Path batch on JDK 17 HotSpot.
        //   Second access is pure memo lookups; measured ≈ 1,200 B (loop-iterator noise).
        //   Threshold 4,000 B sits between the two — far below the ~33,000 B build cost and
        //   comfortably above the memoized floor — so it fails loudly if the memo ever
        //   stops caching without tripping on measurement noise.
        val thresholdBytes = 4_000.0

        println(
            "FaceKeyMemoTest: N=$n Paths — first-access build=${firstBuildBytes}B  " +
                "second-access(memoized)=${memoizedBytes}B  threshold=${thresholdBytes.toLong()}B",
        )

        assertTrue(
            firstBuildBytes > thresholdBytes,
            "First access should build the keys and allocate more than " +
                "${thresholdBytes.toLong()}B for $n Paths; measured ${firstBuildBytes}B. " +
                "If this is ~0 the fixture is not exercising key construction.",
        )
        assertTrue(
            memoizedBytes.toDouble() < thresholdBytes,
            "Repeated faceKey/faceKeyBumped access on the same Path instances allocated " +
                "${memoizedBytes}B — expected < ${thresholdBytes.toLong()}B. The `by lazy` " +
                "memo must return the cached key without rebuilding it.",
        )
    }

    // ---------------------------------------------------------------------------
    // Test 2 — Culling correctness / within-frame dedup is unchanged
    // ---------------------------------------------------------------------------

    @Test
    fun `coincident interior faces still culled correctly with per-Path memo`() {
        val engine = IsometricEngine()
        // Two unit Prisms side by side along the x-axis: their shared vertical wall
        // (the RIGHT face of Prism at x=0 coincides with the LEFT face of Prism at x=1)
        // must be culled by the shared-interior-face pass. The pass reads path.faceKey /
        // path.faceKeyBumped, so this verifies grouping is unchanged by the memo move.
        engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.BLUE)
        engine.add(Prism(Point(1.0, 0.0, 0.0)), IsoColor.RED)

        // Cold call — each Path builds its key on first access here.
        val coldScene = engine.projectScene(800, 600, RenderOptions.Default)
        // Warm call — every FaceKey lookup comes from the per-Path memo.
        val warmScene = engine.projectScene(800, 600, RenderOptions.Default)

        assertEquals(
            coldScene.commands.size,
            warmScene.commands.size,
            "Warm projectScene must produce the same number of rendered faces as the cold " +
                "call — the per-Path memo must not alter grouping or culling decisions.",
        )
        // The shared interior wall pair is culled, so fewer than the 12 total faces
        // (2 Prisms × 6) survive; assert culling actually happened.
        assertTrue(
            coldScene.commands.size < 12,
            "Expected the shared interior wall to be culled (fewer than 12 faces), " +
                "got ${coldScene.commands.size}.",
        )
    }

    // ---------------------------------------------------------------------------
    // Test 3 — No engine-side identity map retains Paths (leak fixed)
    // ---------------------------------------------------------------------------

    @Test
    fun `engine retains no identity map of Paths`() {
        // Direct proof the retention vector is gone: the engine declares no
        // IdentityHashMap (or any Map) field that could accumulate Path references.
        val retainingMapField = IsometricEngine::class.java.declaredFields.firstOrNull { field ->
            IdentityHashMap::class.java.isAssignableFrom(field.type) ||
                Map::class.java.isAssignableFrom(field.type)
        }
        assertFalse(
            retainingMapField != null,
            "IsometricEngine must not hold a Map/IdentityHashMap field caching Path keys — " +
                "found '${retainingMapField?.name}'. Face keys now live on the Path itself.",
        )

        // Churn scenario: many distinct Paths across repeated add/clear cycles. With the
        // memo on the Path, nothing is retained engine-side once the scene is cleared.
        val engine = IsometricEngine()
        repeat(50) { frame ->
            engine.clear()
            for (i in 0 until 8) {
                engine.add(Prism(Point(i.toDouble(), frame.toDouble(), 0.0)), IsoColor.BLUE)
            }
            engine.projectScene(800, 600, RenderOptions.Default)
        }
        // No assertion needed beyond completion + the reflection check above: if an
        // engine-side map existed it would be growing here; there is none to grow.
        engine.clear()
    }
}
