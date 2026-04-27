---
schema: sdlc/v1
type: review
review-command: security
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T20:44:25Z"
updated-at: "2026-04-26T20:44:25Z"
result: pass
metric-findings-total: 2
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 0
metric-findings-low: 1
metric-findings-nit: 1
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - numerical-robustness
  - security
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
---

# Review: security

Scope: `git diff HEAD~1 HEAD` — `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
(+29/-12 lines, `countCloserThan` predicate). Tests added in `PathTest`,
`DepthSorterTest`, `IntersectionUtilsTest`, `IsometricCanvasSnapshotTest`.

## Findings

| ID    | Severity | Confidence | Location                       | Summary                                           |
|-------|----------|------------|--------------------------------|---------------------------------------------------|
| SEC-1 | LOW      | Med        | `Path.kt:140` — epsilon check  | NaN/Infinity in coordinates silently pass the product check |
| SEC-2 | NIT      | High       | `Path.kt:104-116` — KDoc       | KDoc references internal workflow slug (cosmetic) |

## Detailed Findings

### SEC-1 — NaN/Infinity propagation through the product check (LOW)

**Location:** `Path.kt` line 140 — `if (observerPosition * pPosition >= 0.000001)`

**Description:**
If any vertex coordinate or observer coordinate is `NaN` or `±Infinity` (IEEE 754),
the products `observerPosition * pPosition` will be `NaN` or `±Infinity`.

- `NaN >= 0.000001` evaluates to `false`, so a NaN-tainted vertex is silently treated as
  not-closer — the same semantics as coplanar. No exception is thrown; no loop terminates.
- `+Infinity * +Infinity = +Infinity >= 0.000001` is `true`, so a face with an
  infinite coordinate would spuriously pass as "closer".
- `+Infinity * -Infinity = -Infinity >= 0.000001` is `false`, treated as coplanar.

**Impact in practice:** `Path` coordinates originate from internal library math (cross
products, dot products of `Double` arithmetic on user-supplied scene coordinates). If the
caller constructs a `Path` with `NaN`/`Inf` vertices (e.g., dividing by zero in a custom
shape), the depth-sort loop does not detect it and silently produces an incorrect paint
order for that face. The render loop is not locked or crashed — the effect is cosmetic
corruption of the sorting result for the malformed face.

**No DoS risk** is present: the loop iterates over `points`, which is a finite immutable
list validated at construction time (`require(size >= 3)`). There is no unbounded
iteration path.

**Recommendation (non-blocking):** The existing `init { require(points.size >= 3) }` guard
could be extended to validate that no coordinate is NaN or non-finite, e.g.:
```kotlin
require(points.none { it.x.isNaN() || it.y.isNaN() || it.z.isNaN() ||
                       !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) {
    "Path vertices must be finite"
}
```
This would surface bad inputs at the earliest possible moment (API guideline §7) rather
than silently producing incorrect renders. If the upstream `Point` type already enforces
finiteness, this finding is moot.

**This change does not introduce this risk** — the prior code had the same silent NaN
behaviour via the old product comparison. It is a pre-existing latent issue, surfaced here
for completeness.

---

### SEC-2 — KDoc mentions internal workflow slug (NIT)

**Location:** `Path.kt` lines 114-115:
```
* See workflow `depth-sort-shared-edge-overpaint` for the full diagnosis.
```

**Description:** The KDoc for `countCloserThan` references an internal workflow directory
slug. This is cosmetic and not a security concern, but it constitutes internal
implementation-process vocabulary leaking into a public-facing code comment that would be
visible in generated API docs or IDE tooling. Per the project memory note
`feedback_no_slice_vocab_in_pr.md`, workflow vocabulary should not appear in public docs.

Since `countCloserThan` is `private`, this comment is not surfaced in Dokka-generated
public API docs. The finding is NIT-level only.

**Recommendation:** Remove the workflow-slug sentence from the KDoc, or replace it with
a plain description (e.g., "See the depth-sort shared-edge overpaint bug report.").

---

## Summary

The change is **clean from a security perspective.** No user-controlled input is
introduced, no I/O or networking is touched, no authentication logic is modified, and
no sensitive data is logged or exposed. The two findings are:

- **SEC-1 (LOW):** Pre-existing silent NaN/Infinity tolerance in the plane-side test —
  not introduced by this diff, not a DoS vector, purely cosmetic render corruption risk
  for caller-supplied malformed coordinates.
- **SEC-2 (NIT):** Internal workflow vocabulary in a `private` KDoc — no functional or
  security impact; flagged only because of the project's stated preference.

**Result: PASS.** No blockers, no high/medium findings. The fix may proceed to handoff.
