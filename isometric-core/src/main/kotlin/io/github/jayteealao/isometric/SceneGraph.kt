package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.PrismFace

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
        val faceType: PrismFace? = null,
    )

    private val _items = mutableListOf<SceneItem>()
    private var nextId = 0

    val items: List<SceneItem> get() = _items

    fun add(shape: Shape, color: IsoColor) {
        val paths = shape.orderedPaths()
        for (path in paths) {
            add(path, color, shape)
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
        faceType: PrismFace? = null,
    ) {
        _items.add(SceneItem(path, color, originalShape, id ?: "item_${nextId++}", ownerNodeId, material, uvCoords, faceType))
    }

    fun clear() {
        _items.clear()
        nextId = 0
    }
}
