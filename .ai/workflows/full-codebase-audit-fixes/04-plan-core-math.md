---
schema: sdlc/v1
type: plan
slug: full-codebase-audit-fixes
slice-slug: core-math
status: complete
stage-number: 4
created-at: "2026-07-07T12:11:09Z"
updated-at: "2026-07-07T12:11:09Z"
metric-files-to-touch: 12
metric-step-count: 18
has-blockers: false
revision-count: 0
tags: [isometric-core, math, geometry, tests, kdoc]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-core-math.md
  siblings:
    - 04-plan-gesture-coordination.md
    - 04-plan-compose-contracts.md
    - 04-plan-view-module.md
    - 04-plan-shape-geometry.md
    - 04-plan-docs-and-changelog.md
    - 04-plan-snapshot-sweep-gate.md
  implement: 05-implement-core-math.md
next-command: wf-implement
next-invocation: "/wf implement full-codebase-audit-fixes core-math"
---

# Plan: Core Math & Geometry

## The Plan

The branch moved since the 2026-07-06 audit — three commits landed in isometric-core alone — and the first thing this plan does is check which of the nine audit items are still broken. The news is mixed: cullPath (A6) is fixed and PathTest already carries the Stairs concave regression case; distanceToSegmentSquared (A2) includes the full 3D dot product; the Point2D overload for hasInteriorIntersection is staged but uncommitted in IntersectionUtils.kt. What is not fixed: rotateX/rotateY KDoc (A1b), the lighten() clamp (A4), normalize KDoc (A5), TileCoordinate.hashCode at ORIGIN (A7), the public inside-test on isPointCloseToPoly (A3), and the two DepthSorter diagnostic tests that still can't fail (F1/F2).

TileCoordinate is the one where the fix landed but didn't finish the job. Current code reads `x * 1_000_003 xor y` — which evaluates to exactly 0 for ORIGIN (0,0). That's the same answer the old code gave; AC-A7 requires ORIGIN.hashCode() != 0. The implementation needs one more step: use the standard 31-multiplier idiom or Objects.hash(x, y), both of which the freshness research confirms are idiomatic Kotlin/JVM for two-field value types.

A3 is the ordering-sensitive one. `isPointCloseToPoly` needs a private edge-only helper extracted first so that `hasIntersection` and `hasInteriorIntersection` keep their current semantics during the transition — the rule is: private helper extraction and callers migrated, run the full IntersectionUtils+DepthSorter suite green, then and only then add the inside-test to the public function. Shipping the public fix first would change the depth-sorter's hit-testing before the regression net is in place.

Every behavioral fix in this slice needs a constructive test: one that demonstrably fails against the pre-fix code. The plan formalizes this as a "red-before-green" step — for A4 and A1a especially, the implementer should write the test, verify it fails against the current (broken) code via a temporary stash of the fix, then apply the fix and confirm green. That's the only way to distinguish a test that proves the defect from a test that was written after the fix and happened to pass.

## Current State

