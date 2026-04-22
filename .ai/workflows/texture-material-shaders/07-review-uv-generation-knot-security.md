---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: uv-generation-knot
review-command: security
status: complete
updated-at: "2026-04-20T23:00:00Z"
metric-findings-total: 3
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 1
metric-findings-low: 1
metric-findings-nit: 1
result: issues-found
tags: [security]
refs:
  review-master: 07-review-uv-generation-knot.md
---

# Security Review: uv-generation-knot

**Slice:** `uv-generation-knot`
**Commit:** `e5cf72a`
**Scope:** diff of `isometric-core/…/shapes/Knot.kt`, `isometric-shader/…/UvGenerator.kt`,
`isometric-shader/…/UvCoordProviderForShape.kt`, and two test files.
**Date:** 2026-04-20
**Reviewer:** Claude Code

**Pre-existing issues explicitly excluded from this review:**
- Knot depth-sort rendering bug (documented in `Knot` KDoc, pre-existing to this slice)
- `:isometric-benchmark` compile failure (pre-existing, unrelated to this slice)

---

## 0) Scope, Assumptions, and Threat Summary

**What was reviewed:**

- Scope: diff (`e5cf72a` — commit `HEAD~1..HEAD` for slice `uv-generation-knot`)
- Files: 5 changed (+281, −11)
  - `isometric-core/src/main/kotlin/.../shapes/Knot.kt` (+25)
  - `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt` (+90)
  - `isometric-shader/src/main/kotlin/.../shader/UvCoordProviderForShape.kt` (+8 / −7)
  - `isometric-shader/src/test/kotlin/.../shader/PerFaceSharedApiTest.kt` (+16 / −4)
  - `isometric-shader/src/test/kotlin/.../shader/UvGeneratorKnotTest.kt` (+139, new file)

**Threat model:**

This is a pure-CPU graphics library. There is no networking, no authentication, no
persistent storage, and no user-supplied strings parsed at runtime. The relevant
threat surface for this slice is:

- **Entry points:** Public API (`Knot.sourcePrisms`, `UvGenerator.forKnotFace`,
  `UvGenerator.forAllKnotFaces`, `uvCoordProviderForShape`) callable from any
  Kotlin/JVM code on the Compose composition thread.
- **Trust boundaries:** All callers are in-process and assumed to be the host
  application. There is no external data that reaches the UV math.
- **Assets:** The only "sensitive" asset is the internal geometry constants baked
  into `Knot.sourcePrisms` and `Knot.createPaths`. These are hard-coded pure-math
  constants with no confidentiality requirement — this is not an information
  disclosure risk in any practical threat model.
- **Privileged operations:** None. All operations are read-only UV computations
  returning `FloatArray` values.

**Authentication model:** Not applicable — library code, no auth layer.

**Data sensitivity:** None. All inputs and outputs are geometric coordinates (doubles
and floats). No PII, credentials, or secrets are involved.

**Assumptions:**

1. The host application is trusted; in-process API calls are not an adversarial
   channel.
2. `Knot.paths` is constructed once at `Knot` instantiation and is immutable
   (standard `Shape` contract). `sourcePrisms` is a `val` initialised at
   construction and never mutated.
3. The primary risks to evaluate are: integer arithmetic anomalies (overflow, AIOOB),
   resource exhaustion, error message leakage, and thread-safety of the mutable
   `ShapeNode.shape` field (same pattern as in the sibling `uv-generation-stairs`
   security review).

---

## 1) Executive Summary

**Merge Recommendation:** APPROVE_WITH_COMMENTS

**Rationale:**
The slice introduces no blocker or high-severity security issues. The UV math is
pure CPU arithmetic over hard-coded geometry with no external input, no I/O, and no
resource allocation beyond fixed-size `FloatArray` values. Three lower-severity
observations are noted below; the most actionable is the `else -> throw` branch in
`forKnotFace`, which creates a dead code path with a misleading error message because
the `require()` guard directly above it already rejects every out-of-range index.

