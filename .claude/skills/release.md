---
description: >
  Full release pipeline for the Isometric library. Use when the user says
  "release", "publish", "cut a release", "ship version", "release v*",
  or asks to merge and publish to Maven Central. Guides through pre-release
  checks → PR merge → GitHub Release → CI monitoring → Maven Central
  confirmation in strict order, stopping at each gate before proceeding.
---

# Isometric Library Release Pipeline

You are running the full release pipeline for the Isometric library
(`io.github.jayteealao:isometric-*`). Work through every phase in order.
**Never skip a phase.** Stop and report any failure immediately — do not
proceed to the next phase until the current one is clean.

---

## Phase 1 — Pre-Release Checks

### 1.1 Determine the version

If the user has not specified a version, run:

```bash
git cliff --bumped-version
```

Present the suggested version and ask the user to confirm before continuing.
The version must follow SemVer (`MAJOR.MINOR.PATCH`). Do **not** use a
`-SNAPSHOT` suffix for releases.

### 1.2 Verify the working branch

```bash
git branch --show-current
git status --short
```

- Must be on a feature/release branch, **not** directly on `main`.
- Working tree must be **clean**. If there are uncommitted changes, stop and
  ask the user to commit or stash them first.

### 1.3 Verify version is set consistently across all three library modules

```bash
grep -r "^version = " \
  isometric-core/build.gradle.kts \
  isometric-compose/build.gradle.kts \
  isometric-android-view/build.gradle.kts
```

All three must show the same version, and it must match the version agreed
in step 1.1. If any differ, update them:

```kotlin
// In each module's build.gradle.kts
version = "X.Y.Z"
```

Also verify the release workflow's tag validation will pass:

```bash
grep 'GRADLE_VERSION' .github/workflows/release.yml
```

The workflow reads the version from `isometric-core/build.gradle.kts` —
confirm that pattern still matches.

### 1.4 Run the full local CI suite

```bash
./gradlew build test apiCheck
```

All tasks must be **BUILD SUCCESSFUL** with zero test failures. If
`apiCheck` fails, it means the public ABI changed without an `apiDump`
update — run `./gradlew apiDump`, commit the updated `.api` files, then
re-run.

### 1.5 Dry-run publish to local Maven

```bash
./gradlew publishToMavenLocal
```

Then verify all three artifact sets are present and signed:

```bash
find ~/.m2/repository/io/github/jayteealao -name "*.pom" | sort
find ~/.m2/repository/io/github/jayteealao -name "*.asc" | sort
```

Each module must have: `.jar`/`.aar`, `-sources.jar`, `-javadoc.jar`,
`.pom`, `.module`, and a `.asc` signature for every file.

Spot-check the POM for a required Maven Central field:

```bash
cat ~/.m2/repository/io/github/jayteealao/isometric-core/X.Y.Z/isometric-core-X.Y.Z.pom \
  | grep -E "<(name|description|url|licenses|developers|scm)>"
```

All six elements must be present.

### 1.6 Verify GitHub Secrets

```bash
gh secret list -R jayteealao/Isometric
```

The following five secrets must exist and have a **recent** update timestamp:

| Secret | Purpose |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
| `SIGNING_KEY_ID` | Last 8 chars of GPG key fingerprint |
| `SIGNING_KEY_PASSWORD` | GPG key passphrase |
| `SIGNING_KEY` | ASCII-armored private key (single line, no headers) |

If any are missing or suspiciously old (e.g. `SIGNING_KEY` predating this
session), re-upload. Source the passphrase from your local Gradle properties
— never hard-code it:

```bash
# Read passphrase from ~/.gradle/gradle.properties (never hard-code it)
GPG_PASSPHRASE=$(grep '^signingInMemoryKeyPassword=' ~/.gradle/gradle.properties | cut -d= -f2)
SIGNING_KEY=$(gpg --batch --yes --pinentry-mode loopback \
    --passphrase "$GPG_PASSPHRASE" \
    --export-secret-keys --armor 764AB554 \
    | grep -v '\-\-' | grep -v '^=.' | tr -d '\n')
gh secret set SIGNING_KEY -R jayteealao/Isometric --body "$SIGNING_KEY"
unset GPG_PASSPHRASE
```

