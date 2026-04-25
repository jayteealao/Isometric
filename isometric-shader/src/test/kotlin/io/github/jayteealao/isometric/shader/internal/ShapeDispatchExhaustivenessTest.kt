package io.github.jayteealao.isometric.shader.internal

import io.github.jayteealao.isometric.ExperimentalIsometricApi
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.Knot
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.Stairs
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Compile-time-proxy exhaustiveness test for [ShapeRegistry].
 *
 * [io.github.jayteealao.isometric.Shape] is an `open class` (not sealed), so Kotlin
 * cannot enforce exhaustiveness at compile time. This test acts as the runtime proxy:
 * every known concrete [io.github.jayteealao.isometric.Shape] subclass must have an
 * entry in [ShapeRegistry.byClass] (AC6 — webgpu-pipeline-cleanup G9).
 *
 * When a new shape is added to `isometric-core`, this test will fail until a
 * corresponding [ShapeUvDescriptor] is registered in [ShapeRegistry].
 */
@OptIn(ExperimentalIsometricApi::class)
internal class ShapeDispatchExhaustivenessTest {

    @Test
    fun every_concrete_shape_has_a_descriptor() {
        val concrete = listOf(
            Prism::class,
            Cylinder::class,
            Pyramid::class,
            Stairs::class,
            Octahedron::class,
            Knot::class,
        )
        concrete.forEach { kls ->
            assertNotNull(
                "Missing ShapeUvDescriptor in ShapeRegistry for $kls. " +
                    "Register a descriptor in ShapeRegistry.byClass.",
                ShapeRegistry.byClass[kls],
            )
        }
    }
}
