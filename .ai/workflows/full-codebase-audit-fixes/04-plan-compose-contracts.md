---
schema: sdlc/v1
type: plan
slug: full-codebase-audit-fixes
slice-slug: compose-contracts
status: complete
stage-number: 4
created-at: "2026-07-07T12:11:19Z"
updated-at: "2026-07-07T12:46:38Z"
metric-files-to-touch: 6
metric-step-count: 12
has-blockers: false
revision-count: 1
tags: [isometric-compose, group-alpha, stability-annotations, kdoc, api-dump]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-compose-contracts.md
  siblings:
    - 04-plan-core-math.md
    - 04-plan-gesture-coordination.md
    - 04-plan-view-module.md
    - 04-plan-shape-geometry.md
    - 04-plan-docs-and-changelog.md
    - 04-plan-snapshot-sweep-gate.md
  implement: 05-implement-compose-contracts.md
next-command: wf-implement
next-invocation: "/wf implement full-codebase-audit-fixes compose-contracts"
---

# Plan: Compose Contracts

## The Plan

The audit identified four issues in `isometric-compose`, and it turns out one of them — the E4 annotation problem — is effectively done. Commit `187e8f4` already corrected `SceneConfig` from `@Immutable` to `@Stable` with a clear justification in the KDoc (CameraState and NodeDragState are mutable observable holders, which disqualifies the stricter contract). `AdvancedSceneConfig` carries `@Stable` too, which is the right answer: it holds a mutable `engine` and callback lambdas that are intentionally excluded from `equals()`. The uncommitted working tree adds the binary-compatible secondary constructor for `AdvancedSceneConfig` — that finishes E4's scope. There is no dump change to fear here beyond what `187e8f4` already produced and passed.

What's left is real work. G1 is the weight-bearing change: `GroupNode.renderTo` currently passes the child `RenderContext` straight through without applying the group's own `alpha`, even though `IsometricNode.alpha` KDoc promises render-time application. The propagation channel is `RenderContext` — it already accumulates transforms (position, rotation, scale) exactly the same way, and adding `accumulatedAlpha: Float = 1f` follows the identical pattern. `GroupNode` reads its `alpha`, calls `context.withAlpha(alpha)` to produce a child context with the product, and each leaf node (`ShapeNode`, `PathNode`, `BatchNode`, `CustomRenderNode`) reads `context.accumulatedAlpha` and multiplies it into `applyAlpha(color)`. Nested groups multiply naturally because each `withAlpha` call multiplies against whatever the parent already accumulated — `0.5 * 0.5 = 0.25`, not an overwrite. The `Group` composable gains an `alpha: Float = 1f` parameter wired via `set(alpha) { this.alpha = it; markDirty() }`, regenerating the API dump with exactly one additive change.

The alpha=0 semantics question is open and PO-owned: skip children entirely (early return, free, idiomatic — `isVisible=false` and `graphicsLayer(alpha=0f)` in Compose both do this) versus propagate and render fully transparent (costs a full tree traversal). The plan recommends the early return. G3 and F3 are smaller: `AdvancedSceneConfig` gets KDoc documenting the deliberate `equals()` callback exclusion and what callers must do instead, and the alpha-scaling render test gets pinned to an exact expected value (`≈127` for `0.5f * 255`) with three new `GroupNode` propagation tests.

## Current State

### G1 — GroupNode alpha propagation
**Status: Not implemented.** `GroupNode.renderTo` (IsometricNode.kt:205-228) creates a `childContext` via `effectiveContext.withTransform(...)` but never calls anything related to alpha. The `alpha` field on `IsometricNode` has a setter with validation and `applyAlpha()` exists in the uncommitted working tree, but `GroupNode` never reads its own `alpha` and never threads it into the child context. `ShapeNode`, `PathNode`, `BatchNode` each call `applyAlpha(color)` using their own `this.alpha`, which is correct for leaf-level alpha — but Group alpha is entirely absent from the propagation path. The `Group` composable in `IsometricComposables.kt` has no `alpha` parameter at all. The KDoc on `IsometricNode.alpha` states "Applied at render time by scaling the command color's alpha channel" — true for leaf nodes only, false for GroupNode.

