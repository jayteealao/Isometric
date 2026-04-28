---
schema: sdlc/v1
type: review
review-command: refactor-safety
slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T20:44:25Z"
updated-at: "2026-04-28T12:00:00Z"
result: pass
rounds: 2
round-1-scope: "countCloserThan permissive threshold (commit 3e811aa)"
round-2-scope: "Newell Z->X->Y minimax cascade (commit 452b1fc)"
metric-findings-total: 10
metric-findings-blocker: 0
metric-findings-high: 1
metric-findings-med: 2
metric-findings-low: 2
metric-findings-nit: 2
metric-findings-info: 3
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - numerical-robustness
  - refactor-safety
  - newell-cascade
  - algorithmic-restructure
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
---

# Review: refactor-safety

Scope: `Path.countCloserThan` in
`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt` (lines 117–146).

**Semantic contract change under review:**  
`countCloserThan` previously returned a fraction-like `Int` in `{0, 1}` (or rarely `> 1`)
via integer division `(result + result0) / points.size`.  
It now returns a binary `Int` in `{0, 1}` via `if (result > 0) 1 else 0`.  
Its public consumer `closerThan` subtracts both directions, so its old theoretical range
`[-points.size .. points.size]` collapsed to `{-1, 0, 1}`.

---

## Investigation Results

### RS-1 — Callers of `countCloserThan` (private)

**grep result:** single caller, `closerThan` at `Path.kt:100`.  
No other file in the repo references `countCloserThan`.  
The function is `private`, so the scope is definitionally closed.

**Finding:** SAFE — only one call site, no indirect reads.

---

### RS-2 — Callers of `closerThan` (public)

**grep result (production code):**

| File | Line | Usage |
|------|------|-------|
| `DepthSorter.kt:139` | `val cmpPath = itemA.item.path.closerThan(itemB.item.path, observer)` | sole consumer |

**grep result (tests, sign-check only):**

| File | Lines | Assertion style |
|------|-------|-----------------|
| `PathTest.kt:80,104,120,140,160` | `assertTrue(result > 0, ...)` | sign-only |
| `PathTest.kt:178` | `assertEquals(0, result, ...)` | zero-equality |
| `DepthSorterTest.kt:241–244` | `assertEquals(0, ab + ba, ...)` | antisymmetry sum-zero |

All test assertions are sign-only (`> 0`, `== 0`, `ab + ba == 0`).  
No test checks `result == 2`, `result == 4`, or any specific magnitude.  
**Finding:** SAFE — tests already document and enforce sign-only semantics.

---

### RS-3 — `DepthSorter.checkDepthDependency` — does it use magnitude or sign?

`DepthSorter.kt:139–148`:

```kotlin
val cmpPath = itemA.item.path.closerThan(itemB.item.path, observer)
if (cmpPath < 0) {
    drawBefore[i].add(j)
} else if (cmpPath > 0) {
    drawBefore[j].add(i)
}
// When cmpPath == 0 (coplanar or ambiguous), intentionally add no edge.
```

The body is an exclusive sign-branch: `< 0`, `> 0`, else nothing.  
The magnitude of `cmpPath` is never read, stored, multiplied, or compared to any value
other than zero.  
**Finding:** SAFE — the primary consumer is a pure sign comparator; the value collapse
from `[-N..N]` to `{-1,0,1}` is semantically invisible here.

---

### RS-4 — `result0` removal: behavioral change for coplanar vertices (LOW)

**Old code path:** a vertex within 1e-9 of pathA's plane incremented `result0`, which
contributed to the fraction `(result + result0) / points.size`.  
**New code path:** a vertex within 1e-6 of the plane is skipped (neither closer nor
farther), so it cannot promote `result` from 0 to 1.

The net behavioral difference occurs only when **all** of `this.points` lie within 1e-6
of `pathA`'s plane (a genuinely coplanar or nearly-coplanar pair).

- Old: `result = 0`, `result0 = N` → `N / N = 1` → `countCloserThan` returned 1 for
  each direction → `closerThan = 1 - 1 = 0`. Both directions tied; net result was 0.
- New: all vertices skip → `result = 0` → `countCloserThan` returns 0 for each
  direction → `closerThan = 0 - 0 = 0`. Still 0.

