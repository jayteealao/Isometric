package io.github.jayteealao.isometric.shader

import android.graphics.Bitmap
import androidx.annotation.DrawableRes

/**
 * Describes where a texture's pixel data comes from.
 *
 * Sealed to prevent invalid combinations at compile time (guideline Section 6).
 * Use the DSL functions [textured], [texturedAsset], [texturedBitmap] from
 * [IsometricMaterial] builders to construct instances idiomatically.
 */
sealed interface TextureSource {
    /**
     * A drawable resource bundled with the app.
     *
     * @property resId Android drawable resource identifier (e.g., `R.drawable.brick`)
     */
    data class Resource(@DrawableRes val resId: Int) : TextureSource {
        init {
            require(resId > 0) { "Resource ID must be positive, got $resId" }
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
    data class BitmapSource(val bitmap: Bitmap) : TextureSource {
        init {
            require(!bitmap.isRecycled) { "Bitmap must not be recycled" }
        }
    }
}