### E4 — AdvancedSceneConfig/@Stable annotation honesty
**Status: Already fixed (commit 187e8f4 + uncommitted working tree).** `SceneConfig` was changed from `@Immutable` to `@Stable` in commit `187e8f4` with correct justification in KDoc. `AdvancedSceneConfig` carries `@Stable` (correct — it holds a mutable engine and callback lambdas). The uncommitted working tree adds the binary-compatible secondary constructor for `AdvancedSceneConfig` (the `nodeDragState`-less overload mirroring what `SceneConfig` already has). API dumps were regenerated in `187e8f4`. No further annotation or dump work is needed for E4 — only the secondary constructor change needs to be committed (it's part of the current working-tree diff). **The G3 KDoc work on `AdvancedSceneConfig` is the remaining obligation.**

### G3 — Callback re-registration KDoc
**Status: Not implemented.** `AdvancedSceneConfig.equals()` explicitly excludes all callback parameters (`onHitTestReady`, `onFlagsReady`, `onRenderError`, `onEngineReady`, `onRendererReady`, `onBeforeDraw`, `onAfterDraw`, `onPreparedSceneReady`) — they are not compared. This is deliberate (lambdas are generally not equals-comparable in Kotlin), but it has a caller-visible implication: changing only a callback and re-passing `AdvancedSceneConfig` to `IsometricScene` does NOT trigger recomposition. No KDoc currently explains this or tells callers what to do instead (use `rememberUpdatedState` for callbacks, or wrap the whole config in a `remember { }` block keyed on any stable values that must change).

### F3 — Alpha-scaling test exact-value pin
**Status: Partially implemented.** `IsometricNodeRenderTest.alphaHalfScalesCommandColorAlphaBelowOriginal` (line 225-241) sets `node.alpha = 0.5f` on a ShapeNode with a fully-opaque color (a=255) and asserts only `cmd.color.a < 255.0`. This is a direction test, not an exact-value test. With `IsoColor.withAlpha(0.5f)`: `a = (255.0 * 0.5).coerceIn(0.0, 255.0) = 127.5`. The test should assert `cmd.color.a` is within tolerance of `127.5`. No GroupNode alpha tests exist at all.

## Simplicity Ladder

- **G1 alpha propagation channel** → Rung 3 (reuse): `RenderContext` already accumulates `accumulatedScale` as a `val` field with a `withTransform` factory; `accumulatedAlpha` follows the identical pattern. No new concept, no new class — just a new scalar field in the existing immutable context object.
- **G1 Group composable alpha param** → Rung 3 (reuse): the `alpha` wiring in `Shape`, `Path`, `Batch`, `CustomNode` composables is already the pattern; `Group` gets the same `set(alpha)` update block.
- **G1 leaf multiplication** → Rung 3 (reuse): `applyAlpha(color)` already exists on `IsometricNode` (added in uncommitted working tree). Leaf nodes change from `applyAlpha(color)` (which uses `this.alpha` only) to `applyAlpha(color, context.accumulatedAlpha)` — or equivalently, the leaf's own alpha is pre-multiplied with the context alpha before calling `color.withAlpha()`.
- **E4** → Rung 3 (reuse): already done in commit 187e8f4 + working tree.
- **G3 KDoc** → Rung 4 (new prose): no reuse candidate; this is original documentation.
- **F3 exact-value test** → Rung 3 (reuse): extends the existing `alphaHalfScalesCommandColorAlphaBelowOriginal` test; the `IsoColor.withAlpha` formula is deterministic so the exact expected value can be computed.

## Applied Learnings

No applicable learnings found. (`.ai/solutions/INDEX.md` is absent.)

## Likely Files / Areas to Touch

- `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricNode.kt` — G1: `GroupNode.renderTo` alpha propagation; update `IsometricNode.alpha` KDoc; update leaf-node `renderTo` methods to use `context.accumulatedAlpha` in multiplication.
- `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/RenderContext.kt` — G1: add `accumulatedAlpha: Float = 1f` val field; add `withAlpha(alpha: Float): RenderContext` factory method.
- `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricComposables.kt` — G1: add `alpha: Float = 1f` parameter to `Group` composable; wire it in the update block.
- `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/AdvancedSceneConfig.kt` — G3: add callback-exclusion KDoc; E4 secondary constructor (already in working tree, needs commit).
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricNodeRenderTest.kt` — F3: pin exact alpha value; add three GroupNode alpha propagation tests.
- `isometric-compose/api/isometric-compose.api` — G1: regenerate via `apiDump`; diff must show only the Group alpha addition.

## Proposed Change Strategy

`RenderContext` gains an `accumulatedAlpha: Float = 1f` constructor field (private, like `accumulatedPosition`) and a `withAlpha(alpha: Float): RenderContext` factory that returns `copy(accumulatedAlpha = (this.accumulatedAlpha * alpha).coerceIn(0f, 1f))`. `GroupNode.renderTo` is modified to call `context.withAlpha(alpha)` on the already-computed `effectiveContext`, yielding a `childContext` that carries the multiplied alpha. Each leaf node's `renderTo` is modified to compute the effective color as `color.withAlpha(context.accumulatedAlpha * this.alpha)` — the context accumulates the group chain, the node applies its own alpha on top. If `GroupNode.alpha == 0f`, the method returns early (before building `effectiveContext`) — this skips the entire subtree at zero cost. The `Group` composable gains `alpha: Float = 1f` and wires it via `set(alpha) { this.alpha = it; markDirty() }`. KDoc on `IsometricNode.alpha` is updated to describe both paths (leaf applies directly; Group multiplies into children). `AdvancedSceneConfig` gets a KDoc block explaining the callback-exclusion contract and recommending `rememberUpdatedState`.

## Step-by-Step Plan

1. **Add `accumulatedAlpha` to `RenderContext`** — Add `private val accumulatedAlpha: Float = 1f` to the primary constructor. Add `fun withAlpha(alpha: Float): RenderContext` factory: multiplies `this.accumulatedAlpha * alpha`, coerces to `[0f, 1f]`, returns a new `RenderContext` with the product. Add `val effectiveAlpha: Float get() = accumulatedAlpha` (or just expose as a method) for leaf nodes to read.
2. **Update `equals`/`hashCode` in `RenderContext`** — Include `accumulatedAlpha` in both (mirrors how `accumulatedScale` is handled).
3. **Add new GroupNode alpha tests (constructive-proof step)** — Write three tests in `IsometricNodeRenderTest`: (a) `groupAlphaHalfScalesChildCommandAlpha` — GroupNode with alpha=0.5, child ShapeNode with alpha=1.0: expect `cmd.color.a ≈ 127.5`; (b) `nestedGroupAlphaMultiplies` — outer Group alpha=0.5, inner Group alpha=0.5: expect `cmd.color.a ≈ 63.75`; (c) `groupAlphaZeroSkipsChildren` — GroupNode alpha=0.0: expect empty commands. All three must FAIL before step 4.
4. **Modify `GroupNode.renderTo` to propagate alpha** — After computing `effectiveContext`, add: `if (alpha == 0f) return`. Then compute `val alphaContext = effectiveContext.withAlpha(alpha)` and pass `alphaContext` (not `effectiveContext`) to `withTransform`. Children receive the product.
5. **Modify leaf nodes to multiply context alpha** — In `ShapeNode.renderTo`, `PathNode.renderTo`, `BatchNode.renderTo`: change `applyAlpha(color)` to `color.withAlpha(context.accumulatedAlpha * alpha)` (inline multiplication, avoids a second allocation on the fast path). In `CustomRenderNode.renderTo`: apply the same product to each command's color.
6. **Update `IsometricNode.alpha` KDoc** — Expand to state: "For leaf nodes (ShapeNode, PathNode, BatchNode, CustomRenderNode): multiplied with the accumulated group alpha from ancestor GroupNodes, then applied to the render command color. For GroupNodes: multiplied into the RenderContext and propagated to all descendants; a GroupNode with alpha=0 skips rendering entirely. Nested Groups multiply their alphas (never overwrite)."
7. **Add `alpha` parameter to `Group` composable** — Add `alpha: Float = 1f` after `visible`. Add `set(alpha) { this.alpha = it; markDirty() }` to the update block. Update the `@param` KDoc.
8. **Update `IsometricNode.alpha` KDoc on the `Group` composable** — The `@param alpha` doc should state the multiplication semantics and the alpha=0 skip behavior.
9. **Add G3 KDoc to `AdvancedSceneConfig`** — Above the `equals` override, add a `// Callback fields are intentionally excluded from equals()` comment block. Also add a class-level KDoc paragraph: "**Callback re-registration:** The callback parameters (`onHitTestReady`, `onFlagsReady`, etc.) are excluded from `equals()` and `hashCode()`. Replacing an `AdvancedSceneConfig` instance whose only difference is a callback will NOT trigger recomposition. To update callbacks at runtime, wrap them in `rememberUpdatedState` and pass the `.value` into the stable config, or key the entire config on a version counter."
10. **Pin F3 exact alpha value** — In `alphaHalfScalesCommandColorAlphaBelowOriginal`: change `assertTrue(cmd.color.a < 255.0)` to `assertEquals(127.5, cmd.color.a, 0.5)` (tolerance 0.5 to cover Double arithmetic). This must now be a failing test before step 5 (because currently `cmd.color.a` could be anything below 255 — pinning to 127.5 catches a regression if the formula changes).
11. **Regenerate API dump** — Run `./gradlew :isometric-compose:apiDump`. Inspect the diff. Must show only the `Group` composable descriptor gaining the `alpha: Float` parameter. Any unexpected changes are a blocker.
12. **Run `apiCheck` + all JVM tests** — `./gradlew :isometric-compose:apiCheck :isometric-compose:test`. All must pass, including all three new GroupNode alpha tests and the pinned F3 test.

## Verification Strategy

| AC | Tool / method + ladder rung | Environment need — satisfiable? | What must be BUILT | Fallback chain |
|----|------------------------------|--------------------------------|-------------------|----------------|
| AC-G1 behavior: Group alpha=0.5 multiplies into descendants; nested groups multiply; alpha=0 skips | JVM unit test — `IsometricNodeRenderTest` (Rung 1 Robolectric-equivalent / pure JVM) | JVM — yes, no Android device needed | Three new `GroupNode` alpha tests (steps 3, 6) that FAIL pre-fix, PASS post-fix | None needed — pure JVM math |
| AC-G1 API: `Group` composable exposes `alpha`; apiDump regenerated; apiCheck passes | `./gradlew :isometric-compose:apiDump && apiCheck` (Rung 1 Gradle gate) | Gradle + JVM — yes | Step 11 (apiDump run) | Manual .api file diff review |
| AC-G1 docs: KDoc on `IsometricNode.alpha` and `Group` composable states the now-true contract | Human read-through at review stage (manual-review residual) | Source KDoc, no runtime | Step 6, 8 KDoc edits | N/A — prose judgment |
| constraint-resolution: po-accepted: KDoc prose accuracy is a reviewer judgment call; no automated tooling can verify statement correctness against implemented behavior — this is the standard residual for documentation ACs. |
| AC-E4: Stability annotations match actual mutability; apiCheck passes with dumps regenerated | Working-tree diff + `./gradlew :isometric-compose:apiCheck` (Rung 1 Gradle gate) | Gradle — yes | Commit working-tree changes (secondary constructor already written); run apiCheck | Manual annotation review |
| AC-G3: KDoc documents callback-only changes don't re-register | Human read-through at review stage (manual-review residual) | Source KDoc | Step 9 KDoc edit | N/A — prose judgment |
| constraint-resolution: po-accepted: same residual as AC-G1 docs — prose judgment at review stage. |
| AC-F3: Alpha scaling test asserts exact value, not just < 255 | JVM unit test — `IsometricNodeRenderTest.alphaHalfScalesCommandColorAlphaBelowOriginal` (Rung 1 pure JVM) | JVM — yes | Step 10 (pin exact value to 127.5 ± 0.5) | None needed |

## Test / Verification Plan

### Automated checks

- **lint/typecheck:** `./gradlew :isometric-compose:compileDebugKotlin` — catches any signature errors in the `RenderContext.withAlpha` or `Group` composable parameter.
- **unit tests:** `./gradlew :isometric-compose:test` — runs `IsometricNodeRenderTest` including F3 (exact alpha pin) and the three new GroupNode alpha tests. All must pass.
- **api gate:** `./gradlew :isometric-compose:apiCheck` — verifies the dump matches the regenerated .api file after the Group alpha parameter is added.

### Interactive verification (human-in-the-loop)

- **What to verify:** The alpha propagation semantics stated in KDoc are accurate (G1 docs, G3 KDoc); the callback-exclusion description matches what `equals()` actually does.
- **Platform & tool:** Source code read-through. No device, emulator, or runtime needed — these are documentation ACs. The review stage (`/wf review`) is the natural gate.
- **Companion skills:** None required.
- **Steps:** At review stage, read `IsometricNode.alpha` KDoc and verify it accurately describes: (a) leaf multiplication, (b) Group multiplication through children, (c) alpha=0 skip behavior. Read `AdvancedSceneConfig` class-level KDoc paragraph about callbacks and verify it matches `equals()` implementation (which excludes all `on*` fields).
- **Pass criteria:** KDoc is accurate, complete, and does not contradict the implementation.

## Risks / Watchouts

- **R1 — RenderContext @Immutable:** Adding `accumulatedAlpha` must keep all fields `val`. A mutable field would violate `@Immutable`. Use the `val` constructor pattern (same as `accumulatedScale`).
- **R2 — apiDump churn:** E4 dump changes already landed in `187e8f4`. Only G1's Group alpha composable parameter will change the dump in this slice. Verify with `git diff` on the .api file post-dump.
- **R3 — alpha=0 semantics (PO decision):** See Blockers. The plan implements early return; if the PO prefers fully-transparent rendering, step 4 changes from `if (alpha == 0f) return` to `val alphaContext = effectiveContext.withAlpha(alpha)` with no early return (children receive alpha=0f and render 0-alpha commands, which the renderer draws as invisible — same visual result at full traversal cost).
- **R4 — Cross-slice sequencing:** `gesture-coordination` restructures `IsometricScene.kt`; this slice does not touch it. Commit this slice first on the shared branch to keep apiDump attribution clean.
- **R5 — Working-tree E4 changes must be committed:** The `AdvancedSceneConfig` secondary constructor and the `IsometricNode` `applyAlpha` refactor are in the working tree but uncommitted. They should be committed as part of this slice's implementation (or folded into step 11's apiDump run as a prerequisite).
- **R6 — Adopted orphan files (PO decision, plan discovery 2026-07-07):** This slice ALSO commits the four unclaimed working-tree leftovers from the fresh-eyes-review-fixes workflow — `GestureConfig.kt` and `GestureEvents.kt` (binary-compat secondary constructors), `IsometricRenderer.kt`, and `DragEventClarityTest.kt` — as one coherent "land pre-existing contract fixes" commit alongside the `AdvancedSceneConfig` constructor (same ABI-compat family). Commit these BEFORE the G1 work so the apiDump diff in step 11 attributes cleanly to Group alpha alone.

