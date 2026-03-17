package io.github.jayteealao.isometric.compose.runtime

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.Vector
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.compose.runtime.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsometricRendererNativeCanvasTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = RenderContext(
        width = 800,
        height = 600,
        renderOptions = RenderOptions.NoCulling,
        lightDirection = Vector(2.0, -1.0, 3.0).normalize()
    )

    private fun buildSceneRoot(): GroupNode {
        val root = GroupNode()
        val shape = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        root.children.add(shape)
        shape.parent = root
        root.updateChildrenSnapshot()
        return root
    }

    private fun drawWithRenderer(
        renderer: IsometricRenderer,
        root: GroupNode,
        native: Boolean
    ) {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val androidCanvas = android.graphics.Canvas(bitmap)
        val composeCanvas = Canvas(androidCanvas)
        val drawScope = CanvasDrawScope()

        drawScope.draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = Size(800f, 600f)
        ) {
            with(renderer) {
                if (native) {
                    renderNative(root, context)
                } else {
                    render(root, context)
                }
            }
        }
    }

    @Test
    fun runtimeFlagSnapshot_reportsNativeCanvasWhenEnabled() {
        var snapshot: RuntimeFlagSnapshot? = null

        composeRule.setContent {
            IsometricScene(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                config = AdvancedSceneConfig(
                    gestures = GestureConfig.Disabled,
                    enablePathCaching = false,
                    enableSpatialIndex = false,
                    useNativeCanvas = true,
                    onFlagsReady = { snapshot = it }
                )
            ) {
                Shape(
                    geometry = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
                    color = IsoColor.BLUE,
                    position = Point.ORIGIN
                )
            }
        }

        composeRule.waitForIdle()

        assertNotNull("Runtime flag snapshot should be reported", snapshot)
        assertTrue("Runtime flag snapshot should report native canvas enabled", snapshot!!.useNativeCanvas)
    }

    @Test
    fun nativeCanvas_renderPathExecutesAndPreservesHitTestSemantics() {
        val root = buildSceneRoot()

        val nativeRenderer = IsometricRenderer(IsometricEngine(), enablePathCaching = false, enableSpatialIndex = false)
        drawWithRenderer(nativeRenderer, root, native = true)
        val nativeScene = nativeRenderer.currentPreparedScene!!

        root.markDirty()

        val composeRenderer = IsometricRenderer(IsometricEngine(), enablePathCaching = false, enableSpatialIndex = false)
        drawWithRenderer(composeRenderer, root, native = false)
        val composeScene = composeRenderer.currentPreparedScene!!

        assertEquals(composeScene.commands.size, nativeScene.commands.size)
        assertEquals(composeScene.commands.map { it.color }, nativeScene.commands.map { it.color })
        assertEquals(
            composeScene.commands.map { it.points.toList() },
            nativeScene.commands.map { it.points.toList() }
        )

        val cmd = nativeScene.commands.first()
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

        val nativeHit = nativeRenderer.hitTest(root, avgX, avgY, context, 800, 600)
        val composeHit = composeRenderer.hitTest(root, avgX, avgY, context, 800, 600)

        assertNotNull("Native renderer should hit the shape centroid", nativeHit)
        assertNotNull("Compose renderer should hit the shape centroid", composeHit)
        assertEquals(
            "Native canvas must not change hit-test results",
            composeHit!!.nodeId,
            nativeHit!!.nodeId
        )
    }
}
