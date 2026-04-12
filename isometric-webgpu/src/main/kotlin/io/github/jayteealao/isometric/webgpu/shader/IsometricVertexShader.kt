package io.github.jayteealao.isometric.webgpu.shader

internal object IsometricVertexShader {
    const val ENTRY_POINT = "vertexMain"

    val WGSL: String = """
        struct VertexInput {
            @location(0) position: vec2<f32>,
            @location(1) color: vec4<f32>,
            @location(2) uv: vec2<f32>,
            @location(3) textureIndex: u32,
        }

        struct VertexOutput {
            @builtin(position) clipPosition: vec4<f32>,
            @location(0) color: vec4<f32>,
            @location(1) uv: vec2<f32>,
            @location(2) @interpolate(flat) textureIndex: u32,
        }

        @vertex
        fn ${ENTRY_POINT}(in: VertexInput) -> VertexOutput {
            var out: VertexOutput;
            out.clipPosition = vec4<f32>(in.position, 0.0, 1.0);
            out.color = in.color;
            out.uv = in.uv;
            out.textureIndex = in.textureIndex;
            return out;
        }
    """.trimIndent()
}
