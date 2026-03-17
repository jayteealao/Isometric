package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.SceneProjector

/**
 * CompositionLocal for providing the default [IsoColor] applied to shapes that do not
 * specify an explicit color.
 *
 * Override with [androidx.compose.runtime.CompositionLocalProvider] to change the
 * default for an entire subtree:
 * ```
 * CompositionLocalProvider(LocalDefaultColor provides IsoColor.GREEN) {
 *     IsometricScene { Shape(geometry = Prism(Point.ORIGIN)) } // renders green
 * }
 * ```
 *
 * Defaults to Material Blue (`IsoColor(33, 150, 243)`).
 */
val LocalDefaultColor = staticCompositionLocalOf {
    IsoColor(33.0, 150.0, 243.0) // Default blue
}

/**
 * CompositionLocal for providing the directional-light vector used to shade faces.
 *
 * Each face's brightness is computed from the dot product of its normal and this
 * direction vector. Override it to simulate different lighting setups (e.g. top-down
 * illumination or dramatic side-lighting).
 *
 * Defaults to `SceneProjector.DEFAULT_LIGHT_DIRECTION.normalize()` which produces
 * classic top-right isometric lighting.
 */
val LocalLightDirection = staticCompositionLocalOf {
    SceneProjector.DEFAULT_LIGHT_DIRECTION.normalize()
}

/**
 * CompositionLocal for providing [RenderOptions] that control depth sorting,
 * back-face culling, bounds checking, and broad-phase sort behaviour.
 *
 * Override to tune performance or disable specific rendering passes for a subtree.
 * See [RenderOptions.Default], [RenderOptions.NoDepthSorting], and
 * [RenderOptions.NoCulling] for common presets.
 *
 * Defaults to [RenderOptions.Default].
 */
val LocalRenderOptions = staticCompositionLocalOf {
    RenderOptions.Default
}

/**
 * CompositionLocal for providing the current [StrokeStyle] to shapes in the scene.
 *
 * Defaults to [StrokeStyle.FillAndStroke] with the default stroke width and color.
 */
val LocalStrokeStyle = staticCompositionLocalOf<StrokeStyle> {
    StrokeStyle.FillAndStroke()
}

/**
 * A palette of named semantic colors for theming an isometric scene.
 *
 * Provides a small, fixed set of color roles that shapes and composables can
 * reference for consistent visual styling. Create a customised palette via the
 * constructor or by calling [copy] on an existing instance.
 *
 * Marked as [@Immutable] to prevent unnecessary recomposition when instances
 * do not change.
 *
 * @param primary The main brand color, used for prominent shapes. Defaults to blue.
 * @param secondary A complementary color for secondary elements. Defaults to orange.
 * @param accent A highlight color for interactive or attention-drawing shapes. Defaults to green.
 * @param background The scene background color. Defaults to light grey.
 * @param surface Color for surface-level elements such as floors or panels. Defaults to white.
 * @param error Color used to indicate error states. Defaults to red.
 */
@Immutable
class ColorPalette(
    val primary: IsoColor = IsoColor(33.0, 150.0, 243.0),
    val secondary: IsoColor = IsoColor(255.0, 100.0, 0.0),
    val accent: IsoColor = IsoColor(0.0, 200.0, 100.0),
    val background: IsoColor = IsoColor(245.0, 245.0, 245.0),
    val surface: IsoColor = IsoColor(255.0, 255.0, 255.0),
    val error: IsoColor = IsoColor(244.0, 67.0, 54.0)
) {
    fun copy(
        primary: IsoColor = this.primary,
        secondary: IsoColor = this.secondary,
        accent: IsoColor = this.accent,
        background: IsoColor = this.background,
        surface: IsoColor = this.surface,
        error: IsoColor = this.error
    ): ColorPalette = ColorPalette(primary, secondary, accent, background, surface, error)

    override fun equals(other: Any?): Boolean =
        other is ColorPalette &&
            primary == other.primary &&
            secondary == other.secondary &&
            accent == other.accent &&
            background == other.background &&
            surface == other.surface &&
            error == other.error

    override fun hashCode(): Int {
        var result = primary.hashCode()
        result = 31 * result + secondary.hashCode()
        result = 31 * result + accent.hashCode()
        result = 31 * result + background.hashCode()
        result = 31 * result + surface.hashCode()
        result = 31 * result + error.hashCode()
        return result
    }

    override fun toString(): String =
        "ColorPalette(primary=$primary, secondary=$secondary, accent=$accent, background=$background, surface=$surface, error=$error)"
}

/**
 * CompositionLocal for providing a [ColorPalette] of named semantic colors.
 *
 * Shapes and composables can read `LocalColorPalette.current` to resolve
 * role-based colors (`primary`, `secondary`, `accent`, etc.) for consistent
 * theming across a scene.
 *
 * Override with [androidx.compose.runtime.CompositionLocalProvider] to apply
 * a custom palette:
 * ```
 * val dark = ColorPalette(primary = IsoColor(30, 30, 30), background = IsoColor.BLACK)
 * CompositionLocalProvider(LocalColorPalette provides dark) {
 *     IsometricScene { ... }
 * }
 * ```
 *
 * Defaults to [ColorPalette] with its built-in color defaults.
 */
val LocalColorPalette = staticCompositionLocalOf {
    ColorPalette()
}

/**
 * CompositionLocal for providing benchmark hooks to the renderer.
 *
 * Uses [staticCompositionLocalOf] because hooks are set once per benchmark run
 * and rarely change, avoiding unnecessary recomposition tracking.
 * Defaults to null (no instrumentation) in production.
 */
val LocalBenchmarkHooks = staticCompositionLocalOf<RenderBenchmarkHooks?> { null }

/**
 * CompositionLocal for providing the [IsometricEngine] to child composables.
 *
 * Uses [staticCompositionLocalOf] because the engine instance is set once per
 * scene and never changes — avoiding unnecessary recomposition tracking.
 *
 * Access within an IsometricScope:
 * ```
 * val engine = LocalIsometricEngine.current
 * val screenPos = engine.worldToScreen(point, viewportWidth, viewportHeight)
 * ```
 */
val LocalIsometricEngine = staticCompositionLocalOf<IsometricEngine> {
    error("No IsometricEngine provided. LocalIsometricEngine must be used within an IsometricScene.")
}
