---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: uv-generation-shared-api
status: complete
stage-number: 7
created-at: "2026-04-17T16:15:25Z"
updated-at: "2026-04-17T16:15:25Z"
verdict: ship-with-caveats
commands-run: [correctness, security, code-simplification, testing, maintainability, reliability, refactor-safety, api-contracts, architecture, performance]
metric-commands-run: 10
metric-findings-total: 33
metric-findings-raw: 50
metric-findings-blocker: 0
metric-findings-high: 6
metric-findings-med: 10
metric-findings-low: 9
metric-findings-nit: 8
metric-findings-fix: 14
metric-findings-defer: 2
tags: [uv, api, refactor, shared-infrastructure, sealed-class, face-enum, review]
refs:
  index: 00-index.md
  slice-def: 03-slice-uv-generation-shared-api.md
  plan: 04-plan-uv-generation-shared-api.md
  implement: 05-implement-uv-generation-shared-api.md
  verify: 06-verify-uv-generation-shared-api.md
  sub-reviews:
    - 07-review-uv-generation-shared-api-correctness.md
    - 07-review-uv-generation-shared-api-security.md
    - 07-review-uv-generation-shared-api-code-simplification.md
    - 07-review-uv-generation-shared-api-testing.md
    - 07-review-uv-generation-shared-api-maintainability.md
    - 07-review-uv-generation-shared-api-reliability.md
    - 07-review-uv-generation-shared-api-refactor-safety.md
    - 07-review-uv-generation-shared-api-api-contracts.md
    - 07-review-uv-generation-shared-api-architecture.md
    - 07-review-uv-generation-shared-api-performance.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders uv-generation-shared-api"
---

# Review: uv-generation-shared-api

## Verdict

**Ship with caveats.**

No blockers found. The refactor is architecturally sound, the breaking change is
intentional and cleanly captured in the `.api` diffs, and the `refactor-safety` review
PASSED all eight checks (no stale call sites, all tests migrated, DSL narrowing is
source-compatible). However, 4 HIGH and 10 MED findings surfaced across the 10
dispatched reviews that the user has triaged for a follow-up implement pass. The
deferred HIGHs (H-03 non-Prism textured silent-skip, H-06 consumer stub unit tests)
are both appropriately postponed to the shape slices that will actually exercise
those paths. The most impactful follow-up is H-04 — extracting the triplicated
`resolvePerFaceSubMaterial` helper to a single shared extension — which should land
before the 5 shape UV slices begin fanning out.

## Domain Coverage

| Domain | Command | Status |
|--------|---------|--------|
| Logic & invariants | `correctness` | Issues found (3 HIGH, 4 MED, 1 LOW, 1 NIT) |
| Security | `security` | Clean (4 NITs, all non-exploitable) |
| Code simplification | `code-simplification` | Issues found (1 HIGH, 2 LOW, 3 info) |
| Testing | `testing` | Issues found (2 HIGH, 4 MED, 2 LOW, 1 NIT) |
| Maintainability | `maintainability` | Advisory (6 advisory, 2 NIT) |
| Reliability | `reliability` | Mostly clean (1 moderate, 2 minor) |
| Refactor safety | `refactor-safety` | Clean (PASS on all 8 checks) |
| API contracts | `api-contracts` | Issues found (3 findings, 1 intentional-break acknowledged) |
| Architecture | `architecture` | Clean with 3 follow-ups |
| Performance | `performance` | 1 MED (EnumMap swap), 2 LOW, rest negligible |

## All Findings (Deduplicated)