**Critical Vulnerabilities (BLOCKER):** None.

**High-Risk Issues:** None.

**Overall Security Posture:**

- Authentication: N/A (library, no auth layer)
- Authorization: N/A
- Input Validation: Adequate — `require(faceIndex in knot.paths.indices)` is present
  and fires before the `when` dispatch; the `else` branch is unreachable but the
  dead code is harmless.
- Secret Management: N/A — no secrets in scope.
- Defense-in-Depth: Good — bounded list (always 20 faces), safe-zero on degenerate
  spans in `quadBboxUvs`, no heap growth.

---

## 2) Threat Surface Analysis

### Entry Points

| Entry Point | Type | Validation | Notes |
|---|---|---|---|
| `Knot(position)` constructor | Public API | `Shape.init` rejects empty path list | `sourcePrisms` fixed constants |
| `UvGenerator.forKnotFace(knot, faceIndex)` | Internal object fun | `require(faceIndex in knot.paths.indices)` | Int arithmetic only |
| `UvGenerator.forAllKnotFaces(knot)` | Internal object fun | Delegates to `forKnotFace` | Always 20 iterations |
| `uvCoordProviderForShape(shape)` | Internal factory | `is Knot` branch, wraps `forKnotFace` | Closed over immutable `Knot` |

### Trust Boundaries

No trust boundary crossing occurs in this slice. All callers are in-process
Compose/Android framework code. The `UvCoordProvider` lambda closes over a `Knot`
instance provided by the Compose tree; the `Knot` is immutable after construction.

### Assets at Risk

| Asset | Sensitivity | Exposure Risk |
|---|---|---|
| `Knot.sourcePrisms` geometric constants | None (public domain geometry) | None |
| UV float arrays returned to renderer | None | N/A |

### Privileged Operations

None. Read-only UV math; no writes to shared state.

---

## 3) Findings Table

| ID | Severity | Confidence | Category | File:Line | Vulnerability |
|---|---|---|---|---|---|
| SEC-01 | MED | High | Dead code / misleading error | `UvGenerator.kt:381–384` | `else -> throw` branch is unreachable; error message contradicts the `require` guard |
| SEC-02 | LOW | Med | Information disclosure via error message | `UvGenerator.kt:372` | `require` message embeds `knot.paths.size` — benign constant, but inconsistent with internal-API conventions |
| SEC-03 | NIT | Med | Drift-guard single point of failure | `Knot.kt:35–39` | `sourcePrisms` val and `createPaths` constants are manually kept in sync; the unit-test guard is the only safety net |

**Findings Summary:**
- BLOCKER: 0
- HIGH: 0
- MED: 1
- LOW: 1
- NIT: 1

---

## 4) Findings (Detailed)

