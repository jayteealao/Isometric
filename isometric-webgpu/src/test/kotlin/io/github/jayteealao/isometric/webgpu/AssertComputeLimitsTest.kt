package io.github.jayteealao.isometric.webgpu

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JVM unit tests for [GpuContext.Companion.checkComputeLimits] (M-12).
 *
 * Uses the pure helper extracted from `assertComputeLimits` so no real [androidx.webgpu.GPUDevice]
 * or Android runtime is required.
 */
class AssertComputeLimitsTest {

    // --- maxComputeInvocationsPerWorkgroup ---

    @Test
    fun `limits at WebGPU minimum - no exception`() {
        // Both fields at the exact WebGPU-spec minimums — must pass without throwing.
        GpuContext.checkComputeLimits(
            maxComputeInvocationsPerWorkgroup = 256,
            maxStorageBuffersPerShaderStage = 8,
        )
    }

    @Test
    fun `limits well above minimum - no exception`() {
        GpuContext.checkComputeLimits(
            maxComputeInvocationsPerWorkgroup = 1024,
            maxStorageBuffersPerShaderStage = 16,
        )
    }

    @Test
    fun `maxComputeInvocationsPerWorkgroup below 256 - throws IllegalArgumentException`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            GpuContext.checkComputeLimits(
                maxComputeInvocationsPerWorkgroup = 255,
                maxStorageBuffersPerShaderStage = 8,
            )
        }
        assertTrue(
            ex.message?.contains("256") == true,
            "Exception message should mention the minimum (256); was: ${ex.message}",
        )
    }

    @Test
    fun `maxComputeInvocationsPerWorkgroup zero - throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            GpuContext.checkComputeLimits(
                maxComputeInvocationsPerWorkgroup = 0,
                maxStorageBuffersPerShaderStage = 8,
            )
        }
    }

    // --- maxStorageBuffersPerShaderStage ---

    @Test
    fun `maxStorageBuffersPerShaderStage below 8 - throws IllegalStateException with 8`() {
        val ex = assertFailsWith<IllegalStateException> {
            GpuContext.checkComputeLimits(
                maxComputeInvocationsPerWorkgroup = 256,
                maxStorageBuffersPerShaderStage = 7,
            )
        }
        assertTrue(
            ex.message?.contains("8") == true,
            "Exception message should mention the required minimum (8); was: ${ex.message}",
        )
    }

    @Test
    fun `maxStorageBuffersPerShaderStage zero - throws IllegalStateException`() {
        assertFailsWith<IllegalStateException> {
            GpuContext.checkComputeLimits(
                maxComputeInvocationsPerWorkgroup = 256,
                maxStorageBuffersPerShaderStage = 0,
            )
        }
    }

    @Test
    fun `maxStorageBuffersPerShaderStage compat-mode 4 - throws IllegalStateException`() {
        // OpenGL ES 3.1 compat-mode adapters report 4 — must be rejected.
        val ex = assertFailsWith<IllegalStateException> {
            GpuContext.checkComputeLimits(
                maxComputeInvocationsPerWorkgroup = 256,
                maxStorageBuffersPerShaderStage = 4,
            )
        }
        assertTrue(
            ex.message?.contains("8") == true,
            "Exception message should mention the required minimum (8); was: ${ex.message}",
        )
    }

    @Test
    fun `invocations limit check fires before storage buffers check`() {
        // When both limits are bad, the invocations check fires first (IllegalArgumentException).
        assertFailsWith<IllegalArgumentException> {
            GpuContext.checkComputeLimits(
                maxComputeInvocationsPerWorkgroup = 0,
                maxStorageBuffersPerShaderStage = 0,
            )
        }
    }
}
