package io.github.jayteealao.isometric.shader.internal

import io.github.jayteealao.isometric.ExperimentalIsometricApi
import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.compose.runtime.UvCoordProvider
import io.github.jayteealao.isometric.shapes.FaceIdentifier
import io.github.jayteealao.isometric.shapes.Knot
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.Stairs
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.shader.UvGenerator
import kotlin.reflect.KClass

/**
 * Registry that maps each concrete [Shape] subclass to its [ShapeUvDescriptor].
 *
 * ## Rationale
 *
 * Previously there were 5 parallel `when (shape) { is X -> ... }` dispatch sites
 * across `UvCoordProviderForShape`, `IsometricMaterial.resolveForFace`,
 * `GpuTextureManager.collectTextureSources`, and `IsometricNode`. Every new shape
 * required updating all 5 sites in lockstep — a forgotten-update trap.
 *
 * With [ShapeRegistry], adding a new shape requires:
 * 1. Define a new [ShapeUvDescriptor] implementation.
 * 2. Add one entry to [byClass].
 * 3. [ShapeDispatchExhaustivenessTest] catches the omission at test time.
 *
 * ## Migration status (G9 — webgpu-pipeline-cleanup)
 *
 * - [io.github.jayteealao.isometric.shader.uvCoordProviderForShape] (site a) is fully
 *   migrated to use this registry.
 * - Sites (b) and (c) — `resolveForFace` and `collectTextureSourcesFromMaterial` —
 *   already use exhaustive `when` over the sealed `PerFace` hierarchy and are deferred
 *   to a follow-up slice (migration there would add complexity without reducing risk).
 * - Site (d) — `IsometricNode.renderTo faceType dispatch` — migrated in G3 via
 *   [FaceIdentifier.forShape] in `isometric-core`; no action needed here.
 *
 * ## Exhaustiveness guarantee
 *
 * Because [Shape] is an `open class` (not sealed), Kotlin cannot enforce exhaustiveness
 * at compile time. [ShapeDispatchExhaustivenessTest] acts as the runtime proxy: it
 * asserts that every known concrete subclass has an entry in [byClass].
 */
@OptIn(ExperimentalIsometricApi::class)
internal object ShapeRegistry {
    val byClass: Map<KClass<out Shape>, ShapeUvDescriptor> = mapOf(
        Prism::class to PrismDescriptor,
        Cylinder::class to CylinderDescriptor,
        Pyramid::class to PyramidDescriptor,
        Stairs::class to StairsDescriptor,
        Octahedron::class to OctahedronDescriptor,
        Knot::class to KnotDescriptor,
    )
}

// ── Descriptor implementations ────────────────────────────────────────────────

internal object PrismDescriptor : ShapeUvDescriptor {
    override fun uvCoordProvider(shape: Shape): UvCoordProvider? {
        val prism = shape as? Prism ?: return null
        return UvCoordProvider { _, faceIndex -> UvGenerator.forPrismFace(prism, faceIndex) }
    }

    override fun faceIdentifier(shape: Shape, faceIndex: Int): FaceIdentifier? =
        FaceIdentifier.forShape(shape, faceIndex)

    override fun collectTextureSourcesContribution(material: MaterialData): List<TextureSource> {
        val m = material as? IsometricMaterial.PerFace.Prism ?: return emptyList()
        val out = mutableListOf<TextureSource>()
        for (sub in m.faceMap.values) {
            if (sub is IsometricMaterial.Textured) out.add(sub.source)
        }
        if (m.default is IsometricMaterial.Textured) out.add((m.default as IsometricMaterial.Textured).source)
        return out
    }
}

internal object CylinderDescriptor : ShapeUvDescriptor {
    override fun uvCoordProvider(shape: Shape): UvCoordProvider? {
        val cylinder = shape as? Cylinder ?: return null
        return UvCoordProvider { _, faceIndex -> UvGenerator.forCylinderFace(cylinder, faceIndex) }
    }

    override fun faceIdentifier(shape: Shape, faceIndex: Int): FaceIdentifier? =
        FaceIdentifier.forShape(shape, faceIndex)

    override fun collectTextureSourcesContribution(material: MaterialData): List<TextureSource> {
        val m = material as? IsometricMaterial.PerFace.Cylinder ?: return emptyList()
        val out = mutableListOf<TextureSource>()
        (m.top as? IsometricMaterial.Textured)?.let { out.add(it.source) }
        (m.bottom as? IsometricMaterial.Textured)?.let { out.add(it.source) }
        (m.side as? IsometricMaterial.Textured)?.let { out.add(it.source) }
        if (m.default is IsometricMaterial.Textured) out.add((m.default as IsometricMaterial.Textured).source)
        return out
    }
}

