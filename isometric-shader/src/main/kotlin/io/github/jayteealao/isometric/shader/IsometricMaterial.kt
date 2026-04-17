package io.github.jayteealao.isometric.shader

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.shapes.CylinderFace
import io.github.jayteealao.isometric.shapes.FaceIdentifier
import io.github.jayteealao.isometric.shapes.OctahedronFace
import io.github.jayteealao.isometric.shapes.PrismFace
import io.github.jayteealao.isometric.shapes.PyramidFace
import io.github.jayteealao.isometric.shapes.StairsFace

/**
 * Describes how a face should be painted.
 *
 * Implements [MaterialData] so that [io.github.jayteealao.isometric.RenderCommand]
 * can carry a material reference without depending on Android types.
 *
 * ## Progressive disclosure (guideline Section 2)
 *
 * - **Simple:** `Shape(Prism(origin), IsoColor.BLUE)` — zero overhead, flat color via core
 * - **Configurable:** `texturedResource(R.drawable.brick)` — bitmap texture
 * - **Advanced:** `perFace { top = texturedResource(...) }` — per-face control
 *
 * ## Sealed interface (guideline Section 6)
 *
 * Subtypes are `Textured` and `PerFace` only — flat-color rendering uses [IsoColor] directly.
 * **Evolution note:** Adding a new subtype in a future version is a breaking change
 * for consumers using exhaustive `when` expressions. Use an `else` branch in `when`
 * if forward compatibility is needed.
 */
sealed interface IsometricMaterial : MaterialData {

    /**
     * Renders the face with a [TextureSource] bitmap, optionally tinted and transformed.
     *
     * Requires [io.github.jayteealao.isometric.shader.render.ProvideTextureRendering] in the
     * composition or textures will not be rendered.
     *
     * @property source Where to load the bitmap from
     * @property tint Multiplicative color tint applied over the texture (WHITE = no tint)
     * @property transform Affine UV transform (scale, offset, rotation). Defaults to identity.
     */
    data class Textured(
        val source: TextureSource,
        val tint: IsoColor = IsoColor.WHITE,
        val transform: TextureTransform = TextureTransform.IDENTITY,
    ) : IsometricMaterial {
        override fun baseColor(): IsoColor = tint
    }

    /**
     * Assigns different materials to different faces of a shape.
     *
     * `PerFace` is a sealed hierarchy with one subclass per supported shape family:
     *
     * - [Prism] — `TOP`, `BOTTOM`, `FRONT`, `BACK`, `LEFT`, `RIGHT` (six faces, via [PrismFace])
     * - [Cylinder] — `TOP`, `BOTTOM`, `SIDE` (three logical faces, via [CylinderFace])
     * - [Pyramid] — `BASE` + four `Lateral` triangles (via [PyramidFace])
     * - [Stairs] — `RISER`, `TREAD`, `SIDE` logical groups (via [StairsFace])
     * - [Octahedron] — eight individual triangular faces (via [OctahedronFace])
     *
     * Faces not covered by a subclass's per-face slots fall back to [default]. The
     * [default] invariant — a `PerFace` material cannot itself be a `PerFace` — is
     * enforced in the base [init] block and applies to every subclass.
     *
     * Use the [perFace] DSL to construct a [Prism] instance. For non-Prism shapes,
     * construct the matching subclass directly, e.g. `PerFace.Cylinder(top = ..., default = ...)`.
     *
     * ### Validation via `require()` rather than a compile-time builder
     *
     * Invariants (e.g. `default` not being a `PerFace`, `Pyramid.laterals` keys in 0..3,
     * `RenderCommand.faceVertexCount` in 3..24) are enforced by `require()` in each
     * class's init block rather than encoded in the type system via a staged builder
     * or phantom types. This trade-off is deliberate:
     *
     * - **Ergonomics.** `PerFace.Cylinder(top = red)` reads as a single, familiar Kotlin
     *   constructor call. A staged-builder or typestate encoding would require multiple
     *   intermediate types (`EmptyPerFaceBuilder → ValidPerFaceBuilder`) to model the
     *   same invariant, and would obscure the named-argument call-site style preferred
     *   by this API (api-design-guideline §5).
     * - **Coverage.** The invariants guarded here (no `PerFace` nesting, valid face
     *   index ranges) are cheap runtime checks that fail at construction — well before
     *   the material reaches the render path — and are triggered reliably by unit
     *   tests, so compile-time encoding would duplicate coverage without catching new
     *   classes of bug.
     * - **Evolution.** Adding a future invariant (e.g. "all `Textured` slots share
     *   the same `TextureTransform`") is a one-line change to an init block; the same
     *   addition under a builder encoding would be a new intermediate type plus a new
     *   transition method, a larger breaking change for downstream callers.
     *
     * If a future invariant cannot be cheaply verified at construction, revisit this
     * choice for that specific case rather than migrating the whole hierarchy.
     *
     * @property default Material used for faces not explicitly assigned. Must not itself
     *   be a [PerFace] instance. Defaults to mid-gray ([UNASSIGNED_FACE_DEFAULT]) so
     *   unassigned faces are visible.
     */
    sealed class PerFace(public val default: MaterialData) : IsometricMaterial {
        init {
            require(default !is PerFace) {
                "PerFace default cannot itself be PerFace — nesting is not supported"
            }
        }

