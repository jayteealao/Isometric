# Benchmark Report Design Guide

Date: 2026-03-13
Status: Working standard for Phase 3 branch result reports

## Purpose
This document defines how the benchmark result report is written, structured, and maintained.

The report is a single HTML file that covers all five Phase 3 optimization branches against a shared baseline. It is not five separate reports. Each branch gets its own section within the unified report, and pending branches show a placeholder until their benchmark run completes.

The report helps a decision-maker answer three questions quickly and accurately:

1. What was the baseline bottleneck?
2. What changed in each branch, and where?
3. Which branches should stay in contention, be tuned further, or be dropped?

## Audience
Primary audience:
- senior engineer reviewing optimization branches
- product or technical lead deciding which branches are worth carrying forward

Secondary audience:
- future implementer trying to understand why a branch was accepted or rejected
- anyone rerunning the benchmark on a later device or harness version

## Core principles

### 1. Lead with the decision, not the appendix
The first screen should tell the reader:
- which branches have been tested and which are pending
- whether each tested branch won or lost overall
- where it won
- where it regressed
- what still needs follow-up

Do not start with a giant table.

### 2. Start from a shared baseline diagnosis
The baseline profile section appears once and applies to all branches. Every branch section assumes the reader has seen the baseline diagnosis.

Without a baseline diagnosis, readers cannot judge whether a branch moved the meaningful bottleneck or just changed a secondary metric.

### 3. Organize around the reader's questions
The report follows this structure:

1. **Hero** — report title, metadata, branch count summary
2. **Executive dashboard** — branch status chips, headline scorecards
3. **Baseline profile** — cost breakdown, scaling, bottleneck analysis
4. **Branch sections** (one per branch, in this order):
   - Prepared scene cache
   - Spatial index
   - Broad-phase sort
   - Path caching
   - Native canvas
5. **Methodology and artifacts** — comparison design, reading guide, CSV paths

Each branch section that has data follows an internal structure:
1. methodological caveat (if any)
2. frame time delta waterfall (all 24 scenarios, sorted)
3. heatmap (scene size × interaction pattern)
4. analysis by interaction pattern (baseline vs branch bars)
5. analysis by scene size (baseline vs branch bars)
6. subsystem impact (prepare/draw/hit-test deltas per interaction)
7. memory and GC tradeoff (scatter plot)
8. biggest wins table
9. biggest regressions table
10. full matrix comparison table
11. recommendation callout

Pending branches show a placeholder card with the expected signal.

### 4. Use purpose-built inline SVG charts
All data visualization is implemented as inline SVG, generated from embedded JSON data by JavaScript at page load. This keeps the report self-contained (single HTML file, no external dependencies) and ensures charts are interactive (tooltips on hover) and print-friendly.

Chart heights are computed dynamically from the data, never hardcoded, so the layout adapts if the matrix changes.

Every chart must have:
- a legend showing what colors mean
- nearby explanatory text describing the takeaway
- interactive tooltips on hover for exact values
- semantic color encoding: green for improvement, red for regression

### 5. Make the report understandable without the visuals
For every chart, the report must still be understandable as HTML text.

Requirements:
- use semantic HTML tables for raw comparisons (wins, regressions, full matrix)
- keep headings descriptive
- ensure charts have nearby explanatory text
- avoid encoding the message in color alone — always include numeric labels on bars
- prefer HTML structure over screenshots

### 6. Tell the reader what to trust most
The methodology section contains a reading guide. For this benchmark suite, the default ranking is:
1. `avgFrameMs` for product-visible outcome
2. `avgPrepareMs` and `avgHitTestMs` for subsystem diagnosis
3. `allocatedMB` and `gcInvocations` for cost shifting
4. `p95` and `p99` when tail behavior is the question

## Report file and data architecture

### Single report file
The report lives at:
- `docs/reviews/2026-03-11-phase2-baseline-results.html`

It is a self-contained HTML file with embedded CSS, JSON data, and JavaScript. No external dependencies except Google Fonts (graceful degradation to system fonts if offline).

