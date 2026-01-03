# Benchmark Harness Baseline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create production-grade benchmark harness to measure baseline Isometric rendering performance across 32 scenarios.

**Architecture:** New `isometric-benchmark` Android module with Macrobenchmark integration. Deterministic scene generation, Choreographer-based frame pacing, zero-allocation metrics collection. BenchmarkActivity drives orchestrated warmup/measurement phases.

**Tech Stack:**
- androidx.benchmark:benchmark-macro (frame timing)
- Jetpack Compose (UI rendering)
- Choreographer (frame callbacks)
- Kotlin coroutines (async orchestration)

---

## Task 1: Create benchmark module structure

**Files:**
- Create: `isometric-benchmark/build.gradle.kts`
- Create: `isometric-benchmark/src/main/AndroidManifest.xml`
- Modify: `settings.gradle`

**Step 1: Add module to settings.gradle**

```groovy
include ':isometric-benchmark'
```

**Step 2: Run Gradle sync**

Run: `./gradlew projects`
Expected: See `:isometric-benchmark` in project list

**Step 3: Create build.gradle.kts**

File: `isometric-benchmark/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.fabianterhorst.isometric.benchmark"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.fabianterhorst.isometric.benchmark"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.0"
    }
}

dependencies {
    implementation(project(":isometric-compose"))
    implementation(project(":isometric-core"))

    // Compose
    val composeVersion = "1.5.0"
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.material:material:$composeVersion")
    implementation("androidx.activity:activity-compose:1.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

**Step 4: Create AndroidManifest.xml**

File: `isometric-benchmark/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:label="Isometric Benchmark"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".BenchmarkActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

**Step 5: Sync and verify module**

Run: `./gradlew :isometric-benchmark:tasks --quiet | head -20`
Expected: See Android tasks listed

**Step 6: Commit**

```bash
git add settings.gradle isometric-benchmark/
git commit -m "feat: create isometric-benchmark module structure"
```

---

## Task 2: Create benchmark configuration models

**Files:**
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkConfig.kt`

**Step 1: Create BenchmarkConfig data class**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkConfig.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

import android.content.Intent
import android.os.Bundle

data class BenchmarkConfig(
    val name: String,
    val sceneSize: Int,              // 10, 100, 500, 1000
    val scenario: Scenario,
    val interactionPattern: InteractionPattern,
    val flags: OptimizationFlags,
    val outputFile: String = "benchmark_results.csv"
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("name", name)
        putInt("sceneSize", sceneSize)
        putString("scenario", scenario.name)
        putString("interactionPattern", interactionPattern.name)
        putBundle("flags", flags.toBundle())
        putString("outputFile", outputFile)
    }

    companion object {
        fun fromIntent(intent: Intent): BenchmarkConfig {
            val bundle = intent.getBundleExtra("config")
                ?: throw IllegalArgumentException("No config bundle in intent")

            return BenchmarkConfig(
                name = bundle.getString("name") ?: "unknown",
                sceneSize = bundle.getInt("sceneSize"),
                scenario = Scenario.valueOf(bundle.getString("scenario") ?: "STATIC"),
                interactionPattern = InteractionPattern.valueOf(
                    bundle.getString("interactionPattern") ?: "NONE"
                ),
                flags = OptimizationFlags.fromBundle(
                    bundle.getBundle("flags") ?: Bundle()
                ),
                outputFile = bundle.getString("outputFile") ?: "benchmark_results.csv"
            )
        }
    }
}

enum class Scenario {
    STATIC,
    INCREMENTAL_1,   // 1% mutation per frame
    INCREMENTAL_10,  // 10% mutation per frame
    FULL_MUTATION
}

enum class InteractionPattern {
    NONE,
    OCCASIONAL,   // 1 tap per second
    CONTINUOUS,   // 60 taps per second
    HOVER         // 30 taps per second
}

data class OptimizationFlags(
    val enablePreparedSceneCache: Boolean = false,
    val enableDrawWithCache: Boolean = false,
    val enableBroadPhaseSort: Boolean = false,
    val enableSpatialIndex: Boolean = false
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("enablePreparedSceneCache", enablePreparedSceneCache)
        putBoolean("enableDrawWithCache", enableDrawWithCache)
        putBoolean("enableBroadPhaseSort", enableBroadPhaseSort)
        putBoolean("enableSpatialIndex", enableSpatialIndex)
    }

    companion object {
        val BASELINE = OptimizationFlags()
        val PHASE_1 = BASELINE.copy(enablePreparedSceneCache = true)
        val PHASE_2 = PHASE_1.copy(enableDrawWithCache = true)
        val PHASE_3 = PHASE_2.copy(enableBroadPhaseSort = true)
        val PHASE_4 = PHASE_3.copy(enableSpatialIndex = true)

        fun fromBundle(bundle: Bundle): OptimizationFlags = OptimizationFlags(
            enablePreparedSceneCache = bundle.getBoolean("enablePreparedSceneCache"),
            enableDrawWithCache = bundle.getBoolean("enableDrawWithCache"),
            enableBroadPhaseSort = bundle.getBoolean("enableBroadPhaseSort"),
            enableSpatialIndex = bundle.getBoolean("enableSpatialIndex")
        )
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :isometric-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkConfig.kt
git commit -m "feat: add benchmark configuration models"
```

