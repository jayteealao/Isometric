---
schema: sdlc/v1
type: plan
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 4
created-at: "2026-04-26T19:33:07Z"
updated-at: "2026-04-28T08:15:48Z"
metric-files-to-touch: 6
metric-step-count: 22
has-blockers: false
revision-count: 2
amends: 02-shape-amend-2.md
applies-amendment: 2
prior-amendments-applied: [1]
newell-cascade-entry-point: "in-place replace closerThan body; delete countCloserThan; private helper signOfPlaneSide for steps 5/6"
diag-revert-bundling: same-commit-as-newell
polygon-split-status: deferred-unless-cycles-observed
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
  - 3x3-grid
  - screen-overlap-gate
  - newell-minimax
  - newell-full
  - cmpPath-zero-fallback
  - algorithmic-restructure
  - diag-revert
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 02-shape.md
  shape-amendment: 02-shape-amend-2.md
  prior-amendment: 02-shape-amend-1.md
  diagnostic: 07-review-grid-regression-diagnostic.md
  round3-investigation: 05-implement-depth-sort-shared-edge-overpaint.md
  round3-evidence: verify-evidence/round3-longpress-diag.log
  siblings: []
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
next-command: wf-implement
next-invocation: "/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Plan: depth-sort shared-edge overpaint

## Current State

**Amendment-2 update (this revision)**: After amendment-1 shipped (commit `9cef055 fix(depth-sort): screen-overlap gate for 3x3 grid edge-cases`) the verify stage confirmed the gate worked structurally (AC-9, AC-10 unit tests green) but the LongPress sample's back-right cube STILL renders only its top face (AC-11 not met). Round-3's directed investigation (commit `2e29dc5`, log at `verify-evidence/round3-longpress-diag.log`) traced the residual failure to a **third** mechanism: for the (back-right vertical wall, ground top) face pair, `IntersectionUtils.hasInteriorIntersection` correctly admits the pair, but `Path.closerThan` returns **0** because each face has vertices straddling the other's plane on its respective observer-axis. Both `countCloserThan` directions return 1 under the permissive threshold; they cancel. With no edge added, Kahn falls back to depth-descending centroid pre-sort, which puts the wall (centroid depth ~7.2) BEFORE the ground top (centroid depth ~4.9), and the painter then paints ground over wall.