        override fun baseColor(): IsoColor = default.baseColor()

        public companion object {
            /** Mid-gray fallback for unassigned [PerFace] faces (visible, not transparent). */
            internal val UNASSIGNED_FACE_DEFAULT: IsoColor = IsoColor(128, 128, 128, 255)
        }

        /**
         * Assigns different materials to the six faces of a [io.github.jayteealao.isometric.shapes.Prism].
         *
         * @property faceMap Map from [PrismFace] role to material for that face
         * @property default Material used for faces not present in [faceMap].
         */
        public class Prism internal constructor(
            faceMap: Map<PrismFace, MaterialData>,
            default: MaterialData = UNASSIGNED_FACE_DEFAULT,
        ) : PerFace(default) {
            /**
             * Backing storage for per-face materials. Stored as an [java.util.EnumMap]
             * (or [emptyMap] when no slots are assigned) so per-frame lookup in
             * `SceneDataPacker` / `GpuTextureManager` is an O(1) array indexing rather
             * than a `LinkedHashMap` hash probe.
             */
            public val faceMap: Map<PrismFace, MaterialData> = if (faceMap.isEmpty()) {
                emptyMap()
            } else {
                java.util.EnumMap<PrismFace, MaterialData>(PrismFace::class.java).apply {
                    putAll(faceMap)
                }
            }

            init {
                require(this.faceMap.values.none { it is PerFace }) {
                    "PerFace.Prism: face materials cannot themselves be PerFace"
                }
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Prism) return false
                return faceMap == other.faceMap && default == other.default
            }

            override fun hashCode(): Int {
                var result = faceMap.hashCode()
                result = 31 * result + default.hashCode()
                return result
            }

            override fun toString(): String = "PerFace.Prism(faceMap=$faceMap, default=$default)"

            public companion object {
                /**
                 * Creates a [Prism] per-face material.
                 *
                 * Prefer the [perFace] DSL for typical usage; use this factory when you
                 * already have a `Map<PrismFace, MaterialData>` in hand (e.g. loading
                 * from data).
                 *
                 * @param faceMap Map from [PrismFace] to material for that face
                 * @param default Material for faces not in [faceMap]; must not be a [PerFace]
                 */
                public fun of(
                    faceMap: Map<PrismFace, MaterialData>,
                    default: MaterialData = UNASSIGNED_FACE_DEFAULT,
                ): Prism = Prism(faceMap, default)
            }
        }

        /**
         * Assigns different materials to the top cap, bottom cap, and side barrel of a
         * [io.github.jayteealao.isometric.shapes.Cylinder].
         *
         * All side quads share the same [side] material (logical grouping). Any slot
         * left `null` falls back to [default].
         *
         * **Stub:** [resolve] works for direct calls but per-slot rendering requires
         * three collaborators that this slice deliberately leaves empty:
         *
         * - TODO(uv-generation-cylinder): register a non-null provider in
         *   [uvCoordProviderForShape] so `CylinderFace` faces get per-face UVs.
         * - TODO(uv-generation-cylinder): add a `is Cylinder` branch to
         *   [resolveForFace] so the render pipeline dispatches on `CylinderFace` rather
         *   than falling back to [default].
         * - TODO(uv-generation-cylinder): collect per-slot textures in
         *   `GpuTextureManager.collectTextureSources`; a Log.w warning fires today
         *   when Textured slots are present but not rendered.
         *
         * @property top Material for the top cap (path index 0)
         * @property bottom Material for the bottom cap (path index 1)
         * @property side Material for every side quad (path indices 2..)
         * @property default Fallback for any slot left null
         */
        public class Cylinder(
            public val top: MaterialData? = null,
            public val bottom: MaterialData? = null,
            public val side: MaterialData? = null,
            default: MaterialData = UNASSIGNED_FACE_DEFAULT,
        ) : PerFace(default) {

            /** Returns the material for [face], falling back to [default] if unassigned. */
            public fun resolve(face: CylinderFace): MaterialData = when (face) {
                CylinderFace.TOP -> top ?: default
                CylinderFace.BOTTOM -> bottom ?: default
                CylinderFace.SIDE -> side ?: default
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Cylinder) return false
                return top == other.top &&
                    bottom == other.bottom &&
                    side == other.side &&
                    default == other.default
            }

