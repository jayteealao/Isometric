# UV Generation — Security & Code Simplification Review

date: 2026-04-11
reviewer: claude-sonnet-4-6
files reviewed:
- isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/PrismFace.kt
- isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt
- isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricNode.kt (lines 215–270)
- isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt

---

## Security

### UVS-1 — No security concerns

result: clean

The UV generation slice is a pure math module. It performs only:
- arithmetic on `Double` / `Float` scalar values derived from already-constructed `Prism` dimensions and `Point` coordinates
- array index reads on `path.points[i]` (0–3) against a known-size collection

There is no I/O, no network access, no user-controlled string parsing, no reflection, no
serialization/deserialization, and no external data sources of any kind. The only way to
trigger an exception is passing an out-of-range `faceIndex` to `PrismFace.fromPathIndex`,
which throws `IllegalArgumentException` — the correct, documented contract.

Division by `w`, `d`, or `h` would produce `Infinity` or `NaN` if any dimension is zero,
but that is a caller invariant (`Prism` construction responsibility), not a security
concern. No silent data corruption can result because the bad values propagate visibly
into the rendered UVs and would be caught during any GPU-side texture sampling.

---

## Code Simplification

### UVCS-1 — `uvProvider` lambda type is not overly complex; a simpler interface would add friction

```kotlin
var uvProvider: ((Shape, Int) -> FloatArray?)? = null
```

This is idiomatic Kotlin: a nullable function type with two parameters. There is no
benefit to replacing it with a named `fun interface` — that would require callers to
either create an anonymous object or use a SAM conversion that looks identical at the call
site. The nullable outer `?` cleanly models the "no UV provider" case without a sentinel
value or checked boolean.

result: clean — keep as-is

### UVCS-2 — The `when` block with 6 branches is the right approach

`computeUvs` contains a `when (face)` with one branch per `PrismFace` enum entry. This is
exactly what `when` is designed for. The Kotlin compiler will warn (or error with
`-Werror`) if a new `PrismFace` entry is added without handling it here — providing a
free exhaustiveness check. Collapsing branches (e.g., LEFT+RIGHT differ only in `1.0 -`)
would save a few lines but make the mapping harder to read and would lose the compiler
safety net.

result: clean — keep the 6 explicit branches

### UVCS-3 — No unnecessary allocations in the loop

The single `FloatArray(8)` is allocated once before the 4-iteration loop, then filled
in-place. No boxing occurs: `u` and `v` are primitive `Double` locals; `result[i*2]` and
`result[i*2+1]` write primitive `Float` values via `toFloat()`. `path.points[i]` is an
indexed read with no intermediate collection. The loop is allocation-free.

result: clean

### UVCS-4 — `forAllPrismFaces` is already maximally simple

```kotlin
fun forAllPrismFaces(prism: Prism): List<FloatArray> =
    (0..5).map { forPrismFace(prism, it) }
```

This is a one-liner that delegates to the validated single-face function and collects
results into a `List`. The only alternative worth considering is replacing `(0..5)` with
`PrismFace.entries.indices` to make the range self-documenting and automatically track
future enum growth. That is a minor readability improvement, not a correctness issue.

**Recommendation (non-blocking):** change `(0..5)` to `PrismFace.entries.indices` so the
range does not need to be updated if PrismFace ever gains or loses entries.

### UVCS-5 — No dead code or unused imports

`UvGenerator.kt` imports `Path`, `Prism`, and `PrismFace` — all three are referenced.
`IsometricMaterialComposables.kt` imports `Path` (used in the `Path` composable overload),
`Point`, `Shape`, `Prism`, `UvGenerator`, and the compose/runtime types — all used.
`PrismFace.kt` has no imports.
`IsometricNode.kt` excerpt shows no dead parameters or unused fields in `ShapeNode`.

result: clean

### UVCS-6 — Unsafe cast in `IsometricMaterialComposables.kt`

```kotlin
{ shape, faceIndex -> UvGenerator.forPrismFace(shape as Prism, faceIndex) }
```

The lambda captures the cast `shape as Prism`. The outer `if` guard (`geometry is Prism`)
ensures the lambda is only constructed when `geometry` is a `Prism`, so the cast is safe
**at construction time**. However, `uvProvider` is called inside `ShapeNode.renderTo` with
`transformedShape` (the post-transform shape), not the original `geometry`. If
`applyTransformsToShape` ever returns a different `Shape` subtype, the cast will throw a
`ClassCastException` at render time with no diagnostic context.

The fix is low-risk: store the original `Prism` reference in the closure rather than
re-casting the argument:

```kotlin
val prism: Prism = geometry   // already known to be Prism from the outer if-guard
val uvProvider: ((Shape, Int) -> FloatArray?)? = if (material is IsometricMaterial.Textured) {
    { _, faceIndex -> UvGenerator.forPrismFace(prism, faceIndex) }
} else null
```

This closes over the strongly-typed `prism` directly, passes the original (pre-transform)
dimensions to `UvGenerator` (which is correct — UVs are computed in model space), and
eliminates the runtime cast entirely.

**Recommendation (fix):** Replace the `shape as Prism` cast with a closed-over `prism`
reference. This also removes the redundant `geometry is Prism` half of the guard — if
only `material is IsometricMaterial.Textured` is checked, non-Prism geometries simply
receive no UV provider, which is the intended behavior.

---

## Summary

| ID     | Area              | Status         | Action          |
|--------|-------------------|----------------|-----------------|
| UVS-1  | Security          | Clean          | None            |
| UVCS-1 | Lambda type       | Clean          | None            |
| UVCS-2 | `when` branches   | Clean          | None            |
| UVCS-3 | Allocations       | Clean          | None            |
| UVCS-4 | `forAllPrismFaces`| Minor improve  | Use `entries.indices` (non-blocking) |
| UVCS-5 | Dead code/imports | Clean          | None            |
| UVCS-6 | Unsafe cast       | Fix recommended| Close over `prism`, drop re-cast |
