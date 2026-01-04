package io.fabianterhorst.isometric.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.compose.runtime.*
import io.fabianterhorst.isometric.shapes.Prism
import io.fabianterhorst.isometric.shapes.Pyramid
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * This file demonstrates the DIFFERENCE between using:
 * 1. High-level API (Shape, Group, etc.)
 * 2. Low-level primitives (ComposeNode directly)
 *
 * Both work! Choose based on your needs.
 */

// ============================================================================
// EXAMPLE 1: Simple Shape
// ============================================================================

/**
 * HIGH-LEVEL API: Easy and concise
 */
@Composable
fun IsometricScope.SimpleShapeHighLevel() {
    Shape(
        shape = Prism(Point(0.0, 0.0, 0.0)),
        color = IsoColor(255.0, 0.0, 0.0)
    )
}

/**
 * LOW-LEVEL PRIMITIVE: Same result, more control
 */
@Composable
fun SimpleShapeLowLevel() {
    ComposeNode<ShapeNode, IsometricApplier>(
        factory = {
            ShapeNode(
                shape = Prism(Point(0.0, 0.0, 0.0)),
                color = IsoColor(255.0, 0.0, 0.0)
            )
        },
        update = {
            // Empty - no dynamic updates in this simple case
        }
    )
}

// ============================================================================
// EXAMPLE 2: Animated Shape
// ============================================================================

/**
 * HIGH-LEVEL API: Automatic handling of state changes
 */
@Composable
fun IsometricScope.AnimatedShapeHighLevel() {
    var rotation by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            rotation += PI / 90
        }
    }

    Shape(
        shape = Prism(Point(0.0, 0.0, 0.0)),
        color = IsoColor(255.0, 0.0, 0.0),
        rotation = rotation  // Automatically triggers update
    )
}

/**
 * LOW-LEVEL PRIMITIVE: Manual control over updates
 */
@Composable
fun AnimatedShapeLowLevel() {
    var rotation by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            rotation += PI / 90
        }
    }

    ComposeNode<ShapeNode, IsometricApplier>(
        factory = {
            ShapeNode(
                shape = Prism(Point(0.0, 0.0, 0.0)),
                color = IsoColor(255.0, 0.0, 0.0)
            )
        },
        update = {
            // YOU control exactly how rotation updates work
            set(rotation) {
                this.rotation = it
                markDirty()
            }
        }
    )
}

// ============================================================================
// EXAMPLE 3: Custom Update Logic (Low-Level Only Feature)
// ============================================================================

/**
 * This demonstrates something you CAN'T do with high-level API:
 * Custom update logic that only updates when change is significant
 */
@Composable
fun SmartUpdatingShape(rotation: Double) {
    ComposeNode<ShapeNode, IsometricApplier>(
        factory = {
            ShapeNode(
                shape = Prism(Point(0.0, 0.0, 0.0)),
                color = IsoColor(255.0, 0.0, 0.0)
            )
        },
        update = {
            set(rotation) { newRotation ->
                // Only update if rotation changed by at least 0.1 radians
                val currentRotation = this.rotation
                if (kotlin.math.abs(newRotation - currentRotation) > 0.1) {
                    this.rotation = newRotation
                    markDirty()
                    println("Updated rotation: $newRotation")
                } else {
                    println("Skipped update (change too small)")
                }
            }
        }
    )
}

// ============================================================================
// EXAMPLE 4: Custom Node Type (Advanced Low-Level)
// ============================================================================

/**
 * Create a completely custom node type
 */
class MultiShapeNode(
    var shapes: List<io.fabianterhorst.isometric.Shape>,
    var colors: List<IsoColor>
) : IsometricNode() {
    override val children = mutableListOf<IsometricNode>()

    override fun render(context: RenderContext): List<io.fabianterhorst.isometric.RenderCommand> {
        if (!isVisible) return emptyList()

        // Render all shapes with their respective colors
        return shapes.zip(colors).flatMap { (shape, color) ->
            var transformedShape = context.applyTransformsToShape(shape)
            transformedShape = transformedShape.translate(position.x, position.y, position.z)

            if (rotation != 0.0) {
                val origin = rotationOrigin ?: position
                transformedShape = transformedShape.rotateZ(origin, rotation)
            }

            transformedShape.paths.map { path ->
                io.fabianterhorst.isometric.RenderCommand(
                    id = "${nodeId}_${shape.hashCode()}_${path.hashCode()}",
                    points = emptyList(),
                    color = color,
                    originalPath = path,
                    originalShape = transformedShape
                )
            }
        }
    }

    override fun hitTest(x: Double, y: Double, context: RenderContext): IsometricNode? {
        if (!isVisible) return null
        return null
    }
}

