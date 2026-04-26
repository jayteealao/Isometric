package io.github.jayteealao.isometric.webgpu.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.webgpu.CompilationMessageType
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import io.github.jayteealao.isometric.webgpu.GpuContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Instrumented test for [WgslDiagnostics].
 *
 * Validates that [WgslDiagnostics.logCompilation] correctly dispatches
 * Tint compilation messages to logcat at the right severity, and that it
 * surfaces errors for intentionally broken WGSL.
 *
 * ## Status: STUB — @Ignore lifted at slice end (H-4)
 *
 * This test is fully compilable against the real [GPUDevice] API and can be
 * run on a real device or emulator once @Ignore is removed. It is suppressed
 * for now because CI does not have a connected device and instrumented tests
 * are not gated per-commit (only at slice end per the cleanup plan).
 *
 * TODO (H-4 / slice-end verify): Remove the @Ignore annotation and run
 * `./gradlew :isometric-webgpu:connectedAndroidTest` before `/wf-verify`.
 *
 * Pattern reference: vendor/androidx-webgpu/webgpu/src/androidTest/java/
 *   androidx/webgpu/ShaderModuleTest.kt
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
@Ignore("Lifted at slice end — H-4. Run with :isometric-webgpu:connectedAndroidTest on a real device.")
class WgslDiagnosticsInstrumentedTest {

    private lateinit var ctx: GpuContext

    @Before
    fun setUp() = runBlocking {
        ctx = GpuContext.create()
    }

    @After
    fun tearDown() {
        runCatching { ctx.destroy() }
    }

    /**
     * A valid minimal vertex shader. [WgslDiagnostics.logCompilation] must complete
     * without throwing and the compilation info must contain zero error messages.
     */
    @Test
    fun `logCompilation_noExceptionAndNoErrors_forValidWgsl`() = runBlocking {
        val validWgsl = """
            @vertex fn main() -> @builtin(position) vec4<f32> {
                return vec4<f32>(0.0, 0.0, 0.0, 1.0);
            }
        """.trimIndent()

        val module = ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(shaderSourceWGSL = GPUShaderSourceWGSL(validWgsl))
        )

        // Must not throw — any WebGpuException here would indicate an unexpected failure.
        WgslDiagnostics.logCompilation(module, "WgslDiagnosticsInstrumentedTest")

        val info = module.getCompilationInfo()
        val errorCount = info.messages.count { it.type == CompilationMessageType.Error }
        assertEquals(0, errorCount, "Valid WGSL must produce zero error messages from Tint")
    }

    /**
     * An intentionally broken shader (unknown function call). Tint must report at least
     * one error message, and [WgslDiagnostics.logCompilation] must re-throw the resulting
     * [androidx.webgpu.WebGpuException] if the underlying [GPUShaderModule.getCompilationInfo]
     * fails, OR log the error messages without throwing if Tint surfaces them as diagnostic
     * messages rather than exceptions.
     *
     * NOTE: Per the vendor ShaderModuleTest, getCompilationInfo() succeeds even for invalid
     * shaders — Tint returns error messages in [GPUCompilationInfo.messages] rather than
     * throwing. [WgslDiagnostics.logCompilation] therefore logs and returns normally;
     * this test asserts at least one error message is present.
     */
    @Test
    fun `logCompilation_logsErrors_forBrokenWgsl`() = runBlocking {
        val brokenWgsl = """
            @vertex fn main() -> @builtin(position) vec4<f32> {
                return unknown(0.0, 0.0, 0.0, 1.0);
            }
        """.trimIndent()

        val module = ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(shaderSourceWGSL = GPUShaderSourceWGSL(brokenWgsl))
        )

        // logCompilation must not throw even for broken WGSL; errors go to logcat.
        WgslDiagnostics.logCompilation(module, "WgslDiagnosticsInstrumentedTest")

        val info = module.getCompilationInfo()
        val errorCount = info.messages.count { it.type == CompilationMessageType.Error }
        assertTrue(errorCount > 0, "Broken WGSL must produce at least one Tint error message")
    }
}
