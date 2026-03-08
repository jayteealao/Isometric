package io.fabianterhorst.isometric.compose.runtime

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class RenderContextTest {

    private fun baseContext() = RenderContext(
        width = 800,
        height = 600,
        renderOptions = RenderOptions.Default
    )

    @Test
    fun identityTransformReturnsPointUnchanged() {
        val ctx = baseContext()
        val result = ctx.applyTransformsToPoint(Point(3.0, 4.0, 5.0))
        assertEquals(3.0, result.x, 0.001)
        assertEquals(4.0, result.y, 0.001)
        assertEquals(5.0, result.z, 0.001)
    }

    @Test
    fun translationAccumulatesViaWithTransform() {
        val ctx = baseContext()
            .withTransform(position = Point(1.0, 0.0, 0.0))
            .withTransform(position = Point(0.0, 2.0, 0.0))
        val result = ctx.applyTransformsToPoint(Point(0.0, 0.0, 0.0))
        assertEquals(1.0, result.x, 0.001)
        assertEquals(2.0, result.y, 0.001)
        assertEquals(0.0, result.z, 0.001)
    }

    @Test
    fun rotationPiOver2MovesXAxisPointToYAxis() {
        val ctx = baseContext().withTransform(rotation = PI / 2)
        val result = ctx.applyTransformsToPoint(Point(5.0, 0.0, 0.0))
        assertEquals(0.0, result.x, 0.001)
        assertEquals(5.0, result.y, 0.001)
        assertEquals(0.0, result.z, 0.001)
    }

    @Test
    fun twoPiOver4RotationsAccumulateToPiOver2() {
        val ctx = baseContext()
            .withTransform(rotation = PI / 4)
            .withTransform(rotation = PI / 4)
        val result = ctx.applyTransformsToPoint(Point(5.0, 0.0, 0.0))
        assertEquals(0.0, result.x, 0.001)
        assertEquals(5.0, result.y, 0.001)
        assertEquals(0.0, result.z, 0.001)
    }

    @Test
    fun scaleAccumulatesMultiplicatively() {
        val ctx = baseContext()
            .withTransform(scale = 2.0)
            .withTransform(scale = 3.0)
        val result = ctx.applyTransformsToPoint(Point(1.0, 0.0, 0.0))
        assertEquals(6.0, result.x, 0.001)
        assertEquals(0.0, result.y, 0.001)
        assertEquals(0.0, result.z, 0.001)
    }

    @Test
    fun parentRotationTransformsChildPositionIntoWorldSpace() {
        // Parent rotated PI/2, child at local (1,0,0)
        // Child position rotated by parent's PI/2 → world (0,1,0)
        val ctx = baseContext()
            .withTransform(rotation = PI / 2)
            .withTransform(position = Point(1.0, 0.0, 0.0))
        val result = ctx.applyTransformsToPoint(Point(0.0, 0.0, 0.0))
        assertEquals(0.0, result.x, 0.001)
        assertEquals(1.0, result.y, 0.001)
        assertEquals(0.0, result.z, 0.001)
    }

    @Test
    fun parentPositionPlusRotationPlusChildPosition() {
        // Parent at (5,0,0) rotated PI/2, child at local (2,0,0)
        // Child position rotated by PI/2: (2,0,0) → (0,2,0)
        // Accumulated: (5,0,0) + (0,2,0) = (5,2,0)
        val ctx = baseContext()
            .withTransform(position = Point(5.0, 0.0, 0.0), rotation = PI / 2)
            .withTransform(position = Point(2.0, 0.0, 0.0))
        val result = ctx.applyTransformsToPoint(Point(0.0, 0.0, 0.0))
        assertEquals(5.0, result.x, 0.001)
        assertEquals(2.0, result.y, 0.001)
        assertEquals(0.0, result.z, 0.001)
    }

    @Test
    fun pathTransformAppliesSameLogicToEachPoint() {
        val ctx = baseContext().withTransform(position = Point(10.0, 0.0, 0.0))
        val path = Path(listOf(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0)
        ))
        val result = ctx.applyTransformsToPath(path)
        assertEquals(10.0, result.points[0].x, 0.001)
        assertEquals(11.0, result.points[1].x, 0.001)
        assertEquals(11.0, result.points[2].x, 0.001)
        assertEquals(1.0, result.points[2].y, 0.001)
    }

    @Test
    fun parentScaleAffectsChildPosition() {
        // Parent scaled 2x, child at local (3,0,0)
        // Child position scaled by 2 → (6,0,0)
        val ctx = baseContext()
            .withTransform(scale = 2.0)
            .withTransform(position = Point(3.0, 0.0, 0.0))
        val result = ctx.applyTransformsToPoint(Point(0.0, 0.0, 0.0))
        assertEquals(6.0, result.x, 0.001)
        assertEquals(0.0, result.y, 0.001)
    }
}
