---
schema: sdlc/v1
type: review
review-command: docs
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-28T00:00:00Z"
updated-at: "2026-04-28T00:00:00Z"
result: pass-with-findings
metric-findings-total: 10
metric-findings-blocker: 0
metric-findings-high: 2
metric-findings-med: 2
metric-findings-low: 3
metric-findings-nit: 3
tags:
  - docs
  - kdoc
  - depth-sort
  - changelog
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
---

# Review: docs

Scope: cumulative diff `97416ba..HEAD` on `feat/ws10-interaction-props`.
Covers four commits: `3e811aa`, `9cef055`, `2e29dc5`, `452b1fc`.
Files reviewed:
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`
- Four scene factories under `isometric-compose/src/test/kotlin/.../scenes/`
- Git log for commit message changelog quality
- `docs/internal/explanations/` (existence check)

---

## Findings

| ID    | Severity | Location                                              | Summary                                                                                                           |
|-------|----------|-------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| DC-1  | HIGH     | `Path.kt:118–124` — KDoc step numbering               | Cascade lists 6 steps (1-6) but the code has 7 labelled points; step 4 in KDoc is a comment only, not a test step |
| DC-2  | HIGH     | `Path.kt:119-121` — KDoc steps 4 & 5                 | KDoc numbers plane-side forward as step 4 and reverse as step 5; code comments label them step 5 and step 6 — off-by-one mismatch leaks to readers |
| DC-3  | MEDIUM   | `DepthSorter.kt:140` — inline comment                | Workflow slug `depth-sort-shared-edge-overpaint` in a public-facing comment violates memory rule against internal-process vocabulary in source |
| DC-4  | MEDIUM   | `OnClickRowScene.kt:19` — KDoc                       | "WS10" token in KDoc violates memory rule; scene factories ship in the test artifact but KDoc is visible in IDE and Dokka |
| DC-5  | LOW      | `LongPressGridScene.kt:19` — KDoc                    | "amendment 1" token in KDoc is internal workflow vocabulary; forbidden per memory rule |
| DC-6  | LOW      | `Path.kt:100–143` — KDoc sign convention             | Sign convention is documented (negative = closer, positive = farther), but the inverted relationship to the original plan is not noted anywhere in KDoc or inline |
| DC-7  | LOW      | `Path.kt:100–143` — reference link rot               | Citation "Newell, M. E., Newell, R. G., Sancha, T. L., 1972" has no URL; no `@see` or stable hyperlink; maintainers cannot verify the algorithm |
| DC-8  | NIT      | `Path.kt:213–214` — step 3 sign comment              | The comment "Self entirely-below pathA on screen → self farther → +1" is counterintuitive and needs one more sentence justifying why lower screen-y implies farther in iso |
| DC-9  | NIT      | `docs/internal/explanations/`                        | `depth-sort-painter-pipeline.md` does not exist; amendment-2 plan said DEFERRED; no tracking task found anywhere |
| DC-10 | NIT      | `2e29dc5` commit message body                        | Body references `.ai/workflows/` paths and "round3-longpress-diag.log" — internal process vocabulary in a permanent git commit body |

---

## Detailed Findings

### DC-1 — Step count mismatch between KDoc and code (HIGH)

**Location:** `Path.kt` KDoc lines 113–125 vs inline comments in `closerThan`.

The KDoc enumerates 6 numbered steps:

```
1. Iso-depth (Z) extent
2. Screen-x extent
3. Screen-y extent
4. Plane-side forward
5. Plane-side reverse
6. Polygon split — DEFERRED
```

The code's inline comments label the iso-depth block "Step 1", the screen extent block
"Steps 2 / 3", then use "Step 4" as an explanatory comment block (the `DepthSorter`
gate note at line 216–221), and continue with "Step 5: plane-side forward" (line 222),
"Step 6: plane-side reverse" (line 228), and "Step 7 deferred: polygon split" (line 233).

So the code has 7 numbered steps internally (1, 2/3, 4-comment, 5, 6, 7), while the KDoc
promises 6. Readers cross-referencing the KDoc enumeration with the inline comments will
find the forward plane-side test labelled as both "step 4" (KDoc) and "step 5" (inline
comment), and the polygon-split labelled both "step 6" (KDoc) and "step 7" (inline
comment). The KDoc's step 4 (plane-side forward) does not correspond to any action in the
code — the inline "Step 4" comment at line 219 is an explanatory note about the gate, not
a cascade step.

**Impact:** Any maintainer adding a new cascade step, or attempting to understand which
inline comment corresponds to which KDoc bullet, will be confused. The discrepancy is
directly in the public KDoc for a function that Dokka will render on Maven Central.

**Recommended fix:** Either make the inline step numbers match the KDoc (relabel inline
"Step 5" as "Step 4", "Step 6" as "Step 5", "Step 7" as "Step 6" — and convert the current
inline "Step 4" from a step-label into an explanatory prose comment without a step number),
or rewrite the KDoc to enumerate 7 items matching the inline labels. The gate note at the
current inline "Step 4" is not a cascade step and should not carry a step number.

**Severity: HIGH.** Public KDoc on a `fun` that ships to Maven Central; the discrepancy
will actively mislead any maintainer trying to understand or extend the algorithm.

---

### DC-2 — Plane-side step numbers off by one in KDoc vs code (HIGH)

This is the concrete consequence of DC-1, separated for traceability.

| Location            | Plane-side forward | Plane-side reverse | Polygon split |
|---------------------|--------------------|--------------------|---------------|
| KDoc (public)       | step 4             | step 5             | step 6        |
| Inline comment      | step 5             | step 6             | step 7        |

A developer reading Dokka output will see "step 4: plane-side forward" and search the
source for a "Step 4" comment. They will find the gate explanation block, not the
plane-side code. The actual plane-side forward code is labelled "Step 5" inline.

**Recommended fix:** Fix inline step labels to match the KDoc (preferred: KDoc is the
canonical reference), or rewrite both to be consistent. See DC-1.

**Severity: HIGH** (same root cause as DC-1, but distinct observational surface).

---

### DC-3 — Workflow slug in production source code comment (MEDIUM)

**Location:** `DepthSorter.kt` line 140:
```
// See workflow `depth-sort-shared-edge-overpaint` for the full diagnosis.
```

This is an inline comment inside `checkDepthDependency`, which is `private` in an
`internal object`, so it does not render in Dokka. However, the memory rule
("Workflow vocabulary (slice, extension round, wf-*) must not appear in PR bodies,
commit messages, or **public docs**") covers source code comments that are part of the
shipped artifact. The `.ai/workflows/` slug is unintelligible to any external contributor
or library consumer reading the source; the path it references does not exist in the
published artifact.

**Recommended fix:** Replace with prose:
```
// See docs/internal/explanations/depth-sort-painter-pipeline.md for the full
// diagnosis. Short version: hasInteriorIntersection gates edge insertion so that
// face pairs touching only at a shared screen-space edge or vertex do not generate
// spurious dependency edges in the topological sort.
```
(If `depth-sort-painter-pipeline.md` does not yet exist, the inline comment should be
self-contained prose rather than a dangling reference.)

**Severity: MEDIUM.** The comment is inside a `private internal` function, so it does
not appear in published API docs. The memory rule violation is real; the practical harm
is limited to source-level readers.

---

### DC-4 — "WS10" token in scene factory KDoc (MEDIUM)

**Location:** `OnClickRowScene.kt` line 19:
```
 * The original-scope WS10 fix in commit `3e811aa` resolved depth-sort for
