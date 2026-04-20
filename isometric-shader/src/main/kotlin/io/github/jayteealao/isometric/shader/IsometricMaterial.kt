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
     * Each wired-up shape has its own per-face DSL: [prismPerFace] for [Prism],
     * [pyramidPerFace] for [Pyramid], [octahedronPerFace] for [Octahedron],
     * [cylinderPerFace] for [Cylinder]. Stubbed shapes (Stairs) construct the matching
     * subclass directly until their `uv-generation-<shape>` slices ship a DSL.
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
                 * Prefer the [prismPerFace] DSL for typical usage; use this factory when you
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
         * Per-face rendering is fully wired: [uvCoordProviderForShape] registers the
         * Cylinder UV provider (sides use seam-duplicated `u = k/N` mapping, caps use
         * planar disk projection centered at `(0.5, 0.5)`); [resolveForFace] dispatches
         * via `faceType as? CylinderFace`; and `GpuTextureManager.collectTextureSources`
         * aggregates textures from [top], [bottom], and [side].
         *
         * Prefer the `cylinderPerFace { }` DSL over this constructor for readability:
         *
         * ```kotlin
         * val material = cylinderPerFace {
         *     top = texturedResource(R.drawable.grass)
         *     bottom = texturedResource(R.drawable.dirt)
         *     side = texturedResource(R.drawable.brick)
         *     default = IsoColor.GRAY
         * }
         * ```
         *
         * @property top Material for the top cap (path index 1)
         * @property bottom Material for the bottom cap (path index 0)
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
         * fall back to [default]. [base] applies to the rectangular base quad (path
         * index 4, added by the `uv-generation-pyramid` slice).
         *
         * Per-face rendering is fully wired: [uvCoordProviderForShape] registers the
         * Pyramid UV provider (laterals use a canonical apex-at-top triangle layout,
         * base uses a top-down planar projection); [resolveForFace] dispatches via
         * `faceType as? PyramidFace`; and `GpuTextureManager.collectTextureSources`
         * aggregates textures from [base] and [laterals].
         *
         * Prefer the [pyramidPerFace] DSL for construction — it avoids boilerplate
         * `PyramidFace.Lateral(n)` keys and offers an `allLaterals(material)` convenience.
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
         * Per-face rendering is live: [uvCoordProviderForShape] returns a provider
         * for [io.github.jayteealao.isometric.shapes.Stairs], [resolveForFace]
         * dispatches via [StairsFace], and `GpuTextureManager.collectTextureSources`
         * collects the `tread`/`riser`/`side` textures alongside the other shape
         * variants.
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
         * Per-face rendering is fully wired: [uvCoordProviderForShape] registers the
         * Octahedron UV provider, [resolveForFace] dispatches via `faceType as? OctahedronFace`,
         * and `GpuTextureManager.collectTextureSources` aggregates textures from [byIndex].
         * All 8 faces map to the same canonical triangle UV `(0,0)–(1,0)–(0.5,1)` —
         * per-face textures differ in material content, not UV layout.
         *
         * Prefer the [octahedronPerFace] DSL for construction — it accepts enum-keyed slot
         * assignments without the `byIndex = mapOf(...)` boilerplate.
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
 * All [IsometricMaterial.PerFace] variants dispatch via [faceType] today
 * ([IsometricMaterial.PerFace.Prism], [IsometricMaterial.PerFace.Octahedron],
 * [IsometricMaterial.PerFace.Pyramid], [IsometricMaterial.PerFace.Cylinder],
 * and [IsometricMaterial.PerFace.Stairs]).
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
    is IsometricMaterial.PerFace.Octahedron -> {
        val octahedronFace = faceType as? OctahedronFace
        if (octahedronFace != null) byIndex[octahedronFace] ?: default else default
    }
    is IsometricMaterial.PerFace.Pyramid -> {
        val pyramidFace = faceType as? PyramidFace
        if (pyramidFace != null) resolve(pyramidFace) else default
    }
    is IsometricMaterial.PerFace.Cylinder -> {
        val cylinderFace = faceType as? CylinderFace
        if (cylinderFace != null) resolve(cylinderFace) else default
    }
    is IsometricMaterial.PerFace.Stairs -> {
        val stairsFace = faceType as? StairsFace
        if (stairsFace != null) resolve(stairsFace) else default
    }
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
 * Pairs with [pyramidPerFace] and [octahedronPerFace] — one shape-specific DSL per
 * per-face-supporting shape, named `<shape>PerFace` for discoverability.
 *
 * ```kotlin
 * // Simple: grass top, dirt sides
 * Shape(Prism(origin), material = prismPerFace {
 *     top = texturedResource(R.drawable.grass)
 *     sides = texturedResource(R.drawable.dirt)
 * })
 *
 * // Fine-grained: different materials per side
 * Shape(Prism(origin), material = prismPerFace {
 *     top = texturedResource(R.drawable.grass)
 *     front = texturedResource(R.drawable.dirt_shadow)
 *     left = texturedResource(R.drawable.dirt_light)
 *     default = IsoColor.GRAY
 * })
 * ```
 */
