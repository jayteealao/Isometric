# Phase 2: Baseline Benchmarks — Detailed Plan

**Date:** 2026-03-10
**Status:** Plan (Pre-Implementation)
**Parent plan:** `docs/plans/2026-03-08-performance-investigation-v2.md` (Phase 2)
**Prerequisite:** Phase 1 complete — benchmark harness reviewed and review-clean (`docs/reviews/2026-03-10-phase1-benchmark-implementation-review.md`)
**Branch:** `perf/benchmark-harness` (continues from Phase 1)

---

## Goal

Run all 24 benchmark scenarios with every optimization flag disabled (`BenchmarkFlags.ALL_OFF`)
to establish ground truth for the naive implementation. Validate that the harness produces
trustworthy, consistent results on a real device (or emulator with documented caveats).
Analyze the baseline data to confirm the O(N²) prepare-time scaling hypothesis and identify
actual bottlenecks before creating per-optimization branches in Phase 3.

Phase 2 is the first *operational* phase — Phase 1 was code, Phase 2 is execution and analysis.
No new Kotlin code is written. The deliverable is data, not software.

---

## Table of Contents

0. [Architectural Decisions](#0-architectural-decisions)
1. [Prerequisites](#1-prerequisites)
2. [Environment Setup](#2-environment-setup)
3. [Build & Install](#3-build--install)
4. [Smoke Test](#4-smoke-test)
5. [Self-Test Validation](#5-self-test-validation)
6. [Full Baseline Run](#6-full-baseline-run)
7. [Results Collection](#7-results-collection)
8. [Baseline Analysis](#8-baseline-analysis)
9. [Expected Results](#9-expected-results)
10. [Troubleshooting](#10-troubleshooting)
11. [Deliverables](#11-deliverables)
12. [Go/No-Go Criteria for Phase 3](#12-gono-go-criteria-for-phase-3)

---

## 0. Architectural Decisions

These decisions were made during Phase 2 planning and apply to Phase 2 execution and all
subsequent phases. They are recorded here as the authoritative reference so that downstream
plans and analysis scripts can rely on them.

### AD-1: ResultsExporter remains append-only

`ResultsExporter` appends rows to `summary.csv` via `file.appendText()` (`ResultsExporter.kt:101`).
It does **not** support upsert or row replacement by scenario name.

**Rationale:** The exporter runs inside a short-lived Activity on a device with no SQLite,
no file locking, and no concurrent writers. Adding upsert (read CSV, find row, replace,
rewrite) inside the Android process would add complexity and failure modes to measured code.
Deduplication after retries is handled host-side by the runner (see AD-4, AD-6).

### AD-2: Self-tests share the artifact set with the scenario matrix

The two self-tests (`selftest_cache`, `selftest_sanity`) write to the **same** `runTimestamp`
directory and the **same** `summary.csv` as the 24 baseline scenarios. They are not exported
to a separate directory or file.

**Rationale:** They use the same exporter path and the same `runTimestamp` — separating them
would mean either a second `runTimestamp` (complicates the runner) or post-hoc file moves
(fragile). Self-test rows are clearly identifiable by their `scenarioName` prefix (`selftest_*`),
so downstream analysis scripts can filter them:

```bash
# Exclude self-tests from analysis
grep -v "^selftest_" summary.csv > scenarios_only.csv
```

**Implication:** `summary.csv` contains **26 data rows** (24 scenarios + 2 self-tests),
not 24. All row-count references in this plan use 26.

### AD-3: Artifact contract for a completed run

A completed baseline run produces four artifact types with different authority levels:

| Artifact | Authority | Git-tracked | Purpose |
|----------|-----------|-------------|---------|
| `summary.csv` | **Authoritative** for aggregate metrics | Yes | Primary input for scaling analysis, cross-config comparison. One row per scenario/self-test. |
| `<name>.json` | **Authoritative** for per-frame data | No (too large) | Histogram analysis, outlier detection, deep-dive investigation. Raw timing arrays for every frame of every iteration. |
| `<name>_validation.log` | **Diagnostic** | Yes | Proves the harness validated its own results. Not consumed programmatically by analysis scripts. |
| `<name>.result` | **Runner internal** | No | Exists only so `benchmark-runner.sh` can poll for completion. Can be deleted after a successful run without data loss. Not a deliverable. |

**Key implications:**
- Analysis scripts should consume `summary.csv` and optionally `*.json`. They should **not**
  depend on `.result` files.
- `summary.csv` + `*_validation.log` + the analysis document are the git-committed deliverables.
- `.result` markers and `.json` files are ephemeral/local artifacts.

### AD-4: Automatic retry in the runner, not the harness

The benchmark Activity runs a single scenario per invocation and has no retry logic. Retries
are handled entirely host-side in `benchmark-runner.sh`.

**Runner retry behavior:** When a scenario fails validation, the runner automatically retries
it up to `MAX_RETRIES` times (default: 2) using the **same** `runTimestamp`. Each retry
appends a duplicate row to `summary.csv` (per AD-1). After all scenarios complete, the runner
runs a deduplication pass that keeps only the **last** row for each `scenarioName`. This is
a single `awk` command — no manual CSV editing is ever required.

**Rationale:** Building retry into the Activity would require on-device detection of prior
results, skip logic, and partial `summary.csv` handling — all harder to debug than a shell
loop. The runner is the right place because it already owns the scenario loop, the poll
contract, and the final result pull.

**No manual reruns.** A baseline is the output of a single `benchmark-runner.sh` invocation.
If the runner exits 0, the baseline is valid. If it exits 1 (scenarios still failing after
retries), the entire run is discarded and re-run from scratch — not patched.

### AD-5: Unit test bar for harness changes

**Tests required** for classes with deterministic, testable logic that affects measurement
correctness:
- `MetricsCollector` — percentile math, zero-allocation invariants
- `SceneGenerator` — deterministic output, distribution validation
- `MutationSimulator` — rate accuracy, reproducibility
- `InteractionSimulator` — tap point generation, projection math

**Tests not required** for wiring, lifecycle, and I/O classes where device validation is
sufficient:
- `BenchmarkActivity` — Android lifecycle; validated by smoke test
- `BenchmarkScreen` — Compose UI wiring; validated by smoke test
- `ResultsExporter` — file I/O; validated by inspecting output files
- `BenchmarkOrchestrator` — framework integration; validated by end-to-end runs
- `benchmark-runner.sh` — shell script; validated by `bash -n` and operational runs

**Rule:** If a change touches a class that already has tests, update the tests. If a change
creates a new class with deterministic logic, add tests. If a change is pure wiring/UI/IO,
device validation is sufficient.

### AD-6: Operational policy

These policies resolve specific questions about how baseline runs are conducted, validated,
and accepted. They are designed for CI/CD automation — every check is machine-executable.

**Rerun policy:** No manual reruns. The runner retries failed scenarios automatically (AD-4).
If the runner exits 0, the run is accepted. If it exits 1, the run is discarded and re-run
from scratch. There is no concept of "patching" a failed run with a separate retry.

**Manual CSV editing:** Never acceptable. The runner's dedup pass (§6.4) handles duplicate
rows from retries. No human touches `summary.csv` between export and git commit.

**Baseline validity:** A baseline is valid if and only if it is the output of a single
`benchmark-runner.sh` invocation that exited 0. Baselines assembled from multiple runs,
manually merged, or hand-edited are not valid and must not be committed.

**Acceptance evidence:** Machine-checkable. The runner's exit code is the primary signal.
The verification script (§12.1) checks all criteria programmatically. Required evidence:
1. Runner exit code 0
2. 26 data rows in `summary.csv` (24 scenarios + 2 self-tests)
3. 26 `*_validation.log` files, all containing "OVERALL: PASS"
4. 26 `*.json` files present and non-empty
5. No duplicate `scenarioName` values in `summary.csv`
6. `summary.csv` has no `avgPrepareMs` or `avgDrawMs` values of 0.0000

**Environment documentation:** The runner logs device model, Android version, and emulator
status into every CSV row and JSON file automatically. No separate screenshots, thermal
logs, or battery notes are required — the data is self-describing. If thermal throttling
is suspected (CV > 15%), the validation log already flags it.

**Automation target:** The entire Phase 2 flow — build, install, run, pull, verify — is
a single shell pipeline with no interactive steps:

```bash
./gradlew :isometric-benchmark:assembleDebug && \
adb install -r isometric-benchmark/build/outputs/apk/debug/isometric-benchmark-debug.apk && \
bash isometric-benchmark/benchmark-runner.sh && \
echo "BASELINE ACCEPTED" || echo "BASELINE REJECTED"
```

The runner's exit code is the CI gate. Everything else (analysis document, git commit) is
a post-acceptance step that can be automated separately or done by a human.

---

## 1. Prerequisites

### 1.1 Phase 1 Completion Checklist

Before beginning Phase 2, verify the following Phase 1 gates:

| Gate | Status | Evidence |
|------|--------|----------|
| Code review clean | ✅ | `docs/reviews/2026-03-10-phase1-benchmark-implementation-review.md`: "no new code-level correctness, architecture, or plan-exactness issue" |
| Debug build passes | ✅ | `./gradlew :isometric-benchmark:assembleDebug` — PASS |
| Unit tests pass | ✅ | `:isometric-benchmark:testDebugUnitTest` — PASS (4 test files: SceneGenerator, MutationSimulator, InteractionSimulator, MetricsCollector) |
| Compose module tests pass | ✅ | `:isometric-compose:testDebugUnitTest` — PASS |
| Shell script syntax valid | ✅ | `bash -n isometric-benchmark/benchmark-runner.sh` — PASS |

### 1.2 Branch State

The benchmark harness is on `perf/benchmark-harness` (8 commits ahead of master). Phase 2
work continues on this branch — no new branch is needed because Phase 2 adds no code, only
data and analysis documents.

```bash
git checkout perf/benchmark-harness
git log --oneline master..HEAD   # expect 8 commits
```

### 1.3 Code Change Policy

Phase 2 must **not** modify any Kotlin code. The harness is reviewed and frozen. If a bug
is discovered during operational testing, the fix must be reviewed before re-running. This
prevents the "fix it and re-run until it looks good" anti-pattern that undermined the
January 2026 benchmarks.

**Exception:** `benchmark-runner.sh` is an operational artifact, not measured code. It may
receive enhancements for CI/CD readiness — specifically the automatic retry and dedup logic
described in AD-4 and §6.4. These changes are reviewed as part of Phase 2 but do not require
re-reviewing the Kotlin harness.

---

## 2. Environment Setup

### 2.1 Device Selection

**Physical device (strongly recommended):**
- The v2 plan notes that emulator results have unreliable absolute numbers
- Emulators share the host CPU with other processes, causing variance
- AndroidX Microbenchmark refuses to run on emulators (`ERRORS: EMULATOR`)
- Our custom harness will run on emulator but results should be flagged as `isEmulator=true`

**Minimum device requirements:**
- Android API 24+ (minSdk for the benchmark module)
- USB debugging enabled
- ADB connection verified: `adb devices` shows the device

**Ideal device characteristics:**
- Mid-range or better SoC (Snapdragon 6xx+, Dimensity 700+, Tensor, etc.)
- At least 4GB RAM
- Battery > 50% or connected to power (thermal throttling under low battery)
- No other apps running in foreground

### 2.1.1 Windows Shell Note

If Phase 2 is being run from a Windows host, use **Git Bash** for
`isometric-benchmark/benchmark-runner.sh`. Do **not** assume WSL bash is equivalent here.

Operationally, WSL bash failed to execute `adb.exe` correctly on this machine. The visible
symptom was `launch command failed` from the runner even though:
- the APK was installed correctly
- the physical device was connected correctly
- direct `adb shell am start ...` launches from PowerShell worked

The successful Windows invocation pattern was:

```powershell
$env:ANDROID_SERIAL='100.73.175.9:43771'
$env:PATH='C:\Users\jayte\AppData\Local\Android\Sdk\platform-tools;' + $env:PATH
& 'C:\Program Files\Git\bin\bash.exe' -lc 'cd /c/Users/jayte/Documents/dev/Isometric && ./isometric-benchmark/benchmark-runner.sh --label baseline-refresh'
```

Treat this as the default pattern for future physical-device branch runs from Windows unless
the host shell behavior is revalidated.

### 2.2 Device Preparation

Before running benchmarks, minimize noise sources:

```bash
# Verify device is connected
adb devices

# Check device info (will be logged in results, but good to verify)
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk

# Optional: reduce thermal throttling and background noise
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
```

**Restore animations after benchmarking:**
```bash
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
```

### 2.3 Emulator Fallback

If no physical device is available, use an emulator with these settings:
- **System image:** x86_64 API 33 or 34 (avoid ARM images — too slow)
- **RAM:** 4096 MB
- **VM heap:** 512 MB
- **Hardware acceleration:** enabled (HAXM or hypervisor)
- **Multi-core CPU:** 4 cores minimum

**Critical caveat:** All emulator results must be interpreted as **relative** improvements
only. Absolute frame times are not comparable to physical devices. The `isEmulator` flag
in the CSV will be `true`, clearly marking these results.

---

## 3. Build & Install

### 3.1 Build the APK

```bash
cd /c/Users/jayte/Documents/dev/Isometric

# Full build including unit tests (confidence check)
./gradlew :isometric-benchmark:assembleDebug \
          :isometric-benchmark:testDebugUnitTest \
          :isometric-compose:testDebugUnitTest \
          --console=plain
```

**Expected output:** `BUILD SUCCESSFUL`
**APK location:** `isometric-benchmark/build/outputs/apk/debug/isometric-benchmark-debug.apk`

### 3.2 Install on Device

```bash
adb install -r isometric-benchmark/build/outputs/apk/debug/isometric-benchmark-debug.apk
```

The `-r` flag replaces an existing installation. If this is the first install, `-r` is
harmless.

**Verify installation:**
```bash
adb shell pm list packages | grep isometric.benchmark
# Expected: package:io.fabianterhorst.isometric.benchmark
```

### 3.3 Verify ADB Launch

Test that the activity is launchable via ADB with a minimal config. Note: `am start -W` is
best-effort only — it may return before the benchmark finishes on some Android versions.
The reliable completion signal is the `.result` marker file, matching the runner's contract
(see `benchmark-runner.sh:54`).

```bash
PACKAGE="io.fabianterhorst.isometric.benchmark"
DEVICE_RESULTS="/sdcard/Android/data/${PACKAGE}/files/benchmark-results"
RUN_TS=$(date +%Y%m%d-%H%M%S)

# Clear stale results from previous manual runs
adb shell rm -rf "${DEVICE_RESULTS}" 2>/dev/null

# Launch with an explicit runTimestamp so we can poll a known path
adb shell am start -n ${PACKAGE}/.BenchmarkActivity \
    --es config '{"sceneSize":10,"mutationRate":0.0,"interactionPattern":"NONE","name":"launch_test","iterations":1,"measurementFrames":10,"warmupMinFrames":5,"warmupMaxFrames":10,"warmupMaxSeconds":5,"flags":{"enablePathCaching":false,"enableSpatialIndex":false,"enablePreparedSceneCache":false,"enableNativeCanvas":false,"enableBroadPhaseSort":false}}' \
    --es runTimestamp "${RUN_TS}"

# Poll the specific run directory (avoids matching stale results)
while true; do
    result=$(adb shell cat "${DEVICE_RESULTS}/${RUN_TS}/launch_test.result" 2>/dev/null) || result=""
    if [ "$result" = "PASS" ] || [ "$result" = "FAIL" ]; then
        echo "Result: $result"
        break
    fi
    sleep 2
done
```

This should:
1. Launch the activity in landscape
2. Run 10 measurement frames (very fast)
3. Write results and a `.result` marker to device storage
4. Call `finish()`

**Check logcat for details:**
```bash
adb logcat -d -s IsoBenchmark:* | tail -20
```

Expected log lines:
```
Starting benchmark: launch_test
Warmup complete: N frames
Iteration 1/1: measuring 10 frames
Benchmark complete: launch_test
Result marker: .../launch_test.result -> PASS
```

---

## 4. Smoke Test

Before committing to the full 24-scenario matrix (~30–45 minutes), run a single representative
scenario end-to-end to validate the complete pipeline.

### 4.1 Single Scenario Test

Run one mid-complexity scenario (N=50, 10% mutation, no interaction) and poll for the
result marker (do not rely on `am start -W` as a completion signal):

```bash
PACKAGE="io.fabianterhorst.isometric.benchmark"
DEVICE_RESULTS="/sdcard/Android/data/${PACKAGE}/files/benchmark-results"
RUN_TS=$(date +%Y%m%d-%H%M%S)

adb shell am start -n ${PACKAGE}/.BenchmarkActivity \
    --es config '{"sceneSize":50,"mutationRate":0.10,"interactionPattern":"NONE","name":"smoke_test","iterations":2,"measurementFrames":100,"flags":{"enablePathCaching":false,"enableSpatialIndex":false,"enablePreparedSceneCache":false,"enableNativeCanvas":false,"enableBroadPhaseSort":false}}' \
    --es runTimestamp "${RUN_TS}"

# Wait for result marker in the specific run directory
while true; do
    result=$(adb shell cat "${DEVICE_RESULTS}/${RUN_TS}/smoke_test.result" 2>/dev/null) || result=""
    if [ "$result" = "PASS" ] || [ "$result" = "FAIL" ]; then
        echo "Smoke test result: $result"
        break
    fi
    sleep 2
done
```

### 4.2 Verify Output Files

After the result marker appears with "PASS", check that results were written:

```bash
# List files on device
adb shell ls -la /sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark-results/

# Find the most recent run directory
adb shell ls /sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark-results/ | tail -1
```

Expected files in the run directory:
```
summary.csv
smoke_test.json
smoke_test_validation.log
smoke_test.result
```

### 4.3 Inspect Smoke Test Results

Pull and inspect the results:

```bash
mkdir -p benchmark-results/smoke
adb pull /sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark-results/ benchmark-results/smoke/
```

**Check the validation log:**
```bash
cat benchmark-results/smoke/*/smoke_test_validation.log
```

Expected output:
```
Validation Report: smoke_test
============================================================
[PASS] cache: Skipped (cache disabled or mutations active)
[PASS] flags: All runtime flags match config
[PASS] consistency: ...
[PASS] sanity: Skipped (not N=10 baseline)
[PASS] mutation: Observed mutation rate: ~10.0% (expected: 10%, tolerance: ±2%, ...)
============================================================
OVERALL: PASS
```

**Check the CSV:**
```bash
cat benchmark-results/smoke/*/summary.csv
```

Verify that:
- Header row is present and well-formed
- Data row has reasonable values (non-zero prepare/draw/frame times)
- All flag columns are `false` (baseline)
- `isEmulator` matches your device type
- `observedMutationRate` is approximately 0.10

**Check the JSON:**
```bash
# Quick sanity: does it parse? (use glob to find the file, then pass to Python)
python3 -c "import json, glob; d=json.load(open(glob.glob('benchmark-results/smoke/*/smoke_test.json')[0])); print('iterations:', len(d['iterations']))"
```

Or with shell expansion:
```bash
python3 -c "import json, sys; d=json.load(open(sys.argv[1])); print('iterations:', len(d['iterations']))" benchmark-results/smoke/*/smoke_test.json
```

Or manually inspect for:
- `flags` object has all `false` values
- `iterations` array has 2 entries (matching `iterations: 2`)
- Each iteration has `rawTimings` with 4 arrays (prepareTimes, drawTimes, frameTimes, hitTestTimes)
- Array lengths match `measurementFrames` (100)

### 4.4 Smoke Test Pass/Fail Criteria

| Check | Pass Condition |
|-------|----------------|
| Activity launches | No crash, logcat shows "Starting benchmark" |
| Measurement completes | Logcat shows "Benchmark complete: smoke_test" |
| Result marker | `smoke_test.result` contains "PASS" |
| CSV written | `summary.csv` exists with header + 1 data row |
| JSON written | `smoke_test.json` exists and parses correctly |
| Validation | All 5 checks PASS in validation log |
| Timing sanity | Avg prepare time > 0ms and < 1000ms for N=50 |

If the smoke test fails, **do not proceed to the full run**. Diagnose and fix per §10.

---

## 5. Self-Test Validation

The benchmark runner includes two mandatory self-tests that run before the 24-scenario matrix.
These validate the harness itself before trusting it for real measurements.

### 5.1 Self-Test Definitions

**selftest_cache:**
```json
{
  "sceneSize": 10,
  "mutationRate": 0.0,
  "interactionPattern": "NONE",
  "name": "selftest_cache",
  "iterations": 1,
  "measurementFrames": 100,
  "flags": {
    "enablePathCaching": true,
    "enableSpatialIndex": true,
    "enablePreparedSceneCache": true,
    "enableNativeCanvas": false,
    "enableBroadPhaseSort": false
  }
}
```

**Purpose:** Verify that the PreparedScene cache actually works. With `mutationRate=0.0`
(static scene) and `enablePreparedSceneCache=true`, the cache should hit on every frame
after the first. The validation check requires > 95% cache hit rate.

**Why this matters:** The January 2026 benchmarks showed 0% cache hit rate even on static
scenes, meaning the "cached" results were actually uncached. This self-test is the single
most important validation gate — if caching doesn't work, all optimization measurements
that depend on it (Phase 3 Branch 1: prepared-scene-cache) are meaningless.

**selftest_sanity:**
```json
{
  "sceneSize": 10,
  "mutationRate": 0.10,
  "interactionPattern": "NONE",
  "name": "selftest_sanity",
  "iterations": 1,
  "measurementFrames": 100,
  "flags": {
    "enablePathCaching": false,
    "enableSpatialIndex": false,
    "enablePreparedSceneCache": false,
    "enableNativeCanvas": false,
    "enableBroadPhaseSort": false
  }
}
```

**Purpose:** Verify that the naive (no-cache, no-optimization) path works and produces
sub-20ms frame times for a trivial 10-shape scene. If this fails, something is
fundamentally broken (broken rendering, device too slow, background process interference).

### 5.2 Self-Test Execution

The runner script handles self-tests automatically when `--skip-selftests` is not passed.
If either self-test fails, the script aborts before running the 24-scenario matrix.

**To run self-tests only (without the matrix):**
```bash
PACKAGE="io.fabianterhorst.isometric.benchmark"
DEVICE_RESULTS="/sdcard/Android/data/${PACKAGE}/files/benchmark-results"
RUN_TS=$(date +%Y%m%d-%H%M%S)

# Launch the cache self-test with an explicit timestamp
adb shell am start -n ${PACKAGE}/.BenchmarkActivity \
    --es config '{"sceneSize":10,"mutationRate":0.0,"interactionPattern":"NONE","name":"selftest_cache","iterations":1,"measurementFrames":100,"warmupMinFrames":10,"warmupMaxFrames":50,"warmupMaxSeconds":10,"flags":{"enablePathCaching":true,"enableSpatialIndex":true,"enablePreparedSceneCache":true,"enableNativeCanvas":false,"enableBroadPhaseSort":false}}' \
    --es runTimestamp "${RUN_TS}"

# Poll for result marker in the specific run directory
while true; do
    result=$(adb shell cat "${DEVICE_RESULTS}/${RUN_TS}/selftest_cache.result" 2>/dev/null) || result=""
    if [ "$result" = "PASS" ] || [ "$result" = "FAIL" ]; then
        echo "selftest_cache: $result"
        break
    fi
    sleep 2
done
```

Then verify the cache hit rate in logcat:
```bash
adb logcat -d -s IsoBenchmark:* | grep "cache hit rate"
```

Expected: `cache hit rate: 99%` or `100%` (will be < 100% only if the first frame is a miss).

### 5.3 Self-Test Failure Scenarios

| Self-Test | Failure Mode | Likely Cause | Resolution |
|-----------|-------------|--------------|------------|
| selftest_cache | cache hit rate = 0% | `forceRebuild` incorrectly true when cache enabled | Debug renderer flag wiring — check `BenchmarkScreen` → `IsometricScene` → `IsometricRenderer.forceRebuild` |
| selftest_cache | cache hit rate < 95% | Spurious `markDirty()` calls on static scene | Add logging to `IsometricNode.markDirty()` to trace dirty origin |
| selftest_sanity | avgFrameMs > 20ms | Device too slow, or thermal throttling | Retry on a cooled device; if persistent, raise threshold or investigate |
| selftest_sanity | avgFrameMs = 0 | Metrics not being recorded | Check `BenchmarkHooksImpl` wiring — hooks may not be connected |
| Either | Crash | Config parsing error, missing dependency | Check logcat for stack trace, verify APK matches source |

---

## 6. Full Baseline Run

### 6.1 The 24-Scenario Matrix

The baseline matrix exercises all combinations of scene size, mutation rate, and interaction
pattern with **all optimization flags disabled** (`BenchmarkFlags.ALL_OFF`):

| # | Name | N | Mutation | Interaction | Est. Duration |
|---|------|---|----------|-------------|---------------|
| 1 | s10_m10_none | 10 | 10% | NONE | ~30s |
| 2 | s10_m10_occasional | 10 | 10% | OCCASIONAL | ~30s |
| 3 | s10_m10_continuous | 10 | 10% | CONTINUOUS | ~30s |
| 4 | s10_m50_none | 10 | 50% | NONE | ~30s |
| 5 | s10_m50_occasional | 10 | 50% | OCCASIONAL | ~30s |
| 6 | s10_m50_continuous | 10 | 50% | CONTINUOUS | ~30s |
| 7 | s50_m10_none | 50 | 10% | NONE | ~35s |
| 8 | s50_m10_occasional | 50 | 10% | OCCASIONAL | ~35s |
| 9 | s50_m10_continuous | 50 | 10% | CONTINUOUS | ~40s |
| 10 | s50_m50_none | 50 | 50% | NONE | ~35s |
| 11 | s50_m50_occasional | 50 | 50% | OCCASIONAL | ~35s |
| 12 | s50_m50_continuous | 50 | 50% | CONTINUOUS | ~40s |
| 13 | s100_m10_none | 100 | 10% | NONE | ~60s |
| 14 | s100_m10_occasional | 100 | 10% | OCCASIONAL | ~60s |
| 15 | s100_m10_continuous | 100 | 10% | CONTINUOUS | ~75s |
| 16 | s100_m50_none | 100 | 50% | NONE | ~60s |
| 17 | s100_m50_occasional | 100 | 50% | OCCASIONAL | ~60s |
| 18 | s100_m50_continuous | 100 | 50% | CONTINUOUS | ~75s |
| 19 | s200_m10_none | 200 | 10% | NONE | ~120s |
| 20 | s200_m10_occasional | 200 | 10% | OCCASIONAL | ~120s |
| 21 | s200_m10_continuous | 200 | 10% | CONTINUOUS | ~150s |
| 22 | s200_m50_none | 200 | 50% | NONE | ~120s |
| 23 | s200_m50_occasional | 200 | 50% | OCCASIONAL | ~120s |
| 24 | s200_m50_continuous | 200 | 50% | CONTINUOUS | ~150s |

**Duration estimates** include warmup (adaptive, 30–500 frames), 3 measurement iterations
of 500 frames each, 2-second cooldowns between iterations, and activity restart overhead.

**Total estimated time:** ~25–45 minutes (device-dependent; N=200 scenarios are slowest
because prepare time scales quadratically).

### 6.2 Running the Matrix

```bash
cd /c/Users/jayte/Documents/dev/Isometric

# Ensure clean results directory
# (runner creates benchmark-results/<timestamp>/ automatically)

# Run the full matrix (self-tests included)
bash isometric-benchmark/benchmark-runner.sh
```

The runner will:
1. Run `selftest_cache` — abort if cache validation fails
2. Run `selftest_sanity` — abort if baseline frame time exceeds 20ms
3. Run scenarios 1–24 sequentially
4. Pull all results from device to local `benchmark-results/<timestamp>/`

**Monitor progress:** The runner prints real-time status:
```
[14:30:05] Running: s10_m10_none
[14:30:35] Completed: s10_m10_none

[2/24] s10_m10_occasional
[14:30:37] Running: s10_m10_occasional
...
```

### 6.3 Partial Runs

If the full matrix is too long for an initial test, use `--sizes` to run a subset:

```bash
# Only N=10 and N=50 (12 scenarios, ~10 minutes)
bash isometric-benchmark/benchmark-runner.sh --sizes "10 50"
```

This is useful for:
- Quick validation that the harness works end-to-end
- Testing on an emulator before committing to a full device run
- Time-constrained sessions

### 6.4 Automatic Retry and Deduplication

The runner handles failed scenarios automatically (per AD-4, AD-6). No manual intervention
is required.

**Retry behavior:**

When `run_scenario` returns failure (validation failed or timeout), the runner retries the
scenario up to `MAX_RETRIES` times (default: 2) with the **same** `runTimestamp`. Each retry
re-launches the Activity, which overwrites the `.json`, `_validation.log`, and `.result`
files (all use `file.writeText()`), but **appends** a duplicate row to `summary.csv`
(per AD-1, `file.appendText()`).

**Deduplication:**

After all scenarios complete (including retries), the runner deduplicates `summary.csv`
on-device before pulling results. For each `scenarioName`, only the **last** row is kept —
this is the result of the final successful retry (or the final failed attempt if all retries
failed). The dedup is a single `awk` command run via `adb shell`:

```bash
# Deduplicate summary.csv: keep header + last row per scenarioName
adb shell "cd ${DEVICE_RESULTS}/${TIMESTAMP} && \
    awk -F, 'NR==1{print;next} {data[\$1]=\$0} END{for(k in data)print data[k]}' \
    summary.csv > summary.csv.tmp && mv summary.csv.tmp summary.csv"
```

**Runner changes required for Phase 2** (additions to `benchmark-runner.sh`):

```bash
# --- Add near top of script ---
MAX_RETRIES=2

# --- Replace the scenario failure handling in the main loop ---
# (currently: increment failed counter and continue)
# New behavior:
attempt=0
scenario_passed=false
while [ "$attempt" -le "$MAX_RETRIES" ]; do
    if run_scenario "$config_json" "$name"; then
        scenario_passed=true
        break
    fi
    attempt=$((attempt + 1))
    if [ "$attempt" -le "$MAX_RETRIES" ]; then
        echo "  Retry $attempt/$MAX_RETRIES for $name"
        sleep 5  # brief cooldown before retry
    fi
done
if [ "$scenario_passed" = false ]; then
    failed=$((failed + 1))
    echo "FAILED: $name (exhausted $MAX_RETRIES retries)"
fi

# --- Add after main loop, before pulling results ---
# Deduplicate summary.csv (retries may have appended duplicate rows)
echo "--- Deduplicating summary.csv ---"
adb shell "cd ${DEVICE_RESULTS}/${TIMESTAMP} && \
    awk -F, 'NR==1{print;next} {data[\$1]=\$0} END{for(k in data)print data[k]}' \
    summary.csv > summary.csv.tmp && mv summary.csv.tmp summary.csv"
```

**What this means for the operator:**

- **If the runner exits 0:** The baseline is valid. Proceed to §7 (results collection).
- **If the runner exits 1:** One or more scenarios failed even after retries. Discard the
  run and investigate. Do not attempt to patch, merge, or manually re-run individual
  scenarios. Fix the root cause (§10), then re-run the full matrix from scratch.

**No manual CSV editing is ever acceptable** (per AD-6). The dedup pass handles all
duplicate rows from retries. The runner's exit code is the single source of truth.

### 6.5 Unattended Execution Considerations

The benchmark matrix takes 25–45 minutes. During this time:

**Do:**
- Keep the device connected via USB and screen unlocked (or disable auto-lock)
- Keep the device plugged in (battery drain + thermal throttling)
- Leave the terminal running unattended

**Don't:**
- Interact with the device (touches, notifications, other apps)
- Disconnect USB
- Put the device to sleep
- Run CPU-intensive tasks on the host machine (emulator only)

**Screen lock mitigation:**
```bash
# Disable screen timeout (restore after benchmarking)
adb shell settings put system screen_off_timeout 1800000  # 30 minutes
```

---

## 7. Results Collection

### 7.1 Output Structure

After the runner completes, results are in two places:

**On device:**
```
/sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark-results/<timestamp>/
├── summary.csv                        — 26 rows (24 scenarios + 2 self-tests) + header
├── selftest_cache.json                — self-test per-frame data (authoritative)
├── selftest_cache_validation.log      — self-validation proof (diagnostic)
├── selftest_cache.result              — runner poll marker (internal, not a deliverable)
├── selftest_sanity.json
├── selftest_sanity_validation.log
├── selftest_sanity.result
├── s10_m10_none.json                  — per scenario: full per-frame timing arrays
├── s10_m10_none_validation.log
├── s10_m10_none.result
├── s10_m10_occasional.json
├── ...                                — (26 names × 3 files = 78 files + summary.csv)
└── s200_m50_continuous.result
```

**Pulled to local (after flattening — see §7.2):**
```
benchmark-results/baseline-<timestamp>/
├── summary.csv
├── selftest_cache.json
├── ...
└── s200_m50_continuous.result
```

### 7.2 Pull Results

The runner script (`benchmark-runner.sh:157`) pulls results automatically via:
```bash
adb pull "${DEVICE_RESULTS}/" "${RESULTS_DIR}/"
```

This nests the device-side `benchmark-results/<timestamp>/` directory inside the local
`${RESULTS_DIR}` (which is already `benchmark-results/<timestamp>/`), producing a doubled
path like `benchmark-results/<timestamp>/benchmark-results/<timestamp>/...`.

**To pull manually and get a clean layout, pull the specific timestamp directory's
contents into a flat local directory:**

```bash
# Identify the timestamp directory on device
TIMESTAMP=$(adb shell ls /sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark-results/ | tail -1 | tr -d '\r')

# Pull into a clearly named flat directory
mkdir -p "benchmark-results/baseline-${TIMESTAMP}"
adb pull "/sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark-results/${TIMESTAMP}/" \
    "benchmark-results/baseline-${TIMESTAMP}/"
```

If the runner already pulled results (with the nested layout), flatten them:
```bash
# The runner creates benchmark-results/<timestamp>/ locally, then adb pull nests inside it.
# Move the nested files up:
TIMESTAMP="20260311-143000"  # replace with actual timestamp
mv benchmark-results/${TIMESTAMP}/benchmark-results/${TIMESTAMP}/* benchmark-results/${TIMESTAMP}/
rmdir benchmark-results/${TIMESTAMP}/benchmark-results/${TIMESTAMP} benchmark-results/${TIMESTAMP}/benchmark-results
mv benchmark-results/${TIMESTAMP} benchmark-results/baseline-${TIMESTAMP}
```

### 7.3 Verify Completeness

After pulling (and flattening if needed), verify all 26 scenarios (24 + 2 self-tests)
produced results. Use authoritative artifacts (summary.csv, validation logs) rather than
runner-internal `.result` markers (per AD-3).

```bash
BASELINE_DIR="benchmark-results/baseline-${TIMESTAMP}"

# Count data rows in summary.csv (expect 26, excluding header)
tail -n +2 "${BASELINE_DIR}/summary.csv" | wc -l

# Count JSON files (expect 26)
find "${BASELINE_DIR}" -maxdepth 1 -name "*.json" | wc -l

# Count validation logs (expect 26)
find "${BASELINE_DIR}" -maxdepth 1 -name "*_validation.log" | wc -l

# Verify all validation logs show OVERALL: PASS
grep -L "OVERALL: PASS" "${BASELINE_DIR}"/*_validation.log
# (no output = all passed; any filenames printed = those scenarios failed)

# List scenario names from the CSV to confirm all 24 + 2 self-tests are present
tail -n +2 "${BASELINE_DIR}/summary.csv" | cut -d, -f1 | sort
```

### 7.4 Clean Up Device (Optional)

After pulling results and verifying they're complete locally:

```bash
# Remove benchmark results from device
adb shell rm -rf /sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark-results/
```

---

## 8. Baseline Analysis

### 8.1 Analysis Goals

The baseline analysis must answer these questions:

1. **Scaling confirmation:** Does prepare time scale quadratically (O(N²)) with scene size?
2. **Bottleneck identification:** What percentage of frame time is prepare vs draw?
3. **Mutation impact:** How much does mutation rate affect frame time?
4. **Interaction overhead:** What is the raw hit-test cost at each scene size?
5. **Consistency:** Are results stable across 3 iterations (CV < 15%)?
6. **Emulator reliability:** If on emulator, are relative trends trustworthy?

### 8.2 Scaling Analysis

Extract prepare time vs scene size for the `NONE` interaction, 10% mutation scenarios
(the cleanest measurement of engine cost):

| Scenario | N | Expected Prepare (ms) | Observed Prepare (ms) | N² Ratio |
|----------|---|----------------------|----------------------|----------|
| s10_m10_none | 10 | ~5 | ? | 1.0x |
| s50_m10_none | 50 | ~25 | ? | 5.0x expected (50²/10² = 25x) |
| s100_m10_none | 100 | ~110 | ? | 22x expected (100²/10² = 100x) |
| s200_m10_none | 200 | ~450 | ? | 90x expected (200²/10² = 400x) |

**How to compute N² ratio:**

```
ratio(N) = observed_prepare(N) / observed_prepare(10)
expected_ratio(N) = N² / 10² = N² / 100
```

If the observed ratios are close to expected (within ~2x due to constant factors and
non-sorting overhead), this confirms the O(N²) hypothesis. If they're significantly
different, the bottleneck may be elsewhere.

**Key insight from January 2026 data:** At N=10, prepare was 5.0ms. At N=100, prepare was
111ms. That's a 22x increase for a 10x increase in N — consistent with O(N²) (expected 100x,
but constant factors and non-sorting work reduce the ratio at small N).

### 8.3 Prepare vs Draw Breakdown

For each scenario, compute:

```
prepare_fraction = avgPrepareMs / (avgPrepareMs + avgDrawMs)
draw_fraction = avgDrawMs / (avgPrepareMs + avgDrawMs)
```

**Expected pattern:**
- N=10: prepare dominates (~80%+ of frame time)
- N=50: prepare dominates (~85%+ of frame time)
- N=100: prepare dominates (~90%+ of frame time)
- N=200: prepare dominates (~95%+ of frame time)

Draw time should scale roughly linearly with N (each shape = one path draw). If draw time
scales super-linearly, there may be a hidden O(N²) cost in the draw path (unlikely but
worth checking).

### 8.4 Mutation Impact Analysis

Compare 10% vs 50% mutation at each scene size:

```
mutation_overhead(N) = avgPrepareMs(N, 50%) - avgPrepareMs(N, 10%)
```

With `enablePreparedSceneCache=false` (baseline), every frame is a full rebuild regardless
of mutation rate. Therefore, **mutation rate should have minimal impact on prepare time**
in the baseline. If there's a significant difference, it suggests the mutation application
itself has measurable cost (worth noting for Phase 3).

### 8.5 Hit-Test Cost Analysis

Compare interaction patterns at each scene size:

| N | NONE hitTest (ms) | OCCASIONAL hitTest (ms) | CONTINUOUS hitTest (ms) |
|---|-------------------|------------------------|------------------------|
| 10 | 0 (no taps) | ? | ? |
| 50 | 0 | ? | ? |
| 100 | 0 | ? | ? |
| 200 | 0 | ? | ? |

With `enableSpatialIndex=false` (baseline), hit testing uses the linear scan path
(`engine.findItemAt()` — iterates all shapes). Expected scaling: O(N) per hit test.

For CONTINUOUS interaction (every frame), hit-test cost is added to every frame time.
The delta `avgFrameMs(CONTINUOUS) - avgFrameMs(NONE)` at each N estimates the per-frame
hit-test overhead.

### 8.6 Consistency Analysis

For each scenario, the harness runs 3 iterations. The validator computes the coefficient of
variation (CV) across iterations for frame time:

```
CV = stddev(iteration_means) / mean(iteration_means)
```

**Threshold:** CV < 15% → consistent. CV >= 15% → warning (thermal throttling, GC pressure,
or background interference).

**Where to find this:** Look at `*_validation.log` files for the consistency check:
```
[PASS] consistency: Frame time CV across iterations: 5.2% (threshold: <15%)
```

If multiple scenarios have CV > 15%, consider:
- Re-running on a cooled device
- Disabling background processes
- Reducing measurement frames for faster turnaround

### 8.7 Analysis Document

Write the analysis as `docs/analysis/2026-03-XX-phase2-baseline-analysis.md` with:

1. **Summary table:** All 24 scenarios with key metrics (avgPrepareMs, avgDrawMs, avgFrameMs, cacheHitRate)
2. **Scaling chart:** Prepare time vs N (log-log plot if possible, or a table showing ratios)
3. **Bottleneck breakdown:** Prepare/draw fraction per scenario
4. **Anomalies:** Any unexpected results (regressions, outliers, high CV)
5. **Comparison to estimates:** Actual vs expected from v2 plan §Phase 2 table
6. **Conclusion:** Confirmed or refuted hypotheses; implications for Phase 3 priorities

---

## 9. Expected Results

### 9.1 Prepare Time Estimates

Based on January 2026 data (adjusted for N ≤ 200, emulator):

| N | Mutation | Expected Avg Prepare (ms) | Rationale |
|---|----------|--------------------------|-----------|
| 10 | 10% | ~5 | Direct from Jan data: N=10 static = 5.0ms |
| 10 | 50% | ~5 | Same — baseline has no cache, mutation rate shouldn't matter |
| 50 | 10% | ~25 | Interpolated: O(N²) from N=10 baseline |
| 50 | 50% | ~25 | Same reasoning |
| 100 | 10% | ~110 | Direct from Jan data: N=100 static = 111.0ms |
| 100 | 50% | ~110 | Same reasoning |
| 200 | 10% | ~450 | Extrapolated: (200/100)² × 111 ≈ 444ms |
| 200 | 50% | ~450 | Same reasoning |

### 9.2 Draw Time Estimates

| N | Expected Avg Draw (ms) | Rationale |
|---|------------------------|-----------|
| 10 | ~1 | Direct from Jan data: N=10 static = 0.9ms |
| 50 | ~5 | Linear interpolation |
| 100 | ~10 | Direct from Jan data: N=100 static = 10.0ms |
| 200 | ~15-20 | Linear extrapolation |

### 9.3 Cache Hit Rate

For ALL baseline scenarios: **0% cache hit rate expected.**

`enablePreparedSceneCache=false` means `forceRebuild=true`, so `rebuildCache()` runs on
every frame. The `MetricsCollector` records cache misses for every frame. The cache
validation check is correctly skipped for baseline runs (cache disabled).

### 9.4 What to Watch For

**Red flags that indicate a harness problem (not a performance problem):**
- Cache hit rate > 0% with `enablePreparedSceneCache=false` → flag wiring bug
- Prepare time = 0ms → hooks not recording
- Draw time = 0ms → hooks not recording
- Frame time = 0ms → `FramePacer` not working
- Mutation rate off by > ±2% → `MutationSimulator` seed issue (matches `HarnessValidator` tolerance)
- N=10 frame time > 20ms → device too slow or background interference
- CV > 30% across all scenarios → thermal throttling or resource contention

**Yellow flags that are worth noting but not blocking:**
- Prepare time lower than estimates → device faster than Jan 2026 emulator (good)
- Prepare time higher than estimates → device slower or overhead increased
- N=200 prepare time > 1000ms → on an emulator, this may be normal
- CV between 15–30% on some scenarios → thermal throttling, common on budget devices

---

## 10. Troubleshooting

### 10.1 Self-Test Failures

**selftest_cache FAIL — cache hit rate = 0%:**

This is the critical failure mode that plagued the January 2026 benchmarks.

1. Check flag wiring in logcat:
   ```bash
   adb logcat -d -s IsoBenchmark:* | grep -i "flag\|force\|cache"
   ```

2. Verify `BenchmarkScreen` passes `forceRebuild = !flags.enablePreparedSceneCache`:
   - With `enablePreparedSceneCache=true` → `forceRebuild=false` → cache should work
   - With `enablePreparedSceneCache=false` → `forceRebuild=true` → cache disabled (baseline)

3. Check `IsometricRenderer.forceRebuild` is being respected:
   - In `ensurePreparedScene()`: if `forceRebuild` is true, it should call `invalidate()`
   - Add temporary debug logging if needed (but this means modifying code — see §1.3)

**selftest_sanity FAIL — avgFrameMs > 20ms:**

1. Check device load:
   ```bash
   adb shell top -b -n 1 | head -20
   ```

2. Check for thermal throttling:
   ```bash
   adb shell cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null
   ```

3. Retry after cooling:
   ```bash
   # Wait 2 minutes, then re-run
   adb shell am start -W -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity \
       --es config '{"sceneSize":10,"mutationRate":0.10,"interactionPattern":"NONE","name":"selftest_sanity_retry","iterations":1,"measurementFrames":100,"flags":{"enablePathCaching":false,"enableSpatialIndex":false,"enablePreparedSceneCache":false,"enableNativeCanvas":false,"enableBroadPhaseSort":false}}'
   ```

### 10.2 Scenario Timeouts

The runner script has a 5-minute timeout (`RESULT_POLL_TIMEOUT=300`) per scenario. If a
scenario times out, the most likely causes are:

1. **N=200 on emulator:** Each frame takes ~450ms prepare time. 500 frames × 3 iterations
   = 1500 frames × 450ms = 675 seconds (~11 minutes). This exceeds the 5-minute timeout.
   **Fix:** Increase `RESULT_POLL_TIMEOUT` to 900 (15 minutes) for N=200 scenarios, or
   reduce `measurementFrames` to 200 for the initial run.

2. **Activity crash:** Check logcat for stack traces:
   ```bash
   adb logcat -d | grep -A 20 "FATAL EXCEPTION"
   ```

3. **Activity killed by system:** Android may kill the activity if it's consuming too much
   memory or running too long in background. Check:
   ```bash
   adb logcat -d | grep -i "kill\|lowmemory\|oom"
   ```

### 10.3 ADB Connection Issues

```bash
# Reconnect
adb kill-server && adb start-server && adb devices

# If device shows "offline"
adb reconnect

# If using emulator
adb connect localhost:5554
```

### 10.4 Results Not Found

If the runner can't find result files on device:

```bash
# Check the full path
adb shell ls -R /sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/

# On Android 11+, scoped storage may redirect. Check alternate paths:
adb shell ls -R /data/data/io.fabianterhorst.isometric.benchmark/files/
```

If results are in a different location, update `DEVICE_RESULTS` in `benchmark-runner.sh`
(§1.3 allows script-level fixes).

---

## 11. Deliverables

### 11.1 Required Deliverables

All result files live directly inside the flattened `baseline-<timestamp>/` directory
(see §7.2 for pull and flatten instructions). Authority levels per AD-3.

| Deliverable | Authority | Path | Git | Description |
|-------------|-----------|------|-----|-------------|
| Summary CSV | Authoritative | `benchmark-results/baseline-<timestamp>/summary.csv` | Yes | 26 rows (24 scenarios + 2 self-tests), aggregate metrics |
| Per-scenario JSON | Authoritative | `benchmark-results/baseline-<timestamp>/<name>.json` | No | 26 files with per-frame timing arrays (too large for git) |
| Validation logs | Diagnostic | `benchmark-results/baseline-<timestamp>/<name>_validation.log` | Yes | 26 logs proving harness self-validation passed |
| Analysis document | — | `docs/analysis/2026-03-XX-phase2-baseline-analysis.md` | Yes | Scaling analysis, bottleneck breakdown, comparison to estimates |

**Not deliverables** (per AD-3): `.result` marker files are runner internals used for
poll-based completion detection. They are present in the run directory but carry no data
beyond "PASS"/"FAIL" and can be deleted after verifying completeness (§7.3).

### 11.2 Result Naming

The pull-and-flatten step in §7.2 already produces a `baseline-<timestamp>/` directory.
Verify the layout is flat (no nested `benchmark-results/` subdirectory):

```bash
# Should show summary.csv, *.json, *.log, *.result directly — no subdirectories
ls benchmark-results/baseline-*/
```

If you see a nested `benchmark-results/` directory inside, re-run the flatten step from §7.2.

### 11.3 Git Tracking

Commit authoritative and diagnostic artifacts (per AD-3). JSON files and result markers
are excluded:

```bash
# Verify the flat layout first (§11.2) so these globs match actual files
git add benchmark-results/baseline-*/summary.csv
git add benchmark-results/baseline-*/*_validation.log
git add docs/analysis/2026-03-XX-phase2-baseline-analysis.md

# Exclude non-deliverables from git (per AD-3)
cat >> .gitignore << 'EOF'
benchmark-results/**/*.json
benchmark-results/**/*.result
EOF
```

**Commit message:**
```
docs(benchmark): add Phase 2 baseline benchmark results

24-scenario matrix, all optimizations disabled (BenchmarkFlags.ALL_OFF).
Device: <model>, Android <version>, isEmulator=<true/false>.
Key finding: prepare time scales O(N²) — N=200 avg prepare = Xms.
```

---

## 12. Go/No-Go Criteria for Phase 3

### 12.1 Go Criteria (All Must Pass)

Every criterion is machine-checkable. The verification script (§12.2) runs all checks and
exits 0 (GO) or 1 (NO-GO).

| # | Criterion | Machine Check |
|---|-----------|---------------|
| G1 | All 26 results complete | `summary.csv` has exactly 26 data rows; 26 `*_validation.log` files exist |
| G2 | No duplicate scenarios | `scenarioName` column in `summary.csv` has 26 unique values |
| G3 | All validations pass | Every `*_validation.log` contains "OVERALL: PASS" |
| G4 | Self-tests pass | Implied by G3 (self-tests are validated scenarios) |
| G5 | No zero metrics | No rows in `summary.csv` have `avgPrepareMs` or `avgDrawMs` = 0.0000 |
| G6 | Cache disabled in baseline | All non-selftest rows have `cacheHitRate` ≤ 0.01 |
| G7 | Runner exited clean | Runner exit code was 0 (primary gate — implies retries succeeded) |

**Analysis-level criteria** (checked by the analysis document author, not the script):

| # | Criterion | How to Verify |
|---|-----------|---------------|
| A1 | O(N²) scaling confirmed | Prepare time at N=200 is at least 20x prepare time at N=10 |
| A2 | Prepare dominates frame time | At N≥100, prepare is >80% of (prepare + draw) |
| A3 | Consistency acceptable | Majority of scenarios have CV < 15% |

### 12.2 Verification Script

Add this as `isometric-benchmark/verify-baseline.sh`. It is the CI gate — exit 0 means
the baseline is accepted.

```bash
#!/usr/bin/env bash
# verify-baseline.sh — machine-checkable acceptance of a baseline run
#
# Usage: bash isometric-benchmark/verify-baseline.sh <BASELINE_DIR>
# Example: bash isometric-benchmark/verify-baseline.sh benchmark-results/baseline-20260311-143000
#
# Exit 0 = GO (all checks pass)
# Exit 1 = NO-GO (one or more checks failed)

set -euo pipefail

BASELINE_DIR="$1"
CSV="${BASELINE_DIR}/summary.csv"
FAILURES=0

check() {
    local name="$1" condition="$2"
    if eval "$condition"; then
        echo "[PASS] $name"
    else
        echo "[FAIL] $name"
        FAILURES=$((FAILURES + 1))
    fi
}

echo "=== Baseline Verification: ${BASELINE_DIR} ==="

# G1: 26 data rows
ROW_COUNT=$(tail -n +2 "$CSV" | wc -l | tr -d ' ')
check "G1: 26 data rows in summary.csv (found: $ROW_COUNT)" "[ '$ROW_COUNT' -eq 26 ]"

# G1: 26 validation logs
LOG_COUNT=$(find "$BASELINE_DIR" -maxdepth 1 -name "*_validation.log" | wc -l | tr -d ' ')
check "G1: 26 validation logs (found: $LOG_COUNT)" "[ '$LOG_COUNT' -eq 26 ]"

# G2: no duplicate scenario names
UNIQUE_NAMES=$(tail -n +2 "$CSV" | cut -d, -f1 | sort -u | wc -l | tr -d ' ')
check "G2: no duplicate scenarioNames (unique: $UNIQUE_NAMES)" "[ '$UNIQUE_NAMES' -eq 26 ]"

# G3: all validations pass
FAIL_LOGS=$(grep -L "OVERALL: PASS" "$BASELINE_DIR"/*_validation.log 2>/dev/null | wc -l | tr -d ' ')
check "G3: all validation logs PASS (failing: $FAIL_LOGS)" "[ '$FAIL_LOGS' -eq 0 ]"

# G5: no zero prepare or draw metrics
ZERO_PREPARE=$(tail -n +2 "$CSV" | awk -F, '$10 == "0.0000"' | wc -l | tr -d ' ')
ZERO_DRAW=$(tail -n +2 "$CSV" | awk -F, '$17 == "0.0000"' | wc -l | tr -d ' ')
check "G5: no zero avgPrepareMs (found: $ZERO_PREPARE)" "[ '$ZERO_PREPARE' -eq 0 ]"
check "G5: no zero avgDrawMs (found: $ZERO_DRAW)" "[ '$ZERO_DRAW' -eq 0 ]"

# G6: baseline cache hit rate ≈ 0 (exclude selftest rows which have cache ON)
HIGH_CACHE=$(tail -n +2 "$CSV" | grep -v "^selftest_" | awk -F, '$35 > 0.01' | wc -l | tr -d ' ')
check "G6: baseline cacheHitRate ≤ 0.01 for non-selftest rows (violations: $HIGH_CACHE)" "[ '$HIGH_CACHE' -eq 0 ]"

# G1: 26 JSON files (authoritative per-frame data)
JSON_COUNT=$(find "$BASELINE_DIR" -maxdepth 1 -name "*.json" | wc -l | tr -d ' ')
check "G1: 26 JSON files (found: $JSON_COUNT)" "[ '$JSON_COUNT' -eq 26 ]"

echo ""
if [ "$FAILURES" -eq 0 ]; then
    echo "=== RESULT: GO (all checks passed) ==="
    exit 0
else
    echo "=== RESULT: NO-GO ($FAILURES check(s) failed) ==="
    exit 1
fi
```

**CI integration:**
```bash
# Full CI pipeline — single command, exit code is the gate
./gradlew :isometric-benchmark:assembleDebug && \
adb install -r isometric-benchmark/build/outputs/apk/debug/isometric-benchmark-debug.apk && \
bash isometric-benchmark/benchmark-runner.sh && \
bash isometric-benchmark/verify-baseline.sh benchmark-results/baseline-* && \
echo "BASELINE ACCEPTED" || echo "BASELINE REJECTED"
```

### 12.3 No-Go Responses

| Failure | Response |
|---------|----------|
| Runner exits 1 (scenarios failed after retries) | Investigate logcat and validation logs. Fix root cause. Re-run full matrix. |
| Verification script exits 1 | Check which G-criterion failed. If G1 (missing files): check device storage. If G5 (zero metrics): hooks not wired. If G6 (cache > 0 in baseline): `forceRebuild` flag broken. |
| A1 fails (O(N²) not confirmed) | Bottleneck may be elsewhere. Proceed with caution — Phase 3 priorities may change. |
| A3 fails (high CV) | Re-run on a cooled device or with fewer concurrent processes. |

### 12.4 Phase 3 Prerequisites

If Phase 2 passes all Go criteria (runner exit 0, verification script exit 0), Phase 3
begins:

1. **Merge `perf/benchmark-harness` to master** (includes harness code + baseline results)
2. **Create 5 individual optimization branches from master:**
   - `perf/prepared-scene-cache` (flag toggle only — code already exists)
   - `perf/path-caching` (flag toggle only)
   - `perf/broad-phase-sort` (**new implementation** — only genuinely new code)
   - `perf/spatial-index` (flag toggle only)
   - `perf/native-canvas` (flag toggle only)
3. **Each branch runs the same 24-scenario matrix** with its optimization flag enabled
4. **Baseline data from Phase 2 is the comparison reference** for all Phase 3 measurements

### 12.5 Decision Log Template

Record the Phase 2 decision in the analysis document:

```markdown
## Phase 2 Decision

**Date:** 2026-03-XX
**Device:** <model>, Android <version>, isEmulator=<true/false>
**Runner exit code:** 0
**Verification exit code:** 0

**Machine checks (from verify-baseline.sh):**
- [x] G1: 26/26 data rows, 26 validation logs, 26 JSON files
- [x] G2: 26 unique scenario names
- [x] G3: All validation logs PASS
- [x] G5: No zero metrics
- [x] G6: Baseline cache hit rate ≤ 0.01
- [x] G7: Runner exited 0

**Analysis checks:**
- [ ] A1: O(N²) confirmed (N200 prepare / N10 prepare = Xx)
- [ ] A2: Prepare dominates (>80% at N≥100)
- [ ] A3: Consistency OK (X/26 scenarios CV < 15%)

**Key baseline numbers:**
| N | Avg Prepare (ms) | Avg Draw (ms) | Prepare % |
|---|------------------|---------------|-----------|
| 10 | | | |
| 50 | | | |
| 100 | | | |
| 200 | | | |

**Phase 3 priorities (based on baseline data):**
1. ...
2. ...
```

---

## Appendix A: Quick Reference Commands

```bash
# --- Full CI pipeline (single command) ---
./gradlew :isometric-benchmark:assembleDebug && \
adb install -r isometric-benchmark/build/outputs/apk/debug/isometric-benchmark-debug.apk && \
bash isometric-benchmark/benchmark-runner.sh && \
bash isometric-benchmark/verify-baseline.sh benchmark-results/baseline-*

# --- Individual steps ---

# Build and install
./gradlew :isometric-benchmark:assembleDebug
adb install -r isometric-benchmark/build/outputs/apk/debug/isometric-benchmark-debug.apk

# Run full matrix with self-tests (retries and dedup are automatic)
bash isometric-benchmark/benchmark-runner.sh

# Run subset (N=10 and N=50 only)
bash isometric-benchmark/benchmark-runner.sh --sizes "10 50"

# Run without self-tests (NOT recommended for baseline)
bash isometric-benchmark/benchmark-runner.sh --skip-selftests

# Verify baseline acceptance (exit 0 = GO, exit 1 = NO-GO)
bash isometric-benchmark/verify-baseline.sh benchmark-results/baseline-*/

# Check logcat
adb logcat -d -s IsoBenchmark:*

# Quick CSV preview
column -s, -t < benchmark-results/baseline-*/summary.csv | head -5

# Count completed scenarios (from authoritative CSV, not .result markers)
tail -n +2 benchmark-results/baseline-*/summary.csv | wc -l
```

## Appendix B: CSV Column Reference

The `summary.csv` columns, in order:

| Column | Type | Description |
|--------|------|-------------|
| scenarioName | string | e.g., "s50_m10_none" |
| sceneSize | int | 10, 50, 100, or 200 |
| mutationRate | float | 0.10 or 0.50 |
| interactionPattern | string | NONE, OCCASIONAL, or CONTINUOUS |
| enablePathCaching | bool | false (baseline) |
| enableSpatialIndex | bool | false (baseline) |
| enablePreparedSceneCache | bool | false (baseline) |
| enableNativeCanvas | bool | false (baseline) |
| enableBroadPhaseSort | bool | false (baseline) |
| avgPrepareMs | float | Mean prepare time across frames (aggregated across iterations) |
| p50PrepareMs | float | Median prepare time |
| p95PrepareMs | float | 95th percentile prepare time |
| p99PrepareMs | float | 99th percentile prepare time |
| minPrepareMs | float | Minimum prepare time (min of mins across iterations) |
| maxPrepareMs | float | Maximum prepare time (max of maxes across iterations) |
| stdDevPrepareMs | float | Standard deviation of prepare times |
| avgDrawMs | float | Mean draw time |
| p50DrawMs | float | Median draw time |
| p95DrawMs | float | 95th percentile draw time |
| p99DrawMs | float | 99th percentile draw time |
| minDrawMs | float | Minimum draw time |
| maxDrawMs | float | Maximum draw time |
| stdDevDrawMs | float | Standard deviation of draw times |
| avgFrameMs | float | Mean vsync-to-vsync frame time |
| p50FrameMs | float | Median frame time |
| p95FrameMs | float | 95th percentile frame time |
| p99FrameMs | float | 99th percentile frame time |
| minFrameMs | float | Minimum frame time |
| maxFrameMs | float | Maximum frame time |
| stdDevFrameMs | float | Standard deviation of frame times |
| avgHitTestMs | float | Mean hit-test latency (0 if no interaction) |
| p95HitTestMs | float | 95th percentile hit-test latency |
| cacheHits | long | Total cache hits across iterations |
| cacheMisses | long | Total cache misses across iterations |
| cacheHitRate | float | hits / (hits + misses) — 0.0 for baseline |
| observedMutationRate | float | Mean observed mutation rate across iterations |
| warmupFrames | int | Adaptive warmup frames used |
| measurementFrames | int | Measurement frames per iteration (500) |
| iterations | int | Number of iterations (3) |
| allocatedMB | float | Max Java heap used (MB) |
| gcInvocations | int | Max GC invocations observed |
| deviceModel | string | e.g., "Pixel 7" or "sdk_gphone64_x86_64" |
| androidVersion | int | SDK int, e.g., 34 |
| isEmulator | bool | true if running on emulator |
| timestamp | string | Run timestamp (YYYYMMDD-HHMMSS) |

## Appendix C: Timeout Adjustments for N=200

The default `RESULT_POLL_TIMEOUT` in `benchmark-runner.sh` is 300 seconds (5 minutes). For
N=200 scenarios on emulator, each measurement frame may take ~450ms, meaning:

```
500 frames × 3 iterations × 0.45s = 675s (~11 minutes)
+ warmup (~500 frames × 0.45s = 225s)
+ cooldowns (2 × 2s = 4s)
= ~904s total (~15 minutes)
```

If N=200 scenarios time out, increase the timeout:

```bash
# Edit benchmark-runner.sh (operational fix, §1.3 allows this)
RESULT_POLL_TIMEOUT=900  # 15 minutes
```

On a physical device, prepare time at N=200 should be significantly faster than on emulator
(~100-200ms vs ~450ms), so the default 5-minute timeout should suffice.