### SEC-01 — `else -> throw` branch in `forKnotFace` is unreachable dead code [MED] {Confidence: High}

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt:371–384`

**Vulnerable Code:**
```kotlin
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray {
    require(faceIndex in knot.paths.indices) {      // ← rejects any index outside [0, 19]
        "faceIndex $faceIndex out of bounds for Knot with ${knot.paths.size} faces …"
    }
    return when (faceIndex) {
        in 0..17 -> { … }
        18, 19   -> quadBboxUvs(knot.paths[faceIndex])
        else     -> throw IllegalArgumentException(  // ← DEAD: require() already rejects this
            "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
        )
    }
}
```

**Description:**

The `require()` call at the top of `forKnotFace` throws `IllegalArgumentException`
for any `faceIndex` outside `knot.paths.indices` (i.e., outside `[0, 19]` for a
standard `Knot`). The `when` branches cover `0..17` and `18, 19`, which together
exhaust `[0, 19]` completely. The `else ->` branch is therefore unreachable after a
`require()` pass.

This creates two secondary issues:

1. **Misleading diagnostics.** If `Knot.paths` ever returns a list whose size
   differs from 20 (e.g., due to a subclass or a future `Knot` refactor), the
   `require` fires with the correct `knot.paths.size` message, but the `else` branch
   would still reference the hard-coded string "exactly 20 faces (indices 0..19)",
   which would then be wrong. A developer diagnosing a crash might be sent down
   the wrong path by the `else` message if the dead branch somehow became reachable.

2. **Static analysis false positive.** Linters and exhaustiveness checkers may flag
   the unreachable `else` as dead code or as evidence that the `when` is not
   exhaustive by itself (which it actually is, given the guard). This reduces
   confidence in the guard structure.

**Is this exploitable?** No. It is a code-quality issue, not a true security
vulnerability. However, in the sibling `uv-generation-stairs` review (SEC-01 there)
the mutable `ShapeNode.shape` pattern was the top finding; here the pattern to watch
is that dead branches with wrong assumptions can mask real invariant failures during
future maintenance.

**Severity rationale:** MED because the dead branch carries a hard-coded assertion
about face count (`"exactly 20 faces"`) that is now expressed in two places —
the `require` (which checks `knot.paths.size`) and the `else` message (which assumes
20). If `Knot` is subclassed or the path count changes, only one of those two
expressions would be updated, creating a silent lie in the error text. The fix is
one line.

**Remediation:**

Remove the `else` branch entirely and let the `when` be exhaustive via the `require`
guard:

```diff
-        else -> throw IllegalArgumentException(
-            "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
-        )
```

After removal, the function reads:

```kotlin
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray {
    require(faceIndex in knot.paths.indices) {
        "faceIndex $faceIndex out of bounds for Knot with ${knot.paths.size} faces (valid range: 0 until ${knot.paths.size})"
    }
    return when (faceIndex) {
        in 0..17 -> {
            val prismIndex = faceIndex / 6
            val localFaceIndex = faceIndex % 6
            forPrismFace(knot.sourcePrisms[prismIndex], localFaceIndex)
        }
        else -> quadBboxUvs(knot.paths[faceIndex])   // only 18 and 19 can reach here
    }
}
```

This collapse also removes the duplicated hard-coded constant 17/18/19, keeping the
face count expressed in exactly one place (`knot.paths.indices` in the `require`).

**CWE:** CWE-617 (Reachable Assertion) / CWE-561 (Dead Code)

---

### SEC-02 — `require` error message leaks `knot.paths.size` [LOW] {Confidence: Med}

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt:372`

**Code:**
```kotlin
require(faceIndex in knot.paths.indices) {
    "faceIndex $faceIndex out of bounds for Knot with ${knot.paths.size} faces (valid range: 0 until ${knot.paths.size})"
}
```

**Description:**

The error message interpolates both `faceIndex` (the caller-supplied integer) and
`knot.paths.size` (an internal property of the `Knot` geometry model). In a general
networked or multi-tenant context, including internal state in exception messages is
an information-disclosure risk (CWE-209). Here the library context fully mitigates
this: all callers are in-process, there is no external channel through which this
string could be observed by an attacker, and `knot.paths.size` (always 20 for a
standard `Knot`) is not confidential.

The observation is rated LOW because:

1. `UvGenerator` is `internal object`. External code cannot call `forKnotFace`
   directly.
2. `knot.paths.size` is a deterministic public property; the information it
   discloses is the size of the geometry's path list, which is already visible
   via the public `Knot.paths` property.
3. The value is always 20 for any `Knot` constructed via the public constructor.

**Residual risk:** If `Knot` were ever made fully open for external subclassing (it
currently is, `@ExperimentalIsometricApi` class, not `sealed`), a subclass could
override `paths` to return a different-size list. The error message would then
disclose the subclass's internal path count. This is not an immediate concern but
is worth noting for the `@ExperimentalIsometricApi` lifecycle.

