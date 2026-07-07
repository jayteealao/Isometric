/**
 * Interaction tests in this module run as plain JVM unit tests that exercise the
 * gesture callbacks and read back the resulting state. They do **not** drive real
 * pointer input through a Compose test rule.
 *
 * ## Why not Robolectric + `createComposeRule`?
 *
 * Robolectric together with `compose-ui-test-junit4` and `performTouchInput` can
 * simulate pointer gestures on the JVM in isolation. It cannot be used here, because
 * this module already applies the Paparazzi plugin for screenshot tests. Paparazzi and
 * Robolectric each install their own Android JVM environment and intercept Android
 * class loading, so the two cannot coexist in a single Gradle module's test source set.
 * Hosting Robolectric tests would require splitting them into a separate module — added
 * build complexity with no benefit, since the callback/state approach below covers every
 * interaction contract and keeps CI device-free.
 *
 * ## The pattern
 *
 * 1. Construct the relevant config (for example [GestureConfig]) with lambdas that record
 *    into a local collection, or that mutate a captured state holder.
 * 2. Invoke the callback directly with a constructed event value
 *    (`config.onDrag?.invoke(DragEvent(5.0, 10.0))`), or call the state API directly
 *    ([CameraState.pan]).
 * 3. Assert on the recorded events or the resulting state with Truth.
 *
 * No `@RunWith(RobolectricTestRunner)`, no `createComposeRule`, no Android resources.
 * Tests live under `src/test/` alongside the other JVM unit tests, following the
 * conventions in `WS6EscapeHatchesTest`.
 *
 * ## Minimal template
 *
 * ```kotlin
 * val recorded = mutableListOf<DragEvent>()
 * val config = GestureConfig(onDrag = { recorded.add(it) })
 * config.onDrag?.invoke(DragEvent(5.0, 10.0))
 * assertThat(recorded).hasSize(1)
 * assertThat(recorded[0].x).isEqualTo(5.0)
 * ```
 *
 * See `AutopanDeltaContractTest` for the worked example: it locks the drag/autopan
 * delta-accumulation contract using exactly this approach.
 *
 * ## Double-tap test split
 *
 * The double-tap disambiguation fix (merged gesture handler) introduces two test levels:
 *
 * - **`DoubleTapDisambiguationTest`** (this JVM source set) — state-machine-only. Verifies what
 *   happens WHEN the coordinated handler fires the correct callbacks, by invoking them directly.
 *   Does NOT prove that real pointer events route to the right lambda.
 *
 * - **`DoubleTapInstrumentedTest`** (`src/androidTest/`) — live-routing. Uses `createComposeRule`
 *   and `performTouchInput { doubleClick() }` on a real emulator/device. This is the AC-S3 gate:
 *   run via `./gradlew :isometric-compose:connectedDebugAndroidTest`.
 *
 * The split is intentional: the JVM tier covers all callback-count, branch-condition, and
 * state-reset assertions (no environment dependency); the instrumented tier covers the one thing
 * JVM cannot — that real Compose pointer routing dispatches to the right lambdas.
 */
package io.github.jayteealao.isometric.compose.runtime
