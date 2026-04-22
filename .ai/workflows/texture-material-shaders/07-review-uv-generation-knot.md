---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: uv-generation-knot
status: complete
stage-number: 7
created-at: "2026-04-20T23:25:05Z"
updated-at: "2026-04-20T23:25:05Z"
verdict: ship-with-caveats
commands-run: [correctness, security, code-simplification, testing, maintainability, reliability, refactor-safety, performance, api-contracts, architecture]
metric-commands-run: 10
metric-findings-total: 32
metric-findings-raw: 47
metric-findings-blocker: 0
metric-findings-high: 5
metric-findings-med: 11
metric-findings-low: 10
metric-findings-nit: 6
metric-triage-fix: 0
metric-triage-defer: 32
metric-triage-dismiss: 0
tags: [uv, knot, review, experimental, bag-of-primitives, ship-with-caveats]
refs:
  index: 00-index.md
  slice-def: 03-slice-uv-generation-knot.md
  plan: 04-plan-uv-generation-knot.md
  implement: 05-implement-uv-generation-knot.md
  verify: 06-verify-uv-generation-knot.md
  sub-reviews:
    - 07-review-uv-generation-knot-correctness.md
    - 07-review-uv-generation-knot-security.md
    - 07-review-uv-generation-knot-code-simplification.md
    - 07-review-uv-generation-knot-testing.md
    - 07-review-uv-generation-knot-maintainability.md
    - 07-review-uv-generation-knot-reliability.md
    - 07-review-uv-generation-knot-refactor-safety.md
    - 07-review-uv-generation-knot-performance.md
    - 07-review-uv-generation-knot-api-contracts.md
    - 07-review-uv-generation-knot-architecture.md
next-command: wf-handoff
next-invocation: "/wf-handoff texture-material-shaders"
---

# Review: uv-generation-knot

## Verdict

**Ship with caveats.**

Zero blockers across 10 review commands and 47 raw findings. The 5 HIGH findings cluster into three coherent themes — `sourcePrisms` design (drift + mutability + allocation), `quadBboxUvs` hot-path allocation, and `forKnotFace` error-handling consistency with sibling `forPrismFace`. None block correctness or AC achievement (verify pass 1 confirmed all 5 ACs met with 0 issues). All findings have been triaged as **deferred** per user direction, matching the precedent set by sibling slices (cylinder, pyramid, stairs) where review fixes are folded into downstream tightening / extension slices rather than blocking handoff.

## Domain Coverage

| Domain | Command | Status | Verdict |
|--------|---------|--------|---------|
| Logic correctness | `correctness` | Issues | APPROVE_WITH_COMMENTS |
| Security | `security` | Issues | APPROVE_WITH_COMMENTS |
| Code reuse / duplication | `code-simplification` | Issues | APPROVE_WITH_COMMENTS |
| Test coverage | `testing` | Issues | APPROVE_WITH_COMMENTS |
| Maintainability | `maintainability` | Issues | APPROVE_WITH_COMMENTS |
| Reliability | `reliability` | Issues | REQUEST_CHANGES |
| Refactor safety | `refactor-safety` | Clean | SAFE |
| Performance | `performance` | Issues | REQUEST_CHANGES |
| API contracts | `api-contracts` | Issues | APPROVE_WITH_COMMENTS |
| Architecture | `architecture` | Issues | APPROVE_WITH_COMMENTS |

Two reviewers (`reliability`, `performance`) filed REQUEST_CHANGES. Both are HIGH-severity findings that the user has triaged as deferred follow-ups; they do not block this slice's ship-with-caveats verdict but should be folded into a downstream tightening pass.

## All Findings (Deduplicated)

### HIGH (5)

