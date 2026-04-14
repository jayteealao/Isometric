package io.github.jayteealao.isometric.shader

import android.graphics.Bitmap
import androidx.annotation.DrawableRes

/**
 * Describes where a texture's pixel data comes from.
 *
 * Sealed to prevent invalid combinations at compile time (guideline Section 6).
 * Use the DSL functions [texturedResource], [texturedAsset], [texturedBitmap] from
 * [IsometricMaterial] builders to construct instances idiomatically.
 *
 * **Evolution note:** Adding a new subtype in a future version is a breaking change
 * for consumers using exhaustive `when` expressions. Use an `else` branch in `when`
 * if forward compatibility is needed.
 */
sealed interface TextureSource {
    /**
     * A drawable resource bundled with the app.
     *
     * @property resId Android drawable resource identifier (e.g., `R.drawable.brick`)
     */
    data class Resource(@DrawableRes val resId: Int) : TextureSource {
        init {
            require(resId != 0) { "Resource ID must not be zero" }
        }
    }

    /**
     * A file in the app's `assets/` directory.
     *
     * @property path Relative path within `assets/` (e.g., `"textures/grass.png"`)
     */
    data class Asset(val path: String) : TextureSource {
        init {
            require(path.isNotBlank()) { "Asset path must not be blank" }
            require(!path.startsWith("/")) { "Asset path must be relative, got '$path'" }
            require(".." !in path.split("/", "\\")) {
                "Asset path must not contain '..' components, got '$path'"
            }
            require('\u0000' !in path) { "Asset path must not contain null bytes" }
        }
    }

    /**
     * A pre-decoded [Bitmap] provided directly by the caller.
     *
     * The caller retains ownership of the bitmap's lifecycle.
     * Do not recycle the bitmap while any material referencing it is active.
     *
     * @property bitmap The source bitmap. Must not be recycled.
     */
    data class Bitmap(val bitmap: android.graphics.Bitmap) : TextureSource {
        init {
            require(!bitmap.isRecycled) { "Bitmap must not be recycled" }
        }

        /**
         * Verifies the bitmap is still usable. Call this before accessing [bitmap]
         * pixels in a renderer — catches bitmaps recycled after construction.
         *
         * @throws IllegalStateException if the bitmap has been recycled since construction
         */
        fun ensureNotRecycled() {
            check(!bitmap.isRecycled) {
                "Bitmap was recycled after TextureSource.Bitmap was created. " +
                    "Do not recycle a bitmap while any material referencing it is active."
            }
        }
    }
}
