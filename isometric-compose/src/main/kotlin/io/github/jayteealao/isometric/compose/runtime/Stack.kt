package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.StackAxis

/**
 * Renders [count] children arranged along [axis] at equal [gap] spacing.
 *
 * Each child is placed inside a [Group] whose position is `axis × index × gap`
 * from world origin. Content renders relative to that Group's local origin —
 * a [Shape] at the default position appears at the item's slot without any
 * additional offset.
 *
 * ```kotlin
 * // Five-floor building — each floor is 1 world unit above the previous
 * Stack(count = 5, axis = StackAxis.Z, gap = 1.0) { floor ->
 *     Shape(geometry = Prism(), color = floorColors[floor])
 * }
 *
 * // Row of eight columns spaced 1.5 world units apart along X
 * Stack(count = 8, axis = StackAxis.X, gap = 1.5) { _ ->
 *     Shape(geometry = Cylinder())
 * }
 *
 * // To position the whole stack, wrap in a Group:
 * Group(position = Point(2.0, 3.0, 0.0)) {
 *     Stack(count = 4, axis = StackAxis.Z, gap = 1.0) { floor -> … }
 * }
 * ```
 *
 * **Negative gap**: Reverses the stacking direction.
 * `axis = StackAxis.Z, gap = -1.0` stacks downward from world origin.
 *
 * **Nested stacks**: Two `Stack` composables can be nested to form a 2D
 * arrangement. For uniform interactive tile grids, prefer [TileGrid] — it
 * provides tap routing and is optimised for the common case.
 *
 * **Variable spacing**: For items that need non-uniform spacing (e.g., items
 * of varying height stacked flush), use [ForEach] with a running offset
 * accumulator and explicit [Group] positioning.
 *
 * @param count Number of children to render. Must be ≥ 1.
 * @param axis The world axis along which children are arranged.
 *   Defaults to [StackAxis.Z] (vertical — the most common stacking direction).
 * @param gap World-unit distance between consecutive child origins.
 *   Must be non-zero and finite. Negative values reverse the stacking direction.
 * @param content Composable block called once per child, receiving the
 *   child's zero-based [index]. Rendered in the child's local coordinate
 *   space — position (0,0,0) is the child's Group origin.
 * @see StackAxis
 * @see Group
 * @see TileGrid
 */
@IsometricComposable
@Composable
fun IsometricScope.Stack(
    count: Int,
    axis: StackAxis = StackAxis.Z,
    gap: Double = 1.0,
    content: @Composable IsometricScope.(index: Int) -> Unit
) {
    require(count >= 1) { "Stack count must be at least 1, got $count" }
    require(gap.isFinite()) { "Stack gap must be finite, got $gap" }
    require(gap != 0.0) { "Stack gap must be non-zero, got $gap" }

    // Stable list allocation — avoids reallocating on every recomposition.
    val indices = remember(count) { (0 until count).toList() }

    // Hoist unitPoint() outside ForEach — constant for a given axis.
    val unit = axis.unitPoint()

    ForEach(items = indices, key = { it }) { index ->
        val position = Point(
            x = unit.x * index * gap,
            y = unit.y * index * gap,
            z = unit.z * index * gap
        )
        Group(position = position) {
            content(index)
        }
    }
}
