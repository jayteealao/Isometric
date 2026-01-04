package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.Point2D
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand

/**
 * Renderer that converts the isometric node tree to visual output
 * using the IsometricEngine for projection and depth sorting
 */
class IsometricRenderer(
    private val engine: IsometricEngine
) {
    /**
     * Cached prepared scene (invalidated when root is dirty)
     */
    private var cachedPreparedScene: PreparedScene? = null
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cacheValid: Boolean = false

    /**
     * Render the node tree to a DrawScope
     */
    fun DrawScope.render(
        rootNode: GroupNode,
        context: RenderContext,
        strokeWidth: Float = 1f,
        drawStroke: Boolean = true
    ) {
        val width = size.width.toInt()
        val height = size.height.toInt()

        // Check if we need to regenerate the scene
        val needsUpdate = rootNode.isDirty ||
                !cacheValid ||
                width != cachedWidth ||
                height != cachedHeight

        if (needsUpdate) {
            // Clear engine
            engine.clear()

            // Collect all render commands from the tree
            val commands = rootNode.render(context)

            // Add commands to engine
            commands.forEach { command ->
                engine.add(command.originalPath, command.color, command.originalShape)
            }

            // Prepare scene (projects 3D -> 2D, sorts by depth)
            cachedPreparedScene = engine.prepare(
                width = width,
                height = height,
                options = context.renderOptions
            )

            cachedWidth = width
            cachedHeight = height
            cacheValid = true

            // Clear dirty flags
            rootNode.clearDirty()
        }

        // Render the prepared scene
        cachedPreparedScene?.let { scene ->
            renderPreparedScene(scene, strokeWidth, drawStroke)
        }
    }

    /**
     * Render a prepared scene to DrawScope
     */
    private fun DrawScope.renderPreparedScene(
        scene: PreparedScene,
        strokeWidth: Float,
        drawStroke: Boolean
    ) {
        scene.commands.forEach { command ->
            val path = command.toComposePath()
            val color = command.color.toComposeColor()

            // Draw fill
            drawPath(path, color, style = Fill)

            // Optionally draw stroke
            if (drawStroke) {
                drawPath(
                    path,
                    Color.Black.copy(alpha = 0.1f),
                    style = Stroke(width = strokeWidth)
                )
            }
        }
    }

    /**
     * Perform hit testing on the rendered scene
     */
    fun hitTest(
        rootNode: GroupNode,
        x: Double,
        y: Double,
        context: RenderContext
    ): IsometricNode? {
        // Ensure scene is prepared
        if (cachedPreparedScene == null || !cacheValid) {
            return null
        }

        // Use engine's hit testing to find which render command was hit
        val hitCommand = engine.findItemAt(
            preparedScene = cachedPreparedScene!!,
            x = x,
            y = y,
            reverseSort = true,
            useRadius = true,
            radius = 8.0
        )

        if (hitCommand == null) {
            return null
        }

        // Find the node that produced this render command
        // We'll need to traverse the tree and match by ID
        return findNodeById(rootNode, hitCommand.id)
    }

    /**
     * Find a node by its ID or render command ID
     */
    private fun findNodeById(node: IsometricNode, id: String): IsometricNode? {
        // Check if this node's ID matches or if any of its render commands match
        if (id.startsWith(node.nodeId)) {
            return node
        }

        // Recursively search children
        for (child in node.children) {
            val found = findNodeById(child, id)
            if (found != null) return found
        }

        return null
    }

    /**
     * Invalidate cache (call when render options change)
     */
    fun invalidate() {
        cacheValid = false
        cachedPreparedScene = null
    }
}

/**
 * Convert RenderCommand to Compose Path
 */
private fun RenderCommand.toComposePath(): Path {
    return Path().apply {
        if (points.isEmpty()) return@apply

        moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (i in 1 until points.size) {
            lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        }
        close()
    }
}

/**
 * Convert IsoColor to Compose Color
 */
private fun io.fabianterhorst.isometric.IsoColor.toComposeColor(): Color {
    return Color(
        red = (r.toFloat() / 255f).coerceIn(0f, 1f),
        green = (g.toFloat() / 255f).coerceIn(0f, 1f),
        blue = (b.toFloat() / 255f).coerceIn(0f, 1f),
        alpha = (a.toFloat() / 255f).coerceIn(0f, 1f)
    )
}
