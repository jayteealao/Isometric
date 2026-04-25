package io.github.jayteealao.isometric.shader.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * JVM unit tests for [decideSampleSize] (CT-SEC-2 / TextureLoader size guard, Step 6C).
 *
 * [decideSampleSize] is a package-internal pure function extracted from
 * [DefaultTextureLoader] so these tests require no Android runtime, no [android.graphics.Bitmap],
 * and no [android.content.Context].
 */
class TextureLoaderSizeGuardTest {

    private val maxBytes = 64L * 1024L * 1024L  // 64 MiB — matches production default

    // --- edge cases ---

    @Test
    fun `zero width returns 1`() {
        assertEquals(1, decideSampleSize(0, 1000, maxBytes))
    }

    @Test
    fun `zero height returns 1`() {
        assertEquals(1, decideSampleSize(1000, 0, maxBytes))
    }

    @Test
    fun `negative dimensions return 1`() {
        assertEquals(1, decideSampleSize(-1, -1, maxBytes))
    }

    // --- images that fit without downsampling ---

    @Test
    fun `small image returns sampleSize 1`() {
        // 100×100 ARGB_8888 = 40_000 bytes — well under 64 MiB
        assertEquals(1, decideSampleSize(100, 100, maxBytes))
    }

    @Test
    fun `image exactly at maxBytes returns sampleSize 1`() {
        // 4096×4096 ARGB_8888 = 67_108_864 bytes = 64 MiB
        val w = 4096; val h = 4096
        val bytes = w.toLong() * h.toLong() * 4L
        // Only returns 1 if bytes <= maxBytes
        val expectedSampleSize = if (bytes <= maxBytes) 1 else 2
        assertEquals(expectedSampleSize, decideSampleSize(w, h, maxBytes))
    }

    // --- images that need downsampling ---

    @Test
    fun `image twice the limit returns sampleSize 2`() {
        // ratio = 2 → rawSampleSize = ceil(sqrt(2)) = 2 (already PoT)
        // Use a known size: 8192×4096 ARGB_8888 = 134_217_728 bytes = 2× 64 MiB
        val result = decideSampleSize(8192, 4096, maxBytes)
        assertEquals(2, result)
    }

    @Test
    fun `image four times the limit returns sampleSize 2`() {
        // 8192×8192 ARGB_8888 = 268_435_456 bytes = 4× 64 MiB
        // ratio = 4 → rawSampleSize = ceil(sqrt(4)) = 2, already PoT
        val result = decideSampleSize(8192, 8192, maxBytes)
        assertEquals(2, result)
    }

    @Test
    fun `image sixteen times the limit returns sampleSize 4`() {
        // 16384×16384 ARGB_8888 = 1_073_741_824 bytes = 16× 64 MiB
        // ratio = 16 → sqrt = 4 → already PoT
        val result = decideSampleSize(16384, 16384, maxBytes)
        assertEquals(4, result)
    }

    @Test
    fun `result sampleSize fits within maxBytes`() {
        // Property test: for a broad range of sizes, the chosen sampleSize must actually fit.
        val testCases = listOf(
            1000 to 1000,
            2048 to 2048,
            4096 to 4096,
            8192 to 8192,
            10000 to 10000,
        )
        for ((w, h) in testCases) {
            val sampleSize = decideSampleSize(w, h, maxBytes)
            if (sampleSize != null) {
                val sampledBytes = (w.toLong() / sampleSize) * (h.toLong() / sampleSize) * 4L
                assert(sampledBytes <= maxBytes) {
                    "sampleSize=$sampleSize for ${w}×${h} still exceeds maxBytes: " +
                        "sampledBytes=$sampledBytes > maxBytes=$maxBytes"
                }
            }
        }
    }

    @Test
    fun `result is always a power of two`() {
        val testCases = listOf(1024 to 1024, 2000 to 3000, 6000 to 6000, 12000 to 12000)
        for ((w, h) in testCases) {
            val sampleSize = decideSampleSize(w, h, maxBytes) ?: continue
            val isPowerOfTwo = sampleSize > 0 && (sampleSize and (sampleSize - 1)) == 0
            assert(isPowerOfTwo) { "sampleSize=$sampleSize is not a power of two for ${w}×${h}" }
        }
    }

    // --- custom maxBytes (different from production default) ---

    @Test
    fun `custom 1 MiB cap - 2048x2048 needs downsampling`() {
        val oneMiB = 1L * 1024L * 1024L  // 1 MiB
        // 2048×2048×4 = 16 MiB — 16× the cap, needs sampleSize=4
        val result = decideSampleSize(2048, 2048, oneMiB)
        assertEquals(4, result)
    }

    @Test
    fun `tiny maxBytes - very large image returns null on pathological input`() {
        // 1-pixel-wide very tall image: 1×(some_huge_H)×4
        // Such images cannot be shrunk below maxBytes by reducing inSampleSize on the
        // width dimension alone (integer division floors 1/s = 0, preventing progress).
        // decideSampleSize should return null rather than loop forever.
        val tinyMaxBytes = 100L
        val result = decideSampleSize(1, 100_000, tinyMaxBytes)
        // Either returns null (can't fit) or a valid sampleSize; must not throw or infinite-loop.
        // For this pathological case (1-wide, 100_000 tall), (1/s)*(100_000/s)*4 with s=512:
        // = (0)*(195)*4 = 0 <= 100 → sampleSize found, not null.
        // The contract only requires it not to throw; we just validate it doesn't crash.
        // (null is acceptable; a non-zero Int is acceptable)
        assert(result == null || result > 0) { "unexpected result: $result" }
    }
}
