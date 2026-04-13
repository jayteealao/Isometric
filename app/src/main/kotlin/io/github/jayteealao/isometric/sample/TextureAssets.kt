package io.github.jayteealao.isometric.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.random.Random

/**
 * Procedurally generated texture bitmaps for the textured-demo sample.
 * Both are 64x64 ARGB_8888 — generated once and cached via [lazy].
 */
internal object TextureAssets {

    val grassTop: Bitmap by lazy { buildGrassTop() }
    val dirtSide: Bitmap by lazy { buildDirtSide() }

    private const val SIZE = 64

    private fun buildGrassTop(): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()

        // Base fill — medium green
        canvas.drawColor(0xFF6AA84F.toInt())

        // Lighter highlight stripe across the top third
        paint.color = 0xFF93C47D.toInt()
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE / 3f, paint)

        // Noise layer — darker green dots to simulate grass blades
        paint.color = 0xFF3D8512.toInt()
        val rng = Random(42) // deterministic for reproducibility
        repeat(80) {
            val x = rng.nextFloat() * SIZE
            val y = rng.nextFloat() * SIZE
            canvas.drawCircle(x, y, 1.5f, paint)
        }

        return bmp
    }

    private fun buildDirtSide(): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()

        // Base fill — mid-brown
        canvas.drawColor(0xFFA2713D.toInt())

        // Horizontal band variation every 8px
        val lighter = 0xFFB4824B.toInt()
        val darker = 0xFF8C6032.toInt()
        for (row in 0 until SIZE step 8) {
            paint.color = if ((row / 8) % 2 == 0) lighter else darker
            canvas.drawRect(0f, row.toFloat(), SIZE.toFloat(), (row + 8).toFloat(), paint)
        }

        // Small random dark flecks for gravel texture
        paint.color = 0xFF654321.toInt()
        val rng = Random(99)
        repeat(60) {
            val x = rng.nextFloat() * SIZE
            val y = rng.nextFloat() * SIZE
            canvas.drawCircle(x, y, 1f, paint)
        }

        return bmp
    }
}
