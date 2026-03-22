package io.github.jayteealao.isometric.webgpu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.webgpu.BufferUsage
import androidx.webgpu.BufferBindingType
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUBufferBindingLayout
import androidx.webgpu.GPUComputePipelineDescriptor
import androidx.webgpu.GPUComputeState
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.MapMode
import androidx.webgpu.ShaderStage
import io.github.jayteealao.isometric.webgpu.sort.GpuDepthSorter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class WebGpuSmokeInstrumentedTest {

    @Test
    fun webGpuBufferUploadSmokeTest() = runBlocking {
        val ctx = GpuContext.create()
        try {
            val data = intArrayOf(3, 1, 4, 1)
            val size = (data.size * Int.SIZE_BYTES).toLong()
            val upload = createMappedUploadBuffer(ctx, size).also {
                writeIntsToMappedBuffer(it, data)
            }
            val gpuBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    size = size,
                    usage = BufferUsage.CopyDst or BufferUsage.CopySrc,
                )
            )
            val readback = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    size = size,
                    usage = BufferUsage.CopyDst or BufferUsage.MapRead,
                )
            )

            try {
                val uploadEncoder = ctx.device.createCommandEncoder()
                uploadEncoder.copyBufferToBuffer(upload, 0L, gpuBuffer, 0L, size)
                ctx.queue.submit(arrayOf(uploadEncoder.finish()))
                ctx.queue.onSubmittedWorkDone()

                val readbackEncoder = ctx.device.createCommandEncoder()
                readbackEncoder.copyBufferToBuffer(gpuBuffer, 0L, readback, 0L, size)
                ctx.queue.submit(arrayOf(readbackEncoder.finish()))
                ctx.queue.onSubmittedWorkDone()

                readback.mapAndAwait(MapMode.Read, 0L, size)
                val result = IntArray(data.size)
                readback.getConstMappedRange(0L, size).order(ByteOrder.nativeOrder()).asIntBuffer().get(result)
                readback.unmap()

                assertArrayEquals(data, result)
            } finally {
                upload.destroy()
                gpuBuffer.destroy()
                readback.destroy()
            }
        } finally {
            ctx.destroy()
        }
    }

    @Test
    fun webGpuComputeCopyDispatchSmokeTest() = runBlocking {
        val ctx = GpuContext.create()
        try {
            val input = intArrayOf(1, 2, 3, 4)
            val size = (input.size * Int.SIZE_BYTES).toLong()
            val upload = createMappedUploadBuffer(ctx, size).also {
                writeIntsToMappedBuffer(it, input)
            }
            val inputBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    size = size,
                    usage = BufferUsage.Storage or BufferUsage.CopyDst,
                )
            )
            val outputBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    size = size,
                    usage = BufferUsage.Storage or BufferUsage.CopySrc,
                )
            )
            val readback = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    size = size,
                    usage = BufferUsage.CopyDst or BufferUsage.MapRead,
                )
            )

            try {
                val shader = ctx.device.createShaderModule(
                    GPUShaderModuleDescriptor(
                        shaderSourceWGSL = GPUShaderSourceWGSL(
                            """
                            @group(0) @binding(0) var<storage, read> inputData: array<u32>;
                            @group(0) @binding(1) var<storage, read_write> outputData: array<u32>;

                            @compute @workgroup_size(64)
                            fn main(@builtin(global_invocation_id) id: vec3<u32>) {
                                let index = id.x;
                                if (index >= arrayLength(&inputData)) { return; }
                                outputData[index] = inputData[index] * 2u;
                            }
                            """.trimIndent()
                        )
                    )
                )
                val bindGroupLayout = ctx.device.createBindGroupLayout(
                    GPUBindGroupLayoutDescriptor(
                        entries = arrayOf(
                            GPUBindGroupLayoutEntry(
                                binding = 0,
                                visibility = ShaderStage.Compute,
                                buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                            ),
                            GPUBindGroupLayoutEntry(
                                binding = 1,
                                visibility = ShaderStage.Compute,
                                buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
                            ),
                        )
                    )
                )
                val pipelineLayout = ctx.device.createPipelineLayout(
                    GPUPipelineLayoutDescriptor(bindGroupLayouts = arrayOf(bindGroupLayout))
                )
                val pipeline = ctx.device.createComputePipelineAndAwait(
                    GPUComputePipelineDescriptor(
                        layout = pipelineLayout,
                        compute = GPUComputeState(module = shader, entryPoint = "main"),
                    )
                )
                val bindGroup = ctx.device.createBindGroup(
                    androidx.webgpu.GPUBindGroupDescriptor(
                        layout = bindGroupLayout,
                        entries = arrayOf(
                            androidx.webgpu.GPUBindGroupEntry(binding = 0, buffer = inputBuffer),
                            androidx.webgpu.GPUBindGroupEntry(binding = 1, buffer = outputBuffer),
                        )
                    )
                )

                val uploadEncoder = ctx.device.createCommandEncoder()
                uploadEncoder.copyBufferToBuffer(upload, 0L, inputBuffer, 0L, size)
                ctx.queue.submit(arrayOf(uploadEncoder.finish()))
                ctx.queue.onSubmittedWorkDone()

                val computeEncoder = ctx.device.createCommandEncoder()
                val computePass = computeEncoder.beginComputePass()
                computePass.setPipeline(pipeline)
                computePass.setBindGroup(0, bindGroup)
                computePass.dispatchWorkgroups(1)
                computePass.end()
                ctx.queue.submit(arrayOf(computeEncoder.finish()))
                ctx.queue.onSubmittedWorkDone()

                val readbackEncoder = ctx.device.createCommandEncoder()
                readbackEncoder.copyBufferToBuffer(outputBuffer, 0L, readback, 0L, size)
                ctx.queue.submit(arrayOf(readbackEncoder.finish()))
                ctx.queue.onSubmittedWorkDone()

                readback.mapAndAwait(MapMode.Read, 0L, size)
                val result = IntArray(input.size)
                readback.getConstMappedRange(0L, size).order(ByteOrder.nativeOrder()).asIntBuffer().get(result)
                readback.unmap()

                assertArrayEquals(intArrayOf(2, 4, 6, 8), result)
            } finally {
                upload.destroy()
                inputBuffer.destroy()
                outputBuffer.destroy()
                readback.destroy()
            }
        } finally {
            ctx.destroy()
        }
    }

    @Test
    fun webGpuSortSmallSmokeTest() = runBlocking {
        val ctx = GpuContext.create()
        try {
            val sorter = GpuDepthSorter(ctx)
            val sorted = sorter.sortByDepthKeys(floatArrayOf(1.0f, 4.0f, 2.0f, 3.0f))
            assertEquals(listOf(1, 3, 2, 0), sorted.toList())
        } finally {
            ctx.destroy()
        }
    }

    @Test
    fun webGpuSortNonPowerOfTwoSmokeTest() = runBlocking {
        val ctx = GpuContext.create()
        try {
            val sorter = GpuDepthSorter(ctx)
            val keys = FloatArray(295) { index -> index.toFloat() }
            val sorted = sorter.sortByDepthKeys(keys)

            assertEquals(295, sorted.size)
            assertEquals(294, sorted.first())
            assertEquals(0, sorted.last())
            assertEquals((0 until 295).toSet(), sorted.toSet())
        } finally {
            ctx.destroy()
        }
    }

    private fun createMappedUploadBuffer(
        ctx: GpuContext,
        size: Long,
    ): androidx.webgpu.GPUBuffer =
        ctx.device.createBuffer(
            GPUBufferDescriptor(
                size = size,
                usage = BufferUsage.CopySrc,
                mappedAtCreation = true,
            )
        )

    private fun writeIntsToMappedBuffer(buffer: androidx.webgpu.GPUBuffer, data: IntArray) {
        val bytes = ByteBuffer.allocateDirect(data.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        bytes.asIntBuffer().put(data)
        val mapped = buffer.getMappedRange(0L, bytes.capacity().toLong()).order(ByteOrder.nativeOrder())
        bytes.rewind()
        mapped.put(bytes)
        mapped.rewind()
        buffer.unmap()
    }
}
