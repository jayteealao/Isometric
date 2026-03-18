package io.github.jayteealao.isometric

/**
 * The world axis along which a [Stack][io.github.jayteealao.isometric.compose.runtime.Stack]
 * composable arranges its children.
 *
 * In isometric view:
 * - [Z] is vertical (up/down on screen) — the most common stacking direction.
 * - [X] runs right-and-forward on screen.
 * - [Y] runs left-and-forward on screen.
 *
 * Pass to `Stack` via the `axis` parameter. Defaults to [Z].
 */
enum class StackAxis {
    /** Stack along the world X axis (right-and-forward in isometric view). */
    X,

    /** Stack along the world Y axis (left-and-forward in isometric view). */
    Y,

    /** Stack along the world Z axis (vertical — the default stacking direction). */
    Z;

    /**
     * Returns the unit [Point] for this axis.
     *
     * Each component is 0.0 except the component for this axis, which is 1.0.
     * Multiply by child index and `gap` to compute the child's position offset:
     *
     * ```kotlin
     * val unit = StackAxis.Z.unitPoint()  // Point(0.0, 0.0, 1.0)
     * val childPosition = Point(unit.x * index * gap, unit.y * index * gap, unit.z * index * gap)
     * ```
     *
     * Also useful for diagonal stacking — add two axis unit points and use the
     * result as a direction vector into a nested [Group][io.github.jayteealao.isometric.compose.runtime.Group] position.
     */
    fun unitPoint(): Point = when (this) {
        X -> Point(1.0, 0.0, 0.0)
        Y -> Point(0.0, 1.0, 0.0)
        Z -> Point(0.0, 0.0, 1.0)
    }
}
