---
description: >
  Complete PR readiness pipeline for the Isometric library. Audits the branch,
  triages review comments from all automated sources (CodeRabbit, Greptile, Gemini)
  and human reviewers, verifies documentation coverage, ensures API dumps and doc
  mirrors are current, validates commits, runs local CI gates, and creates a PR if
  one does not already exist. Prepares the branch for rebase+merge (fast-forward)
  into master. Use when the user says "prepare PR", "PR ready", "check PR", "triage
  comments", "address review", "address comments", "review comments", or
  "is this ready to merge".
---

# PR Preparation Pipeline

Run all phases in order. Stop and report any failure before proceeding.
**Present findings to the user before taking any fixing action that modifies code or
rewrites history.** Never squash, amend, or rebase without reporting first.

---

## Phase 1 — Branch Audit

### 1.1 Verify branch and working tree

```bash
git branch --show-current
git status --short
git log origin/master..HEAD --oneline
```

- Must be on a feature branch, not `master`. If on `master`, stop and ask the user
  which branch to prepare.
- If the working tree is dirty, list the uncommitted changes and ask the user to
  commit or stash them before continuing.
- Report how many commits are ahead of `origin/master` and list their subjects.

### 1.2 Check for an existing PR

```bash
gh pr list --head "$(git branch --show-current)" --json number,title,url,state
```

**If a PR already exists:** record its number for all later phases. Report its title
and URL and continue.

**If no PR exists:** inspect the branch commits to derive a title, then create one:

```bash
# Derive a title from the most significant commit (feat > fix > docs > build > chore)
git log origin/master..HEAD --oneline

gh pr create \
  --title "<conventional commit title>" \
  --body "$(cat .github/PULL_REQUEST_TEMPLATE.md)" \
  --base master
```

The PR title must be a valid Conventional Commit header matching the branch's primary
change type (`feat:`, `fix:`, `docs:`, `build:`, etc.). Record the new PR number.

---

## Phase 2 — Commit Validation

### 2.1 Run commitlint

```bash
npx --yes commitlint \
  --from "$(git merge-base HEAD origin/master)" \
  --to HEAD \
  --verbose
```

All commits must conform to Conventional Commits. Allowed types per `.commitlintrc.yml`:
`feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `ci`, `build`, `chore`, `style`,
`revert`, `assets`, `benchmark`, `compose`, `core`, `engine`, `renderer`.

Maximum header length: 120 characters (warning, not error).

If any commits fail, list them clearly. **Do not rewrite commits without explicit user
approval** — present the violations and ask how to proceed before touching history.

### 2.2 Check for breaking changes

```bash
git log origin/master..HEAD --format="%s %b" | grep -i "BREAKING CHANGE\|!:"
```

If breaking changes are present, flag them prominently — they will trigger a major
version bump in git-cliff and must be deliberate.

---

## Phase 3 — API Surface Check

### 3.1 Detect changed Kotlin source files

```bash
git diff origin/master..HEAD --name-only | grep -E "^isometric-(core|compose)/src/main/kotlin"
```

**If no Kotlin source files changed in the library modules:** skip to Phase 4.

**If Kotlin source files changed:** run apiCheck to detect ABI drift:

```bash
./gradlew apiCheck 2>&1
```

- **apiCheck passes:** no action needed. Confirm in the readiness report.
- **apiCheck fails:** the public API changed without an `apiDump`. Inspect the diff
  carefully — new classes and functions are expected, removals or signature changes
  are potentially breaking. Run:

```bash
./gradlew apiDump
git diff isometric-core/api/isometric-core.api isometric-compose/api/isometric-compose.api
```

Present the diff to the user. If it looks correct, stage and commit:

```bash
git add isometric-core/api/isometric-core.api isometric-compose/api/isometric-compose.api
git commit -m "build: update API dumps for <brief description>"
```

---

## Phase 4 — Documentation Completeness

### 4.1 Identify new public Kotlin symbols

For each changed Kotlin source file in `isometric-core/src/main/` or
`isometric-compose/src/main/`, check for new or modified public declarations:

```bash
git diff origin/master..HEAD \
  -- "isometric-core/src/main/kotlin/**" \
  -- "isometric-compose/src/main/kotlin/**" \
  | grep "^++" -A1 | grep "^+" \
  | grep -E "public |fun |class |object |interface |enum "