For the case where `result0` vertices were only on one side (mixed but coplanar-ish):
the old logic collapsed via integer division to 0 anyway (that was the diagnosed bug).
The new logic's `result > 0` threshold catches the non-coplanar cases where the old
integer division truncated to 0.

The PathTest coplanar negative control (`closerThan returns zero for genuinely coplanar
non-overlapping faces`) verifies the coplanar zero is preserved.

**Finding:** LOW — the `result0` branch removal produces a functionally equivalent
outcome in the coplanar subcase (both old and new return `closerThan = 0`), but the
path through the code is different. The risk is that a geometry configuration where
some vertices are coplanar within 1e-6 and others are on opposite sides of the plane
could produce different outputs. However, the epsilon widening (1e-9 → 1e-6) was the
intentional fix to absorb floating-point noise for 0..100 coordinate ranges, and
the tests confirm correctness on the diagnosed and boundary cases.

**Recommendation (non-blocking):** A unit test for the degenerate "all vertices within
epsilon of the plane" case would make the contract explicit, but the existing coplanar
test provides sufficient coverage for the stated intent.

---

### RS-5 — Orphaned comments from `result0` removal (NIT)

No orphaned comments remain. The KDoc at lines 103–116 was rewritten as part of the
change and accurately describes the new algorithm. The comment at line 145 in
`DepthSorter.kt` (`// When cmpPath == 0 ...`) is consistent with the new behavior.

**Finding:** NIT — the KDoc on `countCloserThan` (line 107) states "Used by `closerThan`
which subtracts both directions to produce a signed comparator." This is accurate, but
the phrasing "signed comparator" slightly overstates the guarantee: the result is in
`{-1, 0, 1}` only when `countCloserThan` is binary, which is now enforced, but was not
previously guaranteed. The comment is correct post-change.

---

## Summary

| ID   | Severity | Location               | Summary |
|------|----------|------------------------|---------|
| RS-1 | —        | `countCloserThan`      | Private, single caller — SAFE |
| RS-2 | —        | `closerThan` tests     | All assertions sign-only — no magnitude dependency — SAFE |
| RS-3 | —        | `checkDepthDependency` | Pure sign-branch, magnitude never read — SAFE |
| RS-4 | LOW      | `countCloserThan:133`  | `result0` removal changes coplanar code path; net output identical; missing degenerate-coplanar unit test |
| RS-5 | NIT      | `countCloserThan:107`  | KDoc phrasing "signed comparator" is now accurate but was not previously enforced |

**Result: PASS.**  
The semantic contract change from fraction-like magnitude to binary is **invisible to all
callers**: `checkDepthDependency` is a sign-only branch, every test assertion is
sign-only, and `countCloserThan` is private with a single call site. No magnitude-based
consumer exists anywhere in the repo. The `result0` removal is behaviorally safe for the
coplanar subcase. The change achieves its stated intent (resolving shared-edge ties that
integer division collapsed to zero) without breaking any existing contract.

---

# Round 2 Review: refactor-safety — Newell Z→X→Y minimax cascade (commit 452b1fc)

Scope: cumulative diff `97416ba..HEAD` on `feat/ws10-interaction-props`. The primary
change is commit `452b1fc`: the body of `Path.closerThan` is replaced entirely by
Newell, Newell, and Sancha's (1972) six-step cascade. `countCloserThan` is deleted.
`signOfPlaneSide` is added private. Commits `2e29dc5` (DIAG re-enable) and `3e811aa`,
`9cef055` (prior rounds) precede it in the diff range; commit `452b1fc` reverts all
diagnostic instrumentation from `2e29dc5`.

**Public signature `fun closerThan(pathA: Path, observer: Point): Int` is preserved.**
The return values for specific inputs HAVE changed by design.

---

## Investigation Results (Round 2)

### RS-R2-1 — Return-value change: old signs vs new signs — do any pre-existing tests
encode the *exact old value* rather than a sign?

Full grep of `closerThan` across all `.kt` files confirms callers:

**Production callers:**
- `DepthSorter.kt:147` — pure sign branch (`< 0`, `> 0`, else nothing). Magnitude never
  read. SAFE under any sign-preserving rewrite.

