package io.github.jayteealao.isometric.webgpu.triangulation

import android.util.Log
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.webgpu.pipeline.SceneDataLayout
import io.github.jayteealao.isometric.webgpu.texture.TextureAtlasManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class AtlasUv(
    val scaleU: Float,
    val scaleV: Float,
    val offsetU: Float,
    val offsetV: Float,
)

internal class RenderCommandTriangulator {
    companion object {
        /**
         * u32 slots per vertex: pos(2) + color(4) + rawUV(2) + atlasRegion(4) + textureIndex(1) + pad(1) = 14.
         * rawUV is the pre-atlas UV (after user transform, before fract). atlasRegion is
         * (scaleU, scaleV, offsetU, offsetV) as a flat attribute. The fragment shader applies
         * `fract(rawUV) * atlasRegion.xy + atlasRegion.zw` per-fragment.
         */
        const val U32S_PER_VERTEX = 14
        const val BYTES_PER_VERTEX = U32S_PER_VERTEX * 4  // 56 bytes
    }

    data class PackedVertices(
        val buffer: ByteBuffer,
        val vertexCount: Int,
    )

    private var stagingBuffer: ByteBuffer? = null

    /**
     * Pack [scene] into a flat vertex buffer ready for the render pass.
     *
     * @param scene The prepared scene to triangulate.
     * @param atlasRegionResolver Optional resolver that returns the [TextureAtlasManager.AtlasRegion]
     *   for a given [RenderCommand]. When non-null, the atlas scale and offset are written into
     *   each vertex so the fragment shader's `fract(rawUV) * atlasScale + atlasOffset` formula
     *   samples the correct sub-region instead of collapsing to a single texel at the origin.
     *   Pass null (or omit) for untextured scenes; all atlas fields default to identity
     *   (scale=1, offset=0).
     */
    fun pack(
        scene: PreparedScene,
        atlasRegionResolver: ((RenderCommand) -> TextureAtlasManager.AtlasRegion?)? = null,
    ): PackedVertices {
        val vertexCount = countVertices(scene)
        val requiredBytes = vertexCount * BYTES_PER_VERTEX
        val buffer = ensureBuffer(requiredBytes)
        buffer.clear()

        for (command in scene.commands) {
            val pointCount = command.pointCount
            if (pointCount < 3) continue

            val r = (command.color.r / 255.0).toFloat()
            val g = (command.color.g / 255.0).toFloat()
            val b = (command.color.b / 255.0).toFloat()
            val a = (command.color.a / 255.0).toFloat()

            val region = atlasRegionResolver?.invoke(command)
            val atlasUv = AtlasUv(
                scaleU = region?.uvScale?.get(0) ?: 1f,
                scaleV = region?.uvScale?.get(1) ?: 1f,
                offsetU = region?.uvOffset?.get(0) ?: 0f,
                offsetV = region?.uvOffset?.get(1) ?: 0f,
            )

            // Convex fast-path: triangle fan from v0. Cheap O(n) winding check first.
            // Non-convex (e.g., Stairs zigzag side faces) requires ear-clipping to
            // avoid emitting triangles outside the polygon footprint — the fan would
            // cover the concave step-notch with bad geometry.
            if (isConvex(command, pointCount)) {
                val x0 = toNdcX(command.pointX(0), scene.width)
                val y0 = toNdcY(command.pointY(0), scene.height)
                for (i in 1 until pointCount - 1) {
                    writeVertex(buffer, x0, y0, r, g, b, a, atlasUv = atlasUv)
                    writeVertex(
                        buffer,
                        toNdcX(command.pointX(i), scene.width),
                        toNdcY(command.pointY(i), scene.height),
                        r, g, b, a,
                        atlasUv = atlasUv,
                    )
                    writeVertex(
                        buffer,
                        toNdcX(command.pointX(i + 1), scene.width),
                        toNdcY(command.pointY(i + 1), scene.height),
                        r, g, b, a,
                        atlasUv = atlasUv,
                    )
                }
            } else {
                val triangles = earClipTriangulate(command, pointCount)
                for (tri in triangles) {
                    val (a0, b0, c0) = tri
                    writeVertex(
                        buffer,
                        toNdcX(command.pointX(a0), scene.width),
                        toNdcY(command.pointY(a0), scene.height),
                        r, g, b, a,
                        atlasUv = atlasUv,
                    )
                    writeVertex(
                        buffer,
                        toNdcX(command.pointX(b0), scene.width),
                        toNdcY(command.pointY(b0), scene.height),
                        r, g, b, a,
                        atlasUv = atlasUv,
                    )
                    writeVertex(
                        buffer,
                        toNdcX(command.pointX(c0), scene.width),
                        toNdcY(command.pointY(c0), scene.height),
                        r, g, b, a,
                        atlasUv = atlasUv,
                    )
                }
            }
        }

        buffer.flip()
        return PackedVertices(buffer = buffer, vertexCount = vertexCount)
    }

    /**
     * O(n) convexity test using the sign of edge cross products in 2D screen space.
     * A polygon is convex iff all consecutive edge cross products have the same sign.
     * Collinear vertices (zero cross product) are treated as compatible with either sign.
     */
    private fun isConvex(command: RenderCommand, n: Int): Boolean {
        if (n < 4) return true  // triangle is always convex
        var sign = 0
        for (i in 0 until n) {
            val ax = command.pointX(i)
            val ay = command.pointY(i)
            val bx = command.pointX((i + 1) % n)
            val by = command.pointY((i + 1) % n)
            val cx = command.pointX((i + 2) % n)
            val cy = command.pointY((i + 2) % n)
            val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            if (cross > 0.0) {
                if (sign < 0) return false
                sign = 1
            } else if (cross < 0.0) {
                if (sign > 0) return false
                sign = -1
            }
        }
        return true
    }