| ID | Sev | Conf | Source | File:Line | Issue |
|----|-----|------|--------|-----------|-------|
| H-01 | HIGH | High | correctness | `GpuUvCoordsBuffer.kt:55` | Gate `uv.size >= 2 * vertCount` vacuously passes when `faceVertexCount == 0`; writes 12 zero floats as "valid" UV |
| H-02 | HIGH | High | correctness | `TexturedCanvasDrawHook.kt:145` | Hardcoded `uvCoords.size < 6` guard decoupled from `faceVertexCount` |
| H-03 | HIGH | Med | correctness | `IsometricMaterialComposables.kt` | Non-Prism PerFace with textured sub-materials silently skip rendering (null provider → null UV → drawTextured returns false) |
| H-04 | HIGH | High | code-simplification + correctness + maintainability | `SceneDataPacker.kt:233-247`, `GpuTextureManager.kt:343-353`, `TexturedCanvasDrawHook.kt:118-130` | Three structurally identical `resolvePerFaceSubMaterial` helpers; 4th divergent site in `collectTextureSources`. Future shape slices must update 3–4 sites in lockstep |
| H-05 | HIGH | High | testing | `IsometricMaterial.kt:166-317` | `PerFace.{Cylinder,Pyramid,Stairs,Octahedron}.equals/hashCode/toString` zero test coverage |
| H-06 | HIGH | High | testing | consumer sites | Consumer stub branches (non-Prism `when(m)`) have no unit-level behavioral test — only on-device sample-app coverage |
| M-01 | MED | High | correctness + api-contracts + architecture | `PerFace.Pyramid.laterals` | `Map<Int, MaterialData>` should be `Map<PyramidFace.Lateral, MaterialData>` for compile-time slot safety |
| M-02 | MED | Med | correctness + api-contracts | `RenderCommand.faceVertexCount` | Default `= 4` is wrong for non-quad shapes; external `SceneProjector` implementors risk silent UV truncation |
| M-03 | MED | Med | maintainability + architecture | `RenderCommand.faceType` | `PrismFace?` leaks Prism detail into core; needs generalisation (sealed `FaceIdentifier` or `Any?`) before more shape dispatch lands |
| M-04 | MED | High | testing | `PerFace.Prism` | `equals/hashCode/toString` not directly tested |
| M-05 | MED | High | testing | `PyramidFace.Lateral` | Positive-boundary construction (index=0, index=3) not explicitly tested |
| M-06 | MED | Med | testing | `StairsFace.fromPathIndex` | Coverage stops at `stepCount=3`; large `stepCount` not stressed |
| M-07 | MED | High | testing | `uvCoordProviderForShape` | Knot missing from null-return test |
| M-08 | MED | High | correctness | `GpuTextureManager.collectTextureSources` | Non-Prism PerFace per-slot materials silently not collected into atlas |
| M-09 | MED | High | performance | `PerFace.Prism.faceMap` | `LinkedHashMap` → `EnumMap<PrismFace, MaterialData>` would remove 3 HashMap.get() calls per face per frame |
| M-10 | MED | Med | reliability | `IsometricMaterial.kt` init blocks | `require()` at construction vs compile-time builder — design note; keep current approach and document rationale |
| L-01 | LOW | High | code-simplification + maintainability | `UvCoordProviderFactory.kt` | Filename/function-name mismatch (file is `...Factory.kt`, function is `uvCoordProviderForShape`) |
| L-02 | LOW | Med | code-simplification + maintainability | `PyramidFace.LATERAL_0..3` | Companion constants used only internally by `fromPathIndex` + tests; consider removing or wait for pyramid slice |
| L-03 | LOW | High | architecture | Stub `resolve()` bodies | No TODO marker — silent fallback risk for future slice authors |
| L-04 | LOW | Med | api-contracts | Non-Prism `PerFace` DSL | Progressive-disclosure gap: `perFace { }` works only for Prism; each shape slice should add its own builder |
| L-05 | LOW | High | testing | `RenderCommand` | `equals/hashCode/toString` round-trip for `faceVertexCount` field not tested |
| L-06 | LOW | High | testing | `IsometricMaterialTest.perFace` | Narrowed return type (`PerFace.Prism`) not asserted |
| L-07 | LOW | Med | maintainability | `PerFace.Prism` KDoc | Doesn't explain `internal` constructor visibility / companion-factory entry point |
| L-08 | LOW | Med | performance | WebGPU pipeline | HashMap lookup duplicated across `SceneDataPacker` + `GpuTextureManager` same frame |
| L-09 | LOW | Med | performance | `GpuTextureManager.uploadTextures` | `allIdentity` shortcut scan runs O(N) per frame including HashMap lookups |
| N-01 | NIT | Med | testing | `PerFaceSharedApiTest.stubRenderCommand` | `Path(p0, p0, p0, p0)` fragile fixture — may break on `Path` API change |
| N-02 | NIT | High | maintainability | `StairsFace.fromPathIndex` | Takes two params while other face types take one; document inherent asymmetry |
| N-03 | NIT | High | maintainability | `PrismFace.fromPathIndex` | Lacks `public` keyword present on all four newer face types |
| N-04 | NIT | Med | security | `GpuUvCoordsBuffer.kt:52` | `2 * faceVertexCount` Int overflow (non-exploitable; loop capped at 12) |
| N-05 | NIT | Low | security | `GrowableGpuStagingBuffer.kt:53` | `(requiredBytes * 2L).toInt()` truncates at ~2.1 GB (pre-existing) |
| N-06 | NIT | Med | security | `IsometricMaterial.kt:98-101` | PerFace nesting-ban guard in Prism but not stub subclasses (structurally correct via base `init`, but inconsistent defence-in-depth) |
| N-07 | NIT | Low | security | `StairsFace.kt:36` | `2 * stepCount + 2` overflow at `Int.MAX_VALUE` (library-internal) |
| N-08 | NIT | High | reliability | `IsometricNode.kt` | `faceVertexCount == 0` from empty path — benign no-op (GPU handles 0-vert draw) |