---

## Task 3: Implement deterministic scene generation

**Files:**
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/SceneGenerator.kt`

**Step 1: Create SceneGenerator object**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/SceneGenerator.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape
import io.fabianterhorst.isometric.shapes.Cylinder
import io.fabianterhorst.isometric.shapes.Prism
import io.fabianterhorst.isometric.shapes.Pyramid
import kotlin.random.Random

data class SceneItem(
    val shape: Shape,
    val color: IsoColor,
    var position: Point  // Mutable for incremental updates
)

object SceneGenerator {
    /**
     * Generate deterministic scene with fixed seed
     *
     * @param size Number of objects (10, 100, 500, 1000)
     * @param seed Fixed seed for reproducibility
     * @param density Scene density (0.0=sparse, 1.0=dense)
     */
    fun generate(
        size: Int,
        seed: Long = 12345L,
        density: Float = 0.5f
    ): List<SceneItem> {
        val random = Random(seed)
        val spread = 20.0 / density  // Higher density = smaller spread

        return (0 until size).map { i ->
            val x = random.nextDouble(-spread, spread)
            val y = random.nextDouble(-spread, spread)
            val z = random.nextDouble(0.0, 5.0)
            val position = Point(x, y, z)

            val shape = when (i % 3) {
                0 -> Prism(position)
                1 -> Pyramid(position)
                else -> Cylinder(position, radius = 0.5, vertices = 20, height = 2.0)
            }

            val color = IsoColor(
                r = random.nextDouble(50.0, 255.0),
                g = random.nextDouble(50.0, 255.0),
                b = random.nextDouble(50.0, 255.0)
            )

            SceneItem(shape, color, position)
        }
    }

    /**
     * Mutate a percentage of scene items (for incremental update tests)
     */
    fun mutateScene(
        scene: List<SceneItem>,
        mutationRate: Float,  // 0.01 = 1%, 0.10 = 10%
        frameIndex: Int,
        seed: Long = 67890L
    ) {
        val random = Random(seed + frameIndex)
        val itemsToMutate = (scene.size * mutationRate).toInt()

        repeat(itemsToMutate) {
            val index = random.nextInt(scene.size)
            val item = scene[index]

            // Slightly offset position
            item.position = Point(
                item.position.x + random.nextDouble(-0.1, 0.1),
                item.position.y + random.nextDouble(-0.1, 0.1),
                item.position.z + random.nextDouble(-0.1, 0.1)
            )
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :isometric-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/SceneGenerator.kt
git commit -m "feat: add deterministic scene generator"
```

