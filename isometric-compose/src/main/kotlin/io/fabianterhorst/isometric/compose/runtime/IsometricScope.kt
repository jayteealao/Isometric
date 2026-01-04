package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * Scope for building isometric scenes with a DSL-like syntax
 */
@Stable
interface IsometricScope {
    /**
     * This is a marker interface to provide DSL scope for isometric composables
     */
}

/**
 * Internal implementation of IsometricScope
 */
internal object IsometricScopeImpl : IsometricScope
