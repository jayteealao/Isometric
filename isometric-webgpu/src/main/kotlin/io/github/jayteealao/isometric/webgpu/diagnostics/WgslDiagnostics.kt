package io.github.jayteealao.isometric.webgpu.diagnostics

import android.util.Log
import androidx.webgpu.CompilationMessageType
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.WebGpuException

internal object WgslDiagnostics {
    suspend fun logCompilation(module: GPUShaderModule, tag: String) {
        try {
            val info = module.getCompilationInfo()
            if (info.messages.isEmpty()) {
                Log.d(tag, "TINT: shader compiled with zero messages")
                return
            }
            for ((i, msg) in info.messages.withIndex()) {
                val typeName = CompilationMessageType.toString(msg.type)
                val line = "TINT[$i] type=$typeName line=${msg.lineNum}:${msg.linePos} off=${msg.offset} len=${msg.length}: ${msg.message}"
                when (msg.type) {
                    CompilationMessageType.Error -> Log.e(tag, line)
                    CompilationMessageType.Warning -> Log.w(tag, line)
                    else -> Log.d(tag, line)
                }
            }
        } catch (we: WebGpuException) {
            Log.e(tag, "TINT: getCompilationInfo() failed with WebGpuException", we)
            throw we
        }
    }
}
