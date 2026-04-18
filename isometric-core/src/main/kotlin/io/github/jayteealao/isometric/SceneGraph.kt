package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.FaceIdentifier

/**
 * Mutable collection of scene items (paths with colors and metadata).
 * Accumulates items via [add] and provides them for projection via [items].
 */
internal class SceneGraph {
    internal class SceneItem(
        val path: Path,
        val baseColor: IsoColor,
        val originalShape: Shape?,
        val id: String,
        val ownerNodeId: String? = null,
        val material: MaterialData? = null,
        val uvCoords: FloatArray? = null,
        val faceType: FaceIdentifier? = null,
        val faceVertexCount: Int = 4,
    )

    private val _items = mutableListOf<SceneItem>()
    private var nextId = 0

    val items: List<SceneItem> get() = _items

    fun add(shape: Shape, color: IsoColor) {
        val paths = shape.orderedPaths()
        for (path in paths) {
            // Derive faceVertexCount from the actual path rather than defaulting to 4 —
            // shapes with mixed face topology (e.g. Pyramid: 3-vertex laterals +
            // 4-vertex base) would otherwise all be mislabelled as quads, breaking
            // UV/vertex bookkeeping for downstream WebGPU packing.
            //
            // High-vertex caps (Cylinder with 30 sides → 30-vertex cap polygons)
            // exceed the `RenderCommand.faceVertexCount` validator's 3..24 ceiling;
            // clamp at 24 to match the validator. Paths above the cap still render
            // correctly on Canvas (which reads `path.points` directly); WebGPU
            // paths use per-shape UV providers that cap at the shader-side limit
            // independently, so truncating the metadata field here does not lose
            // vertex data in either renderer.
            val vertexCount = path.points.size.coerceIn(3, 24)
            add(path, color, shape, faceVertexCount = vertexCount)
        }
    }

    fun add(
        path: Path,
        color: IsoColor,
        originalShape: Shape? = null,
        id: String? = null,
        ownerNodeId: String? = null,
        material: MaterialData? = null,
        uvCoords: FloatArray? = null,
        faceType: FaceIdentifier? = null,
        faceVertexCount: Int = 4,
    ) {
        _items.add(SceneItem(path, color, originalShape, id ?: "item_${nextId++}", ownerNodeId, material, uvCoords, faceType, faceVertexCount))
    }

    fun clear() {
        _items.clear()
        nextId = 0
    }
}