This is the **cmpPath=0 wall-vs-floor symmetric-straddle** regression class. It is structurally inverse to the amendment-1 over-aggressive-edge bug (too FEW informative votes vs amendment-1's too many spurious ones) and orthogonal to it. No epsilon tuning, no straddling-rule adjustment, no further gate-tightening can resolve it — the comparator must consult a fundamentally different geometric signal (iso-axis minimax) to break the symmetry. Per amendment-2's user directive, the entire `closerThan` predicate is replaced by Newell's canonical Z→X→Y minimax cascade — the existing permissive vote-and-subtract approach is superseded.

**Current source tree state at `2e29dc5` (HEAD on `feat/ws10-interaction-props`):**

- `Path.kt` lines 99–146: contains the post-`3e811aa` permissive `result > 0` predicate. `closerThan` (line 99) and `countCloserThan` (line 117, now `private`) are both present. KDoc on `countCloserThan` documents the permissive threshold rationale. Both functions are removed/repurposed by amendment-2.
- `DepthSorter.kt`: contains the amendment-1 `hasInteriorIntersection` gate-call wired in `checkDepthDependency` (line 175). On top of that the round-3 DIAG instrumentation is present:
  - `private const val DIAG = true` (line 19)
  - `private fun first3(points)` helper (lines 21–27)
  - `FRAME START` emission (line 56), per-pair emission (lines 196–202), `ORDER` emission (lines 120–125), `ORDER-CYCLE` emission (lines 141–146), `FRAME END` emission (line 151)
  - The gate-call refactor that captures `intersects` / `cmpPath` / `edgeLabel` into locals (lines 175–202)
  - All of this MUST be reverted alongside the Newell adoption; per amendment-2 directive (c), the revert lands in the same commit.
- `IntersectionUtils.kt:199` — `fun hasInteriorIntersection(pointsA, pointsB): Boolean` already exists (committed in `9cef055`). Newell cascade RETAINS this as step 4 (or trusts the upstream gate when called from DepthSorter); no change to this file.

**Failing test landscape under amendment-2 (predicted):**

- `PathTest.\`closerThan returns nonzero for hq-right vs factory-top shared-edge case\`` — passes under permissive (positive). Under Newell: hq's z=[0.1, 3.1] vs factory_top's z=2.1. Z-extents overlap. iso-x extents: hq at world x=1.5 projects to one screen-x; factory at world x=[2.0, 3.5] projects to a different screen-x range — disjoint or near-disjoint. Returns non-zero from X-extent step. **Likely still passes** with the same sign (factory_top is "drawn after" → positive when self=factoryTop). Reframe test KDoc to say "Newell X-extent step decides; factory_top is screen-rightward of hq_right." Implementer must verify the actual sign on first run; if it flips, the test geometry was always ordering by something subtly different than the predicate's "winning votes" interpretation and the test name was wrong.
- `PathTest.\`closerThan resolves X-adjacent neighbours with different heights\`` — A right wall at x=1, B top at x=[2,3]. Z-extents: A=[0,3], B=[1,1]. Overlap. X-extents (screen-projected): A's screen-x is around `1·cos30°` ≈ 0.866 along iso-x basis; B's screen-x range is `[2·cos30°, 3·cos30°]` ≈ [1.73, 2.60]. Disjoint, B is screen-rightward. Returns non-zero. Sign must be checked.
- `PathTest.\`closerThan resolves Y-adjacent neighbours with different heights\`` — symmetric to above, decided by Y-extent step.
- `PathTest.\`closerThan resolves top vs vertical side at equal heights\`` — A top z=2, B left wall x=2 z=[0,2]. Z-extents: A=[2,2], B=[0,2]. A.z_min == B.z_max == 2 → equal at one endpoint. Per the cascade epsilon rule, A.z_max (=2) ≤ B.z_min (=0)? No — A.z_max=2 > B.z_min=0. B.z_max (=2) ≤ A.z_min (=2)? Yes (with epsilon if equality counts). So Newell says A is unambiguously farther in iso-z (A is the floor at z=2, B's top is at z=2, BUT B extends downward to z=0). Wait — `point.depth() = x + y - 2z`, so higher z → SMALLER depth → CLOSER to viewer. Reinterpret: A at z=2 has lower depth (closer); B's vertices at z=[0,2] have a range of depths. So Newell's iso-z extent test in TERMS of depth: A.depthRange = some constant (since all at z=2), B.depthRange spans wider. Equal extents at one endpoint, B-extent strictly extends beyond A's. The cascade continues.
- `PathTest.\`closerThan resolves diagonally offset adjacency\`` — A right wall at x=1 z=[0,4]; B stub top at (3,3) z=2. Z-extents overlap. X-extents disjoint. Returns non-zero.
- `PathTest.\`closerThan returns zero for genuinely coplanar non-overlapping faces\`` — **THIS ASSERTION FAILS UNDER NEWELL.** Both tops at z=1 → equal z-extents, cascade continues. A_x=[0,1], B_x=[2,3] → disjoint screen-x extents → returns non-zero from X-extent step. The amendment-2 directive (b) explicitly calls out AC-2 for reframing.
- AC-3 case (g) — the new wall-vs-floor straddle case from amendment-2. Currently absent. Must be added; expects non-zero from Newell Z-extent step.

**Test reframings required** (per amendment-2 directive (b)):

| Existing test | Pre-Newell behaviour | Newell behaviour | Action |
|---|---|---|---|
| `closerThan returns nonzero for hq-right vs factory-top` | positive via vote-and-subtract `1 - 0` | non-zero via X-extent minimax (sign verify on first run) | Update KDoc; sign assertion may need adjusting |
| `closerThan resolves X-adjacent ...` | positive | non-zero via X-extent | Update KDoc |
| `closerThan resolves Y-adjacent ...` | positive | non-zero via Y-extent | Update KDoc |
| `closerThan resolves top vs vertical side at equal heights` | positive | non-zero via plane-side (Z-extents touch but don't strictly disjoint) | Update KDoc |
| `closerThan resolves diagonally offset adjacency` | positive | non-zero via X-extent | Update KDoc |
| `closerThan returns zero for genuinely coplanar non-overlapping faces` | zero | **NON-ZERO** via X-extent | **Reframe** — split into two tests (see Step-by-Step §R3) |

Per amendment-2 § Impact on Implementation: "Some PathTest cases that asserted specific permissive-threshold semantics (e.g., AC-2's 'returns 0 for genuinely coplanar non-overlapping faces') may need their assertions reframed in Newell terms (Newell returns 0 via the screen-projection-overlap step, not via the vote-and-subtract returning 0). The intent of each test is preserved; the exact assertion text may need a one-line update per test as the plan stage works through them."

The plan adopts: **preserve every test's INTENT; update assertion text and KDoc to reflect which Newell step resolves the case.** Where Newell flips zero to non-zero or non-zero to zero relative to the old behaviour, split or rename the test rather than rewrite history.

---

**Amendment-1 update**: Path.kt was modified by `3e811aa fix(depth-sort): permissive countCloserThan threshold + 1e-6 epsilon`. That change is the WS10 fix and remains correct in isolation. The amendment-1 work focuses on a SECOND bug class — over-aggressive topological edges in 3×3 grid layouts — that only became visible on emulator after `3e811aa` shipped. Per the diagnostic in `07-review-grid-regression-diagnostic.md`, the mechanism is: permissive `result > 0` produces "winning votes" in `closerThan` for prism face pairs whose 2D iso-projected polygons don't actually overlap, and `IntersectionUtils.hasIntersection` accepts boundary-touching AABBs as overlapping (line 99-103 of `IntersectionUtils.kt`), so the gate that should reject these pairs lets them through. Topological sort then pushes corner-cube vertical faces to output positions 0–2 where they get painted over.

**`IntersectionUtils.hasIntersection` current behaviour** (read at amendment time):
- AABB rejection (lines 84–103) accepts `A.maxX == B.minX` as overlap (`<=` checks). Permissive about boundary touch.
- SAT edge-crossing test (lines 137–151) uses strict `< -1e-9` so edges that just touch don't count as crossing. Correct.
- `isPointInPoly` containment fallback (lines 154–166) uses ray-casting; boundary points have asymmetric inclusion behaviour. Mostly correct but boundary-only contact CAN return true via the AABB+isPointInPoly path.

The plan needs a tighter gate: when two prism face polygons are separated by a non-zero gap or share only a boundary edge in iso-projected screen space, the gate must return false even if `hasIntersection` returns true. Concretely: add an interior-overlap check that requires non-trivial intersection area (or equivalently, at least one polygon vertex strictly INSIDE the other, OR at least one edge crossing strictly interior to both polygons).

**Pre-amendment tests**: 183 isometric-core tests green at commit `3e811aa`. PathTest (10 cases including the WS10 closerThan unit), DepthSorterTest (8 cases including the WS10 NodeIdSample integration test and the antisymmetry invariant), IntersectionUtilsTest (3 cases — disjoint, overlapping, contains). All retained as-is; the amendment ADDS new tests rather than modifying existing ones.

**Path.kt last modified:** `c4a62a5 build: prepare release v1.0.0`, then `3e811aa fix(depth-sort): permissive countCloserThan threshold + 1e-6 epsilon`. The `closerThan`/`countCloserThan` functions are now in their post-`3e811aa` state and remain UNCHANGED by this amendment.

**Exact bug arithmetic** (verified by sub-agent 1 against the live source):

```kotlin
// Path.kt — countCloserThan, lines 127-140
// Careful with rounding approximations
if (observerPosition * pPosition >= 0.000000001) {
    result++
}
if (observerPosition * pPosition >= -0.000000001 && observerPosition * pPosition < 0.000000001) {
    result0++
}
// ...
return if (result == 0) {
    0
} else {
    (result + result0) / points.size   // ← integer division collapse
}
```

For the diagnosed face pair (factory-top vs hq-right at the WS10 NodeIdSample's coordinates):
- `factory_top.countCloserThan(hq_right)`: result = 0 (none of factory's vertices are on observer side of hq_right's plane) → returns 0.
- `hq_right.countCloserThan(factory_top)`: result = 2 (two of hq_right's four vertices are on observer side of factory_top's plane) → `(2 + 0) / 4 = 0` (integer division collapse) → returns 0.
- `closerThan(factory_top, hq_right) = 0 - 0 = 0`. `DepthSorter.checkDepthDependency` adds no edge. Pre-sort wins. Pre-sort puts hq_right at lower index (drawn first, in back) and factory_top at higher index (drawn later, on top) → factory paints over hq → bug visible.

**Test infrastructure** (sub-agent 3):
- `isometric-core`: 172 tests, all green. JUnit 4 + kotlin.test. Backtick test naming. No `truth`, no Kotest, no shared scene factory.
- `isometric-compose`: Paparazzi 1.3.0. JUnit 4 + Truth. CamelCase Paparazzi test naming. Snapshot baselines NOT committed to git (only 2 of 13 PNGs on disk, untracked) — pre-existing tech debt, out of scope for this fix but the new baseline we add must be committed.
- CI runs `./gradlew test` on Linux. Windows build-logic race does not affect CI. Local Windows verification requires the same `--stop` + `mv build-logic/build aside` dance we've been doing all session.

**No `IntersectionUtilsTest.kt` exists.** Will be created in this fix.

**No shared scene factory exists** in `isometric-compose:src/test/`. Snapshot tests build scenes inline in their test bodies. The user chose to extract the NodeIdSample geometry to a shared factory in this PLAN — adds one new file.

## Reuse Opportunities

**Amendment-2 reuse opportunities:**

- **`Point.depth(angle: Double): Double`** at `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Point.kt:220` — *reuse as-is*. Returns `x*cos(angle) + y*sin(angle) − 2z`. This is exactly the iso-z projection Newell's Z-extent step needs. Per-vertex `point.depth(PI/6)` gives the iso-z scalar; `path.points.minOf/maxOf { it.depth(PI/6) }` gives the polygon's iso-z extent.
- **`Point.depth(): Double`** at `Point.kt:209` — *reuse as-is*. The default-angle (uniform-weight) variant. Either form works for the relative comparison Newell needs; the cascade should pick one consistently. Plan recommends `depth(PI/6)` to match the default engine projection angle visible in the rest of the codebase.
- **`Vector.dotProduct` / `Vector.crossProduct` / `Vector.fromTwoPoints`** — *reuse as-is*. Used by the existing `countCloserThan`'s plane-side test; the Newell cascade's plane-side step (cascade step 5/6) uses the same machinery, so a private helper `signOfPlaneSide(pathA: Path, observer: Point): Int` can be lifted directly from the existing `countCloserThan` body, generalised to return a tri-valued sign instead of {0, 1}.
- **`IntersectionUtils.hasInteriorIntersection`** at `IntersectionUtils.kt:199` — *reuse as-is* as Newell cascade step 4 (screen-overlap). When `Path.closerThan` is called via `DepthSorter.checkDepthDependency`, the gate has already filtered for interior overlap so step 4 is redundant; when called directly from a unit test, step 4 is the safety net that returns 0 for screen-disjoint pairs that nonetheless straddle planes geometrically. Plan recommends KEEPING step 4 unconditionally inside the Newell cascade so the predicate is correct in isolation, accepting one redundant `hasInteriorIntersection` call per pair when invoked through DepthSorter (negligible cost vs the wall-clock saved by the early Z/X/Y minimax exits).
- **`DepthSorter` Kahn's-algorithm machinery** — *reuse as-is*. Newell's optional polygon-splitting step is **deferred** per amendment-2 § Out of Scope adjustment; the existing append-on-cycle fallback (`DepthSorter.kt:139–149`) covers the case if any cycle survives the new comparator. Verify stage will confirm whether AC-12/AC-13 expose any cycles; if they do, splitting becomes mandatory in a follow-up amendment.
- **Existing `DepthSorterTest` integration test pattern** — *reuse as-is*. AC-12 and AC-13 (LongPress and Alpha full-scene tests including the ground prism) follow the same `IsometricEngine().add(...).projectScene(800, 600, RenderOptions.NoCulling)` recipe used by the existing WS10 NodeIdSample integration test and the amendment-1 AC-9 grid test.
- **`Vector` static helpers in `Vector.kt`** — *reuse as-is*. No new vector math required; Newell's plane-side step uses the same dot/cross/from-two-points operations the current `countCloserThan` uses.

No reuse candidate exists for the Newell **Z/X/Y minimax steps themselves** — they're a new geometric primitive in this codebase. Implementation is local to a small private helper inside `Path.kt`.

---

- **`Prism` shape constructor** at `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Prism.kt` — *reuse as-is*. The new tests construct prisms identically to existing `DepthSorterTest` cases.
- **`IsometricEngine.add()` + `projectScene(800, 600, RenderOptions.NoCulling)`** pattern from `DepthSorterTest.kt` — *reuse as-is* for the integration test (AC-4). This is the established recipe in the file.
- **`Paparazzi()` no-args + `Box(modifier = Modifier.size(_, _))` + `IsometricScene { Shape(...) }`** pattern from `IsometricCanvasSnapshotTest.kt` — *reuse as-is* for the snapshot test (AC-5), with size = `800.dp x 600.dp` per Round 2 decision.
- **`@Test fun \`backtick test name\`()` + `kotlin.test.assertEquals/assertTrue`** pattern from `PathTest.kt` and `DepthSorterTest.kt` — *reuse as-is* for new core unit tests.
- **`Building` data class semantics** from `InteractionSamplesActivity.NodeIdSample` (`hq` h=3.0 / `factory` h=2.0 / `warehouse` h=1.5 / `tower` h=4.0 at `Point(i*2.0, 1.0, 0.1)`) — *extract into shared utility then use*. New file in `isometric-compose/src/test/kotlin/.../scenes/` will host the factory; both the new snapshot test and any future regression tests can call it.

No reuse candidate exists for the `closerThan` math itself. The original-scope fix is local to `Path.kt`.

**Amendment-1 reuse opportunities:**
- **`IntersectionUtils.hasIntersection`** at `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt:71-169` — *reuse with modification*. Existing SAT-based polygon overlap test that combines AABB rejection (lines 84–103), edge-crossing detection (lines 137–151), and point-in-polygon containment fallback (lines 154–166). The amendment requires either tightening this function's behaviour for boundary-only contact OR adding a sibling helper `hasInteriorIntersection` that returns false for the same boundary cases. Recommend ADDING a sibling helper to preserve the existing function's contract for any other (non-DepthSorter) callers — even if today it has only one caller.
- **`IntersectionUtils.isPointInPoly`** at `IntersectionUtils.kt:48-60` — *reuse as-is*. Ray-casting containment test; useful inside the new `hasInteriorIntersection` for "is at least one vertex strictly INSIDE the other polygon" check.
- **`WS10NodeIdScene`** at `isometric-compose/src/test/kotlin/.../scenes/WS10NodeIdScene.kt` — *reuse as-is*. Already extracted; the amendment-1 work adds three sibling factories (`OnClickRowScene`, `LongPressGridScene`, `AlphaSampleScene`) following the same pattern.
- **`DepthSorterTest`** existing pattern — *reuse as-is*. AC-9's 3×3 grid integration test follows the same `IsometricEngine().add(...).projectScene(800, 600, RenderOptions.NoCulling)` recipe used by the existing `WS10 NodeIdSample` integration test.

## Likely Files / Areas to Touch

**Amendment-2 file changes (executes on top of `2e29dc5` HEAD):**

A2-1. **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`** —
  **Replace `closerThan`'s body in place with the Newell Z→X→Y minimax cascade.**
  - Keep public signature `fun closerThan(pathA: Path, observer: Point): Int` (preserves AC-8).
  - **Delete** the existing private `countCloserThan` function (lines 117–146 in current source). Its plane-side machinery is lifted into a new private helper.
  - Add private helper `signOfPlaneSide(pathA: Path, observer: Point): Int` returning `+1` if all of `this`'s vertices lie strictly on the observer side of pathA's plane (within 1e-6 epsilon), `-1` if all strictly on the opposite side, `0` if mixed/coplanar. This implements Newell's classical "all-on-same-side" plane-side test (steps 5/6 of the cascade) — note this is STRICTER than the round-1 permissive `result > 0` predicate; it returns 0 unless EVERY vertex agrees. The mixed case falls through to the next cascade step (which, for `closerThan`, means returning 0 and letting the topological-sort/centroid-pre-sort layer handle it — except that under Newell, the X/Y/Z minimax steps will already have decided most mixed cases before the plane-side test runs).
  - Cascade order inside the new `closerThan`:
    1. **Z-extent (iso-depth) minimax**: compute `aZ = pathA.points.map { it.depth(PI/6) }` (min/max), same for `this`. If `selfZmax < aZmin - eps` → `this` is unambiguously closer → return `+1`. If `aZmax < selfZmin - eps` → `this` is unambiguously farther → return `-1`. (Recall higher `depth()` = farther; the sign convention here matches the existing `closerThan` doc: positive when `this` is closer.)
    2. **X-extent minimax**: compute screen-projected x for each vertex via `screenX(p) = (p.x - p.y) * cos(PI/6)` (or use Point's existing helper if one exists; if not, inline this single-line projection — the implementer adds a tiny private helper `Point.isoX(angle): Double` and `Point.isoY(angle): Double` if it improves readability). Compute min/max for both polygons. If extents are disjoint with epsilon, return non-zero with the sign convention "screen-rightward → drawn after → closer = positive when self is rightward."
    3. **Y-extent minimax**: same shape, on screen-y projection (`screenY(p) = (p.x + p.y) * sin(PI/6) - p.z` for the standard 30° iso, or whatever the existing engine uses — check `IsometricEngine` for the canonical projection function before inlining).
    4. **Screen polygon overlap test**: call `IntersectionUtils.hasInteriorIntersection` on the screen-projected polygons. If false → return 0 (no draw-order constraint between them; they don't overpaint each other).
    5. **Plane-side test forward**: `val sFwd = signOfPlaneSide(pathA, observer)`. If non-zero → return `+sFwd` (or whatever sign convention matches the existing tests; the amendment text says positive iff `this` is "closer"). If zero (mixed) → step 6.
    6. **Plane-side test reverse**: `val sRev = pathA.signOfPlaneSide(this, observer)`. If non-zero → return `-sRev` (reverse direction). If zero → step 7.
    7. **Polygon split** — DEFERRED per amendment-2 directive (d). Returns 0 here; Kahn's append-on-cycle fallback in DepthSorter handles any resulting cycles.
  - Update KDoc on `closerThan` to reference Newell's 1972 paper and briefly catalogue the three superseded failure modes (under-determined sort fixed in round 1, over-aggressive edges fixed in round 2 via the gate, cmpPath=0 wall-vs-floor fixed in round 4 via Newell). Move the historical `countCloserThan` integer-division-collapse note to the optional explanation doc rather than carrying it forward.
  - **Net diff: ~80 new lines, ~30 deleted lines (the old countCloserThan + its KDoc) = ~+50 net.**

A2-2. **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`** — **REVERT round-3 DEPTH_SORT_DIAG instrumentation** (commit `2e29dc5`). Specifically:
  - Delete `private const val DIAG = true` (line 19 + comment block lines 13–18).
  - Delete `private fun first3(points)` helper (lines 21–27).
  - Delete the FRAME START emission (line 56).
  - Delete the per-pair emission (lines 196–202) inside `checkDepthDependency`.
  - Delete the ORDER emission (lines 120–125) and ORDER-CYCLE emission (lines 141–146) inside the Kahn loop.
  - Delete the FRAME END emission (line 151).
  - **Keep** the gate-call refactor that captures `intersects` / `cmpPath` into locals (lines 175–195) — slight readability win, no behavioural difference. Delete only the `var edgeLabel = "none"` / `edgeLabel = "i->j"` / `edgeLabel = "j->i"` book-keeping that exists solely to feed the diagnostic emission. Plan recommends keeping the locals-style refactor; implementer may roll back to inline form if it reads cleaner.
  - **Net diff: −47 / +0 lines (a clean reversion of the round-3 additions).**

A2-3. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`** —
  - **Reframe AC-2 (split into two tests).** Replace the single `closerThan returns zero for genuinely coplanar non-overlapping faces` test with TWO tests:
    1. `\`closerThan returns zero for coplanar overlapping faces\`` — geometry: two paths in the same z=1 plane with the same screen-projected x/y extents (e.g., identical-shape duplicates). Z-extents equal → continue. X/Y-extents equal → continue. Screen polygons interior-overlap → continue. Plane-side forward AND reverse both 0 (all vertices on the same plane). Falls through to step 7. Returns 0. Asserts `assertEquals(0, result)`.
    2. `\`closerThan resolves coplanar non-overlapping via X-extent minimax\`` — geometry: the original AC-2 case (two tops at z=1, x=[0,1] vs x=[2,3]). Z-extents equal → continue. X-extents disjoint → returns non-zero. Asserts `assertTrue(result != 0)` with documentation that the sign reflects screen-x ordering.
  - **Update KDoc on five existing closerThan tests** (`hq-right vs factory-top`, `X-adjacent`, `Y-adjacent`, `top vs vertical side at equal heights`, `diagonally offset adjacency`) to explain which Newell cascade step now resolves each case (X-extent, Y-extent, plane-side, etc.). Assertions stay as `assertTrue(result > 0, …)` IF the implementer's first run confirms the Newell sign matches the previously-asserted sign; if it flips, the test name is renamed to reflect the new sign convention without weakening the assertion.
  - **Add AC-3 case (g) — the wall-vs-floor straddle test.** Per amendment-2 § Verification Strategy:
    ```kotlin
    @Test
    fun `closerThan resolves wall-vs-floor straddle via Z-extent minimax`() {
        // Wall: vertical face at x=1.0, y=[0.5, 1.5], z=[0.0, 1.0].
        // Floor: horizontal face at z=0.0 strictly containing wall's xy projection.
        // Pre-Newell (permissive): both directions return 1 → cancel → 0 (the bug).
        // Under Newell: Z-extents — wall=[0.0, 1.0], floor=[0.0, 0.0].
        //   floor.zMax (0.0) ≤ wall.zMin (0.0) within epsilon → floor is unambiguously
        //   farther in iso-z (lower z = farther under depth(PI/6) = x*cos+y*sin−2z
        //   convention; floor's z is 0, wall has z up to 1 which is "closer").
        //   Returns positive when self=wall, signalling wall is closer.
        val wall = Path(
            Point(1.0, 0.5, 0.0), Point(1.0, 1.5, 0.0),
            Point(1.0, 1.5, 1.0), Point(1.0, 0.5, 1.0)
        )
        val floor = Path(
            Point(0.0, 0.0, 0.0), Point(2.0, 0.0, 0.0),
            Point(2.0, 2.0, 0.0), Point(0.0, 2.0, 0.0)
        )
        val result = wall.closerThan(floor, observer)
        assertTrue(result > 0, "wall must be closer than floor (Newell Z-extent step); got $result")
    }
    ```
  - **Net diff: ~+50 lines new, ~−5 lines (the deleted single AC-2 assertion replaced by two tests + the reframing KDocs).**

A2-4. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`** — **add AC-12 and AC-13 integration tests.** Both differ from the existing AC-9 grid test by **including the ground prism** in the `DepthSorter.sort` invocation — this is critical because round-3 demonstrated that AC-9 passes structurally without the ground prism but the visual regression manifests with it.
  - **AC-12 — LongPressSample full scene**: 9 unit prisms (the 3×3 grid) PLUS the ground prism at `Point(-1.0, -1.0, 0.0)` with `width=8.0, depth=6.0, height=0.1`. After projection + DepthSorter, identify the back-right cube's front face (y=3.6 plane) and left face (x=3.6 plane) by vertex geometry — same as AC-9. Identify the ground top face (z=0.1 plane). Assert `backRightFront > groundTop` AND `backRightLeft > groundTop` in command order (i.e., back-right's vertical faces draw AFTER the ground top, which is the visual fix).
  - **AC-13 — AlphaSample full scene**: ground prism + three CYAN prisms at `(3.5, 3.0, 0.1)`, `(4.3, 3.0, 0.1)`, `(5.1, 3.0, 0.1)` with heights 0.8, 1.2, 1.6. For each CYAN prism, identify its front and left vertical faces and the ground top; assert each prism's vertical-face commands are at output positions GREATER than the ground-top position.
  - **Net diff: ~+90 lines new (AC-12 ~50, AC-13 ~40).**

A2-5. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`** — **no source change required.** The four snapshot test methods from amendment-1 (`nodeIdRowScene`, `onClickRowScene`, `longPressGridScene`, `alphaSampleScene`) cover the AC-14 invariant (which supersedes amendment-1's AC-11). Their PNG baselines must be regenerated on Linux CI after the fix lands; the test source code stays the same. Plan-stage decision: do NOT delete the missing baselines; let CI regenerate or accept the verify-stage manual capture path.

A2-6. **`docs/internal/explanations/depth-sort-painter-pipeline.md`** *(NEW — optional, recommended)* — internal explanation doc per amendment 2 § Documentation Plan. Should explain: centroid pre-sort → Kahn topological refinement → `IntersectionUtils.hasInteriorIntersection` gate → Newell Z→X→Y minimax cascade → optional polygon split (deferred). Reference all three failure modes (under-determined sort, over-aggressive edges, cmpPath=0 wall-vs-floor) as the historical rationale for each layer of the pipeline. **Plan does NOT mandate this doc**; ship-stage handoff can include it as an optional follow-up. **Net diff: ~+200 lines new IF written; 0 if deferred.**

**Amendment-2 file count: 4 source/test files modified (Path.kt, DepthSorter.kt, PathTest.kt, DepthSorterTest.kt) + 0 new files (existing snapshots and snapshot test stay). Optionally +1 new explanation doc.** The amendment-1 work is already shipped in `9cef055`; the amendment-2 commit lands as its own atomic `fix(depth-sort): replace closerThan with Newell Z->X->Y minimax cascade` (with the DIAG revert bundled in).

**Files NOT touched by amendment-2** (explicit non-targets):
- `IntersectionUtils.kt` — `hasInteriorIntersection` is correct as-is, used as Newell cascade step 4.
- `IntersectionUtilsTest.kt` — existing 3 baseline + 3 amendment-1 boundary cases all still apply.
- `WS10NodeIdScene.kt`, `OnClickRowScene.kt`, `LongPressGridScene.kt`, `AlphaSampleScene.kt` — existing scene factories unchanged; their geometry continues to drive the Paparazzi snapshots.
- `IsometricCanvasSnapshotTest.kt` — snapshot test sources unchanged; only the underlying PNG baselines regenerate on Linux CI.

---

1. **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`** — modify `countCloserThan` (lines 127-140): change the integer-division return to permissive `result > 0`, and widen epsilon from `0.000000001` (1e-9) to `0.000001` (1e-6). Update KDoc on `countCloserThan` to explain the new semantics. **Net diff: ~10 lines**.
2. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`** — extend with new `closerThan`/`countCloserThan` unit tests covering AC-1, AC-2, AC-3 (parameterised over canonical adjacency configurations). **Net diff: ~80 lines new, no deletions**.
3. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`** — extend with the four-building integration test (AC-4) and the antisymmetry invariant `@Test` (AC-7). **Net diff: ~50 lines new**.
4. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`** *(NEW)* — new file. Baseline coverage for adjacent / shared-edge / non-overlapping cases of `hasIntersection`. **Net diff: ~50 lines new**.
5. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt`** *(NEW)* — new file. Extracts NodeIdSample geometry as a reusable test factory. **Net diff: ~30 lines new**.
6. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`** — append one new `@Test fun nodeIdSharedEdge()` calling `paparazzi.snapshot { ... }` with `Box(800.dp, 600.dp)` + `IsometricScene { WS10NodeIdScene() }`. **Net diff: ~10 lines new**.
7. **`isometric-compose/src/test/snapshots/images/io.github.jayteealao.isometric.compose_IsometricCanvasSnapshotTest_nodeIdSharedEdge.png`** *(NEW)* — new file. Generated by `recordPaparazzi`, committed to git so CI's `verifyPaparazzi` can compare against it. **Net diff: 1 binary file**.

**Total: 5 source files (3 modified, 2 new) + 1 binary snapshot baseline.** Original-scope estimate; superseded by amendment-1 below.

**Amendment-1 file additions:**

8. **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`** — add a NEW public function `hasInteriorIntersection(pointsA, pointsB): Boolean` that reuses the existing AABB and SAT logic but rejects boundary-only contact (no overlapping interior area). Existing `hasIntersection` UNCHANGED so other callers retain their semantics. **Net diff: ~30 lines added**.

9. **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`** — modify `checkDepthDependency` (lines 124-149) to use `hasInteriorIntersection` instead of `hasIntersection` as the gate for adding topological edges. Single-line behavioural change. **Net diff: ~3 lines (1 modified line + 2-3 line comment update)**.

10. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`** — extend with cases for AC-10 + EC-9, EC-10: (a) two polygons sharing exactly one edge → `hasInteriorIntersection` returns false, `hasIntersection` keeps returning true (regression marker). (b) two polygons sharing exactly one vertex → false. (c) bounding-boxes overlap but interiors disjoint (e.g., one inside the other's BBox in a non-overlapping zone) → false. (d) interior overlap → true. **Net diff: ~50 lines new**.

11. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`** — extend with AC-9: `3x3 grid corner cube vertical faces are not at output positions 0-2`. Build the scene with 9 unit prisms at `Point(col*1.8, row*1.8, 0.1)` for col,row ∈ {0,1,2}, run DepthSorter, identify back-right cube's front (y=3.6 plane) and left (x=3.6 plane) faces by vertex geometry, assert `commandIndex >= 3`. **Net diff: ~40 lines new**.

12. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/OnClickRowScene.kt`** *(NEW)* — reusable factory replicating the OnClickSample geometry (5 prisms in a row, optionally one elevated to height=2 + IsoColor.YELLOW). **Net diff: ~25 lines new**.

13. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/LongPressGridScene.kt`** *(NEW)* — reusable factory replicating the LongPressSample 3×3 grid geometry. **Net diff: ~25 lines new**.

14. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/AlphaSampleScene.kt`** *(NEW)* — reusable factory replicating the AlphaSample mixed geometry (pyramid + cylinder + prisms). **Net diff: ~30 lines new**.

15. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`** — REPLACE the single `nodeIdSharedEdge` test (added at original scope, step 8) with FOUR new tests: `nodeIdRowScene` (renamed from nodeIdSharedEdge with its WS10NodeIdScene factory), `onClickRowScene`, `longPressGridScene`, `alphaSampleScene`. **Net diff: ~30 lines (replace 10 lines with 40)**.

16. **Four Paparazzi snapshot baselines** *(NEW)* under `isometric-compose/src/test/snapshots/images/` for each of the four AC-11 tests. Generated by `recordPaparazzi` on Linux CI; committed to git. **Net diff: 4 binary files**.

**Amendment-1 total: 9 source/test files (3 modified, 6 new) + 4 binary snapshot baselines.** Plus the original-scope work which is mostly already committed in `3e811aa`. The original `nodeIdSharedEdge` snapshot is REPLACED by `nodeIdRowScene` (new name reflecting its use as the row-layout regression test rather than the original "shared edge" framing). Remains within medium-appetite ceiling.

## Proposed Change Strategy

**Amendment-2 strategy (overlays on amendment-1 strategy below; supersedes the round-3 directed-investigation work in `2e29dc5`):**

The work is a single atomic commit `fix(depth-sort): replace closerThan with Newell Z->X->Y minimax cascade` that:
1. Replaces `Path.closerThan`'s body in place with the Newell cascade and deletes the obsolete private `countCloserThan`.
2. Reverts the round-3 DEPTH_SORT_DIAG instrumentation in `DepthSorter.kt` (commit `2e29dc5`'s additions).
3. Adds AC-12 (LongPressSample full-scene + ground top ordering) and AC-13 (AlphaSample full-scene + ground top ordering) integration tests in `DepthSorterTest.kt`.
4. Reframes AC-2 in `PathTest.kt` (split into "coplanar-overlapping returns 0" + "coplanar-non-overlapping returns non-zero via X-extent minimax") and updates KDocs on the other five `closerThan` tests to reflect Newell semantics.
5. Adds AC-3 case (g) — the wall-vs-floor straddle test — in `PathTest.kt`.

The atomic-commit policy is dictated by amendment-2 directive (c): the DIAG revert is mechanically inverse to the work that diagnosed the cmpPath=0 case, so bundling them keeps the historical record paired (one event, "before Newell" → "after Newell", with no transient diagnostic-only revisions in between).

**Polygon split (cascade step 7) is deferred** per amendment-2 directive (d). The implementation uses Newell steps 1–6 only; step 7 returns 0 unconditionally and Kahn's append-on-cycle fallback in `DepthSorter.sort` (lines 139–149) handles any residual cycles. The verify stage will confirm whether AC-12/AC-13 surface any cycles by running the full `:isometric-core:test` suite and inspecting `DepthSorter.sort`'s output for the LongPress and Alpha scenes — if the existing `cycle fallback` `DepthSorterTest` case passes AND no new cycles surface in AC-12/AC-13's command-order assertions, polygon-split stays deferred. If cycles DO appear, a follow-up amendment (`amend-3`) escalates polygon-split into scope.

**Strict red-green TDD ordering**: implement steps R1 (pre-flight) → R2 (red AC-12) → R3 (red AC-13) → R4 (red AC-3 case g + reframed AC-2 split) → R5 (green: implement Newell cascade) → R6 (green: revert DIAG) → R7 (regression sweep) → R8 (atomic commit). Round-3 already proved that AC-9 passes structurally without surfacing the bug; AC-12 and AC-13 are designed to fail under `2e29dc5` HEAD and pass only after Newell adoption.

**Risk**: Newell adoption is a larger algorithmic restructure than the previous patches. Two concrete failure modes to watch:

1. **Sign-convention drift**: the existing `closerThan` returns positive when "this is closer than pathA per the [permissive vote-and-subtract] semantics." Newell's plane-side test has the same convention, but the Z/X/Y minimax steps need explicit sign care: `point.depth(angle)` returns LARGER values for FARTHER faces, so `selfDepthMin > aDepthMax` means self is FARTHER → return NEGATIVE (not positive). The implementer's first run on the AC-1 (`hq-right vs factory-top`) test will catch any sign error; if the test flips, fix the sign in the cascade rather than flipping the test assertion.
2. **Sub-pixel epsilon at extent boundaries**: equal-height adjacent prisms have z-extents that touch exactly (e.g., A.zMax == B.zMin == 1.0). The cascade's "≤ with epsilon" check returns true at equality, which means "A is unambiguously farther in iso-z" — this is structurally correct but only if both polygons' z-extents strictly disjoint. For face pairs that share a z-edge (e.g., two coplanar tops at z=1) the comparison must NOT fire from Z step; both extents being exactly equal means they "overlap" → continue cascade. The implementation must use STRICT inequality `selfZmax < aZmin - eps` (with eps=1e-6 matching Path.kt's existing epsilon), so equality → continue. Verified by AC-2's reframed "coplanar overlapping returns 0" test.

**KDoc on the new `closerThan`** should reference Newell's 1972 paper (or the Painter's-algorithm Wikipedia summary) and explicitly note the three superseded failure modes as historical context. The internal `countCloserThan` private function is removed; its KDoc rationale moves to the optional explanation doc rather than carrying forward.

---

**Amendment-1 strategy (overlays on the original strategy below):**

The amendment work is purely additive on top of `3e811aa`. The existing red-green TDD steps 1–9 are CONSIDERED COMPLETE (committed in `3e811aa`). Amendment-1 work is steps A1–A6 below, executed as a second atomic commit `fix(depth-sort): screen-overlap gate for 3x3 grid edge-cases`.

Original-scope strategy (already executed) follows for context:



1. **Red**: write the failing `closerThan` shared-edge unit test in `PathTest.kt`. Run; confirm it fails with the diagnosed `0` return value.
2. **Green**: edit `countCloserThan` in `Path.kt`: replace `(result + result0) / points.size` with `if (result > 0) 1 else 0`, and widen epsilon. Re-run the failing test; confirm it passes.
3. **Layered tests**: add the parameterised `closerThan` tests (AC-3), the `DepthSorter` integration test (AC-4), and the antisymmetry invariant (AC-7) to `DepthSorterTest`. Add the new `IntersectionUtilsTest.kt` baseline.
4. **Snapshot**: add `WS10NodeIdScene.kt` test factory; append the snapshot test to `IsometricCanvasSnapshotTest.kt`; run `recordPaparazzi` locally to generate the baseline PNG.
5. **Regression sweep**: run the full `:isometric-core:test` and `:isometric-compose:test` suites. Confirm no other test breaks. If any existing snapshot pixel-diffs (the strict policy from Round 5 of shape requires investigation), pause and review before re-baselining.
6. **Atomic commit**: stage all source changes + the new snapshot PNG + the (already-uncommitted) build-logic CC fix as separate commits if appropriate; the depth-sort fix itself is one `fix(depth-sort): ...` commit.

The KDoc update on `countCloserThan` is included in step 2's diff.

## Step-by-Step Plan

**Amendment-2 steps (NEW — execute these in this order; this is the active implementation path):**

R1. **Pre-flight (amendment-2)**: confirm working tree is at commit `2e29dc5` (`chore(depth-sort): re-enable DEPTH_SORT_DIAG logging for over-paint investigation`). Confirm the round-3 DIAG block exists in `DepthSorter.kt` (search for `private const val DIAG = true`). Confirm 187 isometric-core tests still pass under `2e29dc5` (the DIAG instrumentation is logging-only; behavioural test count unchanged from amendment-1's 187). Capture the current LongPress AC-14 visual failure as baseline evidence in `verify-evidence/pre-newell-longpress.png` (if not already captured).

R2. **Red — failing AC-12 test**: in `DepthSorterTest.kt`, add the LongPressSample full-scene integration test:
    ```kotlin
    @Test
    fun `WS10 LongPress full scene: back-right cube vertical faces draw after ground top`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), IsoColor.LIGHT_GRAY)  // ground
        for (i in 0 until 9) {
            val row = i / 3; val col = i % 3
            engine.add(
                Prism(Point(col * 1.8, row * 1.8, 0.1), 1.2, 1.2, 1.0),
                IsoColor((col + 1) * 80.0, (row + 1) * 80.0, 150.0)
            )
        }
        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        val groundTop = scene.commands.indexOfFirst { cmd ->
            cmd.path.points.all { it.z in 0.099..0.101 } &&
                cmd.path.points.any { it.x in -1.01..-0.99 } &&
                cmd.path.points.any { it.x in 6.99..7.01 }
        }
        val backRightFront = scene.commands.indexOfFirst { cmd ->
            cmd.path.points.all { it.y in 3.59..3.61 } &&
                cmd.path.points.any { it.x in 3.59..3.61 } &&
                cmd.path.points.any { it.x in 4.79..4.81 }
        }
        val backRightLeft = scene.commands.indexOfFirst { cmd ->
            cmd.path.points.all { it.x in 3.59..3.61 } &&
                cmd.path.points.any { it.y in 3.59..3.61 } &&
                cmd.path.points.any { it.y in 4.79..4.81 }
        }

        assertTrue(groundTop >= 0, "ground top face must be present in scene")
        assertTrue(backRightFront > groundTop, "back-right front (idx=$backRightFront) must draw after ground top (idx=$groundTop)")
        assertTrue(backRightLeft  > groundTop, "back-right left  (idx=$backRightLeft) must draw after ground top (idx=$groundTop)")
    }
    ```
    Run `:isometric-core:test --tests DepthSorterTest`. **Expected: AC-12 FAILS** under `2e29dc5` HEAD because cmpPath=0 for the (back-right vertical, ground top) pair → no edge → centroid pre-sort puts wall before ground top → assertion fails with `backRightFront <= groundTop`.

R3. **Red — failing AC-13 test**: in `DepthSorterTest.kt`, add the AlphaSample full-scene integration test:
    ```kotlin
    @Test
    fun `WS10 Alpha full scene: each CYAN prism vertical faces draw after ground top`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), IsoColor.LIGHT_GRAY)  // ground
        listOf(
            Triple(Point(3.5, 3.0, 0.1), 1.0, 0.8),
            Triple(Point(4.3, 3.0, 0.1), 1.0, 1.2),
            Triple(Point(5.1, 3.0, 0.1), 1.0, 1.6),
        ).forEach { (pos, side, h) ->
            engine.add(Prism(pos, side, side, h), IsoColor.CYAN)
        }
        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        val groundTop = scene.commands.indexOfFirst { cmd ->
            cmd.path.points.all { it.z in 0.099..0.101 } &&
                cmd.path.points.any { it.x in -1.01..-0.99 }
        }
        // Identify each CYAN prism's front face (y=3.0 plane) by xRange.
        val prismFronts = listOf(
            scene.commands.indexOfFirst { /* x in [3.5, 4.5], y=3.0, z=[0.1, 0.9] */ /* ... */ },
            // ... two more prisms
        )
        prismFronts.forEachIndexed { idx, frontIdx ->
            assertTrue(frontIdx > groundTop, "CYAN prism #$idx front face (idx=$frontIdx) must draw after ground top (idx=$groundTop)")
        }
    }
    ```
    Run; **expected: AC-13 FAILS** under `2e29dc5` HEAD by the same wall-vs-floor cmpPath=0 mechanism.

R4. **Red — reframe AC-2 + add AC-3 case (g)**: in `PathTest.kt`:
    1. Delete the existing `closerThan returns zero for genuinely coplanar non-overlapping faces` test.
    2. Add `\`closerThan returns zero for coplanar overlapping faces\`` — geometry: two identical paths in the same z=1 plane (e.g., `Path(Point(0,0,1), Point(1,0,1), Point(1,1,1), Point(0,1,1))` constructed twice). Asserts `assertEquals(0, result)`.
    3. Add `\`closerThan resolves coplanar non-overlapping via X-extent minimax\`` — the original AC-2 geometry (two tops at z=1 with disjoint X ranges). Asserts `assertTrue(result != 0)` and documents the sign reflects screen-x ordering.
    4. Add `\`closerThan resolves wall-vs-floor straddle via Z-extent minimax\`` — the AC-3 case (g) test from amendment-2 § Affected Areas.
    5. Update KDocs on the five remaining `closerThan` tests (`hq-right vs factory-top`, `X-adjacent`, `Y-adjacent`, `top vs vertical side at equal heights`, `diagonally offset`) to explain which Newell cascade step now resolves each case.

    Run; **expected: 3 of the 4 new tests FAIL** under `2e29dc5` HEAD (the wall-vs-floor straddle test fails with result=0 — the bug; the coplanar-non-overlapping test fails because permissive `result > 0` returns 0 in BOTH directions for two non-overlapping coplanar faces, sum=0; the coplanar-overlapping test passes accidentally for the same reason). After R5 the wall-vs-floor test passes via Newell Z-step, the coplanar-non-overlapping test passes via Newell X-step, and the coplanar-overlapping test continues to pass via Newell step 7's deferred-fallback returning 0.

R5. **Green — implement Newell cascade in `Path.kt`**:
    1. Delete the existing private `countCloserThan` function (lines 117–146) and its KDoc.
    2. Replace `closerThan`'s body (lines 99–101) with the Newell cascade:
       ```kotlin
       fun closerThan(pathA: Path, observer: Point): Int {
           val angle = kotlin.math.PI / 6.0  // standard isometric projection angle
           val eps = 0.000001                // matches the existing Path.kt epsilon

           // Step 1: Z-extent (iso-depth) minimax.
           // depth(angle) = x*cos(angle) + y*sin(angle) - 2*z; LARGER value = FARTHER.
           val selfZ = points.map { it.depth(angle) }
           val aZ    = pathA.points.map { it.depth(angle) }
           val selfZmin = selfZ.min(); val selfZmax = selfZ.max()
           val aZmin    = aZ.min();    val aZmax    = aZ.max()
           if (selfZmax < aZmin - eps) return  1   // self entirely closer (lower depth = closer)
           if (aZmax < selfZmin - eps) return -1   // pathA entirely closer

           // Step 2: X-extent (screen-x) minimax. screenX(p) = (p.x - p.y) * cos(angle).
           // ... analogous to step 1 ...
           // Step 3: Y-extent (screen-y) minimax. screenY(p) = (p.x + p.y) * sin(angle) - p.z.
           // ... analogous ...

           // Step 4: screen polygon overlap (defensive — DepthSorter already gates on this).
           val selfScreen = points.map { Point((it.x - it.y) * kotlin.math.cos(angle), (it.x + it.y) * kotlin.math.sin(angle) - it.z, 0.0) }
           val aScreen    = pathA.points.map { Point((it.x - it.y) * kotlin.math.cos(angle), (it.x + it.y) * kotlin.math.sin(angle) - it.z, 0.0) }
           if (!IntersectionUtils.hasInteriorIntersection(selfScreen, aScreen)) return 0

           // Step 5: plane-side forward — all of self's vertices on opposite side of pathA's plane from observer.
           val sFwd = signOfPlaneSide(pathA, observer)
           if (sFwd != 0) return sFwd

           // Step 6: plane-side reverse.
           val sRev = pathA.signOfPlaneSide(this, observer)
           if (sRev != 0) return -sRev

           // Step 7: polygon split — DEFERRED (amendment-2 directive d).
           return 0
       }
       ```
    3. Add the private helper `signOfPlaneSide(pathA, observer): Int` lifted from the existing `countCloserThan` plane-side machinery, modified to return `+1`/`-1`/`0` based on STRICT all-on-same-side (Newell's plane-side test, not the round-1 permissive variant):
       ```kotlin
       private fun signOfPlaneSide(pathA: Path, observer: Point): Int {
           val AB = Vector.fromTwoPoints(pathA.points[0], pathA.points[1])
           val AC = Vector.fromTwoPoints(pathA.points[0], pathA.points[2])
           val n  = Vector.crossProduct(AB, AC)
           val OA = Vector.fromTwoPoints(Point.ORIGIN, pathA.points[0])
           val OU = Vector.fromTwoPoints(Point.ORIGIN, observer)
           val d  = Vector.dotProduct(n, OA)
           val obsSide = Vector.dotProduct(n, OU) - d

           var anyPositive = false; var anyNegative = false
           for (p in points) {
               val OP = Vector.fromTwoPoints(Point.ORIGIN, p)
               val pSide = Vector.dotProduct(n, OP) - d
               val signed = obsSide * pSide
               if (signed >  0.000001) anyPositive = true
               if (signed < -0.000001) anyNegative = true
           }
           return when {
               anyPositive && !anyNegative ->  1   // all on observer side → self is "closer"
               anyNegative && !anyPositive -> -1   // all on opposite side → self is "farther"
               else                         ->  0   // mixed → fall through to next cascade step
           }
       }
       ```
    4. Update the KDoc on `closerThan` to reference Newell's 1972 paper, document the cascade, and briefly catalogue the three superseded failure modes.

    Re-run the full `:isometric-core:test` suite. **Expected: 187 + 4 = 191 tests, all green** (187 pre-amendment-2 baseline + AC-12 + AC-13 + AC-3 case (g) + reframed AC-2 split into 2 cases minus 1 deleted = net +4). The five existing closerThan tests should still pass — if any flips sign under Newell, fix the sign convention in the cascade, NOT the test assertion.

R6. **Green — revert round-3 DEPTH_SORT_DIAG instrumentation in `DepthSorter.kt`**: delete:
    - `private const val DIAG = true` (line 19) and the surrounding comment block (lines 13–18).
    - `private fun first3(points)` helper (lines 21–27).
    - `if (DIAG) System.err.println("DepthSortDiag FRAME START itemCount=$length")` (line 56).
    - The ORDER emission inside the Kahn loop (lines 120–125).
    - The ORDER-CYCLE emission inside the cycle fallback (lines 141–146).
    - `if (DIAG) System.err.println("DepthSortDiag FRAME END")` (line 151).
    - The per-pair emission inside `checkDepthDependency` (lines 196–202) and the `var edgeLabel = "none"` / `edgeLabel = "i->j"` / `edgeLabel = "j->i"` book-keeping that exists solely to feed it.

    **Keep** the `var intersects` / `var cmpPath` locals captured by the round-3 refactor (lines 175–195, minus the edgeLabel) — slight readability win, no behavioural difference. Implementer may roll back to the inline form if it reads cleaner; both are acceptable.

    Re-run the full test suite. **Expected: still 191 green** (DIAG was logging-only; reverting it must not change any test outcome).

R7. **Regression sweep**:
    - Run `./gradlew :isometric-core:test :isometric-compose:test`. All 191 tests must be green.
    - Run `./gradlew :isometric-compose:recordPaparazzi` ON LINUX CI (Windows blank-render gap is environmental and remains out of scope) to regenerate the four amendment-1 baselines. Inspect each: `nodeIdRowScene.png`, `onClickRowScene.png`, `longPressGridScene.png`, `alphaSampleScene.png` should each show NO missing vertical faces — AC-14 visual confirmation. If any baseline shows a regression on a previously-passing scene, pause and review before re-baselining.
    - Run `./gradlew :app:installDebug` (with the documented Windows IC-cache-corruption workaround if applicable) and re-run the Maestro flows `02-longpress.yaml` and `03-alpha.yaml` for live confirmation. Capture screenshots into `verify-evidence/post-newell/`.
    - Cross-check that the existing `cycle fallback` `DepthSorterTest` case still passes — confirms polygon-split deferral (per amendment-2 directive d) is safe.

R8. **Atomic commit**: `fix(depth-sort): replace closerThan with Newell Z->X->Y minimax cascade`. Stage:
    - `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt` — Newell cascade in place of vote-and-subtract.
    - `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt` — round-3 DIAG instrumentation reverted.
    - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt` — AC-2 reframed split, AC-3 case (g) added, KDoc updates on five existing tests.
    - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt` — AC-12, AC-13 added.
    - (optional) regenerated Paparazzi PNGs under `isometric-compose/src/test/snapshots/images/` if regenerated on Linux CI as part of this commit; otherwise let CI regenerate post-merge.

    Commit message:
    ```
    fix(depth-sort): replace closerThan with Newell Z->X->Y minimax cascade

    Three rounds of patching the ad-hoc plane-side comparator each surfaced
    a distinct regression class: round 1 (3e811aa) replaced an integer-
    division collapse that under-determined shared-edge sorts; round 2
    (9cef055) added a screen-overlap gate to suppress over-aggressive
    topological edges in 3x3 grid layouts; round 3 (verify-evidence/
    round3-longpress-diag.log) traced a third class — wall-vs-floor face
    pairs where each polygon straddles the other's plane on its respective
    observer-axis, both countCloserThan directions return 1, the signed
    comparator cancels to 0, no edge is added, and Kahn's algorithm falls
    back to a depth-descending centroid pre-sort that paints ground over
    wall.

    Replace the predicate with Newell's canonical Z->X->Y minimax cascade:
    iso-depth extent disjoint -> return non-zero from step 1; screen-x
    extent disjoint -> step 2; screen-y extent disjoint -> step 3; screen
    polygon non-overlap -> return 0 (no draw-order constraint); strict
    plane-side test forward -> step 5; reverse -> step 6; polygon split
    deferred (Kahn's append-on-cycle fallback handles residual cycles).
    Each cascade step terminates with a definitive non-zero sign or
    continues; only mixed-straddle pairs that pass all six steps without
    decision return 0, which is the genuinely-ambiguous case Newell's
    algorithm cannot resolve without polygon splitting.

    countCloserThan is removed; its plane-side machinery is lifted into a
    private helper signOfPlaneSide that implements Newell's STRICT all-on-
    same-side test (returns 0 unless every vertex agrees, falls through to
    the next cascade step otherwise).

    Reverts the round-3 DEPTH_SORT_DIAG instrumentation in DepthSorter.kt
    (commit 2e29dc5) which served only to diagnose the cmpPath=0 case
    Newell now resolves by construction.

    Adds AC-12 (LongPressSample full-scene integration test asserting
    back-right cube vertical faces draw after ground top), AC-13 (Alpha-
    Sample full-scene integration test asserting each CYAN prism's
    vertical faces draw after ground top), AC-3 case (g) (wall-vs-floor
    straddle PathTest case asserting Newell Z-step returns non-zero).
    Reframes AC-2 by splitting into "coplanar overlapping returns 0"
    (Newell step 7 deferred-fallback) and "coplanar non-overlapping
    returns non-zero via X-extent minimax" (Newell step 2). Updates KDoc
    on five existing closerThan tests to document which Newell step now
    resolves each case.

    Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
    ```

---

**Amendment-1 steps (NEW — execute these in this order):**

A1. **Pre-flight (amendment)**: confirm `isometric-core/.../Path.kt` and `isometric-core/.../DepthSorter.kt` are at commit `3e811aa` state (both files match `git show HEAD:<path>`). Confirm 183 isometric-core tests green. The diagnostic logging from yesterday's session must be REVERTED already (verified: working tree clean for both files).

A2. **Red — failing AC-9 test**: in `DepthSorterTest.kt`, add:
    ```kotlin
    @Test
    fun `WS10 LongPress 3x3 grid back-right cube vertical faces are not drawn first`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), IsoColor.LIGHT_GRAY)
        for (i in 0 until 9) {
            val row = i / 3; val col = i % 3
            engine.add(
                Prism(Point(col * 1.8, row * 1.8, 0.1), 1.2, 1.2, 1.0),
                IsoColor((col + 1) * 80.0, (row + 1) * 80.0, 150.0)
            )
        }
        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // Identify back-right cube's vertical faces by vertex geometry
        val backRightFront = scene.commands.indexOfFirst { cmd ->
            cmd.path.points.all { it.y in 3.59..3.61 } &&
                cmd.path.points.any { it.x in 3.59..3.61 } &&
                cmd.path.points.any { it.x in 4.79..4.81 }
        }
        val backRightLeft = scene.commands.indexOfFirst { cmd ->
            cmd.path.points.all { it.x in 3.59..3.61 } &&
                cmd.path.points.any { it.y in 3.59..3.61 } &&
                cmd.path.points.any { it.y in 4.79..4.81 }
        }

        assertTrue(backRightFront >= 3, "back-right cube's front face must not be at output positions 0-2; was at $backRightFront")
        assertTrue(backRightLeft >= 3, "back-right cube's left face must not be at output positions 0-2; was at $backRightLeft")
    }
    ```
    Run; confirm it FAILS (current `3e811aa` state has back-right vertical faces at positions 0–2 per the diagnostic).

A3. **Red — failing AC-10 test**: in `IntersectionUtilsTest.kt`, add:
    ```kotlin
    @Test
    fun `hasInteriorIntersection returns false for polygons sharing only an edge`() {
        val polyA = listOf(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0), Point(1.0, 1.0, 0.0), Point(0.0, 1.0, 0.0))
        val polyB = listOf(Point(1.0, 0.0, 0.0), Point(2.0, 0.0, 0.0), Point(2.0, 1.0, 0.0), Point(1.0, 1.0, 0.0))
        assertFalse(IntersectionUtils.hasInteriorIntersection(polyA, polyB))
        assertTrue(IntersectionUtils.hasIntersection(polyA, polyB), "old hasIntersection still returns true for shared-edge case (regression marker)")
    }

    @Test
    fun `hasInteriorIntersection returns false for polygons sharing only a vertex`() { /* analogous */ }

    @Test
    fun `hasInteriorIntersection returns true for genuine interior overlap`() { /* analogous, returns true */ }
    ```
    Run; confirm: (a) the new tests fail because `hasInteriorIntersection` doesn't exist yet; (b) the regression-marker assertion would FAIL too if we used the old function.

A4. **Green — implement `hasInteriorIntersection`**: in `IntersectionUtils.kt`, add:
    ```kotlin
    /**
     * Tests whether two convex polygons have a non-trivial interior overlap area.
     *
     * Stricter than [hasIntersection]: returns false when polygons share only an
     * edge or vertex (boundary touch with zero interior overlap). Used by
     * [DepthSorter.checkDepthDependency] to gate topological-edge insertion —
     * boundary-touch pairs do not need a draw-order edge because they don't
     * actually overpaint each other.
     */
    fun hasInteriorIntersection(pointsA: List<Point>, pointsB: List<Point>): Boolean {
        if (!hasIntersection(pointsA, pointsB)) return false

        // hasIntersection passed; now require at least one of:
        //   (1) a strictly-interior point of one polygon inside the other, OR
        //   (2) a strictly-interior edge crossing (already strict in hasIntersection's SAT path)
        // For boundary-only contact, hasIntersection's containment-fallback returns true
        // via isPointInPoly returning true for a vertex on the boundary, but no STRICT
        // crossing exists. We re-check that explicitly here.

        // ... implementation: invoke a strict-interior test (SAT with strict <0 thresholds
        // and isPointInPoly with ε exclusion of boundary) ...
    }
    ```
    Implement the strict-interior logic. Run AC-10 tests; confirm green.

A5. **Green — wire the gate**: in `DepthSorter.kt:133-136`, change:
    ```kotlin
    if (IntersectionUtils.hasIntersection(  // OLD
    ```
    to:
    ```kotlin
    if (IntersectionUtils.hasInteriorIntersection(  // NEW
    ```
    Update the surrounding comment to mention the gate's new strictness. Run AC-9 test from step A2; confirm green. Run the full `:isometric-core:test` suite; confirm 183 pre-existing tests + AC-9 + 3 AC-10 cases all green.

A6. **Replace Paparazzi snapshot test (AC-11)**: in `IsometricCanvasSnapshotTest.kt`, REMOVE the single `nodeIdSharedEdge()` test added at original-scope step 8. ADD four new tests:
    - `nodeIdRowScene()` — uses the existing `WS10NodeIdScene` factory.
    - `onClickRowScene()` — uses new `OnClickRowScene` factory (with one shape selected/elevated to test the height-change case).
    - `longPressGridScene()` — uses new `LongPressGridScene` factory (default state, no long-press; this is where the regression manifests).
    - `alphaSampleScene()` — uses new `AlphaSampleScene` factory.

    Create the three new scene factories under `isometric-compose/src/test/kotlin/.../scenes/`. Run `recordPaparazzi` on Linux CI (or accept the Windows blank-render gap and wait for CI). Commit four new baseline PNGs.

A7. **Maestro flow snapshots**: the existing maestro flows at `.ai/workflows/depth-sort-shared-edge-overpaint/maestro/{01-onclick.yaml, 02-longpress.yaml, 03-alpha.yaml}` produced screenshots in `verify-evidence/`. After the fix lands, re-run them and compare against the post-fix expected behaviour. Capture into `verify-evidence/post-fix/` for the verify stage.

A8. **Atomic commit**: `fix(depth-sort): screen-overlap gate for 3x3 grid edge-cases`. Stage:
    - `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`
    - `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`
    - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`
    - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`
    - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/OnClickRowScene.kt`
    - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/LongPressGridScene.kt`
    - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/AlphaSampleScene.kt`
    - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`
    - 4 baseline PNGs under `isometric-compose/src/test/snapshots/images/`

    Commit message:
    ```
    fix(depth-sort): screen-overlap gate for 3x3 grid edge-cases

    The permissive `result > 0` threshold landed in 3e811aa correctly resolves
    the WS10 NodeIdSample shared-edge case, but its asymmetric "winning votes"
    in countCloserThan fire even for face pairs whose 2D iso-projected polygons
    don't actually overlap. In 3x3 grid layouts (LongPressSample, AlphaSample),
    multiple such spurious votes accumulate as topological edges in
    DepthSorter.checkDepthDependency, pushing corner cubes' vertical faces to
    output positions 0-2 where they get painted over by faces drawn afterward.
    Visible regression: the back-right cube of the 3x3 grid renders with only
    its top face visible.

    Add IntersectionUtils.hasInteriorIntersection — stricter than hasIntersection;
    rejects boundary-only contact (shared edges, shared vertices, no interior
    overlap area). Wire DepthSorter.checkDepthDependency to gate topological-edge
    insertion on this stricter test. The closerThan algorithm itself is unchanged;
    only the gate that decides which closerThan results matter changes.

    Adds AC-9 (3x3 grid integration test asserting corner cubes' vertical faces
    are not at output positions 0-2), AC-10 (hasInteriorIntersection unit cases
    for boundary-only contact), AC-11 (four scene-factory Paparazzi snapshots
    replacing the single nodeIdSharedEdge baseline).

    Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
    ```

---

**Original-scope steps (already executed in commit `3e811aa`)**:

1. **Pre-flight**: confirm current branch is `feat/ws10-interaction-props`; confirm `git status` shows only the build-logic CC fix as uncommitted modification (no other surprises). Run `:isometric-core:test` once to confirm 172 tests pass on the pre-fix baseline.

2. **Red — write failing test**. In `PathTest.kt`, add:

   ```kotlin
   @Test
   fun `closerThan returns nonzero for hq-right vs factory-top shared-edge case`() {
       // Reproduces the WS10 NodeIdSample bug: hq right side (vertical at x=1.5,
       // z spans 0.1..3.1) vs factory top (horizontal at z=2.1, x spans 2.0..3.5).
       val hqRight = Path(
           Point(1.5, 1.0, 0.1), Point(1.5, 2.5, 0.1),
           Point(1.5, 2.5, 3.1), Point(1.5, 1.0, 3.1)
       )
       val factoryTop = Path(
           Point(2.0, 1.0, 2.1), Point(3.5, 1.0, 2.1),
           Point(3.5, 2.5, 2.1), Point(2.0, 2.5, 2.1)
       )
       val observer = Point(-10.0, -10.0, 20.0)
       val result = factoryTop.closerThan(hqRight, observer)
       // Expected sign: factory_top is "closer" by Newell semantics because
       // hq_right has 2 of 4 vertices on observer side of factory_top's plane.
       assertTrue(result > 0, "closerThan should return positive (factory_top wins as 'closer'); got $result")
   }
   ```

   Run `:isometric-core:test --tests PathTest`. Confirm this test fails with `result == 0`.

3. **Green — apply fix**. Edit `Path.kt`, `countCloserThan` (lines 127-140):

   - Change epsilon `0.000000001` → `0.000001` in both occurrences (lines 128 and 132).
   - Replace the return block:
     ```kotlin
     // Before
     return if (result == 0) {
         0
     } else {
         (result + result0) / points.size
     }
     // After — permissive: any vertex on observer side of pathA's plane wins
     return if (result > 0) 1 else 0
     ```
   - Update KDoc (line 105):
     ```kotlin
     /**
      * Count whether any vertex of this path is on the same side of pathA's plane
      * as the observer (within a 1e-6 epsilon to absorb floating-point noise).
      *
      * Returns 1 when at least one vertex is strictly observer-side, 0 otherwise.
      * Used by [closerThan] which subtracts both directions to produce a signed
      * comparator: positive if `this` is closer than pathA, negative if farther.
      *
      * Permissive ("any vertex" rather than "majority") was chosen to match Newell
      * semantics: A is closer than B if at least one of A's vertices lies on the
      * observer's side of B's plane. The previous integer-division `(result + result0)
      * / points.size` collapsed mixed cases (e.g. 2 of 4 same-side) to 0, producing
      * spurious "tied" results that the topological sort then could not resolve —
      * see workflow `depth-sort-shared-edge-overpaint` for the full diagnosis.
      */
     ```

   Re-run the test from step 2; confirm green.

4. **Layered unit tests**. In `PathTest.kt`, add a parameterised set covering AC-3 canonical adjacency pairings:

   - Two prisms sharing an X-face (left/right adjacent at the same y, same height).
   - Two prisms sharing a Y-face (front/back adjacent at the same x, same height).
   - One prism's top vs adjacent prism's vertical side, equal heights.
   - One prism's top vs adjacent prism's vertical side, *different* heights (the diagnosed case generalised).
   - Two prisms with truly coplanar non-overlapping faces (genuine tie → assert returns 0).

   Each as its own `@Test fun \`...\`()` with `assertTrue(result > 0, ...)` or `assertEquals(0, result, ...)`.

5. **Integration test for AC-4**. In `DepthSorterTest.kt`, add:

   ```kotlin
   @Test
   fun `WS10 NodeIdSample four buildings render in correct front-to-back order`() {
       val engine = IsometricEngine()
       val buildings = listOf(
           Triple("hq",       Point(0.0, 1.0, 0.1), 3.0),
           Triple("factory",  Point(2.0, 1.0, 0.1), 2.0),
           Triple("warehouse",Point(4.0, 1.0, 0.1), 1.5),
           Triple("tower",    Point(6.0, 1.0, 0.1), 4.0),
       )
       buildings.forEach { (_, pos, h) ->
           engine.add(Prism(pos, width = 1.5, depth = 1.5, height = h), IsoColor.BLUE)
       }
       val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

       // For every adjacent-building face pair where one is closer to observer,
       // the closer face's command appears LATER in the command list (drawn on top).
       // Specifically: hq's right side face must be drawn AFTER factory's top face.
       // (Test inspects scene.commands order; index of hq_right > index of factory_top.)
       // ... implementation of the order-inspection helper here ...
   }
   ```

6. **Antisymmetry invariant for AC-7**. In `DepthSorterTest.kt`, add:

   ```kotlin
   @Test
   fun `closerThan is antisymmetric for representative non-coplanar pairs`() {
       val pairs = listOf(/* hq_right + factory_top, plus 4-5 other shared-edge pairs */)
       val observer = Point(-10.0, -10.0, 20.0)
       pairs.forEach { (a, b) ->
           val ab = a.closerThan(b, observer)
           val ba = b.closerThan(a, observer)
           assertEquals(0, ab + ba, "closerThan must be antisymmetric for $a vs $b")
       }
   }
   ```

7. **New `IntersectionUtilsTest.kt`**. Create file. Cover: (a) two completely disjoint 2D polygons → false; (b) two overlapping 2D polygons → true; (c) two 2D polygons sharing only an edge (boundary touch, no interior overlap) → behaviour TBD by current implementation, document it. This is *baseline* coverage; not directly fixing the bug but creates the test surface for a future AABB-minimax workflow.

8. **Snapshot test infrastructure**. Create `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt`:

   ```kotlin
   package io.github.jayteealao.isometric.compose.scenes

   import androidx.compose.runtime.Composable
   import io.github.jayteealao.isometric.IsoColor
   import io.github.jayteealao.isometric.Point
   import io.github.jayteealao.isometric.compose.runtime.IsometricSceneScope
   import io.github.jayteealao.isometric.compose.runtime.Shape
   import io.github.jayteealao.isometric.shapes.Prism

   /**
    * The 4-building scene from WS10 NodeIdSample, extracted as a reusable test factory.
    * Use inside `IsometricScene { WS10NodeIdScene() }` to render the canonical bug case.
    */
   @Composable
   fun IsometricSceneScope.WS10NodeIdScene() {
       Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 10.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
       Shape(geometry = Prism(Point(0.0, 1.0, 0.1), 1.5, 1.5, 3.0), color = IsoColor.BLUE)
       Shape(geometry = Prism(Point(2.0, 1.0, 0.1), 1.5, 1.5, 2.0), color = IsoColor.ORANGE)
       Shape(geometry = Prism(Point(4.0, 1.0, 0.1), 1.5, 1.5, 1.5), color = IsoColor.GREEN)
       Shape(geometry = Prism(Point(6.0, 1.0, 0.1), 1.5, 1.5, 4.0), color = IsoColor.PURPLE)
   }
   ```

   Then in `IsometricCanvasSnapshotTest.kt`, append:

   ```kotlin
   @Test
   fun nodeIdSharedEdge() {
       paparazzi.snapshot {
           Box(modifier = Modifier.size(800.dp, 600.dp)) {
               IsometricScene { WS10NodeIdScene() }
           }
       }
   }
   ```

   Run `./gradlew :isometric-compose:recordPaparazzi`. The new PNG appears at `isometric-compose/src/test/snapshots/images/io.github.jayteealao.isometric.compose_IsometricCanvasSnapshotTest_nodeIdSharedEdge.png`. **Manually inspect** the PNG to confirm: no factory-top-over-hq-right artefact; back-to-front order looks correct.

9. **Atomic commit**. Stage:
   - `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
   - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`
   - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`
   - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`
   - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt`
   - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`
   - `isometric-compose/src/test/snapshots/images/io.github.jayteealao.isometric.compose_IsometricCanvasSnapshotTest_nodeIdSharedEdge.png`

   Commit message:

   ```
   fix(depth-sort): permissive countCloserThan threshold + 1e-6 epsilon

   Path.countCloserThan previously collapsed a per-vertex plane-side vote
   into an integer fraction via integer division: (result + result0) / points.size.
   For a 4-vertex face where 2 vertices were on the observer side of the
   other plane, this evaluated to 2/4 = 0, discarding a real signal and
   reporting an ambiguous tie to closerThan. DepthSorter then added no
   topological edge for the pair, the back-to-front pre-sort by Path.depth
   became the sole determiner, and a farther face could be painted over a
   closer one at their shared screen-space corner. Visible regression in
   the WS10 NodeIdSample: factory's top face painted over hq's right side.

   Replace the integer-division collapse with a permissive sign-preserving
   threshold: countCloserThan returns 1 when any vertex of `this` is on
   the observer side of pathA's plane, 0 otherwise. Loosen the
   plane-side epsilon from 1e-9 to 1e-6 to absorb floating-point noise
   for the project's typical 0..100 coordinate range.

   Adds layered regression coverage: closerThan unit cases for canonical
   shared-edge pairings, DepthSorter integration test for the four-building
   scene, antisymmetry invariant test, IntersectionUtils baseline, and a
   Paparazzi snapshot of the WS10 NodeIdSample geometry.

   Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
   ```

## Test / Verification Plan

### Automated checks (post-amendment-2 expected state)

- **Lint/typecheck**: `./gradlew :isometric-core:assemble :isometric-compose:assemble` — confirms compile-clean. Newell cascade adds no new external dependencies; PI/6 and trig come from `kotlin.math`.
- **Unit tests**: `./gradlew :isometric-core:test` should run **191 tests** (187 from amendment-1 baseline + AC-12 + AC-13 + AC-3 case (g) + reframed AC-2 split into two tests minus 1 deleted = +4 net), all green:
  - **From original scope (already passing in `3e811aa`):** PathTest 5 closerThan tests (AC-1 hq-right, X-adjacent, Y-adjacent, top-vs-side equal-height, diagonally offset). Each was previously decided by permissive vote-and-subtract; now decided by a Newell cascade step (Z, X, Y, plane-side, or X). KDocs updated to reflect this; assertion direction (`assertTrue(result > 0, ...)`) preserved IF Newell's sign matches.
  - **From amendment-1 (already passing in `9cef055`):** DepthSorterTest AC-9 (3x3 grid corner), IntersectionUtilsTest AC-10 (3 boundary-touch cases). Newell does not change these — AC-9 was about edge-insertion which still happens via the unchanged gate; AC-10 is internal to IntersectionUtils.
  - **New (amendment-2):**
    - `DepthSorterTest.WS10 LongPress full scene: back-right cube vertical faces draw after ground top` (AC-12).
    - `DepthSorterTest.WS10 Alpha full scene: each CYAN prism vertical faces draw after ground top` (AC-13).
    - `PathTest.closerThan resolves wall-vs-floor straddle via Z-extent minimax` (AC-3 case g).
    - `PathTest.closerThan returns zero for coplanar overlapping faces` (reframed AC-2 case 1).
    - `PathTest.closerThan resolves coplanar non-overlapping via X-extent minimax` (reframed AC-2 case 2).
- **Snapshot verification**: `./gradlew :isometric-compose:verifyPaparazzi` — must pass for the four amendment-1 baselines:
  - `IsometricCanvasSnapshotTest_nodeIdRowScene.png`
  - `IsometricCanvasSnapshotTest_onClickRowScene.png`
  - `IsometricCanvasSnapshotTest_longPressGridScene.png` — **primary AC-14 visual confirmation**
  - `IsometricCanvasSnapshotTest_alphaSampleScene.png`
  - All four baselines need REGENERATION on Linux CI after the Newell adoption lands (they were committed under `9cef055` but were never visually correct on the LongPress side; AC-14 supersedes amendment-1's AC-11 by enumerating which specific cubes must show their vertical faces).
- **Regression**: existing `DepthSorterTest` cases (`coplanar adjacent prisms`, `coplanar tile grid` broad-phase parity, `cycle fallback`, `kahn algorithm preserves existing broad phase sparse test`, the original WS10 NodeIdSample integration test) must remain green. Newell's stricter plane-side test (all-on-same-side vs amendment-1's any-on-same-side) MUST NOT break the coplanar-tile-grid case — verified by the explicit cycle-fallback test, which exercises exactly the non-decisive case Newell defers via step 7.

### Interactive verification (human-in-the-loop)

- **AC-14 — visual diff inspection of the four Paparazzi snapshots** (supersedes amendment-1 AC-11 visual checks):
  - Platform: Linux CI runner (Paparazzi 1.3.0 on JDK 17 — the Windows render-blank gap is environmental and out of scope).
  - Tool: `./gradlew :isometric-compose:recordPaparazzi`, then open each PNG in any image viewer.
  - Steps: run `recordPaparazzi`; locate the four new PNGs; visually inspect at the boundaries documented in amendment-2 § AC-14:
    - `longPressGridScene.png`: back-right cube must show its top, front, and left faces all visible (was: only top face visible — the round-3 regression).
    - `alphaSampleScene.png`: each of the three CYAN prisms must show its top, front, and left faces (round-3 predicted same regression class).
    - `nodeIdRowScene.png`: factory's top face must NOT overpaint hq's right side (the original WS10 case; should remain correct under Newell).
    - `onClickRowScene.png`: no missing vertical faces on any of the 5 row prisms.
  - Pass criteria: every cube in every snapshot displays the visible faces it should display per its geometric position; no missing-face artefacts at any building boundary.
  - Evidence capture: the four PNGs themselves, committed to git as the AC-14 baselines.

- **End-to-end verification on the actual sample app**:
  - Platform: Android emulator `emulator-5554`.
  - Tool: rebuild + reinstall via `./gradlew :app:installDebug` (with the build-logic Windows workaround), then re-run the Maestro flows `.ai/workflows/depth-sort-shared-edge-overpaint/maestro/02-longpress.yaml` and `03-alpha.yaml` (already exist from the round-1/round-2 verify stages; reusable as-is).
  - Steps: build → install → maestro test → compare new screenshots to the round-3 evidence captures (`verify-evidence/round3-longpress-diag.log`'s associated screenshots show the bug; post-fix screenshots should show all faces visible).
  - Pass criteria: LongPress sample's back-right cube shows top + front + left faces (not just top); Alpha sample's three CYAN prisms each show top + front + left.
  - Evidence capture: `verify-evidence/post-newell/longpress-fixed.png`, `alpha-fixed.png`.

### Risks / Watchouts

**Amendment-2 risks:**

- **Sign-convention drift in the Newell cascade.** The existing `closerThan` returns POSITIVE when "this is closer than pathA" (per the docstring). Newell's Z-extent step says: if `selfZmax < aZmin`, self has lower iso-depth → self is FARTHER (because higher `point.depth(angle)` = farther under the `x*cos + y*sin - 2z` formula, so the comparison `selfZmax < aZmin` actually means self has SMALLER depth → CLOSER). Implementer's first run on AC-1 will catch sign errors; if the test flips, FIX the sign in the cascade, NOT the test assertion.
- **`Point.depth(PI/6)` vs `Point.depth()` mismatch.** The codebase has both — `depth()` uses uniform x+y weighting (`x + y - 2z`); `depth(angle)` uses cos/sin weighting (`x*cos + y*sin - 2z`). For the *default* 30° iso projection, these are NOT equivalent: `cos(PI/6) ≈ 0.866`, `sin(PI/6) = 0.5`, vs uniform `1.0` weighting. The cascade should use `depth(PI/6)` to match the engine's projection; using `depth()` would compute extents in a different basis than `IntersectionUtils.hasInteriorIntersection` operates in. Verify the engine's actual projection uses 30° before locking the angle constant.
- **Strict plane-side test breaking previously-passing AC-1 / X-adjacent / Y-adjacent / equal-height / diagonally-offset cases.** Round-1's permissive `result > 0` returned 1 with even ONE observer-side vertex; Newell's strict `signOfPlaneSide` returns non-zero only when ALL vertices agree. For mixed-straddle pairs the strict test returns 0 — but Newell's earlier Z/X/Y minimax steps should have already returned non-zero for these geometries. Confirm by reading sub-agent 1's prior arithmetic (PathTest § hq-right): hq's z=[0.1, 3.1] and factory's z=2.1 plane → Z-extents overlap → continue → X-extents disjoint → return non-zero from step 2. The plane-side step is never reached. Same reasoning applies to the four other cases. **If any test fails after R5, the failure mode is most likely a sign-convention drift, not a Newell-cascade-incorrect issue** — debug accordingly.
- **Polygon-split deferral risk (cycles).** Newell's classical formulation handles residual cycles by splitting one polygon along the other's plane and re-sorting fragments. The plan defers step 7 (`return 0`) and trusts Kahn's append-on-cycle fallback. If AC-12/AC-13 verification surfaces command-order anomalies that don't match the assertions (e.g., back-right cube's vertical face at output position 0 again, but for a different reason than cmpPath=0), it indicates a cycle that the Kahn fallback resolved by appending the wrong item first. Investigation path: re-enable a minimal `DEPTH_SORT_DIAG` block (without the round-3 footprint) to inspect whether cycles are forming. If yes, escalate to `amend-3` and pull polygon-split into scope. If no, deferral holds.

**Inherited risks (from amendment-1, still relevant):**

- **Build-logic Windows file-lock race** — still active. Workaround unchanged.
- **Paparazzi baselines on Windows** — render blank under the documented IC-cache-corruption issue. Linux CI is authoritative for AC-14.
- **Permissive threshold trade-off** — superseded by Newell. Newell's strict plane-side test removes the spurious-edge-from-noisy-vertex risk; Z/X/Y minimax is robust to single-vertex outliers because it operates on extent extrema, not vote counts.

## Dependencies on Other Slices

None. This is single-plan mode; no sibling plans exist.

## Assumptions

**Amendment-2 assumptions:**

- The standard isometric projection angle in this codebase is 30° (`PI/6`). The cascade uses this angle for both iso-z (`Point.depth(PI/6)`) and inline screen-x/y projection. If the engine uses a different angle, the cascade extent comparisons would be in a slightly different basis from `IntersectionUtils.hasInteriorIntersection` — verify by reading `IsometricEngine.projectScene(...)` before locking the constant in R5.
- The five existing `closerThan` PathTest cases (AC-1 et al.) currently asserting `assertTrue(result > 0, ...)` will continue to assert `result > 0` under Newell. If the sign flips on first run, the cascade has a sign-convention drift; fix the cascade.
- Polygon split (cascade step 7) is not needed for the diagnosed scenes. The verify stage will confirm; if any AC-12/AC-13 command-order assertion fails because of a cycle, escalate to amend-3 and pull split into scope.
- The four amendment-1 Paparazzi baselines (`nodeIdRowScene`, `onClickRowScene`, `longPressGridScene`, `alphaSampleScene`) were correct on the row-layout cases (AC-11) but NOT on LongPress / Alpha (AC-14 reveals the missing-faces regression). They will be regenerated on Linux CI after the Newell fix lands.
- The round-3 DIAG block in `DepthSorter.kt` is logging-only — reverting it changes no test outcome. Verified by the round-3 implement log: "187 tests still green at HEAD `2e29dc5`".

**Inherited assumptions** (from prior revisions):

- The user's permissive-threshold choice (Round 2 of original shape) was correct as a partial fix; Newell SUPERSEDES it but does not invalidate the work.
- The build-logic CC fix in working tree continues to suffice for local Windows builds; ride along as a separate `chore(build-logic)` commit at handoff if appropriate.

## Blockers

None.

---

### Automated checks (post-amendment-1 expected state)

- **Lint/typecheck**: `./gradlew :isometric-core:assemble :isometric-compose:assemble` — confirms compile-clean.
- **Unit tests**: `./gradlew :isometric-core:test` should run **187 tests** (183 from pre-amendment-1 baseline + 1 AC-9 + 3 AC-10 cases), all green:
  - **From original scope (already passing in `3e811aa`):** PathTest AC-1, AC-2, AC-3 cases, DepthSorterTest AC-4 (WS10 NodeIdSample), AC-7 (antisymmetry), IntersectionUtilsTest 3 baseline cases.
  - **New (amendment-1):**
    - `DepthSorterTest.WS10 LongPress 3x3 grid back-right cube vertical faces are not drawn first` (AC-9).
    - `IntersectionUtilsTest.hasInteriorIntersection returns false for polygons sharing only an edge` (AC-10 case 1 + EC-10).
    - `IntersectionUtilsTest.hasInteriorIntersection returns false for polygons sharing only a vertex` (AC-10 case 2).
    - `IntersectionUtilsTest.hasInteriorIntersection returns true for genuine interior overlap` (AC-10 case 3).
- **Snapshot verification**: `./gradlew :isometric-compose:verifyPaparazzi` — must pass for FOUR baselines:
  - `IsometricCanvasSnapshotTest_nodeIdRowScene.png` (replaces `nodeIdSharedEdge.png`).
  - `IsometricCanvasSnapshotTest_onClickRowScene.png` (NEW, AC-11).
  - `IsometricCanvasSnapshotTest_longPressGridScene.png` (NEW, AC-11 — primary regression marker).
  - `IsometricCanvasSnapshotTest_alphaSampleScene.png` (NEW, AC-11).
- **Regression**: existing `DepthSorterTest` cases (`coplanar adjacent prisms`, `coplanar tile grid` broad-phase parity, `cycle fallback`, `kahn algorithm preserves existing broad phase sparse test`) must remain green. The new gate's stricter behaviour MUST NOT break the coplanar-tile-grid case (which relies on edges being added for tile-grid face pairs that DO have interior overlap in screen projection).

### Interactive verification (human-in-the-loop)

- **AC-5 — visual diff inspection of the new Paparazzi snapshot**:
  - Platform: developer machine running Paparazzi (Linux/Mac/Windows JVM, no emulator needed).
  - Tool: `./gradlew :isometric-compose:recordPaparazzi`, then open `isometric-compose/src/test/snapshots/images/io.github.jayteealao.isometric.compose_IsometricCanvasSnapshotTest_nodeIdSharedEdge.png` in any image viewer.
  - Steps: run `recordPaparazzi`; locate the new PNG; visually inspect at the hq/factory boundary.
  - Pass criteria: no orange pixels appear within blue's right-side face area; the four buildings appear in left-to-right back-to-front order with correct occlusion at every adjacency.
  - Evidence capture: the PNG itself, committed to git as the baseline.

- **End-to-end verification on the actual sample (optional but recommended)**:
  - Platform: Android emulator `emulator-5554` (already running this session).
  - Tool: rebuild + reinstall via `./gradlew :app:installDebug` (with the build-logic Windows workaround dance), then re-run the Maestro flow `.ai/workflows/hotfix-long-press-node-id-sort/maestro-verify-nodeid.yaml` (it already navigates to the Node ID tab and screenshots).
  - Steps: build → install → maestro test → compare new screenshot to `04-nodeid-fixed.png` from the hotfix workflow.
  - Pass criteria: the boundary between blue (hq) and orange (factory) shows hq's right side in front of factory's top — no orange overpaint.
  - Evidence capture: pull screenshot via `adb shell screencap -p /sdcard/X.png` (with `MSYS_NO_PATHCONV=1`), save under `.ai/workflows/depth-sort-shared-edge-overpaint/screenshots/`.

### Risks / Watchouts

- **Build-logic Windows file-lock race** is still uncommitted and still active. Local `:isometric-compose:test` runs will hit it. Workaround documented in earlier hotfix logs — `./gradlew --stop && mv build-logic/build build-logic/build.stale-N-$(date +%s)` then build immediately. CI on Linux unaffected.
- **Paparazzi baselines not committed**: only 2 of 13 existing snapshot PNGs are on disk and even those are untracked. The new baseline must be committed explicitly. CI's `verifyPaparazzi` would currently fail for the 11 missing baselines if it were running — flag for a follow-up workflow but DO NOT include in this fix's scope.
- **Permissive threshold trade-off**: `result > 0` means a single vertex on observer side wins. In pathological scenes with extreme coordinate values where floating-point noise puts a vertex on the wrong side of a plane, a spurious dependency edge could be added. The 1e-6 epsilon mitigates; relative-epsilon scaling is the deferred future work.
- **Snapshot pixel diffs in the existing 2 baselines**: `pyramid.png` and `sampleThree.png` are already on disk and untracked. If our fix changes their pixels, we may need to re-record them (and commit them as drive-by improvement) — but per Round 5 strict policy, investigate first. Most likely they are unaffected because their geometry doesn't trigger the integer-division collapse, but verify.

## Dependencies on Other Slices

None. This is single-plan mode; no sibling plans exist.

## Assumptions

- The user's permissive-threshold choice (Round 2) is correct; the failing test in step 2 will be flipped to passing by step 3 with no further iteration. If the test still fails after step 3, return to PLAN to revisit the threshold (specifically check whether `result > 0` actually fires in BOTH directions for the hq-right case — sub-agent 1's math says yes, but verify with the actual print on first run).
- `recordPaparazzi` will succeed locally and produce a stable PNG. If pixel hashing differs across JVM versions or Paparazzi rendering quirks, the baseline may be flaky in CI — mitigated by Paparazzi using a fixed Layoutlib snapshot per version.
- The 11 missing Paparazzi baselines are pre-existing and not caused by this fix. Verified by `git ls-files isometric-compose/src/test/snapshots/` returning empty before this fix.
- The build-logic CC fix in the working tree continues to suffice for local Windows builds. Not committing it as part of this fix; it can be a separate `chore(build-logic):` commit at handoff.

## Blockers

None.

## Freshness Research

Inherited from shape's freshness pass. No additional searches required for plan synthesis. Sources still authoritative:
- [Newell's algorithm — Wikipedia](https://en.wikipedia.org/wiki/Newell%27s_algorithm) — confirms the diagnosed case as the "shared-edge plane-side ambiguity" failure mode.
- [Painter's algorithm — Wikipedia](https://en.wikipedia.org/wiki/Painter%27s_algorithm) — broader context.
- [shaunlebron.github.io/IsometricBlocks](https://shaunlebron.github.io/IsometricBlocks/) — alternative AABB minimax approach (deferred).
- [Paparazzi changelog](https://cashapp.github.io/paparazzi/changelog/) — version 1.3.0 pinned, no upgrade in this fix.
- [Kotlin sortedByDescending — stdlib docs](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/sorted-by-descending.html) — confirms TimSort stability of pre-sort fallback.

## Revision History

### Revision 2 — 2026-04-28T08:15:48Z — Directed Fix (apply amendment-2)

- **Mode:** Directed Fix (`/wf-plan depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint replace closerThan with full Newell Z->X->Y minimax cascade per amend-2`).
- **Source:** `02-shape-amend-2.md` and `05-implement-depth-sort-shared-edge-overpaint.md` § Round 3 + `verify-evidence/round3-longpress-diag.log`.
- **Directives applied:**
  - **(a) Cascade entry-point decision**: replace `closerThan`'s body in place (preserving the `(pathA, observer): Int` signature for AC-8 stability); delete the private `countCloserThan` and lift its plane-side machinery into a private `signOfPlaneSide` helper used by Newell cascade steps 5/6.
  - **(b) PathTest assertion reframings**: enumerated each of the six existing `closerThan` tests against the new Newell semantics in `## Current State`. Five tests preserve their assertion direction with KDoc updates only; AC-2 splits into two tests (coplanar-overlapping returns 0 via deferred step 7; coplanar-non-overlapping returns non-zero via X-extent step 2). AC-3 case (g) added for the new wall-vs-floor straddle case.
  - **(c) DIAG revert scheduling**: the round-3 DEPTH_SORT_DIAG instrumentation in `DepthSorter.kt` (commit `2e29dc5`) is reverted in the SAME atomic commit as the Newell adoption — `fix(depth-sort): replace closerThan with Newell Z->X->Y minimax cascade`. Step R6 of the plan executes this revert immediately after step R5 (Newell implementation).
  - **(d) Polygon-splitting status**: DEFERRED. Cascade step 7 returns 0 unconditionally and Kahn's existing append-on-cycle fallback in `DepthSorter.sort` (lines 139–149) handles any residual cycles. Re-evaluation gate: if AC-12/AC-13 verification surfaces a command-order anomaly attributable to a Kahn cycle, escalate to amend-3 and pull polygon-split into scope.
- **What changed in the plan file:**
  - **Frontmatter**: `metric-files-to-touch` 9 → 6 (amendment-2 extends existing files rather than adding new ones); `metric-step-count` 14 → 22 (added R1–R8 for amendment-2); `revision-count` 1 → 2; `amends` updated to `02-shape-amend-2.md`; `applies-amendment` 1 → 2; new keys `prior-amendments-applied: [1]`, `newell-cascade-entry-point`, `diag-revert-bundling: same-commit-as-newell`, `polygon-split-status: deferred-unless-cycles-observed`; tags expanded with `newell-minimax`, `newell-full`, `cmpPath-zero-fallback`, `algorithmic-restructure`, `diag-revert`; refs added for the round-3 implementation log and evidence; `updated-at` refreshed.
  - **`## Current State`**: prepended a substantial amendment-2 section describing the round-3 finding, the cmpPath=0 wall-vs-floor symmetric-straddle mechanism, the round-3 DIAG-instrumentation present at HEAD `2e29dc5`, and the per-test reframing table for all six existing `closerThan` PathTest cases.
  - **`## Reuse Opportunities`**: prepended amendment-2 reuses — `Point.depth(angle)` for iso-z, the existing `Vector` helpers and `signOfPlaneSide` lifted from `countCloserThan`, `IntersectionUtils.hasInteriorIntersection` retained as cascade step 4, and the existing `DepthSorter` Kahn machinery covering polygon-split deferral via the append-on-cycle fallback.
  - **`## Likely Files / Areas to Touch`**: prepended amendment-2 file list — A2-1 through A2-6, covering the Newell replacement, the DIAG revert, AC-12/AC-13 integration tests, the AC-2 reframe + AC-3 case (g), and the optional explanation doc. Crucially: 0 NEW files (only existing files extended); 4 modified files; +1 optional doc.
  - **`## Proposed Change Strategy`**: prepended an amendment-2 strategy section describing the single atomic commit, the polygon-split deferral, the strict red-green TDD ordering (R1–R8), and the two known risk areas (sign-convention drift, sub-pixel epsilon at extent boundaries).
  - **`## Step-by-Step Plan`**: prepended R1–R8 — pre-flight, 3 red-test rounds (R2 AC-12, R3 AC-13, R4 AC-2 reframe + AC-3 case g), 2 green rounds (R5 Newell implementation, R6 DIAG revert), regression sweep (R7), atomic commit (R8). Each step includes the exact test code or change shape needed.
  - **`## Test / Verification Plan`**: prepended an amendment-2 expected-state section — 191 total tests after fix (187 amendment-1 baseline + 4 net new); AC-14 visual confirmation via the existing four Paparazzi snapshots regenerated on Linux CI; explicit pass criteria for each visual check.
  - **`## Risks / Watchouts`**: added four amendment-2-specific risks (sign-convention drift, depth-formula mismatch, strict-plane-side regression risk, polygon-split-deferral risk) with concrete debug paths for each.
  - **`## Assumptions`**: added five amendment-2 assumptions (projection angle is 30°, sign convention preservation, polygon-split sufficiency, Linux CI authority on Paparazzi, DIAG-revert-is-behaviour-neutral).
  - **`## Recommended Next Stage`**: updated Option A to point at amendment-2 implement; added Option C for an `amend-3` escalation if cycles surface.
- **Why:** The amendment-2 directive is a structural restructure of the comparator — three rounds of patching the permissive-vote-and-subtract approach produced three distinct regression classes, and the user has explicitly chosen the Newell canonical algorithm to bound the future regression-discovery loop. The plan revision applies the four directives surgically: (a) entry point, (b) test reframings, (c) DIAG revert bundling, (d) polygon-split deferral.
- **What is preserved unchanged from revision 1:** the amendment-1 work in `9cef055` (the `IntersectionUtils.hasInteriorIntersection` gate, the AC-9 grid integration test, the AC-10 boundary-touch unit tests, the four scene factories `WS10NodeIdScene` / `OnClickRowScene` / `LongPressGridScene` / `AlphaSampleScene`); the `IntersectionUtils.kt` source (gate is correct as-is); the `DepthSorter.kt` gate-call refactor (locals `intersects` / `cmpPath` retained even after DIAG removal); the original-scope work in `3e811aa` (Path.kt's permissive predicate is REMOVED, but the test scaffolding around it stays — assertions reframed, geometries unchanged).
- **What is invalidated:** the round-1 permissive `countCloserThan` predicate in Path.kt (deleted by R5); the round-3 DIAG instrumentation in DepthSorter.kt (reverted by R6); the original AC-2 single-test framing (split into two tests by R4); amendment-1's AC-11 "no missing vertical faces — vague" framing (superseded by AC-14's explicit cube enumeration); the implicit assumption that the cmpPath=0 case was a numerical-precision issue (it's a structural-symmetry issue Newell resolves by construction).

### Revision 1 — 2026-04-27T22:32:06Z — Directed Fix (apply amendment-1)

- **Mode:** Directed Fix (`/wf-plan depth-sort-shared-edge-overpaint amend-1`).
- **Source:** `02-shape-amend-1.md` and `07-review-grid-regression-diagnostic.md`.
- **What changed:**
  - Frontmatter: `metric-files-to-touch` 5 → 9, `metric-step-count` 9 → 14 (original 9 + amendment-1 A1–A8 with A6/A7 sometimes counted as one step block), `revision-count` 0 → 1, added `amends`, `applies-amendment`, `tags` for grid + screen-overlap-gate, `next-invocation` from wf-verify to wf-implement (since amendment-1 work is unimplemented).
  - `## Current State`: noted that Path.kt is already at `3e811aa` post-fix state and the amendment-1 work focuses on a SECOND bug class (over-aggressive edges in 3×3 grids). Added a deep read of `IntersectionUtils.hasIntersection` showing the AABB-rejection step accepts boundary-touching boxes, which is the proximate cause of the over-aggressive gate.
  - `## Reuse Opportunities`: added `IntersectionUtils.hasIntersection` (reuse with modification — recommend ADD a sibling `hasInteriorIntersection` rather than modifying the existing function), `IntersectionUtils.isPointInPoly` (reuse as-is in the new helper), `WS10NodeIdScene` (reuse as-is, plus add three sibling factories), `DepthSorterTest` integration test pattern.
  - `## Likely Files / Areas to Touch`: added 9 new file additions (steps A1–A8 above, listing the new IntersectionUtils sibling helper, the DepthSorter gate change, three new scene factories, expanded snapshot test file, and four new baselines).
  - `## Proposed Change Strategy`: layered amendment-1 strategy on top of the original (which is now committed in `3e811aa`).
  - `## Step-by-Step Plan`: prepended steps A1–A8 for amendment-1 work; original steps 1–9 retained for context but marked as "already executed in `3e811aa`".
  - `## Test / Verification Plan`: total test count changed from ~182–184 to 187 (183 pre-amendment + 4 new from AC-9 and AC-10). Replaced the single `nodeIdSharedEdge` snapshot with FOUR new scene-factory snapshots covering the regressed samples (LongPress, Alpha, OnClick) plus the original WS10 case (renamed).
- **Why:** The original `02-shape.md` was scoped to row-layout shared-edge cases. The diagnostic in `07-review-grid-regression-diagnostic.md` revealed a second bug class (3×3 grid corner cubes lose vertical faces because of accumulated over-aggressive topological edges). Amendment-1 expanded the shape to cover both bug classes; the plan needed to grow the file list, step sequence, test inventory, and snapshot baseline set accordingly.
- **What is preserved unchanged from revision 0:** all original-scope steps (1–9) and their committed result (`3e811aa`); `Path.kt`'s permissive `result > 0` predicate; the 1e-6 epsilon; AC-1 through AC-8 verification logic; the `WS10NodeIdScene` factory; the `IntersectionUtilsTest` baseline case set.
- **What is invalidated:** the single `nodeIdSharedEdge` snapshot test (REPLACED by `nodeIdRowScene`, equivalent geometry, renamed for clarity); the implicit assumption that the WS10 fix was the complete bug class.

## Recommended Next Stage

- **Option A (default, post-amendment-2):** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — execute amendment-2 steps R1–R8: (R1) pre-flight at HEAD `2e29dc5`; (R2–R4) red — add AC-12, AC-13, AC-3 case (g), and split AC-2; (R5) green — replace `closerThan`'s body in place with the Newell Z→X→Y minimax cascade and delete the obsolete `countCloserThan`; (R6) green — revert the round-3 DEPTH_SORT_DIAG instrumentation in `DepthSorter.kt`; (R7) regression sweep on `:isometric-core:test :isometric-compose:test` + Linux Paparazzi regen + Maestro live capture; (R8) atomic commit `fix(depth-sort): replace closerThan with Newell Z->X->Y minimax cascade` bundling the source change, the DIAG revert, and the new + reframed tests. **Compact strongly recommended before invoking implement** — three rounds of prior implement attempts plus the round-3 directed-investigation log are noise that the new plan supersedes. The PreCompact hook will preserve workflow state.

- **Option B:** `/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — only if the user wants to re-verify the current `2e29dc5` state without the amendment-2 work. Not recommended; the verify stage's most recent result (`verify-round-2-result: partial` per `00-index.md`) already confirmed AC-11 visual regression on LongPress. Amendment-2 is the corrective response.

- **Option C:** `/wf-amend depth-sort-shared-edge-overpaint from-implement` — invoke ONLY if R7's regression sweep surfaces a Kahn cycle (i.e., AC-12 or AC-13 fails not because cmpPath=0 but because the topological sort produced a cycle that the append-on-cycle fallback resolved by appending in the wrong order). Triggers `amend-3` to pull cascade step 7 (polygon-split) into scope. Not currently expected; the deferred-step-7 hypothesis is that the existing `cycle fallback` `DepthSorterTest` case adequately covers the Kahn fallback's correctness for the diagnosed scenes.

- **Option D:** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint amend-2-revert-only` — fallback option: revert ONLY the round-3 DIAG instrumentation in `DepthSorter.kt` (commit `2e29dc5`'s additions) without the Newell adoption. Restores the post-amendment-1 state at `9cef055` for diagnostics-clean handoff. **Not recommended** — leaves the AC-14 LongPress regression unfixed and the `00-index.md`'s `verify-round-2-result: partial` blocker open.