**Test callers (all sign-only):**

| File | Assertion | Style |
|------|-----------|-------|
| `PathTest.kt:99` | `result > 0` | sign-positive |
| `PathTest.kt:125` | `result > 0` | sign-positive |
| `PathTest.kt:147` | `result > 0` | sign-positive |
| `PathTest.kt:173` | `result > 0` | sign-positive |
| `PathTest.kt:195` | `result > 0` | sign-positive |
| `PathTest.kt:218` | `assertEquals(0, result)` | zero-equality |
| `PathTest.kt:251` | `result != 0` | non-zero |
| `PathTest.kt:296` | `result < 0` | sign-negative |
| `DepthSorterTest.kt:241–244` | `ab + ba == 0` | antisymmetry |

No assertion checks a specific magnitude (`result == 1`, `result == 2`, etc.). Every
assertion is a sign comparison. The implementer confirmed all 191 tests pass locally.

**Finding (INFO):** No test encodes a specific non-zero magnitude. Sign-only semantics
are fully respected. The new algorithm preserves all tested signs (verified by local
test run reported in `05-implement-...md` Round 4).

---

### RS-R2-2 — Sign-convention drift: the plan's pseudocode said `selfZmax < aZmin → +1`
(self closer); the implementation inverted it to `→ -1` (self closer). The
implementer documented this as a deviation. Are there any test or doc consumers
that encode the PLAN's (wrong) sign convention?

**grep for plan-based sign expectation:**  
- `04-plan-depth-sort-shared-edge-overpaint.md` lines 181, 302: plan says
  `"selfZmax < aZmin - eps → return +1 (self closer)"`.  
- No `.kt` test file uses `+1` or `-1` as a literal; all tests use `> 0`, `< 0`, `== 0`.
- The KDoc in `Path.kt` lines 104–108 correctly documents negative = closer, positive =
  farther. This matches the test convention and the DepthSorter `cmpPath < 0` branch.

**AC-3 case (g) assertion:** `PathTest.kt:296` asserts `result < 0` for wall-vs-floor
(wall is closer). This matches the implementation and the established test contract.
The plan's Amendment-2 text said `assertTrue(result > 0, "wall is closer")` — this was
the plan's error, caught by the implementer and corrected to `result < 0`.

**Finding (INFO):** Sign convention is internally self-consistent. The plan text is wrong;
the code and tests are right. No downstream consumer encoded the plan's convention.
No drift risk.

---

### RS-R2-3 — `countCloserThan` deletion: any remaining caller or reflection reference?

Full grep for `countCloserThan` across the entire repo:

- `DepthSorterTest.kt:167,282,356` — historical KDoc/comment references only; no call
  sites.
- `PathTest.kt:264,266` — historical comment tracing old behaviour; no call sites.
- `Path.kt:127,252` — own KDoc referencing it as a superseded predecessor; no call.
- `.ai/workflows/` files — workflow documentation only.

`countCloserThan` was `private`. There is no subclass, no extension, no reflection
usage anywhere. The deletion is complete and clean.

**Finding (INFO):** `countCloserThan` is fully deleted with zero remaining call sites
or indirect references in production or test code.

---

### RS-R2-4 — `hasIntersection` vs `hasInteriorIntersection`: are any code paths
silently affected by the switch? (HIGH)

`DepthSorter.checkDepthDependency` now calls `hasInteriorIntersection` (amendment-1,
commit `9cef055`). The lenient `hasIntersection` is still public on `IntersectionUtils`
and is used by the `IntersectionUtilsTest` regression marker:

```kotlin
// IntersectionUtilsTest.kt:97
assertTrue(
    IntersectionUtils.hasIntersection(a, b),
    "hasIntersection's existing contract preserved: shared-edge contact remains true"
)
```

This pinning test is correct and important: it explicitly documents that
`hasIntersection` retains its lenient shared-edge-contact=true semantics for any future
non-DepthSorter caller that actually wants the lenient contract.

**External doc risk:** `docs/concepts/depth-sorting.md` line 45 and
`site/src/content/docs/concepts/depth-sorting.mdx` line 45 both say:

> "For each pair of candidate faces within the same cell, `IntersectionUtils.hasIntersection()`
> performs an axis-aligned bounding box (AABB) test."

