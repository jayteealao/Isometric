package io.github.jayteealao.isometric.webgpu.sort

import androidx.webgpu.BufferBindingType
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferBindingLayout
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUComputePipeline
import androidx.webgpu.GPUComputePipelineDescriptor
import androidx.webgpu.GPUComputeState
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.MapMode
import androidx.webgpu.ShaderStage
import io.github.jayteealao.isometric.webgpu.GpuContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * GPU-accelerated radix sort for depth keys.
 *
 * Performs a 4-pass (8 bits/pass) radix sort on IEEE 754 float depth keys, producing
 * back-to-front sorted indices. Falls back to CPU sorting for small arrays (< [GPU_SORT_THRESHOLD]).
 *
 * ## Pipeline
 *
 * For each of the 4 passes (bitShift = 0, 8, 16, 24):
 * 1. Zero the histogram buffer
 * 2. Dispatch [RadixSortShader.COUNT_ENTRY_POINT] — count per-bucket occurrences
 * 3. Read back histogram, compute CPU-side exclusive prefix sum, re-upload
 * 4. Dispatch [RadixSortShader.SCATTER_ENTRY_POINT] — scatter keys to sorted positions
 * 5. Swap ping-pong buffers
 *
 * After 4 passes, read back the `originalIndex` field from sorted keys.
 *
 * @param ctx The GPU context providing device, queue, and event polling.
 */
class GpuDepthSorter(private val ctx: GpuContext) {

