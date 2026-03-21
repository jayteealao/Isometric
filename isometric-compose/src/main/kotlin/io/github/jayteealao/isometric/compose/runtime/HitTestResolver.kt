package io.github.jayteealao.isometric.compose.runtime

import io.github.jayteealao.isometric.HitOrder
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.SceneProjector

/**
 * Encapsulates hit-test resolution: spatial indexing, command-to-node mapping,
 * and the actual hit-test algorithm.
 *
 * Responsible for:
 * - Building and querying a [SpatialGrid] for O(k) candidate lookup
 * - Maintaining maps from command IDs to [RenderCommand]s, draw order, and [IsometricNode]s
 * - Delegating to [SceneProjector.findItemAt] for precise point-in-polygon testing
 *
 * This class does NOT own the scene cache — that belongs to [SceneCache].
 */
internal class HitTestResolver(
    private val engine: SceneProjector,
    private val enableSpatialIndex: Boolean,
    private val spatialIndexCellSize: Double
) {
    companion object {
        internal const val HIT_TEST_RADIUS_PX: Double = 8.0
    }

    // Spatial index for O(k) hit testing
    private var spatialIndex: SpatialGrid? = null

    // Maps for hit-test resolution
    private var commandIdMap: Map<String, RenderCommand> = emptyMap()
    private var commandOrderMap: Map<String, Int> = emptyMap()
    private var commandToNodeMap: Map<String, IsometricNode> = emptyMap()
    private var nodeIdMap: Map<String, IsometricNode> = emptyMap()

    /**
     * Perform hit testing against the given prepared scene.
     *
     * Tries the spatial index first (O(k) candidates), then falls back to
     * a full scene scan via [SceneProjector.findItemAt].
     *
     * @return The [IsometricNode] at the given screen coordinates, or null.
     */
    fun hitTest(
        x: Double,
        y: Double,
        preparedScene: PreparedScene
    ): IsometricNode? {
        if (enableSpatialIndex) {
            hitTestSpatial(x, y, preparedScene)?.let { return it }
        }

        val hit = engine.findItemAt(
            preparedScene = preparedScene,
            x = x,
            y = y,
            order = HitOrder.FRONT_TO_BACK,
            touchRadius = HIT_TEST_RADIUS_PX
        ) ?: return null

        return findNodeByCommandId(hit.commandId)
    }

    /**
     * Rebuild all hit-test indices from the node tree and prepared scene.
     *
     * Call this after the [SceneCache] has rebuilt the scene.
     */
    fun rebuildIndices(rootNode: IsometricNode, scene: PreparedScene) {
        nodeIdMap = buildNodeIdMap(rootNode)
        buildCommandMaps(scene)
        if (enableSpatialIndex) {
            buildSpatialIndex(scene)
        }
    }

    /**
     * Clear all hit-test state. Called when the scene cache is invalidated.
     */
    fun clearIndices() {
        spatialIndex = null
        nodeIdMap = emptyMap()
        commandIdMap = emptyMap()
        commandOrderMap = emptyMap()
        commandToNodeMap = emptyMap()
    }

    private fun hitTestSpatial(x: Double, y: Double, preparedScene: PreparedScene): IsometricNode? {
        val index = spatialIndex ?: return null
        val candidateIds = index.query(x, y, HIT_TEST_RADIUS_PX)
        if (candidateIds.isEmpty()) return null

        val candidateCommands = candidateIds
            .mapNotNull { commandIdMap[it] }
            .sortedBy { command -> commandOrderMap[command.commandId] ?: Int.MAX_VALUE }
        if (candidateCommands.isEmpty()) return null

        val filteredScene = PreparedScene(
            commands = candidateCommands,
            width = preparedScene.width,
            height = preparedScene.height
        )

        val hit = engine.findItemAt(
            preparedScene = filteredScene,
            x = x,
            y = y,
            order = HitOrder.FRONT_TO_BACK,
            touchRadius = HIT_TEST_RADIUS_PX
        ) ?: return null

        return findNodeByCommandId(hit.commandId)
    }

    private fun findNodeByCommandId(commandId: String): IsometricNode? {
        return commandToNodeMap[commandId]
    }

    private fun buildCommandMaps(scene: PreparedScene) {
        val cmdMap = HashMap<String, RenderCommand>(scene.commands.size)
        val orderMap = HashMap<String, Int>(scene.commands.size)
        val cmdToNode = HashMap<String, IsometricNode>(scene.commands.size)

        for ((index, command) in scene.commands.withIndex()) {
            cmdMap[command.commandId] = command
            orderMap[command.commandId] = index
            command.ownerNodeId
                ?.let { nodeIdMap[it] }
                ?.let { node -> cmdToNode[command.commandId] = node }
        }
        commandIdMap = cmdMap
        commandOrderMap = orderMap
        commandToNodeMap = cmdToNode
    }

    private fun buildSpatialIndex(scene: PreparedScene) {
        spatialIndex = SpatialGrid(
            width = scene.width.toDouble(),
            height = scene.height.toDouble(),
            cellSize = spatialIndexCellSize
        )

        for (command in scene.commands) {
            val bounds = command.getBounds()
            if (bounds != null) {
                spatialIndex!!.insert(command.commandId, bounds)
            }
        }
    }

    private fun buildNodeIdMap(root: IsometricNode): Map<String, IsometricNode> {
        val map = mutableMapOf<String, IsometricNode>()
        fun visit(node: IsometricNode) {
            val existing = map.put(node.nodeId, node)
            if (existing != null && node.explicitNodeId != null) {
                error(
                    "Duplicate nodeId '${node.nodeId}' detected. " +
                    "nodeId values must be unique within a scene."
                )
            }
            for (child in node.childrenSnapshot) {
                visit(child)
            }
        }
        visit(root)
        return map
    }
}