            override fun hashCode(): Int {
                var result = top?.hashCode() ?: 0
                result = 31 * result + (bottom?.hashCode() ?: 0)
                result = 31 * result + (side?.hashCode() ?: 0)
                result = 31 * result + default.hashCode()
                return result
            }

            override fun toString(): String =
                "PerFace.Cylinder(top=$top, bottom=$bottom, side=$side, default=$default)"
        }

        /**
         * Assigns different materials to the base and lateral triangles of a
         * [io.github.jayteealao.isometric.shapes.Pyramid].
         *
         * [laterals] maps a [PyramidFace.Lateral] slot to its material. Missing slots
         * fall back to [default]. [base] applies to the rectangular base quad (added by
         * the `uv-generation-pyramid` slice).
         *
         * **Stub:** [resolve] works for direct calls but per-slot rendering requires
         * collaborators that this slice deliberately leaves empty:
         *
         * - TODO(uv-generation-pyramid): register a non-null provider in
         *   [uvCoordProviderForShape].
         * - TODO(uv-generation-pyramid): add a `is Pyramid` branch to [resolveForFace].
         * - TODO(uv-generation-pyramid): collect per-slot textures in
         *   `GpuTextureManager.collectTextureSources` (warning fires today).
         *
         * @property base Material for the rectangular base quad (path index 4)
         * @property laterals Map from [PyramidFace.Lateral] to material for that lateral.
         *   Using the typed key (rather than a raw `Int`) makes invalid slot indices a
         *   compile-time error — `PyramidFace.Lateral`'s own `init` enforces the 0..3 range.
         * @property default Fallback for any slot left null or any lateral not in [laterals]
         */
        public class Pyramid(
            public val base: MaterialData? = null,
            public val laterals: Map<PyramidFace.Lateral, MaterialData> = emptyMap(),
            default: MaterialData = UNASSIGNED_FACE_DEFAULT,
        ) : PerFace(default) {

            /** Returns the material for [face], falling back to [default] if unassigned. */
            public fun resolve(face: PyramidFace): MaterialData = when (face) {
                PyramidFace.BASE -> base ?: default
                is PyramidFace.Lateral -> laterals[face] ?: default
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Pyramid) return false
                return base == other.base &&
                    laterals == other.laterals &&
                    default == other.default
            }

            override fun hashCode(): Int {
                var result = base?.hashCode() ?: 0
                result = 31 * result + laterals.hashCode()
                result = 31 * result + default.hashCode()
                return result
            }

            override fun toString(): String =
                "PerFace.Pyramid(base=$base, laterals=$laterals, default=$default)"
        }

        /**
         * Assigns different materials to the riser, tread, and side surfaces of a
         * [io.github.jayteealao.isometric.shapes.Stairs] shape.
         *
         * All risers share [riser], all treads share [tread], and both zigzag side
         * walls share [side]. This logical grouping is independent of `stepCount` —
         * adding more steps does not change the material API.
         *
         * **Stub:** [resolve] works for direct calls but per-slot rendering requires
         * collaborators that this slice deliberately leaves empty:
         *
         * - TODO(uv-generation-stairs): register a non-null provider in
         *   [uvCoordProviderForShape].
         * - TODO(uv-generation-stairs): add a `is Stairs` branch to [resolveForFace].
         * - TODO(uv-generation-stairs): collect per-slot textures in
         *   `GpuTextureManager.collectTextureSources` (warning fires today).
         */
        public class Stairs(
            public val tread: MaterialData? = null,
            public val riser: MaterialData? = null,
            public val side: MaterialData? = null,
            default: MaterialData = UNASSIGNED_FACE_DEFAULT,
        ) : PerFace(default) {

            /** Returns the material for [face], falling back to [default] if unassigned. */
            public fun resolve(face: StairsFace): MaterialData = when (face) {
                StairsFace.TREAD -> tread ?: default
                StairsFace.RISER -> riser ?: default
                StairsFace.SIDE -> side ?: default
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Stairs) return false
                return tread == other.tread &&
                    riser == other.riser &&
                    side == other.side &&
                    default == other.default
            }

            override fun hashCode(): Int {
                var result = tread?.hashCode() ?: 0
                result = 31 * result + (riser?.hashCode() ?: 0)
                result = 31 * result + (side?.hashCode() ?: 0)
                result = 31 * result + default.hashCode()
                return result
            }

            override fun toString(): String =
                "PerFace.Stairs(tread=$tread, riser=$riser, side=$side, default=$default)"
        }

        /**
         * Assigns different materials to the eight triangular faces of an
         * [io.github.jayteealao.isometric.shapes.Octahedron].
         *
         * [byIndex] maps an [OctahedronFace] to its material; unassigned faces use [default].
         *
         * **Stub:** [resolve] works for direct calls but per-slot rendering requires
         * collaborators that this slice deliberately leaves empty:
         *
         * - TODO(uv-generation-octahedron): register a non-null provider in
         *   [uvCoordProviderForShape].
         * - TODO(uv-generation-octahedron): add a `is Octahedron` branch to
         *   [resolveForFace].
         * - TODO(uv-generation-octahedron): collect per-slot textures in
         *   `GpuTextureManager.collectTextureSources` (warning fires today).
         */
        public class Octahedron(
            public val byIndex: Map<OctahedronFace, MaterialData> = emptyMap(),
            default: MaterialData = UNASSIGNED_FACE_DEFAULT,
        ) : PerFace(default) {

            /** Returns the material for [face], falling back to [default] if unassigned. */
            public fun resolve(face: OctahedronFace): MaterialData =
                byIndex[face] ?: default

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Octahedron) return false
                return byIndex == other.byIndex && default == other.default
            }

            override fun hashCode(): Int {
                var result = byIndex.hashCode()
                result = 31 * result + default.hashCode()
                return result
            }

            override fun toString(): String =
                "PerFace.Octahedron(byIndex=$byIndex, default=$default)"
        }
    }
}