/**
 * Composable wrapper for custom node
 */
@IsometricComposable
@Composable
fun IsometricScope.MultiShape(
    shapes: List<io.fabianterhorst.isometric.Shape>,
    colors: List<IsoColor>,
    rotation: Double = 0.0
) {
    ComposeNode<MultiShapeNode, IsometricApplier>(
        factory = { MultiShapeNode(shapes, colors) },
        update = {
            set(shapes) { this.shapes = it; markDirty() }
            set(colors) { this.colors = it; markDirty() }
            set(rotation) { this.rotation = it; markDirty() }
        }
    )
}

// ============================================================================
// EXAMPLE 5: Performance Optimization (Low-Level)
// ============================================================================

/**
 * Batch multiple property updates to mark dirty only once
 */
@Composable
fun OptimizedBatchUpdate(
    shape: io.fabianterhorst.isometric.Shape,
    color: IsoColor,
    rotation: Double,
    scale: Double
) {
    ComposeNode<ShapeNode, IsometricApplier>(
        factory = { ShapeNode(shape, color) },
        update = {
            // Update all properties
            update {
                // Update multiple properties
                this.shape = shape
                this.color = color
                this.rotation = rotation
                this.scale = scale

                // Mark dirty only ONCE for all changes
                markDirty()
            }
        }
    )
}

// ============================================================================
// EXAMPLE 6: Mixing Both Levels
// ============================================================================

/**
 * You can freely mix high-level and low-level in the same scene!
 */
@Composable
fun MixedLevelExample() {
    var angle by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            angle += PI / 90
        }
    }

    IsometricScene {
        // HIGH-LEVEL: Easy to use for standard shapes
        Shape(
            shape = Prism(Point(-2.0, 0.0, 0.0)),
            color = IsoColor(255.0, 0.0, 0.0)
        )

        // HIGH-LEVEL: Groups work great for hierarchies
        Group(rotation = angle) {
            Shape(
                shape = Pyramid(Point(0.0, 0.0, 0.0)),
                color = IsoColor(0.0, 255.0, 0.0)
            )
        }

        // LOW-LEVEL: Custom behavior when needed
        SmartUpdatingShape(rotation = angle)

        // CUSTOM NODE: Completely new node type
        MultiShape(
            shapes = listOf(
                Prism(Point(2.0, 0.0, 0.0)),
                Prism(Point(2.5, 0.0, 0.0))
            ),
            colors = listOf(
                IsoColor(0.0, 0.0, 255.0),
                IsoColor(0.0, 255.0, 255.0)
            ),
            rotation = angle
        )
    }
}

// ============================================================================
// EXAMPLE 7: When to Use Which?
// ============================================================================

/**
 * USE HIGH-LEVEL when:
 * - Building standard scenes
 * - You want clean, readable code
 * - You want sensible defaults
 * - Learning the library
 */
@Composable
fun StandardUseCase() {
    IsometricScene {
        Shape(Prism(Point(0, 0, 0)), IsoColor(255, 0, 0))
        Group(rotation = PI / 4) {
            Shape(Pyramid(Point(1, 0, 0)), IsoColor(0, 255, 0))
        }
    }
}

/**
 * USE LOW-LEVEL when:
 * - You need custom update behavior
 * - Performance optimization is critical
 * - Building your own library/framework
 * - Creating custom node types
 */
@Composable
fun AdvancedUseCase() {
    var rotation by remember { mutableStateOf(0.0) }

    ComposeNode<ShapeNode, IsometricApplier>(
        factory = { ShapeNode(Prism(Point(0, 0, 0)), IsoColor(255, 0, 0)) },
        update = {
            set(rotation) {
                // Custom logic: only update every 0.5 radians
                val snappedRotation = (it / 0.5).toInt() * 0.5
                if (this.rotation != snappedRotation) {
                    this.rotation = snappedRotation
                    markDirty()
                }
            }
        }
    )
}

// ============================================================================
// SUMMARY
// ============================================================================

/*

HIGH-LEVEL API:
    Shape(...)        // ✅ Use this 95% of the time
    Group(...)        // ✅ Clean and easy
    Path(...)         // ✅ Sensible defaults

LOW-LEVEL PRIMITIVES:
    ComposeNode<ShapeNode, IsometricApplier>(...)  // ✅ Use when you need:
        - Custom update logic
        - Performance optimization
        - New node types
        - Precise control

MIXING BOTH:
    IsometricScene {
        Shape(...)                    // High-level
        MyCustomPrimitive(...)        // Low-level
        Group(...) {                  // High-level
            CustomNode(...)           // Low-level
        }
    }

    ✅ This is totally fine and encouraged!

*/
