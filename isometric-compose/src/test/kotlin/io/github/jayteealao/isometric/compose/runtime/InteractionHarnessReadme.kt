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
 */
package io.github.jayteealao.isometric.compose.runtime
