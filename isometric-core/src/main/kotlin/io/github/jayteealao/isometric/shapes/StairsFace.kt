package io.github.jayteealao.isometric.shapes

/**
 * Identifies which logical face of a [Stairs] shape a path corresponds to.
 *
 * `Stairs.createPaths()` emits paths in this order for `stepCount` steps:
 *
 * | Index range                    | Face  |
 * |--------------------------------|-------|
 * | `0..(2 * stepCount - 1)` even  | RISER (vertical face of each step) |
 * | `0..(2 * stepCount - 1)` odd   | TREAD (horizontal face of each step) |
 * | `2 * stepCount`                | SIDE (zigzag left side)              |
 * | `2 * stepCount + 1`            | SIDE (zigzag right side)             |
 *
 * The three values intentionally group all risers, all treads, and both side walls
 * so per-face materials can address "all risers" without enumerating every step.
 */
enum class StairsFace {
    RISER,
    TREAD,
    SIDE;

    public companion object {
        /**
         * Returns the [StairsFace] for the given 0-based path index within `Stairs.paths`
         * for a staircase with [stepCount] steps.
         *
         * @param index path index in `Stairs.paths`
         * @param stepCount number of steps in the staircase (must be >= 1)
         * @throws IllegalArgumentException if [index] is outside the valid range for the given [stepCount]
         */
        public fun fromPathIndex(index: Int, stepCount: Int): StairsFace {
            require(stepCount >= 1) {
                "stepCount must be at least 1, got $stepCount"
            }
            val totalPaths = 2 * stepCount + 2
            require(index in 0 until totalPaths) {
                "Stairs path index must be 0..${totalPaths - 1} for stepCount=$stepCount, got $index"
            }
            return when {
                index >= 2 * stepCount -> SIDE
                index % 2 == 0 -> RISER
                else -> TREAD
            }
        }
    }
}
