package io.fabianterhorst.isometric.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.fabianterhorst.isometric.*

/**
 * Android View for rendering isometric 3D scenes.
 * Refactored to use IsometricEngine from :isometric-core
 */
class IsometricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnItemClickListener {
        fun onClick(item: RenderCommand)
    }

    private val engine = IsometricEngine()
    private var listener: OnItemClickListener? = null

    private var renderOptions = RenderOptions.Default
    private var reverseSortForLookup = false
    private var touchRadiusLookup = false
    private var touchRadius = 8.0

    private var cachedScene: PreparedScene? = null

    /**
     * Enable/disable depth sorting
     */
    fun setSort(sort: Boolean) {
        renderOptions = renderOptions.copy(enableDepthSorting = sort)
        invalidate()
    }

    /**
     * Enable/disable back-face culling (improves performance)
     * Paths must be defined in counter-clockwise rotation order
     */
    fun setCull(cull: Boolean) {
        renderOptions = renderOptions.copy(enableBackfaceCulling = cull)
        invalidate()
    }

    /**
     * Enable/disable bounds checking (improves performance)
     */
    fun setBoundsCheck(boundsCheck: Boolean) {
        renderOptions = renderOptions.copy(enableBoundsChecking = boundsCheck)
        invalidate()
    }

    /**
     * Reverse the sort order when looking up which item was touched
     */
    fun setReverseSortForLookup(reverseSortForLookup: Boolean) {
        this.reverseSortForLookup = reverseSortForLookup
    }

    /**
     * Allow click lookup to consider a touch region defined by a circle
     */
    fun setTouchRadiusLookup(touchRadiusLookup: Boolean) {
        this.touchRadiusLookup = touchRadiusLookup
    }

    /**
     * Radius of circular touch region in screen pixels
     */
    fun setTouchRadius(touchRadius: Double) {
        this.touchRadius = touchRadius
    }

    /**
     * Set click listener
     */
    fun setClickListener(listener: OnItemClickListener?) {
        this.listener = listener
    }

    /**
     * Clear all items from the scene
     */
    fun clear() {
        engine.clear()
        cachedScene = null
        invalidate()
    }

    /**
     * Add a path to the scene
     */
    fun add(path: Path, color: IsoColor) {
        engine.add(path, color, null)
        cachedScene = null
        invalidate()
    }

    /**
     * Add a shape to the scene
     */
    fun add(shape: Shape, color: IsoColor) {
        engine.add(shape, color)
        cachedScene = null
        invalidate()
    }

    // Compatibility methods for old Color class
    fun add(path: Path, color: io.fabianterhorst.isometric.Color) {
        add(path, convertColor(color))
    }

    fun add(shape: Shape, color: io.fabianterhorst.isometric.Color) {
        add(shape, convertColor(color))
    }

    private fun convertColor(oldColor: io.fabianterhorst.isometric.Color): IsoColor {
        return IsoColor(oldColor.r, oldColor.g, oldColor.b, oldColor.a)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        // Prepare scene when size changes
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (cachedScene == null || cachedScene?.viewportWidth != width || cachedScene?.viewportHeight != height) {
            cachedScene = engine.prepare(width, height, renderOptions)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        cachedScene?.let { scene ->
            AndroidCanvasRenderer.renderIsometric(canvas, scene)
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (listener != null) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    cachedScene?.let { scene ->
                        val item = engine.findItemAt(
                            preparedScene = scene,
                            x = event.x.toDouble(),
                            y = event.y.toDouble(),
                            reverseSort = reverseSortForLookup,
                            useRadius = touchRadiusLookup,
                            radius = touchRadius
                        )

                        item?.let {
                            listener?.onClick(it)
                        }
                    }
                    performClick()
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