fun prismPerFace(
    block: PrismPerFaceMaterialScope.() -> Unit,
): IsometricMaterial.PerFace.Prism =
    PrismPerFaceMaterialScope().apply(block).build()

/**
 * Creates an [IsometricMaterial.PerFace.Pyramid] via a builder with named face slots.
 *
 * Pyramid has two face categories — the rectangular [PyramidPerFaceMaterialScope.base]
 * and four triangular laterals addressed by index 0..3. Use [PyramidPerFaceMaterialScope.allLaterals]
 * to paint every lateral with the same material, then override individual laterals via
 * [PyramidPerFaceMaterialScope.lateral] when needed.
 *
 * ```kotlin
 * Shape(Pyramid(origin), material = pyramidPerFace {
 *     base = texturedResource(R.drawable.dirt)
 *     allLaterals(texturedResource(R.drawable.brick))
 * })
 *
 * Shape(Pyramid(origin), material = pyramidPerFace {
 *     lateral(0, IsoColor.RED)
 *     lateral(1, IsoColor.GREEN)
 *     lateral(2, IsoColor.BLUE)
 *     lateral(3, IsoColor.YELLOW)
 *     base = IsoColor.GRAY
 * })
 * ```
 */
fun pyramidPerFace(
    block: PyramidPerFaceMaterialScope.() -> Unit,
): IsometricMaterial.PerFace.Pyramid =
    PyramidPerFaceMaterialScope().apply(block).build()

/**
 * Creates an [IsometricMaterial.PerFace.Octahedron] via a builder with enum-keyed face slots.
 *
 * Octahedron has eight individually addressable triangular faces. Use
 * [OctahedronPerFaceMaterialScope.face] to assign a material to a specific
 * [OctahedronFace] slot, or [OctahedronPerFaceMaterialScope.allFaces] to paint every
 * face with the same material.
 *
 * ```kotlin
 * Shape(Octahedron(origin), material = octahedronPerFace {
 *     allFaces(texturedResource(R.drawable.stone))
 * })
 *
 * Shape(Octahedron(origin), material = octahedronPerFace {
 *     face(OctahedronFace.UPPER_0, IsoColor.RED)
 *     face(OctahedronFace.UPPER_1, IsoColor.GREEN)
 *     face(OctahedronFace.UPPER_2, IsoColor.BLUE)
 *     face(OctahedronFace.UPPER_3, IsoColor.YELLOW)
 *     default = IsoColor.GRAY
 * })
 * ```
 */
fun octahedronPerFace(
    block: OctahedronPerFaceMaterialScope.() -> Unit,
): IsometricMaterial.PerFace.Octahedron =
    OctahedronPerFaceMaterialScope().apply(block).build()

