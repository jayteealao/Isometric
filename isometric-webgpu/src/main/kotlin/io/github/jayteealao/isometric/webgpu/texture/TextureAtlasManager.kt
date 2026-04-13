package io.github.jayteealao.isometric.webgpu.texture

import android.graphics.Bitmap
import android.util.Log
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureView
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.webgpu.GpuContext

/**
 * Packs multiple bitmaps into a single GPU texture atlas using shelf packing.
 *
 * Each distinct [TextureSource] is packed into a region of the atlas. The atlas
 * is rebuilt from scratch on each [rebuild] call (no incremental updates in this
 * version). UV regions include a half-pixel inset to prevent bilinear sampling
 * from bleeding across texture boundaries.
 *
 * ## Shelf packing algorithm
 *
 * Bitmaps are placed left-to-right on horizontal shelves. When a bitmap doesn't
 * fit on the current shelf, a new shelf starts below. Shelf height is the tallest
 * bitmap on that shelf. A 2px gutter is added between entries.
 *
 * ## Lifetime
 *
 * Create once per [GpuFullPipeline]. Call [rebuild] when the set of textures
 * changes. Call [destroy] on teardown.
 */
internal class TextureAtlasManager(
    private val ctx: GpuContext,
    private val textureStore: GpuTextureStore,
    private val maxAtlasSizePx: Int = 2048,
    private val paddingPx: Int = 2,
) {
    companion object {
        private const val TAG = "TextureAtlasManager"
    }

    /**
     * A region within the atlas for a single texture entry.
     *
     * @property uvOffset Atlas-space UV offset (bottom-left corner with half-pixel inset)
     * @property uvScale Atlas-space UV scale factor for this region
     */
    data class AtlasRegion(
        val uvOffset: FloatArray,  // [u0, v0]
        val uvScale: FloatArray,   // [uScale, vScale]
    ) {
        override fun equals(other: Any?): Boolean =
            other is AtlasRegion &&
                uvOffset.contentEquals(other.uvOffset) &&
                uvScale.contentEquals(other.uvScale)

        override fun hashCode(): Int =
            31 * uvOffset.contentHashCode() + uvScale.contentHashCode()
    }

    /** Current atlas GPU texture (null before first rebuild). */
    private var atlasTexture: GPUTexture? = null

    /** Current atlas texture view. */
    private var atlasTextureView: GPUTextureView? = null

    /** Lookup from TextureSource to its packed atlas region. */
    private val regionMap = mutableMapOf<TextureSource, AtlasRegion>()

    /** Current atlas dimensions. */
    private var atlasWidth = 0
    private var atlasHeight = 0

    /** The atlas texture view for binding. Null before first rebuild. */
    val textureView: GPUTextureView? get() = atlasTextureView

    /** Whether the atlas has been built at least once. */
    val isBuilt: Boolean get() = atlasTexture != null

    /**
     * Look up the atlas region for a [TextureSource]. Returns null if not packed.
     */
    fun getRegion(source: TextureSource): AtlasRegion? = regionMap[source]

    /**
     * Rebuild the atlas from scratch with the given set of textures.
     *
     * @param entries Map from [TextureSource] to resolved [Bitmap]. All bitmaps must
     *   be ARGB_8888 and not recycled.
     * @return true if the atlas was rebuilt, false if empty (no textures).
     */
    fun rebuild(entries: Map<TextureSource, Bitmap>): Boolean {
        // Release previous atlas
        releaseAtlas()
        regionMap.clear()

        if (entries.isEmpty()) return false

        // Sort entries by height descending for better shelf packing
        val sorted = entries.entries.sortedByDescending { it.value.height }

        // Compute atlas dimensions using shelf packing simulation
        val layout = computeShelfLayout(sorted.map { it.value.width to it.value.height })
        if (layout == null) {
            Log.e(TAG, "Atlas overflow: ${entries.size} textures exceed ${maxAtlasSizePx}x${maxAtlasSizePx}px atlas — using fallback texture")
            return false
        }

        atlasWidth = layout.atlasWidth
        atlasHeight = layout.atlasHeight

        Log.d(TAG, "Atlas: ${atlasWidth}x${atlasHeight} with ${entries.size} textures")

        // Create the atlas GPU texture
        val atlasBitmap = Bitmap.createBitmap(atlasWidth, atlasHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(atlasBitmap)

        // Pack each entry into the atlas bitmap and record UV regions
        for ((i, entry) in sorted.withIndex()) {
            val (source, bitmap) = entry
            val placement = layout.placements[i]

            // Draw bitmap at the placement position
            canvas.drawBitmap(bitmap, placement.x.toFloat(), placement.y.toFloat(), null)

            // Compute UV region with half-pixel inset to prevent bleeding
            val halfPixelU = 0.5f / atlasWidth
            val halfPixelV = 0.5f / atlasHeight

            val u0 = placement.x.toFloat() / atlasWidth + halfPixelU
            val v0 = placement.y.toFloat() / atlasHeight + halfPixelV
            val uScale = (bitmap.width.toFloat() / atlasWidth) - 2f * halfPixelU
            val vScale = (bitmap.height.toFloat() / atlasHeight) - 2f * halfPixelV

            regionMap[source] = AtlasRegion(
                uvOffset = floatArrayOf(u0, v0),
                uvScale = floatArrayOf(uScale, vScale),
            )
        }

        // Upload atlas bitmap to GPU
        val gpuTex = textureStore.uploadBitmap(atlasBitmap)
        atlasTexture = gpuTex
        atlasTextureView = gpuTex.createView()

        // Recycle the temporary composite bitmap
        atlasBitmap.recycle()

        return true
    }

    private fun releaseAtlas() {
        atlasTextureView?.close()
        atlasTextureView = null
        if (atlasTexture != null) {
            textureStore.releaseTexture(atlasTexture!!)
            atlasTexture = null
        }
        atlasWidth = 0
        atlasHeight = 0
    }

    fun destroy() {
        releaseAtlas()
        regionMap.clear()
    }

    // ── Shelf packing ────────────────────────────────────────────────────────

    private data class Placement(val x: Int, val y: Int)

    private data class ShelfLayout(
        val atlasWidth: Int,
        val atlasHeight: Int,
        val placements: List<Placement>,
    )

    /**
     * Simulate shelf packing for the given sizes (sorted by height descending).
     * Returns atlas dimensions and placement coordinates for each entry, or null
     * if the textures cannot fit within [maxAtlasSizePx]x[maxAtlasSizePx].
     */
    private fun computeShelfLayout(sizes: List<Pair<Int, Int>>): ShelfLayout? {
        if (sizes.isEmpty()) return ShelfLayout(0, 0, emptyList())

        // Start with the smallest power-of-two that fits the widest texture
        val maxW = sizes.maxOf { it.first }
        var atlasW = nextPowerOfTwo(maxOf(maxW + paddingPx, 512).coerceAtMost(maxAtlasSizePx))

        // Try packing at this width; if it overflows vertically, double the width
        while (atlasW <= maxAtlasSizePx) {
            val result = tryPack(sizes, atlasW)
            if (result != null) return result
            atlasW *= 2
        }

        // Final attempt at max size; return null on overflow to signal failure
        return tryPack(sizes, maxAtlasSizePx)
    }

    private fun tryPack(sizes: List<Pair<Int, Int>>, atlasW: Int): ShelfLayout? {
        val placements = mutableListOf<Placement>()
        var shelfX = 0
        var shelfY = 0
        var shelfHeight = 0

        for ((w, h) in sizes) {
            val paddedW = w + paddingPx
            val paddedH = h + paddingPx

            if (shelfX + paddedW > atlasW) {
                // Start new shelf
                shelfY += shelfHeight
                shelfX = 0
                shelfHeight = 0
            }

            placements.add(Placement(shelfX, shelfY))
            shelfX += paddedW
            shelfHeight = maxOf(shelfHeight, paddedH)
        }

        val totalHeight = shelfY + shelfHeight
        val atlasH = nextPowerOfTwo(totalHeight.coerceAtLeast(1).coerceAtMost(maxAtlasSizePx))

        return if (totalHeight <= maxAtlasSizePx) {
            ShelfLayout(atlasW, atlasH, placements)
        } else {
            null
        }
    }

    private fun nextPowerOfTwo(v: Int): Int {
        var n = v - 1
        n = n or (n shr 1)
        n = n or (n shr 2)
        n = n or (n shr 4)
        n = n or (n shr 8)
        n = n or (n shr 16)
        return (n + 1).coerceAtLeast(1)
    }
}
