---
date: 2026-04-11
slice: material-types
reviewer: claude-sonnet-4-6
result: clean
findings:
  - id: ARCH-1
    severity: note
    title: PerFace recursive self-reference is a sealed-interface evolution hazard
  - id: ARCH-2
    severity: note
    title: isometric-shader double-exposes isometric-compose via api()
  - id: ARCH-3
    severity: note
    title: BatchNode has no material slot — inconsistent with ShapeNode/PathNode
  - id: ARCH-4
    severity: note
    title: UvCoord object is orphaned at module boundary for future uv-generation slice
---

# Architecture Review — material-types slice

## Dependency graph

```
isometric-core (pure JVM)
    ↑ api()
isometric-compose (Android + Compose)
    ↑ api()
isometric-shader (Android + Compose)
```

`isometric-webgpu` depends on `isometric-compose` only. No cycles.
`isometric-compose` contains **zero** imports from `isometric-shader` — confirmed by grep.

**Direction: correct. No cycles.**

---

## Findings

### ARCH-1 — PerFace recursive self-reference is a sealed-interface evolution hazard

`IsometricMaterial.PerFace.faceMap` is `Map<Int, IsometricMaterial>`, meaning a face
value can itself be `PerFace`. The runtime imposes no depth limit. This is not wrong
today, but renderer `when`-branches that `when`-switch on `IsometricMaterial` must handle
arbitrary nesting or they silently skip nested values. The KDoc evolution note warns
about adding new subtypes, but not about the nesting risk.

**Recommendation:** Either document "renderers must recurse into PerFace.faceMap values"
explicitly in the KDoc, or restrict `faceMap` to `Map<Int, IsometricMaterial.FlatColor
| IsometricMaterial.Textured>` by introducing a `LeafMaterial` sealed sub-interface.
A `LeafMaterial` restriction also makes the type system enforce valid states (guideline §6).
This is low-priority for now; raise it before shipping the canvas-textures renderer.

### ARCH-2 — isometric-shader double-exposes isometric-compose via api()

`isometric-shader/build.gradle.kts` declares both:
```kotlin
api(project(":isometric-core"))
api(project(":isometric-compose"))
```

`api(":isometric-compose")` already transitively re-exports `:isometric-core` (because
isometric-compose itself uses `api(":isometric-core")`). The explicit `api(":isometric-core")`
in isometric-shader is therefore redundant — the core types already appear on the
compile classpath of isometric-shader consumers. It causes no breakage and has no runtime
cost, but it is misleading: it implies isometric-shader has a direct relationship with
core that bypasses compose, which is not the intent.

**Recommendation:** Change `api(project(":isometric-core"))` to
`implementation(project(":isometric-core"))` in `isometric-shader/build.gradle.kts`, or
remove it entirely and rely on the transitive exposure from isometric-compose. If core
symbols are used directly inside the shader module's own source (they are — `IsoColor`,
`MaterialData`), keep it as `implementation` so it does not re-leak.

### ARCH-3 — BatchNode has no material slot

`ShapeNode` and `PathNode` both carry `var material: MaterialData? = null`.
`BatchNode` does not. The canvas-textures and webgpu-textures slices will need to
apply textures to batched geometry. Omitting `material` from `BatchNode` now means
those future slices either skip batching (performance regression) or need a
`BatchNode` API change (breaking change for low-level users).

**Recommendation:** Add `var material: MaterialData? = null` to `BatchNode` now,
consistent with the other leaf nodes, and pass it through in the emitted `RenderCommand`.
Cost is zero at runtime for the `null` path.

### ARCH-4 — UvCoord is stranded for the uv-generation slice

`UvCoord` and `UvTransform` live in `isometric-shader`. The planned `uv-generation` slice
will compute per-vertex UV coordinates and store them in `RenderCommand.uvCoords`
(`FloatArray`). The UV generation logic naturally belongs in either isometric-core
(platform-agnostic math) or isometric-shader (already has UV types). But `UvCoord`
is a shader-module type while `RenderCommand.uvCoords` is a core-module flat array.

The two representations diverge: `UvCoord(u, v)` objects versus `FloatArray [u0,v0,...]`.
Callers wanting to go from shader UV types to the flat array on RenderCommand need a
conversion step that currently has no home.

**Recommendation:** Add a `fun UvCoord.Companion.packToFloatArray(coords: List<UvCoord>): FloatArray`
(or an extension) in isometric-shader when implementing the uv-generation slice. Document
the convention (matches vertex order in `Path`) so future slices (canvas-textures,
webgpu-textures) can rely on it without re-implementing.

---

## Checklist results

| Check | Result |
|---|---|
| Dependency direction: core → compose → shader, no cycles | PASS |
| isometric-compose has no imports from isometric-shader | PASS |
| MaterialData as marker interface — correct abstraction | PASS — opaque, zero Android leakage into core |
| `api()` vs `implementation()` usage | MINOR — redundant `api(":isometric-core")` in shader (ARCH-2) |
| Module placement follows Android library conventions | PASS — `isometric.android.library` plugin, consumer-rules.pro present |
| Shader module namespace | PASS — `io.github.jayteealao.isometric.shader` matches package |
| Architecture supports future slices | PASS with caveats — ARCH-3 (BatchNode), ARCH-4 (UvCoord ↔ FloatArray bridge) |

---

## Summary

The dependency graph is clean and the key design decision — `MaterialData` as a pure
Kotlin marker interface in `isometric-core` — correctly decouples the platform-agnostic
pipeline from Android bitmap types. `isometric-compose` is confirmed free of shader
imports. The four findings are all `note`-severity; none block merging the
material-types slice. ARCH-3 (BatchNode) should be addressed before the canvas-textures
slice ships to avoid a breaking BatchNode API change later.