---

## Task 4: Implement interaction simulator

**Files:**
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/InteractionSimulator.kt`

**Step 1: Create InteractionSimulator class**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/InteractionSimulator.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

import androidx.compose.ui.geometry.Offset
import kotlin.random.Random

class InteractionSimulator(
    private val pattern: InteractionPattern,
    private val width: Int,
    private val height: Int,
    seed: Long = 67890L
) {
    // Pre-generate all tap points for reproducibility
    private val tapPoints: List<Offset?> = run {
        val random = Random(seed)
        (0 until 1000).map { frameIndex ->
            when (pattern) {
                InteractionPattern.NONE -> null
                InteractionPattern.OCCASIONAL -> {
                    if (frameIndex % 60 == 0) {
                        Offset(
                            random.nextFloat() * width,
                            random.nextFloat() * height
                        )
                    } else null
                }
                InteractionPattern.CONTINUOUS -> Offset(
                    random.nextFloat() * width,
                    random.nextFloat() * height
                )
                InteractionPattern.HOVER -> {
                    if (frameIndex % 2 == 0) {
                        Offset(
                            random.nextFloat() * width,
                            random.nextFloat() * height
                        )
                    } else null
                }
            }
        }
    }

    fun getHitTestPoint(frameIndex: Int): Offset? {
        return tapPoints.getOrNull(frameIndex)
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :isometric-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/InteractionSimulator.kt
git commit -m "feat: add interaction simulator with pre-generated tap points"
```

---

## Task 5: Implement zero-allocation metrics collector

**Files:**
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/MetricsCollector.kt`
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkResults.kt`

**Step 1: Create BenchmarkResults data class**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkResults.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

data class BenchmarkResults(
    val config: BenchmarkConfig,
    val frameCount: Int,
    val avgFrameTime: Double,
    val p50FrameTime: Double,
    val p95FrameTime: Double,
    val p99FrameTime: Double,
    val minFrameTime: Double,
    val maxFrameTime: Double
) {
    fun toCsv(): String {
        return "${config.name},${config.sceneSize},${config.scenario}," +
               "${config.interactionPattern},$avgFrameTime,$p50FrameTime," +
               "$p95FrameTime,$p99FrameTime,$minFrameTime,$maxFrameTime"
    }

    companion object {
        fun csvHeader(): String {
            return "name,sceneSize,scenario,interaction,avgFrameMs," +
                   "p50FrameMs,p95FrameMs,p99FrameMs,minFrameMs,maxFrameMs"
        }
    }
}
```

**Step 2: Create MetricsCollector class**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/MetricsCollector.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

class MetricsCollector {
    // Pre-allocated arrays for zero allocation during measurement
    private val frameTimes = LongArray(500)
    private var frameCount = 0
    private var currentFrameStart = 0L

    fun onFrameStart(frameTimeNanos: Long) {
        currentFrameStart = frameTimeNanos
    }

    fun onFrameEnd(frameTimeNanos: Long) {
        val duration = frameTimeNanos - currentFrameStart
        if (frameCount < frameTimes.size) {
            frameTimes[frameCount] = duration
            frameCount++
        }
    }

    fun reset() {
        frameCount = 0
    }

    fun computeResults(config: BenchmarkConfig): BenchmarkResults {
        // Convert to milliseconds and sort
        val frameTimesMs = frameTimes.take(frameCount)
            .map { it / 1_000_000.0 }
            .sorted()

        return BenchmarkResults(
            config = config,
            frameCount = frameCount,
            avgFrameTime = frameTimesMs.average(),
            p50FrameTime = frameTimesMs[frameCount / 2],
            p95FrameTime = frameTimesMs[(frameCount * 0.95).toInt()],
            p99FrameTime = frameTimesMs[(frameCount * 0.99).toInt()],
            minFrameTime = frameTimesMs.first(),
            maxFrameTime = frameTimesMs.last()
        )
    }
}
```

