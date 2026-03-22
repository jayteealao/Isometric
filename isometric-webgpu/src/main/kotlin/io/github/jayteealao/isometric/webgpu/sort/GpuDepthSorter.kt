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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * GPU-accelerated depth-key sort using a ping-pong bitonic network.
 *
 * The implementation intentionally avoids [androidx.webgpu.GPUQueue.writeBuffer] because that
 * JNI path has proven unstable on some devices. All CPU→GPU transfers use mapped staging buffers
 * and explicit copy commands instead.
 *
 * All Dawn API calls are confined to the GPU thread via [GpuContext.withGpu] to prevent
 * data races between GPU work and the processEvents polling loop.
 *
 * @param ctx The GPU context providing device, queue, and event polling.
 * @param onProgress Reports coarse milestones for sample/debug surfaces.
 */
class GpuDepthSorter(
    private val ctx: GpuContext,
    private val onProgress: (String, Int?) -> Unit = { _, _ -> },
) {
    private var shaderModule: GPUShaderModule? = null
    private var sortPipeline: GPUComputePipeline? = null
    private var bindGroupLayout: GPUBindGroupLayout? = null

    /**
     * Sort depth keys and return back-to-front indices.
     *
     * @param depthKeys One float depth key per face. Higher = further from viewer.
     * @return Indices into [depthKeys] in back-to-front (drawn-first) order.
     */
    suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray {
        require(depthKeys.isNotEmpty()) { "depthKeys must not be empty" }

        return withContext(NonCancellable) {
            ctx.checkHealthy()

            val count = depthKeys.size
            val paddedCount = nextPowerOfTwo(count)
            val sortBufferSize = (paddedCount * SORT_KEY_BYTES).toLong()
            onProgress("Packing $count depth keys", count)

            val packedKeys = packSortKeys(depthKeys, paddedCount)

            // All Dawn API calls run on the dedicated GPU thread.
            ctx.withGpu {
                ensurePipelineBuilt()

                val primaryBuffer = ctx.device.createBuffer(
                    GPUBufferDescriptor(
                        usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                        size = sortBufferSize,
                    )
                )
                val scratchBuffer = ctx.device.createBuffer(
                    GPUBufferDescriptor(
                        usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                        size = sortBufferSize,
                    )
                )
                val paramsBuffer = ctx.device.createBuffer(
                    GPUBufferDescriptor(
                        usage = BufferUsage.Uniform or BufferUsage.CopyDst,
                        size = PARAMS_BYTES.toLong(),
                    )
                )
                val resultReadback = ctx.device.createBuffer(
                    GPUBufferDescriptor(
                        usage = BufferUsage.MapRead or BufferUsage.CopyDst,
                        size = sortBufferSize,
                    )
                )

                try {
                    uploadBufferContents(
                        destination = primaryBuffer,
                        data = packedKeys,
                        size = sortBufferSize,
                        detail = "Uploaded $count depth keys",
                        count = count,
                    )

                    val workgroupCount =
                        ceil(paddedCount.toDouble() / GPUBitonicSortShader.WORKGROUP_SIZE).toInt()
                    var inputBuffer = primaryBuffer
                    var outputBuffer = scratchBuffer

                    var k = 2
                    while (k <= paddedCount) {
                        var j = k / 2
                        while (j > 0) {
                            ctx.checkHealthy()
                            val paramsData = createParamsBuffer(j, k, paddedCount)
                            val stageBindGroup = createBindGroup(inputBuffer, outputBuffer, paramsBuffer)
                            submitStage(
                                bindGroup = stageBindGroup,
                                paramsData = paramsData,
                                paramsBuffer = paramsBuffer,
                                workgroupCount = workgroupCount,
                            )

                            val swap = inputBuffer
                            inputBuffer = outputBuffer
                            outputBuffer = swap
                            j /= 2
                        }
                        k *= 2
                    }

                    onProgress("Completed compute passes for $count depth keys", count)
                    ctx.queue.onSubmittedWorkDone()

                    val readbackEncoder = ctx.device.createCommandEncoder()
                    readbackEncoder.copyBufferToBuffer(inputBuffer, 0L, resultReadback, 0L, sortBufferSize)
                    ctx.queue.submit(arrayOf(readbackEncoder.finish()))

                    onProgress("Copied GPU results for $count depth keys", count)
                    ctx.queue.onSubmittedWorkDone()

                    resultReadback.mapAndAwait(MapMode.Read, 0L, sortBufferSize)
                    val resultData = resultReadback.getConstMappedRange(0L, sortBufferSize)
                    val sortedIndices = extractSortedIndices(resultData, count, paddedCount)
                    resultReadback.unmap()
                    onProgress("Completed sort for $count depth keys", count)
                    sortedIndices
                } finally {
                    primaryBuffer.destroy()
                    scratchBuffer.destroy()
                    paramsBuffer.destroy()
                    resultReadback.destroy()
                }
            }
        }
    }

    private suspend fun ensurePipelineBuilt() {
        if (sortPipeline != null) return

        if (shaderModule == null) {
            shaderModule = ctx.device.createShaderModule(
                GPUShaderModuleDescriptor(
                    shaderSourceWGSL = GPUShaderSourceWGSL(code = GPUBitonicSortShader.WGSL)
                )
            )
        }

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

        sortPipeline = ctx.device.createComputePipelineAndAwait(
            GPUComputePipelineDescriptor(
                compute = GPUComputeState(
                    module = shaderModule!!,
                    entryPoint = GPUBitonicSortShader.SORT_ENTRY_POINT,
                ),
                layout = pipelineLayout,
            )
        )
        onProgress("Pipeline ready", null)
    }

    private suspend fun submitStage(
        bindGroup: GPUBindGroup,
        paramsData: ByteBuffer,
        paramsBuffer: GPUBuffer,
        workgroupCount: Int,
    ) {
        val paramsUpload = createMappedUploadBuffer(PARAMS_BYTES.toLong())
        try {
            writeToMappedBuffer(paramsUpload, paramsData, PARAMS_BYTES.toLong())
            val encoder = ctx.device.createCommandEncoder()
            encoder.copyBufferToBuffer(paramsUpload, 0L, paramsBuffer, 0L, PARAMS_BYTES.toLong())
            val computePass = encoder.beginComputePass()
            computePass.setPipeline(sortPipeline!!)
            computePass.setBindGroup(0, bindGroup)
            computePass.dispatchWorkgroups(workgroupCount)
            computePass.end()
            ctx.queue.submit(arrayOf(encoder.finish()))
        } finally {
            paramsUpload.destroy()
        }
    }

    private suspend fun uploadBufferContents(
        destination: GPUBuffer,
        data: ByteBuffer,
        size: Long,
        detail: String,
        count: Int,
    ) {
        val uploadBuffer = createMappedUploadBuffer(size)
        try {
            writeToMappedBuffer(uploadBuffer, data, size)
            val encoder = ctx.device.createCommandEncoder()
            encoder.copyBufferToBuffer(uploadBuffer, 0L, destination, 0L, size)
            ctx.queue.submit(arrayOf(encoder.finish()))
            ctx.queue.onSubmittedWorkDone()
            onProgress(detail, count)
        } finally {
            uploadBuffer.destroy()
        }
    }

    private fun createMappedUploadBuffer(size: Long): GPUBuffer =
        ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.CopySrc,
                size = size,
                mappedAtCreation = true,
            )
        )

    private fun writeToMappedBuffer(
        buffer: GPUBuffer,
        data: ByteBuffer,
        size: Long,
    ) {
        val target = buffer.getMappedRange(0L, size).order(ByteOrder.nativeOrder())
        val source = data.duplicate().order(ByteOrder.nativeOrder())
        source.rewind()
        target.put(source)
        target.rewind()
        buffer.unmap()
    }

    private fun createBindGroup(
        inputBuffer: GPUBuffer,
        outputBuffer: GPUBuffer,
        paramsBuffer: GPUBuffer,
    ): GPUBindGroup {
        return ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = bindGroupLayout!!,
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, buffer = inputBuffer),
                    GPUBindGroupEntry(binding = 1, buffer = outputBuffer),
                    GPUBindGroupEntry(binding = 2, buffer = paramsBuffer),
                )
            )
        )
    }

    companion object {
        private const val SORT_KEY_BYTES = 16
        private const val ORIGINAL_INDEX_OFFSET = 4
        private const val PARAMS_BYTES = 16
        private const val SENTINEL_INDEX = -1

        internal fun packSortKeys(depthKeys: FloatArray, paddedCount: Int): ByteBuffer {
            require(paddedCount >= depthKeys.size) {
                "paddedCount=$paddedCount must be >= depthKeys.size=${depthKeys.size}"
            }

            val inputData = ByteBuffer.allocateDirect(paddedCount * SORT_KEY_BYTES)
                .order(ByteOrder.nativeOrder())
            for (i in depthKeys.indices) {
                inputData.putFloat(depthKeys[i])
                inputData.putInt(i)
                inputData.putInt(0)
                inputData.putInt(0)
            }
            for (i in depthKeys.size until paddedCount) {
                inputData.putFloat(Float.NEGATIVE_INFINITY)
                inputData.putInt(SENTINEL_INDEX)
                inputData.putInt(0)
                inputData.putInt(0)
            }
            inputData.rewind()
            return inputData
        }

        internal fun extractSortedIndices(
            resultData: ByteBuffer,
            count: Int,
            paddedCount: Int,
        ): IntArray {
            val sortedIndices = IntArray(count)
            var outIndex = 0
            for (i in 0 until paddedCount) {
                resultData.position(i * SORT_KEY_BYTES + ORIGINAL_INDEX_OFFSET)
                val originalIndex = resultData.getInt()
                if (originalIndex != SENTINEL_INDEX) {
                    check(outIndex < count) {
                        "GPU sort produced more than $count indices"
                    }
                    sortedIndices[outIndex++] = originalIndex
                }
            }
            check(outIndex == count) {
                "GPU sort produced $outIndex indices for $count depth keys"
            }
            return sortedIndices
        }

        internal fun createParamsBuffer(j: Int, k: Int, paddedCount: Int): ByteBuffer =
            ByteBuffer.allocateDirect(PARAMS_BYTES)
                .order(ByteOrder.nativeOrder())
                .apply {
                    putInt(j)
                    putInt(k)
                    putInt(paddedCount)
                    putInt(0)
                    rewind()
                }

        internal fun nextPowerOfTwo(value: Int): Int {
            require(value > 0) { "value must be positive, got $value" }
            var result = 1
            while (result < value) {
                result = result shl 1
            }
            return result
        }
    }
}