```

And `WS10NodeIdScene.kt` line 18 (function name comment):
```
 * Use inside an `IsometricScene { WS10NodeIdScene() }` block.
```
(The function is also named `WS10NodeIdScene` — the "WS10" is baked into the public
function name itself.)

"WS10" is internal workflow shorthand. It appears in:
- The KDoc body of `OnClickRowScene` (line 19) — directly forbidden by the memory rule.
- The function name `WS10NodeIdScene` — this is a public function signature; it ships in
  the `:isometric-compose` test artifact and appears in Dokka. The name leaks the
  internal workflow numbering scheme to any downstream user reading the API.

**Recommended fix for KDoc body:** Replace "WS10 fix" with a plain description:
```
 * The fix in commit `3e811aa` resolved a depth-sort shared-edge overpaint regression for
```

**Recommended fix for function name:** Rename `WS10NodeIdScene` to `NodeIdSampleScene`
(or `InteractionNodeIdScene`) — a name that conveys what the scene contains, not the
internal ticket that motivated it. This is a **public API rename** and should be evaluated
against api-design-guideline §4 (Naming). Since it is a test-only composable, the blast
radius is contained to test files.

**Severity: MEDIUM.** "WS10" in the function name will appear in published test artifacts
and Dokka; it leaks internal vocabulary to library consumers. The KDoc body reference is
additionally forbidden by the explicit memory rule.

---

### DC-5 — "amendment 1" token in LongPressGridScene KDoc (LOW)

**Location:** `LongPressGridScene.kt` line 19:
```
 * regression case for amendment 1: the over-aggressive topological-edge