/**
 * Creates an [IsometricMaterial.PerFace.Cylinder] via a builder with named face slots.
 *
 * Cylinder has three named face regions: the [CylinderPerFaceMaterialScope.top] cap,
 * the [CylinderPerFaceMaterialScope.bottom] cap, and the [CylinderPerFaceMaterialScope.side]
 * barrel (which covers every side quad at once — per-band addressability is not
 * exposed here; use a textured material with a `TextureTransform` for that).
 *
 * ```kotlin
 * Shape(Cylinder(origin), material = cylinderPerFace {
 *     top = texturedResource(R.drawable.grass)
 *     bottom = texturedResource(R.drawable.dirt)
 *     side = texturedResource(R.drawable.brick)
 *     default = IsoColor.GRAY
 * })
 * ```
 */
fun cylinderPerFace(
    block: CylinderPerFaceMaterialScope.() -> Unit,
): IsometricMaterial.PerFace.Cylinder =
    CylinderPerFaceMaterialScope().apply(block).build()

// -- Builder classes ----------------------------------------------------------

/** DSL marker that prevents nesting per-face scope calls inadvertently. */
@DslMarker
annotation class IsometricMaterialDsl

@IsometricMaterialDsl
class PrismPerFaceMaterialScope internal constructor() {
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

@IsometricMaterialDsl
class PyramidPerFaceMaterialScope internal constructor() {
    /** Material for the rectangular base quad (path index 4). */
    var base: MaterialData? = null

    /** Fallback for the base or any lateral not explicitly assigned. */
    var default: MaterialData = IsometricMaterial.PerFace.UNASSIGNED_FACE_DEFAULT

    private val lateralMap = mutableMapOf<PyramidFace.Lateral, MaterialData>()

    /**
     * Assigns [material] to the lateral face at [index] (0..3).
     *
     * @throws IllegalArgumentException if [index] is outside 0..3
     *   (enforced by [PyramidFace.Lateral]'s init block).
     */
    fun lateral(index: Int, material: MaterialData) {
        lateralMap[PyramidFace.Lateral(index)] = material
    }

    /** Convenience: assigns [material] to all four lateral faces. */
    fun allLaterals(material: MaterialData) {
        for (i in 0..3) lateralMap[PyramidFace.Lateral(i)] = material
    }

    internal fun build(): IsometricMaterial.PerFace.Pyramid =
        IsometricMaterial.PerFace.Pyramid(
            base = base,
            laterals = lateralMap.toMap(),
            default = default,
        )
}

@IsometricMaterialDsl
class OctahedronPerFaceMaterialScope internal constructor() {
    /** Fallback for any face not explicitly assigned via [face] or [allFaces]. */
    var default: MaterialData = IsometricMaterial.PerFace.UNASSIGNED_FACE_DEFAULT

    private val faceMap = java.util.EnumMap<OctahedronFace, MaterialData>(OctahedronFace::class.java)

    /** Assigns [material] to the given [OctahedronFace] slot. */
    fun face(face: OctahedronFace, material: MaterialData) {
        faceMap[face] = material
    }

    /** Convenience: assigns [material] to every one of the eight faces. */
    fun allFaces(material: MaterialData) {
        for (f in OctahedronFace.entries) faceMap[f] = material
    }

    internal fun build(): IsometricMaterial.PerFace.Octahedron =
        IsometricMaterial.PerFace.Octahedron(
            byIndex = if (faceMap.isEmpty()) emptyMap() else faceMap.toMap(),
            default = default,
        )
}

@IsometricMaterialDsl
class CylinderPerFaceMaterialScope internal constructor() {
    /** Material for the top cap (path index 1). */
    var top: MaterialData? = null

    /** Material for the bottom cap (path index 0). */
    var bottom: MaterialData? = null

    /** Material for every side quad (path indices 2..). */
    var side: MaterialData? = null

    /** Fallback for any slot left null. */
    var default: MaterialData = IsometricMaterial.PerFace.UNASSIGNED_FACE_DEFAULT

    internal fun build(): IsometricMaterial.PerFace.Cylinder =
        IsometricMaterial.PerFace.Cylinder(
            top = top,
            bottom = bottom,
            side = side,
            default = default,
        )
}