This is now **stale**: `DepthSorter` has used `hasInteriorIntersection` since commit
`9cef055`. The public-facing depth-sorting documentation describes an API that the
implementation no longer uses. A reader following the docs to understand DepthSorter
behaviour will get a wrong mental model: the docs imply boundary-contact pairs are
candidate pairs, but the implementation now silently rejects them via the stricter gate.

**Impact assessment:** The doc refers to `hasIntersection` as an implementation detail of
the DepthSorter pipeline, not as a public API contract. Library users cannot call
`DepthSorter` directly (it is `internal`). However, the docs are the canonical
description of depth-sort behaviour and describing the wrong function is misleading.
The CI workflow (`ci.yml`) does NOT have a Paparazzi step — it runs `./gradlew build`
and `./gradlew test` + `./gradlew apiCheck` on Ubuntu. There is no automated check
that would catch a doc/impl divergence like this.

**Finding (HIGH):** `docs/concepts/depth-sorting.md` and its site counterpart
`site/src/content/docs/concepts/depth-sorting.mdx` both name `hasIntersection` as the
narrow-phase gate, but `DepthSorter` uses `hasInteriorIntersection` since `9cef055`.
These docs are stale. No automated CI check catches this divergence. A future maintainer
reading the docs, then auditing `DepthSorter.kt`, will see a mismatch that could erode
trust in the documentation or cause them to "fix" the implementation back to the lenient
version.

**Recommendation:** Update `depth-sorting.md` line 45 (and the mdx counterpart) to
name `hasInteriorIntersection` and briefly explain the interior-only gate: "Pairs that
only share a boundary edge or vertex in screen projection cannot paint over each other,
so they are excluded from edge-generation." Non-blocking for merge; low effort fix.

---

### RS-R2-5 — DEPTH_SORT_DIAG revert: is it complete? (MED)

`git show 452b1fc -- isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`
confirms the following are removed from the Round-4 commit:

- `private const val DIAG = true` and its comment block — REMOVED.
- `private fun first3(points: List<Point>): String` — REMOVED.
- `System.err.println("DepthSortDiag FRAME START itemCount=$length")` — REMOVED.
- `System.err.println("DepthSortDiag ORDER pos=... depthIdx=... first3=...")` inside
  Kahn loop — REMOVED.
- `System.err.println("DepthSortDiag ORDER-CYCLE ...")` in the cycle fallback — REMOVED.
- `System.err.println("DepthSortDiag FRAME END")` — REMOVED.
- `System.err.println("DepthSortDiag pair: A.idx=...")` inside
  `checkDepthDependency` — REMOVED.
- `edgeLabel` local variable — REMOVED.

