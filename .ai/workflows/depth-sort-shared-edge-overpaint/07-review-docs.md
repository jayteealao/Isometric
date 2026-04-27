---
schema: sdlc/v1
type: review
review-command: docs
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T20:44:25Z"
updated-at: "2026-04-26T20:44:25Z"
result: pass-with-findings
metric-findings-total: 5
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 2
metric-findings-low: 1
metric-findings-nit: 2
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - numerical-robustness
  - docs
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
  security: 07-review-security.md
---

# Review: docs

Scope: `Path.kt` lines 95–146 — KDoc on `closerThan` (lines 95–98) and
`countCloserThan` (lines 103–116). Diff: commit `3e811aa`.
Also checks for `docs/internal/explanations/depth-sort-painter-pipeline.md` (recommended
in shape stage Documentation Plan).

---

## Findings

| ID   | Severity | Location                          | Summary                                                                      |
|------|----------|-----------------------------------|------------------------------------------------------------------------------|
| DC-1 | MEDIUM   | `Path.kt:104-105` — KDoc          | Epsilon description is technically wrong: epsilon is on the product, not on individual vertex distances |
| DC-2 | MEDIUM   | `Path.kt:95-98` — KDoc `closerThan` | KDoc not updated to reflect permissive threshold; still describes strict majority semantics |
| DC-3 | LOW      | `Path.kt:114-115` — KDoc          | Workflow slug reference is internal vocabulary in source KDoc; already flagged SEC-2 but has docs implications too |
| DC-4 | NIT      | `Path.kt:103-116` — KDoc          | "any vertex" description is accurate for the implementation but conflicts with the shape stage's stated desired behavior ("strict majority") |
| DC-5 | NIT      | `docs/internal/explanations/`     | `depth-sort-painter-pipeline.md` not created; shape stage Documentation Plan recommended it as optional |

---

## Detailed Findings

### DC-1 — Epsilon description misleads on where the threshold applies (MEDIUM)

**Location:** `Path.kt` lines 104–105:
```
* Returns 1 if any vertex of this path is on the same side of pathA's plane as the
* observer (within a 1e-6 epsilon to absorb floating-point noise), 0 otherwise.
```

**What the code actually does** (line 140):
```kotlin
if (observerPosition * pPosition >= 0.000001)
```

The 1e-6 threshold is applied to the **product** `observerPosition * pPosition`, not to
either individual signed distance. This means:

- A vertex with `observerPosition = 1.0` and `pPosition = 0.0000005` (each well within 1e-6
  of the plane individually) yields a product of `5e-7`, which is **below** the threshold —
  the vertex is treated as coplanar / not-closer even though neither distance alone would
  trigger a coplanar judgment.
- Conversely, a vertex with `observerPosition = 0.001` and `pPosition = 0.001` yields a
  product of `1e-6`, which is exactly at the threshold.

The KDoc phrase "within a 1e-6 epsilon" implies an absolute distance tolerance
on the signed-distance of each vertex to the plane. A reader (maintainer) who tries to
reason about the boundary band — e.g. for numerical-robustness work — will be misled.

**Recommended fix:**
```
* Returns 1 if any vertex of this path is on the same side of pathA's plane as the
* observer, 0 otherwise. Specifically, a vertex is counted as observer-side when
* `observerPosition * vertexPosition >= 1e-6`, where both positions are signed distances
* to the plane. The product threshold (rather than a per-distance threshold) makes the
* coplanar band wider when both signed distances are small, absorbing floating-point noise
* for the project's typical 0..100 coordinate range.
```

**Severity: MEDIUM.** Technically incorrect; will mislead any future maintainer doing
numerical-robustness work or trying to tune the epsilon.

---

### DC-2 — `closerThan` KDoc not updated to reflect permissive threshold (MEDIUM)

**Location:** `Path.kt` lines 95–98:
```kotlin
/**
 * If pathB ("this") is closer from the observer than pathA, it must be drawn after.
 * It is closer if one of its vertices and the observer are on the same side of the plane defined by pathA.
 */
```

