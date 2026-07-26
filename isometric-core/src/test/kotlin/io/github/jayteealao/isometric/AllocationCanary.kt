package io.github.jayteealao.isometric

import kotlin.test.assertTrue

/**
 * Lower-bound canary shared by the allocation regression tests.
 *
 * [AllocationProbe] fails closed on three measurement failures but deliberately does not guard
 * a fourth: a JVM that reports allocation measurement as supported yet returns a stuck/constant
 * non-negative reading, so `after - before == 0` (see [AllocationProbe]'s KDoc). An
 * upper-bound-only assertion (`measured < threshold`) then passes vacuously, so every allocation
 * test must also assert a lower bound immediately before its upper-bound assertion.
 *
 * The floor cannot live on [AllocationProbe] itself — "how large a delta counts as real"
 * depends on what each caller's measured block allocates, not on the probe — so it stays
 * test-side policy. This helper only holds the shared diagnostic template; every value that
 * varies per fixture (the floor, the per-unit label, what allocated, and what a broken fixture
 * would look like) is supplied by the caller.
 *
 * @param measured the per-call/per-iteration byte reading under test.
 * @param floor the lower bound [measured] must clear; caller-supplied, sized well below the
 *   fixture's documented baseline and well above zero/noise.
 * @param unit the per-unit label used in the failure message, e.g. `"bytes/call"`.
 * @param subject short description of what allocated, e.g. `"Sort"`.
 * @param fixtureHint clause completing "...or $fixtureHint", describing what a broken or
 *   no-longer-exercised fixture would look like for this specific test.
 */
internal fun assertAllocationCanary(
    measured: Double,
    floor: Double,
    unit: String,
    subject: String,
    fixtureHint: String,
) {
    assertTrue(
        measured > floor,
        "$subject allocated only ${measured.toLong()} $unit — expected > ${floor.toLong()}. " +
            "A near-zero reading means thread-allocation measurement is not working " +
            "(stuck/constant reading) or $fixtureHint; the upper-bound assertion below would " +
            "then pass without measuring anything.",
    )
}