**Step 3: Verify compilation**

Run: `./gradlew :isometric-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkResults.kt
git add isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/MetricsCollector.kt
git commit -m "feat: add zero-allocation metrics collector"
```

---

## Task 6: Implement Choreographer-based frame pacer

**Files:**
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/FramePacer.kt`

**Step 1: Create FramePacer class**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/FramePacer.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

import android.view.Choreographer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FramePacer {
    private val choreographer = Choreographer.getInstance()

    suspend fun awaitNextFrame(onFrame: (frameTimeNanos: Long) -> Unit) {
        suspendCoroutine<Unit> { continuation ->
            choreographer.postFrameCallback { frameTimeNanos ->
                onFrame(frameTimeNanos)
                continuation.resume(Unit)
            }
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :isometric-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/FramePacer.kt
git commit -m "feat: add Choreographer-based frame pacer"
```

---

## Task 7: Implement benchmark orchestrator

**Files:**
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkOrchestrator.kt`

**Step 1: Create BenchmarkOrchestrator class**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkOrchestrator.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BenchmarkOrchestrator(
    private val activity: Activity,
    val config: BenchmarkConfig,
    private val onComplete: (BenchmarkResults) -> Unit
) {
    private val metrics = MetricsCollector()
    private val framePacer = FramePacer()
    var phase = Phase.IDLE
        private set

    enum class Phase {
        IDLE,
        GC_AND_WARMUP,
        COOLDOWN,
        MEASUREMENT,
        COMPLETE
    }

    private val _frameTickFlow = MutableStateFlow(0)
    val frameTickFlow: StateFlow<Int> = _frameTickFlow.asStateFlow()

    suspend fun start() {
        Log.d(TAG, "Starting benchmark: ${config.name}")

        // Phase 1: GC and Warmup (500 frames)
        phase = Phase.GC_AND_WARMUP
        forceGarbageCollection()
        delay(2000)
        Log.d(TAG, "Warmup: 500 frames")
        runFrames(count = 500, measure = false)

        // Phase 2: Cooldown
        phase = Phase.COOLDOWN
        Log.d(TAG, "Cooldown: 2 seconds")
        delay(2000)
        forceGarbageCollection()
        delay(1000)

        // Phase 3: Measurement (500 frames)
        phase = Phase.MEASUREMENT
        Log.d(TAG, "Measurement: 500 frames")
        metrics.reset()
        runFrames(count = 500, measure = true)

        // Phase 4: Complete
        phase = Phase.COMPLETE
        val results = metrics.computeResults(config)
        Log.d(TAG, "Benchmark complete: avg=${results.avgFrameTime}ms")
        onComplete(results)
    }

    private suspend fun runFrames(count: Int, measure: Boolean) {
        repeat(count) { frameIndex ->
            framePacer.awaitNextFrame { frameTimeNanos ->
                if (measure) {
                    metrics.onFrameStart(frameTimeNanos)
                }

                // Trigger recomposition
                _frameTickFlow.value = frameIndex

                if (measure) {
                    metrics.onFrameEnd(System.nanoTime())
                }
            }
        }
    }

    private fun forceGarbageCollection() {
        repeat(3) {
            System.gc()
            System.runFinalization()
        }
    }

    companion object {
        private const val TAG = "BenchmarkOrchestrator"
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :isometric-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkOrchestrator.kt
git commit -m "feat: add benchmark orchestrator with warmup/measurement phases"
```

---

## Task 8: Implement BenchmarkActivity