| ID | Source(s) | File:Line | Issue |
|----|-----------|-----------|-------|
| **U-01** | api-contracts (API-01) | `Knot.kt:35-39` | `sourcePrisms` returns mutable `ArrayList` masquerading as `List` — silent UV corruption risk via unsafe cast |
| **U-02** | performance (P-1) | `UvGenerator.kt:forKnotFace` | No identity-cache; 20 `FloatArray(8)` allocations per frame per Knot. Same class as the H-5 cache pattern adopted by Pyramid/Cylinder |
| **U-03** | reliability (REL-K-01) | `UvGenerator.kt:forKnotFace` | Sub-prism delegation does not wrap-rethrow `forPrismFace` exceptions; loses Knot context (forPrismFace's own message names the source Prism, not the originating Knot) |
| **U-04** | code-simp (CS-1) | `UvGenerator.kt:quadBboxUvs` | `Pair(u, v)` allocated per vertex inside loop; axis-selection `when` is loop-invariant — should be hoisted before the `for (i in 0..3)` loop |
| **U-05** | code-simp (CS-2) + maintainability (MA-1) + security (SEC-03) + reliability (REL-K-03) + performance (P-3) + architecture (A-KNOT-01) | `Knot.kt:35-53` | Three Prism dimension constants duplicated between `sourcePrisms` (instance val) and `createPaths()` (companion). Six reviewers flagged this from different angles: drift surface (cross-module test guard), mutable cost (3 Prism + 36 Path allocations per Knot construction), naming/clarity. Architecture rates HIGH; recommended fix is a private companion `CANONICAL_PRISMS` referenced by both sites |

### MED (11)

| ID | Source(s) | File:Line | Issue |
|----|-----------|-----------|-------|
| **U-06** | correctness (CR-1), security (SEC-01), code-simp (CS-3), maintainability (MA-4), reliability (REL-K-04), api-contracts (API-04), architecture (A-KNOT-07) | `UvGenerator.kt:381-383` | Dead `else -> throw` arm in `forKnotFace`; the `require()` guard above already covers `0..19`. **7 reviewers flagged**. SEC-01 elevates to MED because the dead message hardcodes "20 faces" and would silently lie if `paths.size` ever changes |
| **U-07** | code-simp (CS-4) + performance (P-2) | `UvGenerator.kt:quadBboxUvs` | Six `minOf`/`maxOf` traversals over the 4-point path; single forward pass would halve comparisons |
| **U-08** | reliability (REL-K-02) | `UvGenerator.kt:quadBboxUvs` | No NaN/Infinity guard on vertex coordinates; `minOf`/`maxOf` propagate NaN silently to GPU |
| **U-09** | testing (TS-1) | `UvGeneratorKnotTest.kt` | `forAllKnotFaces` only size-checked; no per-element equivalence with `forKnotFace(_, i)`. A future cache refactor could silently diverge |
| **U-10** | testing (TS-2) | `UvGeneratorKnotTest.kt` | Custom quad UVs (faces 18-19) range-checked `[0,1]` but never compared against concrete coordinates. Possible degenerate UV (V axis collapsed to 0) on quad 18 would pass current tests |
| **U-11** | correctness (CR-2) + testing (TS-3) | `UvGeneratorKnotTest.kt` | Delegation tests only cover `localFaceIndex = 0` (faces 0, 6, 12). The `faceIndex % 6` arithmetic is never exercised at non-zero local index — a hardcoded `localFaceIndex = 0` bug would not be caught |
| **U-12** | api-contracts (API-02) | `Knot.kt` (KDoc) + `IsometricNode.renderTo` | `perFace {}` silent fallback to `PerFace.default` has no compile-time signal or runtime warning at the `Shape(Knot(...), material)` call site. Per §6/§7 of api-design-guideline, a `Log.w` at `ShapeNode.renderTo` would surface the caller mistake |
| **U-13** | api-contracts (API-03) | `Knot.kt:35` | `sourcePrisms` name doesn't communicate the pre-transform / UV-only role. A developer may use these Prisms for hit-testing or bounds queries, producing wrong results. Rename to `uvSourcePrisms` or strengthen KDoc before lifting `@ExperimentalIsometricApi` |
| **U-14** | architecture (A-KNOT-04) | `UvGenerator.kt:forKnotFace` | Magic constants `0..17`, `18`, `19` in `when` arms are disconnected from `sourcePrisms.size`. A future 4th sub-prism would silently misroute faces — derive boundary as `knot.sourcePrisms.size * 6` |
| **U-15** | architecture (A-KNOT-02) | `UvCoordProviderForShape.kt` (KDoc) | KDoc still refers to "extension points" / "not yet supported" but the dispatch is now terminal (every built-in shape covered). Stale KDoc; update to state the closed-dispatch decision explicitly |
| **U-16** | architecture (A-KNOT-03) | `UvGenerator.kt` (KDoc) | Bag-of-primitives delegation idiom (sub-prism → `forPrismFace`, custom quad → `quadBboxUvs`) is undocumented as a convention. A one-paragraph addition to `UvGenerator`'s class KDoc would prevent ad-hoc divergence in future compound shape slices |

### LOW (10)

| ID | Source(s) | File:Line | Issue |
|----|-----------|-----------|-------|
| **U-17** | correctness (CR-3) + testing (TS-4) | `UvGeneratorKnotTest.kt` | No position-invariance test (`Knot(Point(3, -2, 1.5))` vs `Knot(Point.ORIGIN)` should produce identical UVs) |
| **U-18** | testing (TS-5) | `PerFaceSharedApiTest.kt` | `CustomShape` fixture uses three identical `Point.ORIGIN` points (degenerate triangle); fragile if `Shape`/`Path` ever gains geometric validation |
| **U-19** | maintainability (MA-2) | `UvGenerator.kt`, `UvCoordProviderForShape.kt` | `@OptIn(ExperimentalIsometricApi::class)` granularity inconsistent — function-level on `forKnotFace`/`forAllKnotFaces`, file-level on `UvCoordProviderForShape`. Also redundant on `forAllKnotFaces` (only calls `forKnotFace` already opted in) |
| **U-20** | maintainability (MA-3) | `UvGenerator.kt:quadBboxUvs` | Function name `quadBboxUvs` doesn't communicate "axis-aligned bounding-box planar projection". Rename to `aabbPlanarProjectUvs` or promote inline block comment to KDoc |
| **U-21** | reliability (REL-K-05) | `UvGenerator.kt:forKnotFace` (KDoc) | KDoc doesn't cross-reference Knot's documented depth-sort caveat |
| **U-22** | api-contracts (API-05) | `ExperimentalIsometricApi` annotation | `@RequiresOptIn(level = WARNING)` not `ERROR`; callers can suppress without explicit opt-in. Pre-1.0 hardening |
| **U-23** | code-simp (CS-5) | `UvGeneratorKnotTest.kt` + `PerFaceSharedApiTest.kt` | Provider non-null assertion duplicated across both test files |
| **U-24** | security (SEC-02) | `UvGenerator.kt:372` | `require()` error message interpolates `knot.paths.size`. Internal-only context (UvGenerator is `internal object`), but `Knot` is `open` so a subclass with different path count could disclose its internal size via the message |
| **U-25** | architecture (A-KNOT-05) | `UvGenerator.kt:forKnotFace` | `@OptIn` + `@ExperimentalIsometricApi` two-annotation pattern is correct but undocumented; brief inline comment would prevent future single-annotation mistakes |
| **U-26** | architecture (A-KNOT-06) | `IsometricNode.kt:faceType` | Knot's intentional absence from `IsometricNode.faceType` dispatch is invisible — indistinguishable from accidental omission. One-line comment would make intention explicit |

### NIT (6)

| ID | Source(s) | File:Line | Issue |
|----|-----------|-----------|-------|
| **U-27** | correctness (CR-4) | `UvGenerator.kt:quadBboxUvs` (comment) | "Projects onto two largest-extent axes" is slightly imprecise for tie cases; comment could note the tie-breaking rule |
| **U-28** | code-simp (CS-6) | All `UvGenerator.forXFace` `require` messages | `require()` error-message template is verbatim-identical across all 6 sibling generators; a private helper would centralize. Also flagged in stairs review NIT |
| **U-29** | refactor-safety (RS-1) | `PerFaceSharedApiTest.kt` | `CustomShape` fixture doesn't call `provider.provide(...)`. Pre-existing coverage gap, not newly introduced by this slice |
| **U-30** | refactor-safety (RS-2) | `IsometricMaterialComposables.kt:57-59` (comment) | Inline comment still reads "currently everything except Prism" — factually stale. Optional 3-line fix |
| **U-31** | api-contracts (API-06) | `Knot.kt` (KDoc on `sourcePrisms`) | KDoc mentions faces 18-19 have no source Prism but doesn't explain they use post-transform bbox projection. One added sentence closes the gap |
| **U-32** | performance (P-7, ADVISORY) | `Knot.kt` (KDoc) | No `remember{}` documentation for Compose callers; amplifies the U-05 allocation cost if Knot is constructed outside `remember {}` |

**Total:** BLOCKER: 0 | HIGH: 5 | MED: 11 | LOW: 10 | NIT: 6
*(After dedup: 32 findings merged from 47 raw findings across 10 commands)*

## Triage Decisions

Per user direction (2026-04-20: "Defer all, proceed"), all 32 findings are marked deferred. Re-triage available via `/wf-review texture-material-shaders uv-generation-knot triage` to re-prompt for any subset.

| ID | Sev | Source(s) | Decision | Notes |
|----|-----|-----------|----------|-------|
| U-01 | HIGH | api-contracts | defer | Mutability contract |
| U-02 | HIGH | performance | defer | UV cache (Pyramid/Cylinder pattern) |
| U-03 | HIGH | reliability | defer | Wrap-rethrow consistency with siblings |
| U-04 | HIGH | code-simp | defer | quadBboxUvs Pair allocation hoist |
| U-05 | HIGH | 6 reviewers | defer | sourcePrisms ↔ createPaths drift; companion `CANONICAL_PRISMS` |
| U-06 | MED | 7 reviewers | defer | Dead `else` arm |
| U-07 | MED | code-simp + performance | defer | Single-pass bbox |
| U-08 | MED | reliability | defer | NaN/Infinity guard |
| U-09 | MED | testing | defer | forAllKnotFaces per-element check |
| U-10 | MED | testing | defer | Custom quad value check |
| U-11 | MED | correctness + testing | defer | localFaceIndex coverage |
| U-12 | MED | api-contracts | defer | perFace silent fallback warning |
| U-13 | MED | api-contracts | defer | sourcePrisms naming |
| U-14 | MED | architecture | defer | Magic 17/18/19 from sourcePrisms.size |
| U-15 | MED | architecture | defer | Stale "extension" KDoc |
| U-16 | MED | architecture | defer | Bag-of-primitives convention KDoc |
| U-17 — U-26 | LOW (10) | various | defer | All deferred |
| U-27 — U-32 | NIT (6) | various | defer | All deferred |

## Recommendations

### Must Fix (triaged "fix")
None this round — user opted to defer all and ship-with-caveats.

### Should Fix (MED triaged "fix")
None this round.

### Deferred (triaged "defer")

All 32 findings deferred. Recommended grouping for a future tightening slice:

**Group A — `sourcePrisms` redesign** (addresses U-01, U-05, U-13, U-14, U-31)
- Promote three Prism constants to `companion object val CANONICAL_PRISMS` (or `Knot.Companion.SOURCE_PRISMS`)
- Reference from both `sourcePrisms` (instance val) and `createPaths()` (eliminates drift surface, ~3 Prism + 36 Path allocations on Knot construction)
- Rename `sourcePrisms` → `uvSourcePrisms` and tighten KDoc (pre-transform space, faces 18-19 use bbox projection)
- Optionally wrap as `Collections.unmodifiableList(...)` to plug the mutable-cast vector
- Replace dispatch boundaries `0..17` / `18, 19` with `knot.uvSourcePrisms.size * 6` arithmetic (catches future 4th sub-prism)
- Estimated effort: 30-45 min

**Group B — `quadBboxUvs` hot-path tightening** (addresses U-04, U-07, U-08, U-20)
- Hoist axis-selection `when` outside the `for (i in 0..3)` loop (eliminates 4 `Pair` allocations per call)
- Replace 6× `minOf`/`maxOf` with single forward pass over 4 points
- Add NaN/Infinity guard or document that input must be finite
- Rename to `aabbPlanarProjectUvs` and promote block comment to KDoc with tie-breaking rule
- Estimated effort: 20-30 min

**Group C — `forKnotFace` cache + error consistency** (addresses U-02, U-03, U-06, U-21)
- Add identity-cache (single-slot `lastKnot` / `lastKnotUvs` matching Pyramid H-5 pattern)
- Wrap `when` body in try/catch and rethrow with Knot context (matches `forPrismFace` pattern)
- Remove dead `else` arm
- Add depth-sort caveat cross-reference to KDoc
- Estimated effort: 30-45 min

**Group D — Test hardening** (addresses U-09, U-10, U-11, U-17, U-18, U-29)
- Per-element `forAllKnotFaces` equivalence check
- Concrete value assertions for custom quads 18, 19 (will surface any U-10 degenerate)
- Parameterize delegation tests over `localFaceIndex ∈ {0, 1, 2, 3, 4, 5}` for each sub-prism
- Position-invariance test (`Knot(Point.ORIGIN)` vs translated)
- Replace degenerate `CustomShape` triangle with realistic non-Knot fixture
- Add `provider.provide(...)` call to `CustomShape` test
- Estimated effort: 45-60 min

**Group E — Doc + style polish** (addresses U-12, U-15, U-16, U-19, U-22, U-23, U-24, U-25, U-26, U-27, U-28, U-30, U-32)
- KDoc updates (perFace warning, terminal extension, bag-of-primitives convention, depth-sort xref, sourcePrisms post-transform note, two-annotation pattern, IsometricNode dispatch intent, remember{} note)
- `@OptIn` granularity normalization
- Stale comment cleanup
- Optional `private fun forFaceRequireMessage(...)` helper extraction
- Optional `RequiresOptIn` level upgrade WARNING → ERROR (pre-1.0 hardening)
- Estimated effort: 30-60 min, mostly documentation

### Dismissed
None.

### Consider (LOW/NIT — not triaged)
All 16 LOW + NIT items are folded into Group D + Group E above for any future tightening slice.

## Recommended Next Stage

- **Option A (recommended):** `/wf-handoff texture-material-shaders` — slice ships with caveats, all sibling slices on `feat/texture` are also at handoff/ship-with-caveats status; the workflow is ready for PR consolidation. Dedicated branch already has PR #8 open.
- **Option B:** `/wf-extend texture-material-shaders from-review` — open new follow-on slice(s) for Groups A-E above. Recommended sequencing: A (sourcePrisms redesign) → B (quadBboxUvs hot path) → C (forKnotFace cache) → D (test hardening) → E (doc polish). Group A has the highest payoff.
- **Option C:** `/wf-implement texture-material-shaders uv-generation-knot` — fix HIGH findings now in this slice rather than deferring. Not recommended given user's explicit defer-all decision and the sibling-slice precedent of folding fixes into downstream slices.
- **Option D:** `/wf-amend texture-material-shaders from-review` — **not applicable**. Findings critique implementation choices, not slice spec/ACs. AC1-AC5 all met; spec was correct.
- **Option E:** `/wf-ship texture-material-shaders` — skip handoff and proceed directly to publish. Acceptable only if PR #8 is already merge-ready and reviewers' triage is complete.

Consider `/compact` before advancing — review-dispatch chatter (10 sub-agent outputs, deduplication scratch, triage prompt) is noise for handoff. PreCompact hook will preserve workflow state; triage decisions are persisted in this file.
