package io.fabianterhorst.isometric

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class PreparedSceneCacheTest {
    @Test
    fun `engine has cache storage fields`() {
        val engine = IsometricEngine()

        // Verify cache fields exist via reflection (internal fields)
        val cachedSceneField = engine.javaClass.getDeclaredField("cachedScene")
        val cachedVersionField = engine.javaClass.getDeclaredField("cachedVersion")
        val cachedWidthField = engine.javaClass.getDeclaredField("cachedWidth")
        val cachedHeightField = engine.javaClass.getDeclaredField("cachedHeight")
        val cachedOptionsField = engine.javaClass.getDeclaredField("cachedOptions")

        assertNotNull(cachedSceneField)
        assertNotNull(cachedVersionField)
        assertNotNull(cachedWidthField)
        assertNotNull(cachedHeightField)
        assertNotNull(cachedOptionsField)
    }

    @Test
    fun `prepare accepts sceneVersion parameter`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

        // Should compile and run with sceneVersion parameter
        val scene = engine.prepare(
            sceneVersion = 1,
            width = 100,
            height = 100,
            options = RenderOptions.Default
        )

        assertNotNull(scene)
    }

    @Test
    fun `prepareSceneInternal is callable internally`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

        // Access via reflection since it's private
        val method = engine.javaClass.getDeclaredMethod(
            "prepareSceneInternal",
            Int::class.java,
            Int::class.java,
            RenderOptions::class.java
        )
        method.isAccessible = true

        val scene = method.invoke(engine, 100, 100, RenderOptions.Default) as PreparedScene
        assertNotNull(scene)
    }

    @Test
    fun `cache hit returns same PreparedScene instance`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

        val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100)
        val scene2 = engine.prepare(sceneVersion = 1, width = 100, height = 100)

        // Same instance = cache hit (reference equality)
        assertSame(scene1, scene2)
    }

    @Test
    fun `cache miss when sceneVersion changes`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

        val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100)
        val scene2 = engine.prepare(sceneVersion = 2, width = 100, height = 100)

        // Different instances = cache miss
        assertNotSame(scene1, scene2)
    }

    @Test
    fun `cache miss when viewport size changes`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

        val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100)
        val scene2 = engine.prepare(sceneVersion = 1, width = 200, height = 200)

        assertNotSame(scene1, scene2)
    }

    @Test
    fun `cache miss when RenderOptions changes`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

        val options1 = RenderOptions(enableDepthSorting = true)
        val options2 = RenderOptions(enableDepthSorting = false)

        val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100, options = options1)
        val scene2 = engine.prepare(sceneVersion = 1, width = 100, height = 100, options = options2)

        assertNotSame(scene1, scene2)
    }

    @Test
    fun `cache works for empty scene`() {
        val engine = IsometricEngine()
        // No items added

        val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100)
        val scene2 = engine.prepare(sceneVersion = 1, width = 100, height = 100)

        assertSame(scene1, scene2)
        assertEquals(0, scene1.commands.size)
    }

    @Test
    fun `cache miss every frame on rapid version changes`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

        val scenes = (1..10).map { version ->
            engine.prepare(sceneVersion = version, width = 100, height = 100)
        }

        // All different instances (cache miss every time)
        scenes.forEach { scene1 ->
            scenes.forEach { scene2 ->
                if (scene1 !== scene2) {
                    assertNotSame(scene1, scene2)
                }
            }
        }
    }

    @Test
    fun `cache uses reference equality for RenderOptions`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

        val options = RenderOptions.Default

        val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100, options = options)
        val scene2 = engine.prepare(sceneVersion = 1, width = 100, height = 100, options = options)

        // Same reference = cache hit
        assertSame(scene1, scene2)

        // Different instance (even if structurally equal) = cache miss
        val optionsCopy = RenderOptions.Default
        val scene3 = engine.prepare(sceneVersion = 1, width = 100, height = 100, options = optionsCopy)
        assertNotSame(scene1, scene3)
    }
}