**Issue:** The phrase "one of its vertices" could be read as intentional (and it now
matches the `result > 0` permissive implementation), but the pre-fix KDoc used the same
wording when the behaviour was integer-division majority. The shape stage Documentation
Plan (02-shape.md §Documentation Plan) specifically called out:

> "Optionally `closerThan` (KDoc update on the changed semantics)."

The fix changed `countCloserThan` from "strict majority → 1" to "any vertex → 1". The
`closerThan` KDoc still reads "one of its vertices … on the same side" which happens to
match the new implementation — but only by accident: the old code also said "one of its
vertices" yet behaved as a majority vote. The KDoc was not consciously updated and does
not explain:

1. That `closerThan` delegates to `countCloserThan` in both directions (subtraction
   pattern).
2. Why a permissive "any vertex" threshold was chosen over strict majority.
3. The consequence for shared-edge cases (the bug that was fixed).

**Recommended fix:** Expand the KDoc to:
```kotlin
/**
 * Returns a positive int if this path ("pathB") is closer to the observer than [pathA]
 * and should therefore be drawn after it, a negative int if [pathA] is closer, or 0 if
 * the relationship is ambiguous.
 *
 * "Closer" is defined permissively: pathB is closer if *any* of its vertices and the
 * observer lie on the same side of the plane defined by [pathA] (see [countCloserThan]).
 * The permissive threshold is required for adjacent prism faces that share an edge — in
 * those configurations only a minority of vertices may sit on the observer side.
 *
 * Implemented as `pathA.countCloserThan(this) - this.countCloserThan(pathA)`, producing
 * an antisymmetric signed comparator.
 */
```

**Severity: MEDIUM.** The existing KDoc is technically accurate by coincidence but does
not explain the design intent or the changed semantics, leaving maintainers without
context for future changes.

---

### DC-3 — Workflow slug in KDoc is internal vocabulary in source (LOW)

**Location:** `Path.kt` lines 114–115:
```
* See workflow `depth-sort-shared-edge-overpaint` for the full diagnosis.
```

This was already flagged as SEC-2 (NIT) in the security review. From a **documentation
perspective** the concern is slightly different: the reference is unintelligible to anyone
who does not know the `.ai/workflows/` directory structure, and the slug itself is not a
navigable link — it cannot be opened from an IDE tooltip or Dokka output. It provides
zero value to a maintainer reading the source without the internal workflow context.

**Recommended fix:** Replace with a prose description, or omit entirely since the KDoc
body already contains the full historical rationale. If a link is desired, reference a
`docs/internal/` path that would actually exist:
```
* See `docs/internal/explanations/depth-sort-painter-pipeline.md` for the full diagnosis
* and algorithm context.
```
(This would also motivate creating that file — see DC-5.)

**Severity: LOW.** The function is `private`, so this comment is never exposed in
generated public API docs. The readability cost is real but contained.

---

### DC-4 — "any vertex" description conflicts with shape-stage desired behavior specification (NIT)

**Location:** `Path.kt` line 109:
```
* Permissive ("any vertex" rather than "majority") is required for shared-edge cases:
```

And the shape stage (`02-shape.md`) stated:

> "Return 1 when a strict majority of vertices land on the same side … Return 0 only when no strict majority exists."

The implementation (`result > 0`) is "any vertex", which the implement stage document
(`05-implement-depth-sort-shared-edge-overpaint.md` line 39) describes as:
> "Replaced the integer-division collapse … with a permissive sign-preserving threshold
> (`result > 0` ⇒ 1, else 0)"

The KDoc correctly describes the implementation. The shape-stage desired behavior
("strict majority") was superseded during implementation. This is not a KDoc error — the
KDoc is accurate — but a reader cross-referencing the shape stage spec against the KDoc
will find a contradiction. The implement stage document does not explicitly call out
"desired behavior was updated from strict-majority to any-vertex."