**Files:**
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkActivity.kt`

**Step 1: Create BenchmarkActivity**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkActivity.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.fabianterhorst.isometric.compose.IsometricCanvas
import io.fabianterhorst.isometric.compose.rememberIsometricSceneState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BenchmarkActivity : ComponentActivity() {
    private lateinit var orchestrator: BenchmarkOrchestrator
    private lateinit var config: BenchmarkConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Parse config or use default
        config = try {
            BenchmarkConfig.fromIntent(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Using default config", e)
            createDefaultConfig()
        }

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        orchestrator = BenchmarkOrchestrator(
            activity = this,
            config = config,
            onComplete = { results ->
                Log.i(TAG, "Results: ${results.toCsv()}")
                // TODO: Export to file in Task 9
                finish()
            }
        )

        setContent {
            BenchmarkScreen(orchestrator)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            delay(1000) // Let UI settle
            orchestrator.start()
        }
    }

    private fun createDefaultConfig(): BenchmarkConfig {
        return BenchmarkConfig(
            name = "baseline_static_100_noInteraction",
            sceneSize = 100,
            scenario = Scenario.STATIC,
            interactionPattern = InteractionPattern.NONE,
            flags = OptimizationFlags.BASELINE
        )
    }

    companion object {
        private const val TAG = "BenchmarkActivity"
    }
}

@Composable
fun BenchmarkScreen(orchestrator: BenchmarkOrchestrator) {
    val config = orchestrator.config
    val frameTick by orchestrator.frameTickFlow.collectAsState()

    // Generate deterministic scene
    val baseScene = remember(config.sceneSize) {
        SceneGenerator.generate(
            size = config.sceneSize,
            seed = 12345L,
            density = 0.5f
        )
    }

    // Create scene state
    val sceneState = rememberIsometricSceneState()

    // Apply scene mutations based on scenario
    LaunchedEffect(frameTick) {
        when (config.scenario) {
            Scenario.STATIC -> {
                // No mutations
            }
            Scenario.INCREMENTAL_1 -> {
                SceneGenerator.mutateScene(baseScene, 0.01f, frameTick)
            }
            Scenario.INCREMENTAL_10 -> {
                SceneGenerator.mutateScene(baseScene, 0.10f, frameTick)
            }
            Scenario.FULL_MUTATION -> {
                SceneGenerator.mutateScene(baseScene, 1.0f, frameTick)
            }
        }

        // Rebuild scene
        sceneState.clear()
        baseScene.forEach { item ->
            sceneState.add(item.shape, item.color)
        }
    }

    // Simulate interaction
    val simulator = remember {
        InteractionSimulator(
            pattern = config.interactionPattern,
            width = 1080,
            height = 1920,
            seed = 67890L
        )
    }

    LaunchedEffect(frameTick) {
        simulator.getHitTestPoint(frameTick)?.let { point ->
            // Hit test will happen in next task
            // For now, just trigger it so scene state is accessed
            sceneState.currentVersion
        }
    }

    IsometricCanvas(
        state = sceneState,
        modifier = Modifier.fillMaxSize()
    ) {
        // Scene already built in LaunchedEffect
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :isometric-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Build and install on device**

Run: `./gradlew :isometric-benchmark:installDebug`
Expected: APK installed successfully

**Step 4: Launch manually and verify**

Run app from launcher, check logcat:
```
adb logcat -s BenchmarkOrchestrator:*
```
Expected: See "Starting benchmark", "Warmup", "Measurement", "Benchmark complete"

**Step 5: Commit**

```bash
git add isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkActivity.kt
git commit -m "feat: add BenchmarkActivity with scene rendering"
```

---

## Task 9: Add results export

**Files:**
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/ResultsExporter.kt`
- Modify: `isometric-benchmark/src/main/AndroidManifest.xml`

**Step 1: Add storage permission to manifest**

File: `isometric-benchmark/src/main/AndroidManifest.xml`