```

"amendment 1" is internal workflow vocabulary for the scope-extension round. It has no
meaning to a library consumer or external contributor.

**Recommended fix:** Replace with the self-contained description:
```
 * regression case for the screen-overlap gate fix: the over-aggressive topological-edge
```

**Severity: LOW.** In a test artifact; not rendered in the main public API Dokka.
Violates the memory rule but with limited external exposure.

---

### DC-6 — Sign convention inverted from original plan; inversion undocumented (LOW)

**Location:** `Path.kt` KDoc lines 105–109.

The KDoc documents the current sign convention correctly:
```
- **negative** — `this` is closer than [pathA] (so `this` paints AFTER [pathA]).
- **positive** — `this` is farther than [pathA] (so `this` paints BEFORE [pathA]).
```

The implementer note flagged that this is **inverted from the original plan**
(the plan described positive = closer, negative = farther). The KDoc documents the
current (correct) convention, but does not mention:
1. That the sign was intentionally inverted relative to an earlier spec.
2. Why negative = closer (rather than positive = closer, which might seem more
   natural since "closer" is "better" / "draw last").

A future maintainer changing the caller in `DepthSorter.checkDepthDependency` might
flip the sign by accident, believing they are "correcting" it to match an intuition or
an older comment.

**Recommended fix:** Add one sentence anchoring the sign to the concrete call-site
semantics:
```
 * Sign matches [DepthSorter]'s caller convention: negative causes `drawBefore[i].add(j)`
 * (this drawn after pathA), positive causes `drawBefore[j].add(i)` (this drawn before).
