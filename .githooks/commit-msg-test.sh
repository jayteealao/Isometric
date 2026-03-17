#!/usr/bin/env bash
# Tests for .githooks/commit-msg
# Run with: bash .githooks/commit-msg-test.sh
# Exit code: 0 = all tests passed, non-zero = failures.

set -euo pipefail

HOOK=".githooks/commit-msg"
PASS=0
FAIL=0
TMPFILE=$(mktemp)
trap 'rm -f "$TMPFILE"' EXIT

# ── Test harness ──────────────────────────────────────────────────────────────

run_hook() {
    printf '%s' "$1" > "$TMPFILE"
    bash "$HOOK" "$TMPFILE" 2>/dev/null
}

assert_passes() {
    local description="$1"
    local message="$2"
    if run_hook "$message"; then
        printf 'PASS: %s\n' "$description"
        ((PASS++)) || true
    else
        printf 'FAIL: %s\n' "  expected PASS for: %s" "$description" "$message"
        ((FAIL++)) || true
    fi
}

assert_fails() {
    local description="$1"
    local message="$2"
    if run_hook "$message"; then
        printf 'FAIL: %s\n  expected hook to REJECT: %s\n' "$description" "$message"
        ((FAIL++)) || true
    else
        printf 'PASS: %s\n' "$description"
        ((PASS++)) || true
    fi
}

# ── Valid commit messages ─────────────────────────────────────────────────────

assert_passes "feat with scope"               "feat(core): add cylinder shape primitive"
assert_passes "fix with scope"                "fix(compose): prevent crash on empty scene"
assert_passes "docs without scope"            "docs: update README with new Maven coordinates"
assert_passes "ci without scope"              "ci: add PR workflow"
assert_passes "build without scope"           "build: migrate to convention plugins"
assert_passes "chore without scope"           "chore: update .gitignore"
assert_passes "test with scope"               "test(core): add projection edge case"
assert_passes "refactor with scope"           "refactor(core): extract SceneCache class"
assert_passes "perf with scope"               "perf(core): replace Double with Float"
assert_passes "breaking change with bang"     "feat(core)!: redesign Point class"
assert_passes "breaking change perf"          "perf(core)!: replace Double with Float for coordinates"
assert_passes "fix without scope"             "fix: resolve null pointer on empty scene"
assert_passes "feat without scope"            "feat: add batch rendering support"
assert_passes "multi-word scope"              "feat(android-view): add touch support"
assert_passes "description length boundary"  "feat(core): $(python3 -c 'print("a"*98)')"

# ── Invalid commit messages ───────────────────────────────────────────────────

assert_fails "empty message"                    ""
assert_fails "no type"                          "add cylinder shape primitive"
assert_fails "invalid type"                     "feature(core): add cylinder shape"
assert_fails "invalid type style"              "Feature: add something"
assert_fails "missing colon after scope"        "feat(core) add cylinder shape"
assert_fails "missing space after colon"        "feat(core):add cylinder shape"
assert_fails "missing description"              "feat(core): "
# The regex .{1,100} matches whitespace — trailing spaces after ": " satisfy the pattern.
# This documents actual hook behavior: a non-empty whitespace-only "description" is accepted.
assert_passes "whitespace after colon-space is accepted by regex" "feat(core):   "
assert_fails "uppercase type"                   "FEAT(core): add cylinder"
assert_fails "wrong type wip"                   "wip: work in progress"
assert_fails "type with trailing space before colon" "feat : add something"

# ── Report ────────────────────────────────────────────────────────────────────

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi