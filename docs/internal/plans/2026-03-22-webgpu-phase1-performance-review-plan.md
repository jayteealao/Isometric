# 2026-03-22 WebGPU Phase 1 Performance Review Plan

## Goal

Before starting Phase 2, confirm that the current Phase 1 WebGPU integration is
stable enough to build on. The review is intentionally narrow:

- validate sustained runtime stability on device
- measure whether memory allocation and GC behavior are acceptable
- isolate whether remaining cost is CPU-side shared pipeline work or WebGPU-side work
- fix only issues that are clearly Phase 1 blockers

This is not a Phase 2 rendering roadmap, and it is not a broad optimization pass.

---

## Why This Review Comes Before Phase 2

Recent work showed that some changes could reduce OOM risk while still making
performance worse in practice:

- moving async prepare work onto the UI thread reduced one class of memory failure
  but introduced earlier visible jank
- explicit per-frame GC hints prevented runaway heap growth but traded that for
  pacing instability
- shared CPU-path refactors reduced object creation in one layer while reintroducing
  conversions and allocations in another

That means the current system needs a short validation gate before more complexity
is added in Phase 2. If the existing Phase 1 path still has structural allocation
or lifecycle issues, Phase 2 will make them harder to isolate.

---

## Exit Criteria

Proceed to Phase 2 only if all of the following are true:

1. WebGPU samples complete sustained on-device runs without progressive heap growth
   toward failure.
2. GC does not cause recurring visible stalls during normal sample playback.
3. Frame pacing is stable enough that remaining issues are minor and understood.
4. Any remaining hotspots are either:
   - low severity and not blocking further work, or
   - clearly Phase 2-owned rather than Phase 1-owned.

Do not proceed if:

- heap usage trends upward continuously across sustained runs
- the app still enters repeated slow/recover cycles tied to GC or rebuild spikes
- there is evidence of repeated buffer recreation or lifecycle churn that Phase 2
  would amplify

---

## Scope

### In Scope

- current `WebGpuSampleActivity` sample paths
- current shared async prepare path
- current `SceneCache` async rebuild path
- current `IsometricEngine.projectSceneAsync()` path
- current `GpuDepthSorter` buffer, staging, and command submission lifecycle
- sample-specific animation/update patterns when they materially distort backend results

### Out of Scope

- new Phase 2 rendering architecture
- new render backend design
- new shader features unrelated to current depth-sort integration
- speculative cleanups without measured impact

---

## Primary Questions To Answer

1. Is the remaining memory pressure caused primarily by shared CPU-side scene
   rebuild/projection work, or by WebGPU-side sorting and buffer management?
2. Are there still per-frame allocations large enough to trigger visible GC churn
   during sustained runs?
3. Are the current samples representative, or are sample-specific patterns masking
   the real backend behavior?
4. Is the WebGPU path now stable enough to serve as a Phase 2 foundation?

---

## Test Matrix

Use the following samples as the baseline review set:

### Sample Set

- `WebGpuSampleActivity` -> `Smoke` -> `Large`
- `WebGpuSampleActivity` -> `Dense Grid`
- `WebGpuSampleActivity` -> `Animated Towers`

### Comparison Set

Where possible, run equivalent scenes with:

- `ComputeBackend.Cpu`
- `ComputeBackend.WebGpu`

Keep all other scene conditions the same:

- same viewport/device
- same animation state
- same render options unless the backend requires a difference
- gestures disabled when interaction is not being tested

### Run Durations

Use three observation windows per sample:

- short run: 2 minutes
- medium run: 5 minutes
- sustained run: 10 minutes

If a sample clearly regresses before the full window, stop the run and record the
timestamp and symptom.

---

## Metrics To Capture

Collect all metrics with timestamps so they can be correlated.

### Memory

- Java heap used
- heap growth trend over time
- allocation rate
- surviving allocation trend after GC
- old-gen growth if visible in tooling

### GC

- GC frequency
- GC pause timing
- GC duration
- whether visible stutters line up with GC events

### Frame Timing

- frame time distribution
- jank count / missed frames
- visible cadence degradation and recovery windows
- whether the slowdown is smoothness loss or actual simulation-speed loss

### WebGPU / Backend Signals

- backend initialization status
- sort input count / depth key count
- any repeated warnings or lifecycle messages
- any command submission, mapping, or device error logs

---

## Tooling

Use whichever combination gives the clearest correlation on the target device:

### Preferred

- Android Studio Memory Profiler
- Android Studio CPU / System Trace
- Perfetto
- `adb logcat`

### Nice To Have

- frame timeline / jank stats
- `dumpsys meminfo`
- `dumpsys gfxinfo`

---

## Review Procedure

### Step 1: Install And Verify Build

- install current `:app:debug` build on the target device
- confirm the exact commit under test
- confirm the device model and Android version
- close other heavy apps that might contaminate results

Record:

- commit SHA
- device model
- Android version
- date and local time

### Step 2: Baseline Observation Pass

For each baseline sample:

- launch the sample fresh
- observe 30 to 60 seconds without profiler attached
- note visible behavior:
  - smooth
  - gradually degrades
  - intermittent stalls
  - slow/recover cycles
  - crash or ANR

