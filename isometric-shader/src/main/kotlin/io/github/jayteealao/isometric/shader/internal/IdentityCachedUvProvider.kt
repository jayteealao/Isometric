package io.github.jayteealao.isometric.shader.internal

import java.util.concurrent.atomic.AtomicReference

/**
 * Single-slot identity-keyed cache for UV-array hot paths.
 *
 * Reuse semantics: `compute(key, builder)` returns the cached array when the
 * passed key is `===` (reference-equal) to the most recently cached key, or
 * invokes `builder(key)` and caches the result on miss.
 *
 * Concurrency: `AtomicReference` provides a single publication point, so
 * concurrent readers either see the previous (still-valid) cached pair or
 * the new one — never a torn write.
 *
 * Per-instance scoping is preserved by using reference equality (`===`) on
 * the key. Two distinct shape instances with identical fields will always
 * miss the cache; this is the same semantic as the prior @Volatile triple.
 */
internal class IdentityCachedUvProvider<K : Any> {
    private val ref = AtomicReference<Pair<K, FloatArray>?>(null)

    fun compute(key: K, builder: (K) -> FloatArray): FloatArray {
        val snap = ref.get()
        if (snap != null && snap.first === key) return snap.second
        val arr = builder(key)
        ref.set(key to arr)
        return arr
    }

    fun clear() { ref.set(null) }
}