    private val shaderModule: GPUShaderModule by lazy {
        ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(
                shaderSourceWGSL = GPUShaderSourceWGSL(code = RadixSortShader.WGSL)
            )
        )
    }

    private var countPipeline: GPUComputePipeline? = null
    private var scatterPipeline: GPUComputePipeline? = null
    private var bindGroupLayout: GPUBindGroupLayout? = null

    /**
     * Sort depth keys on the GPU and return back-to-front indices.
     *
     * @param depthKeys One float depth key per face. Higher = further from viewer.
     * @return Indices into [depthKeys] in back-to-front (drawn-first) order.
     */
    suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray {
        if (depthKeys.size < GPU_SORT_THRESHOLD) {
            return cpuFallbackSort(depthKeys)
        }

        ensurePipelinesBuilt()

        val count = depthKeys.size
        val keyByteSize = (count * SORT_KEY_BYTES).toLong()

        // Prepare input data: interleave depth + originalIndex
        val inputBuffer = ByteBuffer.allocateDirect(count * SORT_KEY_BYTES)
            .order(ByteOrder.nativeOrder())
        for (i in depthKeys.indices) {
            inputBuffer.putFloat(depthKeys[i])
            inputBuffer.putInt(i)
        }
        inputBuffer.rewind()

        // Create GPU buffers — ping-pong pair + histogram + params + readback
        val gpuBufferA = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                size = keyByteSize,
            )
        )
        val gpuBufferB = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                size = keyByteSize,
            )
        )
        val histogramByteSize = (RADIX * 4).toLong()
        val histogramBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                size = histogramByteSize,
            )
        )
        val paramsBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Uniform or BufferUsage.CopyDst,
                size = 8L,
            )
        )
        val histogramReadback = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.MapRead or BufferUsage.CopyDst,
                size = histogramByteSize,
            )
        )
        val resultReadback = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.MapRead or BufferUsage.CopyDst,
                size = keyByteSize,
            )
        )

        // Upload input keys to buffer A
        ctx.queue.writeBuffer(gpuBufferA, 0L, inputBuffer)

        val workgroupCount = ceil(count.toFloat() / RadixSortShader.WORKGROUP_SIZE).toInt()

        // Four passes: bitShift = 0, 8, 16, 24
        var currentIn = gpuBufferA
        var currentOut = gpuBufferB

        for (pass in 0 until 4) {
            val bitShift = pass * 8

            // 1. Zero histogram buffer
            val zeroData = ByteBuffer.allocateDirect(RADIX * 4)
                .order(ByteOrder.nativeOrder())
            ctx.queue.writeBuffer(histogramBuffer, 0L, zeroData)

            // 2. Upload params
            val paramsData = ByteBuffer.allocateDirect(8)
                .order(ByteOrder.nativeOrder())
            paramsData.putInt(count)
            paramsData.putInt(bitShift)
            paramsData.rewind()
            ctx.queue.writeBuffer(paramsBuffer, 0L, paramsData)

            // 3. Dispatch count pass
            val countBindGroup = createBindGroup(currentIn, currentOut, histogramBuffer, paramsBuffer)
            val encoder1 = ctx.device.createCommandEncoder()
            val computePass1 = encoder1.beginComputePass()
            computePass1.setPipeline(countPipeline!!)
            computePass1.setBindGroup(0, countBindGroup)
            computePass1.dispatchWorkgroups(workgroupCount)
            computePass1.end()
            encoder1.copyBufferToBuffer(histogramBuffer, 0L, histogramReadback, 0L, histogramByteSize)
            ctx.queue.submit(arrayOf(encoder1.finish()))

            // 4. CPU prefix sum on histogram
            histogramReadback.mapAndAwait(MapMode.Read, 0L, histogramByteSize)
            val histogramData = histogramReadback.getConstMappedRange(0L, histogramByteSize)
            val histogramInts = IntArray(RADIX)
            histogramData.asIntBuffer().get(histogramInts)
            histogramReadback.unmap()

            // Exclusive prefix sum
            val prefixSums = IntArray(RADIX)
            var sum = 0
            for (i in 0 until RADIX) {
                prefixSums[i] = sum
                sum += histogramInts[i]
            }

            // Upload prefix-summed histogram back
            val prefixData = ByteBuffer.allocateDirect(RADIX * 4)
                .order(ByteOrder.nativeOrder())
            prefixData.asIntBuffer().put(prefixSums)
            prefixData.rewind()
            ctx.queue.writeBuffer(histogramBuffer, 0L, prefixData)

            // 5. Dispatch scatter pass
            val scatterBindGroup = createBindGroup(currentIn, currentOut, histogramBuffer, paramsBuffer)
            val encoder2 = ctx.device.createCommandEncoder()
            val computePass2 = encoder2.beginComputePass()
            computePass2.setPipeline(scatterPipeline!!)
            computePass2.setBindGroup(0, scatterBindGroup)
            computePass2.dispatchWorkgroups(workgroupCount)
            computePass2.end()
            ctx.queue.submit(arrayOf(encoder2.finish()))

            // 6. Swap ping-pong
            val temp = currentIn
            currentIn = currentOut
            currentOut = temp
        }

        // Read back sorted indices from currentIn (result of last pass)
        val finalEncoder = ctx.device.createCommandEncoder()
        finalEncoder.copyBufferToBuffer(currentIn, 0L, resultReadback, 0L, keyByteSize)
        ctx.queue.submit(arrayOf(finalEncoder.finish()))

        resultReadback.mapAndAwait(MapMode.Read, 0L, keyByteSize)
        val resultData = resultReadback.getConstMappedRange(0L, keyByteSize)
        val sortedIndices = IntArray(count)
        for (i in 0 until count) {
            resultData.position(i * SORT_KEY_BYTES + 4) // skip depth float, read originalIndex
            sortedIndices[i] = resultData.getInt()
        }
        resultReadback.unmap()

        // Cleanup GPU buffers
        gpuBufferA.destroy()
        gpuBufferB.destroy()
        histogramBuffer.destroy()
        paramsBuffer.destroy()
        histogramReadback.destroy()
        resultReadback.destroy()

        return sortedIndices
    }

    private fun ensurePipelinesBuilt() {
        if (countPipeline != null) return

        val layout = ctx.device.createBindGroupLayout(
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
                    GPUBindGroupLayoutEntry(
                        binding = 2,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
                    ),
                    GPUBindGroupLayoutEntry(
                        binding = 3,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Uniform),
                    ),
                )
            )
        )
        bindGroupLayout = layout

        val pipelineLayout = ctx.device.createPipelineLayout(
            GPUPipelineLayoutDescriptor(
                bindGroupLayouts = arrayOf(layout)
            )
        )

        countPipeline = ctx.device.createComputePipeline(
            GPUComputePipelineDescriptor(
                compute = GPUComputeState(
                    module = shaderModule,
                    entryPoint = RadixSortShader.COUNT_ENTRY_POINT,
                ),
                layout = pipelineLayout,
            )
        )

        scatterPipeline = ctx.device.createComputePipeline(
            GPUComputePipelineDescriptor(
                compute = GPUComputeState(
                    module = shaderModule,
                    entryPoint = RadixSortShader.SCATTER_ENTRY_POINT,
                ),
                layout = pipelineLayout,
            )
        )
    }

    private fun createBindGroup(
        keysIn: GPUBuffer,
        keysOut: GPUBuffer,
        histogram: GPUBuffer,
        params: GPUBuffer,
    ): GPUBindGroup {
        return ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = bindGroupLayout!!,
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, buffer = keysIn),
                    GPUBindGroupEntry(binding = 1, buffer = keysOut),
                    GPUBindGroupEntry(binding = 2, buffer = histogram),
                    GPUBindGroupEntry(binding = 3, buffer = params),
                )
            )
        )
    }

    companion object {
        /** Below this count, CPU sort is faster than GPU dispatch overhead. */
        const val GPU_SORT_THRESHOLD = 64

        private const val RADIX = 256
        private const val SORT_KEY_BYTES = 8 // f32 depth + u32 originalIndex

        /**
         * CPU fallback sort — descending by depth key (back-to-front).
         * Used when GPU is unavailable or array is too small.
         */
        fun cpuFallbackSort(keys: FloatArray): IntArray =
            keys.indices.sortedByDescending { keys[it] }.toIntArray()
    }
}
