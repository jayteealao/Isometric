# Isometric Compose - Consumer Proguard Rules
# These rules ensure the library works correctly when R8/Proguard is enabled

# Keep all runtime classes for Compose Runtime reflection
-keep class io.fabianterhorst.isometric.compose.runtime.** { *; }

# Keep core isometric classes (shapes, paths, engine)
-keep class io.fabianterhorst.isometric.** { *; }

# Keep custom Applier - critical for Compose Runtime
-keep class io.fabianterhorst.isometric.compose.runtime.IsometricApplier { *; }

# Keep all node types - used by Compose Runtime
-keep class io.fabianterhorst.isometric.compose.runtime.IsometricNode { *; }
-keep class io.fabianterhorst.isometric.compose.runtime.GroupNode { *; }
-keep class io.fabianterhorst.isometric.compose.runtime.ShapeNode { *; }
-keep class io.fabianterhorst.isometric.compose.runtime.PathNode { *; }
-keep class io.fabianterhorst.isometric.compose.runtime.BatchNode { *; }

# Keep CompositionLocals - accessed by name
-keep class io.fabianterhorst.isometric.compose.runtime.CompositionLocalsKt { *; }

# Keep Composable functions
-keep @androidx.compose.runtime.Composable class * { *; }