**Totals after dedup:** BLOCKER 0 | HIGH 6 | MED 10 | LOW 9 | NIT 8 → **33 findings**
*(Merged from 50 raw findings across 10 commands.)*

## Findings (Detailed — HIGH only)

### H-01: `GpuUvCoordsBuffer` gate vacuously passes when `faceVertexCount == 0` [HIGH]

**Location:** `isometric-webgpu/src/main/kotlin/.../pipeline/GpuUvCoordsBuffer.kt:55`
**Source:** correctness

**Evidence:**
```kotlin
if (uv != null && uv.size >= 2 * vertCount) { /* write 12 floats */ }
```

**Issue:** When `faceVertexCount == 0`, `2 * 0 == 0` and any `uv.size >= 0` is trivially true. An empty `FloatArray` enters the "valid UV" branch and writes 12 zero floats instead of taking the documented default-quad path.

**Fix:** Add `vertCount > 0` to the guard: `if (uv != null && vertCount > 0 && uv.size >= 2 * vertCount)`.

**Severity:** HIGH | **Confidence:** High

---

### H-02: `TexturedCanvasDrawHook` hardcoded `uvCoords.size < 6` guard [HIGH]

**Location:** `isometric-shader/src/main/kotlin/.../render/TexturedCanvasDrawHook.kt:145`
**Source:** correctness

**Issue:** Guard is decoupled from `faceVertexCount`. A command with `faceVertexCount == 4` but only 6 UV floats (3 pairs) passes silently; the affine-matrix computation uses only 3 pairs regardless of actual face vertex count. Works today because Canvas drawing only uses the 3-pair affine matrix, but the implicit assumption breaks when pyramid/octahedron UV slices land with exact `2 * faceVertexCount` floats.

**Fix:** Replace `< 6` with `< 2 * cmd.faceVertexCount` to match `GpuUvCoordsBuffer`.

**Severity:** HIGH | **Confidence:** High

---

### H-04: Three-way duplicated Prism-dispatch helpers [HIGH]

**Locations:**
- `isometric-webgpu/.../pipeline/SceneDataPacker.kt:233-247` — `resolvePerFaceSubMaterial`
- `isometric-webgpu/.../texture/GpuTextureManager.kt:343-353` — `resolveEffectiveMaterial` (PerFace branch)
- `isometric-shader/.../render/TexturedCanvasDrawHook.kt:118-130` — `resolvePerFaceSubMaterial`
- (4th, structurally divergent) `GpuTextureManager.collectTextureSources` — uses `if (m is PerFace.Prism)` instead of `when`

**Source:** code-simplification (HIGH) + correctness (NIT) + maintainability (ADVISORY)

**Evidence:** All three share the same dispatch rule: "Prism resolves via `faceType`; every other variant returns `default`." Only return-type nullability differs.

**Fix:**
```kotlin
// Suggested: isometric-shader/IsometricMaterial.kt or new PerFaceResolution.kt
internal fun IsometricMaterial.PerFace.resolveForFace(faceType: PrismFace?): MaterialData =
    when (this) {
        is PerFace.Prism -> if (faceType != null) faceMap[faceType] ?: default else default
        // TODO(uv-generation-cylinder): add Cylinder face dispatch
        // TODO(uv-generation-pyramid):  add Pyramid face dispatch
        // TODO(uv-generation-stairs):   add Stairs face dispatch
        // TODO(uv-generation-octahedron): add Octahedron face dispatch
        else -> default
    }
```

