package io.fabianterhorst.isometric

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

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
}
