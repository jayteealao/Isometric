package io.github.jayteealao.isometric.webgpu.texture

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.webgpu.TextureFormat
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.ProjectionParams
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.Vector
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.webgpu.GpuContext
import io.github.jayteealao.isometric.webgpu.pipeline.GpuRenderPipeline
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for `onTextureLoadError` callback behaviour in [GpuTextureManager].
 *
 * These tests require a real [GpuContext] (WebGPU device + compiled shader pipeline) and
 * run on the connected emulator or device.
 *
 * Run with:
 * ```
 * ./gradlew :isometric-webgpu:connectedDebugAndroidTest \
 *     --tests "*.GpuTextureManagerInstrumentedTest"
 * ```
 *
 * Device: `emulator-5554` (Medium Phone API 36) — same as interactive AC verification.
 *
 * ## Test design notes
 *
 * Both tests compile the full WGSL render pipeline (required to initialize the
 * [GpuTextureBinder] inside [GpuTextureManager.ensurePipelines]).
 *
 * **T-02** uses `maxAtlasSizePx = 8` so any 16×16 bitmap deterministically overflows
 * the atlas, triggering the `!atlasManager.rebuild(entries)` failure branch and the
 * bulk-fire callback loop. This avoids allocating the ~17 MB of bitmap memory that a
 * genuine 2049×2049 overflow would require.
 *
 * **Callback delivery:** callbacks are dispatched via `mainHandler.post` on the GPU
 * thread and delivered on the main looper. A [CountDownLatch] bridges the GPU-thread
 * dispatch and the test-thread assertion.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GpuTextureManagerInstrumentedTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Minimal single-command [PreparedScene] carrying the given [source] as a
     * [IsometricMaterial.Textured] material. Geometric data is arbitrary —
     * [GpuTextureManager.uploadTextures] only reads `cmd.material` for the atlas path.
     */
    private fun sceneWith(vararg sources: TextureSource): PreparedScene {
        val commands = sources.mapIndexed { i, source ->
            RenderCommand(
                commandId = "test-$i",
                points = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.5, 1.0),
                color = IsoColor(255.0, 0.0, 0.0),
                originalPath = Path(listOf(
                    Point(0.0, 0.0, 0.0),
                    Point(1.0, 0.0, 0.0),
                    Point(0.0, 1.0, 0.0),
                )),
                originalShape = null,
                material = IsometricMaterial.Textured(source),
            )
        }
        return PreparedScene(
            commands = commands,
            width = 100,
            height = 100,
            projectionParams = ProjectionParams(1.0, 0.5, -1.0, 0.5, 20.0, 0.5, IsoColor.WHITE),
            lightDirection = Vector(1.0, 1.0, 1.0),
        )
    }

    // ── T-02: Atlas rebuild failure fires callback for every source ────────────

    /**
     * [T-02] Atlas-rebuild-failure callback fire site.
     *
     * Uses `maxAtlasSizePx = 8` so two 16×16 bitmaps deterministically overflow the atlas,
     * triggering `!atlasManager.rebuild(entries)` → `onTextureLoadError` fires for both.
     *
     * Also verifies the SPAM-1 fix: uploading the **same** scene a second time does NOT
     * re-fire the callback (the `lastAtlasSignature` cache hit short-circuits the dispatch).
     */
    @Test
    fun onTextureLoadError_fired_forEachSourceOnAtlasRebuildFailure() = runBlocking {
        val errors = Collections.synchronizedList(mutableListOf<TextureSource>())
        val latch = CountDownLatch(2)

        val ctx = GpuContext.create()
        try {
            ctx.withGpu {
                val renderPipeline = GpuRenderPipeline(ctx, TextureFormat.RGBA8Unorm)
                renderPipeline.ensurePipeline()

                // maxAtlasSizePx = 8 so any 16×16 bitmap causes atlas overflow
                val manager = GpuTextureManager(
                    ctx,
                    onTextureLoadError = { source ->
                        errors.add(source)
                        latch.countDown()
                    },
                    maxAtlasSizePx = 8,
                )
                manager.ensurePipelines(renderPipeline)

                val bmp1 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                val bmp2 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                val src1 = TextureSource.Bitmap(bmp1)
                val src2 = TextureSource.Bitmap(bmp2)
                val scene = sceneWith(src1, src2)

                // First upload: atlas overflows, callbacks fire for both sources
                manager.uploadTextures(scene, 0)

                // Second upload (same scene): lastAtlasSignature cache hit → no re-fire
                manager.uploadTextures(scene, 0)

                manager.close()
                renderPipeline.close()
                bmp1.recycle()
                bmp2.recycle()
            }
        } finally {
            ctx.destroy()
        }

        // Wait for the 2 mainHandler.post callbacks to arrive on the main looper
        assertTrue("onTextureLoadError not received within timeout", latch.await(2, TimeUnit.SECONDS))

        // Exactly 2 callbacks — one per source, from the first upload only (SPAM-1)
        assertEquals(
            "Expected exactly 2 callbacks (one per source); second upload must be suppressed by SPAM-1 fix",
            2,
            errors.size,
        )
    }

    // ── T-05: Valid Bitmap source does not fire callback ──────────────────────

    /**
     * [T-05] Positive path — callback must NOT be invoked for a valid [TextureSource.Bitmap]
     * that fits in the atlas.
     *
     * A 64×64 bitmap packs easily into the default 2048×2048 atlas. The callback should
     * never fire. A spurious fire would indicate a bug in the callback dispatch logic.
     */
    @Test
    fun onTextureLoadError_notFired_forValidBitmapSource() = runBlocking {
        var callbackFired = false

        val ctx = GpuContext.create()
        try {
            ctx.withGpu {
                val renderPipeline = GpuRenderPipeline(ctx, TextureFormat.RGBA8Unorm)
                renderPipeline.ensurePipeline()

                val manager = GpuTextureManager(ctx, onTextureLoadError = { callbackFired = true })
                manager.ensurePipelines(renderPipeline)

                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val src = TextureSource.Bitmap(bmp)

                manager.uploadTextures(sceneWith(src), 0)

                manager.close()
                renderPipeline.close()
                bmp.recycle()
            }
        } finally {
            ctx.destroy()
        }

        // Allow the main looper to drain any queued posts before asserting
        Thread.sleep(300)
        assertFalse("onTextureLoadError must not fire for a valid Bitmap source", callbackFired)
    }
}