**Benefit:** Collapses 3 sites to 1. Each future shape slice adds its own branch (or overrides `resolveForFace` on its subclass). "Forgot to update a site" becomes a compile error instead of a silent regression across 5 slices × 3-4 sites = 15–20 lockstep updates.

**Severity:** HIGH | **Confidence:** High

---

### H-05: Non-Prism `PerFace` subclasses — `equals/hashCode/toString` zero coverage [HIGH]

**Location:** `isometric-shader/src/main/kotlin/.../shader/IsometricMaterial.kt:166-317`
**Source:** testing

**Issue:** All four non-Prism subclasses ship manually written `equals`, `hashCode`, `toString`. None are tested. A field silently dropped from the check (e.g. after a refactor) would go undetected.

**Fix:** Add one equality/hashCode/toString triple-test per subclass in `PerFaceSharedApiTest`. Example:
```kotlin
@Test fun `PerFace_Cylinder_equals_and_hashCode`() {
    val a = IsometricMaterial.PerFace.Cylinder(top = red, default = gray)
    val b = IsometricMaterial.PerFace.Cylinder(top = red, default = gray)
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertTrue(a.toString().contains("top="))
    assertNotEquals(a, IsometricMaterial.PerFace.Cylinder(top = blue, default = gray))
}
```

**Severity:** HIGH | **Confidence:** High

---

*(H-03 and H-06 deferred per triage — see Triage Decisions below.)*

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|----|-----|--------|----------|-------|
| H-01 | HIGH | correctness | **fix** | — |
| H-02 | HIGH | correctness | **fix** | — |
| H-03 | HIGH | correctness | **defer** | Non-Prism textured rendering is out-of-scope for scaffolding slice; each shape slice wires up its own UV provider |
| H-04 | HIGH | code-simplification + 2 others | **fix** | Highest-leverage fix — prevents 15–20 lockstep updates across the 5 downstream slices |
| H-05 | HIGH | testing | **fix** | 4 new tests, ~40 LOC |
| H-06 | HIGH | testing | **defer** | On-device AC6 already confirms end-to-end; unit tests require heavy GPU mocking for marginal benefit |
| M-01 | MED | correctness + 2 others | **fix** | Best done before pyramid UV slice |
| M-02 | MED | correctness + api-contracts | **fix** | Promote TODO to lint or remove default |
| M-03 | MED | maintainability + architecture | **fix** | Generalise before more shape-face dispatch lands |
| M-04 | MED | testing | **fix** | — |
| M-05 | MED | testing | **fix** | — |
| M-06 | MED | testing | **fix** | — |
| M-07 | MED | testing | **fix** | — |
| M-08 | MED | correctness | **fix** | Document or warn |
| M-09 | MED | performance | **fix** | Internal-only change; no API impact |
| M-10 | MED | reliability | **fix** | Keep current approach; add KDoc rationale |
| L-01 … L-09 | LOW | various | **untriaged** | Listed in report; consider bundling with MED follow-up |
| N-01 … N-08 | NIT | various | **untriaged** | Listed in report |

## Recommendations

### Must Fix (triaged "fix")

**Correctness fixes (H-01, H-02):**
- H-01: Add `vertCount > 0` guard to `GpuUvCoordsBuffer.kt:55` (~1 line)
- H-02: Replace `uvCoords.size < 6` with `2 * cmd.faceVertexCount` in `TexturedCanvasDrawHook.kt:145` (~1 line)

**Architectural fixes (H-04, M-01, M-02, M-03):**
- H-04: Extract `PerFace.resolveForFace()` shared extension; migrate 3 consumer sites (~30 LOC + test)
- M-01: Change `PerFace.Pyramid.laterals` key type to `PyramidFace.Lateral` (breaking change to empty stub — acceptable per no-deprecation policy)
- M-02: Remove `faceVertexCount = 4` default OR promote `GpuUvCoordsBuffer` TODO to a lint / range `require()`
- M-03: Generalise `RenderCommand.faceType: PrismFace?` to a `FaceIdentifier` sealed interface (or `Any?`) in core