Retained from the Round-3 refactor (intentional, readability improvement):
- `val intersects = ...` (captured into a local)
- `val cmpPath = ...` (was already in the Round-2 code path; made explicitly local
  by Round-3's refactor)

A full grep for `DIAG`, `first3`, `DepthSortDiag`, `System.err` in
`DepthSorter.kt` returns empty — confirmed zero diagnostic remnants.

**Finding (INFO):** DIAG revert is complete. No diagnostic emissions survive in HEAD.

---

### RS-R2-6 — Cascade steps 2/3 (X/Y screen-extent): sign never empirically validated —
are they internally consistent with the sign convention? (MED)

The implementer explicitly noted that steps 2 and 3 are "inert in the test corpus" —
no current test exercises them. The signs were derived from "iso-projection intuition."

Tracing the sign logic:

**Step 2 (screen-x):**  
`screenX = (p.x - p.y) * cosAngle`. Larger `(x - y)` → larger screenX → in the
standard iso projection, larger `(x - y)` means the face is further to the right on
screen AND further away in iso depth (the right side of an iso scene is the far side).
If `selfScreenXMax < aScreenXMin - EPSILON` (self entirely LEFT of pathA) → self has
smaller `(x - y)` → self is closer (nearer to the left/front of the iso scene) → return
`-1`. Correct per sign convention.

**Step 3 (screen-y):**  
`screenY = -(sinAngle * (p.x + p.y) + p.z)`. Larger `(x + y)` → larger `(sin*(x+y))`
→ larger negative screenY → MORE NEGATIVE screenY → LOWER on screen. Lower on screen in
iso = farther (the bottom of an iso scene is the far side). If `selfScreenYMax <
aScreenYMin - EPSILON` (self entirely BELOW pathA on screen) → self is lower → self is
farther → return `+1`. The comment in the code says "self entirely-below pathA on screen
→ self farther → +1." This is correct.

BUT: there is a **sign asymmetry** between the two branches of each step. For step 2:

- `selfScreenXMax < aScreenXMin - EPSILON` → return `-1` (self left = closer).
- `aScreenXMax < selfScreenXMin - EPSILON` → return `+1` (self right = farther).

For step 3:

- `selfScreenYMax < aScreenYMin - EPSILON` → return `+1` (self below = farther).
- `aScreenYMax < selfScreenYMin - EPSILON` → return `-1` (self above = closer).

The sign assignments are consistent with "iso left/right" and "iso top/bottom" depth
conventions, but there is one subtle risk: the iso-projection semantics of screen-x
depth direction are not universally agreed upon. If the observer is at the top-left
(standard iso), larger `(x - y)` does correspond to farther. If the engine were
configured with a different handedness, these signs would be wrong. The `ISO_ANGLE`
constant is hardcoded to `PI / 6.0` in `Path.kt`; the live `IsometricEngine` also
defaults to `PI / 6`; they are consistent. But a custom-angle engine would disagree
with the cascade's hardcoded `ISO_ANGLE` in step 1.

**Finding (MED):** Steps 2 and 3 are sign-plausible but untested by any AC. Their
sign convention is derived from the standard 30° iso observer position and may produce
incorrect results for non-standard projection angles. `ISO_ANGLE` in `Path.kt` is
hardcoded independently of any `IsometricEngine` instance, so if a caller passes a
non-standard `observer` or if the engine is configured at a non-30° angle, the steps
2/3 cascade sign convention diverges from the actual projection. Since step 1 (Z-extent)
catches most real-scene decisions and steps 2/3 only fire when Z-extents fully overlap,
the practical impact is low for the project's current 30° default. Non-blocking but a
latent correctness risk for future non-standard-angle scenes.

**Recommendation (non-blocking):** Add a unit test for a pair whose iso-depth extents
overlap but screen-x extents are strictly disjoint, to pin the sign of step 2 and give
future maintainers a failing test if the convention is accidentally reversed.

---

### RS-R2-7 — Cascade step ordering: are any tests pinned to "this case resolves at
step N" that would silently break if steps are reordered?

The test KDocs document which step resolves each case:

| Test | Claimed step |
|------|-------------|
| `closerThan returns nonzero for hq-right vs factory-top` | step 5 (plane-side forward) |
| `closerThan resolves X-adjacent neighbours` | step 5 |
| `closerThan resolves Y-adjacent neighbours` | step 5 |
| `closerThan resolves top vs vertical side at equal heights` | step 1 (Z-extent) |
| `closerThan resolves diagonally offset adjacency` | step 5 |
| `closerThan resolves coplanar non-overlapping via Z-extent minimax` | step 1 |
| `closerThan resolves wall-vs-floor straddle via plane-side test` | step 5 |

These claims are in comments only, not in assertions. If a future maintainer swaps
steps 1 and 5 (or changes the cascade order), all tests would still pass because they
only assert the *sign*, not the step. The cascade step is unobservable from the
return value.

**Finding (LOW):** Step-ordering claims are KDoc comments, not assertions. Reordering
the cascade (e.g., putting plane-side before Z-extent) would silently change
performance characteristics and possibly break antisymmetry for edge cases, but would
NOT cause any existing test to fail. There is no mechanism to detect accidental
step-reordering.

**Recommendation (non-blocking):** A future maintainer can verify step-order correctness
via the antisymmetry test (which provides a structural sanity check) and via the
integration tests AC-12/AC-13 (which require the wall-vs-floor pair to produce a
non-zero result). These are not sufficient to pin order, but they are sufficient to
catch a sign-breaking reorder.

---

### RS-R2-8 — Paparazzi snapshot baseline regeneration: is it tracked anywhere
outside workflow files? Is CI configured to regenerate? (LOW)

The CI workflow at `.github/workflows/ci.yml` runs:
- `./gradlew build` (on Ubuntu)
- `./gradlew test`
- `./gradlew apiCheck`

There is **no `recordPaparazzi` or `verifyPaparazzi` step** in any CI workflow file.
The four snapshot tests added by amendment-1 (`nodeIdRowScene`, `onClickRowScene`,
`longPressGridScene`, `alphaSampleScene`) will only fail `verifyPaparazzi` if someone
runs that task manually or if it is added to a CI job. The CI `test` task includes
JUnit tests but NOT the Paparazzi `verifyPaparazzi` Gradle task.

The implement doc (`05-implement-...md` Round 2 § Deferred) and verify doc
(`06-verify-...md` Round 1 Issue 1) document the Windows blank-render problem and
defer baseline regeneration to "Linux CI." But there is no Linux CI step that runs
`verifyPaparazzi`. The four baselines remain absent or blank on disk.

**Finding (LOW):** The "must rebaseline 4 snapshots on Linux CI" requirement is NOT
tracked outside workflow documents. There is no CI job that runs `verifyPaparazzi`.
If a PR is opened, CI will run `./gradlew test` which does NOT include the Paparazzi
verification task. The snapshot regressions could silently accumulate without a gate.

**Recommendation (non-blocking):** Add a `./gradlew :isometric-compose:verifyPaparazzi`
step to `ci.yml` after the test step. This requires recording baselines on Linux first.
Until then, the four snapshot tests are effectively ungated by CI.

---

### RS-R2-9 — Scene factory / live sample geometry drift: is there a detection
mechanism? (MED)

Four scene factories in `isometric-compose/src/test/kotlin/.../scenes/` replicate
geometry from `InteractionSamplesActivity.kt`. Each has a KDoc: "If the sample changes,
update here to match." There is no automated check (no test, no compile-time assertion,
no code-generation) that enforces synchronisation.