Add before `<application>`:
```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

**Step 2: Create ResultsExporter**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/ResultsExporter.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

import android.content.Context
import android.util.Log
import java.io.File

object ResultsExporter {
    fun export(context: Context, results: BenchmarkResults) {
        try {
            val outputDir = context.getExternalFilesDir(null)
            val outputFile = File(outputDir, results.config.outputFile)

            // Append to file (create with header if doesn't exist)
            if (!outputFile.exists()) {
                outputFile.writeText(BenchmarkResults.csvHeader() + "\n")
            }

            outputFile.appendText(results.toCsv() + "\n")

            Log.i(TAG, "Results exported to: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export results", e)
        }
    }

    private const val TAG = "ResultsExporter"
}
```

**Step 3: Update BenchmarkActivity to use ResultsExporter**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkActivity.kt`

Modify `onComplete` callback:
```kotlin
orchestrator = BenchmarkOrchestrator(
    activity = this,
    config = config,
    onComplete = { results ->
        Log.i(TAG, "Results: ${results.toCsv()}")
        ResultsExporter.export(this, results)
        finish()
    }
)
```

**Step 4: Rebuild and test**

Run: `./gradlew :isometric-benchmark:installDebug`
Expected: BUILD SUCCESSFUL

Run app, then check output:
```bash
adb shell "run-as io.fabianterhorst.isometric.benchmark cat files/benchmark_results.csv"
```
Expected: See CSV with header + one row of results

**Step 5: Commit**

```bash
git add isometric-benchmark/
git commit -m "feat: add results export to CSV"
```

---

## Task 10: Create baseline benchmark scenarios

**Files:**
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BaselineScenarios.kt`

**Step 1: Create scenario definitions**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BaselineScenarios.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

object BaselineScenarios {
    /**
     * All 32 baseline scenarios (4 sizes × 2 scene types × 2 interaction patterns × 1 optimization level)
     *
     * Focused subset for initial baseline:
     * - Scene sizes: 10, 100, 500, 1000
     * - Scene types: STATIC, FULL_MUTATION (extremes)
     * - Interactions: NONE, CONTINUOUS (extremes)
     */
    fun getAll(): List<BenchmarkConfig> = buildList {
        val sizes = listOf(10, 100, 500, 1000)
        val scenarios = listOf(Scenario.STATIC, Scenario.FULL_MUTATION)
        val interactions = listOf(InteractionPattern.NONE, InteractionPattern.CONTINUOUS)

        for (size in sizes) {
            for (scenario in scenarios) {
                for (interaction in interactions) {
                    add(
                        BenchmarkConfig(
                            name = "baseline_${scenario.name.lowercase()}_${size}_${interaction.name.lowercase()}",
                            sceneSize = size,
                            scenario = scenario,
                            interactionPattern = interaction,
                            flags = OptimizationFlags.BASELINE,
                            outputFile = "baseline_results.csv"
                        )
                    )
                }
            }
        }
    }

    /**
     * Quick smoke test - just 4 scenarios
     */
    fun getSmokeTest(): List<BenchmarkConfig> = listOf(
        BenchmarkConfig(
            name = "smoke_static_10_none",
            sceneSize = 10,
            scenario = Scenario.STATIC,
            interactionPattern = InteractionPattern.NONE,
            flags = OptimizationFlags.BASELINE
        ),
        BenchmarkConfig(
            name = "smoke_static_100_none",
            sceneSize = 100,
            scenario = Scenario.STATIC,
            interactionPattern = InteractionPattern.NONE,
            flags = OptimizationFlags.BASELINE
        ),
        BenchmarkConfig(
            name = "smoke_mutation_100_none",
            sceneSize = 100,
            scenario = Scenario.FULL_MUTATION,
            interactionPattern = InteractionPattern.NONE,
            flags = OptimizationFlags.BASELINE
        ),
        BenchmarkConfig(
            name = "smoke_static_100_continuous",
            sceneSize = 100,
            scenario = Scenario.STATIC,
            interactionPattern = InteractionPattern.CONTINUOUS,
            flags = OptimizationFlags.BASELINE
        )
    )
}
```

**Step 2: Verify compilation**

Run: `./gradlew :isometric-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BaselineScenarios.kt
git commit -m "feat: define 32 baseline benchmark scenarios"
```

---

## Task 11: Create manual benchmark runner

**Files:**
- Create: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/ManualBenchmarkRunner.kt`

