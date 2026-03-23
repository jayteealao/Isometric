package io.github.jayteealao.isometric.webgpu.shader

internal object IsometricFragmentShader {
    const val ENTRY_POINT = "fragmentMain"

    val WGSL: String = """
        struct FragmentInput {
            @location(0) color: vec4<f32>,
            @location(1) uv: vec2<f32>,
        }

        @fragment
        fn ${ENTRY_POINT}(in: FragmentInput) -> @location(0) vec4<f32> {
            return in.color;
        }
    """.trimIndent()
}
