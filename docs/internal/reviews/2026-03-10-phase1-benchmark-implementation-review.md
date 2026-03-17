# Phase 1 Benchmark Harness Review

**Date:** 2026-03-10
**Plan reviewed:** `docs/plans/2026-03-09-phase1-benchmark-harness.md`
**Scope:** current branch implementation of the Phase 1 benchmark harness after the latest follow-up fixes

## Verification Performed

- `./gradlew :isometric-benchmark:assembleDebug :isometric-benchmark:testDebugUnitTest :isometric-compose:testDebugUnitTest --console=plain` -> PASS
- `bash -n isometric-benchmark/benchmark-runner.sh` -> PASS

## Overall Assessment

The previous report findings are fixed:

1. the main 24-scenario runner matrix uses baseline-disabled flags
2. consistency validation is warning-only rather than a hard failure
3. the sanity check is scoped to the N=10 baseline case
4. mutation validation tolerance is back to ±2%
5. measurement iterations are reset to the deterministic initial scene state
6. benchmark flag and config defaults are baseline-off

I did not find a new code-level correctness, architecture, or plan-exactness issue in this follow-up.

Current assessment:
- **Architecture:** good
- **Buildability:** good
- **Plan coverage:** good
- **Benchmark trustworthiness:** good at code-review level

---

## Confirmed Fixed Since Prior Review

### 1. Measurement iterations now start from the same deterministic scene state

`BenchmarkScreen` now provides an explicit `onResetScene` callback and `BenchmarkOrchestrator` invokes it before each measurement iteration.

References:
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkScreen.kt:62`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkScreen.kt:77`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkOrchestrator.kt:56`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkOrchestrator.kt:84`

### 2. Benchmark flag defaults are now baseline-off

`BenchmarkFlags` now defaults every optimization flag to `false`, and `BenchmarkConfig` now defaults `flags` to `ALL_OFF`.

References:
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkFlags.kt:14`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/Scenario.kt:43`

### 3. Earlier runner and validator fixes remain in place

References:
- `isometric-benchmark/benchmark-runner.sh:137`
- `isometric-benchmark/benchmark-runner.sh:145`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/HarnessValidator.kt:167`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/HarnessValidator.kt:208`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/HarnessValidator.kt:235`

---

## Findings

No findings in the latest pass.

---

## Strengths

- the runner/result-marker contract is solid and the matrix aligns with the Phase 1 baseline
- the validator is aligned with the documented acceptance criteria
- exporter semantics and benchmark-module tests remain in good shape
- renderer instrumentation remains low-impact and benchmark-specific wiring is isolated

Key references:
- `isometric-benchmark/benchmark-runner.sh:50`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/HarnessValidator.kt:80`
- `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/ResultsExporter.kt:67`
- `isometric-benchmark/src/test/kotlin/io/fabianterhorst/isometric/benchmark/MetricsCollectorTest.kt:1`

---

## Residual Risk

I did not run the full ADB/device benchmark matrix end-to-end, so the remaining validation is operational rather than code-level:

- device-side result export paths
- long-running unattended runner behavior across all 24 scenarios
- real-device timing stability and thermal behavior

---

## Conclusion

The prior report findings are fixed.

At code-review level, Phase 1 now looks review-clean. The remaining work is to validate the full device run operationally rather than to resolve a known implementation defect.
