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

### Paparazzi snapshot tests
```bash
./gradlew :isometric-compose:recordPaparazziDebug   # Generate/update golden images
./gradlew :isometric-compose:verifyPaparazziDebug    # Verify against golden images
```

### Sample app
```bash
./gradlew :app:installDebug
```

## Submitting Changes

1. Create a branch from `master` (or the current development branch).
2. Make your changes.
3. Ensure all tests pass.
4. Submit a pull request with a clear description of what changed and why.

## Code Style

- Follow standard Kotlin conventions.
- Add KDoc to all new public API.
- Keep commits focused — one logical change per commit.

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE).