// -- Per-face resolution ------------------------------------------------------

/**
 * Resolve a [IsometricMaterial.PerFace] instance to its per-face sub-material for
 * the face currently being rendered.
 *
 * Only [IsometricMaterial.PerFace.Prism] dispatches via [faceType] in this slice;
 * the other variants (`Cylinder`, `Pyramid`, `Stairs`, `Octahedron`) ship empty
 * stubs and return [IsometricMaterial.PerFace.default] until their
 * `uv-generation-<shape>` slices wire up per-face resolution.
 *
 * Centralising this dispatch means each downstream shape slice adds exactly one
 * `when` branch here — rather than updating the 3 consumer sites
 * ([io.github.jayteealao.isometric.shader.render.TexturedCanvasDrawHook],
 * `SceneDataPacker`, `GpuTextureManager`) in lockstep, which is a
 * forgotten-update trap across the five shape UV slices.
 */
public fun IsometricMaterial.PerFace.resolveForFace(
    faceType: FaceIdentifier?,
): MaterialData = when (this) {
    is IsometricMaterial.PerFace.Prism -> {
        // Prism commands always carry a PrismFace (see IsometricNode.kt). Mismatched types
        // (e.g. CylinderFace on a Prism command) fall back to default rather than crashing —
        // a diagnostic surface is preferred once richer dispatch lands in the shape slices.
        val prismFace = faceType as? PrismFace
        if (prismFace != null) faceMap[prismFace] ?: default else default
    }
    // TODO(uv-generation-cylinder):   dispatch via faceType as? CylinderFace
    // TODO(uv-generation-pyramid):    dispatch via faceType as? PyramidFace
    // TODO(uv-generation-stairs):     dispatch via faceType as? StairsFace
    // TODO(uv-generation-octahedron): dispatch via faceType as? OctahedronFace
    is IsometricMaterial.PerFace.Cylinder,
    is IsometricMaterial.PerFace.Pyramid,
    is IsometricMaterial.PerFace.Stairs,
    is IsometricMaterial.PerFace.Octahedron -> default
}

// -- DSL builders -------------------------------------------------------------