    /**
     * Ear-clipping triangulation for simple (non-self-intersecting) polygons.
     * O(n²) algorithm: repeatedly find a vertex whose triangle (prev, v, next) is
     * a valid ear (correct winding direction and contains no other polygon vertex),
     * emit it, remove v, repeat until 3 vertices remain.
     *
     * Returns triangles as IntArray triples of original-polygon vertex indices.
     */
    private fun earClipTriangulate(command: RenderCommand, n: Int): List<IntArray> {
        val triangles = ArrayList<IntArray>(n - 2)
        // Working list of remaining polygon vertex indices (into command.pointX/Y).
        val remaining = ArrayList<Int>(n)
        for (i in 0 until n) remaining.add(i)

        // Determine the polygon's overall winding direction via signed area.
        // Positive shoelace → counter-clockwise; negative → clockwise.
        var doubleArea = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            doubleArea += command.pointX(i) * command.pointY(j) -
                command.pointX(j) * command.pointY(i)
        }
        val ccw = doubleArea > 0.0

        var guard = remaining.size * 2
        while (remaining.size > 3 && guard-- > 0) {
            var earFound = false
            for (k in remaining.indices) {
                val prev = remaining[(k + remaining.size - 1) % remaining.size]
                val cur = remaining[k]
                val next = remaining[(k + 1) % remaining.size]
                if (isEar(command, prev, cur, next, remaining, ccw)) {
                    triangles.add(intArrayOf(prev, cur, next))
                    remaining.removeAt(k)
                    earFound = true
                    break
                }
            }
            if (!earFound) {
                // M-1: degenerate or self-intersecting polygon — bail with what we have.
                // Log at WARN so callers can detect geometry authored outside the
                // UvGenerator contract (e.g. zero-area or self-intersecting paths).
                Log.w(
                    "RenderCommandTriangulator",
                    "Ear-clip aborted: no ear found at guard=$guard, remaining=${remaining.size}",
                )
                break
            }
        }
        if (remaining.size == 3) {
            triangles.add(intArrayOf(remaining[0], remaining[1], remaining[2]))
        }
        return triangles
    }

    private fun isEar(
        command: RenderCommand,
        prev: Int,
        cur: Int,
        next: Int,
        remaining: List<Int>,
        ccw: Boolean,
    ): Boolean {
        val ax = command.pointX(prev); val ay = command.pointY(prev)
        val bx = command.pointX(cur);  val by = command.pointY(cur)
        val cx = command.pointX(next); val cy = command.pointY(next)
        // Triangle must be wound the same direction as the polygon (convex vertex).
        val cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (ccw && cross <= 0.0) return false
        if (!ccw && cross >= 0.0) return false
        // No other remaining polygon vertex may lie strictly inside the triangle.
        for (idx in remaining) {
            if (idx == prev || idx == cur || idx == next) continue
            if (pointInTriangle(
                    command.pointX(idx), command.pointY(idx),
                    ax, ay, bx, by, cx, cy,
                )
            ) return false
        }
        return true
    }

    private fun pointInTriangle(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
        cx: Double, cy: Double,
    ): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val hasNeg = d1 < 0.0 || d2 < 0.0 || d3 < 0.0
        val hasPos = d1 > 0.0 || d2 > 0.0 || d3 > 0.0
        return !(hasNeg && hasPos)
    }

    private fun ensureBuffer(requiredBytes: Int): ByteBuffer {
        val existing = stagingBuffer
        if (existing != null && existing.capacity() >= requiredBytes) {
            return existing
        }

        val grownCapacity = maxOf(requiredBytes, (existing?.capacity() ?: BYTES_PER_VERTEX) * 2)
        return ByteBuffer.allocateDirect(grownCapacity)
            .order(ByteOrder.nativeOrder())
            .also { stagingBuffer = it }
    }

    private fun countVertices(scene: PreparedScene): Int {
        var vertexCount = 0
        for (command in scene.commands) {
            val pointCount = command.pointCount
            if (pointCount >= 3) {
                vertexCount += (pointCount - 2) * 3
            }
        }
        return vertexCount
    }

    private fun writeVertex(
        buffer: ByteBuffer,
        x: Float,
        y: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        u: Float = 0f,
        v: Float = 0f,
        atlasUv: AtlasUv = AtlasUv(scaleU = 1f, scaleV = 1f, offsetU = 0f, offsetV = 0f),
        textureIndex: Int = SceneDataLayout.NO_TEXTURE,
    ) {
        buffer.putFloat(x)
        buffer.putFloat(y)
        buffer.putFloat(r)
        buffer.putFloat(g)
        buffer.putFloat(b)
        buffer.putFloat(a)
        buffer.putFloat(u)
        buffer.putFloat(v)
        buffer.putFloat(atlasUv.scaleU)
        buffer.putFloat(atlasUv.scaleV)
        buffer.putFloat(atlasUv.offsetU)
        buffer.putFloat(atlasUv.offsetV)
        buffer.putInt(textureIndex)
        buffer.putInt(0)  // padding to reach 56 bytes / 14 u32s
    }

    private fun toNdcX(x: Double, width: Int): Float =
        ((x / width.toDouble()) * 2.0 - 1.0).toFloat()

    private fun toNdcY(y: Double, height: Int): Float =
        (1.0 - (y / height.toDouble()) * 2.0).toFloat()
}