### Embedded data
Benchmark data is embedded as JavaScript arrays near the top of the `<script>` block:
- `baseline` — array of 24 scenario objects from the baseline CSV
- `branch` — array of 24 scenario objects from the current branch CSV (one per completed branch; expand to named objects like `branchPreparedSceneCache`, `branchSpatialIndex`, etc. as branches are added)

Each scenario object contains: `name`, `size`, `mr`, `ip`, `prepare`, `draw`, `frame`, `hit`, `alloc`, `gc`, `cache`.

### Design system
The report uses CSS custom properties for consistent theming:

Typography:
- `DM Serif Display` — display/heading font (editorial weight)
- `JetBrains Mono` — data, labels, monospace (engineering precision)
- `Source Serif 4` — body text (reading comfort)

Color semantics:
- `--green` (#34d399) — improvement / net win
- `--red` (#f87171) — regression / net loss
- `--amber` (#fbbf24) — caution / caveat
- `--accent` (#6c9bff) — neutral emphasis / links
- `--purple` (#a78bfa) — secondary data (draw subsystem)
- `--cyan` (#22d3ee) — tertiary data (hit-test subsystem)

Background: dark theme (`--bg: #0c0e12`), surfaces at progressively lighter values.

## Required report sections

### Hero
Must include:
- report title and phase label
- one-line subtitle describing the report scope
- metadata pills: device, Android version, baseline run id, branch count, matrix size

### Executive dashboard
Must include:
- branch status bar — one chip per branch showing name and status (net win / mixed / pending)
- headline scorecards: average frame delta, biggest win, worst regression, win rate, decision

### Baseline profile
Must include:
- self-test health
- best and worst frame time scorecards
- cost breakdown chart (stacked horizontal bars: prepare + draw + hit-test by scenario)
- frame time scaling chart (line chart across scene sizes by interaction pattern)
- three-card summary: dominant cost, secondary cost, what is not the bottleneck

### Branch section (per completed branch)
Must include:
- section number, title, one-line description of the optimization
- methodological caveat callout (if applicable)
- six SVG charts (waterfall, heatmap, by-interaction, by-size, subsystem, tradeoff)
- biggest wins and regressions tables
- full matrix comparison table
- recommendation callout with explicit outcome

### Branch section (pending)
Must include:
- section number, title, one-line description
- placeholder card stating the benchmark has not been run
- expected signal line (e.g. "Expected signal: hit-test delta dominates frame improvement")

### Methodology and artifacts
Must include:
- comparison design (what changed, what stayed the same)
- reading guide (metric priority)
- artifact references (CSV paths, report design guide path)

## Visualization catalog

### Scorecards
Used for: headline numbers that the reader should absorb immediately.
Appear in: executive dashboard, baseline profile.
Each scorecard has: label, value (color-coded), explanatory note, top accent stripe.

### Stacked horizontal bars
Used for: baseline cost breakdown by scenario.
Shows: prepare + draw + hit-test per row, grouped by scene size × interaction pattern.
Legend required.

### Line chart
Used for: baseline frame time scaling across scene sizes.
Shows: one line per interaction pattern (NONE, OCCASIONAL, CONTINUOUS).
Grid lines at 50ms intervals. Legend required.

### Delta waterfall
Used for: frame time change per scenario in a branch section.
Shows: all 24 scenarios sorted by delta, centered on zero. Green bars left (improvement), red bars right (regression). Numeric labels on every bar.

### Heatmap
Used for: frame time delta summarized as scene size × interaction pattern.
Shows: 4×3 grid, cells color-coded by magnitude and direction. Averaged across mutation rates.
Row headers: N=10, N=50, N=100, N=200. Column headers: NONE, OCCASIONAL, CONTINUOUS.

### Paired horizontal bars
Used for: baseline vs branch comparison grouped by interaction pattern or scene size.
Shows: gray baseline bar and colored branch bar side by side, with delta annotation.
Legend required (Baseline / Branch).

### Centered delta bars (subsystem)
Used for: subsystem-level deltas (prepare, draw, hit-test) per interaction pattern.
Shows: bars centered on zero, one group per interaction pattern, one bar per subsystem.
Axis labels: "← faster" (left), "slower →" (right). Legend required.

### Scatter plot
Used for: memory/GC tradeoff visualization.
Shows: one point per scenario, x = frame delta, y = allocation delta. Point size encodes scene size. Quadrant backgrounds color-coded (green = faster + less mem, red = slower + more mem).
Size legend required.

### Data tables
Used for: scenario-level detail (wins, regressions, full matrix).
Sticky headers, monospace font, color-coded delta cells. Scrollable when the full matrix is shown.

## Branch-specific guidance

### Prepared-scene cache
Emphasize:
- baseline prepare cost
- whether cache hits appear in matrix rows
- large-scene steady-state improvement
- stable-scene probe still required (callout)

### Spatial index
Emphasize:
- continuous hit-test cost in baseline
- whether hit-test deltas dominate frame improvement
- any prepare-cost regression from index construction

### Broad-phase sort
Emphasize:
- prepare-time savings during sort-heavy rows
- whether order correctness remained intact
- whether gains appear mainly at larger scene sizes

### Path caching
Emphasize:
- draw-time movement versus allocation and GC tradeoff
- whether standalone path caching is worthwhile without prepared-scene reuse
- prepare-time increase from path cache build is expected

### Native canvas
Emphasize:
- draw-time movement first
- whether frame-time follows draw-time changes
- whether profiling is needed when timing is ambiguous

## Standard decision language
Use one of these outcomes in each branch recommendation callout:
- Net win, keep in contention
- Mixed result, tune specific regressions
- Combo-branch candidate, not a strong standalone win
- Not worth carrying forward

## Follow-on workflow
When a new branch benchmark run is completed:

1. Parse the branch summary CSV and add the data as a new JavaScript array in the `<script>` block
2. Replace the pending placeholder for that branch with the full chart and table section
3. Copy the chart generation code pattern from an existing branch section, updating variable references
4. Update the executive dashboard: change the branch chip from pending to its result, add a scorecard
5. Update the hero metadata pills (branch count)
6. Preserve the baseline profile — it does not change between branch additions
7. Keep artifact references exact

Do not create a separate report file per branch. All branches live in the same report.

## Chart implementation rules

### Dynamic sizing
All SVG chart heights must be computed from the data. Use formulas like:
```
H = padT + items * (barH * 2 + gapInner + gapOuter) - gapOuter + padB
```
Never hardcode SVG height values.

### Tooltips
All interactive chart elements (bars, circles, cells) must show a tooltip on mousemove with:
- scenario or group label
- exact numeric value
- delta when comparing baseline to branch

### Legends
Every chart that uses color to distinguish categories must have a legend above the chart area. Legends use the `.legend` / `.legend-item` / `.legend-swatch` CSS classes.

### Accessibility
- Numeric labels on bars ensure values are readable without hover
- Color is never the sole indicator — text always accompanies color-coded values
- Tables use semantic `<th>` headers with sticky positioning
- Delta cells use CSS classes (`good`, `bad`) in addition to color

## Source guidance
- Atlassian executive summary guidance:
  - https://www.atlassian.com/software/confluence/templates/executive-summary
- Google style guide for tables:
  - https://developers.google.com/style/tables
- Google visualization traps guidance:
  - https://developers.google.com/machine-learning/guides/data-traps/visualization-traps
- Datawrapper accessibility guidance:
  - https://academy.datawrapper.de/article/206-how-we-make-sure-our-charts-maps-and-tables-are-accessible

## Current reference implementation
The live report:
- `docs/reviews/2026-03-11-phase2-baseline-results.html`

Current state:
- Baseline profile: complete
- Prepared scene cache: complete (benchmarked, analyzed, recommendation issued)
- Spatial index: pending
- Broad-phase sort: pending
- Path caching: pending
- Native canvas: pending