## Dependencies on Other Slices

- `docs-and-changelog` picks up the `Group` composable reference-table `.mdx` row after this lands. No hard dependency — just ordering: this slice first, then docs.
- `snapshot-sweep-gate` may see golden churn if any existing Paparazzi scenes exercise `Group` alpha (unlikely — currently Group has no alpha parameter). Low risk.
- `gesture-coordination` restructures `IsometricScene.kt` but does NOT touch `IsometricNode.kt`, `RenderContext.kt`, or `AdvancedSceneConfig.kt`. No file-level conflict. Commit sequencing: compose-contracts first.
- **apiDump sequencing:** This slice regenerates `isometric-compose.api` for G1. If `gesture-coordination` also triggers any compose API changes (unlikely — gesture changes are in scene plumbing, not the public composable API), the two slices must be committed in order and apiDump run after each. Flag this at implementation time.

## Assumptions

- `IsoColor.withAlpha(Float)` is correct and tested (it multiplies `a * alpha` and coerces). The plan uses it as-is.
- `RenderContext` can safely gain a new constructor field with a default value without breaking existing call sites (all `RenderContext` construction is internal to the compose module — no external callers construct it directly).
- Paparazzi snapshot tests do not currently exercise `GroupNode.alpha` (no test fixture sets a non-1.0 group alpha), so G1's behavior change will not cause golden flakes without a matching scene update.
- The `applyAlpha` refactor already in the working tree (converting inline `if (alpha < 1f) color.withAlpha(alpha) else color` to a shared protected method) will be committed as part of this slice.