**Recommendation:**

No immediate change required. If the error message is ever surfaced to end users
(e.g., as a toast or a crash dialog), strip the internal-state interpolations:

```kotlin
require(faceIndex in knot.paths.indices) {
    "faceIndex $faceIndex is out of range for this Knot"
}
```

For library-internal validation messages the current verbosity is acceptable and
helpful for debugging. Keep as-is unless the error message leaves the process
boundary.

**CWE:** CWE-209 (Generation of Error Message Containing Sensitive Information) —
applies at low severity given the internal-only channel.

---

### SEC-03 — `sourcePrisms` and `createPaths` constants are manually synced; unit test is the sole guard [NIT] {Confidence: Med}

**Location:** `isometric-core/src/main/kotlin/.../shapes/Knot.kt:35–53`

**Code:**
```kotlin
// Knot.kt — val sourcePrisms
val sourcePrisms: List<Prism> = listOf(
    Prism(Point.ORIGIN, 5.0, 1.0, 1.0),            // ← must match createPaths line 51
    Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),    // ← must match createPaths line 52
    Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),   // ← must match createPaths line 53
)

// Knot.kt — companion object createPaths
allPaths.addAll(Prism(Point.ORIGIN, 5.0, 1.0, 1.0).paths)
allPaths.addAll(Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0).paths)
allPaths.addAll(Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0).paths)
```

**Description:**

The three `Prism` constructor arguments in `sourcePrisms` and in `createPaths` are
identical pairs of hard-coded literals. There is a code comment directing editors to
keep them in sync, and `UvGeneratorKnotTest.sourcePrisms dimensions match createPaths
constants` pins the exact values in a CI-enforced test.

This is a NIT rather than a higher severity because:

1. The drift scenario (editing `createPaths` without updating `sourcePrisms`) would
   not cause a security issue — it would produce incorrect UV coordinates on the
   textured Knot, which is a correctness defect, not a security defect.
2. The unit test catches any drift at the earliest feasible automated gate.

The only security-adjacent concern is that `sourcePrisms` is a `@ExperimentalIsometricApi`
public `val`. If a downstream caller reads `sourcePrisms` and makes security-sensitive
decisions based on the Prism dimensions (e.g., clip-plane calculations, occlusion
testing), a silently-drifted `sourcePrisms` could mislead that logic. This is
speculative given the library's graphics-only use, but the experimental API's
explicitly-widened surface makes the risk worth noting.

**Recommendation:**

The current approach (comment + regression test) is adequate for a pre-1.0
experimental API. For a post-1.0 hardening pass, consider deriving `sourcePrisms`
from the same literals used in `createPaths` by extracting them into companion-object
constants:

```kotlin
companion object {
    private val PRISM_0 = Triple(Point.ORIGIN, Triple(5.0, 1.0, 1.0), …)
    // or simply: internal constants for width/depth/height
    internal const val PRISM0_W = 5.0; internal const val PRISM0_D = 1.0; …
}
```

This makes the single-definition-of-truth invariant structural rather than relying on
a comment and a test. Not required before merge.

**CWE:** CWE-1041 (Use of Redundant Code) — at NIT level.

---

## 5) Integer Overflow and Arithmetic Safety

`faceIndex / 6` and `faceIndex % 6` are the core arithmetic operations in `forKnotFace`.
Both are performed on a validated `Int` already known to be in `[0, 17]` after the
`require` guard. The maximum value (`17 / 6 = 2`, `17 % 6 = 5`) is well within Java
`int` range. `prismIndex` ∈ {0, 1, 2} is a safe index into a 3-element `List<Prism>`.

No integer overflow risk exists in this slice.

---

## 6) Resource Exhaustion

`forAllKnotFaces` iterates exactly `knot.paths.indices.map { … }` — always 20
elements for any standard `Knot`. Each iteration returns a `FloatArray(8)` — 32 bytes.
Total per-call heap allocation: 20 × 32 = 640 bytes plus the `List` wrapper. This is
bounded and negligible.

