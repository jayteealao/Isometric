package io.fabianterhorst.isometric.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.fabianterhorst.isometric.IsometricEngine

/**
 * Remember an IsometricSceneState instance across recompositions
 */
@Composable
fun rememberIsometricSceneState(): IsometricSceneState {
    return remember {
        IsometricSceneState(IsometricEngine())
    }
}
