package io.github.jayteealao.isometric

/**
 * Snapshot of the isometric projection and lighting parameters.
 *
 * Extracted from [IsometricProjection] for use by GPU backends that replicate the CPU
 * projection pipeline in shader uniform buffers. The four transformation coefficients
 * map directly to [IsometricProjection.translatePointInto]:
 *
 * ```
 * screenX =  originX + x * t00 + y * t10
 * screenY =  originY - x * t01 - y * t11 - z * scale
 * ```
 *
 * where `originX = viewportWidth / 2.0` and `originY = viewportHeight * 0.9`.
 *
 * Obtain via [IsometricEngine.projectionParams]. The value is stable until [IsometricEngine.angle]
 * or [IsometricEngine.scale] is mutated; use [IsometricEngine.projectionVersion] to detect changes.
 */
data class ProjectionParams(
    /** `scale × cos(angle)` — x-axis contribution to screenX. */
    val t00: Double,
    /** `scale × sin(angle)` — x-axis contribution to screenY (negated in projection). */
    val t01: Double,
    /** `scale × cos(π − angle)` — y-axis contribution to screenX. */
    val t10: Double,
    /** `scale × sin(π − angle)` — y-axis contribution to screenY (negated in projection). */
    val t11: Double,
    /** Pixels per world unit. Applied to the z-axis: `screenY -= z × scale`. */
    val scale: Double,
    /** Brightness multiplier for directional lighting: `brightness × colorDifference`. */
    val colorDifference: Double,
    /** Light tint applied to illuminated surfaces during color transform. */
    val lightColor: IsoColor,
)
