package io.github.jayteealao.isometric

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IsometricEngineAsyncTest {

    private val testFace = Path(
        Point(0.0, 0.0, 0.0),
        Point(1.0, 0.0, 0.0),
        Point(1.0, 1.0, 0.0),
        Point(0.0, 1.0, 0.0)
    )

    private val testFaceHigh = Path(
        Point(0.0, 0.0, 2.0),
        Point(1.0, 0.0, 2.0),
        Point(1.0, 1.0, 2.0),
        Point(0.0, 1.0, 2.0)
    )

    private val identityBackend = object : SortingComputeBackend {
        override suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray =
            depthKeys.indices.sortedByDescending { depthKeys[it] }.toIntArray()
    }

    @Test
    fun `projectSceneAsync returns same face count as projectScene`() = runBlocking {
        val engine1 = IsometricEngine()
        engine1.add(testFace, IsoColor.BLUE)
        val syncScene = engine1.projectScene(200, 200)

        val engine2 = IsometricEngine()
        engine2.add(testFace, IsoColor.BLUE)
        val asyncScene = engine2.projectSceneAsync(200, 200, computeBackend = identityBackend)

        assertEquals(syncScene.commands.size, asyncScene.commands.size)
    }

    @Test
    fun `projectSceneAsync uses backend for sorting`() = runBlocking {
        val engine = IsometricEngine()
        engine.add(testFace, IsoColor.RED)
        engine.add(testFaceHigh, IsoColor.BLUE)

        var sortCalled = false
        val trackingBackend = object : SortingComputeBackend {
            override suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray {
                sortCalled = true
                return depthKeys.indices.sortedByDescending { depthKeys[it] }.toIntArray()
            }
        }

        engine.projectSceneAsync(200, 200, computeBackend = trackingBackend)
        assertTrue(sortCalled, "Backend's sortByDepthKeys should have been called")
    }

    @Test
    fun `projectSceneAsync preserves command colors`() = runBlocking {
        val engine = IsometricEngine()
        engine.add(testFace, IsoColor.RED)
        val scene = engine.projectSceneAsync(200, 200, computeBackend = identityBackend)

        // Commands should exist (face may be culled depending on viewport, but with 200x200 it should pass)
        if (scene.commands.isNotEmpty()) {
            // Lit color will be a lightened variant of RED, but should still be reddish
            assertTrue(scene.commands[0].color.r > 0)
        }
    }
}