| Finding | Status | Evidence |
|---------|--------|----------|
| A1a — rotateX/rotateY sign | **needs verification** | Current matrices look correct for CCW (Point(0,1,0).rotateX→(0,0,1) would pass current code), but this must be empirically confirmed by running AC-A1a against HEAD. If already correct, only KDoc remains. |
| A1b — CCW/right-handed KDoc | **still-broken** | No `CCW` or `right-handed` mention in rotateX/rotateY/rotateZ KDoc. rotateZ KDoc says only "Rotate about origin on the Z axis." |
| A2 — distanceToSegmentSquared full 3D | **already-fixed** | distanceToSegmentSquared now uses all three coordinates (x,y,z) in both the dot product and the interpolation. PointTest.kt line 97 already has the z-segment test. The test must be verified to fail against the pre-fix 2D code (constructive proof). |
| A3 — isPointCloseToPoly inside-test | **still-broken** | Public function still does edge-only loop only (no ray-casting). No private edge-only helper extracted yet. Uncommitted diff adds Point2D overload of hasInteriorIntersection (unrelated to A3). |
| A4 — lighten() lightness clamp | **still-broken** | lighten() uses `min(newColor.l + percentage, 1.0)` — negative percentages produce l < 0 which maps to black via hslToRgb. KDoc says "(0–1 range, clamped to a maximum lightness of 1.0)" — no lower clamp mentioned. |
| A5 — normalize KDoc zero-vector | **still-broken** | KDoc says "Returns a zero vector if the magnitude is zero." The KDoc is already correct at line 46 of Vector.kt! No code change needed. Only the VectorTest pin is missing. |
| A6 — cullPath full shoelace | **already-fixed** | IsometricProjection.cullPath uses full shoelace formula (commit eea28bb). KDoc documents this. PathTest L4 concave mismatch test is present. |
| A7 — TileCoordinate.hashCode ORIGIN | **partially-fixed (still broken)** | Formula changed to `x * 1_000_003 xor y` but ORIGIN(0,0) → 0 xor 0 = 0. Same bug as before for the origin. `(k, k*1_000_003)` pattern: k*1_000_003 xor k*1_000_003 = 0 (two identical keys collide). Different k,k*1_000_003 pairs: e.g. k=1 gives (1, 1_000_003) → 1*1_000_003 xor 1 = 1_000_002; k=1_000_003 gives (1_000_003, 0) → 1_000_003*1_000_003 xor 1_000_003. Needs a proper fix. |
| F1 — TileGrid 6x6 asserting | **still-broken** | Line 92: "No assertion yet — diagnostic only." The test has classification logic but zero assertions on face presence. |
| F2 — Stack tower findFace guards | **partially-broken** | Stack tower diagnostic wraps assertions in `if (prism2Top >= 0 && prism3Left >= 0)` — if findFace returns -1 (face absent), the assertion is silently skipped. The `Stack tower default draws upper walls after lower top faces` test (line 270) already has `assertTrue(findFace(...) >= 0)` guards — F2 is about the diagnostic test at line 182, which still uses the conditional-skip pattern. |

**Note on A5:** Vector.kt normalize() KDoc at line 46 already reads "Returns a unit vector in the same direction. Returns a zero vector if the magnitude is zero." This is correct. AC-A5 only requires adding the VectorTest pin — no KDoc change needed.

**Note on A1a:** The current rotateX matrices: `newZ = pZ*cos - pY*sin; newY = pZ*sin + pY*cos`. This is the standard CCW rotation matrix about X. Point(0,1,0).rotateX(ORIGIN,PI/2): pY=1, pZ=0 → newZ=0*1-1*0=0, newY=0*0+1*1=1 → gives (0,1,0). That is WRONG — CCW about X should give (0,0,1). So A1a is **still-broken**. The matrices need the flip: `newY = pY*cos - pZ*sin; newZ = pY*sin + pZ*cos`.

## Simplicity Ladder

| Capability | Rung | Decision |
|-----------|------|----------|
| rotateX/Y CCW fix | Rung 4 — new code | Standard rotation matrix sign correction; no stdlib or native equivalent |
| rotateX/Y/Z KDoc convention | Rung 4 — prose addition | No stdlib; docs are hand-authored |
| isPointCloseToPoly inside-test | Rung 3 — reuse | `isPointInPoly` already exists in the same file (ray-casting, line 48); compose with existing edge-only logic rather than reimplementing |
| Private edge-only helper extraction | Rung 3 — reuse/extract | Extract the existing `isPointCloseToPoly` loop body into `isPointCloseToEdges`; then migrate callers |
| lighten() lower clamp | Rung 1 — stdlib | `kotlin.math.max(0.0, ...)` or `Double.coerceIn(0.0, 1.0)` — Kotlin stdlib, no new dependency |
| normalize KDoc | Rung 4 — prose only | No stdlib equivalent; already implemented correctly |
| TileCoordinate hashCode fix | Rung 1 — stdlib | `Objects.hash(x, y)` from java.util.Objects, or the idiomatic Kotlin `31 * x + y` two-field hash. Freshness research confirms `Objects.hash` is the idiomatic JVM pattern for multiple fields. |
| F1 face-count assertion | Rung 3 — reuse | The classification logic already exists in the test; add `assertEquals(48, ...)` and `assertEquals(36, ...)` |
| F2 findFace guard | Rung 3 — reuse | `assertTrue(prism2Top >= 0, ...)` before using the index — one line per call site |

## Applied Learnings

No applicable learnings found. `.ai/solutions/INDEX.md` does not exist in this repository.