```

Read the actual source files for any new public types or functions and verify the
following for each:

**KDoc coverage:**
Every public class, function, and property must have a `/** ... */` KDoc comment. Read
the file and flag any missing KDoc. New types introduced in this branch with no KDoc
are a documentation gap that will show up in the Dokka API reference.

**Composables reference** (`site/src/content/docs/reference/composables.mdx`):
Any new `@Composable` function that is part of the public API must have a parameter
table entry in the reference. Check for its presence:

```bash
grep -n "<ComposableName>" site/src/content/docs/reference/composables.mdx
```

**Engine reference** (`site/src/content/docs/reference/engine.mdx`):
Any new extension function on `IsometricEngine` must appear in the Tile Coordinate
Helpers section or a relevant section of the engine reference.

**Guide coverage:**
Major new features (new composables, new config types) should have or be covered by a
guide page under `site/src/content/docs/guides/`. Check for obvious gaps and report
them — do not create guides without user confirmation.

**Testing reference** (`site/src/content/docs/contributing/testing.mdx`):
Any new test class added to `isometric-core/src/test/` or `isometric-compose/src/androidTest/`
should have a corresponding row in the test class table:

```bash
git diff origin/master..HEAD --name-only | grep "src/test\|src/androidTest"
```

### 4.2 Verify sidebar registration for new guide pages

```bash
git diff origin/master..HEAD --name-only | grep "^site/src/content/docs/guides/.*\.mdx$"
```

If new guide `.mdx` files were added, verify each appears in `site/astro.config.mjs`:

```bash
grep -n "slug: 'guides/" site/astro.config.mjs
```

A guide file with no matching `slug` entry is invisible on the docs site. Add missing
entries in logical order (simpler concepts before more complex ones). Sidebar ordering
convention: Shapes → Stack → Tile Grid → Animation → Gestures → Camera → Theming →
Custom Shapes → Performance → Advanced Config.

### 4.3 Check README coverage for new features

For any `feat:` commit in this branch, verify the README features list and
documentation links section acknowledge the new capability:

```bash
git log origin/master..HEAD --format="%s" | grep "^feat"
# For each feature, grep README
grep -n "<feature keyword>" README.md
```

Report any features present in the code but absent from the README features list or
documentation links.

### 4.4 Run sync-docs.js if any .mdx files changed

```bash
git diff origin/master..HEAD --name-only | grep "site/src/content/docs/.*\.mdx$"
```

**If any `.mdx` files changed** (including any fixes made in earlier phases):

```bash
node scripts/sync-docs.js
```

Check for resulting changes in `docs/`:

```bash
git status --short docs/
```

If the script produced changes, stage and commit:

```bash
git add docs/
git commit -m "docs: regenerate .md mirrors via sync-docs.js"
```

Always run sync-docs.js after modifying `.mdx` files. Never edit `docs/*.md` files
by hand — they are generated output.

---

## Phase 5 — PR Comment Triage

### 5.1 Fetch all review comments

Fetch general PR comments (bot summaries, top-level remarks):

```bash
gh pr view <PR_NUMBER> --json comments --jq '.comments[] | { author: .author.login, body: .body }'
```

Fetch inline review threads via GraphQL — this returns thread IDs needed for resolution later.
Record the `threadId` alongside each comment so it can be resolved after the fix is applied:

```bash
gh api graphql -f query='
{
  repository(owner: "jayteealao", name: "Isometric") {
    pullRequest(number: <PR_NUMBER>) {
      reviewThreads(first: 100) {
        nodes {
          id
          isResolved
          comments(first: 10) {
            nodes {
              author { login }
              body
              path
              originalLine
            }
          }
        }
      }
    }
  }
}' --jq '.data.repository.pullRequest.reviewThreads.nodes[]
  | select(.isResolved == false)
  | { threadId: .id, author: .comments.nodes[0].author.login,
      file: .comments.nodes[0].path, line: .comments.nodes[0].originalLine,
      body: .comments.nodes[0].body }'
```

Fetch formal review submissions (approved / request changes):

```bash
gh api repos/jayteealao/Isometric/pulls/<PR_NUMBER>/reviews \
  --jq '.[] | { author: .user.login, state: .state, body: .body }'
```

### 5.2 Identify comment sources

| Source | GitHub login |
|---|---|
| CodeRabbit | `coderabbitai` |
| Greptile | `greptile-dev` |
| Gemini Code Assist | `gemini-code-assist` |
| Codex | `chatgpt-codex-connector[bot]` |
| Human reviewer | any other login |

### 5.3 Classify each comment

Read through every comment body. Classify as:

- **🔴 Blocking** — correctness bug, crash risk, security issue, data loss, or
  explicit "request changes" from a human reviewer with a specific must-fix item.
- **🟡 Suggestion** — style, naming, documentation gap, test coverage, or a
  bot-flagged improvement worth considering.
- **🟢 Informational** — walkthrough summaries, praise, nitpicks already decided
  against, or items marked as deferred.

For each 🔴 and 🟡 item: report the source, file, line (if available), the comment
text, and a recommended action.

### 5.4 Apply fixes

**🔴 Blocking:** Fix immediately. Read the referenced file, apply the correction,
stage the fix, and commit with an appropriate Conventional Commit message. Report each
fix made.

**🟡 Suggestions:** Present all suggestions grouped by file. Ask the user which to
apply. Apply approved ones and commit. Skip declined ones and note the reason.

**🟢 Informational:** Summarise in the Phase 9 report. No action taken.

### 5.5 Resolve fixed threads

After each fix is committed, mark the corresponding review thread as resolved using the
`threadId` captured in Phase 5.1. Only resolve threads for items that were actually fixed
or deliberately declined — leave open threads for deferred items:

```bash
gh api graphql -f query='
mutation {
  resolveReviewThread(input: { threadId: "<THREAD_ID>" }) {
    thread { isResolved }
  }
}'
```

General PR comments (top-level, not in threads) cannot be resolved via API — no action needed.

After all fixes and resolutions, push:

```bash
git push origin HEAD
```

---

## Phase 6 — Local CI Gate

### 6.1 Build, test, and API check

```bash
./gradlew build test apiCheck
```

Must be **BUILD SUCCESSFUL** with zero test failures. If it fails:

1. Read the error output carefully
2. Fix the root cause
3. Commit the fix with an appropriate message
4. Re-run until clean

### 6.2 Markdown lint

Run locally against all changed markdown and mdx files:

```bash
npx --yes markdownlint-cli2 \
  "**/*.md" "site/src/content/docs/**/*.mdx" \
  --ignore CHANGELOG.md \
  --ignore "docs/internal/**" \
  --ignore "node_modules/**" \
  --ignore "**/build/**" \
  --ignore "site/dist/**" \
  --config .markdownlint.json
```

Fix any linting errors in `site/src/content/docs/*.mdx` source files. Never fix them
directly in `docs/*.md` mirrors — fix the source and re-run `node scripts/sync-docs.js`.

After lint fixes, commit and re-run sync if needed.

---

## Phase 7 — PR Body Review

### 7.1 Read the current PR body

```bash
gh pr view <PR_NUMBER> --json body --jq '.body'
```

### 7.2 Update the checklist

Cross-reference each checkbox in the PR template against the actual branch content:

| Checklist item | Verification |
|---|---|
| Self-review completed | Phase 5 complete |
| Commit messages follow Conventional Commits | Phase 2 result |
| No unrelated changes included | `git diff origin/master..HEAD --stat` — flag any off-topic files |
| Updated existing docs page(s) | Phase 4 result |
| Added new docs page(s) registered in astro.config.mjs | Phase 4.2 result |
| KDoc updated for all changed public API symbols | Phase 4.1 result |
| Screenshots regenerated | Check if any shape/rendering output changed visually |

Fill in the **Summary** section if it is still a placeholder. Write one concise
paragraph describing what the PR does and why. Fill in the **Test plan** section if
it is empty.

Tick all completed checklist items. Update the PR body:

```bash
gh pr edit <PR_NUMBER> --body "$(cat <<'EOF'
<updated body content>
EOF
)"
```

### 7.3 Verify PR title format

The title must be a valid Conventional Commit header and ≤ 120 characters. If it does
not match, update it:

```bash
gh pr edit <PR_NUMBER> --title "<corrected title>"
```

---

## Phase 8 — Pull, Rebase onto Master, Push

### 8.1 Fetch everything

```bash
git fetch origin
```

Fetch all remotes at once — this updates `origin/master` and `origin/<branch>` in
one round-trip.

### 8.2 Integrate remote changes on the feature branch

Check whether the remote feature branch has commits the local branch does not:

```bash
git log HEAD..origin/$(git branch --show-current) --oneline
```

If any commits appear, fast-forward the local branch before doing anything else:

```bash
git rebase origin/$(git branch --show-current)
```

This handles commits pushed by CI bots, other collaborators, or a previous session.
If this rebase has conflicts the local and remote feature branches have diverged in
an incompatible way — stop and report to the user before proceeding.

### 8.3 Rebase onto master

```bash
git rebase origin/master
```

If there are conflicts, stop immediately. Report each conflicting file by name and
show the conflict markers. **Do not resolve conflicts automatically.** Present each
conflict to the user and wait for instruction before continuing.

### 8.4 Push

If both rebases succeeded cleanly, push with force-with-lease (refuses if the remote
has commits the local branch still does not know about — a final safety net):

```bash
git push --force-with-lease origin HEAD
```

---

## Phase 9 — Final Readiness Report

### 9.1 Check actual PR blocking state

Before declaring readiness, verify the PR's live state on GitHub:

```bash
gh pr view <PR_NUMBER> --json reviewDecision,statusCheckRollup \
  --jq '{
    decision: .reviewDecision,
    failing: [.statusCheckRollup[] | select(.conclusion != "SUCCESS" and .conclusion != null) | {name: .name, conclusion: .conclusion}],
    pending: [.statusCheckRollup[] | select(.status == "IN_PROGRESS" or .status == "QUEUED") | {name: .name}]
  }'
```

| `reviewDecision` value | Meaning |
|---|---|
| `APPROVED` | All required reviews approved — clear to merge |
| `CHANGES_REQUESTED` | A reviewer has requested changes — must be addressed |
| `REVIEW_REQUIRED` | Required reviews not yet submitted — waiting |
| `null` | No review requirements configured |

If any checks are failing or `reviewDecision` is `CHANGES_REQUESTED`, mark those items ❌
in the report and stop. Do **not** proceed to the merge command.

### 9.2 Resolve any remaining open threads

Check for unresolved threads that were fixed but not yet resolved in Phase 5.5:

```bash
gh api graphql -f query='
{
  repository(owner: "jayteealao", name: "Isometric") {
    pullRequest(number: <PR_NUMBER>) {
      reviewThreads(first: 100) {
        nodes {
          id
          isResolved
          comments(first: 1) { nodes { author { login } body } }
        }
      }
    }
  }
}' --jq '.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved == false) | {threadId: .id, author: .comments.nodes[0].author.login, snippet: .comments.nodes[0].body[:80]}'
```

Resolve any threads whose fix was already committed and pushed.

### 9.3 Present summary

Present a summary table:

```
PR Readiness Report — <branch> → master
PR: #<number> — <title>

✅ Commits: N commits, all conventional
✅ API dumps: current (or: no public API changes)
✅ Documentation: complete
   - KDoc: all public symbols documented
   - composables.mdx: updated
   - engine.mdx: updated
   - testing.mdx: updated
   - Sidebar: registered in astro.config.mjs
✅ sync-docs.js: run, mirrors up to date
✅ PR comments triaged:
   - CodeRabbit: N blocking fixed, M suggestions (K applied, J deferred)
   - Greptile: informational only
   - Gemini: N/A (error creating summary)
   - Codex: N blocking fixed
   - All resolved threads marked resolved
✅ Local CI: BUILD SUCCESSFUL
✅ Markdown lint: clean
✅ PR body: checklist complete, summary filled
✅ Rebased onto origin/master (fast-forward eligible)
✅ GitHub CI: all checks passing
✅ Review decision: APPROVED (or: no review required)

Ready for rebase+merge:
  gh pr merge <PR_NUMBER> --rebase
```

If any item is ❌, describe what remains and stop. Do **not** run the merge command —
report to the user and wait for instruction.

---

## Quick Reference

| Item | Value |
|---|---|
| Default base branch | `master` |
| Merge strategy | `gh pr merge --rebase` (rebase + fast-forward) |
| Conventional Commit types | `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `ci`, `build`, `chore`, `style`, `revert`, `assets`, `benchmark`, `compose`, `core`, `engine`, `renderer` |
| Allowed scopes | `core`, `compose`, `view`, `bench`, `build`, `ci`, `docs` |
| Header max length | 120 characters |
| API dump command | `./gradlew apiDump` |
| API dump files | `isometric-core/api/isometric-core.api`, `isometric-compose/api/isometric-compose.api` |
| Doc mirror command | `node scripts/sync-docs.js` |
| Doc source | `site/src/content/docs/**/*.mdx` (edit here) |
| Doc mirrors | `docs/**/*.md` (generated — never edit by hand) |
| Sidebar config | `site/astro.config.mjs` |
| CI: build gate | `./gradlew build test apiCheck` |
| CI: markdown gate | `markdownlint-cli2` with `.markdownlint.json` |
| PR bot logins | `coderabbitai`, `greptile-dev`, `gemini-code-assist` |
| Repo | `jayteealao/Isometric` |