`quadBboxUvs` allocates one `FloatArray(8)` per call — 32 bytes. No growth path exists.

No resource exhaustion risk exists in this slice.

---

## 7) `sourcePrisms` Information Disclosure Analysis

The user-provided background flags `sourcePrisms` exposing internal geometry constants
as a potential information-disclosure concern. Assessment:

- `sourcePrisms` is annotated `@ExperimentalIsometricApi` and documented in KDoc as
  the pre-transform Prism dimensions. The values (5.0, 1.0, 4.0, 3.0) are
  pure-math geometry constants with no confidentiality requirement.
- There is no mechanism by which these constants could be used to escalate privilege,
  bypass authentication, or exfiltrate sensitive data.
- The constants are already visible from the `Knot.createPaths` companion (private,
  but the values are identical and readable from the source). Exposing them via
  `sourcePrisms` does not add any new attack surface beyond what is visible from the
  `Prism` instances inside `createPaths`.

**Verdict:** Not an information-disclosure risk in any realistic threat model for a
pure-CPU graphics library. SEC-03 (NIT) documents the maintenance risk of drift, which
is a correctness concern, not a security concern.

---

## 8) Input Validation Summary

| Entry point | Validation present | Adequate? |
|---|---|---|
| `Knot(position)` constructor | `Shape.init` rejects empty path list | Yes |
| `forKnotFace(knot, faceIndex)` | `require(faceIndex in knot.paths.indices)` | Yes (SEC-01: `else` branch redundant) |
| `forAllKnotFaces(knot)` | Delegates to `forKnotFace` per-element | Yes |
| `quadBboxUvs(path)` | `pts.minOf`/`maxOf` guard + span > 0 check | Yes (degenerate spans → 0) |
| `uvCoordProviderForShape(is Knot)` | Closed over immutable `Knot`; `faceIndex` validated inside `forKnotFace` | Yes |

---

## 9) Deserialization / String / IO Surface

None. This slice introduces no deserialization, no user-provided strings parsed at
runtime, no file I/O, and no network access. The attack surface is limited to
in-process API calls.

---

## Summary

The `uv-generation-knot` slice has **no blocker or high-severity security findings**.
The intrinsic security risk is low: pure CPU UV math, fixed 20-face bounded list, no
external data sources, no I/O.

**SEC-01 (MED)** is the most actionable finding. The `else -> throw` branch in
`forKnotFace` is unreachable because the `require()` guard directly above it already
rejects every out-of-bounds `faceIndex`. The `else` branch carries a hard-coded
assertion about face count (`"exactly 20 faces"`) that duplicates the dynamic
`knot.paths.size` check in the `require` message. If `Knot`'s path count ever changes
(subclass, future refactor), only the `require` would reflect the new value — the
`else` message would silently lie. Remove the `else` branch and collapse to a two-arm
`when`.

**SEC-02 (LOW)** notes that the `require` error message interpolates
`knot.paths.size`. This is not a practical information-disclosure risk in the current
internal-only context, but it is inconsistent with the principle of minimal-disclosure
in error messages. No change required for merge.

**SEC-03 (NIT)** observes that `sourcePrisms` and `createPaths` share identical
hard-coded constants that must be kept in sync manually. The unit test
`sourcePrisms dimensions match createPaths constants` is the sole automated guard.
This is adequate for an experimental API but is worth improving to structural
single-definition-of-truth post-1.0.

No GPU resource leaks, no WGSL injection surfaces, no buffer overflows, and no
thread-safety issues beyond the pre-existing `ShapeNode.shape` pattern (not introduced
by this slice — noted in sibling `uv-generation-stairs` SEC-01, applies uniformly to
all non-Prism shape branches in `IsometricNode.renderTo`).