**Current drift check (manual):**

| Scene factory | Live app counterpart | Status |
|---------------|---------------------|--------|
| `WS10NodeIdScene.kt` | `NodeIdSample` | MATCH — ground 10×6×0.1, 4 buildings same positions. Minor: live app uses a `nodeId` param on each `Shape`; factory omits it. Not geometry drift. |
| `LongPressGridScene.kt` | `LongPressSample` | MATCH — ground 8×6×0.1, 3×3 grid at `col * 1.8, row * 1.8`, each 1.2×1.2×1.0. |
| `OnClickRowScene.kt` | `OnClickSample` | MATCH — ground 10×6×0.1, 5 shapes at `i * 1.5`, 1×1×1 (or 1×1×2 when selected). |
| `AlphaSampleScene.kt` | `AlphaSample` | PARTIAL DRIFT — live `AlphaSample` uses a `Batch(shapes = [...], alpha = 0.6f)` composable for the 3 CYAN prisms; the factory uses 3 individual `Shape(...)` calls. The geometry coordinates match; the composable type differs. This is documented as intentional in the factory KDoc ("alpha values NOT replicated"). However, if `Batch` and `Shape` produce different path decompositions or depth-sort entry counts in the future, the factory would no longer be a faithful reproduction. |

**Finding (MED):** `AlphaSampleScene.kt` uses individual `Shape` calls for the 3 CYAN
prisms while the live `AlphaSample` wraps them in a `Batch` composable. If `Batch`
ever changes its path decomposition (e.g., merging faces, changing indices, or altering
the face count fed to `DepthSorter`), the factory would silently diverge from the app
scene. Currently the `Batch` composable is a thin wrapper that produces individual
`SceneGraph.SceneItem` entries (same as `Shape`), so no drift exists today. There is
also no automated drift detection for any of the four factories.

