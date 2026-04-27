---
schema: sdlc/v1
type: review
review-command: refactor-safety
slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T20:44:25Z"
updated-at: "2026-04-26T20:44:25Z"
result: pass
metric-findings-total: 3
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 0
metric-findings-low: 1
metric-findings-nit: 2
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - numerical-robustness
  - refactor-safety
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
