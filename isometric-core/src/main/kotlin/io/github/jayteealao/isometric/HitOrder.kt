package io.github.jayteealao.isometric

/**
 * Search order for hit testing.
 */
enum class HitOrder {
    /** Return the frontmost (closest to viewer) matching command. This is the default. */
    FRONT_TO_BACK,
    /** Return the rearmost (furthest from viewer) matching command. */
    BACK_TO_FRONT
}