**Test additions (H-05, M-04, M-05, M-06, M-07):**
- H-05: 4 equality/hashCode/toString triples for stub subclasses (~40 LOC)
- M-04: 1 equality triple for `PerFace.Prism` (~10 LOC)
- M-05: Positive-boundary Lateral construction asserts (~5 LOC)
- M-06: Large-`stepCount` arithmetic test for `StairsFace` (~10 LOC)
- M-07: Null-return test for `uvCoordProviderForShape(Knot)` (~3 LOC)

**Minor design follow-ups (M-08, M-09, M-10):**
- M-08: Document or log warning when non-Prism per-slot materials are passed but not collected
- M-09: Swap `LinkedHashMap` → `EnumMap<PrismFace, MaterialData>` in `PerFaceMaterialScope.build()` and `Prism.of()` (internal, no API change)
- M-10: Add KDoc rationale noting `require()` is deliberate over compile-time builder

**Total estimated fix pass:** ~200–300 LOC across ~10 files + `.api` dump refresh for M-01/M-03.

### Deferred (triaged "defer")

- H-03 — Non-Prism textured silent-skip: revisit when each shape slice wires up its `UvCoordProvider`
- H-06 — Consumer stub behavioural tests: revisit when cylinder slice fills in real resolution logic

### Untriaged (LOW / NIT)

9 LOW + 8 NIT findings listed in the findings table. Consider bundling the cheap ones (L-01 rename, L-03 TODO markers, L-05/L-06 test additions, N-02/N-03 KDoc) into the same follow-up implement pass. The security NITs (N-04, N-05, N-07) are non-exploitable and can remain as-is.

Re-triage later via `/wf-review texture-material-shaders uv-generation-shared-api triage`.

## Recommended Next Stage

- **Option A (Recommended):** `/wf-implement texture-material-shaders uv-generation-shared-api` — apply the triaged fixes (4 HIGH + 10 MED). This is a substantial follow-up pass; most-impactful fix is H-04 (helper extraction) which should land before the 5 shape UV slices fan out. Consider `/compact` first — review dispatch chatter is noise for the fix pass.
- **Option B:** `/wf-handoff texture-material-shaders` — defer all fixes to a later slice or a cleanup PR. Only viable if user accepts the HIGHs as documented follow-ups; not recommended given H-04's leverage effect on downstream slices.
- **Option C:** `/wf-plan texture-material-shaders uv-generation-cylinder` — skip the fix pass and proceed to the first shape slice. H-04 would then cost ~3x more to fix later because cylinder will have added a 4th lockstep site.
- **Option D:** `/wf-extend texture-material-shaders from-review` — elevate the follow-ups into a new tracked slice (e.g. `uv-generation-shared-api-followups`) so each finding has its own slice artifact. Useful if the fix pass turns out larger than expected.
- **Option E:** `/wf-amend texture-material-shaders from-review` — rewrite the slice acceptance criteria to include M-01/M-02/M-03 upfront. Not applicable — the original slice deliberately scoped to shared-API-only and was verified complete.

## Fix Status

Second implementation pass ran 2026-04-17 in Reviews Mode (`/wf-implement ... reviews`).
Scope: triaged `fix` findings + cheap LOW/NIT bundle per user triage.