/**
 * Creates an [IsometricMaterial.Textured] material from a drawable resource.
 *
 * Requires [io.github.jayteealao.isometric.shader.render.ProvideTextureRendering] in the
 * composition or textures will not be rendered.
 *
 * ```kotlin
 * Shape(Prism(origin), material = texturedResource(R.drawable.brick))
 * Shape(Prism(origin), material = texturedResource(R.drawable.brick, tint = IsoColor.RED))
 * Shape(Prism(origin), material = texturedResource(R.drawable.brick,
 *     transform = TextureTransform.tiling(2f, 2f)))
 * ```
 */
fun texturedResource(
    @DrawableRes resId: Int,
    tint: IsoColor = IsoColor.WHITE,
    transform: TextureTransform = TextureTransform.IDENTITY,
): IsometricMaterial.Textured =
    IsometricMaterial.Textured(source = TextureSource.Resource(resId), tint = tint, transform = transform)

/**
 * Creates an [IsometricMaterial.Textured] material from an asset path.
 *
 * Requires [io.github.jayteealao.isometric.shader.render.ProvideTextureRendering] in the
 * composition or textures will not be rendered.
 */
fun texturedAsset(
    path: String,
    tint: IsoColor = IsoColor.WHITE,
    transform: TextureTransform = TextureTransform.IDENTITY,
): IsometricMaterial.Textured =
    IsometricMaterial.Textured(source = TextureSource.Asset(path), tint = tint, transform = transform)

/**
 * Creates an [IsometricMaterial.Textured] material from a [Bitmap].
 *
 * Requires [io.github.jayteealao.isometric.shader.render.ProvideTextureRendering] in the
 * composition or textures will not be rendered.
 */
fun texturedBitmap(
    bitmap: Bitmap,
    tint: IsoColor = IsoColor.WHITE,
    transform: TextureTransform = TextureTransform.IDENTITY,
): IsometricMaterial.Textured =
    IsometricMaterial.Textured(source = TextureSource.Bitmap(bitmap), tint = tint, transform = transform)

/**
 * Creates an [IsometricMaterial.PerFace.Prism] material via a builder with named face properties.
 *
 * The DSL produces a [IsometricMaterial.PerFace.Prism] specifically. For non-Prism
 * shapes (Cylinder, Pyramid, Stairs, Octahedron), construct the matching
 * `PerFace.<Shape>` subclass directly.
 *
 * ```kotlin
 * // Simple: grass top, dirt sides
 * Shape(Prism(origin), material = perFace {
 *     top = texturedResource(R.drawable.grass)
 *     sides = texturedResource(R.drawable.dirt)
 * })
 *
 * // Fine-grained: different materials per side
 * Shape(Prism(origin), material = perFace {
 *     top = texturedResource(R.drawable.grass)
 *     front = texturedResource(R.drawable.dirt_shadow)
 *     left = texturedResource(R.drawable.dirt_light)
 *     default = IsoColor.GRAY
 * })
 * ```
 */
fun perFace(
    block: PerFaceMaterialScope.() -> Unit,
): IsometricMaterial.PerFace.Prism =
    PerFaceMaterialScope().apply(block).build()

// -- Builder classes ----------------------------------------------------------

/** DSL marker that prevents nesting [PerFaceMaterialScope] calls inadvertently. */
@DslMarker
annotation class IsometricMaterialDsl

@IsometricMaterialDsl
class PerFaceMaterialScope internal constructor() {
    var top: MaterialData? = null
    var bottom: MaterialData? = null
    var front: MaterialData? = null
    var back: MaterialData? = null
    var left: MaterialData? = null
    var right: MaterialData? = null
    var default: MaterialData = IsometricMaterial.PerFace.UNASSIGNED_FACE_DEFAULT

    /**
     * Convenience: sets [front], [back], [left], [right] to the same material.
     * Write-only — later individual assignments override.
     */
    var sides: MaterialData?
        @Deprecated("sides is write-only", level = DeprecationLevel.ERROR)
        get() = error("sides is write-only")
        set(value) { front = value; back = value; left = value; right = value }

    internal fun build(): IsometricMaterial.PerFace.Prism {
        val map = java.util.EnumMap<PrismFace, MaterialData>(PrismFace::class.java).apply {
            top?.let { put(PrismFace.TOP, it) }
            bottom?.let { put(PrismFace.BOTTOM, it) }
            front?.let { put(PrismFace.FRONT, it) }
            back?.let { put(PrismFace.BACK, it) }
            left?.let { put(PrismFace.LEFT, it) }
            right?.let { put(PrismFace.RIGHT, it) }
        }
        return IsometricMaterial.PerFace.Prism.of(faceMap = map, default = default)
    }
}