This pass establishes whether profiler overhead is changing the symptoms.

### Step 3: Profiled Runs

Repeat each sample with profiling enabled.

For each run:

1. Start profiler capture.
2. Enter the target sample.
3. Let it run untouched for the planned duration.
4. Mark any visible pacing events with timestamps.
5. Stop capture and save artifacts.

For each run, record:

- sample name
- backend
- duration completed
- whether visible degradation occurred
- exact timestamps for pacing drops, recoveries, or failures

### Step 4: CPU vs WebGPU Isolation

For at least one matched scene pair:

- run under CPU backend
- run under WebGPU backend

Compare:

- heap slope
- allocation rate
- GC timing
- frame pacing

Interpretation:

- if both paths show similar pressure, the issue is probably in shared scene
  collection/projection/caching
- if only WebGPU shows the issue, the problem is likely in sort/buffer lifecycle
- if WebGPU improves CPU cost but still regresses overall, the GPU handoff or
  resource lifecycle is suspect

### Step 5: Code Audit Of Current Hot Path

Audit the following code areas specifically:

- `isometric-compose/.../IsometricScene.kt`
- `isometric-compose/.../IsometricRenderer.kt`
- `isometric-compose/.../SceneCache.kt`
- `isometric-core/.../IsometricEngine.kt`
- `isometric-core/.../DepthSorter.kt`
- `isometric-core/.../HitTester.kt`
- `isometric-webgpu/.../GpuDepthSorter.kt`
- `isometric-webgpu/.../WebGpuComputeBackend.kt`
- `isometric-webgpu/.../GpuContext.kt`

Look for:

- per-frame object creation
- repeated `ArrayList` / `DoubleArray` recreation
- command encoder / pass recreation that could be pooled or reduced
- repeated GPU buffer recreation instead of resizing / reuse
- stale-scene retention extending object lifetime
- extra copies between scene collection and sort input
- hit-test/index work accidentally running for non-interactive samples

### Step 6: Fix Only Phase 1 Blockers

Only implement fixes that meet both conditions:

1. clearly improve a measured problem
2. stay within the current Phase 1 architecture

Examples of acceptable fixes:

- remove repeated transient allocations in async prepare
- keep old-scene lifetime shorter without affecting draw correctness
- reuse or resize GPU buffers instead of recreating them each frame
- remove sample-side churn that distorts profiling of the backend
- eliminate redundant conversion layers in the sort / draw path

Examples of unacceptable fixes for this review:

- starting a new renderer architecture
- introducing large abstraction shifts intended for Phase 2
- speculative rewrites without profiling evidence

### Step 7: Re-Profile After Each Material Fix

Use the same:

- device
- sample set
- durations
- profiling method

Do not accept a fix as a win unless it improves at least one tracked metric without
making pacing worse elsewhere.

---

## Likely Hotspots To Prioritize

Prioritize review in this order:

1. `SceneCache.rebuildAsync()`
   - command collection
   - old scene lifetime
   - path cache rebuild cost

2. `IsometricEngine.projectSceneAsync()`
   - transformed item creation
   - depth key extraction
   - per-face `DoubleArray` creation
   - render command creation

3. `GpuDepthSorter`
   - repeated staging buffer creation
   - per-dispatch buffer lifecycle
   - command encoder / pass churn
   - map/readback overhead

4. Shared geometry utilities
   - any remaining packed-point conversion churn
   - any hidden allocations in sorting or hit testing

5. Sample code
   - animation cadence
   - unnecessary recomposition drivers
   - config differences that make backend comparison noisy

---

## Deliverables

Produce three outputs:

### 1. Profiling Notes

A short report with:

- sample tested
- backend tested
- run duration
- observed behavior
- key metrics
- notable timestamps

### 2. Fix Summary

For each accepted fix:

- problem observed
- evidence
- code changed
- result after re-profile

### 3. Gate Decision

A final recommendation:

- `Proceed to Phase 2`
- `Proceed with caveats`
- `Hold Phase 2 and finish Phase 1 stability work`

Include the reasoning in terms of:

- memory trend
- GC behavior
- pacing stability
- architectural risk

---

## Suggested Pass/Fail Thresholds

These are pragmatic thresholds, not strict production KPIs:

### Pass-Leaning

- no crash or OOM in 10-minute sustained runs
- no repeated visible stall/recovery cycle during normal sample playback
- heap usage reaches a plateau or near-plateau rather than climbing continuously
- GC occurs but is not the dominant visible pacing event

### Fail-Leaning

- visible recurring slowdown/recovery cycles persist
- heap continues climbing across the run with no sign of stabilization
- WebGPU path still depends on fixes that merely trade memory pressure for UI jank
- profiling shows repeated avoidable buffer or command object churn every frame

---

## Recommended Next Move

Run this review before Phase 2.

If the result is clean or nearly clean, move on immediately and avoid over-optimizing
Phase 1. If the result shows structural churn or lifecycle instability, fix those
specific blockers first, then re-run the gate.

That gives the team a measured basis for the Phase 2 decision instead of relying on
sample feel alone.