| ID   | Severity | Status       | Notes                                                                                |
|------|----------|--------------|--------------------------------------------------------------------------------------|
| H-01 | HIGH     | Fixed        | Added `vertCount > 0` to `GpuUvCoordsBuffer.kt:52` guard.                            |
| H-02 | HIGH     | Fixed        | Replaced `< 6` with `< maxOf(6, 2 * command.faceVertexCount)` in `TexturedCanvasDrawHook.kt`. 6 floor preserves `computeAffineMatrix` invariant. |
| H-03 | HIGH     | Deferred     | Pre-triaged `defer` — non-Prism textured silent-skip; revisit when each shape slice wires up its UvCoordProvider. |
| H-04 | HIGH     | Fixed        | Extracted `public fun IsometricMaterial.PerFace.resolveForFace(faceType: FaceIdentifier?)`; migrated 3 consumer sites; TODO markers for 4 downstream slices. `collectTextureSources` intentionally left separate (different concern). |
| H-05 | HIGH     | Fixed        | Added 4 equality/hashCode/toString triples for non-Prism PerFace subclasses in `PerFaceSharedApiTest`. |
| H-06 | HIGH     | Deferred     | Pre-triaged `defer` — consumer stub behavioural tests; revisit when cylinder slice fills in real resolution. |
| M-01 | MED      | Fixed        | `PerFace.Pyramid.laterals` retyped to `Map<PyramidFace.Lateral, MaterialData>`; `require()` range check removed (constructor enforces). Breaking change. |
| M-02 | MED      | Fixed (partial) | Added `require(faceVertexCount in 3..24)` + KDoc warning for external `SceneProjector` implementors. Kept default `= 4` to avoid breaking 11+ internal sites (deviation from review's preferred option). |
| M-03 | MED      | Fixed        | Introduced `sealed interface FaceIdentifier`; five face types now implement it; `RenderCommand.faceType`, `SceneGraph`, `SceneProjector.add`, `IsometricEngine.add`, `resolveForFace` all retyped. Breaking change on public `SceneProjector.add`. |
| M-04 | MED      | Fixed        | Added `PerFace.Prism` equality triple in `PerFaceSharedApiTest`.                     |
| M-05 | MED      | Fixed        | Added positive-boundary `PyramidFace.Lateral(0)`/`Lateral(3)` construction + companion-constant equality assertion. |
| M-06 | MED      | Fixed        | Added `stepCount=25` stress test in `ShapeFaceEnumTest` walking all 52 path indices. |
| M-07 | MED      | Fixed        | Added `Knot` to null-return test, gated with `@OptIn(ExperimentalIsometricApi::class)`. |
| M-08 | MED      | Fixed        | Added `warnIfNonPrismPerFaceHasTexturedSlots` helper in `GpuTextureManager`. Fires one `Log.w` per variant kind naming skipped Textured sources. |
| M-09 | MED      | Fixed        | Swapped `LinkedHashMap` → `EnumMap<PrismFace, MaterialData>` in both `Prism` init and `PerFaceMaterialScope.build`. Internal-only; `Map.equals` unchanged. |
| M-10 | MED      | Fixed        | Added four-bullet "Validation via `require()` rather than a compile-time builder" rationale block on `PerFace` class KDoc. |
| L-01 | LOW      | Fixed        | `git mv UvCoordProviderFactory.kt UvCoordProviderForShape.kt` — history follows.     |
| L-02 | LOW      | Untriaged    | Pyramid-slice decision — bundle later.                                                |
| L-03 | LOW      | Fixed        | Added explicit three-bullet TODO(uv-generation-<shape>) lists to each non-Prism PerFace subclass KDoc. |
| L-04 | LOW      | Untriaged    | Per-shape DSL builder — each shape slice should add its own builder.                 |
| L-05 | LOW      | Fixed        | Added `RenderCommand faceVertexCount` round-trip equality/hashCode/toString test.    |
| L-06 | LOW      | Fixed        | Narrowed `assertIs<PerFace>` to `assertIs<PerFace.Prism>` in `IsometricMaterialTest`. |
| L-07 | LOW      | Untriaged    | Additional KDoc on `Prism` constructor visibility — bundle later.                    |
| L-08 | LOW      | Untriaged    | Cross-module HashMap lookup dedup — revisit during a perf pass.                      |
| L-09 | LOW      | Untriaged    | `allIdentity` scan — revisit during a perf pass.                                     |
| N-01 | NIT      | Untriaged    | Test fixture fragility — low priority.                                                |
| N-02 | NIT      | Fixed        | Added "Why stepCount is required" KDoc block on `StairsFace.fromPathIndex`.          |
| N-03 | NIT      | Fixed        | Added explicit `public` on `PrismFace` companion + `fromPathIndex`.                  |
| N-04 | NIT      | Untriaged    | Int-overflow security NIT (non-exploitable, loop-capped).                            |
| N-05 | NIT      | Untriaged    | Pre-existing overflow in `GrowableGpuStagingBuffer` — out of slice scope.            |
| N-06 | NIT      | Untriaged    | Defence-in-depth consistency for PerFace nesting guard.                              |
| N-07 | NIT      | Untriaged    | `StairsFace` 2 * stepCount + 2 overflow at `Int.MAX_VALUE` — library-internal.       |
| N-08 | NIT      | Untriaged    | `faceVertexCount == 0` benign no-op (now `require()`-prevented by M-02 on `RenderCommand`). |

**Totals:** Fixed 20 | Fixed (partial) 1 | Deferred 2 | Untriaged 10.

`.api` deltas: `isometric-core.api` (+13 / -10), `isometric-shader.api` (+1 / 0).
