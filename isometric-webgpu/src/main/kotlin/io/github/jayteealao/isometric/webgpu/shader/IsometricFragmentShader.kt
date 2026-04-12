package io.github.jayteealao.isometric.webgpu.shader

internal object IsometricFragmentShader {
    const val ENTRY_POINT = "fragmentMain"

    val WGSL: String = """
        @group(0) @binding(0) var diffuseTexture: texture_2d<f32>;
        @group(0) @binding(1) var diffuseSampler: sampler;

        struct FragmentInput {
            @location(0) color: vec4<f32>,
            @location(1) uv: vec2<f32>,
            @location(2) @interpolate(flat) textureIndex: u32,
        }

        @fragment
        fn ${ENTRY_POINT}(in: FragmentInput) -> @location(0) vec4<f32> {
            // Always sample to satisfy Dawn's uniform control flow requirement.
            // For NO_TEXTURE faces, the fallback checkerboard is bound but the
            // result is discarded via select — zero visual cost since the sampler
            // fetch is the same for all fragments in the draw call.
            let sampled = textureSample(diffuseTexture, diffuseSampler, in.uv);
            let textured = sampled * in.color;
            return select(textured, in.color, in.textureIndex == 0xFFFFFFFFu);
        }
    """.trimIndent()
}
