package io.github.jayteealao.isometric.compose.runtime

import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderOptions
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

    // --- AC-23: rotationOrigin does NOT inherit from parent ---------------------------
    //
    // L6 finding: withTransform previously used `rotationOrigin ?: this.rotationOrigin`,
    // so a child with rotationOrigin=null inherited the parent's pivot — surprising and
    // almost certainly unintended. The fix: child null → child keeps null (rotates around
    // its own accumulated position in applyTransformsToShape/Path/Point).

    @Test
    fun `AC-23 explicit rotationOrigin is honored for child context`() {
        // Parent at (5,0,0) with no explicit rotation origin; rotation=0.
        // Child with rotationOrigin = (1,0,0) and rotation = PI/2.
        //
        // After parent: accumulatedPosition=(5,0,0), accumulatedRotation=0.
        // After child: accumulatedPosition=(5,0,0) (child position=origin), rotation=PI/2,
        //              rotationOrigin=(1,0,0) — the child's explicit origin, not inherited.
        //
        // applyTransformsToPoint(Point(2,0,0)):
        //   1. Translate: (2,0,0) + (5,0,0) = (7,0,0)
        //   2. Rotate PI/2 around (1,0,0): pX=7-1=6, pY=0; newX=0, newY=6 → (1, 6, 0)
        val childOrigin = Point(1.0, 0.0, 0.0)
        val ctx = baseContext()
            .withTransform(position = Point(5.0, 0.0, 0.0))
            .withTransform(rotationOrigin = childOrigin, rotation = PI / 2)

        val result = ctx.applyTransformsToPoint(Point(2.0, 0.0, 0.0))
        assertEquals(1.0, result.x, 0.001)
        assertEquals(6.0, result.y, 0.001)
    }

    @Test
    fun `AC-23 null rotationOrigin does not inherit parent rotationOrigin`() {
        // Parent has an explicit rotationOrigin at (10,0,0) and a rotation.
        // Child has null rotationOrigin. After the fix, child's null must NOT become (10,0,0).
        // applyTransformsToShape falls back to accumulatedPosition when rotationOrigin is null.
        val parentOrigin = Point(10.0, 0.0, 0.0)
        val parentCtx = baseContext()
            .withTransform(position = Point(5.0, 0.0, 0.0), rotationOrigin = parentOrigin, rotation = 0.0)

        // Child with null rotationOrigin: must NOT inherit (10,0,0).
        val childCtx = parentCtx.withTransform(position = Point(1.0, 0.0, 0.0), rotationOrigin = null)

        // applyTransformsToPoint uses accumulatedPosition as fallback when rotationOrigin is null.
        // With rotation=0 throughout, the child's null rotationOrigin is irrelevant to the result —
        // but the CONTEXT's internal rotationOrigin field must be null (not (10,0,0)).
        // We verify indirectly: apply a rotation in ANOTHER withTransform that inherits from childCtx.
        val rotatedCtx = childCtx.withTransform(rotationOrigin = null, rotation = PI / 2)

        // Rotation pivots at accumulatedPosition (6,0,0), not at (10,0,0).
        // Point at absolute (8,0,0) (= accumulated 6 + 2 from child geometry).
        // Rotated PI/2 around (6,0,0): (8,0,0) → (6,2,0).
        val result = rotatedCtx.applyTransformsToPoint(Point(2.0, 0.0, 0.0))
        assertEquals(6.0, result.x, 0.001)
        assertEquals(2.0, result.y, 0.001)
    }
}