> **Safer alternative** — pipe the passphrase via `--passphrase-fd 0` so it
> never appears in the process list:
>
> ```bash
> GPG_PASSPHRASE=$(grep '^signingInMemoryKeyPassword=' ~/.gradle/gradle.properties | cut -d= -f2)
> SIGNING_KEY=$(echo "$GPG_PASSPHRASE" | gpg --batch --yes --pinentry-mode loopback \
>     --passphrase-fd 0 \
>     --export-secret-keys --armor 764AB554 \
>     | grep -v '\-\-' | grep -v '^=.' | tr -d '\n')
> gh secret set SIGNING_KEY -R jayteealao/Isometric --body "$SIGNING_KEY"
> unset GPG_PASSPHRASE
> ```

### 1.7 Generate / update the changelog

```bash
git cliff -o CHANGELOG.md --tag vX.Y.Z
```

Review the generated `CHANGELOG.md`. If it looks correct, commit it:

```bash
git add CHANGELOG.md
git commit -m "docs: update CHANGELOG.md for vX.Y.Z"
```

---

## Phase 2 — Merge to Main

### 2.1 Push the branch

```bash
git push origin HEAD
```

### 2.2 Open a Pull Request

```bash
gh pr create \
  --title "build: prepare release vX.Y.Z" \
  --body "$(cat <<'EOF'
## Summary
- Version bumped to X.Y.Z across all three library modules
- CHANGELOG.md updated
- All pre-release checks passed locally (build, test, apiCheck, publishToMavenLocal)

## Pre-release checklist
- [ ] `./gradlew build test apiCheck` passes
- [ ] `./gradlew publishToMavenLocal` produces correctly signed artifacts
- [ ] Version set to X.Y.Z in all three library modules
- [ ] CHANGELOG.md updated
- [ ] GitHub Secrets verified
EOF
)"
```

### 2.3 Wait for CI to pass

```bash
gh pr checks --watch
```

The `CI / Build & Test` job must pass. Do **not** merge until all checks
are green. If the build job fails, investigate the failure, fix it on the
branch, push, and wait again.

### 2.4 Merge the PR

```bash
gh pr merge --rebase
```

Use `--rebase` for a fast-forward merge — all release-prep commits land individually on
`master` with their full history preserved. After merge:

```bash
git checkout main
git pull origin main
git log --oneline -5
```

Confirm the merge commit is present on `main`.

---

## Phase 3 — Tag and Create GitHub Release

### 3.1 Generate release notes from the changelog

```bash
git cliff --latest --strip header
```

Capture the output — it will be used as the GitHub Release description.

### 3.2 Create the GitHub Release

```bash
gh release create vX.Y.Z \
  --title "vX.Y.Z" \
  --notes "$(git cliff --latest --strip header)" \
  --target main
```

This tag creation on `main` triggers the `release.yml` workflow immediately.

### 3.3 Confirm the release workflow started

```bash
gh run list --workflow=release.yml --limit 3
```

Get the run ID of the most recent run and watch it:

```bash
gh run watch <RUN_ID>
```

---

## Phase 4 — Monitor the Release CI Workflow

The `release.yml` workflow runs these steps in order. Watch for failures
at each:

| Step | What it does | Common failure |
|---|---|---|
| `Validate version matches tag` | Ensures tag `vX.Y.Z` == `version` in `isometric-core/build.gradle.kts` | Version mismatch |
| `Build and test` | Full `./gradlew build test apiCheck` | Flaky test or API drift |
| `Publish to Maven Central` | `./gradlew publishAndReleaseToMavenCentral` | Bad signing credentials, wrong key format |

If `Publish to Maven Central` fails with a signing error:

1. Re-export the key: `gpg --export-secret-keys --armor 5B94324C764AB554 | grep -v '\-\-' | grep -v '^=.' | tr -d '\n'`
2. Re-upload: `gh secret set SIGNING_KEY -R jayteealao/Isometric --body "$KEY"`
3. Re-run the workflow: `gh run rerun <RUN_ID>`

If it fails with a 401 from Maven Central:

1. Check `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` are still valid
2. User tokens expire — regenerate at [central.sonatype.com](https://central.sonatype.com) → Account → Generate User Token
3. Re-upload both secrets and re-run

### 4.1 Confirm the workflow completed successfully

```bash
gh run view <RUN_ID> --log | tail -20
```

Look for `BUILD SUCCESSFUL` and `Releasing deployment` in the output.

---

## Phase 5 — Confirm Maven Central Publication

Maven Central propagation typically takes **5–30 minutes** after the
workflow completes. Poll until all three artifacts are resolvable.

### 5.1 Check the Central Portal

```bash
open https://central.sonatype.com/artifact/io.github.jayteealao/isometric-core
```

Or via API:

```bash
curl -s "https://central.sonatype.com/api/v1/publisher/published?namespace=io.github.jayteealao&name=isometric-core&version=X.Y.Z" \
  | python3 -m json.tool | grep -E "(published|version)"
```

### 5.2 Verify all three modules on search.maven.org

```bash
for artifact in isometric-core isometric-compose isometric-android-view; do
  echo "=== $artifact ==="
  curl -s "https://search.maven.org/solrsearch/select?q=g:io.github.jayteealao+a:$artifact&rows=1&wt=json" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['response']['docs'][0]['latestVersion'] if d['response']['numFound'] > 0 else 'NOT FOUND')"
done
```

All three must return `X.Y.Z`.

### 5.3 Test resolution from a fresh project

Create a minimal `build.gradle.kts` snippet and resolve it:

```bash
mkdir -p /tmp/isometric-smoke-test && cat > /tmp/isometric-smoke-test/build.gradle.kts << EOF
repositories { mavenCentral() }
configurations { create("smoke") }
dependencies {
    "smoke"("io.github.jayteealao:isometric-core:X.Y.Z")
    "smoke"("io.github.jayteealao:isometric-compose:X.Y.Z")
    "smoke"("io.github.jayteealao:isometric-android-view:X.Y.Z")
}
tasks.register("resolveDeps") {
    val cfg = configurations["smoke"]
    doLast { cfg.resolvedConfiguration.resolvedArtifacts.forEach { println("✅ ${it.moduleVersion.id}") } }
}
EOF
cd /tmp/isometric-smoke-test && gradle resolveDeps --no-daemon 2>&1 | grep -E "(✅|FAILED|error)"
```

All three `✅` lines must appear.

---

## Phase 6 — Post-Release

### 6.1 Bump to next development version

On `main`, update the version in all three library modules to the next
SNAPSHOT:

```kotlin
// isometric-core/build.gradle.kts
version = "X.Y+1.0-SNAPSHOT"   // or X+1.0.0-SNAPSHOT for a major bump
```

Commit:

```bash
git add isometric-core/build.gradle.kts \
        isometric-compose/build.gradle.kts \
        isometric-android-view/build.gradle.kts
git commit -m "build: bump to X.Y+1.0-SNAPSHOT development version"
git push origin main
```

### 6.2 Verify the GitHub Release page

```bash
gh release view vX.Y.Z
```

Confirm title, tag, and release notes look correct.

### 6.3 Summary report

Report the following to the user:

```text
✅ Release vX.Y.Z complete

Published:
  io.github.jayteealao:isometric-core:X.Y.Z
  io.github.jayteealao:isometric-compose:X.Y.Z
  io.github.jayteealao:isometric-android-view:X.Y.Z

GitHub Release: https://github.com/jayteealao/Isometric/releases/tag/vX.Y.Z
Maven Central:  https://central.sonatype.com/artifact/io.github.jayteealao/isometric-core
Next version:   X.Y+1.0-SNAPSHOT (on main)
```

---

## Quick Reference — Key Values

| Item | Value |
|---|---|
| Group ID | `io.github.jayteealao` |
| Modules | `isometric-core`, `isometric-compose`, `isometric-android-view` |
| GPG Key ID | `764AB554` |
| GPG Fingerprint | `37823EE2BB39B1996E0B3E655B94324C764AB554` |
| GPG Key Passphrase | stored in `~/.gradle/gradle.properties` as `signingInMemoryKeyPassword` — do not commit |
| Central Portal | [central.sonatype.com](https://central.sonatype.com) |
| Default branch | `main` |
| Release trigger | GitHub Release creation on `main` |
| Gradle publish task | `./gradlew publishAndReleaseToMavenCentral` |