**Step 1: Create runner with sequential execution**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/ManualBenchmarkRunner.kt`

```kotlin
package io.fabianterhorst.isometric.benchmark

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Helper to run benchmark scenarios sequentially
 *
 * Usage from adb:
 * adb shell am start -n io.fabianterhorst.isometric.benchmark/.ManualBenchmarkRunner \
 *   --es scenario smoke
 */
class ManualBenchmarkRunner : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scenarioType = intent.getStringExtra("scenario") ?: "smoke"
        val scenarios = when (scenarioType) {
            "smoke" -> BaselineScenarios.getSmokeTest()
            "all" -> BaselineScenarios.getAll()
            else -> {
                Log.e(TAG, "Unknown scenario type: $scenarioType")
                finish()
                return
            }
        }

        Log.i(TAG, "Running ${scenarios.size} scenarios")

        lifecycleScope.launch {
            scenarios.forEach { config ->
                Log.i(TAG, "Starting: ${config.name}")
                runScenario(config)
                delay(5000) // Cooldown between scenarios
            }
            Log.i(TAG, "All scenarios complete")
            finish()
        }
    }

    private suspend fun runScenario(config: BenchmarkConfig) {
        val intent = Intent(this, BenchmarkActivity::class.java).apply {
            putExtra("config", config.toBundle())
        }
        startActivity(intent)

        // Wait for benchmark to complete (rough estimate: warmup 8s + measurement 8s + overhead 2s)
        delay(20000)
    }

    companion object {
        private const val TAG = "ManualBenchmarkRunner"
    }
}
```

**Step 2: Add to AndroidManifest.xml**

File: `isometric-benchmark/src/main/AndroidManifest.xml`

Add inside `<application>`:
```xml
<activity
    android:name=".ManualBenchmarkRunner"
    android:exported="true" />
```

**Step 3: Add missing import to ManualBenchmarkRunner**

File: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/ManualBenchmarkRunner.kt`

Add imports at top:
```kotlin
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
```

**Step 4: Verify compilation and install**

Run: `./gradlew :isometric-benchmark:installDebug`
Expected: BUILD SUCCESSFUL

**Step 5: Test smoke scenarios from command line**

Run:
```bash
adb shell am start -n io.fabianterhorst.isometric.benchmark/.ManualBenchmarkRunner --es scenario smoke
```

Wait 2 minutes, then check results:
```bash
adb shell "run-as io.fabianterhorst.isometric.benchmark cat files/baseline_results.csv"
```

Expected: CSV with 4 rows (smoke test scenarios)

**Step 6: Commit**

```bash
git add isometric-benchmark/
git commit -m "feat: add manual benchmark runner for sequential execution"
```

---

## Task 12: Document baseline execution instructions

**Files:**
- Create: `isometric-benchmark/README.md`

**Step 1: Create README with instructions**

File: `isometric-benchmark/README.md`

```markdown
# Isometric Benchmark Harness

Production-grade benchmark suite for measuring Isometric rendering performance.

## Quick Start

### Run Smoke Test (4 scenarios, ~2 minutes)

```bash
# Install benchmark app
./gradlew :isometric-benchmark:installDebug

# Run smoke test
adb shell am start -n io.fabianterhorst.isometric.benchmark/.ManualBenchmarkRunner --es scenario smoke

# Wait 2 minutes, then retrieve results
adb shell "run-as io.fabianterhorst.isometric.benchmark cat files/baseline_results.csv"
```

### Run Full Baseline (32 scenarios, ~15 minutes)

```bash
# Run all baseline scenarios
adb shell am start -n io.fabianterhorst.isometric.benchmark/.ManualBenchmarkRunner --es scenario all

