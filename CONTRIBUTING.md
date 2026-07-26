# Contributing to Isometric

Thank you for your interest in contributing!

## Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/jayteealao/Isometric.git
   cd Isometric
   ```

2. Open in Android Studio (Hedgehog or newer recommended).

3. Sync Gradle and ensure the project builds:
   ```bash
   ./gradlew build
   ```

## Running Tests

### Unit tests

```bash
./gradlew :isometric-core:test
./gradlew :isometric-compose:testDebugUnitTest
```

> `isometric-core`'s allocation regression tests (`*AllocationTest`, `FaceKeyMemoTest`) require
> a JVM that supports `com.sun.management.ThreadMXBean` per-thread allocation measurement
> (HotSpot/OpenJDK, e.g. Temurin — the toolchain default). They fail closed by design: if a
> failure message mentions "allocation measurement unavailable" or "unsupported", that means
> your JVM can't measure allocations, not that you've introduced a regression. Run with a
> HotSpot-based JDK (Temurin, etc.) to avoid this.

### Paparazzi snapshot tests

```bash
./gradlew :isometric-compose:recordPaparazziDebug   # Generate/update golden images
./gradlew :isometric-compose:verifyPaparazziDebug    # Verify against golden images
```

### Sample app

```bash
./gradlew :app:installDebug
```

## API Compatibility

The public API of `isometric-core`, `isometric-compose`, and `isometric-android-view` is tracked
with the binary-compatibility validator. CI runs `apiCheck` on every PR, so verify it locally:

```bash
./gradlew apiCheck
```

If you intentionally changed the public API, regenerate the committed `.api` dumps and include
them in your commit:

```bash
./gradlew apiDump
```

## Documentation Changes

The canonical documentation source is the `.mdx` files under `site/src/content/docs/`.
The `docs/` folder is a generated mirror for GitHub browsing — never edit its `.md`
files directly. After editing any `.mdx`, regenerate the mirror and commit both:

```bash
node scripts/sync-docs.js
```

See [docs/contributing/docs-guide.md](docs/contributing/docs-guide.md) for the full
documentation workflow.

## Submitting Changes

1. Create a branch from `master` (or the current development branch).
2. Make your changes.
3. Ensure tests and `./gradlew apiCheck` pass.
4. Submit a pull request with a clear description of what changed and why.

## Code Style

- Follow standard Kotlin conventions.
- Add KDoc to all new public API.
- Keep commits focused — one logical change per commit.
- Write commit messages in [Conventional Commits](https://www.conventionalcommits.org/)
  format (`feat: …`, `fix(compose): …`, `docs: …`). The changelog is generated from
  commit messages by git-cliff, so a malformed subject line produces a malformed
  changelog entry.

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE).