## Blockers

None. The alpha=0 semantics decision was resolved by the PO at plan discovery
(2026-07-07, see po-answers.md Stage 4): **skip children** — early return in
`GroupNode.renderTo` when `alpha == 0f`. Step 4 implements exactly that; the
`groupAlphaZeroSkipsChildren` test (Step 3c) pins it.

## Freshness Research

**Compose @Stable/@Immutable semantics (verified current for Compose 1.5.x):**
The distinction is well-established: `@Immutable` promises all publicly observable properties will never change after construction; `@Stable` promises only that `equals()` is well-defined and stable, and that if `equals()` returns `true` Compose can skip recomposition. `SceneConfig` holds `CameraState` (a mutable observable holder compared by reference identity) and `NodeDragState` (same), making `@Immutable` incorrect — the object's public state can change. `@Stable` is the correct annotation. This matches what commit `187e8f4` already implemented.

**`$stable` synthetic fields in binary-compatibility-validator:** The `$stable` field is a Compose compiler-generated bitmask (0 = stable, 1 = unstable) baked into the bytecode for each class. It is sensitive to the `@Stable`/`@Immutable`/`@Unstable` annotation on the class and on each parameter type. `binary-compatibility-validator` (0.17.0) does NOT track `$stable` fields in `.api` dumps — it tracks only public JVM method and field descriptors. Therefore swapping `@Immutable` for `@Stable` on `SceneConfig` does NOT change the `.api` file. The empirical apiDump check from the slice definition is still correct discipline (run it and inspect), but the risk of an unexpected dump diff from E4 is low.

