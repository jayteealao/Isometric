---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: material-types
review-command: correctness
status: complete
updated-at: "2026-04-11T23:51:56Z"
metric-findings-total: 2
metric-findings-blocker: 0
metric-findings-high: 0
result: issues-found
tags: []
refs:
  review-master: 07-review.md
---

# Review: correctness

## Findings
| ID | Sev | Conf | File:Line | Issue |
|----|-----|------|-----------|-------|
| CR-1 | MED | High | RenderCommand.kt:56 | `uvCoords contentEquals other.uvCoords` relies on Kotlin nullable extension — correct but fragile |
| CR-2 | LOW | Med | IsometricMaterial.kt:57 | `PerFace.faceMap` uses `Map<Int, IsometricMaterial>` — index-based, no compile-time face validation |

## Detailed Findings

### CR-1: FloatArray nullable contentEquals in RenderCommand.equals [MED]
**Location:** `isometric-core/src/main/kotlin/.../RenderCommand.kt:56`
**Evidence:**
```kotlin
(uvCoords contentEquals other.uvCoords)
```
**Issue:** `FloatArray?.contentEquals(FloatArray?)` is a Kotlin stdlib extension that handles null correctly (both null → true, one null → false). This works, but it's the only field in equals() that uses the nullable extension pattern — all other nullable fields use `==`. The inconsistency could confuse maintainers. Additionally, the infix form `contentEquals` without the `?` safety operator is only valid because Kotlin resolves the nullable extension. A minor readability concern.
**Fix:** No functional fix needed. Consider adding a comment: `// nullable contentEquals: both-null=true, one-null=false`
**Severity:** MED | **Confidence:** High

### CR-2: PerFace.faceMap uses Int keys instead of PrismFace enum [LOW]
**Location:** `isometric-shader/src/main/kotlin/.../IsometricMaterial.kt:57`
**Evidence:**
```kotlin
data class PerFace(
    val faceMap: Map<Int, IsometricMaterial>,
    val default: IsometricMaterial,
) : IsometricMaterial
```
**Issue:** The `Int` key allows any integer, including negative values or values > 5 that don't correspond to any face. The `per-face-materials` plan introduces `PrismFace` enum as the key type. Using `Int` now means a breaking change later when switching to the enum. However, this is explicitly documented as deferred — the plan says "Named face accessors deferred to per-face-materials slice."
**Fix:** No fix needed in this slice — the per-face-materials slice will migrate this.
**Severity:** LOW | **Confidence:** Med

## Summary
- Total findings: 2
- Blockers: 0
- Status: Issues Found (minor)
