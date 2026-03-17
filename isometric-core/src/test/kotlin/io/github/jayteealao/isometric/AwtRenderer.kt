package io.github.jayteealao.isometric

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Pure-JVM renderer that draws a [PreparedScene] to a [BufferedImage] using AWT Graphics2D.
 * Mirrors the rendering logic of AndroidCanvasRenderer but requires no Android dependencies.
 *
 * Used for generating documentation screenshots deterministically on any JVM.
 */
object AwtRenderer {

    fun renderToPng(
        width: Int,
        height: Int,
        outputFile: File,
        backgroundColor: Color? = null,
        scale: Double = 70.0,
        centerContent: Boolean = true,
        block: IsometricEngine.() -> Unit
    ) {
        val engine = IsometricEngine(scale = scale)
        engine.block()

        val scene = engine.projectScene(width, height)
        val image = renderScene(scene, width, height, backgroundColor, centerContent)

        outputFile.parentFile?.mkdirs()
        ImageIO.write(image, "PNG", outputFile)
    }

    fun renderScene(
        scene: PreparedScene,
        width: Int,
        height: Int,
        backgroundColor: Color? = null,
        centerContent: Boolean = true
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Background (null = transparent)
        if (backgroundColor != null) {
            g2d.color = backgroundColor
            g2d.fillRect(0, 0, width, height)
        }

        // Auto-center: compute bounding box of all projected content and translate to center
        if (centerContent && scene.commands.isNotEmpty()) {
            val (dx, dy, contentScale) = computeCenteringTransform(scene, width, height)
            g2d.translate(width / 2.0, height / 2.0)
            g2d.scale(contentScale, contentScale)
            g2d.translate(-width / 2.0, -height / 2.0)
            g2d.translate(dx, dy)
        }

        // Draw each render command (polygon fill + stroke to eliminate seams)
        val stroke = BasicStroke(1f)
        for (command in scene.commands) {
            if (command.points.isEmpty()) continue

            val path = Path2D.Double()
            path.moveTo(command.points[0].x, command.points[0].y)
            for (i in 1 until command.points.size) {
                path.lineTo(command.points[i].x, command.points[i].y)
            }
            path.closePath()

            val c = command.color
            val awtColor = Color(
                c.r.toInt().coerceIn(0, 255),
                c.g.toInt().coerceIn(0, 255),
                c.b.toInt().coerceIn(0, 255),
                c.a.toInt().coerceIn(0, 255)
            )

            // Fill
            g2d.color = awtColor
            g2d.fill(path)

            // Stroke with same color to eliminate polygon seams
            g2d.stroke = stroke
            g2d.draw(path)
        }

        g2d.dispose()
        return image
    }

    private data class CenteringTransform(val dx: Double, val dy: Double, val scale: Double)

    private fun computeCenteringTransform(
        scene: PreparedScene,
        width: Int,
        height: Int,
        padding: Double = 0.08
    ): CenteringTransform {
        var minX = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var minY = Double.MAX_VALUE
        var maxY = Double.MIN_VALUE

        for (command in scene.commands) {
            for (p in command.points) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
        }

        if (minX > maxX) return CenteringTransform(0.0, 0.0, 1.0)

        val contentCenterX = (minX + maxX) / 2.0
        val contentCenterY = (minY + maxY) / 2.0
        val canvasCenterX = width / 2.0
        val canvasCenterY = height / 2.0

        val dx = canvasCenterX - contentCenterX
        val dy = canvasCenterY - contentCenterY

        // Scale down if content overflows canvas (with padding)
        val contentWidth = maxX - minX
        val contentHeight = maxY - minY
        val availableWidth = width * (1.0 - 2 * padding)
        val availableHeight = height * (1.0 - 2 * padding)

        val scale = if (contentWidth > availableWidth || contentHeight > availableHeight) {
            minOf(availableWidth / contentWidth, availableHeight / contentHeight)
        } else {
            1.0
        }

        return CenteringTransform(dx, dy, scale)
    }
}