Repeat-deferral tripwire: `00-index.md` `runtime-evidence-deferrals` is absent. No prior walls to check.

## Likely Files / Areas to Touch

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Point.kt`: rotateX/rotateY sign + CCW KDoc on all three rotate functions
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`: private edge-only helper extraction + public inside-test
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IsoColor.kt`: lighten() lower clamp + KDoc update
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Vector.kt`: normalize() KDoc (already correct, verify only)
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/TileCoordinate.kt`: hashCode formula fix
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PointTest.kt`: rotateX/Y CCW tests + distanceToSegmentSquared strengthened test
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/VectorTest.kt`: normalize zero-vector pin
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IsoColorTest.kt`: lighten negative-percentage tests
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/TileCoordinateTest.kt`: ORIGIN hashCode != 0 + distribution assertions
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`: isPointCloseToPoly interior-point test
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`: F1 face-count assertions + F2 findFace existence guards
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IsometricProjection.kt`: **no code change** (A6 already fixed); verify PathTest L4 is constructive
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/TileCoordinate.kt` (already mentioned above)

## Proposed Change Strategy

Fix in four coherent commit groups on the shared branch:

1. **A3-first commit (helper extraction + migration):** Extract `isPointCloseToEdges` private helper from `isPointCloseToPoly`. Migrate the three internal callers (`hasIntersection`, `hasInteriorIntersection`, the Point2D overload). Run `./gradlew :isometric-core:test` — must be green. Then add the inside-test to the public function. Then add the IntersectionUtilsTest assertion.

2. **F1/F2 + A6-verification commit:** Add face-count assertions to the TileGrid diagnostic test (F1) and `assertTrue(findFace(...) >= 0)` guards to the Stack tower diagnostic (F2). Verify PathTest L4 (already present) would fail against the pre-fix single-first-3-vertex cullPath — document the constructive proof in a code comment.

3. **A1/A2/A4/A5/A7 fixes + tests commit (or two commits if large):** Fix rotateX/rotateY sign + add CCW KDoc on all three. Strengthen PointTest. Fix lighten() clamp + IsoColorTest. Add VectorTest zero-vector pin. Fix TileCoordinate.hashCode + TileCoordinateTest distribution assertions.

4. **Commit the uncommitted IntersectionUtils.kt diff** (Point2D overload) alongside the A3 commit, since it's already in the working tree.

## Step-by-Step Plan

**Step 1 — Verify A1a against HEAD**
Run `Point(0,1,0).rotateX(ORIGIN, PI/2)` against current code. Based on analysis, current code gives (0,1,0) not (0,0,1) → A1a is still broken. Confirm empirically before writing the test.

**Step 2 — Write rotateX/Y tests first (A1a red)**
Add to PointTest.kt:
```kotlin
@Test fun `rotateX CCW around X axis (right-handed convention)`() {
    val result = Point(0.0, 1.0, 0.0).rotateX(Point.ORIGIN, PI / 2)
    assertEquals(0.0, result.x, 1e-10); assertEquals(0.0, result.y, 1e-10); assertEquals(1.0, result.z, 1e-10)
}
@Test fun `rotateY CCW around Y axis (right-handed convention)`() {
    val result = Point(0.0, 0.0, 1.0).rotateY(Point.ORIGIN, PI / 2)
    assertEquals(1.0, result.x, 1e-10); assertEquals(0.0, result.y, 1e-10); assertEquals(0.0, result.z, 1e-10)
}
```
Run → should be RED against current code. If GREEN, re-analyze — do not skip.

**Step 3 — Fix rotateX/Y matrices (A1a green)**
In Point.kt rotateX: change to `val newY = pY * cosAngle - pZ * sinAngle; val newZ = pY * sinAngle + pZ * cosAngle`.
In Point.kt rotateY: change to `val newZ = pZ * cosAngle - pX * sinAngle; val newX = pZ * sinAngle + pX * cosAngle`.
Verify existing rotateZ test still passes.

**Step 4 — Add CCW KDoc to all three rotate functions (A1b)**
Add to rotateX KDoc: "Positive angles rotate CCW around the positive X axis (right-handed convention): (0,1,0) → (0,0,1) at π/2."
Add to rotateY KDoc: "Positive angles rotate CCW around the positive Y axis (right-handed convention): (0,0,1) → (1,0,0) at π/2."
Add to rotateZ KDoc: "Positive angles rotate CCW around the positive Z axis (right-handed convention): (1,0,0) → (0,1,0) at π/2."

**Step 5 — Verify A2 distanceToSegmentSquared constructive proof**
PointTest already has the z-segment test at line 97. Confirm it would fail against the old 2D-only code (stash the current correct 3D implementation, run test, expect red). If PointTest:97 is already testing the right thing, strengthen with the AC-A2 exact case: segment (0,0,0)→(0,0,2), query (0,0,1), expected 0.0.

**Step 6 — Extract isPointCloseToEdges private helper (A3 prerequisite)**
In IntersectionUtils.kt, add:
```kotlin
private fun isPointCloseToEdges(poly: List<Point>, x: Double, y: Double, radius: Double): Boolean { /* current isPointCloseToPoly body */ }
```
Update `hasIntersection` to call `isPointCloseToEdges` instead of `isPointCloseToPoly`.
Update `hasInteriorIntersection` to call `isPointCloseToEdges` instead of `isPointCloseToPoly`.
Run `./gradlew :isometric-core:test` — must be green (semantics unchanged, pure rename).

**Step 7 — Add inside-test to public isPointCloseToPoly (A3)**
Modify public `isPointCloseToPoly` to: first check `isPointInPoly(poly, x, y)` → return true; then iterate edges via existing loop.
Add to IntersectionUtilsTest.kt:
```kotlin
@Test fun `isPointCloseToPoly returns true for strictly interior point`() {
    val poly = listOf(Point(-10.0,-10.0,0.0), Point(10.0,-10.0,0.0), Point(10.0,10.0,0.0), Point(-10.0,10.0,0.0))
    assertTrue(IntersectionUtils.isPointCloseToPoly(poly, 0.0, 0.0, 0.1))
}
```
Run IntersectionUtils + DepthSorterTest suites. All must pass.

**Step 8 — F1: Add TileGrid 6x6 face-count assertions**
Replace the "No assertion yet" comment in DepthSorterTest with:
```kotlin
assertEquals(48, sceneDefault.commands.size, "Default 6x6 TileGrid must produce exactly 48 exterior faces")
assertEquals(36, topsDefault.size, "All 36 tile tops must be present")
assertTrue((expected - topsDefault).isEmpty(), "No top face may be missing: missing=${expected - topsDefault}")
```

**Step 9 — F2: Add findFace existence guards to Stack tower diagnostic**
In the `diagnostic - Stack tower default render face order and overlap check` test, before each conditional assertion block, add:
```kotlin
assertTrue(prism2Top >= 0, "YELLOW(2) TOP face must exist in scene commands")
assertTrue(prism3Left >= 0, "RED(3) LEFT face must exist in scene commands")
assertTrue(prism3Front >= 0, "RED(3) FRONT face must exist in scene commands")
```
Remove the `if (prism2Top >= 0 && ...)` wrappers so the ordering assertions always run.

**Step 10 — Fix lighten() lightness clamp (A4)**
In IsoColor.kt lighten(), change:
```kotlin
val newLightness = min(newColor.l + percentage, 1.0)
```
to:
```kotlin
val newLightness = (newColor.l + percentage).coerceIn(0.0, 1.0)
```
Update KDoc to: "@param percentage Amount to add to the lightness component (range [-1, 1]); the result is clamped to [0, 1]."

**Step 11 — Write lighten negative-percentage test (A4 constructive proof)**
Add to IsoColorTest.kt (write BEFORE the fix; verify RED, then apply fix):
```kotlin
@Test fun `lighten with negative percentage does not produce zero RGB channels`() {
    val blue = IsoColor(10, 10, 80)  // dark blue, low lightness
    val result = blue.lighten(-0.20, IsoColor.WHITE)
    assertTrue(result.r > 0 || result.g > 0 || result.b > 0, "lighten(-0.20) must not produce black")
    assertTrue(result.l >= 0.0, "lightness must be >= 0")
}
@Test fun `lighten boundary: percentage exactly minus one produces minimum lightness`() {
    val color = IsoColor(128, 64, 32)
    val result = color.lighten(-1.0, IsoColor.WHITE)
    assertEquals(0.0, result.l, 1e-10, "lighten(-1.0) should produce lightness 0")
}
```

**Step 12 — Add VectorTest zero-vector pin (A5)**
Add to VectorTest.kt:
```kotlin
@Test fun `normalize of zero vector returns zero vector`() {
    assertEquals(Vector(0.0, 0.0, 0.0), Vector(0.0, 0.0, 0.0).normalize())
}
```
This should already pass (implementation correct) — it's a constructive pin, not a defect test. Run to confirm green.

**Step 13 — Fix TileCoordinate.hashCode (A7)**
Replace `override fun hashCode(): Int = x * 1_000_003 xor y` with:
```kotlin
override fun hashCode(): Int {
    var result = x
    result = 31 * result + y
    return result
}
```
Verify: `TileCoordinate(0,0).hashCode()` = 31 * 0 + 0 = 0. Still zero! Must use a better formula.
Use: `override fun hashCode(): Int = 31 * (x + 1) + y` or `java.util.Objects.hash(x, y)`.
`Objects.hash(x, y)` → internally uses Arrays.hashCode([x, y]) = 31 * (31 * 1 + x) + y = 961 + 31*x + y. For ORIGIN: 961 + 0 + 0 = 961 ≠ 0. For (1, 1_000_003): 961 + 31 + 1_000_003 = 1_000_995. For (1_000_003, 1): 961 + 31_000_093 + 1 = 31_001_055. No collision. Use `Objects.hash(x, y)`.

**Step 14 — Write TileCoordinateTest distribution assertions (A7 constructive proof)**
Add to TileCoordinateTest.kt (write BEFORE the fix; verify against old formula; confirm RED for ORIGIN test):
```kotlin
@Test fun `hashCode of ORIGIN is not zero`() {
    assertNotEquals(0, TileCoordinate.ORIGIN.hashCode(), "ORIGIN.hashCode() must not be zero")
}
@Test fun `hashCode distributes k vs k-times-1_000_003 patterns`() {
    for (k in 1..5) {
        val a = TileCoordinate(k, k * 1_000_003)
        val b = TileCoordinate(k * 1_000_003, k)
        assertNotEquals(a.hashCode(), b.hashCode(), "($k, ${k*1_000_003}) and (${k*1_000_003}, $k) must have different hashCodes")
    }
}
```

**Step 15 — Commit the uncommitted IntersectionUtils.kt working-tree changes**
The Point2D overload of hasInteriorIntersection is already correct in the working tree but uncommitted. Stage and commit it alongside the A3 changes in Step 6–7.

**Step 16 — Run full suite**
`./gradlew :isometric-core:test` — all tests green.

**Step 17 — Verify constructive proofs documented**
For each behavioral fix (A1a, A3, A4, A7), confirm the test name or a comment in the test file notes that it was verified to fail against the pre-fix implementation.

**Step 18 — Commit message hygiene**
One commit per logical fix group per the sequencing above. Conventional-commit format:
- `fix(core): correct rotateX/rotateY direction to CCW right-handed convention (A1)` 
- `fix(core): extract edge-only helper; add interior test to isPointCloseToPoly (A3)`
- `fix(core): clamp lighten() lightness to [0,1] to prevent black back-lit faces (A4)`
- `fix(core): correct TileCoordinate.hashCode for ORIGIN and linear patterns (A7)`
- `test(core): convert F1/F2 diagnostic tests to asserting tests`

## Verification Strategy

| AC | Tool / method + ladder rung | Environment need — satisfiable? | What must be built | Fallback chain |
|----|------------------------------|----------------------------------|-------------------|----------------|
| AC-A1a (rotateX/Y CCW) | JUnit `assertEquals` in PointTest (`jvm-unit`) | JVM — yes | New PointTest cases that must fail pre-fix | Static reasoning → ILLEGAL; rung 1 JVM-unit is sufficient |
| AC-A1b (KDoc CCW prose) | Manual read-through at review + `dokka` builds clean (`manual-review`) | Local Gradle — yes | No fixture needed; human judgment + `./gradlew dokkaHtml` | n/a — prose is irreducibly manual |
| AC-A2 (distanceToSegmentSquared 3D) | JUnit `assertEquals` in PointTest (`jvm-unit`) | JVM — yes | Strengthen existing PointTest:97; verify fails pre-fix via stash | n/a |
| AC-A3 (isPointCloseToPoly interior + regression) | JUnit `assertTrue`/`assertFalse` in IntersectionUtilsTest + DepthSorterTest (`jvm-unit`) | JVM — yes | IntersectionUtilsTest interior assertion + private-helper migration | n/a |
| AC-A4 (lighten clamp) | JUnit property-style in IsoColorTest (`jvm-unit`) | JVM — yes | IsoColorTest negative-percentage and boundary tests | n/a |
| AC-A5 (normalize KDoc + zero pin) | VectorTest `assertEquals` + manual KDoc read (`jvm-unit` + `manual-review`) | JVM — yes | VectorTest zero-vector case | n/a |
| AC-A6 (cullPath full shoelace) | PathTest L4 concave-mismatch case (`jvm-unit`) | JVM — yes | Already present in PathTest; verify constructive proof via stash | n/a |
| AC-A7 (hashCode distribution) | JUnit in TileCoordinateTest (`jvm-unit`) | JVM — yes | ORIGIN != 0 test + k/k*1_000_003 collision test | n/a |
| AC-F1 (TileGrid face assertions) | JUnit `assertEquals` in DepthSorterTest (`jvm-unit`) | JVM — yes | Replace "No assertion" comment with face-count asserts | n/a |
| AC-F2 (findFace guards) | JUnit `assertTrue(findFace(...) >= 0)` in DepthSorterTest (`jvm-unit`) | JVM — yes | Add existence assertions before ordering assertions | n/a |

All ACs in this slice are `observable: false` — pure JVM unit assertions. No emulator, no device, no interactive verification required. The one residual manual-review AC (AC-A1b, AC-A5 KDoc prose) is `constraint-resolution: po-accepted: prose accuracy is human-judged at review stage; dokka build clean is the automated gate`.

**Force-scope rule:** No AC in this slice depends on a credential, device, or external service. No force-scope resolution needed.

## Test / Verification Plan

### Automated checks

- `./gradlew :isometric-core:test` — covers all Zone A + F1/F2 ACs in this slice
- All new and strengthened tests must be green on HEAD after fixes applied
- `./gradlew :isometric-core:test apiCheck` — must be green (hashCode change in TileCoordinate may affect binary compatibility dump; verify with apiDump first)

### Interactive verification (human-in-the-loop)

**AC-A1b and AC-A5 KDoc prose:**
- What to verify: KDoc on rotateX/rotateY/rotateZ states CCW right-handed convention; normalize() KDoc states zero-vector return
- Platform & tool: Local IDE read-through; `./gradlew :isometric-core:dokkaHtml` confirms Dokka parses KDoc without errors
- Steps: Open Point.kt, read each rotate function's KDoc; open Vector.kt, read normalize() KDoc
- Pass criteria: Each rotate function names the CCW right-handed convention with a concrete example (e.g., "(0,1,0) → (0,0,1) at π/2"); normalize KDoc states "Returns Vector(0,0,0) when the magnitude is zero"

## Risks / Watchouts

- **A1 rotation sign:** The matrices look correct-for-CCW on careful analysis but have not been empirically verified against the pre-audit code. Do NOT assume the audit was wrong — verify against AC-A1a before skipping the fix.
- **A7 hashCode ORIGIN still 0:** The `x * 1_000_003 xor y` formula does not fix the ORIGIN case. Use `Objects.hash(x, y)` which produces 961 for (0,0).
- **A3 ordering constraint:** Violating the helper-extraction-before-public-fix order breaks depth-sort correctness. This is the single hardest ordering dependency in the slice.
- **F1/F2 new assertions may expose real failures:** If the TileGrid 6x6 scene does not actually produce 48 faces under current code, the F1 assertion will fail — that is a finding to fix, not to relax.
- **Uncommitted IntersectionUtils.kt diff:** The Point2D overload is in the working tree but not staged. Commit it in the same step as A3 to keep the diff coherent.
- **IsoColorTest.kt uncommitted additions:** The `withAlpha` tests are uncommitted. Commit them alongside A4 or as a separate step before starting.

## Dependencies on Other Slices

- None. This slice has no dependencies on other slices and is the recommended starting point.
- `shape-geometry` slice (B1/B2/B3) depends on this slice's `Point.rotateX/Y` fix being correct before Octahedron geometry is re-verified.
- `docs-and-changelog` slice (E-zone) depends on KDoc changes from this slice to generate correct doc mirrors.

## Assumptions

- The branch is on `feat/ws10-interaction-props`; all commits are on this shared branch.
- `./gradlew :isometric-core:test` runs in < 60 seconds locally (pure JVM, no device needed).
- `Objects.hash(x, y)` is available (java.util.Objects is in the JDK; no additional dependency).
- The uncommitted IntersectionUtils.kt diff (Point2D overload) was intentional and should be committed as part of this slice.
- The uncommitted IsoColorTest.kt diff (`withAlpha` tests) should be committed as part of this slice.
- `apiDump` will be run after any public API signature change (TileCoordinate.hashCode is an implementation-only change and should not affect the .api dump, but must be verified).

## Blockers

None. All implementation decisions are settled in the slice definition and po-answers.md.

## Freshness Research

**Topic: Kotlin idiomatic hashCode for two Int fields**
- Source: Kotlin stdlib source + JetBrains coding conventions (July 2026)
- Relevance: AC-A7 requires a hashCode formula that doesn't return 0 for ORIGIN
- Takeaway: The idiomatic Kotlin pattern for two-field hash is `31 * x + y` or `java.util.Objects.hash(x, y)`. The `data class` compiler-generated hashCode uses `31 * x.hashCode() + y.hashCode()` (same as the 31-multiplier pattern). For two `Int` fields where `Int.hashCode() == Int.toInt()`, this becomes `31 * x + y`. For ORIGIN: 31 * 0 + 0 = 0 — still zero! `Objects.hash(x, y)` uses `Arrays.hashCode([x, y])` = `31 * (31 * 1 + x) + y` = `961 + 31*x + y`. For ORIGIN: 961 ≠ 0. Recommendation: use `Objects.hash(x, y)` (import java.util.Objects).

**Topic: Kotlin stdlib coerceIn for Double clamping**
- Source: Kotlin stdlib docs, kotlinlang.org (July 2026)
- Relevance: AC-A4 lightness clamp — replace `min(x, 1.0)` with full lower+upper clamp
- Takeaway: `Double.coerceIn(minimumValue, maximumValue)` is available in Kotlin stdlib since 1.0, returns value clamped to [min, max]. `(newColor.l + percentage).coerceIn(0.0, 1.0)` is idiomatic; no import needed (stdlib). Prefer over `max(0.0, min(1.0, ...))`.

**Topic: CCW right-handed rotation matrix conventions**
- Source: Wikipedia — Rotation matrix; OpenGL/graphics convention references (July 2026)
- Relevance: AC-A1a — verify the correct matrix for CCW rotation about each axis
- Takeaway: For CCW rotation about X by angle θ (right-handed, active rotation): y' = y*cos(θ) - z*sin(θ); z' = y*sin(θ) + z*cos(θ). For CCW about Y: z' = z*cos(θ) - x*sin(θ); x' = z*sin(θ) + x*cos(θ). Current Point.kt rotateX has: newZ = pZ*cos - pY*sin; newY = pZ*sin + pY*cos — this is y' = z*sin + y*cos (WRONG), z' = z*cos - y*sin (WRONG direction). The fix swaps to the standard form above.

**Topic: Java/Kotlin Objects.hash vs manual hash**
- Source: java.util.Objects javadoc; Kotlin data class compiler output (July 2026)
- Relevance: AC-A7 — confirm Objects.hash is acceptable vs. rolling own
- Takeaway: `java.util.Objects.hash(vararg values: Any?)` is the standard Java/JVM idiom for hashing multiple fields. Kotlin data classes use the same underlying algorithm. No security or performance concern for a 2D tile coordinate. Confirmed acceptable.

## Revision History

*(appended by review-and-fix mode)*

## Recommended Next Stage

- **Option A (default):** `/wf implement full-codebase-audit-fixes core-math` — all findings classified, step-by-step plan is execution-ready, no blockers. Consider running `/compact` first — planning research is noise for implementation, and workflow state lives in the artifact files.
- **Option B:** `/wf implement full-codebase-audit-fixes shape-geometry` — if another agent is implementing this slice in parallel; shape-geometry has no dependencies on core-math being complete first (it depends on the underlying math being correct, not on the slice being finished as a unit).
