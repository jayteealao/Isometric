package io.fabianterhorst.isometric

/**
 * Marks APIs that are experimental and may change or be removed
 * in future releases. Use at your own risk.
 */
@RequiresOptIn(
    message = "This API is experimental and may change without notice.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER
)
annotation class ExperimentalIsometricApi
