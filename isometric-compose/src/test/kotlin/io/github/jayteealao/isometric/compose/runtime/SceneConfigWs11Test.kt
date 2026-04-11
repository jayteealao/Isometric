package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SceneConfigWs11Test {

    @Test
    fun `default SceneConfig has Canvas render mode with Cpu compute`() {
        val config = SceneConfig()
        assertThat(config.renderMode).isEqualTo(RenderMode.Canvas())
    }

    @Test
    fun `default Canvas render mode uses Cpu compute`() {
        val mode = RenderMode.Canvas()
        assertThat(mode.compute).isEqualTo(RenderMode.Canvas.Compute.Cpu)
    }

    @Test
    fun `SceneConfig equals includes renderMode`() {
        val a = SceneConfig()
        val b = SceneConfig()
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `SceneConfig hashCode includes renderMode`() {
        val a = SceneConfig()
        val b = SceneConfig()
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `different render modes are not equal`() {
        val a = SceneConfig(renderMode = RenderMode.Canvas())
        val b = SceneConfig(renderMode = RenderMode.WebGpu())
        assertThat(a).isNotEqualTo(b)
    }
}
