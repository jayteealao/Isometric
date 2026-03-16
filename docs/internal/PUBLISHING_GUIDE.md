# Publishing Guide — Isometric Library Fork

> A comprehensive guide for publishing this fork of [FabianTerhorst/Isometric](https://github.com/FabianTerhorst/Isometric) to Maven Central under a new namespace.

---

## Table of Contents

1. [License Compliance](#1-license-compliance)
2. [Package & Namespace Renaming](#2-package--namespace-renaming)
3. [Maven Central Account Setup](#3-maven-central-account-setup)
4. [Gradle Publishing Configuration](#4-gradle-publishing-configuration)
5. [GPG Signing](#5-gpg-signing)
6. [Version Catalog & Build Modernization](#6-version-catalog--build-modernization)
7. [API Compatibility Tracking](#7-api-compatibility-tracking)
8. [Conventional Commits](#8-conventional-commits)
9. [Changelog Generation](#9-changelog-generation)
10. [CI/CD with GitHub Actions](#10-cicd-with-github-actions)
11. [Release Strategy](#11-release-strategy)
12. [Pre-Flight Checklist](#12-pre-flight-checklist)

---

## 1. License Compliance

### Current State

The upstream project is **Apache License 2.0**. The `LICENSE` file exists but has **unfilled copyright placeholders** (`Copyright {yyyy} {name of copyright owner}`). The README credits `Copyright 2017 Fabian Terhorst`.

### What Apache 2.0 Requires for Forks

Apache 2.0 is permissive — you **can** fork, modify, rename, and republish. But you must:

| Obligation | What To Do |
|---|---|
| **Preserve the license** | Keep the `LICENSE` file at the repo root. Do NOT delete or replace it. |
| **Preserve attribution** | Keep the original copyright notice. Add your own alongside it. |
| **State changes** | Modified files must carry a **prominent notice** that you changed them. This is unique to Apache 2.0 — MIT and BSD don't require it. |
| **NOTICE file** | If the upstream has a `NOTICE` file, you must preserve it. The upstream does not have one, but you should create one (see below). |
| **No endorsement** | Do not use "FabianTerhorst" or the original project name in a way that implies endorsement. |

### Action Items

#### 1a. Fix the LICENSE file

The copyright placeholder in `LICENSE` was never filled in by the upstream. Leave the Apache 2.0 license text as-is, and handle attribution in the `NOTICE` file instead.

#### 1b. Create a NOTICE file

```
Isometric
Copyright 2017 Fabian Terhorst (original work)
Copyright 2024-2026 jayteealao (derivative work)

This product includes software developed by Fabian Terhorst
(https://github.com/FabianTerhorst/Isometric).

This is a fork that has been substantially rewritten:
  - Full Kotlin rewrite of the core rendering engine
  - Jetpack Compose integration with custom runtime applier
  - Android View adapter layer
  - Performance benchmark harness

Licensed under the Apache License, Version 2.0.
```

#### 1c. Add modification headers to changed files

For files that were originally from the upstream and have been modified, add a header:

```kotlin
/*
 * Copyright 2017 Fabian Terhorst (original work)
 * Copyright 2024-2026 jayteealao (modifications)
 *
 * Licensed under the Apache License, Version 2.0
 * Modifications: Rewritten in Kotlin; [brief description of changes]
 */
```

For files that are entirely new (the Compose module, benchmark harness, etc.), use your own copyright:

```kotlin
/*
 * Copyright 2024-2026 jayteealao
 *
 * Licensed under the Apache License, Version 2.0
 */
```

> **Tip:** You can automate header insertion with the [Spotless](https://github.com/diffplug/spotless) Gradle plugin, which supports license header management.

---

## 2. Package & Namespace Renaming

### Current State

| Scope | Current Value |
|---|---|
| Maven `group` | `io.fabianterhorst` |
| Root package | `io.fabianterhorst.isometric` |
| Compose package | `io.fabianterhorst.isometric.compose` |
| View package | `io.fabianterhorst.isometric.view` |
| Sample app | `io.fabianterhorst.isometric.sample` |
| Benchmark app | `io.fabianterhorst.isometric.benchmark` |

**You cannot publish to Maven Central under `io.fabianterhorst`** — you don't own that namespace. You must rename everything.

### Target Namespace

Choose one of:

| Option | Namespace | Pros | Cons |
|---|---|---|---|
| **GitHub-based** | `io.github.jayteealao` | Free, auto-verified via GitHub login | Tied to your GitHub username |
| **Custom domain** | `com.yourdomain` | Professional, portable | Requires owning a domain + DNS verification |

**Recommended for getting started:** `io.github.jayteealao`

### Renaming Process

This is a one-time operation. Do it in a dedicated branch/commit.

#### Step 1: Update `group` in build files

**Root `build.gradle` or `build.gradle.kts`:**
```kotlin
allprojects {
    group = "io.github.jayteealao"
}
```

**Each publishable module's `build.gradle.kts`:**
```kotlin
group = "io.github.jayteealao"
```

#### Step 2: Update `settings.gradle.kts`

```kotlin
rootProject.name = "isometric"

include(":isometric-core")
include(":isometric-compose")
include(":isometric-android-view")
include(":app")
include(":isometric-benchmark")
// Remove `:lib` if you're dropping the legacy Java module
```

#### Step 3: Rename source packages

Use IntelliJ IDEA's refactoring:

1. Right-click `io.fabianterhorst.isometric` in the Project panel
2. **Refactor > Rename** (Shift+F6)
3. Change to `io.github.jayteealao.isometric`
4. Check "Search in comments and strings" and "Search for text occurrences"
5. Apply across all modules

**Affected directories:**
```
isometric-core/src/main/kotlin/io/fabianterhorst/isometric/
  → isometric-core/src/main/kotlin/io/github/jayteealao/isometric/

isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/
  → isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/

isometric-android-view/src/main/kotlin/io/fabianterhorst/isometric/view/
  → isometric-android-view/src/main/kotlin/io/github/jayteealao/isometric/view/
```

#### Step 4: Update Android namespace declarations

In each Android module's `build.gradle.kts`:

```kotlin
android {
    namespace = "io.github.jayteealao.isometric.compose" // was io.fabianterhorst.isometric.compose
}
```

And for the sample app:
```kotlin
android {
    namespace = "io.github.jayteealao.isometric.sample"
    defaultConfig {
        applicationId = "io.github.jayteealao.isometric.sample"
    }
}
```

#### Step 5: Update all import statements

IntelliJ's refactoring should catch most of these, but verify with a global search:

```bash
grep -r "io\.fabianterhorst" --include="*.kt" --include="*.kts" --include="*.java" --include="*.xml"
```

#### Step 6: Update documentation

- `README.md` — installation snippets, package references
- `docs/` — any references to the old package name
- Paparazzi snapshot test golden files (may need regeneration)

### Handling Future Upstream Merges

After renaming, merging upstream changes will produce predictable conflicts in package declarations. To minimize friction:

```bash
# Keep upstream remote configured
git remote add upstream https://github.com/FabianTerhorst/Isometric.git

# Fetch and merge
git fetch upstream
git merge upstream/master

# Resolve package name conflicts (mechanical find-and-replace)
```

Since the fork has been substantially rewritten (Kotlin rewrite, Compose runtime, etc.), the overlap with upstream is minimal. Most upstream changes won't conflict at all.

---

## 3. Maven Central Account Setup

### The New Central Portal (Post-June 2025)

The old OSSRH (Sonatype Nexus) system at `oss.sonatype.org` is defunct. All publishing now goes through the **Central Publisher Portal** at [central.sonatype.com](https://central.sonatype.com).

### Step-by-Step Setup

#### 3a. Create an account

1. Go to [central.sonatype.com](https://central.sonatype.com)
2. Sign in with your **GitHub account** (recommended — auto-provisions `io.github.jayteealao`)
3. Or sign up with email + password (for custom domain namespaces)

#### 3b. Verify your namespace

**For `io.github.jayteealao`:**
- Automatically verified when you sign in with your GitHub account `jayteealao`
- No DNS records needed
- Sub-namespaces are automatically allowed (e.g., `io.github.jayteealao.isometric`)

**For a custom domain (e.g., `com.jayteealao`):**
1. Go to **Namespaces** in the portal
2. Click **Add Namespace** → enter `com.jayteealao`
3. Add a DNS TXT record to `jayteealao.com`:
   ```
   TXT  @  "sonatype-verification=<key-provided-by-portal>"
   ```
4. Wait for DNS propagation, then click **Verify**

#### 3c. Generate a user token

1. In the portal, go to **Account** → **Generate User Token**
2. This gives you a username/password pair (NOT your login credentials)
3. Store these securely — you'll need them for Gradle and CI

### Final Maven Coordinates

| Module | Coordinates |
|---|---|
| Core | `io.github.jayteealao:isometric-core:1.0.0` |
| Compose | `io.github.jayteealao:isometric-compose:1.0.0` |
| Android View | `io.github.jayteealao:isometric-android-view:1.0.0` |

---

## 4. Gradle Publishing Configuration

### Recommended Plugin: vanniktech/gradle-maven-publish-plugin

This is the de facto standard for publishing Kotlin libraries to Maven Central via the new Central Portal. It handles POM generation, sources/javadoc JARs, GPG signing, upload, validation polling, and release — all in one plugin.

#### 4a. Add the plugin

**Root `build.gradle.kts`:**
```kotlin
plugins {
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}
```

#### 4b. Configure each publishable module

**`isometric-core/build.gradle.kts`:**
```kotlin
plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

group = "io.github.jayteealao"
version = "1.0.0"

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates(
        groupId = "io.github.jayteealao",
        artifactId = "isometric-core",
        version = version.toString()
    )

    pom {
        name.set("Isometric Core")
        description.set("Platform-agnostic isometric rendering engine for Kotlin")
        url.set("https://github.com/jayteealao/Isometric")
        inceptionYear.set("2024")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("jayteealao")
                name.set("jayteealao")
                url.set("https://github.com/jayteealao")
            }
            developer {
                id.set("fabianterhorst")
                name.set("Fabian Terhorst")
                url.set("https://github.com/FabianTerhorst")
            }
        }

        scm {
            url.set("https://github.com/jayteealao/Isometric")
            connection.set("scm:git:git://github.com/jayteealao/Isometric.git")
            developerConnection.set("scm:git:ssh://git@github.com/jayteealao/Isometric.git")
        }
    }
}
```

**`isometric-compose/build.gradle.kts`:**
```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
    id("com.vanniktech.maven.publish")
}

group = "io.github.jayteealao"
version = "1.0.0"

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates(
        groupId = "io.github.jayteealao",
        artifactId = "isometric-compose",
        version = version.toString()
    )

    pom {
        name.set("Isometric Compose")
        description.set("Jetpack Compose integration for the Isometric rendering engine")
        url.set("https://github.com/jayteealao/Isometric")
        inceptionYear.set("2024")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("jayteealao")
                name.set("jayteealao")
                url.set("https://github.com/jayteealao")
            }
        }

        scm {
            url.set("https://github.com/jayteealao/Isometric")
            connection.set("scm:git:git://github.com/jayteealao/Isometric.git")
            developerConnection.set("scm:git:ssh://git@github.com/jayteealao/Isometric.git")
        }
    }
}
```

Apply the same pattern for `isometric-android-view`.

#### 4c. Configure credentials

**`~/.gradle/gradle.properties`** (local machine, never commit):
```properties
mavenCentralUsername=<user-token-username>
mavenCentralPassword=<user-token-password>
signing.keyId=<last-8-chars-of-gpg-key>
signing.password=<gpg-passphrase>
signing.secretKeyRingFile=/path/to/secring.gpg
```

Or for **in-memory signing** (preferred for CI):
```properties
mavenCentralUsername=<user-token-username>
mavenCentralPassword=<user-token-password>
signingInMemoryKeyId=<last-8-chars-of-gpg-key>
signingInMemoryKeyPassword=<gpg-passphrase>
signingInMemoryKey=<armored-private-key-without-headers>
```

#### 4d. Publish commands

```bash
# Publish and automatically release (no manual portal confirmation)
./gradlew publishAndReleaseToMavenCentral

# Publish but hold for manual confirmation on the portal
./gradlew publishToMavenCentral

# Publish to local Maven (~/.m2) for testing
./gradlew publishToMavenLocal
```

#### 4e. Exclude non-publishable modules

Modules like `:app`, `:isometric-benchmark`, and the legacy `:lib` should NOT have the publish plugin applied. Only apply it to the three library modules.

### Maven Central POM Requirements (Validation Checklist)

The portal will reject your artifacts if any of these are missing:

| Element | Status |
|---|---|
| `groupId` matching a verified namespace | Required |
| `artifactId` | Required |
| `version` (NOT `-SNAPSHOT`) | Required |
| `name` | Required |
| `description` | Required |
| `url` | Required |
| `licenses` (at least one) | Required |
| `developers` (at least one) | Required |
| `scm` (`connection`, `developerConnection`, `url`) | Required |
| Sources JAR (`-sources.jar`) | Required |
| Javadoc JAR (`-javadoc.jar`) | Required (empty JAR acceptable for Kotlin) |
| GPG signatures (`.asc` for every file) | Required |

---

## 5. GPG Signing

### Generate a Key

```bash
# Generate a 4096-bit RSA key (Maven Central minimum)
gpg --full-generate-key
# Select: (1) RSA and RSA
# Key size: 4096
# Expiration: 0 (does not expire) or 2 years
# Name: jayteealao
# Email: your-email@example.com
```

### Publish the Public Key

```bash
# List your keys to find the key ID
gpg --list-keys --keyid-format long

# Upload to key servers (Maven Central checks these)
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

### Export for CI (In-Memory Signing)

```bash
# Export the private key in ASCII armor
gpg --armor --export-secret-keys <KEY_ID> > private-key.asc

# Strip the header/footer lines and newlines for the signingInMemoryKey property:
# Remove "-----BEGIN PGP PRIVATE KEY BLOCK-----" and "-----END PGP PRIVATE KEY BLOCK-----"
# Keep everything in between as a single property value (newlines preserved)
```

Store the exported key, key ID, and passphrase as GitHub Secrets (see [CI/CD section](#10-cicd-with-github-actions)).

### Important Notes

- Use the **primary key** for signing — Maven Central cannot verify sub-key signatures
- RSA keys must be **at least 4096 bits**
- Generate a **dedicated CI key** rather than using your personal key
- Never commit private keys to the repository

---

## 6. Version Catalog & Build Modernization

### Current State

The project has **no version catalog** — all dependency versions are hardcoded in individual build files. This makes coordinated version bumps error-prone.

### Create `gradle/libs.versions.toml`

```toml
[versions]
kotlin = "1.9.22"
agp = "8.2.2"
compose-compiler = "1.5.8"
compose-bom = "2024.02.00"
junit = "4.13.2"
paparazzi = "1.3.2"
binary-compat = "0.17.0"
maven-publish = "0.30.0"
spotless = "6.25.0"

[libraries]
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-foundation = { module = "androidx.compose.foundation:foundation" }
compose-runtime = { module = "androidx.compose.runtime:runtime" }
compose-material3 = { module = "androidx.compose.material3:material3" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
android-library = { id = "com.android.library", version.ref = "agp" }
android-application = { id = "com.android.application", version.ref = "agp" }
binary-compat = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version.ref = "binary-compat" }
maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "maven-publish" }
paparazzi = { id = "app.cash.paparazzi", version.ref = "paparazzi" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

### Usage in build files

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.binary.compat)
    alias(libs.plugins.maven.publish)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    testImplementation(libs.junit)
}
```

### Convention Plugins (Optional but Recommended)

For shared configuration across the three library modules, create a `build-logic/` composite build:

```
build-logic/
  convention/
    build.gradle.kts
    src/main/kotlin/
      isometric.library.gradle.kts        # shared Android library config
      isometric.publishing.gradle.kts      # shared publishing config
```

This eliminates copy-pasting the `mavenPublishing { }` block across modules.

---

## 7. API Compatibility Tracking

### Kotlin Binary Compatibility Validator

This plugin dumps your library's public ABI (classes, methods, properties) into human-readable `.api` files. CI checks that the current code matches the committed `.api` files — any accidental API break is caught before merge.

#### Setup

**Root `build.gradle.kts`:**
```kotlin
plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.17.0"
}

apiValidation {
    // Exclude non-library subprojects
    ignoredProjects += setOf("app", "isometric-benchmark", "lib")
}
```

#### Usage

```bash
# Generate initial .api dump files
./gradlew apiDump

# Check current code against committed .api files (use in CI)
./gradlew apiCheck
```

#### Output Structure

After `apiDump`, you'll have:
```
isometric-core/api/isometric-core.api
isometric-compose/api/isometric-compose.api
isometric-android-view/api/isometric-android-view.api
```

These files look like:
```
public final class io/github/jayteealao/isometric/Point {
    public fun <init> (DDD)V
    public final fun getX ()D
    public final fun getY ()D
    public final fun getZ ()D
    public final fun translate (DDD)Lio/github/jayteealao/isometric/Point;
}
```

#### Workflow

1. **Normal development:** `apiCheck` runs in CI. If you accidentally expose or remove a public API, CI fails.
2. **Intentional API change:** Run `apiDump` locally, commit the updated `.api` files. The diff in the PR clearly shows what API surface changed.
3. **PR review:** Reviewers see exactly which public APIs were added/removed/changed.

#### Marking Internal APIs

Use the `@InternalApi` annotation pattern:

```kotlin
@RequiresOptIn(
    message = "This is internal API. It may change without notice.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
annotation class InternalIsometricApi
```

Then in `apiValidation`:
```kotlin
apiValidation {
    nonPublicMarkers += setOf("io.github.jayteealao.isometric.InternalIsometricApi")
}
```

Classes/functions annotated with `@InternalIsometricApi` will be excluded from the public API dump.

---

## 8. Conventional Commits

### Specification

Every commit message must follow this format:

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### Types

| Type | Description | SemVer Impact |
|---|---|---|
| `feat` | New feature | MINOR |
| `fix` | Bug fix | PATCH |
| `perf` | Performance improvement | PATCH |
| `refactor` | Code restructuring (no behavior change) | None |
| `docs` | Documentation only | None |
| `test` | Adding/fixing tests | None |
| `ci` | CI/CD changes | None |
| `build` | Build system changes | None |
| `chore` | Maintenance tasks | None |

### Scopes (project-specific)

| Scope | Module/Area |
|---|---|
| `core` | `isometric-core` |
| `compose` | `isometric-compose` |
| `view` | `isometric-android-view` |
| `bench` | `isometric-benchmark` |
| `build` | Gradle build configuration |
| `ci` | GitHub Actions workflows |
| `docs` | Documentation |

### Breaking Changes

Two ways to indicate a breaking change (triggers MAJOR version bump):

```
feat(core)!: redesign Point class to use Float instead of Double

BREAKING CHANGE: Point constructor now takes Float parameters.
All existing code using Double must be updated.
```

Or just the footer:
```
refactor(compose): simplify IsometricScene API

BREAKING CHANGE: IsometricScene no longer accepts a Modifier parameter.
Use IsometricCanvas for the Modifier-based API.
```

### Examples

```
feat(compose): add ForEach composable for dynamic shape lists

fix(core): correct z-sorting for overlapping prisms at same elevation

perf(compose): cache prepared paths to avoid reallocation on redraw

docs: add publishing guide for Maven Central

build: migrate to version catalog (libs.versions.toml)

ci: add release workflow with Maven Central publishing

refactor(core)!: rename IsometricEngine to Renderer

BREAKING CHANGE: IsometricEngine class has been renamed to Renderer.
Update all imports from IsometricEngine to Renderer.
```

### Enforcing Conventional Commits

#### Git Hook (commit-msg)

Create `.githooks/commit-msg`:
```bash
#!/usr/bin/env bash
# Validate conventional commit format
commit_msg=$(cat "$1")
pattern='^(feat|fix|perf|refactor|docs|test|ci|build|chore)(\(.+\))?(!)?: .{1,100}'

if ! echo "$commit_msg" | head -1 | grep -qE "$pattern"; then
  echo ""
  echo "ERROR: Commit message does not follow Conventional Commits format."
  echo ""
  echo "Expected: <type>(<scope>): <description>"
  echo "Types: feat, fix, perf, refactor, docs, test, ci, build, chore"
  echo ""
  echo "Examples:"
  echo "  feat(core): add cylinder shape primitive"
  echo "  fix(compose): prevent crash on empty scene"
  echo "  perf(core)!: replace Double with Float for coordinates"
  echo ""
  echo "Your message:"
  echo "  $commit_msg"
  echo ""
  exit 1
fi
```

#### Gradle task to install hooks

**Root `build.gradle.kts`:**
```kotlin
tasks.register<Copy>("installGitHooks") {
    from(file("${rootProject.rootDir}/.githooks"))
    into(file("${rootProject.rootDir}/.git/hooks"))
    filePermissions {
        unix("rwxr-xr-x")
    }
}

// Auto-install hooks on first build
tasks.named("prepareKotlinBuildScriptModel") {
    dependsOn("installGitHooks")
}
```

---

## 9. Changelog Generation

### Recommended Tool: git-cliff

[git-cliff](https://git-cliff.org) is a language-agnostic changelog generator that understands Conventional Commits. It's fast (Rust-based), highly configurable, and used by major projects.

#### Installation

```bash
# Windows (scoop)
scoop install git-cliff

# macOS (Homebrew)
brew install git-cliff

# Cargo (cross-platform)
cargo install git-cliff
```

#### Configuration

Create `cliff.toml` at the repository root:

```toml
[changelog]
header = """
# Changelog

All notable changes to the Isometric library will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com),
and this project adheres to [Semantic Versioning](https://semver.org).\n
"""

body = """
{%- macro remote_url() -%}
  https://github.com/jayteealao/Isometric
{%- endmacro -%}

{% if version -%}
## [{{ version | trim_start_matches(pat="v") }}] — {{ timestamp | date(format="%Y-%m-%d") }}
{% else -%}
## [Unreleased]
{% endif -%}

{% for group, commits in commits | group_by(attribute="group") %}
### {{ group | striptags | trim | upper_first }}
{% for commit in commits
| filter(attribute="scope")
| sort(attribute="scope") %}
    - **{{ commit.scope }}**: {{ commit.message | upper_first }} ([{{ commit.id | truncate(length=7, end="") }}]({{ self::remote_url() }}/commit/{{ commit.id }}))
{%- endfor -%}
{% for commit in commits %}
{%- if not commit.scope %}
    - {{ commit.message | upper_first }} ([{{ commit.id | truncate(length=7, end="") }}]({{ self::remote_url() }}/commit/{{ commit.id }}))
{% endif -%}
{% endfor -%}
{% endfor %}
"""

footer = ""
trim = true
postprocessors = []

[git]
conventional_commits = true
filter_unconventional = true
split_commits = false
commit_preprocessors = []
commit_parsers = [
    { message = "^feat", group = "<!-- 0 -->Features" },
    { message = "^fix", group = "<!-- 1 -->Bug Fixes" },
    { message = "^perf", group = "<!-- 2 -->Performance" },
    { message = "^refactor", group = "<!-- 3 -->Refactoring" },
    { message = "^doc", group = "<!-- 4 -->Documentation" },
    { message = "^test", group = "<!-- 5 -->Testing" },
    { message = "^ci", group = "<!-- 6 -->CI/CD" },
    { message = "^build", group = "<!-- 7 -->Build" },
    { message = "^chore", skip = true },
    { body = ".*security", group = "<!-- 8 -->Security" },
]
protect_breaking_commits = false
filter_commits = false
tag_pattern = "v[0-9].*"
skip_tags = ""
ignore_tags = ""
topo_order = false
sort_commits = "newest"
```

#### Usage

```bash
# Generate full changelog
git cliff -o CHANGELOG.md

# Generate changelog for unreleased changes only
git cliff --unreleased

# Generate changelog since last tag
git cliff --latest

# Preview what the next version would be (based on commits)
git cliff --bumped-version

# Generate changelog for a specific range
git cliff v1.0.0..v1.1.0
```

#### Integrate with Gradle

Create a Gradle task to generate the changelog before releases:

```kotlin
tasks.register<Exec>("changelog") {
    group = "release"
    description = "Generate CHANGELOG.md from conventional commits"
    commandLine("git", "cliff", "-o", "CHANGELOG.md")
}
```

---

## 10. CI/CD with GitHub Actions

### Workflow Structure

```
.github/
  workflows/
    ci.yml              # Build + test + API check on every PR
    release.yml         # Publish to Maven Central on GitHub Release
    changelog.yml       # Generate changelog on demand
```

### CI Workflow (`.github/workflows/ci.yml`)

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - uses: gradle/actions/setup-gradle@v4

      - name: Build all modules
        run: ./gradlew build

      - name: Check API compatibility
        run: ./gradlew apiCheck

      - name: Run unit tests
        run: ./gradlew test

      - name: Verify Paparazzi snapshots
        run: ./gradlew :isometric-compose:verifyPaparazzi

      - name: Upload test reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: "**/build/reports/tests/"
```

### Release Workflow (`.github/workflows/release.yml`)

```yaml
name: Release

on:
  release:
    types: [published]

jobs:
  publish:
    runs-on: ubuntu-latest
    permissions:
      contents: read

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - uses: gradle/actions/setup-gradle@v4

      - name: Validate version matches tag
        run: |
          TAG="${GITHUB_REF#refs/tags/v}"
          GRADLE_VERSION=$(grep -oP 'version\s*=\s*"\K[^"]+' isometric-core/build.gradle.kts | head -1)
          if [ "$TAG" != "$GRADLE_VERSION" ]; then
            echo "ERROR: Tag v$TAG does not match Gradle version $GRADLE_VERSION"
            exit 1
          fi

      - name: Build and test
        run: ./gradlew build test apiCheck

      - name: Publish to Maven Central
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyId: ${{ secrets.SIGNING_KEY_ID }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_KEY_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_KEY }}
        run: ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

### Changelog Workflow (`.github/workflows/changelog.yml`)

```yaml
name: Update Changelog

on:
  workflow_dispatch:
    inputs:
      version:
        description: "Version to generate changelog for (e.g., v1.1.0)"
        required: false

jobs:
  changelog:
    runs-on: ubuntu-latest
    permissions:
      contents: write

    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0  # Full history needed for git-cliff

      - name: Install git-cliff
        run: |
          curl -sSL https://github.com/orhun/git-cliff/releases/latest/download/git-cliff-x86_64-unknown-linux-gnu.tar.gz | tar xz
          sudo mv git-cliff /usr/local/bin/

      - name: Generate changelog
        run: git cliff -o CHANGELOG.md

      - name: Commit and push
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add CHANGELOG.md
          git diff --staged --quiet || git commit -m "docs: update CHANGELOG.md"
          git push
```

### Required GitHub Secrets

| Secret | Description | Where to get it |
|---|---|---|
| `MAVEN_CENTRAL_USERNAME` | User token username | Central Portal → Account → Generate User Token |
| `MAVEN_CENTRAL_PASSWORD` | User token password | Central Portal → Account → Generate User Token |
| `SIGNING_KEY_ID` | Last 8 characters of GPG key ID | `gpg --list-keys --keyid-format long` |
| `SIGNING_KEY_PASSWORD` | GPG key passphrase | Set during key generation |
| `SIGNING_KEY` | Armored private key (without header/footer lines) | `gpg --armor --export-secret-keys <KEY_ID>` |

---

## 11. Release Strategy

### Trunk-Based Development with Release Tags

This project should use **trunk-based development** — simple, low-overhead, and standard for small-team libraries.

```
main ──●──●──●──●──●──●──●──●──●──●──
            ↑           ↑           ↑
          v1.0.0      v1.1.0      v2.0.0
```

### Rules

| Rule | Detail |
|---|---|
| **`main` is always releasable** | Every commit to `main` should pass CI |
| **Short-lived feature branches** | Branch from `main`, merge back via PR within hours/days |
| **No `develop` branch** | Unnecessary overhead for a small team |
| **Releases are tags on `main`** | Create a GitHub Release → triggers publish workflow |
| **Hotfix branches only when needed** | Only if you need to patch an older major version |

### Release Process (Step by Step)

#### 1. Prepare the release

```bash
# Ensure you're on main and up to date
git checkout main
git pull origin main

# Verify everything passes
./gradlew build test apiCheck
```

#### 2. Bump the version

Update `version` in each publishable module's `build.gradle.kts`:

```kotlin
version = "1.1.0"  // was "1.0.0"
```

Determine the version using conventional commits:
- Any `feat` commits since last release → bump MINOR
- Only `fix`/`perf` commits → bump PATCH
- Any `BREAKING CHANGE` → bump MAJOR

Or use git-cliff to calculate it automatically:
```bash
git cliff --bumped-version
```

#### 3. Update the changelog

```bash
git cliff -o CHANGELOG.md --tag v1.1.0
```

#### 4. Update the API dump

```bash
./gradlew apiDump
```

#### 5. Commit the release

```bash
git add -A
git commit -m "build: prepare release v1.1.0"
git push origin main
```

#### 6. Create the GitHub Release

```bash
# Create a tag and GitHub Release (triggers the publish workflow)
gh release create v1.1.0 \
  --title "v1.1.0" \
  --notes-file <(git cliff --latest --strip header)
```

Or create the release via the GitHub web UI:
1. Go to **Releases** → **Draft a new release**
2. Tag: `v1.1.0` (create on publish)
3. Target: `main`
4. Title: `v1.1.0`
5. Description: paste from `git cliff --latest`
6. Click **Publish release**

The `release.yml` workflow will automatically:
- Validate the tag matches the Gradle version
- Build and test
- Publish all three modules to Maven Central
- Central Portal validates and releases automatically

#### 7. Post-release

After publishing, bump to the next development version:
```bash
# In each module's build.gradle.kts
version = "1.2.0-SNAPSHOT"  # or "1.1.1-SNAPSHOT"
```

```bash
git commit -am "build: bump to next development version"
git push origin main
```

### Hotfix Pattern (When Needed)

If you've already released v1.1.0 and v1.2.0, but need to patch v1.1.x:

```bash
# Create a release branch from the v1.1.0 tag
git checkout -b release/1.1.x v1.1.0

# Cherry-pick or apply the fix
git cherry-pick <fix-commit-hash>

# Bump to 1.1.1, update changelog, commit
# Create GitHub Release from this branch
gh release create v1.1.1 --target release/1.1.x \
  --title "v1.1.1" --notes "Hotfix: ..."
```

### Version Numbering

Follow [Semantic Versioning 2.0.0](https://semver.org):

| Change Type | Version Bump | Example |
|---|---|---|
| Breaking API change | MAJOR | 1.0.0 → 2.0.0 |
| New feature (backward-compatible) | MINOR | 1.0.0 → 1.1.0 |
| Bug fix / performance improvement | PATCH | 1.0.0 → 1.0.1 |

**Pre-1.0.0**: During initial development, the public API is not considered stable. Breaking changes can happen in MINOR bumps. Consider starting at `0.1.0` if the API is not yet finalized.

---

## 12. Pre-Flight Checklist

Complete these items before the first release:

### Legal & Attribution
- [ ] `LICENSE` file preserved at repo root (Apache 2.0)
- [ ] `NOTICE` file created with original + fork attribution
- [ ] Copyright headers added to modified upstream files
- [ ] Copyright headers added to new files

### Namespace & Identity
- [ ] Central Portal account created at [central.sonatype.com](https://central.sonatype.com)
- [ ] Namespace `io.github.jayteealao` verified
- [ ] All source packages renamed from `io.fabianterhorst.*` → `io.github.jayteealao.*`
- [ ] `group` updated in all build files
- [ ] Android `namespace` updated in all modules
- [ ] `applicationId` updated in sample/benchmark apps
- [ ] README updated with new Maven coordinates
- [ ] All `import` statements updated (verify with grep)

### Build & Publish
- [ ] Version catalog created (`gradle/libs.versions.toml`)
- [ ] `vanniktech/gradle-maven-publish-plugin` configured on all 3 library modules
- [ ] POM metadata complete (name, description, url, license, developers, scm)
- [ ] GPG key generated (4096-bit RSA)
- [ ] GPG public key uploaded to keyservers
- [ ] `publishToMavenLocal` succeeds and artifacts look correct
- [ ] Publication tested with a `-SNAPSHOT` version first

### API & Quality
- [ ] Binary compatibility validator configured
- [ ] Initial `.api` dump generated and committed
- [ ] `apiCheck` passes
- [ ] Paparazzi snapshots regenerated (package names changed)
- [ ] All unit tests pass

### Commit & Changelog
- [ ] `.githooks/commit-msg` hook created
- [ ] `installGitHooks` Gradle task wired up
- [ ] `cliff.toml` created and configured
- [ ] Initial `CHANGELOG.md` generated

### CI/CD
- [ ] `.github/workflows/ci.yml` created and passing
- [ ] `.github/workflows/release.yml` created
- [ ] `.github/workflows/changelog.yml` created
- [ ] GitHub Secrets configured (5 secrets)
- [ ] First release published via `gh release create`

### Post-First-Release
- [ ] Verify artifacts appear on [search.maven.org](https://search.maven.org)
- [ ] Test dependency resolution from a fresh project
- [ ] Bump to next `-SNAPSHOT` development version
- [ ] Announce the release (GitHub Discussions, social media, etc.)

---

## References

### Official Documentation
- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) — Full license text
- [Maven Central Requirements](https://central.sonatype.org/publish/requirements/) — What's needed to pass validation
- [Central Publisher Portal Guide](https://central.sonatype.org/publish/publish-portal-guide/) — Step-by-step portal usage
- [Register a Namespace](https://central.sonatype.org/register/namespace/) — Namespace verification process
- [GPG Signing Requirements](https://central.sonatype.org/publish/requirements/gpg/) — Key generation and publishing
- [Conventional Commits v1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) — Full specification
- [Semantic Versioning 2.0.0](https://semver.org) — Version numbering rules
- [Kotlin Binary Compatibility Validator](https://github.com/Kotlin/binary-compatibility-validator) — API tracking plugin

### Tools
- [vanniktech/gradle-maven-publish-plugin](https://github.com/vanniktech/gradle-maven-publish-plugin) — Maven Central publishing for Gradle
- [git-cliff](https://git-cliff.org) — Changelog generator
- [Spotless](https://github.com/diffplug/spotless) — License header management
- [Gradle Version Catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html) — Dependency management

### Guides
- [Gradle Cookbook: Publishing to Maven Central](https://cookbook.gradle.org/integrations/maven-central/publishing/)
- [Simon Scholz: Publish to Maven Central with Gradle](https://simonscholz.dev/tutorials/publish-maven-central-gradle/)
- [Kotlin Gradle Best Practices](https://kotlinlang.org/docs/gradle-best-practices.html)
