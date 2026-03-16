# Phase 3 Spatial Index Plan Review

**Date:** 2026-03-11
**Plan reviewed:** `docs/plans/2026-03-11-phase3-spatial-index.md`
**Scope:** Review of the Phase 3 spatial-index plan against the current renderer implementation and the
actual Phase 2 harness/runner behavior that shipped on `perf/benchmark-harness`.

## Overall Assessment

The plan direction is good. It is correctly centered on trustworthiness of the fast path before
benchmarking. The main thing that needed correction was the baseline reference point: several concerns
must be framed against the Phase 2 runner that actually exists, not against earlier Phase 2 planning
ideas that were never implemented.

6 findings: 2 high, 3 medium, 1 low.

---

## Findings

### High Severity

#### 1. Neighboring-cell query requirements must be explicit and parameterized

**Plan references:**
- `docs/plans/2026-03-11-phase3-spatial-index.md` section 6.1
- `docs/plans/2026-03-11-phase3-spatial-index.md` Step 2

**Code references:**
- `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt`

**Issue:** The renderer hit test uses a radius-based query, not a point query. The plan already calls
out cell-boundary misses, but the fix requirements should be stated in exact implementation terms:

1. neighboring-cell expansion must scale from `ceil(hitRadius / cellSize)`, not a hardcoded 1-cell ring
2. candidate dedup must preserve scene order
3. tests must prove order parity against the slow path for overlapping candidates

Without that precision, the branch could "fix" false negatives while still changing which overlapping
item wins.

**Recommendation:** State the expansion rule, require order-preserving dedup, and require an explicit
ordering regression test.

---

#### 2. Index-build overhead is guaranteed in this branch and should be described as such

**Plan references:**
- `docs/plans/2026-03-11-phase3-spatial-index.md` section 4
- `docs/plans/2026-03-11-phase3-spatial-index.md` section 5.2

**Code references:**
- `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt`

**Issue:** The current renderer builds the spatial index inside the cache rebuild path. With
`enablePreparedSceneCache=false`, that means the branch is guaranteed to pay index-build cost during
every measured prepare/rebuild cycle. That is not a speculative caveat; it is the actual mechanical
behavior this branch is isolating.

**Recommendation:** Phrase the expected prepare-time regression as a factual consequence of the flag
configuration, not as a possible side effect.

---

### Medium Severity

#### 3. Runner override requirements should match the shipped Phase 2 runner, not nonexistent retry/dedup features

**Plan references:**
- `docs/plans/2026-03-11-phase3-spatial-index.md` Step 4

**Code references:**
- `isometric-benchmark/benchmark-runner.sh`

**Issue:** The current runner supports:

1. timestamp-isolated output directories
2. `--skip-selftests`
3. `--sizes`
4. hardcoded self-test configs

It does **not** implement automatic retries, CSV dedup, or a verification helper script. The plan
should therefore require only what the current code can honestly support:

1. branch results must be isolated from baseline results
2. branch flag overrides must be obvious in the generated config metadata
3. branch CLI support must not weaken current timestamp isolation

**Recommendation:** Rewrite Step 4 around distinct branch result labeling and self-test isolation, not
around preserving retry/dedup behavior that does not exist in shipped code.

---

#### 4. Self-tests must remain hardcoded under branch overrides

**Plan references:**
- `docs/plans/2026-03-11-phase3-spatial-index.md` Step 4
- `docs/plans/2026-03-11-phase3-spatial-index.md` section 10

**Code references:**
- `isometric-benchmark/benchmark-runner.sh`

**Issue:** In the shipped runner, self-tests already have fixed configs:

1. `selftest_cache` runs with cache-oriented flags enabled
2. `selftest_sanity` runs all-off baseline flags

That behavior is correct and should stay untouched by any Phase 3 flag-override mechanism. The plan
should state this explicitly so the implementation does not accidentally apply `--enable-spatial-index`
to the self-tests as well as to the matrix.

**Recommendation:** Add an explicit rule that branch overrides apply only to the 24-scenario matrix.

---

#### 5. `enableBroadPhaseSort` should be called out as unimplemented

**Plan references:**
- `docs/plans/2026-03-11-phase3-spatial-index.md` sections 1 and 3

**Code references:**
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkActivity.kt`

**Issue:** The plan correctly keeps `enableBroadPhaseSort=false`, but it should be explicit that this
flag is not just "off for isolation" — it is also unimplemented today. The benchmark app logs a warning
if it is enabled.

**Recommendation:** Add a one-line scope note that broad-phase sort is out of scope because the flag has
no implementation yet.

---

### Low Severity

#### 6. Comparison output should include `p99HitTestMs` and call out `NONE`-scenario prepare regression separately

**Plan references:**
- `docs/plans/2026-03-11-phase3-spatial-index.md` section 7

**Issue:** The likely value of this branch is tail-latency reduction under continuous interaction, and
the likely cost is prepare regression in `NONE` scenarios. `p95HitTestMs` is useful, but `p99HitTestMs`
is already available from the CSV and gives a clearer read on worst-case interaction jank.

**Recommendation:** Add `p99HitTestMs` to the comparison table and explicitly separate prepare-cost
analysis for `NONE` scenarios from interaction-heavy scenarios.

---

## Summary

| # | Severity | Finding |
|---|----------|---------|
| 1 | HIGH | Neighboring-cell query requirements must be explicit and parameterized |
| 2 | HIGH | Index-build overhead is guaranteed in this branch and should be described as such |
| 3 | MEDIUM | Runner override requirements should match the shipped Phase 2 runner, not nonexistent retry/dedup features |
| 4 | MEDIUM | Self-tests must remain hardcoded under branch overrides |
| 5 | MEDIUM | `enableBroadPhaseSort` should be called out as unimplemented |
| 6 | LOW | Comparison output should include `p99HitTestMs` and separate `NONE`-scenario prepare analysis |
