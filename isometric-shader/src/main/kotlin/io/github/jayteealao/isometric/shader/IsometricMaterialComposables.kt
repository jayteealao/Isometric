package io.github.jayteealao.isometric.shader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReusableComposeNode
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.compose.runtime.IsometricApplier
import io.github.jayteealao.isometric.compose.runtime.IsometricComposable
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.LocalDefaultColor
import io.github.jayteealao.isometric.compose.runtime.ShapeNode
import io.github.jayteealao.isometric.compose.runtime.UvCoordProvider


/**
 * Add a 3D shape with a material to the isometric scene.
 *
 * This overload accepts an [IsometricMaterial] instead of an [IsoColor].
 * For textured or per-face materials, [LocalDefaultColor] is used as the
 * base color and the material is set on the node for the renderer to interpret.
 *
 * @param geometry The 3D shape to render
 * @param material The material describing how faces should be painted
 * @param alpha Opacity multiplier (0 = fully transparent, 1 = fully opaque).
 *   Applied to the shape's overall opacity. For textured materials, `alpha` scales the
 *   composite opacity; tint alpha is controlled via [IsometricMaterial.Textured.tint].
 * @param position Local position offset
 * @param rotation Local rotation around Z axis
 * @param scale Local scale factor
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the shape is visible
 * @param onClick Callback invoked when this shape is tapped
 * @param onLongClick Callback invoked when this shape is long-pressed
 * @param testTag Optional tag for testing and diagnostics
 * @param nodeId Optional stable identifier. Must be unique within the scene when provided.
 * @see io.github.jayteealao.isometric.compose.runtime.Shape
 */
@IsometricComposable
@Composable
fun IsometricScope.Shape(
    geometry: Shape,
    material: IsometricMaterial,
    alpha: Float = 1f,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    testTag: String? = null,
    nodeId: String? = null,
) {
    val color = material.baseColor()
    // UV provider: dispatched by shape type through uvCoordProviderForShape(). Each
    // shape slice adds its own branch there; shapes without per-face UV support
    // (currently everything except Prism) return null and fall back to flat color.
    val needsUvs = material is IsometricMaterial.Textured ||
        material is IsometricMaterial.PerFace
    val uvProvider: UvCoordProvider? = if (needsUvs) uvCoordProviderForShape(geometry) else null

    ReusableComposeNode<ShapeNode, IsometricApplier>(
        factory = {
            ShapeNode(geometry, color).also {
                it.material = material
                it.uvProvider = uvProvider
            }
        },
        update = {
            set(geometry) { this.shape = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(material) { this.material = it; markDirty() }
            set(uvProvider) { this.uvProvider = it; markDirty() }
            set(alpha) { this.alpha = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) {
                require(it.isFinite()) { "rotation must be finite, got $it" }
                this.rotation = it; markDirty()
            }
            set(scale) {
                require(it.isFinite() && it > 0.0) { "scale must be positive and finite, got $it" }
                this.scale = it; markDirty()
            }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
            set(onClick) { this.onClick = it }
            set(onLongClick) { this.onLongClick = it }
            set(testTag) { this.testTag = it }
            set(nodeId) { this.explicitNodeId = it }
        }
    )
}