```

**Severity: LOW.** The KDoc is accurate; the missing context is "why this sign".

---

### DC-7 — Newell 1972 citation has no URL; link may rot (LOW)

**Location:** `Path.kt` KDoc line 112:
```
 * (Newell, M. E., Newell, R. G., Sancha, T. L., 1972, "A solution to the hidden
 * surface problem"),
```

The citation is bibliographically correct but includes no URL, DOI, or `@see` tag.
The 1972 paper is not freely available via a canonical stable URL (it is in the AFIPS
conference proceedings). However, the algorithm as described in the KDoc is well
covered by stable secondary sources (e.g., the relevant Wikipedia "Painter's algorithm"
article, or Foley et al. *Computer Graphics: Principles and Practice*).

Without any link, a maintainer who wants to verify a cascade step cannot follow a
reference from within the IDE tooltip or Dokka page.

**Recommended fix:** Add a `@see` tag pointing to the Wikipedia article as a stable
secondary reference (Wikipedia's "Painter's algorithm" article describes the Newell
cascade and will remain stable):
```
 * @see <a href="https://en.wikipedia.org/wiki/Painter%27s_algorithm">Painter's algorithm (Wikipedia)</a>
```

**Severity: LOW.** Not wrong; just an improvement for maintainer usability.

---

### DC-8 — Step 3 sign comment incomplete for screen-y (NIT)

**Location:** `Path.kt` lines 209–214:
```kotlin
// Step 3: screen-y. screenY = -(sin*(x+y) + z). Smaller (more negative)
// screen-y means lower on screen — in iso this dominantly tracks
// "deeper into the scene" (larger world (x+y) → larger iso depth →
// farther). Self entirely-below pathA on screen → self farther → +1.
if (selfScreenYMax < aScreenYMin - EPSILON) return 1
if (aScreenYMax < selfScreenYMin - EPSILON) return -1
```

The comment correctly explains the `return 1` branch (self below pathA → farther → +1).
It does not explain the `return -1` branch: `aScreenYMax < selfScreenYMin - EPSILON`
means pathA is entirely below self on screen, so pathA is farther and self is closer →
`return -1`. The asymmetry is internally consistent (the code is correct) but a reader
must mentally derive the second branch from the first. Given that step 2 (screen-x) uses
the conventional "larger screen-x → farther" direction and step 3 inverts it, an explicit
comment on both branches would prevent off-by-one sign errors in future edits.

**Recommended fix:** Add one comment line:
```kotlin
if (aScreenYMax < selfScreenYMin - EPSILON) return -1  // pathA below self → self closer
```

**Severity: NIT.**

---

### DC-9 — `depth-sort-painter-pipeline.md` does not exist; no tracking task (NIT)

**Location:** `docs/internal/explanations/` — directory does not exist.

Amendment-2 plan explicitly stated this file was "DEFERRED for the ship-stage handoff."
No tracking task was found in any `.ai/` workflow file, `00-index.md`, or issue tracker.
The deferral itself is acceptable (the KDoc-only coverage is sufficient for ship), but
the lack of a tracking task means the deferral may be permanently forgotten.

DC-3 specifically flags the dangling `depth-sort-shared-edge-overpaint` workflow slug
in `DepthSorter.kt`. Once that comment is fixed (per DC-3), the motivation for the
explanation doc weakens further. If DC-3 is resolved by replacing the slug with
self-contained prose, this NIT can be closed as "won't do."

**Severity: NIT.** No immediate harm; the deferral is documented in the amendment-2 plan.
Recommend either creating a tracking task or explicitly closing the item in `00-index.md`.

---

### DC-10 — Internal path references in `2e29dc5` commit message body (NIT)

**Location:** Commit `2e29dc5` body (diagnostic-only commit, subsequently reverted by `452b1fc`):
```
Captured evidence and full mechanism analysis live in
.ai/workflows/depth-sort-shared-edge-overpaint/05-implement-*.md and
.ai/workflows/depth-sort-shared-edge-overpaint/verify-evidence/round3-longpress-diag.log.
```

The memory rule forbids workflow vocabulary from appearing in commit messages. This
commit body contains both the workflow slug (`depth-sort-shared-edge-overpaint`) and an
internal path. The commit is already in the permanent git history; it cannot be changed
without rewriting history. The commit is also a `chore:` diagnostic commit, so git-cliff
will render it in the "Miscellaneous" section of the changelog:

```
### Miscellaneous
- **depth-sort**: Re-enable DEPTH_SORT_DIAG logging for over-paint investigation
```

The subject line itself is fine for changelog purposes. Only the body leaks internal
vocabulary — and git-cliff's `body` template field is not rendered by the default
`cliff.toml` configuration (only `commit.message` from the subject is used in the
template), so the body will not appear in the auto-generated CHANGELOG.

**Impact:** The body is in the permanent record; it will appear in `git log` output
and on GitHub's commit page. It cannot be fixed retroactively.

**Severity: NIT.** The CHANGELOG output is unaffected. The rule violation is in an
already-landed commit and is not actionable going forward; flag for awareness so future
diagnostic commits avoid the same pattern.

---

## KDoc Accuracy: `closerThan` 6-step cascade

The KDoc accurately describes the high-level algorithm intention. Specific accuracy
assessments:

| Step | KDoc description | Code behavior | Accurate? |
|------|-----------------|---------------|-----------|
| 1. Iso-depth extent | "smaller depth range is unambiguously closer" | `selfDepthMax < aDepthMin - EPSILON → -1` | Yes — but "smaller depth range" is imprecise; it should say "smaller-max depth extent" |
| 2. Screen-x extent | "same test, projected to iso screen-x" | `selfScreenXMax < aScreenXMin - EPSILON → -1` | Yes |
| 3. Screen-y extent | "same test, projected to iso screen-y" | sign inverted vs step 2 (see DC-8) | Partly — the sign inversion vs step 2 is unexplained |
| 4. Plane-side forward | KDoc calls this step 4; code calls it step 5 | see DC-1/DC-2 | Off-by-one in numbering |
| 5. Plane-side reverse | KDoc calls this step 5; code calls it step 6 | see DC-1/DC-2 | Off-by-one |
| 6. Polygon split | "DEFERRED; cycle remnants absorbed by Kahn" | `return 0` | Yes |

Sign convention in KDoc: **negative = this closer, positive = this farther, zero = ambiguous** — matches implementation. Correctly documented.

The "three superseded failure modes" catalogue in the "Why not the older approach?" block
is accurate and matches the commit log history. No factual errors found in that section.

---

## `signOfPlaneSide` KDoc accuracy

The KDoc on `signOfPlaneSide` (lines 239–255) is accurate and well-written. One
observation: the KDoc says "Vertices exactly on the plane (within [EPSILON]) count as
'same side' for this purpose." The code uses `signed = observerPosition * pPosition`
with `signed > EPSILON` for same-side. When `pPosition` is near-zero (vertex near
the plane), the product can be below `EPSILON` even if `pPosition` and `observerPosition`
have the same sign — so near-plane vertices are treated as neither same-side nor
opposite-side (they fall into the `else → 0` path). The KDoc claim that on-plane vertices
"count as same side" is inaccurate for the epsilon check: they actually fall through to
ambiguous. This is a minor inaccuracy but worth noting.

---

## `hasInteriorIntersection` KDoc accuracy

The KDoc on `hasInteriorIntersection` is accurate and complete. The three-step algorithm
(AABB, strict edge-crossing, strict-inside fallback) matches the implementation.
The `1e-6` band explanation is correct. No inaccuracies found.

---

## Scene factory KDoc adequacy

| File | "If sample changes, update here" note | Workflow vocab leak | Adequacy |
|------|--------------------------------------|---------------------|----------|
| `WS10NodeIdScene.kt` | No explicit note | "WS10" in function name and KDoc (DC-4) | Insufficient — function name leaks internal vocab |
| `OnClickRowScene.kt` | Yes (line 26) | "WS10" in KDoc body (DC-4) | Adequate except for vocab leak |
| `LongPressGridScene.kt` | Yes (line 25) | "amendment 1" (DC-5) | Adequate except for vocab leak |
| `AlphaSampleScene.kt` | Yes (line 26) | None found | Adequate |

The "If the sample changes, update here to match" instruction is present on three of the
four factories. `WS10NodeIdScene.kt` lacks this note — it should be added for consistency.

---

## CHANGELOG / git-cliff quality

git-cliff config (`cliff.toml`) renders only `commit.message` (the subject line) in the
changelog body. Commit body text is not rendered. Evaluation of the four subjects:

| Commit | Subject | git-cliff group | Changelog entry quality |
|--------|---------|----------------|------------------------|
| `3e811aa` | `fix(depth-sort): permissive countCloserThan threshold + 1e-6 epsilon` | Bug Fixes | Adequate — scope and summary are clear |
| `9cef055` | `fix(depth-sort): screen-overlap gate for 3x3 grid edge-cases` | Bug Fixes | Adequate — "3x3 grid edge-cases" is specific enough |
| `2e29dc5` | `chore(depth-sort): re-enable DEPTH_SORT_DIAG logging for over-paint investigation` | Miscellaneous | Problematic — a diagnostic-only chore that was immediately reverted by 452b1fc will appear as a standalone changelog entry. It was not reverted via `revert:` commit type, so git-cliff will not suppress it. A changelog reader will see a "re-enable" entry with no corresponding "disable" entry, and the entry references "over-paint investigation" without context. |
| `452b1fc` | `fix(depth-sort): replace closerThan with Newell Z->X->Y minimax cascade` | Bug Fixes | Good — precise and descriptive |

**Actionable concern:** Commit `2e29dc5` will appear in the CHANGELOG as a standalone
"Miscellaneous" entry for re-enabling a diagnostic logger. Since the revert was
implemented by a new commit (`452b1fc`) rather than a `revert:` conventional commit,
git-cliff will render both:
- `chore(depth-sort): re-enable DEPTH_SORT_DIAG logging for over-paint investigation`
- `fix(depth-sort): replace closerThan with Newell Z->X->Y minimax cascade`

...as separate changelog entries with no indication that the first was a temporary
diagnostic step. For a library consumer, the "re-enable" entry is noise and implies the
logger is now on in the shipped code (it is not). This is not fixable without history
rewrite; flag for awareness so future diagnostic commits use a `chore(internal):` scope
or an explicit revert commit if the diagnostic is reverted.

---

## API design guideline compliance (§4 Naming, §5 Readability)

**§4 Naming:** New internal helpers (`signOfPlaneSide`, `EPSILON`, `ISO_ANGLE`,
`EDGE_BAND`) are `private` or `private companion object` — they do not constitute
public API surface. Names express intent clearly: `signOfPlaneSide` describes what it
returns, `ISO_ANGLE` and `EDGE_BAND` are self-explanatory constants.

`WS10NodeIdScene` violates §4 for a slightly different reason: the name does not express
user intent — it expresses internal ticket provenance. Rename to `NodeIdSampleScene`
or `InteractionNodeIdScene` (see DC-4).

`hasInteriorIntersection` (public on `IntersectionUtils`) names correctly per §4:
it describes the predicate's distinguishing property ("interior") over the existing
`hasIntersection`.

**§5 Readability:** Call sites in `DepthSorter.checkDepthDependency` are clean:
`IntersectionUtils.hasInteriorIntersection(...)` reads as a clear upgrade from
`hasIntersection`. `path.closerThan(pathA, observer)` is unchanged.

No §4/§5 violations found in new internal helpers (other than the WS10 naming noted
above).

---

## Summary

The Newell cascade KDoc on `closerThan` is well-written and historically useful, but has
two HIGH-severity structural defects: the step numbering in the public KDoc (6 steps)
disagrees with the inline comment numbering (7 labelled points), creating an off-by-one
that will actively mislead maintainers cross-referencing the two. Two MEDIUM findings
cover forbidden workflow vocabulary — the `depth-sort-shared-edge-overpaint` workflow
slug in `DepthSorter.kt` and the "WS10" token in scene factory KDocs and function names —
both of which violate the memory rule against internal-process language in source. Three
LOW findings cover the undocumented sign-convention inversion, the unlinked Newell 1972
citation, and residual vocabulary ("amendment 1") in test KDocs. Three NITs cover a
minor screen-y sign explanation gap, the missing explanation doc with no tracking task,
and an already-landed commit body that contains workflow paths.

**Result: PASS with findings.** No blockers. The two HIGH findings (DC-1/DC-2) should
be resolved before the PR is merged; they are a single coordinated fix to align the
inline step labels with the KDoc enumeration. The two MEDIUM findings (DC-3/DC-4) should
be resolved before merge per the memory rule. The remaining six findings are judgment
calls.

### Recommended fixes in priority order

1. **DC-1 + DC-2** — Align inline `// Step N` comments with KDoc step enumeration:
   remove the step-number label from the current inline "Step 4" gate comment (convert
   to plain prose), and renumber "Step 5 / 6 / 7" inline to "Step 4 / 5 / 6".
2. **DC-3** — Replace `// See workflow \`depth-sort-shared-edge-overpaint\`` with
   self-contained prose in `DepthSorter.kt`.
3. **DC-4** — Remove "WS10" from `OnClickRowScene.kt` KDoc body; evaluate renaming
   `WS10NodeIdScene` to `NodeIdSampleScene`.
4. **DC-5** — Replace "amendment 1" with plain description in `LongPressGridScene.kt`.
5. **DC-6** — Add one sentence anchoring sign convention to `DepthSorter` call-site.
6. **DC-7** — Add `@see` Wikipedia link for Newell 1972.
7. **DC-8** — Add missing comment on `return -1` branch of step 3 screen-y.
8. **DC-9** — Either create `depth-sort-painter-pipeline.md` or close the deferral
   explicitly in `00-index.md`.
9. **DC-10** — Awareness only; not actionable retroactively.
