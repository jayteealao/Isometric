package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import io.github.jayteealao.isometric.ComputeBackend
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.Vector
import org.junit.Test

class AsyncPrepareInputsTest {

    @Test
    fun `equality changes when render options change`() {
        val a = AsyncPrepareInputs(
            renderOptions = RenderOptions.Default,
            lightDirection = Vector(1.0, 1.0, 1.0),
            computeBackend = ComputeBackend.Cpu,
        )
        val b = AsyncPrepareInputs(
            renderOptions = RenderOptions.NoDepthSorting,
            lightDirection = Vector(1.0, 1.0, 1.0),
            computeBackend = ComputeBackend.Cpu,
        )

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `equality changes when light direction changes`() {
        val a = AsyncPrepareInputs(
            renderOptions = RenderOptions.Default,
            lightDirection = Vector(1.0, 1.0, 1.0),
            computeBackend = ComputeBackend.Cpu,
        )
        val b = AsyncPrepareInputs(
            renderOptions = RenderOptions.Default,
            lightDirection = Vector(0.0, 1.0, 1.0),
            computeBackend = ComputeBackend.Cpu,
        )

        assertThat(a).isNotEqualTo(b)
    }
}