internal object PyramidDescriptor : ShapeUvDescriptor {
    override fun uvCoordProvider(shape: Shape): UvCoordProvider? {
        val pyramid = shape as? Pyramid ?: return null
        return UvCoordProvider { _, faceIndex -> UvGenerator.forPyramidFace(pyramid, faceIndex) }
    }

    override fun faceIdentifier(shape: Shape, faceIndex: Int): FaceIdentifier? =
        FaceIdentifier.forShape(shape, faceIndex)

    override fun collectTextureSourcesContribution(material: MaterialData): List<TextureSource> {
        val m = material as? IsometricMaterial.PerFace.Pyramid ?: return emptyList()
        val out = mutableListOf<TextureSource>()
        (m.base as? IsometricMaterial.Textured)?.let { out.add(it.source) }
        for (sub in m.laterals.values) {
            if (sub is IsometricMaterial.Textured) out.add(sub.source)
        }
        if (m.default is IsometricMaterial.Textured) out.add((m.default as IsometricMaterial.Textured).source)
        return out
    }
}

internal object StairsDescriptor : ShapeUvDescriptor {
    override fun uvCoordProvider(shape: Shape): UvCoordProvider? {
        val stairs = shape as? Stairs ?: return null
        return UvCoordProvider { _, faceIndex -> UvGenerator.forStairsFace(stairs, faceIndex) }
    }

    override fun faceIdentifier(shape: Shape, faceIndex: Int): FaceIdentifier? =
        FaceIdentifier.forShape(shape, faceIndex)

    override fun collectTextureSourcesContribution(material: MaterialData): List<TextureSource> {
        val m = material as? IsometricMaterial.PerFace.Stairs ?: return emptyList()
        val out = mutableListOf<TextureSource>()
        (m.tread as? IsometricMaterial.Textured)?.let { out.add(it.source) }
        (m.riser as? IsometricMaterial.Textured)?.let { out.add(it.source) }
        (m.side as? IsometricMaterial.Textured)?.let { out.add(it.source) }
        if (m.default is IsometricMaterial.Textured) out.add((m.default as IsometricMaterial.Textured).source)
        return out
    }
}

internal object OctahedronDescriptor : ShapeUvDescriptor {
    override fun uvCoordProvider(shape: Shape): UvCoordProvider? {
        val octahedron = shape as? Octahedron ?: return null
        return UvCoordProvider { _, faceIndex -> UvGenerator.forOctahedronFace(octahedron, faceIndex) }
    }

    override fun faceIdentifier(shape: Shape, faceIndex: Int): FaceIdentifier? =
        FaceIdentifier.forShape(shape, faceIndex)

    override fun collectTextureSourcesContribution(material: MaterialData): List<TextureSource> {
        val m = material as? IsometricMaterial.PerFace.Octahedron ?: return emptyList()
        val out = mutableListOf<TextureSource>()
        for (sub in m.byIndex.values) {
            if (sub is IsometricMaterial.Textured) out.add(sub.source)
        }
        if (m.default is IsometricMaterial.Textured) out.add((m.default as IsometricMaterial.Textured).source)
        return out
    }
}

/**
 * Descriptor for [Knot].
 *
 * [Knot] has no named face taxonomy (its depth-sorting bug makes per-face materials
 * unreliable), so [faceIdentifier] always returns `null`. UV generation is handled
 * by [io.github.jayteealao.isometric.shader.UvGenerator.forKnotFace].
 * [collectTextureSourcesContribution] is a no-op: `PerFace` materials on Knot resolve
 * every face to the `PerFace.default` material (see [io.github.jayteealao.isometric.shapes.Knot]
 * KDoc), so callers should collect sources from the `default` material directly.
 */
@OptIn(ExperimentalIsometricApi::class)
internal object KnotDescriptor : ShapeUvDescriptor {
    override fun uvCoordProvider(shape: Shape): UvCoordProvider? {
        val knot = shape as? Knot ?: return null
        return UvCoordProvider { _, faceIndex -> UvGenerator.forKnotFace(knot, faceIndex) }
    }

    override fun faceIdentifier(shape: Shape, faceIndex: Int): FaceIdentifier? = null

    override fun collectTextureSourcesContribution(material: MaterialData): List<TextureSource> =
        emptyList()
}