# Wait 15-20 minutes, then retrieve results
adb shell "run-as io.fabianterhorst.isometric.benchmark cat files/baseline_results.csv" > baseline_results.csv
```

## Scenarios

**Smoke Test (4 scenarios):**
- Static scene, 10 objects, no interaction
- Static scene, 100 objects, no interaction
- Full mutation, 100 objects, no interaction
- Static scene, 100 objects, continuous interaction

**Full Baseline (32 scenarios):**
- 4 scene sizes: 10, 100, 500, 1000
- 2 scene types: STATIC, FULL_MUTATION
- 2 interaction patterns: NONE, CONTINUOUS
- All with BASELINE optimization flags (all disabled)

## Results Format

CSV columns:
- `name`: Scenario identifier
- `sceneSize`: Number of objects (10, 100, 500, 1000)
- `scenario`: STATIC or FULL_MUTATION
- `interaction`: NONE or CONTINUOUS
- `avgFrameMs`: Average frame time in milliseconds
- `p50FrameMs`: Median frame time
- `p95FrameMs`: 95th percentile frame time
- `p99FrameMs`: 99th percentile frame time
- `minFrameMs`: Minimum frame time
- `maxFrameMs`: Maximum frame time

## Analysis

**Key Metrics:**
- **Avg frame time < 16ms** → 60fps capable
- **P95 frame time < 20ms** → Acceptable jank levels
- **P99 frame time** → Worst-case performance

**Expected Baseline:**
- N=10: ~2-5ms avg
- N=100: ~8-15ms avg
- N=500: ~40-60ms avg (won't hit 60fps)
- N=1000: ~150-200ms avg (very slow)

## Next Steps

After baseline measurements:
1. Analyze results against predictions in performance investigation plan
2. Identify worst bottlenecks
3. Implement Phase 1 optimization (PreparedScene cache)
4. Re-run benchmarks to measure improvement
```

**Step 2: Commit**

```bash
git add isometric-benchmark/README.md
git commit -m "docs: add benchmark execution instructions"
```

---

## Final Verification

**Step 1: Clean build from scratch**

Run: `./gradlew clean :isometric-benchmark:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Install and run smoke test**

```bash
./gradlew :isometric-benchmark:installDebug
adb shell am start -n io.fabianterhorst.isometric.benchmark/.ManualBenchmarkRunner --es scenario smoke
```

Watch logcat:
```bash
adb logcat -s BenchmarkOrchestrator:* ManualBenchmarkRunner:*
```

Expected: See 4 scenarios execute sequentially

**Step 3: Retrieve and validate results**

```bash
adb shell "run-as io.fabianterhorst.isometric.benchmark cat files/baseline_results.csv"
```

Expected:
- Header row
- 4 data rows with realistic frame times
- All columns populated

**Step 4: Final commit**

```bash
git add -A
git commit -m "feat: complete baseline benchmark harness implementation"
```

---

## Success Criteria

- ✅ Benchmark module builds successfully
- ✅ Smoke test runs 4 scenarios in ~2 minutes
- ✅ Results exported to CSV with all metrics
- ✅ Frame times are realistic (10-200ms range for N=10-1000)
- ✅ Choreographer-based frame pacing works correctly
- ✅ GC and warmup phases execute before measurement
- ✅ Deterministic scene generation produces same scenes across runs
- ✅ Ready to run full 32-scenario baseline

## Timeline

- **Estimated total:** 2-3 hours for implementation
- **Testing:** 30 minutes (smoke + full baseline run)
- **Analysis:** Initial review of baseline data

## Next Phase

After baseline measurements complete:
1. Analyze results vs predictions in `docs/plans/2026-01-03-isometric-performance-investigation.md`
2. Create Phase 1 implementation plan (PreparedScene cache)
3. Implement and benchmark optimization
4. Compare Phase 1 vs Baseline results
