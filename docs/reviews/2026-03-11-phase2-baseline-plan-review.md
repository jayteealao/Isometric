# Phase 2 Baseline Plan Review

**Date:** 2026-03-11
**Plan reviewed:** `docs/plans/2026-03-10-phase2-baseline-benchmarks.md`
**Scope:** review of the Phase 2 operational plan against the current Phase 1 benchmark harness

## Overall Assessment

The previous plan-review findings are fixed:

1. manual execution steps now use explicit `runTimestamp` values and poll exact `.result` paths instead of treating `am start -W` as completion
2. the deliverables and local-path instructions now match the flattened pull layout
3. the plan now correctly counts `summary.csv` as 26 rows (24 scenarios + 2 self-tests)
4. the JSON parse example now uses a real glob-expansion path
5. mutation-rate tolerance is now consistent with the harness (`±2%`)

The plan is now close to execution-ready. One operational mismatch remains in the retry workflow.

---

## Confirmed Fixed Since Prior Review

### 1. Manual launch and smoke/self-test steps now use exact result-marker polling

The plan now passes an explicit `runTimestamp` and polls a known device path for the corresponding
`.result` file, which matches the actual harness contract.

References:
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:173`
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:186`
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:231`
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:409`

### 2. Deliverables and go/no-go checks now account for the self-tests

The plan now consistently treats the baseline artifact set as 24 scenario runs plus 2 self-tests.

References:
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:606`
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:671`
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:973`
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:1023`
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:1067`

### 3. The JSON parse example is now executable

The smoke-test section now uses `glob.glob(...)[0]` or an explicit argument instead of passing a
literal `*` into Python `open()`.

References:
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:315`
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:321`

### 4. Mutation-rate tolerance is now internally consistent

The analysis and red-flag criteria now match the validator's `±2%` tolerance.

Reference:
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:861`

---

## Findings

## Medium Severity

### 1. The retry workflow still relies on manual row replacement because re-running a failed scenario into any existing `summary.csv` appends a duplicate row

The plan correctly warns not to reuse the same `runTimestamp`, but the replacement procedure still
depends on the operator manually copying one scenario row from a retry run's `summary.csv` into the
original baseline run. That keeps the data recoverable, but it is still an error-prone workflow
because the exporter has no replace/upsert behavior.

Plan references:
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:538`
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:555`
- `docs/plans/2026-03-10-phase2-baseline-benchmarks.md:571`

Code references:
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/ResultsExporter.kt:82`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/ResultsExporter.kt:90`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/ResultsExporter.kt:99`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/ResultsExporter.kt:101`

Why this matters:
- the recovery path is still manual at the point where the plan is supposed to be most operationally reliable
- row replacement in CSV is easy to get wrong and can silently corrupt the final baseline artifact set
- the plan is executable, but the retry story is still weaker than the rest of the document

Recommended direction:
- either keep this as an explicit manual-recovery limitation and call it out more strongly in the deliverables section
- or add a small follow-up harness improvement for replacing an existing scenario row by name

---

## Conclusion

The earlier plan-review findings are fixed.

The remaining issue is limited to the failed-scenario recovery workflow: the plan now avoids the old
duplicate-row trap, but it still falls back to manual CSV surgery because the exporter only appends.

I would treat the plan as operationally usable, with one caveat:

1. if a scenario must be retried, the merge step needs to be handled carefully because the harness does not support row replacement