**Compose-idiomatic alpha propagation pattern:** Compose's own `graphicsLayer { alpha = ... }` works by setting a layer-level alpha that the render pass applies to the whole layer. The equivalent for a custom render tree without graphicsLayer is to carry the accumulated alpha in the render context (the approach taken here). Paint-level multiplication (calling `Canvas.drawPath` with a Paint whose alpha is set) is the _Android_ rendering idiom but is not accessible from the Compose draw scope without dropping to native canvas. The `IsoColor.withAlpha` approach (multiplying into the color's `a` field before emitting a `RenderCommand`) is the correct rung for this project's architecture — it keeps the propagation entirely in the JVM-testable domain tree, with no Compose rendering primitives involved.

## Revision History

- **2026-07-07T12:46:38Z — Mode: PO discovery (plan stage, parallel-all).** Resolved
  `PO-DECISION-PENDING: alpha=0 semantics` → **skip children** (recommended option accepted);
  Blockers cleared, `has-blockers: false`. Added R6: this slice adopts the four orphaned
  working-tree files (`GestureConfig.kt`, `GestureEvents.kt`, `IsometricRenderer.kt`,
  `DragEventClarityTest.kt`) per PO decision — committed before the G1 work for clean
  apiDump attribution.

## Recommended Next Stage

- **Option A (default):** `/wf implement full-codebase-audit-fixes compose-contracts` — plan is complete and ready for execution. Consider running `/compact` before implementing — workflow state lives in the artifact files on disk.
- **Option B:** Implement after `core-math` slice if PO wants the dependency order respected (core-math has no compose dependencies, but the PO chose it as the first slice for the session).
