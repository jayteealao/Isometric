package io.github.jayteealao.isometric.webgpu.shader

internal object IsometricFragmentShader {
    const val ENTRY_POINT = "fragmentMain"

    val WGSL: String = """
        @group(0) @binding(0) var diffuseTexture: texture_2d<f32>;
        @group(0) @binding(1) var diffuseSampler: sampler;

        struct FragmentInput {
            @location(0) color: vec4<f32>,
            @location(1) uv: vec2<f32>,
            // atlasRegion: (scaleU, scaleV, offsetU, offsetV) flat-interpolated from the compute pass.
            // Apply fract() here (per-fragment) so tiling wraps correctly within the atlas sub-region.
            // Per-vertex fract() fails because fract(1.0) = 0.0 collapses face corners to one texel.
            @location(2) @interpolate(flat) atlasRegion: vec4<f32>,
            @location(3) @interpolate(flat) textureIndex: u32,
        }

        @fragment
        fn ${ENTRY_POINT}(in: FragmentInput) -> @location(0) vec4<f32> {
            // Apply fract() per-fragment to the pre-atlas UV, then map into the atlas sub-region.
            // fract() wraps tiling UVs back into [0,1) before the atlas scale+offset is applied,
            // so tiles wrap within the sub-region rather than bleeding to the atlas origin.
            // Always sample to satisfy Dawn's uniform control flow requirement.
            // For NO_TEXTURE faces the result is discarded via select.
            let atlasUV = fract(in.uv) * in.atlasRegion.xy + in.atlasRegion.zw;
            let sampled = textureSample(diffuseTexture, diffuseSampler, atlasUV);
            let textured = sampled * in.color;
            return select(textured, in.color, in.textureIndex == 0xFFFFFFFFu);
        }
    """.trimIndent()
}
