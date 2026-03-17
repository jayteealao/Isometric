package io.github.jayteealao.isometric

/**
 * Mutable collection of scene items (paths with colors and metadata).
 * Accumulates items via [add] and provides them for projection via [items].
 */
internal class SceneGraph {
    internal data class SceneItem(
        val path: Path,
        val baseColor: IsoColor,
        val originalShape: Shape?,
        val id: String,
        val ownerNodeId: String? = null
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
        ownerNodeId: String? = null
    ) {
        _items.add(SceneItem(path, color, originalShape, id ?: "item_${nextId++}", ownerNodeId))
    }

    fun clear() {
        _items.clear()
        nextId = 0
    }
}
