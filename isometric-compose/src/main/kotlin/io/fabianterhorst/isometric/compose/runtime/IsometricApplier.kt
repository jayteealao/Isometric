package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.AbstractApplier

/**
 * Custom Applier that teaches the Compose runtime how to build and update
 * the isometric node tree.
 *
 * This is the bridge between Compose's declarative API and our imperative node tree.
 */
class IsometricApplier(
    root: GroupNode
) : AbstractApplier<IsometricNode>(root) {

    /**
     * Set of GroupNodes modified during the current change batch.
     * Snapshot updates and dirty propagation are deferred to onEndChanges().
     */
    private val modifiedGroups = mutableSetOf<GroupNode>()
    private var batching = false

    override fun onBeginChanges() {
        super.onBeginChanges()
        batching = true
    }

    override fun onEndChanges() {
        batching = false
        // Update snapshots and propagate dirty once per batch
        modifiedGroups.forEach { group ->
            group.updateChildrenSnapshot()
            group.markDirty()
        }
        modifiedGroups.clear()
        super.onEndChanges()
    }

    /**
     * Insert nodes top-down.
     * We ignore this and use bottom-up insertion for efficiency.
     */
    override fun insertTopDown(index: Int, instance: IsometricNode) {
        // Ignored - we insert bottom-up
    }

    /**
     * Insert nodes bottom-up.
     * This is called after the entire subtree has been composed.
     */
    override fun insertBottomUp(index: Int, instance: IsometricNode) {
        val parent = current as? GroupNode
            ?: error("Can only insert children into GroupNode, but current is ${current::class.simpleName}")

        parent.children.add(index, instance)
        instance.parent = parent

        if (batching) {
            modifiedGroups.add(parent)
        } else {
            parent.updateChildrenSnapshot()
            parent.markDirty()
        }
    }

    /**
     * Remove nodes from the tree
     */
    override fun remove(index: Int, count: Int) {
        val parent = current as? GroupNode
            ?: error("Can only remove children from GroupNode, but current is ${current::class.simpleName}")

        repeat(count) {
            val removed = parent.children.removeAt(index)
            removed.parent = null
        }

        if (batching) {
            modifiedGroups.add(parent)
        } else {
            parent.updateChildrenSnapshot()
            parent.markDirty()
        }
    }

    /**
     * Move nodes within the same parent
     */
    override fun move(from: Int, to: Int, count: Int) {
        val parent = current as? GroupNode
            ?: error("Can only move children within GroupNode, but current is ${current::class.simpleName}")

        parent.children.move(from, to, count)

        if (batching) {
            modifiedGroups.add(parent)
        } else {
            parent.updateChildrenSnapshot()
            parent.markDirty()
        }
    }

    /**
     * Clear all children from a node
     */
    override fun onClear() {
        val node = current
        if (node is GroupNode) {
            node.children.forEach { it.parent = null }
            node.children.clear()

            if (batching) {
                modifiedGroups.add(node)
            } else {
                node.updateChildrenSnapshot()
                node.markDirty()
            }
        }
    }
}

/**
 * Extension function to move items in a mutable list
 */
private fun <T> MutableList<T>.move(from: Int, to: Int, count: Int) {
    // Early return for no-op cases
    if (from == to || count == 0) return

    val items = mutableListOf<T>()

    // Extract items to move
    repeat(count) {
        items.add(removeAt(from))
    }

    // Calculate adjusted insertion index
    val adjustedTo = if (from < to) to - count else to

    // Insert items at new location
    addAll(adjustedTo, items)
}
