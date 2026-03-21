package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import io.github.jayteealao.isometric.ComputeBackend
import io.github.jayteealao.isometric.compose.runtime.render.RenderBackend
import org.junit.Test

class SceneConfigWs11Test {

    @Test
    fun `default SceneConfig has Canvas render backend`() {
        val config = SceneConfig()
        assertThat(config.renderBackend).isEqualTo(RenderBackend.Canvas)
    }

    @Test
    fun `default SceneConfig has Cpu compute backend`() {
        val config = SceneConfig()
        assertThat(config.computeBackend).isEqualTo(ComputeBackend.Cpu)
    }

    @Test
    fun `Cpu compute backend is not async`() {
        assertThat(ComputeBackend.Cpu.isAsync).isFalse()
    }

    @Test
    fun `Canvas render backend toString`() {
        assertThat(RenderBackend.Canvas.toString()).isEqualTo("RenderBackend.Canvas")
    }

    @Test
    fun `SceneConfig equals includes renderBackend and computeBackend`() {
        val a = SceneConfig()
        val b = SceneConfig()
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `SceneConfig hashCode includes renderBackend and computeBackend`() {
        val a = SceneConfig()
        val b = SceneConfig()
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