**Recommended fix:** Add one sentence to the KDoc noting the deviation from the
original spec:
```
* Note: the shape stage specified a strict-majority threshold; that was replaced during
* implementation because strict majority is indeterminate for the 2/4 shared-edge case.
* "Any vertex" is strictly more permissive — it may add extra ordering edges — but is
* correct for the project's axis-aligned prism scenes.
```

Alternatively, update 02-shape.md §Desired Behavior to reflect the implemented semantics.

**Severity: NIT.** No reader confusion in practice; only visible if cross-referencing
workflow docs against source.

---

### DC-5 — `docs/internal/explanations/depth-sort-painter-pipeline.md` not created (NIT)

**Location:** `docs/internal/explanations/` — directory does not exist; file was never
created.

**Shape stage Documentation Plan** (`02-shape.md` §Documentation Plan) recommended:

> **Explanation (internal)** — Optional, recommended.
> Target location: `docs/internal/explanations/depth-sort-painter-pipeline.md` (new file).
> Link from this workflow's `00-index.md` and from the changed function's KDoc.

The implement stage (`05-implement-depth-sort-shared-edge-overpaint.md`) does not mention
creating this file; it was not created. As a result:

- The KDoc's "See workflow …" reference (DC-3) has no clean internal-docs target to point to.
- Maintainers extending the painter pipeline have no written record of: why centroid
  pre-sort + Kahn + plane-side was chosen; the integer-division collapse incident; the
  explicitly deferred items (AABB minimax, Newell cascade).

This is the weakest of the five findings — the explanation document was marked "optional"
in the shape stage — but it is the natural resolution to DC-3 as well.

**Recommended fix:** Create `docs/internal/explanations/depth-sort-painter-pipeline.md`
covering the three items above. The shape stage §Documentation Plan contains a complete
content brief; implementation is straightforward. Link from `00-index.md` and replace the
workflow slug reference in the KDoc with a link to this file.

**Severity: NIT.** No immediate reader harm; shapes future maintainability.

---

## Summary

The `countCloserThan` KDoc is well-intentioned and historically useful, but contains one
technically wrong claim (DC-1: the epsilon description) and one omission that leaves the
`closerThan` KDoc inadequately explaining the changed semantics (DC-2). Both are MEDIUM
severity and are in the same two-function block, so fixing them is a single small edit.

The workflow slug reference (DC-3) and the missing explanation document (DC-5) are lower
severity but are coupled: the slug is harmless only because the function is `private`; if
the explanation document existed, DC-3 could be resolved by pointing to it instead.

The shape-spec vs implementation divergence (DC-4) is a traceability nit with no practical
impact since the KDoc accurately describes the actual code.

**Result: PASS with findings.** No blockers. Two MEDIUM findings (DC-1, DC-2) should be
fixed before handoff; the remaining three are judgment calls for the next operator.

### Recommended fixes in priority order

1. **DC-1** — Correct the epsilon description in `countCloserThan` KDoc to explain product-threshold semantics.
2. **DC-2** — Expand `closerThan` KDoc to explain permissive threshold, subtraction pattern, and shared-edge rationale.
3. **DC-3** — Remove or replace the workflow slug sentence; replace with prose or a future `docs/internal/` link.
4. **DC-5** — Create `docs/internal/explanations/depth-sort-painter-pipeline.md` (resolves DC-3 cleanly).
5. **DC-4** — Add a one-sentence note in `countCloserThan` KDoc or update 02-shape.md §Desired Behavior to reflect "any vertex" semantics.

### CHANGELOG / public docs

CHANGELOG handling is consistent: the shape stage Documentation Plan explicitly states
"Auto-generated by git-cliff. … no manual edit." Commit `3e811aa` uses a conventional
`fix(depth-sort):` prefix, so git-cliff will pick it up correctly. No manual public-docs
update is required or missing.