**Recommendation (non-blocking):** The existing per-factory KDoc instruction ("If the
sample changes, update here to match") is the only safety net. Consider adding a
compile-time constant (e.g., a `const val FACTORY_PRISM_COUNT = 3` shared between the
factory and a sanity assertion) or a comment in `InteractionSamplesActivity.kt` pointing
back to the test factories.

---

### RS-R2-10 — `depth()` vs `depth(ISO_ANGLE)` mismatch between pre-sort and cascade

`DepthSorter.sort` line 34 pre-sorts using `it.item.path.depth` — the `depth` property
which delegates to `Point.depth()` = `x + y - 2 * z` (uniform x/y weighting).

`Path.closerThan` cascade step 1 uses `p.depth(ISO_ANGLE)` = `x * cos(PI/6) + y *
sin(PI/6) - 2 * z` (cos/sin-weighted x/y).

These two formulas agree for x == y but diverge for x ≠ y because `cos(PI/6) ≈ 0.866`
and `sin(PI/6) = 0.5`, whereas the pre-sort uses coefficient 1.0 for both x and y.

This mismatch is pre-existing (it predates the Newell rewrite) and was present in
the round-1/2 code. The Newell rewrite did not introduce or worsen it. The practical
impact is that for scenes with significant x/y asymmetry (tall shapes far in the x
direction vs. tall shapes far in the y direction), Kahn's zero-in-degree tiebreaker
(which picks the lowest pre-sorted index, i.e., the highest `path.depth` value) may
disagree with the cascade's step-1 depth ordering for pairs where `closerThan` returns
0. This has always been true; the Newell rewrite makes no regression here.

**Finding (INFO):** Pre-existing pre-sort / cascade depth-formula mismatch. Not
introduced by this change; not worsened by it. Low practical impact since Kahn
tiebreakers only matter for genuine `closerThan == 0` (ambiguous) pairs.

---

## Summary (Round 2)

| ID | Severity | Location | Summary |
|----|----------|----------|---------|
| RS-R2-1 | INFO | `PathTest.kt`, `DepthSorterTest.kt` | All 191 tests sign-only; no magnitude encoded; all pass under Newell — SAFE |
| RS-R2-2 | INFO | `PathTest.kt:296` | Sign convention internally consistent; plan text was wrong, code is right — SAFE |
| RS-R2-3 | INFO | `countCloserThan` deletion | Zero remaining call sites or reflection references — SAFE |
| RS-R2-4 | HIGH | `docs/concepts/depth-sorting.md`, `site/src/content/docs/concepts/depth-sorting.mdx` | Both docs name `hasIntersection` as the narrow-phase gate but DepthSorter uses `hasInteriorIntersection` since commit `9cef055` — stale documentation; no automated CI check catches it |
| RS-R2-5 | INFO | `DepthSorter.kt` | DIAG revert verified complete — zero diagnostic remnants — SAFE |
| RS-R2-6 | MED | `Path.kt` cascade steps 2/3 | Screen-x/y sign convention untested by any AC; correct for 30° default but latent risk for non-standard projection angles; `ISO_ANGLE` is hardcoded independently of engine angle |
| RS-R2-7 | LOW | `PathTest.kt` KDocs | Step-ordering claims are comments only, not assertions; cascade reorder would silently change performance without failing any test |
| RS-R2-8 | LOW | `.github/workflows/ci.yml` | No `verifyPaparazzi` CI step; "must rebaseline 4 snapshots on Linux CI" requirement is undocumented outside workflow files and ungated by any CI job |
| RS-R2-9 | MED | `AlphaSampleScene.kt` vs `AlphaSample` | Factory uses 3 individual `Shape` calls; live app uses `Batch`; geometry matches today but no drift detection; silently diverges if `Batch` changes face decomposition |
| RS-R2-10 | INFO | `DepthSorter.kt:34` vs `Path.kt:152` | Pre-sort uses `depth()` (uniform x/y coefficients); cascade step 1 uses `depth(PI/6)` (cos/sin-weighted) — pre-existing mismatch, not introduced by this change |

**Round 2 Result: PASS.**  
No blocker findings. The algorithm replacement preserves all tested behaviour contracts;
the public signature and sign semantics are unchanged from the caller's perspective;
`countCloserThan` is fully deleted with no orphaned callers; the DIAG revert is complete.
Two medium-severity findings (RS-R2-6, RS-R2-9) and one high-severity documentation
staleness (RS-R2-4) are the highest-priority non-blocking items. The high-severity
documentation finding (RS-R2-4) is the most likely to confuse a future maintainer;
it should be addressed before this branch merges to `master`.
